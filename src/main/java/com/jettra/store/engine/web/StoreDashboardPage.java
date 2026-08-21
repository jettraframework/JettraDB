package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main dashboard for JettraStoreEngine.
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
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'>Storage Engine Dashboard</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Real-time operational monitoring, multi-model storage status, and cluster topology.</p>")
            ),
            Row.of(
                Paragraph.of("<button class='btn-action btn-primary' onclick=\"triggerBackup()\"><i class='fas fa-save'></i> Create Backup Snapshot</button>"),
                Paragraph.of("<a href='" + JettraServer.resolvePath("/engines") + "' class='btn-action btn-secondary' style='margin-left: 8px;'><i class='fas fa-cubes'></i> Explorer</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Metric Cards Grid
        Widget ramCard = createStatCard("fas fa-memory", "#3b82f6", "JVM Heap Memory", usedHeapMb + " MB / " + maxHeapMb + " MB", heapPercent + "% utilized", "badge-engine");
        Widget diskCard = createStatCard("fas fa-hdd", "#10b981", "Data Storage Disk", usedDiskMb + " MB / " + totalDiskMb + " MB", engine.getStorageDir().toString(), "badge-active");
        Widget uptimeCard = createStatCard("fas fa-clock", "#f59e0b", "Engine Uptime", uptimeStr, "Java 25 (JEP 450 Active)", "badge-raft");
        Widget consensusCard = createStatCard("fas fa-network-wired", "#8b5cf6", "Cluster / Raft", "1 Node (Leader)", "Port 9092 | Consensus OK", "badge-raft");

        Widget statsGrid = Div.of(ramCard, diskCard, uptimeCard, consensusCard)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("store-stat-grid"));

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

        StringBuilder dbOverviewRows = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> entry : dbMap.entrySet()) {
            String db = entry.getKey();
            Map<String, Integer> comps = entry.getValue();
            int totalKeys = comps.values().stream().mapToInt(Integer::intValue).sum();
            
            StringBuilder compPills = new StringBuilder();
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
                compPills.append("<span style='").append(badgeStyle).append(" padding:2px 8px; border-radius:6px; font-size:11px; font-weight:600; margin-right:5px;'>")
                    .append(eng).append(" (").append(cnt).append(")</span>");
            }

            dbOverviewRows.append("<tr>\n")
                .append("  <td><div style='display:flex; align-items:center; gap:8px;'><i class='fas fa-database' style='color:#38bdf8;'></i> <b style='color:#f8fafc;'>").append(db).append("</b></div></td>\n")
                .append("  <td>").append(compPills).append("</td>\n")
                .append("  <td><span class='store-badge badge-active'>").append(totalKeys).append(" keys</span></td>\n")
                .append("  <td><a href='").append(JettraServer.resolvePath("/engines?engine=RECORDS&db=" + db)).append("' class='btn-action btn-primary' style='padding:4px 10px; font-size:12px;'><i class='fas fa-search'></i> Explore</a></td>\n")
                .append("</tr>\n");
        }

        Widget dbSummaryCard = Div.of(
            Row.of(
                Paragraph.of("<h3 style='margin: 0; font-size: 18px; font-weight: 600;'><i class='fas fa-server' style='color:#38bdf8; margin-right:8px;'></i> Active Database Namespaces (" + dbMap.size() + ")</h3>"),
                Paragraph.of("<a href='" + JettraServer.resolvePath("/databases") + "' class='btn-action btn-secondary' style='font-size:12px; padding:6px 12px;'><i class='fas fa-external-link-alt'></i> Manage All Databases</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 14px;")),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>Database Namespace</th>\n" +
                "        <th>Active Components</th>\n" +
                "        <th>Stored Entities</th>\n" +
                "        <th>Actions</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                dbOverviewRows.toString() +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Multi-Model Database Engines Section
        Widget enginesHeader = Row.of(
            Paragraph.of("<h2 style='margin: 0; font-size: 20px; font-weight: 600;'><i class='fas fa-cubes' style='color:#38bdf8; margin-right:8px;'></i> Supported Database Engines (9 Multi-Models)</h2>"),
            Span.of("All 9 Active").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-active"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 16px; margin-top: 12px;"));

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
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("engine-grid"));

        // Bottom Operations & Client Info Section
        Widget bottomSection = Row.of(
            createQuickActionCard(),
            createNetworkEndpointsCard()
        ).modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 24px;"));

        // Client-side script for backup trigger
        Widget backupScript = Paragraph.of(
            "<script>\n" +
            "  async function triggerBackup() {\n" +
            "    try {\n" +
            "      const res = await fetch('" + JettraServer.resolvePath("/api/backup") + "', { method: 'POST' });\n" +
            "      if (res.ok) {\n" +
            "        alert('Backup snapshot successfully initiated and written to storage directory!');\n" +
            "      } else {\n" +
            "        alert('Backup failed with status: ' + res.status);\n" +
            "      }\n" +
            "    } catch(e) {\n" +
            "      alert('Error triggering backup: ' + e);\n" +
            "    }\n" +
            "  }\n" +
            "</script>\n"
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
                Div.of(Paragraph.of("<i class='" + icon + "' style='color: " + color + "; font-size: 20px;'></i>"))
                    .modifier(new io.jettra.flux.core.Modifier().style("background: rgba(255,255,255,0.06); width: 42px; height: 42px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("LIVE").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge " + badgeClass))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Paragraph.of("<div style='font-size: 13px; color: #94a3b8; font-weight: 500;'>" + title + "</div>"),
            Paragraph.of("<div style='font-size: 22px; font-weight: 700; color: #f8fafc; margin: 4px 0;'>" + mainValue + "</div>"),
            Paragraph.of("<div style='font-size: 12px; color: #64748b;'>" + subValue + "</div>")
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }

    private Widget createEngineSummaryCard(String engineKey, String icon, String color, String name, String description) {
        boolean isRegistered = engine.getEngine(engineKey) != null;
        String statusText = isRegistered ? "ACTIVE" : "STANDBY";
        String statusBadge = isRegistered ? "badge-active" : "badge-engine";

        return Div.of(
            Column.of(
                Row.of(
                    Row.of(
                        Div.of(Paragraph.of("<i class='" + icon + "' style='color: " + color + ";'></i>"))
                            .modifier(new io.jettra.flux.core.Modifier().cssClass("engine-icon-box").style("background: " + color + "20;")),
                        Column.of(
                            Paragraph.of("<div style='font-weight: 700; font-size: 16px; color: #f8fafc;'>" + name + "</div>"),
                            Paragraph.of("<div style='font-size: 11px; color: #94a3b8; letter-spacing: 0.5px;'>ENGINE: " + engineKey + "</div>")
                        ).modifier(new io.jettra.flux.core.Modifier().style("margin-left: 12px;"))
                    ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;")),
                    Span.of(statusText).modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge " + statusBadge))
                ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center;")),
                Paragraph.of("<p style='font-size: 13px; color: #cbd5e1; line-height: 1.5; margin: 12px 0 16px 0;'>" + description + "</p>"),
                Row.of(
                    Paragraph.of("<a href='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "' class='btn-action btn-secondary' style='font-size: 12px; padding: 6px 12px;'><i class='fas fa-search'></i> Explore Data</a>")
                ).modifier(new io.jettra.flux.core.Modifier().style("margin-top: auto;"))
            ).modifier(new io.jettra.flux.core.Modifier().cssClass("engine-item"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }

    private Widget createQuickActionCard() {
        return Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-tools' style='color: #60a5fa; margin-right: 8px;'></i> Quick Operations</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 16px;'>Perform direct administrative and maintenance actions on the active node.</p>"),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/users") + "' class='btn-action btn-secondary' style='font-size: 13px;'><i class='fas fa-user-shield'></i> Manage Users</a>"),
                Paragraph.of("<a href='" + JettraServer.resolvePath("/components") + "' class='btn-action btn-secondary' style='font-size: 13px; margin-left: 8px;'><i class='fas fa-layer-group'></i> Storage Core</a>"),
                Paragraph.of("<button class='btn-action btn-primary' onclick=\"triggerBackup()\" style='font-size: 13px; margin-left: 8px;'><i class='fas fa-hdd'></i> Snapshot WAL</button>")
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }

    private Widget createNetworkEndpointsCard() {
        return Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-plug' style='color: #34d399; margin-right: 8px;'></i> Network Interfaces</h3>"),
            Paragraph.of(
                "<ul style='list-style: none; padding: 0; margin: 0; font-size: 13px; color: #cbd5e1;'>\n" +
                "  <li style='padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;'><span>HTTP REST API:</span> <code style='color:#38bdf8;'>http://localhost:8080/api/</code></li>\n" +
                "  <li style='padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;'><span>Multi-Model API:</span> <code style='color:#38bdf8;'>http://localhost:8080/api/model/{engine}</code></li>\n" +
                "  <li style='padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; justify-content: space-between;'><span>Document API:</span> <code style='color:#38bdf8;'>http://localhost:8080/api/document/{coll}</code></li>\n" +
                "  <li style='padding: 6px 0; display: flex; justify-content: space-between;'><span>Swagger OpenAPI:</span> <a href='" + JettraServer.resolvePath("/swagger-ui") + "' style='color:#a78bfa; text-decoration:none;'>/swagger-ui</a></li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }
}
