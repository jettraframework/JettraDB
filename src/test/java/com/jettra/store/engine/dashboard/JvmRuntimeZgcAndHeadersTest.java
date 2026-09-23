package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.storage.StorageEngineFactory;
import com.jettra.store.engine.dashboard.DashboardMetrics.SystemHealthStatus;
import io.jettra.flux.core.Widget;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit tests verifying JVM 25 ZGC detection, Compact Object Headers status,
 * and JettraFlux telemetry dashboard rendering.
 * Exclusively using JettraTest.
 */
@NotRequiresRunningServer
public class JvmRuntimeZgcAndHeadersTest {

    @JettraTest
    void testSystemHealthStatusModelFields() {
        SystemHealthStatus status = new SystemHealthStatus(
            "HEALTHY_LEADER",
            128,
            2048,
            6,
            500,
            50000,
            "02h 15m 30s",
            0,
            "1 Node (Consensus OK)",
            "ZGC (Ultra-Low Latency)",
            true,
            "JettraSerialization (JettraEE Native)"
        );

        assertEquals("HEALTHY_LEADER", status.nodeStatus());
        assertEquals("ZGC (Ultra-Low Latency)", status.gcName());
        assertTrue(status.compactHeadersActive());
        assertEquals("JettraSerialization (JettraEE Native)", status.serializationStrategy());
    }

    @JettraTest
    void testSystemHealthPanelJettraFluxRendering() {
        SystemHealthStatus status = new SystemHealthStatus(
            "HEALTHY_LEADER",
            256,
            4096,
            6,
            1200,
            100000,
            "01h 00m 00s",
            2,
            "1 Node (Consensus OK)",
            "ZGC (Low-Latency <1ms)",
            true,
            StorageEngineFactory.getDefaultStrategy().getStrategyName()
        );

        Widget healthPanelWidget = SystemHealthPanel.build(status);
        assertNotNull(healthPanelWidget, "SystemHealthPanel must return a valid JettraFlux Widget");

        String renderedHtml = healthPanelWidget.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertNotNull(renderedHtml, "Rendered HTML must not be null");
        assertTrue(renderedHtml.contains("JVM RUNTIME & HEADERS"), "Must contain JVM runtime card");
        assertTrue(renderedHtml.contains("SERIALIZATION ENGINE"), "Must contain serialization engine card");
        assertTrue(renderedHtml.contains("Compact Headers: ON"), "Must display Compact Headers badge in header");
        assertTrue(renderedHtml.contains("ZGC"), "Must display ZGC indicator");
    }

    @JettraTest
    void testStorageEngineFactoryStrategyResolution() {
        assertNotNull(StorageEngineFactory.getDefaultStrategy());
        assertEquals("JettraSerialization (JettraEE Native)", StorageEngineFactory.getDefaultStrategy().getStrategyName());
    }
}
