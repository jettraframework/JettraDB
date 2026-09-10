package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.web.validation.UserValidationChain;
import com.jettra.store.engine.web.validation.UserValidationService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class StoreUsersPageUniquenessTest {

    private Path tempDir;
    private Path tempSystemDbDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private StoreUsersPage usersPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_uniqueness_test");
        tempSystemDbDir = Files.createTempDirectory("jettra_sysdb_uniqueness");

        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl(tempSystemDbDir);
        authManager = new AuthManager(systemUserRepo);
        UserValidationService valService = new UserValidationService(systemUserRepo);

        usersPage = new StoreUsersPage(
            engine,
            authManager,
            systemUserRepo
        );

        SecurityContextHolder.setContext(new SecurityContext(
            SecurityPrincipal.of("admin", "ADMIN", "Security", Set.of("*")),
            true
        ));
    }

    @AfterEach
    void tearDown() {
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
        if (tempSystemDbDir != null && Files.exists(tempSystemDbDir)) {
            try {
                Files.walk(tempSystemDbDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("1. GET /users check_username identifies exact and case-insensitive duplicates against system_db")
    void testCheckUsernameCaseInsensitive() throws IOException {
        // Exact duplicate
        MockHttpExchange ex1 = new MockHttpExchange("GET", "/users?action=check_username&username=admin");
        usersPage.handle(ex1);
        assertEquals(200, ex1.getResponseCode());
        String body1 = ex1.getResponseBodyAsString();
        assertTrue(body1.contains("\"valid\":false"));
        assertTrue(body1.contains("\"status\":\"DUPLICATE\""));

        // Case-insensitive duplicate (ADMIN)
        MockHttpExchange ex2 = new MockHttpExchange("GET", "/users?action=check_username&username=ADMIN");
        usersPage.handle(ex2);
        assertEquals(200, ex2.getResponseCode());
        String body2 = ex2.getResponseBodyAsString();
        assertTrue(body2.contains("\"valid\":false"), "Mixed-case duplicate must be recognized as invalid");
        assertTrue(body2.contains("\"status\":\"DUPLICATE\""), "Status must be DUPLICATE for ADMIN");

        // Available username
        MockHttpExchange ex3 = new MockHttpExchange("GET", "/users?action=check_username&username=roberto_gomez");
        usersPage.handle(ex3);
        assertEquals(200, ex3.getResponseCode());
        String body3 = ex3.getResponseBodyAsString();
        assertTrue(body3.contains("\"valid\":true"));
        assertTrue(body3.contains("\"status\":\"AVAILABLE\""));
    }

    @JettraTest
    @DisplayName("2. POST /users create_user with case-collided username is BLOCKED and renders ValidationFeedback")
    void testPostCreateUser_BlocksCaseCollidedUsername() throws IOException {
        long initialCount = systemUserRepo.count();

        // Attempt to create user 'ADMIN' when 'admin' already exists
        String body = "action=create_user&username=ADMIN&email=admin2@jettra.io&password=secret999&role=READ_WRITE&target_dbs=*";
        MockHttpExchange exchange = new MockHttpExchange("POST", "/users", body);
        usersPage.handle(exchange);

        // Verify that system_db has not persisted the duplicate
        assertEquals(initialCount, systemUserRepo.count(), "Persistence in system_db must be blocked");

        String html = exchange.getResponseBodyAsString();
        assertTrue(html.contains("jettra-feedback-alert"), "Must render FeedbackAlert on collision");
        assertTrue(html.contains("ya está registrado") || html.contains("Error de Validación de Usuario"), "Error text must reflect collision");
        assertTrue(html.contains("is-invalid"), "Field must receive is-invalid decoration");
    }

    @JettraTest
    @DisplayName("3. POST /users create_user with UNIQUE username persists in system_db and updates AuthManager")
    void testPostCreateUser_PersistsSuccessfullyInSystemDb() throws IOException {
        long initialCount = systemUserRepo.count();

        String body = "action=create_user&username=gabriela_mistral&email=gabriela@jettra.io&password=chile1945&role=READ_WRITE&target_dbs=records_store,system_db";
        MockHttpExchange exchange = new MockHttpExchange("POST", "/users", body);
        usersPage.handle(exchange);

        // Verification of system_db persistence
        assertEquals(initialCount + 1, systemUserRepo.count(), "User count in system_db must increment by 1");

        Optional<SystemUser> created = systemUserRepo.findByUsername("gabriela_mistral");
        assertTrue(created.isPresent(), "User must be present in system_db");
        assertEquals("gabriela@jettra.io", created.get().email());
        assertTrue(created.get().assignedDatabases().contains("records_store"));

        // Verify authentication via AuthManager
        assertTrue(authManager.authenticate("gabriela_mistral", "chile1945"), "New user must authenticate via AuthManager");

        String html = exchange.getResponseBodyAsString();
        assertTrue(html.contains("provisioned with role"), "Success message must be displayed in web view");
    }

    @JettraTest
    @DisplayName("4. Concurrency test: Multiple simultaneous creation requests for same username allow exactly one persistence")
    void testConcurrentCreationOfDuplicateUsername() throws InterruptedException {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        long initialCount = systemUserRepo.count();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    String body = "action=create_user&username=race_condition_user&email=race@jettra.io&password=password1&role=READ_WRITE&target_dbs=*";
                    MockHttpExchange exchange = new MockHttpExchange("POST", "/users", body);
                    usersPage.handle(exchange);
                    String resp = exchange.getResponseBodyAsString();
                    if (resp.contains("provisioned with role")) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads at once
        latch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Exactly 1 must have succeeded, 7 rejected
        assertEquals(1, successCount.get(), "Exactly one concurrent thread must succeed in provisioning the username");
        assertEquals(threads - 1, rejectedCount.get(), "All other concurrent attempts must be rejected");
        assertEquals(initialCount + 1, systemUserRepo.count(), "Repository size must increase by exactly 1");
    }

    @JettraTest
    @DisplayName("5. First execution of /users guarantees that exclusively the admin user exists")
    void testFirstExecutionGuaranteesOnlyAdminUser() throws IOException {
        Path freshDir = Files.createTempDirectory("fresh_users_page_test");
        try {
            SystemUserRepository freshRepo = new SystemUserRepositoryImpl(freshDir);
            AuthManager freshAuth = new AuthManager(freshRepo);
            StoreUsersPage freshPage = new StoreUsersPage(engine, freshAuth, freshRepo);

            // Execute /users for the first time
            MockHttpExchange exchange = new MockHttpExchange("GET", "/users");
            freshPage.handle(exchange);

            assertEquals(200, exchange.getResponseCode());
            // Exactly 1 user must exist: admin
            assertEquals(1, freshRepo.count(), "On first execution of /users, exactly 1 user (admin) must exist");
            Optional<SystemUser> adminOpt = freshRepo.findByUsername("admin");
            assertTrue(adminOpt.isPresent(), "Admin user must exist");
            assertTrue(adminOpt.get().isAdmin(), "Admin must have admin role");

            String html = exchange.getResponseBodyAsString();
            assertTrue(html.contains("admin"), "HTML must list the admin user");
            assertFalse(html.contains("super-user"), "HTML must NOT list any super-user or test accounts");
        } finally {
            try {
                Files.walk(freshDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("6. Users created from driver are preserved and correctly displayed alongside admin")
    void testUsersCreatedFromDriverArePreservedAndListed() throws IOException {
        Path driverDir = Files.createTempDirectory("driver_users_test");
        try {
            SystemUserRepository driverRepo = new SystemUserRepositoryImpl(driverDir);
            AuthManager driverAuth = new AuthManager(driverRepo);
            StoreUsersPage driverPage = new StoreUsersPage(engine, driverAuth, driverRepo);

            // Driver creates a user before/independent of web UI
            SystemUser driverUser = SystemUser.create(
                "driver_service_account",
                SystemUserRepositoryImpl.hashPassword("driverPass123"),
                "driver@jettra.io",
                "READ_WRITE",
                Set.of("analytics_db")
            );
            driverRepo.save(driverUser);
            assertEquals(2, driverRepo.count(), "Repository must contain admin + driver user");

            // Now administrator loads /users web interface
            MockHttpExchange exchange = new MockHttpExchange("GET", "/users");
            driverPage.handle(exchange);

            assertEquals(200, exchange.getResponseCode());
            String html = exchange.getResponseBodyAsString();
            assertTrue(html.contains("admin"), "Web UI must display admin");
            assertTrue(html.contains("driver_service_account"), "Web UI must preserve and display driver-created user");
            assertEquals(2, driverRepo.count(), "User count must remain exactly 2");
        } finally {
            try {
                Files.walk(driverDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    // --- Mock Infrastructure for HttpExchange ---

    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = 200;

        MockHttpExchange(String method, String url) {
            this(method, url, "");
        }

        MockHttpExchange(String method, String url, String body) {
            this.method = method;
            this.uri = URI.create("http://localhost:8080" + (url.startsWith("/") ? url : "/" + url));
            this.requestHeaders.set("Cookie", "username=admin; jettra_user=admin; role=ADMIN");
            if (body != null && !body.isEmpty()) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                this.requestBody = new ByteArrayInputStream(bytes);
                this.requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
                this.requestHeaders.set("Content-Length", String.valueOf(bytes.length));
            }
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
