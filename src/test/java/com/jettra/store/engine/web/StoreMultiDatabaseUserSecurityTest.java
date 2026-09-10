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
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Test Suite verifying:
 * 1. Multi-database assignment modeling and persistence in JUser
 * 2. JettraFlux MultiSelect and BadgeList UI rendering in /users
 * 3. Security authorization evaluation enforcing per-database access control
 * 4. Backwards compatibility with legacy 7-argument user records
 */
@NotRequiresRunningServer
public class StoreMultiDatabaseUserSecurityTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreUsersPage usersPage;
    private StoreDatabasesPage databasesPage;
    private StoreEnginesPage enginesPage;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_multi_db_user_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        usersPage = new StoreUsersPage(engine, authManager);
        databasesPage = new StoreDatabasesPage(engine, authManager);
        enginesPage = new StoreEnginesPage(engine);
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityDbTestCleanup.purgeNonAdminTestUsers(userRepo, credRepo);
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
    @DisplayName("1. Multi-Database Provisioning: User can be created with multiple assigned databases and validates correctly")
    void testCreateUserWithMultipleDatabases() throws IOException {
        String testUser = "analyst_multi_" + System.currentTimeMillis();
        String password = "SecurePass123!";

        // Provision user assigned to three distinct databases via POST /users
        TestHttpExchange exchange = new TestHttpExchange("POST", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=create_user&username=" + testUser +
                "&email=" + testUser + "@jettra.io&password=" + password +
                "&target_dbs=customers_db,analytics_db,finance_db&role=READ_WRITE");

        usersPage.handle(exchange);
        assertEquals(200, exchange.getResponseCode());

        // Verify persistence in repository
        Optional<JUser> userOpt = userRepo.findAll().stream()
                .filter(u -> testUser.equalsIgnoreCase(u.firstName()))
                .findFirst();

        assertTrue(userOpt.isPresent(), "User must be persisted in repository");
        JUser user = userOpt.get();

        // Validate assignedDatabases collection
        Set<String> dbs = user.assignedDatabases();
        assertNotNull(dbs, "assignedDatabases collection must not be null");
        assertEquals(3, dbs.size(), "User must have exactly 3 assigned databases");
        assertTrue(dbs.contains("customers_db"), "Must contain customers_db");
        assertTrue(dbs.contains("analytics_db"), "Must contain analytics_db");
        assertTrue(dbs.contains("finance_db"), "Must contain finance_db");

        // Authorization checks
        assertTrue(user.isAuthorizedForDatabase("customers_db"), "Must be authorized for customers_db");
        assertTrue(user.isAuthorizedForDatabase("analytics_db"), "Must be authorized for analytics_db");
        assertTrue(user.isAuthorizedForDatabase("finance_db"), "Must be authorized for finance_db");
        assertFalse(user.isAuthorizedForDatabase("secret_warehouse_db"), "Must NOT be authorized for unassigned secret_warehouse_db");
        assertFalse(user.isAuthorizedForDatabase("hr_db"), "Must NOT be authorized for unassigned hr_db");
    }

    @JettraTest
    @DisplayName("2. JettraFlux UI Rendering: /users view renders MultiSelect component and BadgeList tags")
    void testMultiSelectAndBadgeListRenderingInUsersView() throws IOException {
        // Provision a user with multiple databases
        String userA = "user_badges_" + System.currentTimeMillis();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));
        JUser user = new JUser(UUID.randomUUID(), userA, "sales_db, telemetry_db",
                userA + "@jettra.io", "+123", true, roles, Set.of("sales_db", "telemetry_db"));
        userRepo.save(user);

        // Provision wildcard admin user
        String adminUser = "global_admin_" + System.currentTimeMillis();
        JUser wildcardUser = new JUser(UUID.randomUUID(), adminUser, "*",
                adminUser + "@jettra.io", "+123", true, Set.of(new JRole(UUID.randomUUID(), "DB_ADMIN", true)), Set.of("*"));
        userRepo.save(wildcardUser);

        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        usersPage.handle(exchange);
        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Verify MultiSelect component is rendered in the create user card
        assertTrue(body.contains("jettra-multiselect-root"), "Must render JettraFlux MultiSelect component");
        assertTrue(body.contains("Target Databases Access"), "Must render multi-select label");
        assertTrue(body.contains("name=\"target_dbs\""), "Must contain multi-select input name target_dbs");
        assertTrue(body.contains("Select All"), "Must render quick-action Select All");
        assertTrue(body.contains("Clear"), "Must render quick-action Clear");
        assertTrue(body.contains("* (All Databases)"), "Must include wildcard option in multi-select");

        // Verify BadgeList is rendered in the table rows
        assertTrue(body.contains("jettra-badge-list"), "Must render JettraFlux BadgeList in table");
        assertTrue(body.contains("sales_db"), "Must render sales_db badge");
        assertTrue(body.contains("telemetry_db"), "Must render telemetry_db badge");
        assertTrue(body.contains("ALL DATABASES"), "Wildcard user must render 'ALL DATABASES' badge");
        assertTrue(body.contains("Assigned Databases"), "Table header must state 'Assigned Databases'");
    }

    @JettraTest
    @DisplayName("3. Security Authorization: StoreDatabasesPage filters visible databases according to multi-database assignments")
    void testDatabasesPageAuthorizationFilter() throws IOException {
        String testUser = "scoped_multi_" + System.currentTimeMillis();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_ONLY", true));
        JUser user = new JUser(UUID.randomUUID(), testUser, "customers_db, marketing_db",
                testUser + "@jettra.io", "+123", true, roles, Set.of("customers_db", "marketing_db"));
        userRepo.save(user);

        SecurityPrincipal principal = SecurityPrincipal.of(testUser, "USER", "", Set.of("customers_db", "marketing_db"));

        List<JUser> userList = List.of(user);

        // Validate isAuthorizedForDatabase logic
        assertTrue(databasesPage.isAuthorizedForDatabase(principal, "customers_db", userList),
                "User must be authorized for customers_db");
        assertTrue(databasesPage.isAuthorizedForDatabase(principal, "marketing_db", userList),
                "User must be authorized for marketing_db");
        assertFalse(databasesPage.isAuthorizedForDatabase(principal, "finance_db", userList),
                "User must NOT be authorized for finance_db");
        assertFalse(databasesPage.isAuthorizedForDatabase(principal, "system_core_db", userList),
                "User must NOT be authorized for system_core_db");
    }

    @JettraTest
    @DisplayName("4. Database Operations Authorization: StoreEnginesPage blocks operations on unassigned databases")
    void testStoreEnginesPageAccessEnforcement() throws IOException {
        String restrictedUser = "restricted_dev_" + System.currentTimeMillis();
        String pass = "Pass123!";
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "USER", true));
        JUser user = new JUser(UUID.randomUUID(), restrictedUser, "allowed_db",
                restrictedUser + "@jettra.io", "+123", true, roles, Set.of("allowed_db"));
        userRepo.save(user);

        JCredential cred = new JCredential(UUID.randomUUID(), user, restrictedUser,
                JettraSecurityDBInitializer.hashPassword(pass), true, Instant.now());
        credRepo.save(cred);

        // 1. Attempt mutation on unauthorized database -> Must be denied
        TestHttpExchange badExchange = new TestHttpExchange("POST", "/engines");
        badExchange.getRequestHeaders().set("Cookie", "username=" + restrictedUser + "; role=USER");
        badExchange.setRequestBody("action=create_unit&target_db=forbidden_finance_db&unit_name=transactions&engine_type=DOCUMENT");

        enginesPage.handle(badExchange);
        String badBody = badExchange.getResponseBodyAsString();
        assertTrue(badBody.contains("Access Denied"), "Unauthorized database operation must return Access Denied");
        assertTrue(badBody.contains("forbidden_finance_db"), "Must reference forbidden database");

        // 2. Operation on authorized database -> Permitted
        TestHttpExchange goodExchange = new TestHttpExchange("POST", "/engines");
        goodExchange.getRequestHeaders().set("Cookie", "username=" + restrictedUser + "; role=USER");
        goodExchange.setRequestBody("action=create_unit&target_db=allowed_db&unit_name=allowed_unit&engine_type=DOCUMENT");

        enginesPage.handle(goodExchange);
        String goodBody = goodExchange.getResponseBodyAsString();
        assertFalse(goodBody.contains("Access Denied: User '" + restrictedUser + "'"),
                "Authorized database operation must NOT be denied");
    }

    @JettraTest
    @DisplayName("5. Backwards Compatibility: Legacy user with comma-separated lastName initializes assignedDatabases automatically")
    void testLegacyUserBackwardsCompatibility() {
        UUID id = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));

        // Construct using legacy 7-parameter constructor
        JUser legacyUser = new JUser(id, "legacy_analyst", "db1, db2, db3", "legacy@jettra.io", "+123", true, roles);

        // assignedDatabases should be automatically populated from lastName
        Set<String> assigned = legacyUser.assignedDatabases();
        assertNotNull(assigned, "assignedDatabases must be populated");
        assertEquals(3, assigned.size());
        assertTrue(assigned.contains("db1"));
        assertTrue(assigned.contains("db2"));
        assertTrue(assigned.contains("db3"));

        // isAuthorizedForDatabase must recognize all parsed databases
        assertTrue(legacyUser.isAuthorizedForDatabase("db1"));
        assertTrue(legacyUser.isAuthorizedForDatabase("db2"));
        assertTrue(legacyUser.isAuthorizedForDatabase("db3"));
        assertFalse(legacyUser.isAuthorizedForDatabase("db4"));
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
