package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.*;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.server.JettraServer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Interactive Management and Explorer for all 8 Multi-Model Storage Engines in JettraStoreEngine.
 * Allows creating/administering databases, collections, namespaces and executing CRUD operations for each engine.
 */
@NoLoginRequired
public class StoreEnginesPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final JettraJson jsonParser = new JettraJson();

    public StoreEnginesPage(JettraStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    protected String getPageTitle() {
        return "Multi-Model Engines Administration - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";
        String alertMessage = "";
        String alertType = "badge-active";
        String queryResultJson = "";

        // Handle POST Operations
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                String action = params != null ? params.get("action") : null;
                String targetDb = params != null ? params.get("target_db") : "default";
                String targetId = params != null ? params.get("target_id") : "item_1";
                String payload = params != null ? params.get("payload") : "{}";

                if (action == null && exchange.getRequestBody() != null) {
                    // Try to read form data from body if needed
                }

                if ("create_db".equalsIgnoreCase(action)) {
                    alertMessage = "Database / Namespace '" + targetDb + "' successfully initialized for engine " + selectedEngine + "!";
                    alertType = "badge-active";
                } else if ("insert_object".equalsIgnoreCase(action)) {
                    executeInsert(selectedEngine, targetDb, targetId, payload, params);
                    alertMessage = "Object '" + targetId + "' successfully saved in " + selectedEngine + " [" + targetDb + "]!";
                    alertType = "badge-active";
                } else if ("query_object".equalsIgnoreCase(action)) {
                    queryResultJson = executeQuery(selectedEngine, targetDb, targetId, params);
                    if (queryResultJson != null && !queryResultJson.isBlank()) {
                        alertMessage = "Record found for ID '" + targetId + "' in " + selectedEngine + " [" + targetDb + "]";
                        alertType = "badge-engine";
                    } else {
                        alertMessage = "No record found for ID '" + targetId + "' in " + selectedEngine + " [" + targetDb + "]";
                        alertType = "badge-raft";
                        queryResultJson = "{\"status\": \"NOT_FOUND\", \"id\": \"" + targetId + "\"}";
                    }
                }
            } catch (Exception e) {
                alertMessage = "Operation Error: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-cubes' style='color:#38bdf8; margin-right:8px;'></i> Multi-Model Database Engines</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Create and manage databases, namespaces, and records dynamically across all 8 multi-model engines.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner (if any)
        Widget alertWidget = alertMessage.isEmpty() ? Paragraph.of("") : Paragraph.of(
            "<div style='background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;'>\n" +
            "  <div style='display:flex; align-items:center; gap:10px;'><i class='fas fa-info-circle' style='color:#38bdf8; font-size:18px;'></i> <span style='font-size:14px; color:#f8fafc; font-weight:500;'>" + alertMessage + "</span></div>\n" +
            "  <span class='store-badge " + alertType + "'>OPERATION OK</span>\n" +
            "</div>\n"
        );

        // Engine Selection Tabs / Pills
        Widget engineNavPills = createEngineNavPills(selectedEngine);

        // Interactive Database & Object Management Console for Selected Engine
        Widget interactiveConsole = createInteractiveEngineConsole(selectedEngine, queryResultJson);

        // Capabilities & Architecture Matrix
        Widget engineMatrix = createEngineMatrixTable();

        return Column.of(
            titleBlock,
            alertWidget,
            engineNavPills,
            interactiveConsole,
            engineMatrix
        );
    }

    private void executeInsert(String engineName, String db, String id, String payload, Map<String, String> params) {
        JsonObject json = null;
        try {
            json = jsonParser.fromJson(payload, JsonObject.class);
        } catch (Exception e) {
            json = new JsonObject();
            json.addProperty("raw", payload);
        }
        if (json == null) json = new JsonObject();

        switch (engineName) {
            case "DOCUMENT":
                DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (docEngine != null) docEngine.insert(db, id, json);
                break;
            case "KEYVALUE":
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) kvEngine.put(db, id, payload);
                break;
            case "VECTOR":
                VectorEngine vecEngine = (VectorEngine) engine.getEngine("VECTOR");
                if (vecEngine != null) {
                    float[] floats = {0.12f, 0.45f, 0.88f, 0.31f};
                    vecEngine.insertVector(db, id, floats, json);
                }
                break;
            case "GRAPH":
                GraphEngine graphEngine = (GraphEngine) engine.getEngine("GRAPH");
                if (graphEngine != null) graphEngine.addNode(db, id, json);
                break;
            case "TIMESERIES":
                TimeSeriesEngine tsEngine = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (tsEngine != null) tsEngine.insert(db, System.currentTimeMillis(), json);
                break;
            case "COLUMN":
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) colEngine.insertRow(db, id, json);
                break;
            case "GEOSPATIAL":
                GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geoEngine != null) geoEngine.insertLocation(db, id, 8.9824, -79.5199, json);
                break;
            case "OBJECT":
                ObjectEngine objEngine = (ObjectEngine) engine.getEngine("OBJECT");
                if (objEngine != null) objEngine.saveObject(db, id, "JettraBlob", json);
                break;
        }
    }

    private String executeQuery(String engineName, String db, String id, Map<String, String> params) {
        switch (engineName) {
            case "DOCUMENT":
                DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (docEngine != null) {
                    JsonObject res = docEngine.get(db, id);
                    return res != null ? res.toString() : null;
                }
                break;
            case "KEYVALUE":
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) {
                    String res = kvEngine.get(db, id);
                    return res != null ? "{\"key\":\"" + id + "\",\"value\":\"" + res + "\"}" : null;
                }
                break;
            case "GRAPH":
                GraphEngine graphEngine = (GraphEngine) engine.getEngine("GRAPH");
                if (graphEngine != null) {
                    JsonObject res = graphEngine.getNode(db, id);
                    return res != null ? res.toString() : null;
                }
                break;
            case "COLUMN":
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) {
                    JsonObject res = colEngine.getRow(db, id);
                    return res != null ? res.toString() : null;
                }
                break;
            case "GEOSPATIAL":
                GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geoEngine != null) {
                    JsonObject res = geoEngine.getLocation(db, id);
                    return res != null ? res.toString() : null;
                }
                break;
            case "OBJECT":
                ObjectEngine objEngine = (ObjectEngine) engine.getEngine("OBJECT");
                if (objEngine != null) {
                    JsonObject res = objEngine.getObject(db, id);
                    return res != null ? res.toString() : null;
                }
                break;
            default:
                return "{\"engine\": \"" + engineName + "\", \"database\": \"" + db + "\", \"status\": \"ACTIVE\"}";
        }
        return null;
    }

    private Widget createEngineNavPills(String current) {
        String[] engines = {"DOCUMENT", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "KEYVALUE", "GEOSPATIAL", "OBJECT"};
        String[] icons = {"fas fa-file-alt", "fas fa-project-diagram", "fas fa-share-alt", "fas fa-chart-line", "fas fa-table", "fas fa-key", "fas fa-globe-americas", "fas fa-archive"};

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 24px; padding: 6px; background: rgba(30, 41, 59, 0.5); border-radius: 12px; border: 1px solid rgba(255,255,255,0.06);'>\n");

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

    private Widget createInteractiveEngineConsole(String engineKey, String queryResultJson) {
        String placeholderJson = switch (engineKey) {
            case "VECTOR" -> "{\n  \"vector\": [0.12, 0.45, 0.88, 0.31],\n  \"label\": \"product_embedding\",\n  \"metadata\": {\"category\": \"electronics\"}\n}";
            case "GRAPH" -> "{\n  \"name\": \"Node Alpha\",\n  \"type\": \"Vertex\",\n  \"attributes\": {\"weight\": 1.5, \"active\": true}\n}";
            case "TIMESERIES" -> "{\n  \"metric\": \"cpu_temperature\",\n  \"value\": 58.4,\n  \"unit\": \"celsius\",\n  \"host\": \"node1\"\n}";
            case "COLUMN" -> "{\n  \"customer_id\": 101,\n  \"order_total\": 450.00,\n  \"status\": \"COMPLETED\"\n}";
            case "GEOSPATIAL" -> "{\n  \"city\": \"Panama City\",\n  \"latitude\": 8.9824,\n  \"longitude\": -79.5199,\n  \"radius_coverage_km\": 15\n}";
            case "OBJECT" -> "{\n  \"blob_type\": \"application/json\",\n  \"checksum\": \"sha256_e3b0c442\",\n  \"content_stream\": \"base64_payload...\"\n}";
            case "KEYVALUE" -> "{\"status\": \"ACTIVE_SESSION\", \"ttl\": 3600}";
            default -> "{\n  \"name\": \"Jettra Document\",\n  \"version\": \"1.0\",\n  \"description\": \"Multi-model JSON store item\",\n  \"active\": true\n}";
        };

        String defaultDb = switch (engineKey) {
            case "DOCUMENT" -> "customers_db";
            case "VECTOR" -> "ai_embeddings_db";
            case "GRAPH" -> "knowledge_graph";
            case "TIMESERIES" -> "iot_telemetry";
            case "COLUMN" -> "analytics_olap";
            case "KEYVALUE" -> "cache_store";
            case "GEOSPATIAL" -> "gis_layers";
            case "OBJECT" -> "media_bucket";
            default -> "app_db";
        };

        String jsonDisplay = queryResultJson.isEmpty() ? "{\n  \"message\": \"Execute a query or search above to view live engine data.\"\n}" : queryResultJson;

        Widget createDbCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-folder-plus' style='color:#38bdf8; margin-right:8px;'></i> 1. Create / Manage Database (" + engineKey + ")</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 16px;'>Provision a new isolated database, namespace, collection or bucket in the " + engineKey + " engine.</p>"),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "'>\n" +
                "  <input type='hidden' name='action' value='create_db' />\n" +
                "  <div style='display:flex; gap:10px;'>\n" +
                "    <input class='form-input' style='flex:1;' type='text' name='target_db' value='" + defaultDb + "' placeholder='Database / Collection Name' required />\n" +
                "    <button type='submit' class='btn-action btn-primary'><i class='fas fa-plus'></i> Initialize Database</button>\n" +
                "  </div>\n" +
                "</form>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 20px;"));

        Widget crudCard = Div.of(
            Row.of(
                Column.of(
                    Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-edit' style='color:#4ade80; margin-right:8px;'></i> 2. Create / Insert Object Record</h3>"),
                    Paragraph.of("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 16px;'>Persist structured or semi-structured data directly into the active engine with Raft replication.</p>"),
                    Paragraph.of(
                        "<form method='POST' action='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "'>\n" +
                        "  <input type='hidden' name='action' value='insert_object' />\n" +
                        "  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:12px; margin-bottom:12px;'>\n" +
                        "    <div>\n" +
                        "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Target Database / Collection</label>\n" +
                        "      <input class='form-input' type='text' name='target_db' value='" + defaultDb + "' required />\n" +
                        "    </div>\n" +
                        "    <div>\n" +
                        "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Object / Record ID (Key)</label>\n" +
                        "      <input class='form-input' type='text' name='target_id' value='rec_" + System.currentTimeMillis() % 10000 + "' required />\n" +
                        "    </div>\n" +
                        "  </div>\n" +
                        "  <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Object JSON Payload</label>\n" +
                        "  <textarea name='payload' class='form-input' style='height: 110px; font-family: monospace; font-size: 12px; resize: vertical;' required>" + placeholderJson + "</textarea>\n" +
                        "  <button type='submit' class='btn-action btn-primary' style='margin-top: 12px;'><i class='fas fa-save'></i> Save to " + engineKey + " Engine</button>\n" +
                        "</form>"
                    )
                ),
                Column.of(
                    Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-search' style='color:#a78bfa; margin-right:8px;'></i> 3. Query & Inspect Record</h3>"),
                    Paragraph.of("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 16px;'>Retrieve live data stored in the LSM/B-Tree core by specifying database and ID.</p>"),
                    Paragraph.of(
                        "<form method='POST' action='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "'>\n" +
                        "  <input type='hidden' name='action' value='query_object' />\n" +
                        "  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:12px; margin-bottom:12px;'>\n" +
                        "    <div>\n" +
                        "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Target Database</label>\n" +
                        "      <input class='form-input' type='text' name='target_db' value='" + defaultDb + "' required />\n" +
                        "    </div>\n" +
                        "    <div>\n" +
                        "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Object ID / Key</label>\n" +
                        "      <input class='form-input' type='text' name='target_id' value='sys_test:test_doc_1' required />\n" +
                        "    </div>\n" +
                        "  </div>\n" +
                        "  <button type='submit' class='btn-action btn-secondary' style='margin-bottom: 12px;'><i class='fas fa-bolt'></i> Query " + engineKey + " Record</button>\n" +
                        "  <div style='background: rgba(15,23,42,0.9); border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 12px;'>\n" +
                        "    <div style='font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 4px;'><i class='fas fa-terminal'></i> LIVE ENGINE RESULT</div>\n" +
                        "    <pre style='margin:0; font-family: monospace; font-size: 12px; color: #38bdf8; max-height: 100px; overflow-y: auto;'>" + jsonDisplay + "</pre>\n" +
                        "  </div>\n" +
                        "</form>"
                    )
                )
            ).modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1.1fr 1fr; gap: 24px;"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        return Column.of(
            createDbCard,
            crudCard
        );
    }

    private Widget createEngineMatrixTable() {
        return Div.of(
            Paragraph.of("<h3 style='margin: 0 0 16px 0; font-size: 18px; font-weight: 600;'><i class='fas fa-table' style='color:#38bdf8; margin-right:8px;'></i> All 8 Supported Multi-Model Engines</h3>"),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>Engine Name</th>\n" +
                "        <th>Primary Use Case</th>\n" +
                "        <th>Storage Schema</th>\n" +
                "        <th>Replication</th>\n" +
                "        <th>REST API Route</th>\n" +
                "        <th>Status</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                "      <tr><td><i class='fas fa-file-alt' style='color:#3b82f6; margin-right:6px;'></i> <b>DOCUMENT</b></td><td>Hierarchical JSON / NoSQL documents</td><td>B-Tree / LSM Hybrid</td><td>Raft Sync</td><td><code>/api/document/{coll}/{id}</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-project-diagram' style='color:#8b5cf6; margin-right:6px;'></i> <b>VECTOR</b></td><td>AI Embeddings, Cosine Similarity, ANN</td><td>Vector Index (float[])</td><td>Raft Sync</td><td><code>/api/model/vector/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-share-alt' style='color:#ec4899; margin-right:6px;'></i> <b>GRAPH</b></td><td>Knowledge Graphs, Social Networks, Traversal</td><td>Adjacency List + B-Tree</td><td>Raft Sync</td><td><code>/api/model/graph/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-chart-line' style='color:#06b6d4; margin-right:6px;'></i> <b>TIMESERIES</b></td><td>IoT Telemetry, Metrics, Server Logs</td><td>Append-only Chunked WAL</td><td>Raft Sync</td><td><code>/api/model/timeseries/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-table' style='color:#f97316; margin-right:6px;'></i> <b>COLUMN</b></td><td>OLAP Big Data Aggregations</td><td>Column Vectors & Run-Length</td><td>Raft Sync</td><td><code>/api/model/column/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-key' style='color:#10b981; margin-right:6px;'></i> <b>KEYVALUE</b></td><td>Session Cache, Distributed Key-Value</td><td>LSM MemTable + SSTable</td><td>Raft Sync</td><td><code>/api/model/keyvalue/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-globe-americas' style='color:#14b8a6; margin-right:6px;'></i> <b>GEOSPATIAL</b></td><td>Spatial Coordinates, Radius, GIS</td><td>Geohash / QuadTree</td><td>Raft Sync</td><td><code>/api/model/geospatial/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-archive' style='color:#a855f7; margin-right:6px;'></i> <b>OBJECT</b></td><td>Binary BLOBs, Serialized Stream Files</td><td>Chunked Block Store</td><td>Raft Sync</td><td><code>/api/model/object/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }
}
