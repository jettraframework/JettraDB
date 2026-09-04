package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.StoreDashboardPage;
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
 * Integration Test Suite for Global Vertical Scrolling, Keyboard Focus Accessibility (tabindex="0"),
 * Sticky Header anchoring, and Themed Scrollbar in StoreDashboardPage and MainDashboardView.
 */
public class DashboardGlobalScrollAndKeyboardAccessibilityTest {

    private Path tempDir;
    private JettraStorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_dashboard_scroll_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:customers_db:cust_1", "{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:customers_db:session_1", "tok_abc".getBytes(StandardCharsets.UTF_8), now);
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
    @DisplayName("StoreDashboardPage renders complete layout with scrollable workspace body and tabindex=0")
    void testStoreDashboardPageScrollAndKeyboardAccessibility() {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        Widget fullUi = page.buildUI(null, Collections.emptyMap(), "Matrix");
        String html = fullUi.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);

        // 1. Workspace body container with tabindex="0" and accessible attributes
        assertTrue(html.contains("id=\"jettraWorkspaceBody\""), "Must have unique id for workspace body");
        assertTrue(html.contains("tabindex=\"0\""), "Must have tabindex=0 to receive keyboard focus for scrolling");
        assertTrue(html.contains("role=\"region\""), "Must declare accessible role region");
        assertTrue(html.contains("aria-label=\"Dashboard Content Body\""), "Must declare descriptive aria-label");
        assertTrue(html.contains("jettra-workspace-body"), "Must apply jettra-workspace-body class");

        // 2. CSS styles allow vertical overflow and avoid hidden clipping
        assertTrue(html.contains("overflow-y: auto"), "CSS must specify overflow-y: auto for vertical scrolling");
        assertTrue(html.contains("overflow-x: hidden"), "CSS must specify overflow-x: hidden to prevent horizontal blowout");
        assertTrue(html.contains("scrollbar-color: var(--jf-accent"), "CSS must bind scrollbar to semantic theme tokens");
        assertTrue(html.contains(".jettra-workspace-body::-webkit-scrollbar"), "CSS must style webkit scrollbars");

        // 3. Top Header Bar is sticky and flex-shrink: 0
        assertTrue(html.contains("position: sticky"), "Top bar must be sticky to stay anchored during scrolling");
        assertTrue(html.contains("flex-shrink: 0"), "Top bar must not shrink when dashboard content grows");

        // 4. Content components rendered within body
        assertTrue(html.contains("Storage Engine Dashboard"), "Must contain dashboard title");
        assertTrue(html.contains("Create Backup Snapshot"), "Must contain snapshot button");
        assertTrue(html.contains("Quick Operations"), "Must contain bottom quick operations");
        assertTrue(html.contains("Network Endpoints"), "Must contain network endpoints at the bottom");
    }

    @Test
    @DisplayName("MainDashboardView renders using elastic FlexColumn without height constraints")
    void testMainDashboardViewFlexColumnFlow() {
        DashboardMetricsCollector collector = new DashboardMetricsCollector(engine);
        DashboardMetrics.ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();

        Widget mainView = MainDashboardView.build(snapshot);
        String html = mainView.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("jettra-flex-column"), "MainDashboardView must use FlexColumn for elastic layout flow");
        assertTrue(html.contains("Storage Engine Dashboard"), "Must render header block");
        assertTrue(html.contains("btnCreateBackupSnapshot"), "Must render backup button");
        assertTrue(html.contains("Hierarchy Explorer"), "Must render hierarchy explorer link");
    }

    @Test
    @DisplayName("StoreDashboardPage renders properly under Light / Flat theme with theme-compliant scrollbar tokens")
    void testStoreDashboardPageLightThemeScrollTokens() {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        Widget fullUi = page.buildUI(null, Map.of("target_db", "customers_db"), "White");
        String html = fullUi.render(Themes.FlatTheme());

        assertNotNull(html);
        assertTrue(html.contains("jettra-workspace-body"));
        assertTrue(html.contains("tabindex=\"0\""));
        assertTrue(html.contains("scrollbar-color: var(--jf-accent, var(--j-primary"));
    }
}
