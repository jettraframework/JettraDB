package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.server.autentification.repository.JettraSecurityDBInitializer;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Test Suite verifying:
 * 1. Cleaned-up action bar omitting extraneous DB-level buttons on /users
 * 2. Password hashing & authentication validation for newly registered users on /login
 * 3. Encapsulated JettraConfirmDialog for user revocation without raw JavaScript confirms
 */
@NotRequiresRunningServer
public class StoreUsersPageSecurityAndDialogTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreUsersPage usersPage;
    private StoreLoginPage loginPage;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_users_security_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        usersPage = new StoreUsersPage(engine, authManager);
        loginPage = new StoreLoginPage(authManager);
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
    }

    @AfterEach
    void tearDown() throws IOException {
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
    @DisplayName("1. Toolbar sanitization: /users excludes extraneous DB buttons (+ DB, + Unit, Backup, Restore, Export, Búsqueda Avanzada, Sample DBs)")
    void testUsersViewSanitizedToolbar() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render extraneous database-level buttons in the toolbar
        assertFalse(body.contains("title=\"Create Database\""), "Toolbar must NOT contain 'Create Database' button");
        assertFalse(body.contains("title=\"Add Unit / Collection\""), "Toolbar must NOT contain 'Add Unit' button");
        assertFalse(body.contains("openBackupDbModal"), "Toolbar must NOT contain 'Backup' button");
        assertFalse(body.contains("openRestoreDbModal"), "Toolbar must NOT contain 'Restore' button");
        assertFalse(body.contains("openExportDataModal"), "Toolbar must NOT contain 'Export' button");
        assertFalse(body.contains("openAdvancedSearchModal"), "Toolbar must NOT contain 'Búsqueda Avanzada' button");
        assertFalse(body.contains("openSampleDatabasesModal"), "Toolbar must NOT contain 'Sample DBs' button");
        assertFalse(body.contains("+ DB"), "Toolbar must NOT contain '+ DB' button");
        assertFalse(body.contains("+ Unit"), "Toolbar must NOT contain '+ Unit' button");
        assertFalse(body.contains("Sample DBs"), "Toolbar must NOT contain 'Sample DBs' button");

        // Must NOT render top navigation tabs on /users
        assertFalse(body.contains("class=\"top-tabs-nav\""), "Toolbar must NOT contain top navigation tabs");
        assertFalse(body.contains("tab=buckets"), "Toolbar must NOT contain Buckets tab");
        assertFalse(body.contains("tab=indexes"), "Toolbar must NOT contain Indexes tab");
        assertFalse(body.contains("tab=dictionary"), "Toolbar must NOT contain Dictionary tab");

        // Must render security management identity
        assertTrue(body.contains("Users & Per-Database Security"), "Page must render user security header");
        assertTrue(body.contains("Database User Accounts"), "Page must render user accounts section");
    }

    @JettraTest
    @DisplayName("2. Authentication integrity: Newly created user password is encrypted and validates successfully on /login")
    void testCreatedUserAuthenticatesSuccessfully() throws IOException {
        String newUsername = "dev_analyst_" + System.currentTimeMillis();
        String plainPassword = "SecuredPassword2026!";

        // Provision user via POST /users
        TestHttpExchange createExchange = new TestHttpExchange("POST", "/users");
        createExchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        createExchange.setRequestBody("action=create_user&username=" + newUsername +
                "&email=dev@jettra.io&password=" + plainPassword +
                "&target_db=customers_db&role=READ_WRITE");

        usersPage.handle(createExchange);
        assertEquals(200, createExchange.getResponseCode());

        // Verify user persistence
        List<JUser> matchedUsers = userRepo.findAll().stream()
                .filter(u -> newUsername.equals(u.firstName()))
                .toList();
        assertFalse(matchedUsers.isEmpty(), "Newly created user must be saved in user repository");
        JUser savedUser = matchedUsers.get(0);
        assertTrue(savedUser.active(), "User must be active");

        // Verify credential persistence and password encryption
        Optional<JCredential> credOpt = credRepo.findByUsername(newUsername);
        assertTrue(credOpt.isPresent(), "Credentials for user must be saved in credential repository");
        JCredential cred = credOpt.get();
        assertTrue(cred.active(), "Credential must be active");

        // Password hash must NOT match plaintext password
        assertNotEquals(plainPassword, cred.passwordHash(), "Password must be hashed, never stored in plain text");
        assertEquals(JettraSecurityDBInitializer.hashPassword(plainPassword), cred.passwordHash(),
                "Password must be hashed with SHA-256 according to security standards");

        // Attempt login with valid credentials on /login
        TestHttpExchange loginExchange = new TestHttpExchange("POST", "/login");
        loginExchange.setRequestBody("username=" + newUsername + "&password=" + plainPassword);

        loginPage.handle(loginExchange);

        // Must cleanly redirect to /dashboard without error
        assertEquals(302, loginExchange.getResponseCode(), "Login must return HTTP 302 Found redirect");
        String location = loginExchange.getResponseHeaders().getFirst("Location");
        assertNotNull(location, "Redirect Location header must be present");
        assertTrue(location.endsWith("/dashboard"), "Successful login must redirect to /dashboard");
        assertFalse(location.contains("error=invalid_credentials"), "Location must NOT redirect to invalid_credentials");

        // Attempt login with incorrect password
        TestHttpExchange badLoginExchange = new TestHttpExchange("POST", "/login");
        badLoginExchange.setRequestBody("username=" + newUsername + "&password=wrongPassword999");

        loginPage.handle(badLoginExchange);

        assertEquals(302, badLoginExchange.getResponseCode());
        String badLocation = badLoginExchange.getResponseHeaders().getFirst("Location");
        assertNotNull(badLocation);
        assertTrue(badLocation.contains("error=invalid_credentials"), "Invalid password must redirect with error=invalid_credentials");
    }

    @JettraTest
    @DisplayName("3. Native JettraConfirmDialog encapsulation: Revoke button uses framework modal with zero raw JS confirm leaks")
    void testEncapsulatedJettraConfirmDialog() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // 1. Zero raw JavaScript confirms
        assertFalse(body.contains("confirm('"), "Must NOT contain raw window.confirm('...");
        assertFalse(body.contains("confirm(\""), "Must NOT contain raw window.confirm(\"...");
        assertFalse(body.contains("window.confirm"), "Must NOT contain window.confirm");

        // 2. Encapsulated JettraConfirmDialog attributes
        assertTrue(body.contains("revokeUserConfirmDialog"), "Must include revokeUserConfirmDialog container");
        assertTrue(body.contains("role=\"alertdialog\""), "Must use alertdialog accessibility role");
        assertTrue(body.contains("aria-modal=\"true\""), "Must declare aria-modal='true'");
        assertTrue(body.contains("Confirm User Access Revocation"), "Must render dialog title");
        assertTrue(body.contains("Revoke Access"), "Must render confirmation action button");
        assertTrue(body.contains("Cancel"), "Must render cancellation button");
        assertTrue(body.contains("fas fa-user-slash"), "Must render user revocation icon");

        // 3. Client helper integration
        assertTrue(body.contains("confirmRevokeUser"), "Must wire confirmRevokeUser client dispatch");
        assertTrue(body.contains("JettraConfirmDialog.open"), "Must invoke window.JettraConfirmDialog.open");
    }

    @JettraTest
    @DisplayName("4. User revocation execution: Revoking user deletes account and credentials, blocking subsequent logins")
    void testRevokeUserExecution() throws IOException {
        String testUser = "revoke_target_" + System.currentTimeMillis();
        String testPass = "PassTarget123!";

        // 1. Create user
        TestHttpExchange createExchange = new TestHttpExchange("POST", "/users");
        createExchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        createExchange.setRequestBody("action=create_user&username=" + testUser +
                "&email=target@jettra.io&password=" + testPass +
                "&target_db=*&role=READ_WRITE");

        usersPage.handle(createExchange);
        assertEquals(200, createExchange.getResponseCode());

        JUser user = userRepo.findAll().stream()
                .filter(u -> testUser.equals(u.firstName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Target user must exist"));

        // 2. Execute revoke via delete_user
        TestHttpExchange deleteExchange = new TestHttpExchange("POST", "/users");
        deleteExchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        deleteExchange.setRequestBody("action=delete_user&user_id=" + user.id().toString());

        usersPage.handle(deleteExchange);
        assertEquals(200, deleteExchange.getResponseCode());
        String delBody = deleteExchange.getResponseBodyAsString();
        assertTrue(delBody.contains("User account revoked and access removed."));

        // 3. Verify user removed from repositories
        Optional<JUser> postDeleteUser = userRepo.findById(user.id());
        assertTrue(postDeleteUser.isEmpty(), "User must be removed from repository");

        Optional<JCredential> postDeleteCred = credRepo.findByUsername(testUser);
        assertTrue(postDeleteCred.isEmpty(), "Credentials must be cleaned up from credential repository");

        // 4. Attempt login with revoked user -> must fail
        TestHttpExchange loginExchange = new TestHttpExchange("POST", "/login");
        loginExchange.setRequestBody("username=" + testUser + "&password=" + testPass);

        loginPage.handle(loginExchange);
        assertEquals(302, loginExchange.getResponseCode());
        String loc = loginExchange.getResponseHeaders().getFirst("Location");
        assertNotNull(loc);
        assertTrue(loc.contains("error=invalid_credentials"), "Revoked user must not be able to log in");
    }

    /**
     * In-memory test implementation of HttpExchange.
     */
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
