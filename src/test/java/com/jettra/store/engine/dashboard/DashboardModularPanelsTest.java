package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.dashboard.DashboardMetrics.*;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.StoreDashboardPage;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Suite for JettraDB Modular Panel-and-Chart Dashboard.
 * Validates Virtual Thread telemetry aggregation, Reactive Stream observer notifications,
 * JettraFlux chart components (CharsDoughnut, ChartsLine, CharsBar),
 * and StoreDashboardPage lifecycle integration.
 */
public class DashboardModularPanelsTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private DashboardMetricsCollector collector;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_dashboard_modular_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        // Seed multi-model storage data
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:customers_db:cust_01", "{\"name\":\"Alice\",\"tier\":\"GOLD\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:customers_db:cust_02", "{\"name\":\"Bob\",\"tier\":\"SILVER\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:cache_db:session_01", "active_token_123".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("rec:analytics_db:metric_01", "{\"views\":1250}".getBytes(StandardCharsets.UTF_8), now);

        collector = new DashboardMetricsCollector(engine);
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
    @DisplayName("Test 1: Virtual Thread Telemetry Collection and Snapshot Accuracy")
    void testVirtualThreadMetricsCollection() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();

        assertNotNull(snapshot, "Snapshot must not be null");
        assertNotNull(snapshot.kpi(), "KPI summary must not be null");
        assertNotNull(snapshot.distribution(), "Multi-model distribution must not be null");
        assertNotNull(snapshot.telemetry(), "Telemetry dataset must not be null");
        assertNotNull(snapshot.hierarchy(), "Storage hierarchy must not be null");
        assertNotNull(snapshot.health(), "System health must not be null");

        // Verify multi-model counts
        assertEquals(2, snapshot.distribution().getCount("DOCUMENT"));
        assertEquals(1, snapshot.distribution().getCount("KEYVALUE"));
        assertEquals(1, snapshot.distribution().getCount("RECORDS"));
        assertEquals(4, snapshot.distribution().totalItems());

        // Verify KPI summary
        assertTrue(snapshot.kpi().totalDatabases() >= 3, "Should discover at least 3 active databases");
        assertEquals(9, snapshot.kpi().activeEngines());
        assertEquals(4, snapshot.kpi().totalRecords());
        assertTrue(snapshot.kpi().opsPerSec() > 0);

        // Verify System Health
        assertEquals("HEALTHY_LEADER", snapshot.health().nodeStatus());
        assertTrue(snapshot.health().maxHeapMb() > 0);
    }

    @Test
    @DisplayName("Test 2: Reactive Observer Subscription and Telemetry Burst Updates")
    void testReactiveObserverSubscriptionAndTelemetryBurst() {
        AtomicInteger notificationCount = new AtomicInteger(0);
        AtomicReference<ComprehensiveDashboardSnapshot> lastReceived = new AtomicReference<>();

        // Subscribe reactive observer
        collector.subscribe(snapshot -> {
            notificationCount.incrementAndGet();
            lastReceived.set(snapshot);
        });

        // Fire telemetry operation records
        collector.recordOperation(2500.0, 950.0, 0.65);
        collector.recordOperation(2800.0, 1100.0, 0.58);

        ComprehensiveDashboardSnapshot freshSnapshot = collector.collectSnapshot();

        assertEquals(1, notificationCount.get(), "Reactive observer should receive snapshot update");
        assertNotNull(lastReceived.get());
        assertEquals(freshSnapshot, lastReceived.get());

        // Verify telemetry points include fresh metrics
        List<ThroughputLatencyPoint> points = freshSnapshot.telemetry().points();
        assertFalse(points.isEmpty());
        ThroughputLatencyPoint latestPoint = points.get(points.size() - 1);
        assertEquals(2800.0, latestPoint.readIops());
        assertEquals(1100.0, latestPoint.writeIops());
        assertEquals(0.58, latestPoint.latencyMs());

        // Test unsubscribe
        collector.unsubscribe(lastReceived::set);
    }

    @Test
    @DisplayName("Test 3: Modular Panel Rendering with Native JettraFlux Components")
    void testMainDashboardViewAndModularPanelsRendering() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();

        // 1. KPI Panel
        Widget kpiPanel = KpiSummaryCardsPanel.build(snapshot.kpi());
        String kpiHtml = kpiPanel.render(Themes.FlatTheme());
        assertNotNull(kpiHtml);
        assertTrue(kpiHtml.contains("TOTAL DATABASES"));
        assertTrue(kpiHtml.contains("MULTI-MODEL ENGINES"));
        assertTrue(kpiHtml.contains("STORED ENTITIES"));

        // 2. Multi-Model Distribution Panel (CharsDoughnut)
        Widget distPanel = MultiModelDistributionPanel.build(snapshot.distribution());
        String distHtml = distPanel.render(Themes.FlatTheme());
        assertTrue(distHtml.contains("Multi-Model Data Distribution"));
        assertTrue(distHtml.contains("espresso-charsdoughnut") || distHtml.contains("doughnut") || distHtml.contains("canvas"));
        assertTrue(distHtml.contains("Document Store"));
        assertTrue(distHtml.contains("Key-Value Store"));

        // 3. Throughput & Latency Panel (ChartsLine)
        Widget latencyPanel = ThroughputLatencyPanel.build(snapshot.telemetry());
        String latencyHtml = latencyPanel.render(Themes.FlatTheme());
        assertTrue(latencyHtml.contains("Throughput &amp; Latency Telemetry") || latencyHtml.contains("Throughput & Latency Telemetry"));
        assertTrue(latencyHtml.contains("espresso-chartsline") || latencyHtml.contains("line") || latencyHtml.contains("canvas"));

        // 4. Engine Hierarchy Panel (CharsBar)
        Widget hierarchyPanel = EngineHierarchyChartPanel.build(snapshot.hierarchy());
        String hierarchyHtml = hierarchyPanel.render(Themes.FlatTheme());
        assertTrue(hierarchyHtml.contains("Storage Hierarchy &amp; Namespace Breakdown") || hierarchyHtml.contains("Storage Hierarchy & Namespace Breakdown"));
        assertTrue(hierarchyHtml.contains("espresso-charsbar") || hierarchyHtml.contains("bar") || hierarchyHtml.contains("canvas"));
        assertTrue(hierarchyHtml.contains("customers_db"));

        // 5. System Health Panel
        Widget healthPanel = SystemHealthPanel.build(snapshot.health());
        String healthHtml = healthPanel.render(Themes.FlatTheme());
        assertTrue(healthHtml.contains("System Status &amp; Node Telemetry") || healthHtml.contains("System Status & Node Telemetry"));
        assertTrue(healthHtml.contains("JVM HEAP ALLOCATION"));
        assertTrue(healthHtml.contains("DATA DISK STORAGE"));

        // 6. Complete Composite MainDashboardView
        Widget mainView = MainDashboardView.build(snapshot);
        String mainHtml = mainView.render(Themes.FlatTheme());
        assertTrue(mainHtml.contains("Storage Engine Dashboard"));
        assertTrue(mainHtml.contains("Create Backup Snapshot"));
        assertTrue(mainHtml.contains("Hierarchy Explorer"));
    }

    @Test
    @DisplayName("Test 4: Java 25 Pattern Matching Prefix Categorization")
    void testPrefixPatternMatchingCategorization() {
        assertEquals("RECORDS", DashboardMetricsCollector.categorizePrefix("rec:"));
        assertEquals("DOCUMENT", DashboardMetricsCollector.categorizePrefix("doc:"));
        assertEquals("VECTOR", DashboardMetricsCollector.categorizePrefix("vec:"));
        assertEquals("GRAPH", DashboardMetricsCollector.categorizePrefix("graph:"));
        assertEquals("TIMESERIES", DashboardMetricsCollector.categorizePrefix("ts:"));
        assertEquals("COLUMN", DashboardMetricsCollector.categorizePrefix("col:"));
        assertEquals("KEYVALUE", DashboardMetricsCollector.categorizePrefix("kv:"));
        assertEquals("GEOSPATIAL", DashboardMetricsCollector.categorizePrefix("geo:"));
        assertEquals("OBJECT", DashboardMetricsCollector.categorizePrefix("obj:"));
        assertEquals("DOCUMENT", DashboardMetricsCollector.categorizePrefix("unknown:"));
    }

    @Test
    @DisplayName("Test 5: StoreDashboardPage Full Integration")
    void testStoreDashboardPageIntegration() {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        assertNotNull(page.getMetricsCollector());

        Widget content = page.buildContent(null, Collections.emptyMap(), "Ast");
        assertNotNull(content);

        String renderedHtml = content.render(Themes.FlatTheme());
        assertTrue(renderedHtml.contains("Storage Engine Dashboard"));
        assertTrue(renderedHtml.contains("Quick Operations"));
        assertTrue(renderedHtml.contains("Network Endpoints"));
    }
}
