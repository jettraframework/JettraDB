package com.jettra.store.engine.web;

import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.web.view.StorageTreeView;
import com.jettra.store.engine.web.view.TreePaginationQuery;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.flux.widgets.tree.TreePaginationControl;
import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.test.annotation.AfterAll;
import io.jettra.test.annotation.BeforeAll;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the Tree View pagination architecture in JettraDB:
 * - Builder Pattern: TreePaginationQuery construction and HTTP parameter resolution.
 * - Composite Pattern: TreePaginationControl widget in JettraFlux.
 * - Integration: StorageTreeView rendering with per-node pagination avoiding memory saturation.
 */
@NotRequiresRunningServer
public class StorageTreeViewPaginationTest {

    private static JettraStorageEngine engine;
    private static String testPath;

    @BeforeAll
    public static void setUp() {
        testPath = "/tmp/jettra_pagi_test_" + System.currentTimeMillis() + "_" + System.nanoTime();
        engine = new JettraStorageEngine(testPath);
        engine.start();
    }

    @AfterAll
    public static void tearDown() {
        if (engine != null) {
            try { engine.dropAllDatabases(); } catch (Exception ignored) {}
            try { engine.stop(); } catch (Exception ignored) {}
        }
        if (testPath != null) {
            deleteDir(new File(testPath));
        }
    }

