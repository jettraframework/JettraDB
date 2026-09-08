package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.StorageHierarchyNodeData;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.flux.widgets.FluxTree;
import io.jettra.flux.widgets.FluxTreeNode;
import io.jettra.flux.widgets.JettraTreeNode;
import io.jettra.flux.widgets.JettraCollapsible;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.Span;
import io.jettra.flux.widgets.FluxTreeVisitor;
import io.jettra.flux.widgets.NodeExpansionState;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.DisplayName;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.jettra.test.core.JettraAssert.*;

import io.jettra.test.annotation.NotRequiresRunningServer;

/**
 * Unit & Integration test suite for Multi-Model Storage Hierarchy Explorer Tree View.
 * Validates:
 * 1. Multi-model hierarchy hydration (Engines, Databases, Units, Items) via JettraFlux.
 * 2. Deterministic expandAll() and collapseAll() recursive state mutation and consistency.
 * 3. Composite and Visitor pattern traversal over storage metadata.
 * 4. WAI-ARIA accessibility compliance and reactive action wiring.
 */
@NotRequiresRunningServer
public class MultiModelStorageTreeHierarchyTest {

    private static JettraStorageEngine engine;
    private static HierarchyExplorerService hierarchyService;
    private static String testPath;

    @BeforeAll
    public static void setUp() {
        initEngine();
    }

