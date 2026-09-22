package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.test.TestDatabaseCleanup;
import com.jettra.store.engine.web.page.StoreEnginesPage;
import com.jettra.store.engine.web.view.StorageTableView;
import com.jettra.store.engine.web.view.StorageTreeView;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JettraJson;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the interactivity and composite hierarchy operations for TreeView and TableView on /engines.
 * Verifies the Expand ALL and Collapse ALL listeners, Composite traversal of hierarchical nodes and tabular rows,
 * and JettraFlux component exclusivity.
 */
@NotRequiresRunningServer
public class ExplorerViewInteractivityTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage enginesPage;
    private final JettraJson jsonParser = new JettraJson();

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_interactivity_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        // Seed sample multi-model data
        String db = "inventory_db";
        engine.getStorageCore().put("doc:" + db + ":items:item_1",
                "{\"sku\":\"SKU-001\",\"qty\":100}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:" + db + ":items:item_2",
                "{\"sku\":\"SKU-002\",\"qty\":250}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("kv:" + db + ":active_session",
                "session_value_abc".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        enginesPage = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. Toolbar: Expand ALL and Collapse ALL buttons render with unified explorer listeners")
    void testToolbarExpandAndCollapseButtonsRender() {
        Widget content = enginesPage.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", "inventory_db",
                "view_mode", "tree"
        ), "dark");

        String html = content.render(Themes.FlatTheme());

        assertTrue(html.contains("Expand All"), "Toolbar must contain 'Expand All' action button");
        assertTrue(html.contains("Collapse All"), "Toolbar must contain 'Collapse All' action button");
        assertTrue(html.contains("onclick=\"expandAllExplorerView()\""),
                "Expand button must invoke expandAllExplorerView() listener");
        assertTrue(html.contains("onclick=\"collapseAllExplorerView()\""),
                "Collapse button must invoke collapseAllExplorerView() listener");
    }

    @JettraTest
    @DisplayName("2. Client Script: expandAllExplorerView and collapseAllExplorerView define recursive Composite traversal")
    void testExplorerClientScriptDefinesCompositeTraversal() {
        Widget scriptWidget = enginesPage.buildModalsScript();
        String script = scriptWidget.render(Themes.FlatTheme());

        assertTrue(script.contains("function expandAllExplorerView()"),
                "Script must define expandAllExplorerView() function");
        assertTrue(script.contains("function collapseAllExplorerView()"),
                "Script must define collapseAllExplorerView() function");
        assertTrue(script.contains("expandAllTableRows"),
                "expandAllExplorerView must call expandAllTableRows() for composite tabular rows");
        assertTrue(script.contains("collapseAllTableRows"),
                "collapseAllExplorerView must call collapseAllTableRows() for composite tabular rows");
    }

    @JettraTest
    @DisplayName("3. TableView: StorageTableView generates composite rows and expansion listeners")
    void testStorageTableViewCompositeRowsAndExpandListeners() {
        List<StorageTableView.FlatRecordItem> items = new ArrayList<>();
        items.add(new StorageTableView.FlatRecordItem(
                "DOCUMENT", "#3b82f6", "fas fa-file-alt",
                "inventory_db", "items", "item_1", 1,
                "{\"sku\":\"SKU-001\",\"qty\":100}", "eyJza3UiOiJTS1UtMDAxIn0=", ""
        ));

        Widget tableWidget = StorageTableView.build("DOCUMENT", "inventory_db", "items",
                "/engines?engine=", items, Map.of("expand", "true"), jsonParser);

        String tableHtml = tableWidget.render(Themes.FlatTheme());

        assertTrue(tableHtml.contains("expandAllTableRows"),
                "TableView script must contain expandAllTableRows function");
        assertTrue(tableHtml.contains("collapseAllTableRows"),
                "TableView script must contain collapseAllTableRows function");
        assertTrue(tableHtml.contains("explorer-table-detail-row"),
                "TableView must render expandable composite child row for record payload");
    }

    @JettraTest
    @DisplayName("4. TreeView: StorageTreeView renders composite hierarchy nodes with JettraFlux components")
    void testStorageTreeViewHierarchyRendering() {
        Widget content = enginesPage.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", "inventory_db",
                "view_mode", "tree"
        ), "dark");

        String html = content.render(Themes.FlatTheme());

        assertTrue(html.contains("Multi-Model Storage Hierarchy Explorer"),
                "Explorer must render Multi-Model Storage Hierarchy Explorer title");
        assertTrue(html.contains("Tree View"), "Explorer must contain Tree View button");
        assertTrue(html.contains("Table View"), "Explorer must contain Table View button");
    }
}
