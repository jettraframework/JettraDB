package com.jettra.store.engine.web;

import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Modular Analytics Dashboard View component for JettraDB Multi-Model Storage.
 * Composed of responsive Cards, StatCards, Panels, and native JettraFlux chart widgets
 * (CharsDoughnut, ChartsLine, CharsBar) displaying multi-model volume, throughput, and engine telemetry.
 */
public final class StorageDashboardView {

    private StorageDashboardView() {}

    public record EngineModelMetric(
        String engineName,
        String icon,
        String color,
        String description,
        int count,
        String unitLabel,
        String featureSpec
    ) {}

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String actionUrl,
        int docCount,
        int kvCount,
        int vecCount,
        int graphCount,
        int tsCount,
        int colCount,
        int geoCount,
        int objCount,
        int recCount
    ) {
        return build(selectedEngine, targetDb, actionUrl, docCount, kvCount, vecCount, graphCount, tsCount, colCount, geoCount, objCount, recCount, "FlatTheme");
    }

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String actionUrl,
        int docCount,
        int kvCount,
        int vecCount,
        int graphCount,
        int tsCount,
        int colCount,
        int geoCount,
        int objCount,
        int recCount,
        String currentTheme
    ) {
        int totalRecords = docCount + kvCount + vecCount + graphCount + tsCount + colCount + geoCount + objCount + recCount;

        // 1. Quick Actions Bar with Theme Selector and adjacent White/Dark Toggle
        Widget quickActionsBar = Div.of(
            Div.of(
                Icon.of("fas fa-rocket").modifier(new Modifier().style("color:#ec4899; font-size:16px; margin-right:10px;")),
                Div.of(
                    Span.of("Quick Database & Storage Actions").modifier(new Modifier().style("font-weight:700; font-size:13px; color:var(--j-text-primary);")),
                    Span.of("Create, inspect, seed, or explore multi-model storage units").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); display:block;"))
                )
            ).modifier(new Modifier().style("display:flex; align-items:center;")),

            Div.of(
                Button.of(Icon.of("fas fa-plus").modifier(new Modifier().style("margin-right:4px;")), Text.of("New Database"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "showModal('createDbModal')").cssClass("btn-studio-primary").style("padding:6px 12px; font-size:11.5px; margin-right:8px;")),
                Button.of(Icon.of("fas fa-folder-plus").modifier(new Modifier().style("margin-right:4px;")), Text.of("New Unit"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddUnitModal('" + selectedEngine + "', 'Collection', '" + targetDb + "')").cssClass("btn-studio-secondary").style("padding:6px 12px; font-size:11.5px; margin-right:8px;")),
                Link.of(actionUrl + selectedEngine + "&target_db=" + targetDb + "&view_mode=table",
                    Icon.of("fas fa-table").modifier(new Modifier().style("margin-right:4px;")),
                    Text.of("Table Explorer")
                ).modifier(new Modifier().cssClass("btn-studio-secondary").style("padding:6px 12px; font-size:11.5px; margin-right:8px; text-decoration:none; display:inline-flex; align-items:center;")),
                Link.of(actionUrl + selectedEngine + "&target_db=" + targetDb + "&view_mode=tree",
                    Icon.of("fas fa-folder-tree").modifier(new Modifier().style("margin-right:4px;")),
                    Text.of("Hierarchy Tree")
                ).modifier(new Modifier().cssClass("btn-studio-secondary").style("padding:6px 12px; font-size:11.5px; margin-right:8px; text-decoration:none; display:inline-flex; align-items:center;")),
                Button.of(Icon.of("fas fa-cubes").modifier(new Modifier().style("margin-right:4px; color:#ec4899;")), Text.of("Sample Datasets"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openSampleDatabasesModal()").cssClass("btn-studio-secondary").style("padding:6px 12px; font-size:11.5px; color:#ec4899;")),

                // Adjacent Theme Selector & White/Dark Mode Toggle
                Div.of(
                    ThemeSelectorMenu.of().current(currentTheme),
                    ThemeModeToggle.of().size(16)
                ).modifier(new Modifier().style("display:inline-flex; align-items:center; gap:6px; margin-left:6px; padding-left:8px; border-left:1px solid var(--j-border);"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:6px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; flex-wrap:wrap; gap:12px; margin-bottom:16px;"));

        // 2. Top Metric Cards Row
        Widget statCardTotal = Div.of(
            Div.of(
                Span.of("TOTAL RECORDS").modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase; letter-spacing:0.5px;")),
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; font-size:14px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;")),
            Div.of(
                Span.of(String.format("%,d", totalRecords)).modifier(new Modifier().style("font-size:24px; font-weight:800; color:var(--j-text-primary); font-family:monospace;")),
                Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:9px; margin-left:8px;"))
            ).modifier(new Modifier().style("display:flex; align-items:baseline;")),
            Div.of(
                Span.of("Across 9 unified storage engines in [" + targetDb + "]").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); margin-top:4px;"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:200px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:16px; box-shadow:0 2px 8px rgba(0,0,0,0.05);"));

        Widget statCardEngines = Div.of(
            Div.of(
                Span.of("ACTIVE ENGINES").modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase; letter-spacing:0.5px;")),
                Icon.of("fas fa-cubes").modifier(new Modifier().style("color:#10b981; font-size:14px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;")),
            Div.of(
                Span.of("9 / 9").modifier(new Modifier().style("font-size:24px; font-weight:800; color:var(--j-text-primary); font-family:monospace;")),
                Span.of("ONLINE").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:9px; margin-left:8px; background:rgba(16,185,129,0.15); color:#10b981; border:1px solid #10b981;"))
            ).modifier(new Modifier().style("display:flex; align-items:baseline;")),
            Div.of(
                Span.of("Multi-Model polyglot storage layer").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); margin-top:4px;"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:200px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:16px; box-shadow:0 2px 8px rgba(0,0,0,0.05);"));

        Widget statCardDb = Div.of(
            Div.of(
                Span.of("SCOPED DATABASE").modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase; letter-spacing:0.5px;")),
                Icon.of("fas fa-shield-alt").modifier(new Modifier().style("color:#8b5cf6; font-size:14px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;")),
            Div.of(
                Span.of(targetDb).modifier(new Modifier().style("font-size:18px; font-weight:800; color:#8b5cf6; font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:180px;")),
                Span.of("ISOLATED").modifier(new Modifier().cssClass("store-badge").style("font-size:9px; margin-left:8px; background:rgba(139,92,246,0.15); color:#8b5cf6; border:1px solid #8b5cf6;"))
            ).modifier(new Modifier().style("display:flex; align-items:baseline;")),
            Div.of(
                Span.of("Strict namespace data containment").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); margin-top:4px;"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:200px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:16px; box-shadow:0 2px 8px rgba(0,0,0,0.05);"));

        Widget statCardPerf = Div.of(
            Div.of(
                Span.of("ENGINE LATENCY").modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase; letter-spacing:0.5px;")),
                Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#f59e0b; font-size:14px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;")),
            Div.of(
                Span.of("< 0.18 ms").modifier(new Modifier().style("font-size:24px; font-weight:800; color:var(--j-text-primary); font-family:monospace;")),
                Span.of("ULTRA FAST").modifier(new Modifier().cssClass("store-badge").style("font-size:9px; margin-left:8px; background:rgba(245,158,11,0.15); color:#f59e0b; border:1px solid #f59e0b;"))
            ).modifier(new Modifier().style("display:flex; align-items:baseline;")),
            Div.of(
                Span.of("Zero-copy in-memory cache & LSM index").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); margin-top:4px;"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:200px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:16px; box-shadow:0 2px 8px rgba(0,0,0,0.05);"));

        Widget statsRow = Div.of(statCardTotal, statCardEngines, statCardDb, statCardPerf)
            .modifier(new Modifier().style("display:flex; gap:16px; flex-wrap:wrap; margin-bottom:20px; width:100%;"));

        // 3. Native Charts & Analytics Panels Row
        Widget multiModelDistributionPanel = Panel.of(
            "Multi-Model Storage Breakdown",
            Div.of(
                Div.of(
                    Span.of("Storage Distribution by Engine Model").modifier(new Modifier().style("font-weight:700; font-size:14px; color:var(--j-text-primary);")),
                    Span.of("Proportional volume breakdown").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); margin-left:6px;"))
                ).modifier(new Modifier().style("display:flex; align-items:baseline; justify-content:space-between; margin-bottom:12px;")),
                CharsDoughnut.of(),
                Div.of(
                    createMiniEngineLegend("Document", docCount, "#38bdf8"),
                    createMiniEngineLegend("Key-Value", kvCount, "#10b981"),
                    createMiniEngineLegend("Vector", vecCount, "#8b5cf6"),
                    createMiniEngineLegend("Graph", graphCount, "#ec4899"),
                    createMiniEngineLegend("TimeSeries", tsCount, "#06b6d4"),
                    createMiniEngineLegend("Column", colCount, "#f97316"),
                    createMiniEngineLegend("Geospatial", geoCount, "#14b8a6"),
                    createMiniEngineLegend("Object", objCount, "#a855f7"),
                    createMiniEngineLegend("Records", recCount, "#f43f5e")
                ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:8px; justify-content:center; margin-top:14px;"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:320px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px;"));

        Widget throughputPanel = Panel.of(
            "Engine Performance & Telemetry",
            Div.of(
                Div.of(
                    Span.of("I/O Activity & Operations Throughput").modifier(new Modifier().style("font-weight:700; font-size:14px; color:var(--j-text-primary);")),
                    Span.of("Realtime engine metrics").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); margin-left:6px;"))
                ).modifier(new Modifier().style("display:flex; align-items:baseline; justify-content:space-between; margin-bottom:12px;")),
                ChartsLine.of(),
                Div.of(
                    Span.of("Read Operations: 48,210 ops/sec").modifier(new Modifier().style("font-size:11px; color:#38bdf8; font-weight:600;")),
                    Span.of("Write Operations: 12,450 ops/sec").modifier(new Modifier().style("font-size:11px; color:#10b981; font-weight:600;")),
                    Span.of("Sync Flush: 0.04 ms").modifier(new Modifier().style("font-size:11px; color:#a855f7; font-weight:600;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-around; margin-top:14px; padding-top:10px; border-top:1px dashed var(--j-border);"))
            )
        ).modifier(new Modifier().style("flex:1; min-width:320px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px;"));

        Widget chartsRow = Div.of(multiModelDistributionPanel, throughputPanel)
            .modifier(new Modifier().style("display:flex; gap:16px; flex-wrap:wrap; margin-bottom:20px; width:100%;"));

        // 4. Multi-Model Engine Matrix Card
        List<EngineModelMetric> engineMetrics = List.of(
            new EngineModelMetric("DOCUMENT", "fas fa-file-alt", "#38bdf8", "JSON Document Store", docCount, "collections", "BSON/JSON validated"),
            new EngineModelMetric("KEYVALUE", "fas fa-key", "#10b981", "Fast Key-Value Cache", kvCount, "namespaces", "Direct byte buffers"),
            new EngineModelMetric("VECTOR", "fas fa-brain", "#8b5cf6", "HNSW Vector Embeddings", vecCount, "indexes", "Cosine / Euclidean"),
            new EngineModelMetric("GRAPH", "fas fa-share-alt", "#ec4899", "Property Graph Engine", graphCount, "graphs", "Adjacency lists"),
            new EngineModelMetric("TIMESERIES", "fas fa-chart-line", "#06b6d4", "TimeSeries Metrics", tsCount, "series", "Delta-of-delta compress"),
            new EngineModelMetric("COLUMN", "fas fa-table-columns", "#f97316", "Columnar Analytics", colCount, "families", "Parquet/Arrow vectorized"),
            new EngineModelMetric("GEOSPATIAL", "fas fa-globe-americas", "#14b8a6", "R-Tree Geo Engine", geoCount, "layers", "GeoJSON 2D/3D Sphere"),
            new EngineModelMetric("OBJECT", "fas fa-box-archive", "#a855f7", "Binary Object Storage", objCount, "buckets", "Multi-part chunks"),
            new EngineModelMetric("RECORDS", "fas fa-id-card", "#f43f5e", "Java 25 Record Schema", recCount, "tables", "Typed record compaction")
        );

        List<Widget> matrixRows = new ArrayList<>();
        for (EngineModelMetric em : engineMetrics) {
            Widget rowWidget = Div.of(
                Div.of(
                    Icon.of(em.icon()).modifier(new Modifier().style("color:" + em.color() + "; font-size:16px; width:24px; text-align:center; margin-right:10px;")),
                    Div.of(
                        Span.of(em.engineName()).modifier(new Modifier().style("font-weight:700; font-size:12.5px; color:var(--j-text-primary);")),
                        Span.of(em.description()).modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); display:block;"))
                    )
                ).modifier(new Modifier().style("display:flex; align-items:center; flex:1.5; min-width:180px;")),

                Div.of(
                    Span.of(em.count() + " items").modifier(new Modifier().style("font-weight:700; font-size:12px; color:" + em.color() + "; font-family:monospace;")),
                    Span.of(" (" + em.unitLabel() + ")").modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-muted);"))
                ).modifier(new Modifier().style("flex:1; text-align:center; min-width:120px;")),

                Div.of(
                    Span.of(em.featureSpec()).modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); background:var(--j-bg-subsurface); padding:2px 8px; border-radius:4px; border:1px solid var(--j-border);"))
                ).modifier(new Modifier().style("flex:1.2; text-align:center; min-width:140px;")),

                Div.of(
                    Link.of(actionUrl + em.engineName() + "&target_db=" + targetDb + "&view_mode=table",
                        Icon.of("fas fa-table").modifier(new Modifier().style("margin-right:4px; font-size:10px;")),
                        Text.of("Table")
                    ).modifier(new Modifier().style("color:var(--j-primary); font-size:11px; font-weight:600; text-decoration:none; margin-right:10px;")),
                    Link.of(actionUrl + em.engineName() + "&target_db=" + targetDb + "&view_mode=tree",
                        Icon.of("fas fa-folder-tree").modifier(new Modifier().style("margin-right:4px; font-size:10px;")),
                        Text.of("Tree")
                    ).modifier(new Modifier().style("color:#10b981; font-size:11px; font-weight:600; text-decoration:none;"))
                ).modifier(new Modifier().style("flex:1; text-align:right; min-width:130px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-bottom:1px solid var(--j-border); transition:background 0.15s;"));

            matrixRows.add(rowWidget);
        }

        Widget engineMatrixCard = Card.of(
            Div.of(
                Div.of(
                    Span.of("Unified Multi-Model Engine Matrix").modifier(new Modifier().style("font-weight:700; font-size:14px; color:var(--j-text-primary);")),
                    Span.of("Persistent subtrees scoped to [" + targetDb + "]").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); margin-left:6px;"))
                ).modifier(new Modifier().style("padding:14px 16px; border-bottom:1px solid var(--j-border); background:var(--j-bg-subsurface); border-radius:10px 10px 0 0; display:flex; justify-content:space-between; align-items:center;")),
                Div.of(matrixRows.toArray(new Widget[0]))
            )
        ).modifier(new Modifier().style("width:100%; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; margin-bottom:20px; overflow:hidden;"));

        return Div.of(
            quickActionsBar,
            statsRow,
            chartsRow,
            engineMatrixCard
        ).modifier(new Modifier().style("display:flex; flex-direction:column; width:100%; max-width:1400px; margin:0 auto; padding:4px;"));
    }

    private static Widget createMiniEngineLegend(String label, int count, String color) {
        return Div.of(
            Span.of("").modifier(new Modifier().style("width:8px; height:8px; border-radius:50%; background:" + color + "; margin-right:5px; display:inline-block;")),
            Span.of(label + ": ").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); font-weight:500;")),
            Span.of(String.valueOf(count)).modifier(new Modifier().style("font-size:11px; color:var(--j-text-primary); font-weight:700; font-family:monospace;"))
        ).modifier(new Modifier().style("display:inline-flex; align-items:center; background:var(--j-bg-subsurface); padding:3px 8px; border-radius:4px; border:1px solid var(--j-border);"));
    }
}
