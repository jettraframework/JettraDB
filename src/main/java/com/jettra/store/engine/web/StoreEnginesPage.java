package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.util.Map;

/**
 * Explorer and management interface for the 8 Multi-Model Storage Engines in JettraStoreEngine.
 */
@NoLoginRequired
public class StoreEnginesPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;

    public StoreEnginesPage(JettraStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    protected String getPageTitle() {
        return "Multi-Model Engines - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-cubes' style='color:#38bdf8; margin-right:8px;'></i> Multi-Model Database Engines</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Explore, inspect and interact with the 8 multi-model database storage engines natively hosted in JettraStoreEngine.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Engine Selection Tabs / Pills
        Widget engineNavPills = createEngineNavPills(selectedEngine);

        // Detail View for the Selected Engine
        Widget engineDetail = createEngineDetailView(selectedEngine);

        // Engine Comparison Matrix
        Widget engineMatrix = createEngineMatrixTable();

        return Column.of(
            titleBlock,
            engineNavPills,
            engineDetail,
            engineMatrix
        );
    }

    private Widget createEngineNavPills(String current) {
        String[] engines = {"DOCUMENT", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "KEYVALUE", "GEOSPATIAL", "OBJECT"};
        String[] icons = {"fas fa-file-alt", "fas fa-project-diagram", "fas fa-share-alt", "fas fa-chart-line", "fas fa-table", "fas fa-key", "fas fa-globe-americas", "fas fa-archive"};

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 24px; padding: 4px; background: rgba(30, 41, 59, 0.5); border-radius: 12px; border: 1px solid rgba(255,255,255,0.06);'>\n");

        for (int i = 0; i < engines.length; i++) {
            String eng = engines[i];
            String icon = icons[i];
            boolean active = eng.equalsIgnoreCase(current);
            String bg = active ? "background: #3b82f6; color: #ffffff; font-weight: 600; box-shadow: 0 0 12px rgba(59,130,246,0.4);" : "background: transparent; color: #94a3b8;";
            sb.append("<a href='").append(JettraServer.resolvePath("/engines?engine=" + eng))
              .append("' style='display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 8px; text-decoration: none; font-size: 13px; transition: all 0.2s; ").append(bg).append("'>")
              .append("<i class='").append(icon).append("'></i> ").append(eng)
              .append("</a>\n");
        }
        sb.append("</div>\n");

        return Paragraph.of(sb.toString());
    }

    private Widget createEngineDetailView(String engineKey) {
        switch (engineKey) {
            case "VECTOR":
                return buildEnginePanel(
                    "VECTOR", "fas fa-project-diagram", "#8b5cf6", "Vector Embeddings Engine",
                    "Dense vector storage and Approximate Nearest Neighbor (ANN) search with Cosine distance indexing.",
                    "JSON Vector Array (float[] / double[]) with arbitrary metadata payload",
                    "/api/model/vector/query",
                    "POST /api/model/vector/insert",
                    "1. Stores multi-dimensional embeddings (e.g. 768d, 1536d OpenAI / Gemini vectors).\n2. Computes Cosine Similarity and L2 Euclidean Distance.\n3. Embedded in-memory index + LSM B-Tree persistence."
                );
            case "GRAPH":
                return buildEnginePanel(
                    "GRAPH", "fas fa-share-alt", "#ec4899", "Graph & Network Engine",
                    "Property graph storage engine for nodes, edges, properties, and deep adjacency traversal.",
                    "Vertices {id, label, properties} and Edges {from, to, weight, relation}",
                    "/api/model/graph/traverse",
                    "POST /api/model/graph/edge",
                    "1. Bidirectional adjacency lists indexed in LSM B-Tree.\n2. Breadth-First and Depth-First relationship traversals.\n3. Cycle detection and weighted path calculations."
                );
            case "TIMESERIES":
                return buildEnginePanel(
                    "TIMESERIES", "fas fa-chart-line", "#06b6d4", "Time Series Telemetry Engine",
                    "Chronological time-series metric engine for IoT, system telemetry, downsampling, and range scans.",
                    "Metric {metricName, timestampEpochMs, valueFloat, tagsMap}",
                    "/api/model/timeseries/range",
                    "POST /api/model/timeseries/point",
                    "1. High-throughput append-only WAL writes.\n2. Timestamp delta-of-delta encoding and fast range queries.\n3. Aggregations: AVG, MIN, MAX, SUM, COUNT per interval."
                );
            case "COLUMN":
                return buildEnginePanel(
                    "COLUMN", "fas fa-table", "#f97316", "Columnar OLAP Engine",
                    "Column-oriented relational storage for analytical aggregations and vectorized column scans.",
                    "Columns {columnName, columnType, compressedVector}",
                    "/api/model/column/scan",
                    "POST /api/model/column/row",
                    "1. Efficient column projection avoiding disk reads of unused attributes.\n2. Run-length compression for high compressibility.\n3. Analytical query engine optimized for aggregates."
                );
            case "KEYVALUE":
                return buildEnginePanel(
                    "KEYVALUE", "fas fa-key", "#10b981", "Key-Value Store Engine",
                    "Low-latency key-value store optimized for high-throughput single key lookups and binary payload storage.",
                    "Key: String | Value: byte[] / String / JSON",
                    "/api/model/keyvalue/get?key=sample_key",
                    "POST /api/model/keyvalue/put",
                    "1. Direct in-memory LSM MemTable lookup + B-Tree secondary indexing.\n2. Atomic CAS (Compare-And-Swap) operations.\n3. Fast zero-copy memory reads."
                );
            case "GEOSPATIAL":
                return buildEnginePanel(
                    "GEOSPATIAL", "fas fa-globe-americas", "#14b8a6", "Geospatial GIS Engine",
                    "2D Geographical coordinates index with bounding box queries and Haversine radius distance calculations.",
                    "GeoPoint {id, latitude, longitude, metadata}",
                    "/api/model/geospatial/radius?lat=8.98&lon=-79.51&radiusKm=10",
                    "POST /api/model/geospatial/point",
                    "1. Geohash and QuadTree space-filling curve spatial indexing.\n2. Great-circle Haversine distance computations.\n3. Polygon containment and proximity filtering."
                );
            case "OBJECT":
                return buildEnginePanel(
                    "OBJECT", "fas fa-archive", "#a855f7", "Object & Binary Blob Engine",
                    "Serialized Java objects, streaming files, and binary payload storage with chunking.",
                    "ObjectKey: String | Binary Payload: byte[]",
                    "/api/model/object/get?key=sample_obj",
                    "POST /api/model/object/put",
                    "1. Chunked streaming storage for large payloads (>64KB).\n2. Java Object serialization and deserialization validation.\n3. Content checksumming and deduplication."
                );
            case "DOCUMENT":
            default:
                return buildDocumentEnginePanel();
        }
    }

    private Widget buildDocumentEnginePanel() {
        DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
        io.jettra.json.JsonObject sampleDoc = docEngine != null ? docEngine.get("sys_test", "test_doc_1") : null;
        String sampleJson = sampleDoc != null ? sampleDoc.toString() : "{\"name\": \"Jettra Engine\", \"version\": \"1.0\", \"status\": \"Active\"}";

        return Div.of(
            Row.of(
                Column.of(
                    Row.of(
                        Div.of(Paragraph.of("<i class='fas fa-file-alt' style='color: #3b82f6;'></i>"))
                            .modifier(new io.jettra.flux.core.Modifier().cssClass("engine-icon-box").style("background: rgba(59,130,246,0.15);")),
                        Column.of(
                            Paragraph.of("<h2 style='margin: 0; font-size: 20px; font-weight: 700; color: #f8fafc;'>Document Engine (JSON/BSON)</h2>"),
                            Paragraph.of("<div style='font-size: 12px; color: #94a3b8;'>Engine Type: DOCUMENT | Replication: Raft Synchronous</div>")
                        ).modifier(new io.jettra.flux.core.Modifier().style("margin-left: 12px;"))
                    ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;")),
                    Paragraph.of("<p style='color: #cbd5e1; font-size: 14px; line-height: 1.6; margin: 16px 0 20px 0;'>The Document Engine is the primary general-purpose NoSQL model in JettraStoreEngine. It stores unstructured and semi-structured JSON documents with schema validation via JettraRules annotations, automatic primary indexing, and cluster-wide Raft replication.</p>")
                ),
                Column.of(
                    Paragraph.of(
                        "<div style='background: rgba(15,23,42,0.8); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; padding: 16px;'>\n" +
                        "  <div style='font-size: 12px; font-weight: 600; color: #94a3b8; margin-bottom: 8px;'><i class='fas fa-code'></i> SAMPLE DOCUMENT (sys_test:test_doc_1)</div>\n" +
                        "  <pre style='margin:0; font-family: monospace; font-size: 13px; color: #38bdf8; overflow-x: auto;'>" + sampleJson + "</pre>\n" +
                        "</div>"
                    )
                )
            ).modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1.2fr 1fr; gap: 24px;")),
            Paragraph.of(
                "<div style='margin-top: 20px; padding-top: 16px; border-top: 1px solid rgba(255,255,255,0.08); display: flex; gap: 12px; align-items: center;'>\n" +
                "  <span style='font-size: 13px; color: #94a3b8;'>REST API Endpoint:</span>\n" +
                "  <code style='background: rgba(59,130,246,0.15); color: #60a5fa; padding: 4px 8px; border-radius: 6px; font-size: 12px;'>GET /api/document/{collection}/{id}</code>\n" +
                "  <code style='background: rgba(34,197,94,0.15); color: #4ade80; padding: 4px 8px; border-radius: 6px; font-size: 12px;'>POST /api/document/{collection}/{id}</code>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));
    }

    private Widget buildEnginePanel(String key, String icon, String color, String title, String desc, String format, String readApi, String writeApi, String features) {
        return Div.of(
            Row.of(
                Column.of(
                    Row.of(
                        Div.of(Paragraph.of("<i class='" + icon + "' style='color: " + color + ";'></i>"))
                            .modifier(new io.jettra.flux.core.Modifier().cssClass("engine-icon-box").style("background: " + color + "20;")),
                        Column.of(
                            Paragraph.of("<h2 style='margin: 0; font-size: 20px; font-weight: 700; color: #f8fafc;'>" + title + "</h2>"),
                            Paragraph.of("<div style='font-size: 12px; color: #94a3b8;'>Engine Type: " + key + " | Replication: Raft Consensus</div>")
                        ).modifier(new io.jettra.flux.core.Modifier().style("margin-left: 12px;"))
                    ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;")),
                    Paragraph.of("<p style='color: #cbd5e1; font-size: 14px; line-height: 1.6; margin: 16px 0;'>" + desc + "</p>"),
                    Paragraph.of("<div style='font-size: 13px; color: #94a3b8; font-weight: 600; margin-bottom: 6px;'>Key Features:</div>"),
                    Paragraph.of("<pre style='margin:0 0 16px 0; font-family: inherit; font-size: 13px; color: #e2e8f0; line-height: 1.5; white-space: pre-wrap;'>" + features + "</pre>")
                ),
                Column.of(
                    Paragraph.of(
                        "<div style='background: rgba(15,23,42,0.8); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; padding: 16px;'>\n" +
                        "  <div style='font-size: 12px; font-weight: 600; color: #94a3b8; margin-bottom: 8px;'><i class='fas fa-info-circle'></i> STORAGE DATA SCHEMA</div>\n" +
                        "  <div style='font-size: 13px; color: #38bdf8; margin-bottom: 12px;'>" + format + "</div>\n" +
                        "  <div style='font-size: 12px; font-weight: 600; color: #94a3b8; margin-bottom: 6px;'><i class='fas fa-plug'></i> API ROUTES</div>\n" +
                        "  <div style='font-size: 12px; font-family: monospace; color: #a78bfa; margin-bottom: 4px;'>" + readApi + "</div>\n" +
                        "  <div style='font-size: 12px; font-family: monospace; color: #4ade80;'>" + writeApi + "</div>\n" +
                        "</div>"
                    )
                )
            ).modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1.2fr 1fr; gap: 24px;")),
            Paragraph.of(
                "<div style='margin-top: 20px; padding-top: 16px; border-top: 1px solid rgba(255,255,255,0.08); display: flex; gap: 12px; align-items: center;'>\n" +
                "  <span style='font-size: 13px; color: #94a3b8;'>Universal Multi-Model Endpoint:</span>\n" +
                "  <code style='background: rgba(59,130,246,0.15); color: #60a5fa; padding: 4px 8px; border-radius: 6px; font-size: 12px;'>GET /api/model/" + key.toLowerCase() + "/*</code>\n" +
                "  <code style='background: rgba(34,197,94,0.15); color: #4ade80; padding: 4px 8px; border-radius: 6px; font-size: 12px;'>POST /api/model/" + key.toLowerCase() + "/*</code>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));
    }

    private Widget createEngineMatrixTable() {
        return Div.of(
            Paragraph.of("<h3 style='margin: 0 0 16px 0; font-size: 18px; font-weight: 600;'><i class='fas fa-table' style='color:#38bdf8; margin-right:8px;'></i> All 8 Engine Multi-Model Capabilities</h3>"),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>Engine Name</th>\n" +
                "        <th>Primary Use Case</th>\n" +
                "        <th>Indexing Structure</th>\n" +
                "        <th>Replication</th>\n" +
                "        <th>Status</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                "      <tr><td><i class='fas fa-file-alt' style='color:#3b82f6; margin-right:6px;'></i> <b>DOCUMENT</b></td><td>Hierarchical JSON / NoSQL documents</td><td>B-Tree / LSM Hybrid</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-project-diagram' style='color:#8b5cf6; margin-right:6px;'></i> <b>VECTOR</b></td><td>AI Embeddings, Cosine Similarity, ANN</td><td>Vector Index (HNSW/Flat)</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-share-alt' style='color:#ec4899; margin-right:6px;'></i> <b>GRAPH</b></td><td>Knowledge Graphs, Social Networks, Traversal</td><td>Adjacency List + B-Tree</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-chart-line' style='color:#06b6d4; margin-right:6px;'></i> <b>TIMESERIES</b></td><td>IoT Telemetry, Metrics, Server Logs</td><td>Append-only Chunked WAL</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-table' style='color:#f97316; margin-right:6px;'></i> <b>COLUMN</b></td><td>OLAP Big Data Aggregations</td><td>Column Vectors & Run-Length</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-key' style='color:#10b981; margin-right:6px;'></i> <b>KEYVALUE</b></td><td>Session Cache, Distributed Key-Value</td><td>LSM MemTable + SSTable</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-globe-americas' style='color:#14b8a6; margin-right:6px;'></i> <b>GEOSPATIAL</b></td><td>Spatial Coordinates, Radius, GIS</td><td>Geohash / QuadTree</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-archive' style='color:#a855f7; margin-right:6px;'></i> <b>OBJECT</b></td><td>Binary BLOBs, Serialized Stream Files</td><td>Chunked Block Store</td><td>Raft Sync</td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }
}
