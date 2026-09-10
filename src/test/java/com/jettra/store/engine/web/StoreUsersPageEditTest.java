package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.server.autentification.repository.JettraSecurityDBInitializer;
import com.jettra.store.engine.exception.ImmutableAccountException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Test Suite validating:
 * 1. User detail retrieval endpoint (GET /users?action=get_user) for hydration
 * 2. In-place user profile, RBAC role, and multi-database assignment updates (POST action=update_user)
 * 3. Atomic persistence of updated roles and assigned database collections
 * 4. Preservation of existing password hashes and credential state during updates
 * 5. Explicit password reset capabilities without identity corruption
 * 6. Self-lockout prevention guarding administrator accounts against demotion/deactivation
 * 7. UI rendering of the Edit action trigger and JettraUserEditModal dialog
 */
@NotRequiresRunningServer
public class StoreUsersPageEditTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreUsersPage usersPage;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_user_edit_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        authManager = new AuthManager();
        usersPage = new StoreUsersPage(engine, authManager);
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();

        // Ensure security directory exists and setup admin context
        new File("db/securitydb").mkdirs();
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityDbTestCleanup.purgeNonAdminTestUsers(userRepo, credRepo);
        SecurityContextHolder.clear();
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("Should hydrate user details via JSON endpoint (GET /users?action=get_user)")
    void testHydrateUserDetailsViaJsonEndpoint() throws IOException {
        String username = "carlos_hydrate_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_ONLY", true));
        Set<String> dbs = Set.of("analytics_db", "warehouse_db");

        JUser user = new JUser(uId, username, "analytics_db, warehouse_db", "carlos@test.com", "+123", true, roles, dbs);
        userRepo.save(user);

        MockHttpExchange getExchange = new MockHttpExchange("GET", "/users?action=get_user&username=" + username);
        usersPage.handle(getExchange);

        assertEquals(200, getExchange.getResponseCode(), "Should return 200 OK for valid user");
        String responseJson = getExchange.getResponseBodyAsString();

        assertTrue(responseJson.contains("\"status\":\"SUCCESS\""), "Response must indicate SUCCESS status");
        assertTrue(responseJson.contains("\"username\":\"" + username + "\""), "Response must contain username");
        assertTrue(responseJson.contains("\"email\":\"carlos@test.com\""), "Response must contain email");
        assertTrue(responseJson.contains("\"role\":\"READ_ONLY\""), "Response must contain role");
        assertTrue(responseJson.contains("analytics_db"), "Response must contain assigned databases");
        assertTrue(responseJson.contains("warehouse_db"), "Response must contain assigned databases");

        // Clean up
        userRepo.delete(uId);
    }

    @JettraTest
    @DisplayName("Should atomically update roles, email, and multi-database access via POST action=update_user")
    void testAtomicUserUpdateRolesAndDatabases() {
        String username = "marina_update_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> initialRoles = Set.of(new JRole(UUID.randomUUID(), "READ_ONLY", true));
        Set<String> initialDbs = Set.of("tenant_one");

        JUser user = new JUser(uId, username, "tenant_one", "marina@old.io", "+123", true, initialRoles, initialDbs);
        userRepo.save(user);

        // Provision credentials
        String originalHash = JettraSecurityDBInitializer.hashPassword("secret123");
        JCredential cred = new JCredential(UUID.randomUUID(), user, username, originalHash, true, Instant.now());
        credRepo.save(cred);

        // Submit edit via POST
        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "update_user");
        formParams.put("user_id", uId.toString());
        formParams.put("username", username);
        formParams.put("email", "marina.lead@company.com");
        formParams.put("role", "MANAGER");
        formParams.put("active", "true");
        formParams.put("target_dbs", "tenant_one, tenant_two, reporting_db");

        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        usersPage.buildContent(postExchange, formParams, "dark");

        // Verify user in repository
        Optional<JUser> updatedOpt = userRepo.findByUsername(username);
        assertTrue(updatedOpt.isPresent(), "User should exist after update");
        JUser updated = updatedOpt.get();

        assertEquals("marina.lead@company.com", updated.email(), "Email should be updated");
        assertTrue(updated.jRoles().stream().anyMatch(r -> "MANAGER".equalsIgnoreCase(r.name())), "Role should be updated to MANAGER");
        assertTrue(updated.isAuthorizedForDatabase("tenant_one"), "Should have access to tenant_one");
        assertTrue(updated.isAuthorizedForDatabase("tenant_two"), "Should have access to tenant_two");
        assertTrue(updated.isAuthorizedForDatabase("reporting_db"), "Should have access to reporting_db");
        assertFalse(updated.isAuthorizedForDatabase("secret_db"), "Should NOT have access to secret_db");

        // Clean up
        userRepo.delete(uId);
        credRepo.delete(cred.id());
    }

    @JettraTest
    @DisplayName("Should preserve existing password hash when no new password is provided")
    void testPreservePasswordHashDuringUpdate() {
        String username = "dave_preserve_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));
        Set<String> dbs = Set.of("db_initial");

        JUser user = new JUser(uId, username, "db_initial", "dave@corp.io", "+123", true, roles, dbs);
        userRepo.save(user);

        String initialHash = JettraSecurityDBInitializer.hashPassword("originalPassword99");
        UUID credId = UUID.randomUUID();
        JCredential cred = new JCredential(credId, user, username, initialHash, true, Instant.now());
        credRepo.save(cred);

        // Submit update with EMPTY password
        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "update_user");
        formParams.put("username", username);
        formParams.put("email", "dave.updated@corp.io");
        formParams.put("role", "DB_ADMIN");
        formParams.put("target_dbs", "db_initial, db_new");
        formParams.put("password", ""); // Empty: password must be preserved

        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        usersPage.buildContent(postExchange, formParams, "dark");

        // Check credentials in repository
        Optional<JCredential> preservedCred = credRepo.findById(credId);
        assertTrue(preservedCred.isPresent(), "Credential must exist");
        assertEquals(initialHash, preservedCred.get().passwordHash(), "Password hash must NOT be modified or wiped");
        assertEquals("dave.updated@corp.io", preservedCred.get().jUser().email(), "Embedded user in credential should reflect updated email");

        // Clean up
        userRepo.delete(uId);
        credRepo.delete(credId);
    }

    @JettraTest
    @DisplayName("Should update password when explicit new password is provided")
    void testExplicitPasswordReset() {
        String username = "elena_reset_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));

        JUser user = new JUser(uId, username, "*", "elena@corp.io", "+123", true, roles, Set.of("*"));
        userRepo.save(user);

        String initialHash = JettraSecurityDBInitializer.hashPassword("oldPassword1");
        UUID credId = UUID.randomUUID();
        JCredential cred = new JCredential(credId, user, username, initialHash, true, Instant.now());
        credRepo.save(cred);
        authManager.register(username, "oldPassword1");

        // Submit update with NEW password
        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "update_user");
        formParams.put("username", username);
        formParams.put("role", "READ_WRITE");
        formParams.put("password", "newSuperSecret2026");

        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        usersPage.buildContent(postExchange, formParams, "dark");

        Optional<JCredential> updatedCred = credRepo.findById(credId);
        assertTrue(updatedCred.isPresent(), "Credential must exist");
        String expectedNewHash = JettraSecurityDBInitializer.hashPassword("newSuperSecret2026");
        assertEquals(expectedNewHash, updatedCred.get().passwordHash(), "Password hash must be updated to new SHA-256 hash");

        // Verify authManager accepts new password
        assertTrue(authManager.authenticate(username, "newSuperSecret2026"), "New password should authenticate in AuthManager");

        // Clean up
        userRepo.delete(uId);
        credRepo.delete(credId);
    }

    @JettraTest
    @DisplayName("Should prevent self-lockout when admin edits their own account")
    void testSelfLockoutPrevention() {
        String adminUsername = "admin_lockout_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> adminRole = Set.of(new JRole(UUID.randomUUID(), "DB_ADMIN", true));

        JUser adminUser = new JUser(uId, adminUsername, "*", "admin@corp.io", "+123", true, adminRole, Set.of("*"));
        userRepo.save(adminUser);

        // Mock current logged in principal as this admin
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of(adminUsername, "ADMIN", "Security", Set.of("*")),
            true
        ));
        MockHttpExchange exchange = new MockHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=" + adminUsername + "; jettra_user=" + adminUsername + "; jettra_role=ADMIN");

        // Attempt 1: Demote self to READ_ONLY
        Map<String, String> demoteParams = new HashMap<>();
        demoteParams.put("action", "update_user");
        demoteParams.put("username", adminUsername);
        demoteParams.put("role", "READ_ONLY");

        var widget = usersPage.buildContent(exchange, demoteParams, "dark");
        String html = widget.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(html.contains("Self-lockout prevented"), "Must display self-lockout prevented alert banner");

        // Verify admin role was NOT removed
        JUser checkUser = userRepo.findByUsername(adminUsername).get();
        assertTrue(checkUser.jRoles().stream().anyMatch(r -> "DB_ADMIN".equalsIgnoreCase(r.name())), "Admin role must remain intact");

        // Attempt 2: Deactivate self
        Map<String, String> deactivateParams = new HashMap<>();
        deactivateParams.put("action", "update_user");
        deactivateParams.put("username", adminUsername);
        deactivateParams.put("role", "DB_ADMIN");
        deactivateParams.put("active", "false");

        var widget2 = usersPage.buildContent(exchange, deactivateParams, "dark");
        String html2 = widget2.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertTrue(html2.contains("Self-lockout prevented"), "Must prevent self deactivation");
        assertTrue(userRepo.findByUsername(adminUsername).get().active(), "Account must remain ACTIVE");

        // Clean up
        userRepo.delete(uId);
    }

    @JettraTest
    @DisplayName("Should render Edit button and JettraUserEditModal with immutable identity and MultiSelect")
    void testEditActionAndModalUIRendering() {
        String username = "frank_ui_" + UUID.randomUUID().toString().substring(0, 6);
        UUID uId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));
        Set<String> dbs = Set.of("orders_db", "inventory_db");

        JUser user = new JUser(uId, username, "orders_db, inventory_db", "frank@ui.com", "+123", true, roles, dbs);
        userRepo.save(user);

        MockHttpExchange exchange = new MockHttpExchange("GET", "/users");
        var uiWidget = usersPage.buildUI(exchange, Map.of(), "dark");
        String html = uiWidget.render(io.jettra.flux.theme.Themes.FlatTheme());

        // 1. Edit button in table row
        assertTrue(html.contains("Edit"), "Table action column must render Edit button");
        assertTrue(html.contains("openEditUser"), "Edit button must have openEditUser dispatch handler");
        assertTrue(html.contains(username), "Must render username in table");

        // 2. JettraUserEditModal container and header
        assertTrue(html.contains("id=\"editUserModal\""), "Must contain editUserModal overlay element");
        assertTrue(html.contains("Edit User Profile & Security Permissions"), "Must render edit modal title");
        assertTrue(html.contains("SECURITY AUDITED"), "Must render security audited badge");

        // 3. Form elements
        assertTrue(html.contains("id=\"editUserModal_username\""), "Must contain username input");
        assertTrue(html.contains("readonly"), "Username input must be locked / readonly");
        assertTrue(html.contains("fas fa-lock"), "Username input must feature lock icon");
        assertTrue(html.contains("id=\"editUserModal_email\""), "Must contain email input");
        assertTrue(html.contains("id=\"editUserModal_role\""), "Must contain role selector");
        assertTrue(html.contains("id=\"editUserModal_active\""), "Must contain active status selector");
        assertTrue(html.contains("id=\"editUserModal_password\""), "Must contain password reset input");

        // 4. Embedded MultiSelect
        assertTrue(html.contains("id=\"editUserModal_dbs\""), "Must contain embedded MultiSelect container");
        assertTrue(html.contains("Authorized Databases Access"), "Must render MultiSelect label");

        // 5. Submit and Cancel Buttons
        assertTrue(html.contains("Guardar Cambios"), "Must render Guardar Cambios submit button");
        assertTrue(html.contains("Cancelar"), "Must render Cancelar dismiss button");

        // Clean up
        userRepo.delete(uId);
    }

    private JUser ensureAdminUser() {
        Optional<JUser> adminOpt = userRepo.findByUsername("admin");
        if (adminOpt.isPresent()) {
            return adminOpt.get();
        }
        UUID adminId = UUID.randomUUID();
        JRole adminRole = new JRole(UUID.randomUUID(), "ADMIN", true);
        JUser adminUser = new JUser(adminId, "admin", "*", "admin@jettra.io", "+000000", true, Set.of(adminRole), Set.of("*"));
        userRepo.save(adminUser);
        credRepo.save(new JCredential(UUID.randomUUID(), adminUser, "admin", JettraSecurityDBInitializer.hashPassword("admin123"), true, Instant.now()));
        return adminUser;
    }

    @JettraTest
    @DisplayName("Should strictly block revocation of the admin account in repository and web controller")
    void testAdminAccountRevocationBlocked() {
        JUser admin = ensureAdminUser();

        // 1. Controller test: POST action=delete_user with admin id
        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        Map<String, String> formParams = Map.of(
            "action", "delete_user",
            "user_id", admin.id().toString()
        );
        var widget = usersPage.buildContent(postExchange, formParams, "dark");
        String html = widget.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(html.contains("El usuario admin no puede ser revocado."), "Alert must state that admin cannot be revoked");
        assertTrue(userRepo.findByUsername("admin").isPresent(), "Admin user must still exist in repository");

        // 2. Repository-level test: userRepo.delete(adminId) must throw ImmutableAccountException
        boolean exceptionThrown = false;
        try {
            userRepo.delete(admin.id());
        } catch (io.jettra.server.autentification.exception.ImmutableAccountException e) {
            exceptionThrown = true;
            assertTrue(e.getMessage().contains("El usuario admin no puede ser revocado."));
        }
        assertTrue(exceptionThrown, "userRepo.delete must throw ImmutableAccountException when deleting admin");
        assertTrue(userRepo.findByUsername("admin").isPresent(), "Admin user must remain in repository");
    }

    @JettraTest
    @DisplayName("Should reject modification of admin account when caller is a different user")
    void testAdminModificationByOtherAdminBlocked() {
        JUser admin = ensureAdminUser();
        String originalEmail = admin.email();

        // Session caller is 'supervisor' (another administrator)
        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        postExchange.getRequestHeaders().set("Cookie", "username=supervisor");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("supervisor", "ADMIN", "Operations", Set.of("*")),
            true
        ));

        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "update_user");
        formParams.put("user_id", admin.id().toString());
        formParams.put("username", "admin");
        formParams.put("email", "hacked_admin@test.com");
        formParams.put("role", "READ_ONLY");

        var widget = usersPage.buildContent(postExchange, formParams, "dark");
        String html = widget.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(html.contains("Solo el usuario admin activo puede modificar el perfil de admin"),
            "Alert must reject non-admin attempt to modify admin");

        // Verify admin record remained unchanged
        Optional<JUser> refreshedOpt = userRepo.findByUsername("admin");
        assertTrue(refreshedOpt.isPresent());
        assertEquals(originalEmail, refreshedOpt.get().email(), "Admin email must not be altered by other user");
    }

    @JettraTest
    @DisplayName("Should permit admin user to modify own profile and assigned databases")
    void testAdminModificationBySelfAllowed() {
        ensureAdminUser();

        // Session caller is 'admin'
        MockHttpExchange postExchange = new MockHttpExchange("POST", "/users");
        postExchange.getRequestHeaders().set("Cookie", "username=admin");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));

        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "update_user");
        formParams.put("username", "admin");
        formParams.put("email", "root_admin@jettra.io");
        formParams.put("role", "DB_ADMIN");
        formParams.put("target_dbs", "master_db,audit_db");

        var widget = usersPage.buildContent(postExchange, formParams, "dark");
        String html = widget.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(html.contains("updated successfully"), "Alert must report successful update");

        Optional<JUser> refreshedOpt = userRepo.findByUsername("admin");
        assertTrue(refreshedOpt.isPresent());
        assertEquals("root_admin@jettra.io", refreshedOpt.get().email(), "Email should be updated by admin itself");
        assertTrue(refreshedOpt.get().isAuthorizedForDatabase("master_db"), "master_db should be authorized");
        assertTrue(refreshedOpt.get().isAuthorizedForDatabase("audit_db"), "audit_db should be authorized");
    }

    @JettraTest
    @DisplayName("Should render protected safeguards in UI: disabled revoke for admin and edit conditionally visible")
    void testAdminRowProtectedUiRendering() {
        ensureAdminUser();

        // 1. Viewed by non-admin ('supervisor'): admin row shows Bloqueado and Protegido
        MockHttpExchange exchangeOther = new MockHttpExchange("GET", "/users");
        exchangeOther.getRequestHeaders().set("Cookie", "username=supervisor");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("supervisor", "ADMIN", "Operations", Set.of("*")),
            true
        ));

        var widgetOther = usersPage.buildUI(exchangeOther, Map.of(), "dark");
        String htmlOther = widgetOther.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(htmlOther.contains("Bloqueado"), "Admin row must render Bloqueado badge when viewed by other user");
        assertTrue(htmlOther.contains("Solo el usuario admin puede editar su propia cuenta"), "Must have tooltip explaining admin edit lock");
        assertTrue(htmlOther.contains("Protegido"), "Admin row must render Protegido button");
        assertTrue(htmlOther.contains("Acción protegida"), "Must have tooltip explaining protected action");

        // 2. Viewed by 'admin': admin row shows Edit and Protegido
        MockHttpExchange exchangeAdmin = new MockHttpExchange("GET", "/users");
        exchangeAdmin.getRequestHeaders().set("Cookie", "username=admin");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));

        var widgetAdmin = usersPage.buildUI(exchangeAdmin, Map.of(), "dark");
        String htmlAdmin = widgetAdmin.render(io.jettra.flux.theme.Themes.FlatTheme());

        assertTrue(htmlAdmin.contains("openEditUser"), "Admin row must render editable action when viewed by admin");
        assertTrue(htmlAdmin.contains("Protegido"), "Admin row must still render Protegido button");
        assertTrue(htmlAdmin.contains("disabled=\"disabled\""), "Protegido button must be disabled");
    }

    @JettraTest
    @DisplayName("Should restrict GET action=get_user for admin profile to admin session only")
    void testAdminGetUserRestrictedToSelf() throws IOException {
        ensureAdminUser();

        // 1. Unauthorized session trying to fetch admin JSON
        MockHttpExchange exOther = new MockHttpExchange("GET", "/users?action=get_user&username=admin");
        exOther.getRequestHeaders().set("Cookie", "username=supervisor");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("supervisor", "ADMIN", "Operations", Set.of("*")),
            true
        ));

        usersPage.handle(exOther);
        assertEquals(403, exOther.getResponseCode(), "Non-admin querying admin details must receive 403 Forbidden");
        assertTrue(exOther.getResponseBodyAsString().contains("Acceso denegado"), "Response must indicate access denied");

        // 2. Admin session querying admin details
        MockHttpExchange exAdmin = new MockHttpExchange("GET", "/users?action=get_user&username=admin");
        exAdmin.getRequestHeaders().set("Cookie", "username=admin");
        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));

        usersPage.handle(exAdmin);
        assertEquals(200, exAdmin.getResponseCode(), "Admin querying own details must receive 200 OK");
        assertTrue(exAdmin.getResponseBodyAsString().contains("\"username\":\"admin\""), "Response must contain admin user details");
    }

    // Mock HttpExchange
    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = 200;

        public MockHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create("http://localhost:8080" + path);
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
