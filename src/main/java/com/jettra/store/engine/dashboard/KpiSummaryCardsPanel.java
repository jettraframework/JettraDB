package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.dashboard.DashboardMetrics.KpiSummary;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-row KPI Summary Cards Panel for JettraDB Dashboard.
 * Visualizes Total Databases, Active Multi-Model Engines, Total Storage Volume,
 * Operations Throughput (Ops/sec), and System Latency using JettraFlux layout widgets.
 */
public final class KpiSummaryCardsPanel {

    private KpiSummaryCardsPanel() {}

    public static Widget build(KpiSummary kpi) {
        List<Widget> cards = new ArrayList<>();

        // 1. Total Databases Card
        cards.add(createKpiCard(
            "fas fa-database",
            "#38bdf8",
            "TOTAL DATABASES",
            String.valueOf(kpi.totalDatabases()),
            "Isolated Namespaces",
            "ACTIVE",
            "badge-active"
        ));

        // 2. Active Engines Card
        cards.add(createKpiCard(
            "fas fa-cubes",
            "#10b981",
            "MULTI-MODEL ENGINES",
            kpi.activeEngines() + " / 9",
            "Polyglot Storage Engines",
            "ONLINE",
            "badge-active"
        ));

        // 3. Total Stored Records Card
        cards.add(createKpiCard(
            "fas fa-layer-group",
            "#a855f7",
            "STORED ENTITIES",
            String.format("%,d", kpi.totalRecords()),
            "Across all engine models",
            "SYNCED",
            "badge-records"
        ));

        // 4. Operations Throughput Card
        cards.add(createKpiCard(
            "fas fa-tachometer-alt",
            "#f59e0b",
            "OPERATIONS / SEC",
            String.format("%,d ops/s", kpi.opsPerSec()),
            "Real-time IOPS Throughput",
            "FAST",
            "badge-engine"
        ));

        // 5. Latency & JVM Heap Card
        cards.add(createKpiCard(
            "fas fa-bolt",
            "#ec4899",
            "AVG LATENCY / HEAP",
            String.format("%.2f ms", kpi.avgLatencyMs()),
            "JVM Heap: " + kpi.heapPercent() + "% utilized",
            "HEALTHY",
            "badge-raft"
        ));

        return Div.of(cards.toArray(new Widget[0]))
            .modifier(new Modifier()
                .style("display:grid; grid-template-columns:repeat(auto-fit, minmax(200px, 1fr)); gap:16px; margin-bottom:24px; width:100%;"));
    }

    private static Widget createKpiCard(
        String icon,
        String color,
        String title,
        String value,
        String subtitle,
        String badgeText,
        String badgeClass
    ) {
        Widget topRow = Div.of(
            Div.of(
                Icon.of(icon).modifier(new Modifier().style("color:" + color + "; font-size:16px;"))
            ).modifier(new Modifier().style("width:36px; height:36px; border-radius:8px; background:" + color + "1a; display:flex; align-items:center; justify-content:center;")),
            Span.of(badgeText).modifier(new Modifier().cssClass("store-badge " + badgeClass).style("font-size:9.5px; font-weight:700; padding:2px 6px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;"));

        Widget label = Span.of(title)
            .modifier(new Modifier().style("font-size:10.5px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase; letter-spacing:0.5px; display:block;"));

        Widget mainVal = Span.of(value)
            .modifier(new Modifier().style("font-size:22px; font-weight:800; color:var(--j-text-primary); font-family:monospace; margin:4px 0; display:block;"));

        Widget sub = Span.of(subtitle)
            .modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); display:block;"));

        return Div.of(topRow, label, mainVal, sub)
            .modifier(new Modifier()
                .cssClass("store-card")
                .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:16px; box-shadow:0 2px 8px rgba(0,0,0,0.05); transition:transform 0.15s ease;"));
    }
}
