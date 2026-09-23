package com.jettra.store.engine.web;

import com.jettra.store.engine.web.view.StorageTableView;
import com.jettra.store.engine.web.view.StorageTableView.FlatRecordItem;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JettraJson;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the optimized web pagination and JettraFlux component exclusivity
 * in the StorageTableView of the /engines interface.
 */
@NotRequiresRunningServer
public class StorageTableViewPaginationTest {

    private final JettraJson jsonParser = new JettraJson();

    @JettraTest
    @DisplayName("1. Verify Paginated Table View Controls and Record Slicing")
    void testStorageTableViewPaginationCalculation() {
        int totalItems = 1500;
        int currentPage = 2;
        int pageSize = 15;

        List<FlatRecordItem> pageItems = new ArrayList<>();
        for (int i = 16; i <= 30; i++) {
            String payload = "{\"sku\":\"SKU-" + i + "\",\"name\":\"Product #" + i + "\"}";
            String b64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            pageItems.add(new FlatRecordItem(
                "RECORDS", "#10b981", "fas fa-table", "ExampleFactura", "empleado", "emp_" + i,
                1, payload, b64, "[]"
            ));
        }

        Map<String, String> params = new HashMap<>();
        params.put("table_page", "2");
        params.put("table_size", "15");

        Widget tableWidget = StorageTableView.buildPaged(
            "RECORDS", "ExampleFactura", "empleado", "/engines?engine=",
            totalItems, currentPage, pageSize, pageItems, params, jsonParser
        );

        String html = tableWidget.render(Themes.FlatTheme());
        assertNotNull(html);

        // Verify pagination summary text
        assertTrue(html.contains("Showing 16 - 30 of 1500 records (Page 2 of 100)"),
            "Must display correct pagination count slice for page 2");

        // Verify pagination navigation buttons
        assertTrue(html.contains("« First"), "Must contain First page button");
        assertTrue(html.contains("‹ Prev"), "Must contain Prev page button");
        assertTrue(html.contains("Next ›"), "Must contain Next page button");
        assertTrue(html.contains("Last »"), "Must contain Last page button");

        // Verify row items rendered
        assertTrue(html.contains("emp_16"), "Must contain first item of page 2");
        assertTrue(html.contains("emp_30"), "Must contain last item of page 2");

        // Verify modal action handlers
        assertTrue(html.contains("openInspectRecordModal"), "Must contain inspect modal handler");
        assertTrue(html.contains("openUniversalEditModal"), "Must contain edit modal handler");
        assertTrue(html.contains("openUniversalRestoreModal"), "Must contain restore modal handler");
        assertTrue(html.contains("openUniversalDeleteModal"), "Must contain delete modal handler");
    }

    @JettraTest
    @DisplayName("2. Verify JettraFlux Exclusivity in StorageTableView")
    void testJettraFluxExclusivity() {
        FlatRecordItem item = new FlatRecordItem(
            "DOCUMENT", "#38bdf8", "fas fa-file-alt", "ExampleFactura", "grupos", "grp_01",
            1, "{\"name\":\"Hardware\"}", "eyJuYW1lIjoiSGFyZHdhcmUifQ==", "[]"
        );

        Widget tableWidget = StorageTableView.buildPaged(
            "DOCUMENT", "ExampleFactura", "grupos", "/engines?engine=",
            1, 1, 15, List.of(item), Collections.emptyMap(), jsonParser
        );

        String html = tableWidget.render(Themes.FlatTheme());
        assertNotNull(html);

        // Checkbox rendered via JettraFlux Checkbox widget
        assertTrue(html.contains("chkAutoResolveRefsGlobal"), "Must contain chkAutoResolveRefsGlobal element");
        assertTrue(html.contains("toggleGlobalReferenceResolution"), "Must include toggleGlobalReferenceResolution listener");
        assertTrue(html.contains("tableExplorerQuickFilter"), "Must contain quick filter input");
    }
}
