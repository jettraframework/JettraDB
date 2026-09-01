package com.jettra.store.engine.dashboard;

import io.jettra.server.JettraServer;
import com.jettra.store.engine.dashboard.DashboardMetrics.DatabaseStorageHierarchy;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Engine Storage Hierarchy & Comparative Volume Panel for JettraDB Dashboard.
 * Integrates native JettraFlux CharsBar chart and a responsive Datatable breakdown
 * of database namespaces, components, and stored key counts.
 */
public final class EngineHierarchyChartPanel {

    private EngineHierarchyChartPanel() {}

    public static Widget build(DatabaseStorageHierarchy hierarchy) {
        // Panel Header
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-chart-bar").modifier(new Modifier().style("color:#8b5cf6; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Storage Hierarchy & Namespace Breakdown"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
                Span.of(hierarchy.dbModelCounts().size() + " Active Databases")
                    .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px; margin-left:8px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Link.of(JettraServer.resolvePath("/databases"),
                Icon.of("fas fa-external-link-alt"),
                Text.of(" Manage Namespaces")
            ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size:11.5px; padding:4px 10px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;"));

        // Native JettraFlux Bar Chart
        Widget barChart = CharsBar.of()
            .modifier(new Modifier().style("width:100%; height:220px; position:relative; margin-bottom:16px;"));

        // Datatable breakdown
        List<Widget> tableHeaders = List.of(
            Text.of("Database Namespace"),
            Text.of("Active Storage Models"),
            Text.of("Stored Entities"),
            Text.of("Actions")
        );

        List<List<Widget>> tableRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : hierarchy.dbModelCounts().entrySet()) {
            String db = entry.getKey();
            Map<String, Integer> models = entry.getValue();
            int totalKeys = models.values().stream().mapToInt(Integer::intValue).sum();

            List<Widget> badges = new ArrayList<>();
            for (Map.Entry<String, Integer> m : models.entrySet()) {
                String eng = m.getKey();
                int cnt = m.getValue();
                String color = switch (eng) {
                    case "RECORDS" -> "#f43f5e";
                    case "DOCUMENT" -> "#38bdf8";
                    case "VECTOR" -> "#8b5cf6";
                    case "KEYVALUE" -> "#10b981";
                    case "GRAPH" -> "#ec4899";
                    case "TIMESERIES" -> "#06b6d4";
                    case "COLUMN" -> "#f97316";
                    case "GEOSPATIAL" -> "#14b8a6";
                    case "OBJECT" -> "#a855f7";
                    default -> "#6366f1";
                };

                badges.add(Span.of(eng + " (" + cnt + ")").modifier(new Modifier()
                    .style("background:" + color + "1f; color:" + color + "; border:1px solid " + color + "4d; padding:2px 8px; border-radius:5px; font-size:10.5px; font-weight:600; margin-right:4px; margin-bottom:2px; display:inline-block;")));
            }

            Widget dbCell = Div.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; margin-right:8px; font-size:12px;")),
                Span.of(db).modifier(new Modifier().style("color:var(--j-text-primary); font-weight:700; font-size:12px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;"));

            Widget modelCell = Div.of(badges.toArray(new Widget[0]))
                .modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:2px; align-items:center;"));

            Widget countCell = Span.of(String.format("%,d keys", totalKeys))
                .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10.5px; font-family:monospace; font-weight:700;"));

            Widget actionCell = Link.of(JettraServer.resolvePath("/engines?engine=DOCUMENT&target_db=" + db),
                Icon.of("fas fa-search"),
                Text.of(" Explore")
            ).modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:3px 10px; font-size:11px;"));

            tableRows.add(List.of(dbCell, modelCell, countCell, actionCell));
        }

        Datatable datatable = Datatable.ofWidgets(tableHeaders, tableRows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget tableContainer = Div.of(datatable)
            .modifier(new Modifier().cssClass("table-responsive").style("max-height:260px; overflow-y:auto; border:1px solid var(--j-border); border-radius:8px;"));

        return Div.of(header, barChart, tableContainer)
            .modifier(new Modifier()
                .cssClass("store-card")
                .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.05); margin-bottom:24px; width:100%;"));
    }
}
