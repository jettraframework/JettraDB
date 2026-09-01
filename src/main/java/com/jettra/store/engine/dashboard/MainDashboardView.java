package com.jettra.store.engine.dashboard;

import io.jettra.server.JettraServer;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

/**
 * Main Composite Dashboard View for JettraDB built strictly with JettraFlux components.
 * Assembles modular panels, reactive metrics, and native chart widgets
 * (CharsDoughnut, ChartsLine, CharsBar) into a responsive, cohesive layout.
 */
public final class MainDashboardView {

    private MainDashboardView() {}

    /**
     * Builds the complete modular dashboard UI using the provided snapshot.
     */
    public static Widget build(ComprehensiveDashboardSnapshot snapshot) {
        // 1. Header Row (Title, Subtitle, Global Action Buttons)
        Widget titleBlock = Div.of(
            Div.of(
                Header.of(1, Text.of("Storage Engine Dashboard"))
                    .modifier(new Modifier().style("margin:0; font-size:24px; font-weight:800; color:var(--j-text-primary); letter-spacing:-0.5px;")),
                Paragraph.of(Text.of("Real-time operational monitoring, multi-model storage hierarchy, and cluster telemetry."))
                    .modifier(new Modifier().style("margin:4px 0 0 0; color:var(--j-text-muted); font-size:13px;"))
            )
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; flex-wrap:wrap; gap:12px;"));
//        Widget titleBlock = Div.of(
//            Div.of(
//                Header.of(1, Text.of("Storage Engine Dashboard"))
//                    .modifier(new Modifier().style("margin:0; font-size:24px; font-weight:800; color:var(--j-text-primary); letter-spacing:-0.5px;")),
//                Paragraph.of(Text.of("Real-time operational monitoring, multi-model storage hierarchy, and cluster telemetry."))
//                    .modifier(new Modifier().style("margin:4px 0 0 0; color:var(--j-text-muted); font-size:13px;"))
//            ),
//            Div.of(
//                Button.of(
//                    Icon.of("fas fa-save").modifier(new Modifier().style("margin-right:6px;")),
//                    Text.of("Create Backup Snapshot")
//                ).attribute("onclick", "triggerBackup()")
//                 .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:8px 16px; font-size:12px; font-weight:600;")),
//                Link.of(JettraServer.resolvePath("/engines"),
//                    Icon.of("fas fa-cubes").modifier(new Modifier().style("margin-right:6px;")),
//                    Text.of("Hierarchy Explorer")
//                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:8px 16px; font-size:12px; font-weight:600; margin-left:10px;"))
//            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:8px;"))
//        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; flex-wrap:wrap; gap:12px;"));

        // 2. Top KPI Summary Cards Row
        Widget kpiRow = KpiSummaryCardsPanel.build(snapshot.kpi());

        // 3. Middle Charts Row: Multi-Model Distribution (Doughnut) + Throughput & Latency (Line)
        Widget chartsRow = Div.of(
            MultiModelDistributionPanel.build(snapshot.distribution()),
            ThroughputLatencyPanel.build(snapshot.telemetry())
        ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:16px; margin-bottom:24px; width:100%;"));

        // 4. Storage Hierarchy Panel: Comparative Bar Chart + Namespaces Datatable
        Widget hierarchyPanel = EngineHierarchyChartPanel.build(snapshot.hierarchy());

        // 5. System Health & Resource Allocation Panel
        Widget healthPanel = SystemHealthPanel.build(snapshot.health());

        // 6. Quick Operations & Network Interfaces Panel
        Widget bottomPanel = QuickActionsAndEndpointsPanel.build();

        // 7. Client-side backup trigger script
        Widget backupScript = RawScript.of(
            "async function triggerBackup() {\n" +
            "  try {\n" +
            "    const res = await fetch('" + JettraServer.resolvePath("/api/backup") + "', { method: 'POST' });\n" +
            "    if (res.ok) {\n" +
            "      alert('Backup snapshot successfully initiated and written to storage directory!');\n" +
            "    } else {\n" +
            "      alert('Backup failed with status: ' + res.status);\n" +
            "    }\n" +
            "  } catch(e) {\n" +
            "    alert('Error triggering backup: ' + e);\n" +
            "  }\n" +
            "}"
        );

        return Column.of(
            titleBlock,
            kpiRow,
            chartsRow,
            hierarchyPanel,
            healthPanel,
            bottomPanel,
            backupScript
        ).modifier(new Modifier().style("width:100%; max-width:1440px; margin:0 auto; padding:4px; box-sizing:border-box;"));
    }
}
