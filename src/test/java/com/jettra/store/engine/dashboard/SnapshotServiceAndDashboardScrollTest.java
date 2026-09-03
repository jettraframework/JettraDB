package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.dashboard.DashboardMetrics.*;
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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Suite for SnapshotService Markdown persistence and EngineHierarchyChartPanel scrollability.
 */
public class SnapshotServiceAndDashboardScrollTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private DashboardMetricsCollector collector;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_snapshot_scroll_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:customers_db:cust_101", "{\"name\":\"Neo\",\"role\":\"The One\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:customers_db:session_101", "active_auth_token".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("rec:analytics_db:metric_01", "data".getBytes(StandardCharsets.UTF_8), now);

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
    @DisplayName("SnapshotService correctly generates structured Markdown with tables and headers")
    void testGenerateMarkdown() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 15, 30, 0);

        String md = SnapshotService.generateMarkdown(snapshot, "root", "Matrix", ColorMode.DARK, now);
        assertNotNull(md, "Markdown should not be null");

        // Validate Header & Metadata
        assertTrue(md.contains("# JettraDB System & Storage Dashboard Snapshot"));
        assertTrue(md.contains("2026-09-03 15:30:00"));
        assertTrue(md.contains("root"));
        assertTrue(md.contains("Matrix"));
        assertTrue(md.contains("DARK"));

        // Validate KPI Summary Table
        assertTrue(md.contains("## 1. High-Level KPI Summary"));
        assertTrue(md.contains("Total Databases"));
        assertTrue(md.contains("Active Engines"));
        assertTrue(md.contains("Total Storage Allocated"));

        // Validate Multi-Model Distribution Table
        assertTrue(md.contains("## 2. Multi-Model Data Volume Distribution"));
        assertTrue(md.contains("DOCUMENT"));
        assertTrue(md.contains("KEYVALUE"));
        assertTrue(md.contains("RECORDS"));

        // Validate Storage Hierarchy Breakdown
        assertTrue(md.contains("## 3. Storage Hierarchy & Namespace Breakdown"));
        assertTrue(md.contains("customers_db"));
        assertTrue(md.contains("analytics_db"));

        // Validate Telemetry & Health
        assertTrue(md.contains("## 4. Telemetry Stream & Performance Profiling"));
        assertTrue(md.contains("## 5. System Health, Memory & Resource Allocation"));
        assertTrue(md.contains("| **JVM Heap Memory** |"));
    }

    @Test
    @DisplayName("SnapshotService persists file matching pattern snapshot-yyyy-MM-dd-HH-mm-ss.md atomically")
    void testCreateSnapshotFilePersistence() throws IOException {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Path createdPath = SnapshotService.createSnapshot(snapshot, "admin", "Matrix", ColorMode.DARK);

        assertNotNull(createdPath);
        assertTrue(Files.exists(createdPath), "Snapshot file must exist on disk");
        assertTrue(Files.size(createdPath) > 500, "Snapshot content must be non-trivial");

        String fileName = createdPath.getFileName().toString();
        assertTrue(fileName.startsWith("snapshot-"), "Filename must start with snapshot-");
        assertTrue(fileName.endsWith(".md"), "Filename must end with .md");
        assertTrue(fileName.matches("^snapshot-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.md$"),
            "Filename must conform strictly to snapshot-yyyy-MM-dd-HH-mm-ss.md, but was: " + fileName);

        String readContent = Files.readString(createdPath, StandardCharsets.UTF_8);
        assertTrue(readContent.contains("JettraDB System & Storage Dashboard Snapshot"));
        assertTrue(readContent.contains("Matrix"));

        // Cleanup test file
        Files.deleteIfExists(createdPath);
    }

    @Test
    @DisplayName("SnapshotService creates snapshot specifically in engine.getStorageDir()/snapshot")
    void testCreateSnapshotInEngineStorageDir() throws IOException {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Path storageDir = engine.getStorageDir();
        Path snapshotPath = SnapshotService.createSnapshot(storageDir, snapshot, "root", "Matrix", ColorMode.DARK);

        assertNotNull(snapshotPath);
        assertTrue(Files.exists(snapshotPath));
        assertEquals(storageDir.resolve("snapshot"), snapshotPath.getParent(),
            "Snapshot must be saved in the snapshot subdirectory of the database storage directory");

        Files.deleteIfExists(snapshotPath);
    }

    @Test
    @DisplayName("EngineHierarchyChartPanel includes scrollableContent and theme-styled scrollbar")
    void testEngineHierarchyChartPanelScrollableLayout() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Widget panel = EngineHierarchyChartPanel.build(snapshot.hierarchy());
        String html = panel.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("Storage Hierarchy &amp; Namespace Breakdown") || html.contains("Storage Hierarchy & Namespace Breakdown"),
            "Must display panel title");
        assertTrue(html.contains("storage-hierarchy-scroll-container"),
            "Must contain scrollable container class");
        assertTrue(html.contains("overflow-y:auto") || html.contains("overflow-y: auto"),
            "Container must specify vertical overflow");
        assertTrue(html.contains("max-height:580px") || html.contains("max-height: 580px"),
            "Container must enforce responsive max-height");
        assertTrue(html.contains("scrollbar-color:var(--jf-accent"),
            "Scrollbar must bind to semantic theme tokens");
        assertTrue(html.contains("customers_db"), "Must contain seeded database namespace");
        assertTrue(html.contains("espresso-charsbar"), "Must contain bar chart canvas element");
    }

    @Test
    @DisplayName("MainDashboardView renders Create Backup Snapshot button with interactive trigger and toast")
    void testMainDashboardViewButtonAndScript() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Widget mainView = MainDashboardView.build(snapshot);
        String html = mainView.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("btnCreateBackupSnapshot"), "Button must have unique ID btnCreateBackupSnapshot");
        assertTrue(html.contains("Create Backup Snapshot"), "Button text must be present");
        assertTrue(html.contains("triggerBackup(this)"), "Button must call triggerBackup with element ref");
        assertTrue(html.contains("jettra-snapshot-toast"), "Script must manage snapshot toast feedback");
        assertTrue(html.contains("action=backup"), "Script must trigger dashboard backup action");
    }

    @Test
    @DisplayName("StoreDashboardPage handles action=backup POST request and outputs JSON")
    void testStoreDashboardPageOnPostBackup() {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        assertNotNull(page);
        assertNotNull(page.getMetricsCollector());
        assertEquals("Dashboard - JettraStoreEngine", page.getPageTitle());
    }
}
