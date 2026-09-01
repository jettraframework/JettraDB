package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Unit and UI Integration Test Suite for JettraDB Multi-Model Storage Hierarchy Explorer.
 * Validates TableView row expansion, modal action commands (VER, EDITAR, VERSIONES, ELIMINAR),
 * dynamic TreeView active DB scoping, dashboard modular panels, JettraFlux charts,
 * Java 25 pattern matching on sealed records, and Virtual Thread concurrency.
 */
public class MultiModelExplorerInteractivityTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage page;
    private final JettraJson jsonParser = new JettraJson();

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_explorer_interactivity_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();
        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
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

    @Test
    @DisplayName("TableView: Verify Row Expansion (>) and Detail Panel with Field Attributes")
    void testTableViewRowExpansionAndNestedDetailPanel() {
        String testDb = "retail_store_db";
        // Insert sample documents and key-values
        engine.getStorageCore().put("doc:" + testDb + ":products:prod_101",
                "{\"name\":\"Wireless Headphones\",\"price\":129.99,\"category\":\"Audio\",\"inStock\":true}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());
        engine.getStorageCore().put("kv:" + testDb + ":session_token_1",
                "user_session_payload_active".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        Widget tableUi = page.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", testDb,
                "view_mode", "table"
        ), "dark");

        String html = tableUi.render(Themes.FlatTheme());

        // 1. Verify row expansion toggle button & identifier
        assertTrue(html.contains("toggleTableRowDetail"), "Table must include toggleTableRowDetail function for row expansion");
        assertTrue(html.contains("tbl_row_detail_1"), "Table rows must have unique detail row IDs");
        assertTrue(html.contains("explorer-table-detail-row"), "Detail rows must have explorer-table-detail-row CSS class");

        // 2. Verify nested detail panel content
        assertTrue(html.contains("prod_101"), "Detail row must contain record ID");
        assertTrue(html.contains("Wireless Headphones") || html.contains("category"), "Detail row must render field attributes preview");
        assertTrue(html.contains("📍 doc:retail_store_db:products:prod_101") || html.contains("doc:retail_store_db"), "Detail row must contain storage address");
    }

    @Test
    @DisplayName("TableView: Verify 4 Row Action Buttons (VER, EDITAR, VERSIONES, ELIMINAR)")
    void testTableViewRowActionButtonsAndModalTriggers() {
        String testDb = "action_test_db";
        engine.getStorageCore().put("doc:" + testDb + ":customers:cust_555",
                "{\"name\":\"John Doe\",\"email\":\"john@example.com\"}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        Widget tableUi = page.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", testDb,
                "view_mode", "table"
        ), "dark");

        String html = tableUi.render(Themes.FlatTheme());

        // 1. VER (Inspect Record) action button
        assertTrue(html.contains("openInspectRecordModal"), "Table row must contain VER action button calling openInspectRecordModal");
        assertTrue(html.contains("Ver detalles del registro") || html.contains("fa-eye"), "VER button must have tooltip / eye icon");

        // 2. EDITAR (Edit Record) action button
        assertTrue(html.contains("openUniversalEditModal"), "Table row must contain EDITAR action button calling openUniversalEditModal");
        assertTrue(html.contains("Editar registro") || html.contains("fa-edit"), "EDITAR button must have tooltip / edit icon");

        // 3. VERSIONES (Version History) action button
        assertTrue(html.contains("openUniversalRestoreModal"), "Table row must contain VERSIONES action button calling openUniversalRestoreModal");
        assertTrue(html.contains("Historial de versiones") || html.contains("fa-history"), "VERSIONES button must have tooltip / history icon");

        // 4. ELIMINAR (Delete Record) action button
        assertTrue(html.contains("openUniversalDeleteModal"), "Table row must contain ELIMINAR action button calling openUniversalDeleteModal");
        assertTrue(html.contains("Eliminar registro") || html.contains("fa-trash"), "ELIMINAR button must have tooltip / trash icon");
    }

    @Test
    @DisplayName("TreeView: Verify Scoping to Active DB and Absence of Redundant [Explore DB]")
    void testTreeViewActiveDatabaseScopingAndNoExploreDbButton() {
        String activeDb = "active_scoped_db";
        engine.getStorageCore().put("doc:" + activeDb + ":orders:ord_1",
                "{\"orderId\":\"ord_1\",\"amount\":99.0}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        Widget treeUi = page.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", activeDb,
                "view_mode", "tree"
        ), "dark");

        String html = treeUi.render(Themes.FlatTheme());

        // 1. Scoped strictly to active database
        assertTrue(html.contains(activeDb), "Tree view must render active database");
        assertTrue(html.contains("db_content_1"), "Tree view must render subtree container for active database");
        assertTrue(html.contains("role=\"treeitem\""), "Tree view nodes must have accessible treeitem role");

        // 2. Redundant [Explore DB] button must NOT exist
        assertFalse(html.contains("[Explore DB]"), "Redundant [Explore DB] button must be completely removed");

        // 3. Expand/Collapse controls
        assertTrue(html.contains("expandAllTreeNodes"), "Tree view header must contain Expand All handler");
        assertTrue(html.contains("collapseAllTreeNodes"), "Tree view header must contain Collapse All handler");
    }

    @Test
    @DisplayName("Dashboard: Verify Modular Panels, Metric StatCards, and Native JettraFlux Charts")
    void testDashboardLayoutModularPanelsAndNativeCharts() {
        String targetDb = "analytics_dashboard_db";
        engine.getStorageCore().put("doc:" + targetDb + ":items:item_1", "{\"val\":1}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("kv:" + targetDb + ":key_1", "value_1".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        Widget dashUi = page.buildContent(null, Map.of(
                "tab", "schema",
                "target_db", targetDb
        ), "dark");

        String html = dashUi.render(Themes.FlatTheme());

        // 1. Metric StatCards
        assertTrue(html.contains("TOTAL RECORDS"), "Dashboard must render TOTAL RECORDS metric card");
        assertTrue(html.contains("ACTIVE ENGINES"), "Dashboard must render ACTIVE ENGINES metric card");
        assertTrue(html.contains("SCOPED DATABASE"), "Dashboard must render SCOPED DATABASE metric card");
        assertTrue(html.contains("ENGINE LATENCY"), "Dashboard must render ENGINE LATENCY metric card");

        // 2. Modular Panels & JettraFlux Charts
        assertTrue(html.contains("Multi-Model Storage Breakdown"), "Dashboard must render Storage Breakdown panel");
        assertTrue(html.contains("Engine Performance & Telemetry"), "Dashboard must render Performance Telemetry panel");
        assertTrue(html.contains("espresso-panel") || html.contains("espresso-card") || html.contains("Unified Multi-Model Engine Matrix"),
                "Dashboard must be composed of structured panels/cards");

        // 3. Multi-Model Engine Matrix
        assertTrue(html.contains("Unified Multi-Model Engine Matrix"), "Dashboard must render Engine Matrix card");
        assertTrue(html.contains("DOCUMENT"), "Engine matrix must list DOCUMENT engine");
        assertTrue(html.contains("KEYVALUE"), "Engine matrix must list KEYVALUE engine");
        assertTrue(html.contains("VECTOR"), "Engine matrix must list VECTOR engine");
        assertTrue(html.contains("GRAPH"), "Engine matrix must list GRAPH engine");
        assertTrue(html.contains("Quick Database & Storage Actions"), "Dashboard must render Quick Action toolbar");
    }

    @Test
    @DisplayName("Java 25: Test Sealed HierarchyRowCommand Pattern Matching Dispatch")
    void testJava25SealedHierarchyRowCommandPatternMatching() {
        StorageModalCommands.HierarchyRowCommand viewCmd = new StorageModalCommands.ViewCommand(
                "DOCUMENT", "sales_db", "orders", "ord_99", "{\"amount\":250}", 2
        );
        StorageModalCommands.HierarchyRowCommand editCmd = new StorageModalCommands.EditCommand(
                "KEYVALUE", "cache_db", "default", "sess_01", "new_session_val", new JsonObject()
        );
        StorageModalCommands.HierarchyRowCommand restoreCmd = new StorageModalCommands.RestoreCommand(
                "RECORDS", "hr_db", "employees", "emp_1", 1700000000000L, 3
        );
        StorageModalCommands.HierarchyRowCommand deleteCmd = new StorageModalCommands.DeleteCommand(
                "VECTOR", "ai_db", "embeddings", "vec_42"
        );

        assertEquals("VIEW:DOCUMENT:sales_db:orders:ord_99:v2", StorageModalCommands.dispatchCommand(viewCmd));
        assertEquals("EDIT:KEYVALUE:cache_db:default:sess_01", StorageModalCommands.dispatchCommand(editCmd));
        assertEquals("RESTORE:RECORDS:hr_db:employees:emp_1@1700000000000", StorageModalCommands.dispatchCommand(restoreCmd));
        assertEquals("DELETE:VECTOR:ai_db:embeddings:vec_42", StorageModalCommands.dispatchCommand(deleteCmd));
    }

    @Test
    @DisplayName("Java 25: Test Virtual Thread Asynchronous Query Execution")
    void testVirtualThreadAsyncExplorerQueries() throws InterruptedException, ExecutionException {
        int queryCount = 20;
        ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor();
        List<Callable<JsonObject>> tasks = new ArrayList<>();

        for (int i = 0; i < queryCount; i++) {
            final int id = i;
            tasks.add(() -> {
                String db = "vt_db_" + id;
                engine.getStorageCore().put("doc:" + db + ":items:doc_" + id,
                        ("{\"itemId\":\"doc_" + id + "\",\"seq\":" + id + "}").getBytes(StandardCharsets.UTF_8),
                        System.currentTimeMillis());
                return page.buildDatabaseHierarchyJson(db);
            });
        }

        List<Future<JsonObject>> futures = vExecutor.invokeAll(tasks);
        assertEquals(queryCount, futures.size());

        for (int i = 0; i < queryCount; i++) {
            JsonObject res = futures.get(i).get();
            assertNotNull(res);
            assertEquals("SUCCESS", res.getAsString("status"));
            assertEquals("vt_db_" + i, res.getAsString("database"));
            assertTrue(res.getAsBoolean("hasComponents"));
        }

        vExecutor.shutdown();
    }
}
