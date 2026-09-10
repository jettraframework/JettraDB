package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.web.validation.UserValidationService;
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
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.UserUpdateCommand;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Integration Test Suite validating prevention of duplicate usernames in /users
 * and pure JettraFlux visual feedback rendering (FeedbackAlert & ValidationMessage).
 */
@NotRequiresRunningServer
public class StoreUsersPageValidationTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private MockUserRepository userRepo;
    private MockCredentialRepository credRepo;
    private UserValidationService validationService;
    private StoreUsersPage usersPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_user_val_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        authManager = new AuthManager();

        userRepo = new MockUserRepository();
        credRepo = new MockCredentialRepository();

        // Seed initial admin user
        JUser adminUser = new JUser(
            UUID.randomUUID(),
            "admin",
            "*",
            "admin@jettra.io",
            "+123456",
            true,
            Set.of(new JRole(UUID.randomUUID(), "DB_ADMIN", true)),
            Set.of("*")
        );
        userRepo.save(adminUser);

        validationService = new UserValidationService(userRepo);
        usersPage = new StoreUsersPage(engine, authManager, userRepo, credRepo, validationService);

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
    }

    @JettraTest
    @DisplayName("GET /users?action=check_username for existing username returns DUPLICATE status")
    void testCheckUsernameEndpoint_Duplicate() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/users?action=check_username&username=admin");
        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String response = exchange.getResponseBodyAsString();
        assertTrue(response.contains("\"valid\":false"), "Should indicate invalid username");
        assertTrue(response.contains("\"status\":\"DUPLICATE\""), "Status should be DUPLICATE");
        assertTrue(response.contains("ya está registrado") || response.contains("ya se encuentra registrado"), "Message should notify duplication");
    }

    @JettraTest
    @DisplayName("GET /users?action=check_username for new username returns AVAILABLE status")
    void testCheckUsernameEndpoint_Available() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/users?action=check_username&username=mario_rossi");
        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String response = exchange.getResponseBodyAsString();
        assertTrue(response.contains("\"valid\":true"), "Should indicate valid username");
        assertTrue(response.contains("\"status\":\"AVAILABLE\""), "Status should be AVAILABLE");
    }

    @JettraTest
    @DisplayName("GET /users?action=check_username with short name returns INVALID status")
    void testCheckUsernameEndpoint_TooShort() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/users?action=check_username&username=xy");
        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String response = exchange.getResponseBodyAsString();
        assertTrue(response.contains("\"valid\":false"), "Should indicate invalid username");
        assertTrue(response.contains("USERNAME_TOO_SHORT"), "Should fail format length");
    }

    @JettraTest
    @DisplayName("POST /users create_user with DUPLICATE username is BLOCKED from persistence and displays FeedbackAlert")
    void testPostCreateUser_BlocksDuplicate() throws IOException {
        int initialCount = userRepo.findAll().size();

        String body = "action=create_user&username=admin&email=newadmin@jettra.io&password=pass123&role=READ_WRITE&target_dbs=*";
        MockHttpExchange exchange = new MockHttpExchange("POST", "/users", body);
        usersPage.handle(exchange);

        // Persistence must NOT have occurred
        assertEquals(initialCount, userRepo.findAll().size(), "Repository size must not increase upon duplicate submission");

        String html = exchange.getResponseBodyAsString();

        // Must display JettraFlux FeedbackAlert with error
        assertTrue(html.contains("jettra-feedback-alert"), "Must render FeedbackAlert widget");
        assertTrue(html.contains("Error de Validación de Usuario") || html.contains("ya se encuentra registrado"),
            "Alert must display validation collision error");

        // Must display ValidationMessage for username
        assertTrue(html.contains("jettra-validation-message"), "Must render ValidationMessage widget");
        assertTrue(html.contains("is-invalid"), "Field must have is-invalid state");
    }

    @JettraTest
    @DisplayName("POST /users create_user with UNIQUE username persists account and displays success")
    void testPostCreateUser_PersistsUniqueUser() throws IOException {
        int initialCount = userRepo.findAll().size();

        String body = "action=create_user&username=beatriz_costa&email=beatriz@jettra.io&password=secret456&role=DB_ADMIN&target_dbs=records_store";
        MockHttpExchange exchange = new MockHttpExchange("POST", "/users", body);
        usersPage.handle(exchange);

        // Persistence must succeed
        assertEquals(initialCount + 1, userRepo.findAll().size(), "New user should be persisted");
        Optional<JUser> created = userRepo.findByUsername("beatriz_costa");
        assertTrue(created.isPresent(), "User beatriz_costa should exist in repository");
        assertEquals("beatriz_costa", created.get().firstName());

        String html = exchange.getResponseBodyAsString();
        assertTrue(html.contains("provisioned with role"), "Success message should be displayed");
    }

    // --- Mock Infrastructure ---

    private static class MockUserRepository implements JUserRepository {
        private final List<JUser> users = new ArrayList<>();

        @Override
        public Optional<JUser> findById(UUID id) {
            return users.stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public Optional<JUser> findByUsername(String username) {
            if (username == null) return Optional.empty();
            return users.stream().filter(u -> u.firstName().equalsIgnoreCase(username.trim())).findFirst();
        }

        @Override
        public List<JUser> findAll() {
            return new ArrayList<>(users);
        }

        @Override
        public void save(JUser user) {
            users.removeIf(u -> u.id().equals(user.id()));
            users.add(user);
        }

        @Override
        public void delete(UUID id) {
            users.removeIf(u -> u.id().equals(id));
        }

        @Override
        public List<JUser> search(String query) {
            if (query == null || query.isBlank()) return findAll();
            return users.stream().filter(u -> u.firstName().contains(query)).toList();
        }

        @Override
        public Optional<JUser> updateUser(String username, UserUpdateCommand command) {
            return Optional.empty();
        }
    }

    private static class MockCredentialRepository implements JCredentialRepository {
        private final List<JCredential> credentials = new ArrayList<>();

        @Override
        public Optional<JCredential> findById(UUID id) {
            return credentials.stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public Optional<JCredential> findByUsername(String username) {
            return credentials.stream().filter(c -> c.username().equalsIgnoreCase(username)).findFirst();
        }

        @Override
        public List<JCredential> findAll() {
            return new ArrayList<>(credentials);
        }

        @Override
        public void save(JCredential credential) {
            credentials.removeIf(c -> c.id().equals(credential.id()));
            credentials.add(credential);
        }

        @Override
        public void delete(UUID id) {
            credentials.removeIf(c -> c.id().equals(id));
        }

        @Override
        public List<JCredential> search(String query) {
            if (query == null || query.isBlank()) return findAll();
            return credentials.stream().filter(c -> c.username().contains(query)).toList();
        }

        @Override
        public Optional<JCredential> findByUsernamePassword(String username, String password) {
            return findByUsername(username);
        }
    }

    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final InputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private int responseCode = 200;

        public MockHttpExchange(String method, String pathWithQuery) {
            this(method, pathWithQuery, "");
        }

        public MockHttpExchange(String method, String pathWithQuery, String body) {
            this.method = method;
            this.uri = URI.create("http://localhost:8080" + pathWithQuery);
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            this.requestHeaders.set("Cookie", "username=admin; jettra_user=admin; role=ADMIN");
            if ("POST".equalsIgnoreCase(method)) {
                this.requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
            }
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
