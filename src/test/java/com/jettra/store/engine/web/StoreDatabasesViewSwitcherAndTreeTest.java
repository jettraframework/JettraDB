package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Test suite for ViewSwitcher (List/Tree) in Multi-Model Database Workspace
 * and Enhanced User Selection Dialog in StoreDatabasesPage using JettraTest.
 */
@NotRequiresRunningServer
public class StoreDatabasesViewSwitcherAndTreeTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_view_switcher_tree_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl();
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        authManager = new AuthManager(systemUserRepo);
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);

        // Seed admin user baseline
        if (!systemUserRepo.existsByUsername("admin")) {
            systemUserRepo.save(SystemUser.create(
                "admin",
                SystemUserRepositoryImpl.hashPassword("admin123"),
                "admin@jettra.io",
                "DB_ADMIN",
                Set.of("*")
            ));
        }

        // Seed databases with multi-model components
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("rec:inventory_db:part_1", "{\"sku\":\"P101\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:inventory_db:item_2", "{\"desc\":\"Sensor\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("vec:ai_knowledge_db:emb_1", "{\"vector\":[0.1, 0.9]}".getBytes(StandardCharsets.UTF_8), now);

        // Seed scoped users
        systemUserRepo.save(SystemUser.create("inv_operator", "hash", "inv@jettra.io", "READ_WRITE", Set.of("inventory_db", "warehouse_db")));
        systemUserRepo.save(SystemUser.create("ai_researcher", "hash", "ai@jettra.io", "READ_ONLY", Set.of("ai_knowledge_db")));
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
    @DisplayName("1. Multi-Model Database Workspace renders ViewSwitcher with List View and Tree View controls")
    void testWorkspaceRendersViewSwitcher() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String html = exchange.getResponseBodyAsString();

        // 1. Workspace header & panel
        assertTrue(html.contains("Multi-Model Database Workspace"), "Must render workspace panel title");

        // 2. ViewSwitcher container & ARIA tablist
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher\""), "Must contain ViewSwitcher element");
        assertTrue(html.contains("class=\"jettra-view-switcher"), "Must declare view switcher CSS class");
        assertTrue(html.contains("role=\"tablist\""), "Must declare WAI-ARIA tablist role");

        // 3. Segmented view tabs (List View & Tree View)
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_tab_list\""), "Must render List View tab button");
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_tab_tree\""), "Must render Tree View tab button");
        assertTrue(html.contains("fas fa-th-list"), "Must render List View icon");
        assertTrue(html.contains("fas fa-project-diagram"), "Must render Tree View icon");
        assertTrue(html.contains("List View"), "Must render List View text");
        assertTrue(html.contains("Tree View"), "Must render Tree View text");

        // 4. View panels
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_panel_list\""), "Must render List View panel");
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_panel_tree\""), "Must render Tree View panel");

        // 5. Default perspective is List View
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_tab_list\" class=\"view-switcher-btn active\"")
            || html.contains("aria-selected=\"true\""), "List View tab must be active by default");
    }

    @JettraTest
    @DisplayName("2. Tree View perspective renders FluxTree with hierarchical databases, storage engines, and scoped users")
    void testHierarchicalTreeViewStructure() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases?view=tree");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String html = exchange.getResponseBodyAsString();

        // 1. Tree View container & FluxTree
        assertTrue(html.contains("id=\"databases-workspace-tree\""), "Must render databases-workspace-tree FluxTree component");
        assertTrue(html.contains("role=\"tree\""), "FluxTree must declare WAI-ARIA tree role");
        assertTrue(html.contains("Hierarchical Storage Tree Explorer"), "Must render tree toolbar title");

        // 2. Expand All & Collapse All toolbar buttons
        assertTrue(html.contains("Expand All"), "Must render Expand All toolbar button");
        assertTrue(html.contains("Collapse All"), "Must render Collapse All toolbar button");

        // 3. Database tree nodes
        assertTrue(html.contains("node_tree_node_db_inventory_db"), "Must render inventory_db tree node");
        assertTrue(html.contains("node_tree_node_db_ai_knowledge_db"), "Must render ai_knowledge_db tree node");
        assertTrue(html.contains("node_tree_node_db_system_db"), "Must render system_db tree node");

        // 4. Multi-Model Storage Components branch
        assertTrue(html.contains("Storage Components"), "Must render Storage Components branch");
        assertTrue(html.contains("RECORDS"), "Must list RECORDS engine component");
        assertTrue(html.contains("DOCUMENT"), "Must list DOCUMENT engine component");
        assertTrue(html.contains("VECTOR"), "Must list VECTOR engine component");

        // 5. Scoped Users branch
        assertTrue(html.contains("Authorized Scoped Users"), "Must render Authorized Scoped Users branch");
        assertTrue(html.contains("inv_operator"), "Must list inv_operator user under inventory_db");
        assertTrue(html.contains("ai_researcher"), "Must list ai_researcher user under ai_knowledge_db");

        // 6. Action button in tree node
        assertTrue(html.contains("openAssignUserModal('inventory_db')"), "Tree node must declare ASSIGN USER action trigger");
    }

    @JettraTest
    @DisplayName("3. Request parameter ?view=tree dynamically sets Tree View as initially active")
    void testTreeViewParamActivation() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases?view=tree");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String html = exchange.getResponseBodyAsString();

        // Tree View panel should be displayed (display:block) and tab active (aria-selected="true")
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_tab_tree\"") && html.contains("aria-selected=\"true\""),
            "Tree View tab must be aria-selected=true when requested via ?view=tree");
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_panel_tree\" role=\"tabpanel\" aria-labelledby=\"dbWorkspaceViewSwitcher_tab_tree\" class=\"view-switcher-panel\" style=\"display:block; width:100%;\""),
            "Tree View panel must have display:block when requested via ?view=tree");
        assertTrue(html.contains("id=\"dbWorkspaceViewSwitcher_panel_list\" role=\"tabpanel\" aria-labelledby=\"dbWorkspaceViewSwitcher_tab_list\" class=\"view-switcher-panel\" style=\"display:none; width:100%;\""),
            "List View panel must have display:none when requested via ?view=tree");
    }

    @JettraTest
    @DisplayName("4. ASSIGN USER button triggers modal containing UserSelectionTable with selection/deselection controls")
    void testAssignUserModalUserSelectionControls() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String html = exchange.getResponseBodyAsString();

        // 1. ASSIGN USER button presence in page
        assertTrue(html.contains("ASSIGN USER"), "Must render ASSIGN USER buttons");
        assertTrue(html.contains("openAssignUserModal('inventory_db')"), "Must wire openAssignUserModal for inventory_db");

        // 2. Modal structure
        assertTrue(html.contains("id=\"assignUserModal\""), "Must contain assignUserModal dialog");
        assertTrue(html.contains("Assign User to"), "Must render dialog title");

        // 3. UserSelectionTable with selection controls
        assertTrue(html.contains("id=\"assignUserSelectionTable\""), "Must render UserSelectionTable widget");
        assertTrue(html.contains("toggle-item-checkbox"), "Must render checkbox selection controls for users");
        assertTrue(html.contains("data-username=\"inv_operator\""), "Must render checkbox for inv_operator");
        assertTrue(html.contains("data-username=\"ai_researcher\""), "Must render checkbox for ai_researcher");
        assertTrue(html.contains("data-username=\"admin\""), "Must render checkbox for admin");

        // 4. Quick bulk controls
        assertTrue(html.contains("Select All"), "Must render Select All quick control");
        assertTrue(html.contains("Clear All"), "Must render Clear All quick control");
        assertTrue(html.contains("id=\"assignUserSelectionTable_counter\""), "Must render live selection counter badge");
        assertTrue(html.contains("id=\"assignUserSubmitBtn\""), "Must render ASSIGN USER submit button");
    }

    @JettraTest
    @DisplayName("5. User authorization synchronization persists selective access granting and revoking")
    void testSelectiveAccessGrantingAndRevoking() throws IOException {
        // Pre-state:
        // inv_operator has access to inventory_db
        // ai_researcher has access to ai_knowledge_db (NOT inventory_db)
        SystemUser preInv = systemUserRepo.findByUsername("inv_operator").orElseThrow();
        assertTrue(preInv.hasDatabaseAccess("inventory_db"));

        SystemUser preAi = systemUserRepo.findByUsername("ai_researcher").orElseThrow();
        assertFalse(preAi.hasDatabaseAccess("inventory_db"));

        // Action: Synchronize inventory_db users: select ai_researcher, deselect inv_operator
        TestHttpExchange exchange = new TestHttpExchange("POST", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.setRequestBody("action=assign_user&assign_mode=existing&target_db=inventory_db&assigned_users=admin,ai_researcher");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("Sincronización de usuarios completada"), "Response must report sync success");

        // Post-state:
        // ai_researcher gained access to inventory_db
        SystemUser postAi = systemUserRepo.findByUsername("ai_researcher").orElseThrow();
        assertTrue(postAi.hasDatabaseAccess("inventory_db"), "ai_researcher must have been granted access to inventory_db");
        assertTrue(postAi.hasDatabaseAccess("ai_knowledge_db"), "ai_researcher must preserve prior db access");

        // inv_operator lost access to inventory_db (deselected)
        SystemUser postInv = systemUserRepo.findByUsername("inv_operator").orElseThrow();
        assertFalse(postInv.hasDatabaseAccess("inventory_db"), "inv_operator must have been revoked from inventory_db");
        assertTrue(postInv.hasDatabaseAccess("warehouse_db"), "inv_operator must retain warehouse_db");
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
