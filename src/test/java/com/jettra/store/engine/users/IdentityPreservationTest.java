package com.jettra.store.engine.users;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.exception.UnsupportedUserDeletionException;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.server.JettraShellCommandDispatcher;
import com.jettra.store.engine.users.commands.UserAdminCommand;
import com.jettra.store.engine.users.commands.UserAdminCommand.CommandSource;
import com.jettra.store.engine.users.commands.UserAdminPipeline;
import com.jettra.store.engine.web.SecurityDbTestCleanup;
import com.jettra.store.engine.web.StoreDatabasesPage;
import com.jettra.store.engine.web.StoreUsersPage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Rigorous integration and domain test suite for JettraDB's Identity Preservation Policy.
 * Validates:
 * 1. Physical user deletion veto across Repository level (delete, deleteByUsername).
 * 2. Command Pipeline interception vetoing DeleteUserAttemptCommand via pattern matching.
 * 3. Client Driver REST JSON deletion attempts return HTTP 400 with USER_DELETION_PROHIBITED code.
 * 4. Shell CLI commands ('DROP USER', 'DELETE USER', 'user delete') are intercepted and vetoed.
 * 5. Web UI (/users) deletion attempts are blocked with clear feedback and preserve user entities.
 * 6. Explicit separation between database de-association (/databases) and identity preservation.
 * 7. Safe account deactivation vs physical destruction.
 */
