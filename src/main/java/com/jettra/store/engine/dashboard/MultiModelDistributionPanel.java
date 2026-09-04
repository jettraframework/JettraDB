package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.dashboard.DashboardMetrics.MultiModelDistribution;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Model Storage Distribution Panel for JettraDB Dashboard.
 * Integrates native JettraFlux CharsDoughnut / CharsPie chart with an interactive
 * engine breakdown matrix showing entity volume per storage model.
 */
public final class MultiModelDistributionPanel {

    private MultiModelDistributionPanel() {}

    private record EngineMeta(String name, String label, String color, String icon) {}

    private static final EngineMeta[] ENGINES = {
        new EngineMeta("DOCUMENT", "Document Store", "#3b82f6", "fas fa-file-alt"),
        new EngineMeta("KEYVALUE", "Key-Value Store", "#10b981", "fas fa-key"),
        new EngineMeta("VECTOR", "Vector Index", "#8b5cf6", "fas fa-project-diagram"),
        new EngineMeta("GRAPH", "Graph & Nodes", "#ec4899", "fas fa-share-alt"),
        new EngineMeta("TIMESERIES", "Time Series", "#06b6d4", "fas fa-chart-line"),
        new EngineMeta("COLUMN", "Columnar OLAP", "#f97316", "fas fa-table"),
        new EngineMeta("GEOSPATIAL", "Geospatial GIS", "#14b8a6", "fas fa-globe-americas"),
        new EngineMeta("OBJECT", "Object Storage", "#a855f7", "fas fa-archive"),
        new EngineMeta("RECORDS", "Java Records", "#f43f5e", "fas fa-id-card")
    };

    public static Widget build(MultiModelDistribution dist) {
        // Panel Header
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-chart-pie").modifier(new Modifier().style("color:#38bdf8; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Multi-Model Data Distribution"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
                Span.of(dist.totalItems() + " Total Items")
                    .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px; margin-left:8px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of("9 Engines Unified").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted);"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;"));

        // Native JettraFlux Chart
        Widget chartWidget = CharsDoughnut.of()
            .modifier(new Modifier().style("width:100%; height:220px; position:relative;"));

        // Engine Breakdown Legend Matrix
        List<Widget> legendItems = new ArrayList<>();
        for (EngineMeta eng : ENGINES) {
            int count = dist.getCount(eng.name());
            double pct = dist.getPercentage(eng.name());

            Widget dot = Div.of()
                .modifier(new Modifier().style("width:8px; height:8px; border-radius:50%; background:" + eng.color() + "; margin-right:6px; flex-shrink:0;"));

            Widget nameSpan = Span.of(eng.label())
                .modifier(new Modifier().style("font-size:11.5px; color:var(--j-text-primary); font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

            Widget leftPart = Div.of(dot, nameSpan)
                .modifier(new Modifier().style("display:flex; align-items:center; min-width:0; flex:1;"));

            Widget countPart = Span.of(count + " (" + String.format("%.1f", pct) + "%)")
                .modifier(new Modifier().style("font-size:11px; font-weight:700; color:" + eng.color() + "; font-family:monospace; margin-left:8px;"));

            Widget row = Div.of(leftPart, countPart)
                .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:3px 0;"));

            legendItems.add(row);
        }

        Widget legendContainer = Div.of(legendItems.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:2px; max-height:220px; overflow-y:auto; padding-right:4px;"));

        Widget body = Div.of(
            Div.of(chartWidget).modifier(new Modifier().style("flex:1.1; min-width:220px; display:flex; align-items:center; justify-content:center;")),
            Div.of(legendContainer).modifier(new Modifier().style("flex:1.3; min-width:240px;"))
        ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:16px; align-items:center;"));

        return Div.of(header, body)
            .modifier(new Modifier()
                .cssClass("store-card")
                .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.05); flex:1; min-width:320px; box-sizing:border-box;"));
    }
}
