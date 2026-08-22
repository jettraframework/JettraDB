package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main dashboard for JettraStoreEngine built entirely with JettraFlux components.
 */
@NoLoginRequired
public class StoreDashboardPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;

    public StoreDashboardPage(JettraStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    protected String getPageTitle() {
        return "Dashboard - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        // Collect System Metrics
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long usedHeapMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long maxHeapMb = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        int heapPercent = (int) ((usedHeapMb * 100) / (maxHeapMb > 0 ? maxHeapMb : 1));

        File dataDir = new File(engine.getStorageDir().toString());
        long totalDiskMb = dataDir.getTotalSpace() / (1024 * 1024);
        long freeDiskMb = dataDir.getFreeSpace() / (1024 * 1024);
        long usedDiskMb = totalDiskMb - freeDiskMb;

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        long uptimeSeconds = runtimeBean.getUptime() / 1000;
        long hours = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        String uptimeStr = String.format("%02dh %02dm %02ds", hours, minutes, seconds);

        // Section Header
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1, Text.of("Storage Engine Dashboard"))
                    .modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(Text.of("Real-time operational monitoring, multi-model storage status, and cluster topology."))
                    .modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            ),
            Row.of(
                Button.of(
                    Icon.of("fas fa-save"),
                    Text.of(" Create Backup Snapshot")
                ).attribute("onclick", "triggerBackup()")
                 .modifier(new Modifier().cssClass("btn-action btn-primary")),
                Link.of(JettraServer.resolvePath("/engines"),
                    Icon.of("fas fa-cubes"),
                    Text.of(" Explorer")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-left: 8px;"))
            ).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Metric Cards Grid
        Widget ramCard = createStatCard("fas fa-memory", "#3b82f6", "JVM Heap Memory", usedHeapMb + " MB / " + maxHeapMb + " MB", heapPercent + "% utilized", "badge-engine");
        Widget diskCard = createStatCard("fas fa-hdd", "#10b981", "Data Storage Disk", usedDiskMb + " MB / " + totalDiskMb + " MB", engine.getStorageDir().toString(), "badge-active");
        Widget uptimeCard = createStatCard("fas fa-clock", "#f59e0b", "Engine Uptime", uptimeStr, "Java 25 (JEP 450 Active)", "badge-raft");
        Widget consensusCard = createStatCard("fas fa-network-wired", "#8b5cf6", "Cluster / Raft", "1 Node (Leader)", "Port 9092 | Consensus OK", "badge-raft");

        Widget statsGrid = Div.of(ramCard, diskCard, uptimeCard, consensusCard)
            .modifier(new Modifier().cssClass("store-stat-grid"));

        // Live Databases & Components Discovery Block
        Map<String, Map<String, Integer>> dbMap = new LinkedHashMap<>();
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
            String eng = switch (p) {
                case "rec:" -> "RECORDS";
                case "vec:" -> "VECTOR";
                case "graph:" -> "GRAPH";
                case "ts:" -> "TIMESERIES";
                case "col:" -> "COLUMN";
                case "kv:" -> "KEYVALUE";
                case "geo:" -> "GEOSPATIAL";
                case "obj:" -> "OBJECT";
                default -> "DOCUMENT";
            };
            for (String k : keys.keySet()) {
                String rest = k.substring(p.length());
                int colonIdx = rest.indexOf(':');
                String dbName = colonIdx > 0 ? rest.substring(0, colonIdx) : "default";
                dbMap.computeIfAbsent(dbName, d -> new LinkedHashMap<>()).merge(eng, 1, Integer::sum);
            }
        }
        if (dbMap.isEmpty()) {
            dbMap.computeIfAbsent("records_store", d -> new LinkedHashMap<>()).put("RECORDS", 1);
            dbMap.computeIfAbsent("system_db", d -> new LinkedHashMap<>()).put("DOCUMENT", 1);
        }

        List<Widget> dbTableHeaders = List.of(
            Text.of("Database Namespace"),
            Text.of("Active Components"),
            Text.of("Stored Entities"),
            Text.of("Actions")
        );

        List<List<Widget>> dbTableRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : dbMap.entrySet()) {
            String db = entry.getKey();
            Map<String, Integer> comps = entry.getValue();
            int totalKeys = comps.values().stream().mapToInt(Integer::intValue).sum();

            List<Widget> compBadges = new ArrayList<>();
            for (Map.Entry<String, Integer> comp : comps.entrySet()) {
                String eng = comp.getKey();
                int cnt = comp.getValue();
                String badgeStyle = switch (eng) {
                    case "RECORDS" -> "background:rgba(244,63,94,0.15); color:#f43f5e; border:1px solid rgba(244,63,94,0.3);";
                    case "DOCUMENT" -> "background:rgba(56,189,248,0.15); color:#38bdf8; border:1px solid rgba(56,189,248,0.3);";
                    case "VECTOR" -> "background:rgba(168,85,247,0.15); color:#c084fc; border:1px solid rgba(168,85,247,0.3);";
                    case "KEYVALUE" -> "background:rgba(34,197,94,0.15); color:#4ade80; border:1px solid rgba(34,197,94,0.3);";
                    default -> "background:rgba(99,102,241,0.15); color:#818cf8; border:1px solid rgba(99,102,241,0.3);";
                };
                compBadges.add(Span.of(eng + " (" + cnt + ")").modifier(new Modifier().style(badgeStyle + " padding:2px 8px; border-radius:6px; font-size:11px; font-weight:600; margin-right:5px;")));
            }

            Widget dbCell = Div.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Span.of(db).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;"));

            Widget compCell = Div.of(compBadges.toArray(new Widget[0]));
            Widget countCell = Span.of(totalKeys + " keys").modifier(new Modifier().cssClass("store-badge badge-active"));
            Widget actionCell = Link.of(JettraServer.resolvePath("/engines?engine=RECORDS&db=" + db),
                Icon.of("fas fa-search"),
                Text.of(" Explore")
            ).modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:4px 10px; font-size:12px;"));

            dbTableRows.add(List.of(dbCell, compCell, countCell, actionCell));
        }

        Datatable dbDatatable = Datatable.ofWidgets(dbTableHeaders, dbTableRows);
        dbDatatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget dbSummaryCard = Div.of(
            Row.of(
                Header.of(3,
                    Icon.of("fas fa-server").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Active Database Namespaces (" + dbMap.size() + ")")
                ).modifier(new Modifier().style("margin: 0; font-size: 18px; font-weight: 600;")),
                Link.of(JettraServer.resolvePath("/databases"),
                    Icon.of("fas fa-external-link-alt"),
                    Text.of(" Manage All Databases")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 12px;"))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 14px;")),
            Div.of(dbDatatable).modifier(new Modifier().cssClass("table-responsive"))
        ).modifier(new Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Multi-Model Database Engines Section
        Widget enginesHeader = Row.of(
            Header.of(2,
                Icon.of("fas fa-cubes").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Text.of("Supported Database Engines (9 Multi-Models)")
            ).modifier(new Modifier().style("margin: 0; font-size: 20px; font-weight: 600;")),
            Span.of("All 9 Active").modifier(new Modifier().cssClass("store-badge badge-active"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 16px; margin-top: 12px;"));

        Widget docEngineCard = createEngineSummaryCard("DOCUMENT", "fas fa-file-alt", "#3b82f6", "Document Store", "JSON / BSON document store with collection partitioning, compound indexing, and schema-free queries.");
        Widget vectorEngineCard = createEngineSummaryCard("VECTOR", "fas fa-project-diagram", "#8b5cf6", "Vector Embeddings", "High-dimensional vector similarity engine with Cosine distance and Approximate Nearest Neighbor (ANN) index.");
        Widget graphEngineCard = createEngineSummaryCard("GRAPH", "fas fa-share-alt", "#ec4899", "Graph & Relationships", "Directed and undirected graph engine for nodes, vertices, and adjacency list traversal queries.");
        Widget timeSeriesCard = createEngineSummaryCard("TIMESERIES", "fas fa-chart-line", "#06b6d4", "Time Series Telemetry", "High-throughput temporal time-series engine for metric monitoring, timestamp sorting, and downsampling.");
        Widget columnEngineCard = createEngineSummaryCard("COLUMN", "fas fa-table", "#f97316", "Columnar OLAP", "Column-oriented analytical engine with high data compression and fast vectorized column aggregations.");
        Widget kvEngineCard = createEngineSummaryCard("KEYVALUE", "fas fa-key", "#10b981", "Key-Value Store", "Low-latency key-value engine backed by an in-memory LSM MemTable and WAL for instant lookups.");
        Widget geoEngineCard = createEngineSummaryCard("GEOSPATIAL", "fas fa-globe-americas", "#14b8a6", "Geospatial GIS", "2D spatial coordinates engine with bounding box queries and Haversine geodesic radius calculations.");
        Widget objectEngineCard = createEngineSummaryCard("OBJECT", "fas fa-archive", "#a855f7", "Object & Binary Blob", "Large binary objects, file payloads, and serialized byte stream persistence.");
        Widget recordsEngineCard = createEngineSummaryCard("RECORDS", "fas fa-id-card", "#f43f5e", "Java Records Store", "High-density immutable Java records storage with schema inspection, component validation, and field projections.");

        Widget enginesGrid = Div.of(
            docEngineCard, vectorEngineCard, graphEngineCard, timeSeriesCard,
            columnEngineCard, kvEngineCard, geoEngineCard, objectEngineCard, recordsEngineCard
        ).modifier(new Modifier().cssClass("engine-grid"));

        // Bottom Operations & Client Info Section
        Widget bottomSection = Row.of(
            createQuickActionCard(),
            createNetworkEndpointsCard()
        ).modifier(new Modifier().style("display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 24px;"));

        // Client-side script for backup trigger
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
            statsGrid,
            enginesHeader,
            enginesGrid,
            bottomSection,
            backupScript
        );
    }

    private Widget createStatCard(String icon, String color, String title, String mainValue, String subValue, String badgeClass) {
        return Div.of(
            Row.of(
                Div.of(Icon.of(icon).modifier(new Modifier().style("color: " + color + "; font-size: 20px;")))
                    .modifier(new Modifier().style("background: rgba(255,255,255,0.06); width: 42px; height: 42px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("LIVE").modifier(new Modifier().cssClass("store-badge " + badgeClass))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Div.of(Text.of(title)).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; font-weight: 500;")),
            Div.of(Text.of(mainValue)).modifier(new Modifier().style("font-size: 22px; font-weight: 700; color: #f8fafc; margin: 4px 0;")),
            Div.of(Text.of(subValue)).modifier(new Modifier().style("font-size: 12px; color: #64748b;"))
        ).modifier(new Modifier().cssClass("store-card"));
    }

    private Widget createEngineSummaryCard(String engineKey, String icon, String color, String name, String description) {
        boolean isRegistered = engine.getEngine(engineKey) != null;
        String statusText = isRegistered ? "ACTIVE" : "STANDBY";
        String statusBadge = isRegistered ? "badge-active" : "badge-engine";

        return Div.of(
            Column.of(
                Row.of(
                    Row.of(
                        Div.of(Icon.of(icon).modifier(new Modifier().style("color: " + color + ";")))
                            .modifier(new Modifier().cssClass("engine-icon-box").style("background: " + color + "20;")),
                        Column.of(
                            Div.of(Text.of(name)).modifier(new Modifier().style("font-weight: 700; font-size: 16px; color: #f8fafc;")),
                            Div.of(Text.of("ENGINE: " + engineKey)).modifier(new Modifier().style("font-size: 11px; color: #94a3b8; letter-spacing: 0.5px;"))
                        ).modifier(new Modifier().style("margin-left: 12px;"))
                    ).modifier(new Modifier().style("align-items: center;")),
                    Span.of(statusText).modifier(new Modifier().cssClass("store-badge " + statusBadge))
                ).modifier(new Modifier().style("justify-content: space-between; align-items: center;")),
                Paragraph.of(Text.of(description)).modifier(new Modifier().style("font-size: 13px; color: #cbd5e1; line-height: 1.5; margin: 12px 0 16px 0;")),
                Row.of(
                    Link.of(JettraServer.resolvePath("/engines?engine=" + engineKey),
                        Icon.of("fas fa-search"),
                        Text.of(" Explore Data")
                    ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size: 12px; padding: 6px 12px;"))
                ).modifier(new Modifier().style("margin-top: auto;"))
            ).modifier(new Modifier().cssClass("engine-item"))
        ).modifier(new Modifier().cssClass("store-card"));
    }

    private Widget createQuickActionCard() {
        return Div.of(
            Header.of(3,
                Icon.of("fas fa-tools").modifier(new Modifier().style("color: #60a5fa; margin-right: 8px;")),
                Text.of("Quick Operations")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Paragraph.of(Text.of("Perform direct administrative and maintenance actions on the active node."))
                .modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 16px;")),
            Row.of(
                Link.of(JettraServer.resolvePath("/users"),
                    Icon.of("fas fa-user-shield"),
                    Text.of(" Manage Users")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size: 13px;")),
                Link.of(JettraServer.resolvePath("/components"),
                    Icon.of("fas fa-layer-group"),
                    Text.of(" Storage Core")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size: 13px; margin-left: 8px;")),
                Button.of(
                    Icon.of("fas fa-hdd"),
                    Text.of(" Snapshot WAL")
                ).attribute("onclick", "triggerBackup()")
                 .modifier(new Modifier().cssClass("btn-action btn-primary").style("font-size: 13px; margin-left: 8px;"))
            )
        ).modifier(new Modifier().cssClass("store-card"));
    }

    private Widget createNetworkEndpointsCard() {
        return Div.of(
            Header.of(3,
                Icon.of("fas fa-plug").modifier(new Modifier().style("color: #34d399; margin-right: 8px;")),
                Text.of("Network Interfaces")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Div.of(
                Div.of(
                    Span.of("HTTP REST API:"),
                    RawHtml.of("<code style='color:#38bdf8;'>http://localhost:8080/api/</code>")
                ).modifier(new Modifier().style("padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;")),
                Div.of(
                    Span.of("Multi-Model API:"),
                    RawHtml.of("<code style='color:#38bdf8;'>http://localhost:8080/api/model/{engine}</code>")
                ).modifier(new Modifier().style("padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;")),
                Div.of(
                    Span.of("Document API:"),
                    RawHtml.of("<code style='color:#38bdf8;'>http://localhost:8080/api/document/{coll}</code>")
                ).modifier(new Modifier().style("padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;")),
                Div.of(
                    Span.of("Swagger OpenAPI:"),
                    Link.of(JettraServer.resolvePath("/swagger-ui"), Text.of("/swagger-ui"))
                        .modifier(new Modifier().style("color:#a78bfa; text-decoration:none;"))
                ).modifier(new Modifier().style("padding: 6px 0; display: flex; justify-content: space-between;"))
            ).modifier(new Modifier().style("font-size: 13px; color: #cbd5e1;"))
        ).modifier(new Modifier().cssClass("store-card"));
    }
}
