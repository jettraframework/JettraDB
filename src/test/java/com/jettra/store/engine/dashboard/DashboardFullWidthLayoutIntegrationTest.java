package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.StorageDashboardView;
import com.jettra.store.engine.web.StoreDashboardPage;
import com.jettra.store.engine.web.StoreEnginesPage;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.ColorMode;
import io.jettra.flux.theme.Themes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test Suite validating the Unified Full-Width and Responsive Layout
 * of Dashboard against Engines in JettraDB using JettraFlux FluidContainer.
 */
public class DashboardFullWidthLayoutIntegrationTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private DashboardMetricsCollector collector;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_dashboard_fullwidth_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:customers_db:item_1", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:customers_db:key_1", "val_1".getBytes(StandardCharsets.UTF_8), now);

        collector = new DashboardMetricsCollector(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    @DisplayName("MainDashboardView renders using FluidContainer with 100% width and no max-width bottleneck")
    void testMainDashboardViewFullWidth() {
        DashboardMetrics.ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Widget mainView = MainDashboardView.build(snapshot);
        String html = mainView.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("jettra-fluid-container"), "MainDashboardView must encapsulate inside FluidContainer");
        assertTrue(html.contains("width:100%"), "Must stretch across 100% width");
        assertTrue(html.contains("min-width:100%"), "Must enforce 100% min-width");
        assertTrue(html.contains("flex:1"), "Must take flex: 1 available space");
        assertTrue(html.contains("padding:16px 20px"), "Must have standard 16px 20px canvas padding matching Engines");

        assertFalse(html.contains("max-width:1440px"), "Must NOT impose max-width: 1440px constraint");
        assertFalse(html.contains("margin:0 auto"), "Must NOT impose margin: 0 auto centering bottleneck");
    }

    @Test
    @DisplayName("StorageDashboardView renders using FluidContainer with 100% width and no max-width bottleneck")
    void testStorageDashboardViewFullWidth() {
        Widget storageView = StorageDashboardView.build(
                "DOCUMENT", "customers_db", "/engines?engine=",
                10, 5, 2, 4, 1, 0, 0, 0, 8, "Matrix"
        );
        String html = storageView.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("jettra-fluid-container"), "StorageDashboardView must encapsulate inside FluidContainer");
        assertTrue(html.contains("width:100%"), "Must stretch across 100% width");
        assertTrue(html.contains("min-width:100%"), "Must enforce 100% min-width");

        assertFalse(html.contains("max-width:1400px"), "Must NOT impose max-width: 1400px constraint");
        assertFalse(html.contains("margin:0 auto"), "Must NOT impose margin: 0 auto centering bottleneck");
    }

    @Test
    @DisplayName("StoreDashboardPage renders responsive full-width layout matching StoreEnginesPage")
    void testDashboardMatchesEnginesFullWidthResponsiveness() {
        StoreDashboardPage dashPage = new StoreDashboardPage(engine);
        Widget dashUi = dashPage.buildUI(null, Collections.emptyMap(), "Matrix");
        String dashHtml = dashUi.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(dashHtml);
        assertTrue(dashHtml.contains("jettra-workspace-body"), "Dashboard UI must retain accessible workspace body");
        assertTrue(dashHtml.contains("jettra-fluid-container"), "Dashboard content must be fluid and full-width");
        assertTrue(dashHtml.contains("padding:16px 20px"), "Dashboard must adopt the identical padding specification as Engines");

        // Verify Engines page also uses full-width padding:16px 20px
        StoreEnginesPage enginesPage = new StoreEnginesPage(engine);
        Widget enginesUi = enginesPage.buildContent(null, Map.of("tab", "schema", "view_mode", "dashboard"), "Matrix");
        String enginesHtml = enginesUi.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(enginesHtml);
        assertTrue(enginesHtml.contains("padding:16px 20px"), "Engines canvas must utilize 16px 20px padding");
        assertTrue(enginesHtml.contains("jettra-fluid-container"), "Embedded dashboard inside Engines must also use FluidContainer");
    }
}
