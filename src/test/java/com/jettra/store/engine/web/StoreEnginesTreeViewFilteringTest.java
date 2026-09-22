package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.web.view.StorageTreeView;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class StoreEnginesTreeViewFilteringTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private HierarchyExplorerService hierarchyService;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_tree_filter_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        hierarchyService = new HierarchyExplorerService(engine);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    @JettraTest
    @DisplayName("1. Tree View in /engines strictly renders ONLY the database currently selected in the top bar")
    void testTreeViewRendersOnlyActiveSelectedDatabase() {
        String activeDb = "inventory_db";
        String otherDb1 = "hr_employees_db";
        String otherDb2 = "finance_audit_db";

        long now = System.currentTimeMillis();
        // Seed items in activeDb
        engine.getStorageCore().put("doc:" + activeDb + ":products:prod_1", "{\"sku\":\"SKU-001\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("rec:" + activeDb + ":stocks:stk_1", "{\"qty\":50}".getBytes(StandardCharsets.UTF_8), now);

        // Seed items in otherDb1 and otherDb2
        engine.getStorageCore().put("doc:" + otherDb1 + ":staff:emp_100", "{\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:" + otherDb2 + ":ledgers:led_500", "{\"amount\":10000}".getBytes(StandardCharsets.UTF_8), now);

        // Verify that discovery sees all 3 databases in the system
        Set<String> allDiscovered = hierarchyService.discoverAllDatabases();
        assertTrue(allDiscovered.contains(activeDb), "Discovery must see activeDb");
        assertTrue(allDiscovered.contains(otherDb1), "Discovery must see otherDb1");
        assertTrue(allDiscovered.contains(otherDb2), "Discovery must see otherDb2");

        // Build Tree View for activeDb (specified by Connected as admin @ selector)
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", activeDb);

        Widget treeWidget = StorageTreeView.build(
                "DOCUMENT",
                activeDb,
                "products",
                "/engines?engine=DOCUMENT",
                params,
                hierarchyService
        );

        assertNotNull(treeWidget, "StorageTreeView widget must not be null");
        String html = treeWidget.render(Themes.FlatTheme());
        assertNotNull(html, "Rendered HTML must not be null");

        // 1. MUST contain active selected database
        assertTrue(html.contains("node_db_" + activeDb), "Tree must contain active database root node");
        assertTrue(html.contains(activeDb), "Tree must display active database name");
        assertTrue(html.contains("products"), "Tree must display collection under active database");
        assertTrue(html.contains("prod_1"), "Tree must display item under active database");

        // 2. MUST NOT contain other databases in the tree
        assertFalse(html.contains("node_other_db_" + otherDb1), "Tree must NOT render node for otherDb1");
        assertFalse(html.contains("node_other_db_" + otherDb2), "Tree must NOT render node for otherDb2");
        assertFalse(html.contains("node_db_" + otherDb1), "Tree must NOT render primary node for otherDb1");
        assertFalse(html.contains("node_db_" + otherDb2), "Tree must NOT render primary node for otherDb2");
        assertFalse(html.contains("emp_100"), "Tree must NOT display items belonging to foreign databases");
        assertFalse(html.contains("led_500"), "Tree must NOT display items belonging to foreign databases");
    }

    @JettraTest
    @DisplayName("2. Switching selected database dynamically updates the rendered Tree View context")
    void testTreeViewSwitchesContextWhenSelectedDatabaseChanges() {
        String dbA = "db_alpha";
        String dbB = "db_beta";

        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:" + dbA + ":col_a:item_a", "{\"val\":\"A\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:" + dbB + ":col_b:item_b", "{\"val\":\"B\"}".getBytes(StandardCharsets.UTF_8), now);

        // When Connected as admin @ db_alpha
        Widget treeAlpha = StorageTreeView.build("DOCUMENT", dbA, "col_a", "/engines?engine=DOCUMENT", Map.of("target_db", dbA), hierarchyService);
        String htmlAlpha = treeAlpha.render(Themes.FlatTheme());
        assertTrue(htmlAlpha.contains("node_db_" + dbA));
        assertTrue(htmlAlpha.contains("col_a"));
        assertFalse(htmlAlpha.contains("node_db_" + dbB));
        assertFalse(htmlAlpha.contains("col_b"));

        // When Connected as admin @ db_beta
        Widget treeBeta = StorageTreeView.build("DOCUMENT", dbB, "col_b", "/engines?engine=DOCUMENT", Map.of("target_db", dbB), hierarchyService);
        String htmlBeta = treeBeta.render(Themes.FlatTheme());
        assertTrue(htmlBeta.contains("node_db_" + dbB));
        assertTrue(htmlBeta.contains("col_b"));
        assertFalse(htmlBeta.contains("node_db_" + dbA));
        assertFalse(htmlBeta.contains("col_a"));
    }
}