@NotRequiresRunningServer
public class IdentityPreservationTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private StoreUsersPage usersPage;
    private StoreDatabasesPage databasesPage;
    private UserAdminPipeline pipeline;
    private JettraShellCommandDispatcher shellDispatcher;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_identity_preservation_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl();
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        authManager = new AuthManager(systemUserRepo);

        usersPage = new StoreUsersPage(engine, authManager, systemUserRepo, userRepo, credRepo, null);
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);
        pipeline = new UserAdminPipeline(systemUserRepo, userRepo);
        shellDispatcher = new JettraShellCommandDispatcher(pipeline);

        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));

        // Baseline admin preservation
        if (!systemUserRepo.existsByUsername("admin")) {
            systemUserRepo.save(SystemUser.create(
                "admin",
                SystemUserRepositoryImpl.hashPassword("admin"),
                "admin@jettra.io",
                "DB_ADMIN",
                Set.of("*")
            ));
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityDbTestCleanup.purgeNonAdminTestUsers(userRepo, credRepo, systemUserRepo);
        SecurityContextHolder.clear();
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @JettraTest
    @DisplayName("1. Repository level: Physical deletion of any user via delete() or deleteByUsername() strictly throws UnsupportedUserDeletionException")
    void testRepositoryLevelPhysicalDeletionProhibited() {
        String username = "preserved_analyst";
        SystemUser user = SystemUser.create(username, "hash123", "analyst@jettra.io", "READ_WRITE", Set.of("analytics_db"));
        systemUserRepo.save(user);
        assertTrue(systemUserRepo.existsByUsername(username));

        // 1. Calling delete(UUID) must throw UnsupportedUserDeletionException
        UnsupportedUserDeletionException exUuid = null;
        try {
            systemUserRepo.delete(user.id());
        } catch (UnsupportedUserDeletionException e) {
            exUuid = e;
        }
        assertNotNull(exUuid, "systemUserRepo.delete(UUID) must throw UnsupportedUserDeletionException");
        assertEquals("USER_DELETION_PROHIBITED", exUuid.getErrorCode());
        assertTrue(exUuid.getMessage().contains(username));

        // 2. Calling deleteByUsername(String) must throw UnsupportedUserDeletionException
        UnsupportedUserDeletionException exName = null;
        try {
            systemUserRepo.deleteByUsername(username);
        } catch (UnsupportedUserDeletionException e) {
            exName = e;
        }
        assertNotNull(exName, "systemUserRepo.deleteByUsername(String) must throw UnsupportedUserDeletionException");
        assertEquals("USER_DELETION_PROHIBITED", exName.getErrorCode());

        // 3. Entity must remain completely intact in system_db
        assertTrue(systemUserRepo.existsByUsername(username), "User entity must permanently persist in system_db");
        Optional<SystemUser> loaded = systemUserRepo.findByUsername(username);
        assertTrue(loaded.isPresent());
        assertEquals(user.id(), loaded.get().id());
    }

    @JettraTest
    @DisplayName("2. Command Pipeline: IdentityPreservationInterceptor vetoes DeleteUserAttemptCommand across all sources")
    void testCommandPipelineVetoesDeleteCommand() {
        for (CommandSource source : CommandSource.values()) {
            UserAdminCommand deleteCmd = new UserAdminCommand.DeleteUserAttemptCommand(
                "operator_target",
                UUID.randomUUID(),
                source,
                "Testing pipeline veto for source: " + source
            );

            UnsupportedUserDeletionException thrown = null;
            try {
                pipeline.execute(deleteCmd);
            } catch (UnsupportedUserDeletionException e) {
                thrown = e;
            }

            assertNotNull(thrown, "Pipeline must veto deletion for channel: " + source);
            assertEquals("USER_DELETION_PROHIBITED", thrown.getErrorCode());
            assertEquals(source.name(), thrown.getChannelSource());
            assertTrue(thrown.getMessage().contains("Identity preservation policy violation"));
        }
    }

    @JettraTest
    @DisplayName("3. Client Driver: Direct JSON deletion POST request returns HTTP 400 with USER_DELETION_PROHIBITED code")
    void testClientDriverJsonDeletionRequestRejected() throws IOException {
        String testUser = "driver_client_user";
        systemUserRepo.save(SystemUser.create(testUser, "hash", "driver@jettra.io", "READ_WRITE", Set.of("finance_db")));

        // Test 1: Native JSON body payload
        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Accept", "application/json");
        exchange.getRequestHeaders().set("Content-Type", "application/json");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody(String.format("{\"action\":\"delete_user\",\"username\":\"%s\"}", testUser));

        usersPage.handle(exchange);

        assertEquals(400, exchange.getResponseCode(), "Driver deletion attempt must be rejected with HTTP 400");
        String responseBody = exchange.getResponseBodyAsString();
        assertTrue(responseBody.contains("USER_DELETION_PROHIBITED"), "Must return USER_DELETION_PROHIBITED error code");
        assertTrue(responseBody.contains("Identity preservation policy violation"), "Must explain policy violation");

        // Verify user remains in system_db
        assertTrue(systemUserRepo.existsByUsername(testUser), "User must not be removed by driver request");
    }

    @JettraTest
    @DisplayName("4. Shell CLI: Commands 'DROP USER', 'DELETE USER', and 'user delete' are intercepted and vetoed")
    void testShellCliCommandsVetoed() {
        String[] dropCommands = {
            "DROP USER security_operator",
            "delete user security_operator",
            "rm user security_operator",
            "user delete security_operator",
            "user drop security_operator"
        };

        for (String cmd : dropCommands) {
            UnsupportedUserDeletionException ex = null;
            try {
                shellDispatcher.executeCommand(cmd);
            } catch (UnsupportedUserDeletionException e) {
                ex = e;
            }

            assertNotNull(ex, "Shell command '" + cmd + "' must throw UnsupportedUserDeletionException");
            assertEquals("SHELL_CLI", ex.getChannelSource());
            assertEquals("USER_DELETION_PROHIBITED", ex.getErrorCode());
        }
    }

    @JettraTest
    @DisplayName("5. Web UI: Action delete_user on /users is blocked, displays informative alert, and preserves user entity")
    void testWebUiDeletionActionBlockedWithPreservationFeedback() throws IOException {
        String username = "web_ui_target";
        UUID userId = UUID.randomUUID();
        SystemUser user = new SystemUser(userId, username, "hash", "web@jettra.io", "READ_ONLY", true, Set.of("reports_db"), Instant.now(), Instant.now());
        systemUserRepo.save(user);

        JUser legacyUser = new JUser(userId, username, "reports_db", "web@jettra.io", "+123", true, Set.of(new JRole(UUID.randomUUID(), "READ_ONLY", true)), Set.of("reports_db"));
        userRepo.save(legacyUser);

        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=delete_user&user_id=" + userId.toString());

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("La eliminación física de usuarios está estrictamente prohibida en JettraDB"),
            "Alert banner must explain that physical user deletion is prohibited");

        // Identity must remain intact across repositories
        assertTrue(systemUserRepo.existsByUsername(username), "system_db must preserve user identity");
        assertTrue(userRepo.findById(userId).isPresent(), "userRepo must preserve user identity");
    }

    @JettraTest
    @DisplayName("6. Database De-association: Deselecting user on /databases revokes target database without deleting user")
    void testDatabaseDeassociationRevokesScopeWithoutDeletingUser() throws IOException {
        String username = "scoped_developer";
        UUID uId = UUID.randomUUID();
        // User has access to both 'target_analytics_db' and 'preserve_data_db'
        SystemUser user = new SystemUser(uId, username, "passHash", "dev@jettra.io", "READ_WRITE", true,
            Set.of("target_analytics_db", "preserve_data_db"), Instant.now(), Instant.now());
        systemUserRepo.save(user);

        // Perform mass sync on 'target_analytics_db': user is NOT in the selected list (deselection)
        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&target_db=target_analytics_db&assigned_users=admin");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());

        // 1. User MUST STILL EXIST in system_db (Identity Preserved!)
        Optional<SystemUser> loaded = systemUserRepo.findByUsername(username);
        assertTrue(loaded.isPresent(), "User entity MUST NOT be deleted from system_db");

        // 2. 'target_analytics_db' has been revoked
        assertFalse(loaded.get().assignedDatabases().contains("target_analytics_db"), "Target database must be revoked");

        // 3. 'preserve_data_db' is retained
        assertTrue(loaded.get().assignedDatabases().contains("preserve_data_db"), "Other databases must be preserved");

        // 4. Account remains active and operational
        assertTrue(loaded.get().active(), "User account status must remain active");
    }

    @JettraTest
    @DisplayName("7. Safe Deactivation: Toggling account status deactivates access while preserving identity record")
    void testSafeAccountStatusDeactivation() {
        String username = "contractor_temp";
        SystemUser user = SystemUser.create(username, "hash", "temp@jettra.io", "READ_WRITE", Set.of("corp_db"));
        systemUserRepo.save(user);

        // Deactivate user via command pipeline
        UserAdminCommand toggleCmd = new UserAdminCommand.ToggleUserStatusCommand(
            username,
            false,
            CommandSource.WEB_UI
        );
        UserAdminPipeline.UserCommandResult res = pipeline.execute(toggleCmd);

        assertTrue(res.success());
        assertTrue(res.message().contains("INACTIVE"));

        // User still exists in system_db with active = false
        SystemUser deactivated = systemUserRepo.findByUsername(username).orElseThrow();
        assertFalse(deactivated.active(), "Account should now be inactive");
        assertEquals(user.id(), deactivated.id(), "Identity ID must not change");
        assertTrue(deactivated.assignedDatabases().contains("corp_db"), "Scope assignments are preserved for auditing");
    }

    private static class TestHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = -1;

        TestHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        void setRequestBody(String body) {
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
