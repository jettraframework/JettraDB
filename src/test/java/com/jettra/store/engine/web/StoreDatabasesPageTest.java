package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
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
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Comprehensive Integration and Unit Test Suite for StoreDatabasesPage:
 * 1. Verification of removal of obsolete action elements (+ ADD COMPONENT / RECORD, INSPECT ENTITIES).
 * 2. Verification of unified JettraCardPanel layout and multi-model components aggregation.
 * 3. Verification of native JettraConfirmDialog modal for destructive database deletion.
 * 4. Verification of RBAC security filtering (ADMIN, Scoped USER, and Unauthorized EmptyStateComponent).
 * 5. Verification of end-to-end database drop flow.
 */
@NotRequiresRunningServer
public class StoreDatabasesPageTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDatabasesPage databasesPage;
    private JUserRepository userRepo;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_databases_page_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        userRepo = new JUserRepositoryImpl();
        databasesPage = new StoreDatabasesPage(engine, authManager);

        // Seed some sample databases into storage core
        // db1: "customers_db" with RECORDS and DOCUMENT
        engine.getStorageCore().put("rec:customers_db:c_1", "{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:customers_db:c_2", "{\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // db2: "finance_db" with VECTOR
        engine.getStorageCore().put("vec:finance_db:v_1", "{\"coords\":[0.1, 0.2]}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // db3: "admin_private_db" with RECORDS
        engine.getStorageCore().put("rec:admin_private_db:s_1", "{\"secret\":true}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityDbTestCleanup.purgeNonAdminTestUsers(userRepo, null);
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
    @DisplayName("1. Obsolete actions (+ ADD COMPONENT / RECORD and INSPECT ENTITIES) are completely removed from the page")
    void testObsoleteActionsAreRemoved() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "Admin access must yield HTTP 200 OK");
        String body = exchange.getResponseBodyAsString();

        // Must NOT contain the obsolete buttons
        assertFalse(body.contains("Add Component / Record"), "Obsolete '+ Add Component / Record' button must be removed");
        assertFalse(body.contains("Inspect Entities"), "Obsolete 'Inspect Entities' button must be removed");
        assertFalse(body.contains("toggleEntitiesViewer"), "Obsolete 'toggleEntitiesViewer' must be removed");
        assertFalse(body.contains("toggleEntities"), "Obsolete 'toggleEntities' must be removed");
    }

    @JettraTest
    @DisplayName("2. Page renders unified JettraCardPanel hosting Multi-Model Components Overview and Active Databases")
    void testUnifiedPanelStructure() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("Multi-Model Database Workspace"), "Must render unified JettraCardPanel title");
        assertTrue(body.contains("Multi-Model Storage Components Overview"), "Must render multi-model components overview section");
        assertTrue(body.contains("Authorized Active Databases"), "Must render authorized active databases section");
        assertTrue(body.contains("customers_db"), "Must list customers_db for ADMIN");
        assertTrue(body.contains("finance_db"), "Must list finance_db for ADMIN");
        assertTrue(body.contains("admin_private_db"), "Must list admin_private_db for ADMIN");
    }

    @JettraTest
    @DisplayName("3. Destructive deletion integrates native JettraConfirmDialog modal with target item and warning")
    void testNativeJettraConfirmDialogIntegration() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Dialog presence assertions
        assertTrue(body.contains("dropDbConfirmDialog"), "Must include dropDbConfirmDialog modal element");
        assertTrue(body.contains("Confirm Database Deletion"), "Must render dialog title");
        assertTrue(body.contains("role=\"alertdialog\""), "Must use alertdialog accessibility role");
        assertTrue(body.contains("aria-modal=\"true\""), "Must block modal context");
        assertTrue(body.contains("Confirm Deletion"), "Must render explicit confirmation button");
        assertTrue(body.contains("Cancel"), "Must render cancel button");
        assertTrue(body.contains("JettraConfirmDialog.open"), "Must wire client opening call");
        assertTrue(body.contains("confirmDropDb"), "Must define confirmDropDb helper");
    }

    @JettraTest
    @DisplayName("4. RBAC Permission Filtering: Scoped user only sees authorized databases via Streams and Predicates")
    void testScopedUserOnlySeesAuthorizedDatabases() throws IOException {
        // Provision a user specifically scoped to "customers_db"
        UUID userId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true));
        JUser scopedUser = new JUser(userId, "scoped_analyst", "customers_db", "analyst@jettra.io", "+12345", true, roles);
        userRepo.save(scopedUser);

        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=scoped_analyst; role=USER");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Authorized: customers_db
        assertTrue(body.contains("customers_db"), "Scoped user must see customers_db");

        // Unauthorized: finance_db and admin_private_db must NOT be present in rendered active databases
        assertFalse(body.contains("finance_db"), "Scoped user must NOT see finance_db");
        assertFalse(body.contains("admin_private_db"), "Scoped user must NOT see admin_private_db");
    }

    @JettraTest
    @DisplayName("5. RBAC Permission Filtering: User without any authorized databases renders EmptyStateComponent")
    void testUnauthorizedUserRendersEmptyState() throws IOException {
        // Provision a user scoped to "non_existent_db"
        UUID userId = UUID.randomUUID();
        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), "READ_ONLY", true));
        JUser restrictedUser = new JUser(userId, "restricted_user", "unknown_warehouse_db", "restricted@jettra.io", "+12345", true, roles);
        userRepo.save(restrictedUser);

        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=restricted_user; role=USER");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("No Authorized Databases"), "Must render EmptyStateComponent title");
        assertTrue(body.contains("jettra-empty-state"), "Must include empty state styling class");
        assertFalse(body.contains("finance_db"), "Must not display unauthorized database finance_db");
        assertFalse(body.contains("admin_private_db"), "Must not display unauthorized database admin_private_db");
    }

    @JettraTest
    @DisplayName("6. isAuthorizedForDatabase logic correctly validates admin, scoped users, and negative cases")
    void testIsAuthorizedForDatabaseMethod() {
        SecurityPrincipal adminPrincipal = SecurityPrincipal.of("superadmin", "ADMIN", "IT");
        SecurityPrincipal userPrincipal = SecurityPrincipal.of("john_dev", "USER", "");
        SecurityPrincipal deptPrincipal = SecurityPrincipal.of("finance_lead", "USER", "finance_db");

        UUID uId = UUID.randomUUID();
        JUser userInRepo = new JUser(uId, "john_dev", "sales_db", "john@jettra.io", "+123", true,
                Set.of(new JRole(UUID.randomUUID(), "READ_WRITE", true)));
        List<JUser> userList = List.of(userInRepo);

        // Admin has access to anything
        assertTrue(databasesPage.isAuthorizedForDatabase(adminPrincipal, "customers_db", userList));
        assertTrue(databasesPage.isAuthorizedForDatabase(adminPrincipal, "secret_db", userList));

        // Dept matched principal
        assertTrue(databasesPage.isAuthorizedForDatabase(deptPrincipal, "finance_db", userList));
        assertFalse(databasesPage.isAuthorizedForDatabase(deptPrincipal, "customers_db", userList));

        // John dev is scoped to sales_db in repo
        assertTrue(databasesPage.isAuthorizedForDatabase(userPrincipal, "sales_db", userList));
        assertFalse(databasesPage.isAuthorizedForDatabase(userPrincipal, "finance_db", userList));

        // Null principal has no access
        assertFalse(databasesPage.isAuthorizedForDatabase(null, "sales_db", userList));
    }

    @JettraTest
    @DisplayName("7. Drop database action executes successfully and purges database components")
    void testDropDatabaseExecution() throws IOException {
        // First verify database exists in storage
        Map<String, byte[]> keysBefore = engine.getStorageCore().scanPrefix("rec:admin_private_db:");
        assertFalse(keysBefore.isEmpty(), "admin_private_db must have keys prior to drop");

        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        exchange.setRequestBody("action=drop_db&target_db=admin_private_db");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("dropped"), "Response must indicate database was dropped");
        assertTrue(body.contains("admin_private_db"), "Response must mention dropped database name");

        // Verify keys in storage were purged
        Map<String, byte[]> keysAfter = engine.getStorageCore().scanPrefix("rec:admin_private_db:");
        assertTrue(keysAfter.isEmpty(), "Keys for admin_private_db must be completely purged from storage core");
    }

    @JettraTest
    @DisplayName("8. system_db cannot be renamed nor deleted in UI and shows SYSTEM PROTECTED status")
    void testSystemDbIsProtectedFromRenameAndDeleteInUI() throws IOException {
        // Ensure system_db exists in storage
        engine.getStorageCore().put("rec:system_db:sys_cfg", "{\"cluster\":\"node1\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("system_db"), "Must render system_db");
        assertTrue(body.contains("SYSTEM PROTECTED"), "Must indicate system_db is protected");
        assertFalse(body.contains("openRenameDbModal('system_db')"), "Must NOT render rename button for system_db");
        assertFalse(body.contains("dropDbConfirmDialog', 'system_db'"), "Must NOT render drop dialog trigger for system_db");
    }

    @JettraTest
    @DisplayName("9. Backend rejects rename_db and drop_db requests targeting system_db")
    void testSystemDbIsProtectedFromRenameAndDeleteInBackend() throws IOException {
        // 1. Attempt rename_db on system_db
        TestHttpExchange renameExchange = new TestHttpExchange("POST", "/databases");
        renameExchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        renameExchange.setRequestBody("action=rename_db&old_db=system_db&new_db=hacked_db");

        databasesPage.handle(renameExchange);

        assertEquals(200, renameExchange.getResponseCode());
        String renameBody = renameExchange.getResponseBodyAsString();
        assertTrue(renameBody.contains("The system database 'system_db' is protected and cannot be renamed."),
                "Must reject rename attempt on system_db");

        // 2. Attempt drop_db on system_db
        TestHttpExchange dropExchange = new TestHttpExchange("POST", "/databases");
        dropExchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");
        dropExchange.setRequestBody("action=drop_db&target_db=system_db");

        databasesPage.handle(dropExchange);

        assertEquals(200, dropExchange.getResponseCode());
        String dropBody = dropExchange.getResponseBodyAsString();
        assertTrue(dropBody.contains("The system database 'system_db' is protected and cannot be deleted."),
                "Must reject drop attempt on system_db");
    }

    @JettraTest
    @DisplayName("9. Dashboard operational metrics are encapsulated inside JettraFlux Panel, PanelHeader, PanelBody, and MetricCards")
    void testDashboardMetricsPanelEncapsulation() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must render PanelHeader title and subtitle
        assertTrue(body.contains("Engine & Cluster Operational Metrics"), "Must render PanelHeader title");
        assertTrue(body.contains("Real-time distributed telemetry"), "Must render PanelHeader subtitle");

        // Must render Panel container classes
        assertTrue(body.contains("jettra-panel"), "Must include jettra-panel class");
        assertTrue(body.contains("jettra-panel-header"), "Must include jettra-panel-header class");
        assertTrue(body.contains("jettra-panel-body"), "Must include jettra-panel-body class");

        // Must render encapsulated MetricCards
        assertTrue(body.contains("jettra-metric-card"), "Must contain JettraFlux MetricCard elements");
        assertTrue(body.contains("Active Databases"), "Must render Active Databases metric card");
        assertTrue(body.contains("Multi-Model Components"), "Must render Multi-Model Components metric card");
        assertTrue(body.contains("Java 25 Records"), "Must render Java 25 Records metric card");
        assertTrue(body.contains("Total Stored Entities"), "Must render Total Stored Entities metric card");

        // Must render badges and subtext
        assertTrue(body.contains("LSM / B-Tree Storage"), "Must render storage engine subtext");
        assertTrue(body.contains("9 Supported Engines"), "Must render engine models subtext");
        assertTrue(body.contains("JEP 450 Compact Headers"), "Must render records subtext");
        assertTrue(body.contains("Raft State Synchronized"), "Must render raft state subtext");
        assertTrue(body.contains("espresso-badge"), "Must render JettraFlux Badge component");
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