    @AfterAll
    public static void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (testPath != null) {
            deleteDir(new File(testPath));
        }
    }

    private static synchronized void initEngine() {
        if (engine == null) {
            testPath = "/tmp/jettra_tree_test_" + System.currentTimeMillis() + "_" + System.nanoTime();
            engine = new JettraStorageEngine(testPath);
            engine.start();
            hierarchyService = new HierarchyExplorerService(engine);
        }
    }

    private static void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteDir(f);
            }
        }
        dir.delete();
    }

    @JettraTest
    @DisplayName("Test Multi-Model Tree Hierarchy: hydrate engines, units, and items with JettraFlux")
    public void testMultiModelTreeHierarchyHydrationAndRendering() {
        initEngine();
        String dbName = "multimodel_store_db";
        long now = System.currentTimeMillis();

        // 1. Insert records across multiple storage engines
        engine.getStorageCore().put("doc:" + dbName + ":users:usr_101",
                "{\"name\":\"Alice\",\"role\":\"Admin\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:" + dbName + ":auth_tokens:tok_999",
                "jwt.session.token.value".getBytes(StandardCharsets.UTF_8), now);

        // 2. Build Storage Tree View
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", dbName);

        Widget treeWidget = StorageTreeView.build(
                "DOCUMENT",
                dbName,
                "users",
                "/engines?engine=DOCUMENT",
                params,
                hierarchyService
        );

        assertNotNull(treeWidget, "StorageTreeView widget must not be null");

        String html = treeWidget.render(Themes.FlatTheme());
        assertNotNull(html, "Rendered HTML must not be null");

        // 3. Verify Database level
        assertTrue(html.contains(dbName), "Tree must contain target database name");
        assertTrue(html.contains("db_content_1"), "Tree must contain legacy container db_content_1 for contract compliance");
        assertTrue(html.contains("role=\"treeitem\""), "Tree nodes must declare role=treeitem");
        assertTrue(html.contains("role=\"tree\""), "Tree root must declare role=tree");

        // 4. Verify Multi-Model Engines and Units
        assertTrue(html.contains("DOCUMENT"), "Tree must display DOCUMENT engine branch");
        assertTrue(html.contains("users"), "Tree must display collection/unit 'users'");
        assertTrue(html.contains("usr_101"), "Tree must display item 'usr_101'");

        // 5. Verify Item Actions (Inspect, Versions, Edit, Delete)
        assertTrue(html.contains("openInspectRecordModal"), "Item node must have Inspect Record action");
        assertTrue(html.contains("openUniversalRestoreModal"), "Item node must have Historical Versions action");
        assertTrue(html.contains("openUniversalEditModal"), "Item node must have Edit Record action");
        assertTrue(html.contains("openUniversalDeleteModal"), "Item node must have Delete Record action");

        // 6. Verify Deterministic Client Controller integration
        assertTrue(html.contains("FluxTree.toggle"), "Tree view must embed FluxTree.toggle controller");
        assertTrue(html.contains("FluxTree.toggleDetails"), "Tree view must embed FluxTree.toggleDetails controller");
        assertTrue(html.contains("FluxTree.expandAll"), "Tree view must embed FluxTree.expandAll controller");
        assertTrue(html.contains("FluxTree.collapseAll"), "Tree view must embed FluxTree.collapseAll controller");

        // 7. Verify Expandable Technical Details Panels and Leaf Toggles
        assertTrue(html.contains("flux-tree-details-panel"), "Tree must contain collapsible technical details panels");
        assertTrue(html.contains("btn_toggle_details_node_item_DOCUMENT_users_usr_101"), "Item node must have details toggle button");
        assertTrue(html.contains("📍 doc:" + dbName + ":users:usr_101"), "Detail panel must render primary storage address");
        assertTrue(html.contains("Alice") || html.contains("Admin"), "Detail panel must render record attributes preview");
        assertTrue(html.contains("Mem:"), "Detail panel must render memory size badge");
    }

    @JettraTest
    @DisplayName("Test State & Visitor Patterns: expandAll, collapseAll, and Visitor traversal on multi-model tree")
    public void testTreeExpandAllAndCollapseAllStateConsistency() {
        // Construct an in-memory FluxTree representing multi-model storage hierarchy
        FluxTreeNode<StorageHierarchyNodeData> dbNode = FluxTreeNode.of(
                "db_ecommerce", "ecommerce_db", StorageHierarchyNodeData.forDatabase("DOCUMENT", "ecommerce_db")
        ).icon("fas fa-database");

        FluxTreeNode<StorageHierarchyNodeData> docEngineNode = FluxTreeNode.of(
                "eng_doc", "DOCUMENT Engine", StorageHierarchyNodeData.forEngine("DOCUMENT")
        ).icon("fas fa-file-code");

        FluxTreeNode<StorageHierarchyNodeData> collCustomers = FluxTreeNode.of(
                "unit_customers", "customers (2 items)", StorageHierarchyNodeData.forUnit("DOCUMENT", "ecommerce_db", "customers", 2)
        ).icon("fas fa-folder");

        FluxTreeNode<StorageHierarchyNodeData> item1 = FluxTreeNode.of(
                "item_c1", "cust_001", StorageHierarchyNodeData.forItem("DOCUMENT", "ecommerce_db", "customers", "cust_001", 1, 0, "", "{}", "", "")
        );
        FluxTreeNode<StorageHierarchyNodeData> item2 = FluxTreeNode.of(
                "item_c2", "cust_002", StorageHierarchyNodeData.forItem("DOCUMENT", "ecommerce_db", "customers", "cust_002", 2, 0, "", "{}", "", "")
        );

        collCustomers.child(item1).child(item2);
        docEngineNode.child(collCustomers);
        dbNode.child(docEngineNode);

        FluxTree<StorageHierarchyNodeData> tree = FluxTree.of(dbNode);

        // 1. Initial State: all collapsed
        assertFalse(dbNode.isExpanded());
        assertFalse(docEngineNode.isExpanded());
        assertFalse(collCustomers.isExpanded());

        // 2. Test recursive expandAll()
        tree.expandAll();
        assertTrue(dbNode.isExpanded(), "Database node must be expanded");
        assertTrue(docEngineNode.isExpanded(), "Engine node must be expanded");
        assertTrue(collCustomers.isExpanded(), "Collection node must be expanded");
        assertTrue(item1.isExpanded(), "Item 1 node must be expanded");
        assertTrue(item2.isExpanded(), "Item 2 node must be expanded");

        // 3. Test recursive collapseAll()
        tree.collapseAll();
        assertFalse(dbNode.isExpanded(), "Database node must be collapsed");
        assertFalse(docEngineNode.isExpanded(), "Engine node must be collapsed");
        assertFalse(collCustomers.isExpanded(), "Collection node must be collapsed");
        assertFalse(item1.isExpanded(), "Item 1 node must be collapsed");
        assertFalse(item2.isExpanded(), "Item 2 node must be collapsed");

        // 4. Test Visitor Pattern: count item categories
        AtomicInteger databaseCount = new AtomicInteger(0);
        AtomicInteger unitCount = new AtomicInteger(0);
        AtomicInteger itemCount = new AtomicInteger(0);

        FluxTreeVisitor<StorageHierarchyNodeData> categoryCounter = node -> {
            if (node.getData() != null) {
                switch (node.getData().category()) {
                    case "DATABASE" -> databaseCount.incrementAndGet();
                    case "UNIT" -> unitCount.incrementAndGet();
                    case "ITEM" -> itemCount.incrementAndGet();
                }
            }
        };

        tree.accept(categoryCounter);
        assertEquals(1, databaseCount.get(), "Must have counted 1 database node");
        assertEquals(1, unitCount.get(), "Must have counted 1 unit node");
        assertEquals(2, itemCount.get(), "Must have counted 2 item nodes");

        // 5. Test collapseToRoot(): keeps root database node expanded, collapses all children
        tree.expandAll();
        assertTrue(dbNode.isExpanded());
        assertTrue(docEngineNode.isExpanded());
        assertTrue(collCustomers.isExpanded());

        tree.collapseToRoot();
        assertTrue(dbNode.isExpanded(), "Database root node must remain expanded after collapseToRoot");
        assertFalse(docEngineNode.isExpanded(), "Engine node must be collapsed");
        assertFalse(collCustomers.isExpanded(), "Collection node must be collapsed");
        assertFalse(item1.isExpanded(), "Item 1 must be collapsed");
        assertFalse(item2.isExpanded(), "Item 2 must be collapsed");
    }

    @JettraTest
    @DisplayName("Test Java 25 NodeExpansionState Pattern Matching and Stream Gatherer on Storage Hierarchy")
    public void testJava25NodeExpansionStateAndStreamGatherersOnStorageHierarchy() {
        FluxTreeNode<StorageHierarchyNodeData> dbNode = FluxTreeNode.of(
                "db_orders", "orders_db", StorageHierarchyNodeData.forDatabase("DOCUMENT", "orders_db")
        );
        FluxTreeNode<StorageHierarchyNodeData> collNode = FluxTreeNode.of(
                "coll_orders", "orders", StorageHierarchyNodeData.forUnit("DOCUMENT", "orders_db", "orders", 1)
        );
        FluxTreeNode<StorageHierarchyNodeData> itemNode = FluxTreeNode.of(
                "item_1", "ord_1", StorageHierarchyNodeData.forItem("DOCUMENT", "orders_db", "orders", "ord_1", 1, 0, "", "{}", "", "")
        );

        collNode.child(itemNode);
        dbNode.child(collNode);
        FluxTree<StorageHierarchyNodeData> tree = FluxTree.of(dbNode);

        // 1. Immutable state records
        NodeExpansionState expState = NodeExpansionState.expanded("coll_orders");
        NodeExpansionState collState = NodeExpansionState.collapsed("item_1");

        collNode.applyExpansionState(expState);
        itemNode.applyExpansionState(collState);

        assertTrue(collNode.isExpanded(), "Pattern matching must set collNode expanded");
        assertFalse(itemNode.isExpanded(), "Pattern matching must set itemNode collapsed");

        // 2. Stream Gatherers fold aggregation
        FluxTree.TreeExpansionSummary summary = tree.summarizeExpansion();
        assertEquals(3, summary.totalNodes(), "Total nodes must be 3");
        assertEquals(1, summary.expandedNodes(), "Only collNode is expanded");
        assertEquals(2, summary.collapsedNodes(), "dbNode and itemNode are collapsed");

        // 3. Collect expansion states
        List<NodeExpansionState> collected = tree.collectExpansionStates();
        assertEquals(3, collected.size());
        assertTrue(collected.stream().anyMatch(s -> s.nodeId().equals("coll_orders") && s.isExpanded()));
        assertTrue(collected.stream().anyMatch(s -> s.nodeId().equals("item_1") && !s.isExpanded()));
    }

    @JettraTest
    @DisplayName("Test Pure JettraFlux Button Generation for Storage Tree")
    public void testStorageTreeViewToolbarButtonsPresence() {
        Widget expandBtn = FluxTree.expandAllButton("storage-hierarchy-tree");
        Widget collapseBtn = FluxTree.collapseToRootButton("storage-hierarchy-tree");

        String expandHtml = expandBtn.render(Themes.FlatTheme());
        String collapseHtml = collapseBtn.render(Themes.FlatTheme());

        assertTrue(expandHtml.contains("FluxTree.expandAll('storage-hierarchy-tree')"), "Expand All button must target tree ID");
        assertTrue(collapseHtml.contains("FluxTree.collapseToRoot('storage-hierarchy-tree')"), "Collapse button must target tree ID");
    }

    @JettraTest
    @DisplayName("Test JettraTreeNode and JettraCollapsible: technical detail binding and toggle state mutation")
    public void testExpandableCollapsibleTreeDetailsAndState() {
        Div detailsPanel = Div.of(
            Span.of("Engine: VECTOR | Dimension: 128"),
            Span.of("📍 vec:test_db:idx_vectors:v_001")
        );

        JettraTreeNode<StorageHierarchyNodeData> vectorNode = JettraTreeNode.<StorageHierarchyNodeData>of(
            "node_vec_001",
            "v_001",
            StorageHierarchyNodeData.forItem("VECTOR", "test_db", "idx_vectors", "v_001", 1, 0, "", "{}", "", "")
        ).icon("fas fa-project-diagram")
         .badge("v1")
         .withDetails(detailsPanel)
         .detailsExpanded(false);

        assertTrue(vectorNode.hasDetails(), "Node must have details registered");
        assertNotNull(vectorNode.getDetails(), "Details widget must not be null");
        assertFalse(vectorNode.isDetailsExpanded(), "Details must initially be collapsed");

        // Test toggle state
        vectorNode.toggleDetails();
        assertTrue(vectorNode.isDetailsExpanded(), "Details must be expanded after toggle");

        vectorNode.toggleDetails();
        assertFalse(vectorNode.isDetailsExpanded(), "Details must be collapsed after second toggle");

        // Verify HTML rendering with FluxTree
        FluxTree<StorageHierarchyNodeData> tree = FluxTree.of(vectorNode);
        String html = tree.render(Themes.FlatTheme());

        assertTrue(html.contains("btn_toggle_details_node_vec_001"), "Tree must render leaf details toggle button");
        assertTrue(html.contains("details_node_vec_001"), "Tree must render details panel container");
        assertTrue(html.contains("Dimension: 128"), "Tree details panel must contain vector metrics");
        assertTrue(html.contains("vec:test_db:idx_vectors:v_001"), "Tree details panel must contain address");
        assertTrue(html.contains("FluxTree.toggleDetails"), "Tree must include toggleDetails client script");
    }
}

