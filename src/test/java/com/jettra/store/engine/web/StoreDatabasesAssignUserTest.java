package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.exception.UserAlreadyExistsException;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Comprehensive Integration and Unit Test Suite for JettraDB Modal 'Assign User to' in /databases.
 * Tests:
 * 1. Existing user selection from system_db and direct assignment to database.
 * 2. New user creation with guaranteed uniqueness and atomic persistence in system_db.
 * 3. Prevention of duplicate username collision throwing UserAlreadyExistsException and displaying error feedback.
 * 4. Modal visual structure and composition using pure typed JettraFlux components (ToggleTabs, SelectFilter, TextInput, ValidationFeedback).
 * 5. Automatic cleanup preserving only 'admin'.
 */
@NotRequiresRunningServer
public class StoreDatabasesAssignUserTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_assign_user_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl();
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        authManager = new AuthManager(systemUserRepo);
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);

        // Ensure database 'analytics_db' exists in storage
        engine.getStorageCore().put("rec:analytics_db:init", "{\"status\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // Ensure admin baseline
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
    @DisplayName("1. Existing user selection from system_db assigns target database scope and role correctly")
    void testExistingUserAssignmentToDatabase() {
        // Pre-create an existing user in system_db with restricted scope
        String username = "existing_data_analyst";
        SystemUser initialUser = new SystemUser(
            UUID.randomUUID(),
            username,
            SystemUserRepositoryImpl.hashPassword("secretPass123"),
            "analyst@enterprise.io",
            "READ_ONLY",
            true,
            Set.of("initial_db"),
            Instant.now(),
            Instant.now()
        );
        systemUserRepo.save(initialUser);

        // Assign existing user to 'analytics_db' with upgraded 'READ_WRITE' role
        SystemUser updatedUser = databasesPage.assignExistingUser("analytics_db", username, "READ_WRITE");

        assertNotNull(updatedUser, "Updated user must not be null");
        assertEquals("READ_WRITE", updatedUser.role(), "Role should be updated to READ_WRITE");
        assertTrue(updatedUser.hasDatabaseAccess("analytics_db"), "User must have access to analytics_db");
        assertTrue(updatedUser.hasDatabaseAccess("initial_db"), "User should retain access to initial_db");

        // Verify persistence directly in systemUserRepo
        Optional<SystemUser> loadedOpt = systemUserRepo.findByUsername(username);
        assertTrue(loadedOpt.isPresent(), "User must be found in system_db");
        assertTrue(loadedOpt.get().assignedDatabases().contains("analytics_db"), "system_db must contain analytics_db");
    }

    @JettraTest
    @DisplayName("2. Existing user assignment via HTTP POST action handles assign_user in existing mode")
    void testExistingUserAssignmentViaHttpPost() throws IOException {
        String username = "finance_analyst";
        systemUserRepo.save(new SystemUser(
            UUID.randomUUID(),
            username,
            SystemUserRepositoryImpl.hashPassword("pass123"),
            "finance@jettra.io",
            "READ_ONLY",
            true,
            Set.of("finance_db"),
            Instant.now(),
            Instant.now()
        ));

        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&existing_username=" + username + "&target_db=analytics_db&role=DB_ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String responseBody = exchange.getResponseBodyAsString();
        assertTrue(responseBody.contains("Usuario existente '" + username + "' asignado exitosamente"),
                "Response must confirm successful assignment of existing user");

        // Verify user in system_db has updated permissions
        Optional<SystemUser> userOpt = systemUserRepo.findByUsername(username);
        assertTrue(userOpt.isPresent());
        assertTrue(userOpt.get().assignedDatabases().contains("analytics_db"));
        assertEquals("DB_ADMIN", userOpt.get().role());
    }

    @JettraTest
    @DisplayName("3. New user creation with unique username persists in system_db with target database assigned")
    void testNewUserCreationWithUniqueUsername() {
        String newUsername = "secure_operator_01";
        SystemUser created = databasesPage.createAndAssignNewUser(
            "analytics_db",
            newUsername,
            "operator@jettra.io",
            "ComplexPassword!2026",
            "READ_WRITE"
        );

        assertNotNull(created, "Created user must not be null");
        assertEquals(newUsername, created.username());
        assertEquals("READ_WRITE", created.role());
        assertTrue(created.hasDatabaseAccess("analytics_db"));

        // Verify password hash is stored, not plaintext
        assertFalse("ComplexPassword!2026".equals(created.passwordHash()), "Plaintext password must never be stored");
        assertTrue(created.passwordHash().length() >= 32, "Password must be hashed");

        // Verify lookup in systemUserRepo
        Optional<SystemUser> loaded = systemUserRepo.findByUsername(newUsername);
        assertTrue(loaded.isPresent());
        assertEquals("operator@jettra.io", loaded.get().email());
    }

    @JettraTest
    @DisplayName("4. New user creation with duplicate username strictly throws UserAlreadyExistsException")
    void testDuplicateUsernameThrowsDomainException() {
        // Pre-create 'registered_user' in system_db
        String existingUsername = "registered_user";
        systemUserRepo.save(SystemUser.create(
            existingUsername,
            SystemUserRepositoryImpl.hashPassword("pw123"),
            "registered@jettra.io",
            "USER",
            Set.of("analytics_db")
        ));

        // Attempting to create duplicate user directly must throw UserAlreadyExistsException
        UserAlreadyExistsException ex = null;
        try {
            databasesPage.createAndAssignNewUser("analytics_db", existingUsername, "dup@jettra.io", "pwd", "READ_WRITE");
        } catch (UserAlreadyExistsException e) {
            ex = e;
        }

        assertNotNull(ex, "Must throw UserAlreadyExistsException on duplicate username");
        assertEquals(existingUsername, ex.getUsername(), "Exception must carry collided username");
        assertTrue(ex.getMessage().contains(existingUsername), "Message must mention username");

        // Case-insensitive duplication test ('REGISTERED_USER')
        UserAlreadyExistsException caseEx = null;
        try {
            databasesPage.createAndAssignNewUser("analytics_db", existingUsername.toUpperCase(), "dup@jettra.io", "pwd", "READ_WRITE");
        } catch (UserAlreadyExistsException e) {
            caseEx = e;
        }
        assertNotNull(caseEx, "Case-insensitive collision must also throw UserAlreadyExistsException");
    }

    @JettraTest
    @DisplayName("5. HTTP POST with duplicate username captures exception and renders visual error feedback")
    void testDuplicateUsernameRendersErrorFeedbackInHttp() throws IOException {
        String username = "clash_analyst";
        systemUserRepo.save(SystemUser.create(
            username,
            SystemUserRepositoryImpl.hashPassword("pass"),
            "clash@jettra.io",
            "READ_ONLY",
            Set.of("analytics_db")
        ));

        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=new&username=" + username + "&email=new@jettra.io&password=pass&target_db=analytics_db&role=READ_WRITE");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must display error alert indicating uniqueness collision
        assertTrue(body.contains("Error de unicidad"), "Must display uniqueness error header");
        assertTrue(body.contains(username), "Must mention collided username in alert");
    }

    @JettraTest
    @DisplayName("6. Modal UI renders pure JettraFlux components: ToggleTabs, SelectFilter, TextInput, and ValidationFeedback")
    void testModalRendersPureJettraFluxComponents() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String html = exchange.getResponseBodyAsString();

        // 1. Modal dialog container
        assertTrue(html.contains("id=\"assignUserModal\""), "Must contain assignUserModal dialog");

        // 2. ToggleTabs component
        assertTrue(html.contains("id=\"assignUserModeTabs\""), "Must render ToggleTabs component");
        assertTrue(html.contains("role=\"tablist\""), "ToggleTabs must have role tablist");
        assertTrue(html.contains("data-tab-id=\"existing\""), "Must have tab for existing user");
        assertTrue(html.contains("data-tab-id=\"new\""), "Must have tab for new user");
        assertTrue(html.contains("switchAssignUserMode"), "Must wire tab switcher script");

        // 3. UserSelectionTable for existing users mass selection & synchronization
        assertTrue(html.contains("id=\"assignUserSelectionTable\""), "Must render UserSelectionTable component");
        assertTrue(html.contains("id=\"assignUserSelectionTable_search\""), "Must render live filter search input");
        assertTrue(html.contains("class=\"jettra-toggle-selection-item"), "Must render selectable user items");
        assertTrue(html.contains("data-value=\"admin\""), "UserSelectionTable must list admin user");
        assertTrue(html.contains("id=\"assignUserSelectionTable_counter\""), "Must render live counter badge");
        assertTrue(html.contains("Select All"), "Must render quick action Select All button");
        assertTrue(html.contains("Clear All"), "Must render quick action Clear All button");
        assertTrue(html.contains("window.UserSelectionTable.syncForDatabase"), "Must wire database synchronization script");

        // 4. TextInput and ValidationFeedback for new user
        assertTrue(html.contains("id=\"new_username_input\""), "Must render username TextInput");
        assertTrue(html.contains("id=\"new_username_feedback\""), "Must render ValidationFeedback for username");
        assertTrue(html.contains("id=\"new_email_input\""), "Must render email TextInput");
        assertTrue(html.contains("id=\"new_password_input\""), "Must render password TextInput");

        // 5. JettraFluxSelect for role selection
        assertTrue(html.contains("id=\"assignUserRoleSelect\""), "Must render role JettraFluxSelect");
        assertTrue(html.contains("DB_ADMIN"), "Role select must contain DB_ADMIN option");
        assertTrue(html.contains("READ_WRITE"), "Role select must contain READ_WRITE option");

        // 6. Action button
        assertTrue(html.contains("id=\"assignUserSubmitBtn\""), "Must render typed submit button");
    }

    @JettraTest
    @DisplayName("7. Assigning non-existent user in existing mode fails gracefully with error feedback")
    void testNonExistentUserAssignmentFails() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&existing_username=ghost_user_999&target_db=analytics_db");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("no fue encontrado en system_db") || body.contains("ghost_user_999"),
            "Must report that user was not found");
    }

    @JettraTest
    @DisplayName("8. Java 25 Virtual Thread validation service validates uniqueness asynchronously")
    void testVirtualThreadUniquenessValidation() {
        com.jettra.store.engine.web.validation.UserValidationService valService = 
            new com.jettra.store.engine.web.validation.UserValidationService(systemUserRepo);

        // Async check for existing admin
        com.jettra.store.engine.web.validation.UserValidationContext adminCtx = 
            com.jettra.store.engine.web.validation.UserValidationContext.forCreate("admin", "admin@jettra.io", "ADMIN");
        com.jettra.store.engine.web.validation.ValidationResult adminRes = 
            valService.validateAsync(adminCtx).join();

        assertTrue(adminRes.isInvalid(), "Admin username must be flagged as duplicate");
        assertTrue(adminRes.message().contains("admin"), "Error message must cite duplicate username");

        // Async check for brand new unique username
        com.jettra.store.engine.web.validation.UserValidationContext brandNewCtx = 
            com.jettra.store.engine.web.validation.UserValidationContext.forCreate("brand_new_valid_dev", "dev@jettra.io", "READ_WRITE");
        com.jettra.store.engine.web.validation.ValidationResult brandNewRes = 
            valService.validateAsync(brandNewCtx).join();

        assertTrue(brandNewRes.isValid(), "New unique username must be valid");
    }

    @JettraTest
    @DisplayName("9. Mass user assignment and deselection synchronization persists additions and removals")
    void testMassUserAssignmentAndDeselectionSynchronization() throws IOException {
        String u1 = "mass_user_alpha";
        String u2 = "mass_user_beta";

        // Alpha initially has NO access to analytics_db
        systemUserRepo.save(SystemUser.create(u1, "hash1", "alpha@jettra.io", "READ_WRITE", Set.of("other_db")));
        // Beta initially HAS access to analytics_db and other_db
        systemUserRepo.save(SystemUser.create(u2, "hash2", "beta@jettra.io", "READ_ONLY", Set.of("analytics_db", "other_db")));

        // Perform mass sync: Alpha is checked, Beta is deselected
        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&target_db=analytics_db&assigned_users=admin," + u1);

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("Sincronización de usuarios completada"), "Must report sync success");

        // Verify Alpha gained analytics_db access
        SystemUser loadedAlpha = systemUserRepo.findByUsername(u1).orElseThrow();
        assertTrue(loadedAlpha.hasDatabaseAccess("analytics_db"), "Alpha must have been assigned to analytics_db");
        assertTrue(loadedAlpha.hasDatabaseAccess("other_db"), "Alpha must preserve other_db");

        // Verify Beta lost analytics_db access (deselected) but retains other_db
        SystemUser loadedBeta = systemUserRepo.findByUsername(u2).orElseThrow();
        assertFalse(loadedBeta.assignedDatabases().contains("analytics_db"), "Beta must have been de-assigned from analytics_db");
        assertTrue(loadedBeta.hasDatabaseAccess("other_db"), "Beta must preserve other_db");
    }

    @JettraTest
    @DisplayName("10. Admin cluster-wide rights remain protected during mass user deselection")
    void testAdminProtectionInMassAssignment() throws IOException {
        // Deselect all users including admin attempt
        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&target_db=analytics_db&assigned_users=");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());

        // Admin must still have access
        SystemUser admin = systemUserRepo.findByUsername("admin").orElseThrow();
        assertTrue(admin.hasDatabaseAccess("analytics_db"), "Admin access must never be revoked during mass synchronization");
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