    private static void deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteDir(f);
            }
            dir.delete();
        }
    }

    @JettraTest
    @DisplayName("1. TreePaginationQuery Builder Pattern and Parameter Resolution")
    void testTreePaginationQueryBuilder() {
        // Default builder values
        TreePaginationQuery defaultQuery = TreePaginationQuery.builder()
                .database("ExampleFactura")
                .engine("RECORDS")
                .unit("empleado")
                .build();

        assertEquals("ExampleFactura", defaultQuery.getDatabase());
        assertEquals("RECORDS", defaultQuery.getEngine());
        assertEquals("empleado", defaultQuery.getUnit());
        assertEquals(1, defaultQuery.getPage());
        assertEquals(TreePaginationQuery.DEFAULT_PAGE_SIZE, defaultQuery.getPageSize());

        // Parameter-based resolution
        Map<String, String> params = new HashMap<>();
        params.put("tree_unit", "empleado");
        params.put("tree_page", "3");
        params.put("tree_page_size", "20");

        TreePaginationQuery resolved = TreePaginationQuery.builder()
                .fromParams(params, "RECORDS", "empleado")
                .build();

        assertEquals(3, resolved.getPage());
        assertEquals(20, resolved.getPageSize());
        assertEquals("RECORDS", resolved.getEngine());
        assertEquals("empleado", resolved.getUnit());

        // Verify calculation helpers
        assertEquals(5, resolved.calculateTotalPages(95));
        assertTrue(resolved.hasNext(95));
        assertTrue(resolved.hasPrevious());
    }

    @JettraTest
    @DisplayName("2. TreePaginationQuery Slice Logic")
    void testTreePaginationQuerySlicing() {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            items.add("item_" + i);
        }

        // Page 1 (15 items)
        TreePaginationQuery p1 = TreePaginationQuery.builder().page(1).pageSize(15).build();
        List<String> slice1 = p1.slice(items);
        assertEquals(15, slice1.size());
        assertEquals("item_1", slice1.get(0));
        assertEquals("item_15", slice1.get(14));

        // Page 2 (15 items)
        TreePaginationQuery p2 = TreePaginationQuery.builder().page(2).pageSize(15).build();
        List<String> slice2 = p2.slice(items);
        assertEquals(15, slice2.size());
        assertEquals("item_16", slice2.get(0));
        assertEquals("item_30", slice2.get(14));

        // Page 4 (last page with remaining 5 items)
        TreePaginationQuery p4 = TreePaginationQuery.builder().page(4).pageSize(15).build();
        List<String> slice4 = p4.slice(items);
        assertEquals(5, slice4.size());
        assertEquals("item_46", slice4.get(0));
        assertEquals("item_50", slice4.get(4));

        // Out of bounds page wraps gracefully to last page
        TreePaginationQuery pOut = TreePaginationQuery.builder().page(99).pageSize(15).build();
        List<String> sliceOut = pOut.slice(items);
        assertEquals(5, sliceOut.size());
        assertEquals("item_46", sliceOut.get(0));
    }

    @JettraTest
    @DisplayName("3. TreePaginationControl Composite Widget Rendering in JettraFlux")
    void testTreePaginationControlRendering() {
        TreePaginationControl control = TreePaginationControl.of("facturas", 2, 15, 60)
                .baseUrl("/engines?database=ExampleFactura&engine=RECORDS");

        assertEquals(2, control.getCurrentPage());
        assertEquals(15, control.getPageSize());
        assertEquals(60, control.getTotalItems());
        assertEquals(4, control.getTotalPages());
        assertTrue(control.hasPrevious());
        assertTrue(control.hasNext());

        String rendered = control.render(Themes.FlatTheme());
        assertNotNull(rendered);
        assertTrue(rendered.contains("tree-pagination-control"), "Must contain container class");
        assertTrue(rendered.contains("data-unit=\"facturas\""), "Must contain data-unit attribute");
        assertTrue(rendered.contains("data-page=\"2\""), "Must contain data-page attribute");
        assertTrue(rendered.contains("data-total-pages=\"4\""), "Must contain data-total-pages attribute");
        assertTrue(rendered.contains("16–30 de 60"), "Must display correct item range");
        assertTrue(rendered.contains("Pág. 2/4"), "Must display page badge");
        assertTrue(rendered.contains("fa-chevron-left"), "Must render previous button icon");
        assertTrue(rendered.contains("fa-chevron-right"), "Must render next button icon");
    }

    @JettraTest
    @DisplayName("4. StorageTreeView Integration with Paginated Hierarchy Explorer Service")
    void testStorageTreeViewPaginationIntegration() {
        // Create mock hierarchy with 45 items in RECORDS/clientes
        Map<String, List<String>> unitMap = new HashMap<>();
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            items.add("cli_" + String.format("%03d", i));
        }
        unitMap.put("clientes", items);

        HierarchyExplorerService mockService = new HierarchyExplorerService(engine) {
            @Override
            public Map<String, List<String>> discoverUnitsAndItems(String engineName, String database) {
                if ("RECORDS".equalsIgnoreCase(engineName) && "ExampleFactura".equalsIgnoreCase(database)) {
                    return unitMap;
                }
                return Collections.emptyMap();
            }

            @Override
            public String getItemPayload(String engineName, String database, String unitName, String itemId) {
                return "{\"id\":\"" + itemId + "\",\"nombre\":\"Cliente " + itemId + "\"}";
            }

            @Override
            public int getItemVersionCount(String engineName, String database, String unitName, String itemId) {
                return 1;
            }

            @Override
            public String getVersionsJson(String engineName, String database, String unitName, String itemId) {
                return "[]";
            }
        };

        // Render Page 1
        Map<String, String> paramsP1 = new HashMap<>();
        paramsP1.put("tree_unit", "clientes");
        paramsP1.put("tree_page", "1");

        Widget widgetP1 = StorageTreeView.build("RECORDS", "ExampleFactura", "clientes", "/engines", paramsP1, mockService);
        assertNotNull(widgetP1);
        String htmlP1 = widgetP1.render(Themes.FlatTheme());

        // Page 1 should contain items 1-15, but NOT item 20
        assertTrue(htmlP1.contains("cli_001"));
        assertTrue(htmlP1.contains("cli_015"));
        assertFalse(htmlP1.contains("cli_020"));
        assertTrue(htmlP1.contains("tree-pagination-control"), "Must contain TreePaginationControl");

        // Render Page 2
        Map<String, String> paramsP2 = new HashMap<>();
        paramsP2.put("tree_unit", "clientes");
        paramsP2.put("tree_page", "2");

        Widget widgetP2 = StorageTreeView.build("RECORDS", "ExampleFactura", "clientes", "/engines", paramsP2, mockService);
        String htmlP2 = widgetP2.render(Themes.FlatTheme());

        // Page 2 should contain items 16-30, but NOT item 001 or 040
        assertFalse(htmlP2.contains("cli_001"));
        assertTrue(htmlP2.contains("cli_016"));
        assertTrue(htmlP2.contains("cli_030"));
        assertFalse(htmlP2.contains("cli_040"));
    }
}
