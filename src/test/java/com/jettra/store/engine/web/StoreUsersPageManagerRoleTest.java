package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.web.page.StoreUsersPage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class StoreUsersPageManagerRoleTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreUsersPage usersPage;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private SystemUserRepository systemUserRepo;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_manager_role_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        authManager = new AuthManager(systemUserRepo);
        usersPage = new StoreUsersPage(engine, authManager);
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();

        // Provision manager user in system_db
        if (!systemUserRepo.existsByUsername("manager_user")) {
            systemUserRepo.save(SystemUser.create(
                "manager_user",
                "managerPassHash",
                "manager@jettra.io",
                "MANAGER",
                Set.of("*")
            ));
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityDbTestCleanup.purgeNonAdminTestUsers(userRepo, credRepo, systemUserRepo);
        SecurityContextHolder.clear();
        com.jettra.store.engine.test.TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. User with role MANAGER can access /users and admin row is rendered with Bloqueado and Protegido")
    void testManagerCanAccessUsersPageAndAdminIsProtectedInUI() throws IOException {
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("manager_user", "MANAGER", "Operations", Set.of("*")),
            true
        ));

        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must display user management UI
        assertTrue(body.contains("Database User Accounts"), "Manager must be able to view user accounts table");

        // The admin row must be blocked from editing and protected from revoking
        assertTrue(body.contains("Bloqueado"), "Admin edit action must be displayed as Bloqueado for MANAGER");
        assertTrue(body.contains("El usuario con perfil MANAGER no puede alterar al usuario ADMIN") || body.contains("Solo el usuario admin"),
            "Tooltip must inform that MANAGER cannot alter ADMIN");
        assertTrue(body.contains("Protegido"), "Admin delete action must be displayed as Protegido for MANAGER");
    }

    @JettraTest
    @DisplayName("2. User with role MANAGER is prohibited from altering the ADMIN user account")
    void testManagerCannotAlterAdminUser() throws IOException {
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("manager_user", "MANAGER", "Operations", Set.of("*")),
            true
        ));

        SystemUser beforeAttempt = systemUserRepo.findByUsername("admin").orElseThrow();
        String originalEmail = beforeAttempt.email();

        // Attempt to edit admin user profile
        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        exchange.setRequestBody("action=update_user&username=admin&email=hacked_admin@jettra.io&role=READ_WRITE");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("Operación denegada") && body.contains("ADMIN"),
            "Alert banner must report that MANAGER cannot alter ADMIN user");

        // Verify admin profile was NOT altered in system_db
        SystemUser admin = systemUserRepo.findByUsername("admin").orElseThrow();
        assertEquals("ADMIN", admin.role(), "Admin role must remain ADMIN");
        assertEquals(originalEmail, admin.email(), "Admin email must remain unmodified");
    }

    @JettraTest
    @DisplayName("3. User with role MANAGER cannot delete the ADMIN user account")
    void testManagerCannotDeleteAdminUser() throws IOException {
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("manager_user", "MANAGER", "Operations", Set.of("*")),
            true
        ));

        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        exchange.setRequestBody("action=delete_user&username=admin");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("admin no puede ser revocado") || body.contains("Operación denegada"),
            "Must reject deletion of admin account by MANAGER");

        assertTrue(systemUserRepo.existsByUsername("admin"), "Admin must still exist in system_db");
    }

    @JettraTest
    @DisplayName("4. User with role MANAGER cannot elevate users or assign the role ADMIN")
    void testManagerCannotAssignAdminRole() throws IOException {
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("manager_user", "MANAGER", "Operations", Set.of("*")),
            true
        ));

        // Attempt to create user with ADMIN role
        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        exchange.setRequestBody("action=create_user&username=fake_admin&email=fake@jettra.io&password=pass123&role=ADMIN&target_db=*");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("Operación denegada") && body.contains("ADMIN"),
            "Must state that MANAGER cannot assign ADMIN role");

        assertFalse(systemUserRepo.existsByUsername("fake_admin"), "Account with unauthorized ADMIN role must NOT be created");
    }

    @JettraTest
    @DisplayName("5. User with role MANAGER can administer regular users (create, update profile, and delete)")
    void testManagerCanAdministerRegularUsers() throws IOException {
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("manager_user", "MANAGER", "Operations", Set.of("*")),
            true
        ));

        String workerUser = "worker_ops_" + UUID.randomUUID().toString().substring(0, 5);

        // 1. Create regular user with role READ_WRITE
        TestHttpExchange createEx = new TestHttpExchange("POST", "/users");
        createEx.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        createEx.setRequestBody("action=create_user&username=" + workerUser + "&email=worker@jettra.io&password=workerPass123&role=READ_WRITE&target_db=*");

        usersPage.handle(createEx);

        assertEquals(200, createEx.getResponseCode());
        assertTrue(createEx.getResponseBodyAsString().contains("provisioned") || createEx.getResponseBodyAsString().contains("exitosamente"),
            "Manager must successfully provision regular user");

        Optional<SystemUser> workerOpt = systemUserRepo.findByUsername(workerUser);
        assertTrue(workerOpt.isPresent(), "Worker must exist in system_db");
        assertEquals("READ_WRITE", workerOpt.get().role());

        // 2. Update regular user profile (e.g. change role to READ_ONLY)
        TestHttpExchange updateEx = new TestHttpExchange("POST", "/users");
        updateEx.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        updateEx.setRequestBody("action=update_user&username=" + workerUser + "&email=worker_new@jettra.io&role=READ_ONLY&active=true&target_db=*");

        usersPage.handle(updateEx);

        assertEquals(200, updateEx.getResponseCode());
        assertTrue(updateEx.getResponseBodyAsString().contains("updated successfully"), "Manager must successfully update regular user");
        assertEquals("READ_ONLY", systemUserRepo.findByUsername(workerUser).get().role());

        // 3. Delete regular user
        TestHttpExchange deleteEx = new TestHttpExchange("POST", "/users");
        deleteEx.getRequestHeaders().set("Cookie", "username=manager_user; role=MANAGER");
        deleteEx.setRequestBody("action=delete_user&username=" + workerUser);

        usersPage.handle(deleteEx);

        assertEquals(200, deleteEx.getResponseCode());
        assertTrue(deleteEx.getResponseBodyAsString().contains("eliminado exitosamente"),
            "Manager must be authorized to delete regular non-admin user");

        assertFalse(systemUserRepo.existsByUsername(workerUser), "Worker user must be removed from system_db");
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
            this.requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        public String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }
}
