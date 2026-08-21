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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Interactive Type-Specific Database and Object Administrator for all 8 Multi-Model Storage Engines in JettraStoreEngine.
 * Provides specialized management interfaces (not just generic JSON) for Document, KeyValue, Vector, Graph,
 * TimeSeries, Column, Geospatial, and Object engines, with full CRUD, search, inspection and deletion.
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
        return "Multi-Model Engines & Object Administrator - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";
        String alertMessage = "";
        String alertType = "badge-active";
        String queryResultDisplay = "";
        String targetDb = params != null && params.containsKey("target_db") ? params.get("target_db") : getDefaultDbForEngine(selectedEngine);

        // Handle POST Operations
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                String action = params != null ? params.get("action") : null;
                String targetId = params != null ? params.get("target_id") : "";

                if ("create_db".equalsIgnoreCase(action)) {
                    alertMessage = "Database / Namespace '" + targetDb + "' successfully initialized for " + selectedEngine + " engine!";
                    alertType = "badge-active";
                } else if ("insert_object".equalsIgnoreCase(action)) {
                    executeTypeSpecificInsert(selectedEngine, targetDb, params);
                    alertMessage = "Object successfully created and persisted in " + selectedEngine + " [" + targetDb + "]!";
                    alertType = "badge-active";
                } else if ("query_object".equalsIgnoreCase(action)) {
                    queryResultDisplay = executeTypeSpecificQuery(selectedEngine, targetDb, targetId, params);
                    if (queryResultDisplay != null && !queryResultDisplay.isBlank()) {
                        alertMessage = "Record found for ID '" + targetId + "' in " + selectedEngine + " [" + targetDb + "]";
                        alertType = "badge-engine";
                    } else {
                        alertMessage = "No record found for ID '" + targetId + "' in " + selectedEngine + " [" + targetDb + "]";
                        alertType = "badge-raft";
                        queryResultDisplay = "{\"status\": \"NOT_FOUND\", \"id\": \"" + targetId + "\"}";
                    }
                } else if ("search_vector".equalsIgnoreCase(action)) {
                    queryResultDisplay = executeVectorSearch(targetDb, params);
                    alertMessage = "Vector similarity search executed successfully on [" + targetDb + "]!";
                    alertType = "badge-engine";
                } else if ("calc_distance".equalsIgnoreCase(action)) {
                    queryResultDisplay = executeGeoDistance(params);
                    alertMessage = "Geospatial distance calculated successfully!";
                    alertType = "badge-engine";
                } else if ("delete_object".equalsIgnoreCase(action)) {
                    executeTypeSpecificDelete(selectedEngine, targetDb, targetId, params);
                    alertMessage = "Object '" + targetId + "' successfully deleted from " + selectedEngine + " [" + targetDb + "]!";
                    alertType = "badge-raft";
                }
            } catch (Exception e) {
                alertMessage = "Operation Error: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-database' style='color:#38bdf8; margin-right:8px;'></i> Multi-Model Database & Objects Administrator</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Administer databases and manage native typed objects across all 8 multi-model engines with specialized controls.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner (if any)
        Widget alertWidget = alertMessage.isEmpty() ? Paragraph.of("") : Paragraph.of(
            "<div style='background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;'>\n" +
            "  <div style='display:flex; align-items:center; gap:10px;'><i class='fas fa-info-circle' style='color:#38bdf8; font-size:18px;'></i> <span style='font-size:14px; color:#f8fafc; font-weight:500;'>" + alertMessage + "</span></div>\n" +
            "  <span class='store-badge " + alertType + "'>STATUS</span>\n" +
            "</div>\n"
        );

        // Engine Selection Tabs / Pills
        Widget engineNavPills = createEngineNavPills(selectedEngine);

        // Database Provisioning Bar
        Widget dbProvisionBar = createDatabaseProvisionBar(selectedEngine, targetDb);

        // Engine-Specific Object Creation & Management Card
        Widget typeSpecificCrudCard = createTypeSpecificCrudCard(selectedEngine, targetDb, queryResultDisplay);

        // Live Objects Explorer for active Engine & Database
        Widget liveObjectsExplorer = createLiveObjectsExplorer(selectedEngine, targetDb);

        // Capabilities & Architecture Matrix
        Widget engineMatrix = createEngineMatrixTable();

        return Column.of(
            titleBlock,
            alertWidget,
            engineNavPills,
            dbProvisionBar,
            typeSpecificCrudCard,
            liveObjectsExplorer,
            engineMatrix
        );
    }

    private String getDefaultDbForEngine(String engineKey) {
        return switch (engineKey) {
            case "DOCUMENT" -> "customers_db";
            case "KEYVALUE" -> "session_cache";
            case "VECTOR" -> "ai_embeddings_db";
            case "GRAPH" -> "knowledge_graph";
            case "TIMESERIES" -> "iot_telemetry";
            case "COLUMN" -> "analytics_olap";
            case "GEOSPATIAL" -> "gis_layers";
            case "OBJECT" -> "media_bucket";
            default -> "app_db";
        };
    }

    private void executeTypeSpecificInsert(String engineName, String db, Map<String, String> params) {
        String targetId = params.getOrDefault("target_id", "rec_" + (System.currentTimeMillis() % 10000));
        
        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (docEngine != null) {
                    String jsonPayload = params.getOrDefault("doc_payload", "{}");
                    JsonObject doc = parseJsonOrWrap(jsonPayload);
                    String docClass = params.get("doc_class");
                    if (docClass != null && !docClass.isBlank()) {
                        doc.addProperty("_class", docClass.trim());
                    }
                    docEngine.insert(db, targetId, doc);
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) {
                    String value = params.getOrDefault("kv_value", "");
                    String valType = params.getOrDefault("kv_type", "string");
                    String finalVal = "json".equalsIgnoreCase(valType) ? value : value;
                    kvEngine.put(db, targetId, finalVal);
                }
            }
            case "VECTOR" -> {
                VectorEngine vecEngine = (VectorEngine) engine.getEngine("VECTOR");
                if (vecEngine != null) {
                    String rawVec = params.getOrDefault("vector_coords", "0.12, 0.45, 0.88, 0.31");
                    float[] floats = parseFloats(rawVec);
                    String metaStr = params.getOrDefault("vector_meta", "{}");
                    JsonObject meta = parseJsonOrWrap(metaStr);
                    String label = params.get("vector_label");
                    if (label != null && !label.isBlank()) meta.addProperty("label", label);
                    vecEngine.insertVector(db, targetId, floats, meta);
                }
            }
            case "GRAPH" -> {
                GraphEngine graphEngine = (GraphEngine) engine.getEngine("GRAPH");
                if (graphEngine != null) {
                    String graphMode = params.getOrDefault("graph_mode", "node");
                    if ("edge".equalsIgnoreCase(graphMode)) {
                        String from = params.getOrDefault("edge_from", "node_1");
                        String to = params.getOrDefault("edge_to", "node_2");
                        String label = params.getOrDefault("edge_label", "CONNECTED_TO");
                        String edgeProps = params.getOrDefault("edge_props", "{}");
                        graphEngine.addEdge(db, from, to, label, parseJsonOrWrap(edgeProps));
                    } else {
                        String nodeLabel = params.getOrDefault("node_label", "Vertex");
                        String nodeProps = params.getOrDefault("node_props", "{}");
                        JsonObject data = parseJsonOrWrap(nodeProps);
                        data.addProperty("label", nodeLabel);
                        graphEngine.addNode(db, targetId, data);
                    }
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine tsEngine = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (tsEngine != null) {
                    String rawTs = params.get("ts_timestamp");
                    long timestamp = (rawTs != null && !rawTs.isBlank()) ? Long.parseLong(rawTs.trim()) : System.currentTimeMillis();
                    double val = Double.parseDouble(params.getOrDefault("ts_value", "0.0"));
                    String unit = params.getOrDefault("ts_unit", "");
                    String tags = params.getOrDefault("ts_tags", "{}");
                    JsonObject dp = parseJsonOrWrap(tags);
                    dp.addProperty("value", val);
                    if (!unit.isBlank()) dp.addProperty("unit", unit);
                    tsEngine.insert(db, timestamp, dp);
                }
            }
            case "COLUMN" -> {
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) {
                    String colData = params.getOrDefault("col_data", "{}");
                    JsonObject row = parseJsonOrColumns(colData);
                    colEngine.insertRow(db, targetId, row);
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geoEngine != null) {
                    double lat = Double.parseDouble(params.getOrDefault("geo_lat", "8.9824"));
                    double lon = Double.parseDouble(params.getOrDefault("geo_lon", "-79.5199"));
                    String metaStr = params.getOrDefault("geo_meta", "{}");
                    JsonObject meta = parseJsonOrWrap(metaStr);
                    String name = params.get("geo_name");
                    if (name != null && !name.isBlank()) meta.addProperty("name", name);
                    geoEngine.insertLocation(db, targetId, lat, lon, meta);
                }
            }
            case "OBJECT" -> {
                ObjectEngine objEngine = (ObjectEngine) engine.getEngine("OBJECT");
                if (objEngine != null) {
                    String className = params.getOrDefault("obj_class", "GenericBlob");
                    String payload = params.getOrDefault("obj_payload", "");
                    String mime = params.getOrDefault("obj_mime", "application/octet-stream");
                    JsonObject state = new JsonObject();
                    state.addProperty("mimeType", mime);
                    state.addProperty("sizeBytes", payload.getBytes(StandardCharsets.UTF_8).length);
                    state.addProperty("content", payload);
                    objEngine.saveObject(db, targetId, className, state);
                }
            }
        }
    }

    private String executeTypeSpecificQuery(String engineName, String db, String id, Map<String, String> params) {
        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (docEngine != null) {
                    JsonObject res = docEngine.get(db, id);
                    return res != null ? res.toString() : null;
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) {
                    String res = kvEngine.get(db, id);
                    return res != null ? "{\"key\": \"" + id + "\", \"value\": \"" + res + "\", \"type\": \"KEYVALUE\"}" : null;
                }
            }
            case "VECTOR" -> {
                VectorEngine vecEngine = (VectorEngine) engine.getEngine("VECTOR");
                if (vecEngine != null) {
                    JsonObject res = vecEngine.getVector(db, id);
                    return res != null ? res.toString() : null;
                }
            }
            case "GRAPH" -> {
                GraphEngine graphEngine = (GraphEngine) engine.getEngine("GRAPH");
                if (graphEngine != null) {
                    JsonObject res = graphEngine.getNode(db, id);
                    return res != null ? res.toString() : null;
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine tsEngine = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (tsEngine != null) {
                    long ts = 0;
                    try { ts = Long.parseLong(id); } catch (Exception ignored) {}
                    JsonObject res = tsEngine.get(db, ts);
                    return res != null ? res.toString() : null;
                }
            }
            case "COLUMN" -> {
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) {
                    JsonObject res = colEngine.getRow(db, id);
                    return res != null ? res.toString() : null;
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geoEngine != null) {
                    JsonObject res = geoEngine.getLocation(db, id);
                    return res != null ? res.toString() : null;
                }
            }
            case "OBJECT" -> {
                ObjectEngine objEngine = (ObjectEngine) engine.getEngine("OBJECT");
                if (objEngine != null) {
                    JsonObject res = objEngine.getObject(db, id);
                    return res != null ? res.toString() : null;
                }
            }
        }
        return null;
    }

    private String executeVectorSearch(String db, Map<String, String> params) {
        VectorEngine vecEngine = (VectorEngine) engine.getEngine("VECTOR");
        if (vecEngine != null) {
            String queryCoords = params.getOrDefault("query_vector", "0.10, 0.44, 0.85, 0.30");
            int topK = Integer.parseInt(params.getOrDefault("top_k", "5"));
            float[] queryVec = parseFloats(queryCoords);
            List<JsonObject> results = vecEngine.searchVector(db, queryVec, topK);
            return jsonParser.toJson(results);
        }
        return "[]";
    }

    private String executeGeoDistance(Map<String, String> params) {
        GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
        if (geoEngine != null) {
            double lat1 = Double.parseDouble(params.getOrDefault("dist_lat1", "8.9824"));
            double lon1 = Double.parseDouble(params.getOrDefault("dist_lon1", "-79.5199"));
            double lat2 = Double.parseDouble(params.getOrDefault("dist_lat2", "8.9745"));
            double lon2 = Double.parseDouble(params.getOrDefault("dist_lon2", "-79.5532"));
            double distanceKm = geoEngine.calculateDistance(lat1, lon1, lat2, lon2);
            JsonObject res = new JsonObject();
            res.addProperty("point1", lat1 + ", " + lon1);
            res.addProperty("point2", lat2 + ", " + lon2);
            res.addProperty("distanceKm", Math.round(distanceKm * 1000.0) / 1000.0);
            res.addProperty("distanceMiles", Math.round((distanceKm * 0.621371) * 1000.0) / 1000.0);
            return res.toString();
        }
        return "{}";
    }

    private void executeTypeSpecificDelete(String engineName, String db, String id, Map<String, String> params) {
        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) de.delete(db, id);
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) ke.delete(db, id);
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) ve.deleteVector(db, id);
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) ge.deleteNode(db, id);
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    try { te.delete(db, Long.parseLong(id)); } catch (Exception ignored) {}
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) ce.deleteRow(db, id);
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (ge != null) ge.deleteLocation(db, id);
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) oe.deleteObject(db, id);
            }
        }
    }

    private float[] parseFloats(String raw) {
        if (raw == null || raw.isBlank()) return new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        String clean = raw.replaceAll("[\\[\\]]", "");
        String[] parts = clean.split("[,\\s]+");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                arr[i] = Float.parseFloat(parts[i].trim());
            } catch (Exception e) {
                arr[i] = 0.0f;
            }
        }
        return arr;
    }

    private JsonObject parseJsonOrWrap(String payload) {
        if (payload == null || payload.isBlank()) return new JsonObject();
        try {
            JsonObject obj = jsonParser.fromJson(payload, JsonObject.class);
            return obj != null ? obj : new JsonObject();
        } catch (Exception e) {
            JsonObject wrap = new JsonObject();
            wrap.addProperty("raw", payload);
            return wrap;
        }
    }

    private JsonObject parseJsonOrColumns(String colData) {
        if (colData == null || colData.isBlank()) return new JsonObject();
        try {
            JsonObject obj = jsonParser.fromJson(colData, JsonObject.class);
            if (obj != null) return obj;
        } catch (Exception ignored) {}
        JsonObject obj = new JsonObject();
        String[] pairs = colData.split("[,;\\n]+");
        for (String pair : pairs) {
            String[] kv = pair.split("[:=]", 2);
            if (kv.length == 2) {
                obj.addProperty(kv[0].trim(), kv[1].trim());
            }
        }
        return obj;
    }

    private Widget createEngineNavPills(String current) {
        String[] engines = {"DOCUMENT", "KEYVALUE", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT"};
        String[] icons = {"fas fa-file-alt", "fas fa-key", "fas fa-project-diagram", "fas fa-share-alt", "fas fa-chart-line", "fas fa-table", "fas fa-globe-americas", "fas fa-archive"};
        String[] types = {"NoSQL JSON", "KV Cache", "AI Vector ANN", "LPG Graph", "IoT Telemetry", "OLAP Columns", "2D GIS Spatial", "Binary BLOB"};

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; padding: 6px; background: rgba(30, 41, 59, 0.5); border-radius: 12px; border: 1px solid rgba(255,255,255,0.06);'>\n");

        for (int i = 0; i < engines.length; i++) {
            String eng = engines[i];
            String icon = icons[i];
            String typeBadge = types[i];
            boolean active = eng.equalsIgnoreCase(current);
            String bg = active ? "background: #3b82f6; color: #ffffff; font-weight: 600; box-shadow: 0 0 12px rgba(59,130,246,0.4);" : "background: transparent; color: #94a3b8;";
            sb.append("<a href='").append(JettraServer.resolvePath("/engines?engine=" + eng))
              .append("' style='display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 8px; text-decoration: none; font-size: 13px; transition: all 0.2s; ").append(bg).append("'>")
              .append("<i class='").append(icon).append("'></i> <span>").append(eng).append("</span>")
              .append("<span style='font-size:10px; opacity:0.8; background:rgba(0,0,0,0.2); padding:2px 6px; border-radius:4px;'>").append(typeBadge).append("</span>")
              .append("</a>\n");
        }
        sb.append("</div>\n");

        return Paragraph.of(sb.toString());
    }

    private Widget createDatabaseProvisionBar(String engineKey, String currentDb) {
        String dbLabel = switch (engineKey) {
            case "DOCUMENT" -> "Database / Collection";
            case "KEYVALUE" -> "Namespace / Bucket";
            case "VECTOR" -> "Vector Index / Collection";
            case "GRAPH" -> "Graph Space";
            case "TIMESERIES" -> "Measurement / Feed";
            case "COLUMN" -> "Column Family / Table";
            case "GEOSPATIAL" -> "GIS Layer / Collection";
            case "OBJECT" -> "Storage Bucket";
            default -> "Database";
        };

        return Div.of(
            Row.of(
                Column.of(
                    Paragraph.of("<div style='font-size:14px; font-weight:600; color:#f8fafc;'><i class='fas fa-folder-open' style='color:#38bdf8; margin-right:6px;'></i> Active " + dbLabel + ": <span style='color:#38bdf8;'>" + currentDb + "</span></div>"),
                    Paragraph.of("<div style='font-size:12px; color:#94a3b8;'>Engine Type: <b>" + engineKey + "</b> (Raft Synchronized & LSM-Tree Indexed)</div>")
                ),
                Paragraph.of(
                    "<form method='POST' action='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "' style='display:flex; gap:8px; margin:0;'>\n" +
                    "  <input type='hidden' name='action' value='create_db' />\n" +
                    "  <input class='form-input' style='width:240px; padding:6px 12px; font-size:13px;' type='text' name='target_db' value='" + currentDb + "' placeholder='Database / Namespace Name' required />\n" +
                    "  <button type='submit' class='btn-action btn-primary' style='padding:6px 14px; font-size:13px;'><i class='fas fa-plus'></i> Switch / Create</button>\n" +
                    "</form>"
                )
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 20px; padding: 14px 20px;"));
    }

    private Widget createTypeSpecificCrudCard(String engineKey, String targetDb, String queryResultDisplay) {
        Widget insertFormWidget = buildEngineInsertForm(engineKey, targetDb);
        Widget querySearchWidget = buildEngineQuerySearchForm(engineKey, targetDb, queryResultDisplay);

        return Div.of(
            Row.of(
                Column.of(insertFormWidget),
                Column.of(querySearchWidget)
            ).modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1.1fr 1fr; gap: 24px;"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));
    }

    private Widget buildEngineInsertForm(String engineKey, String targetDb) {
        StringBuilder sb = new StringBuilder();
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        switch (engineKey) {
            case "DOCUMENT" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-file-alt' style='color:#38bdf8; margin-right:8px;'></i> Insert Document (JSON / Schema)</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Save structured JSON documents with optional Java Class and JettraRules validation.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Document ID</label><input class='form-input' type='text' name='target_id' value='doc_").append(System.currentTimeMillis() % 10000).append("' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Schema / _class (Optional)</label><input class='form-input' type='text' name='doc_class' placeholder='com.jettra.model.Customer' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Document JSON Fields</label>\n");
                sb.append("  <textarea name='doc_payload' class='form-input' style='height: 110px; font-family: monospace; font-size: 12px; resize: vertical;' required>{\n  \"name\": \"Global Enterprise Inc\",\n  \"tier\": \"Platinum\",\n  \"active\": true,\n  \"credit_limit\": 75000\n}</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-save'></i> Save Document</button>\n");
                sb.append("</form>");
            }
            case "KEYVALUE" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-key' style='color:#10b981; margin-right:8px;'></i> Set Key-Value Pair (High Speed Cache)</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Store native string/raw cache keys with sub-millisecond MemTable reads.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1.2fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Key (Lookup Identifier)</label><input class='form-input' type='text' name='target_id' value='session_tok_").append(System.currentTimeMillis() % 1000).append("' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Value Type</label><select name='kv_type' class='form-input'><option value='string'>Plain String</option><option value='json'>Raw JSON / Payload</option><option value='number'>Numeric Counter</option></select></div>\n");
                sb.append("  </div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Value Data</label>\n");
                sb.append("  <textarea name='kv_value' class='form-input' style='height: 80px; font-family: monospace; font-size: 13px; resize: vertical;' required>ACTIVE_USER_TOKEN_99A8BC71</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-save'></i> Put Key-Value</button>\n");
                sb.append("</form>");
            }
            case "VECTOR" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-project-diagram' style='color:#8b5cf6; margin-right:8px;'></i> Store Vector Embedding (AI / LLM)</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Insert high-dimensional float vectors with associated metadata attributes.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Vector ID</label><input class='form-input' type='text' name='target_id' value='vec_").append(System.currentTimeMillis() % 1000).append("' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Label / Classification</label><input class='form-input' type='text' name='vector_label' value='semantic_doc_embedding' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Float Vector Components (comma-separated)</label>\n");
                sb.append("  <input class='form-input' style='font-family:monospace; margin-bottom:10px;' type='text' name='vector_coords' value='0.12, 0.45, 0.88, 0.31' required />\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Metadata JSON</label>\n");
                sb.append("  <textarea name='vector_meta' class='form-input' style='height: 50px; font-family: monospace; font-size: 12px;'>{\"title\": \"LSM B-Tree Whitepaper\", \"category\": \"database\"}</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-save'></i> Save Vector Embedding</button>\n");
                sb.append("</form>");
            }
            case "GRAPH" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-share-alt' style='color:#ec4899; margin-right:8px;'></i> Graph Vertex / Edge Manager</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Manage Labeled Property Graph (LPG) vertices and directed relationships.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Element Type</label><select name='graph_mode' class='form-input'><option value='node'>Node (Vertex)</option><option value='edge'>Edge (Relationship)</option></select></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Node ID / Edge From</label><input class='form-input' type='text' name='target_id' value='node_").append(System.currentTimeMillis() % 1000).append("' required /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Edge Target (To) / Label</label><input class='form-input' type='text' name='edge_to' placeholder='node_2 (if edge)' /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Label / Relation</label><input class='form-input' type='text' name='node_label' value='KNOWS' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Attributes / Properties JSON</label>\n");
                sb.append("  <textarea name='node_props' class='form-input' style='height: 55px; font-family: monospace; font-size: 12px;'>{\"name\": \"Alice\", \"role\": \"Lead Engineer\", \"weight\": 1.0}</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-plus-circle'></i> Save Graph Entity</button>\n");
                sb.append("</form>");
            }
            case "TIMESERIES" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-chart-line' style='color:#06b6d4; margin-right:8px;'></i> Ingest Time-Series Data Point</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Append-only telemetry logs with monotonic timestamp ordering.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1.2fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Timestamp (Millis)</label><input class='form-input' type='text' name='ts_timestamp' value='").append(System.currentTimeMillis()).append("' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Metric Value (Numeric)</label><input class='form-input' type='number' step='any' name='ts_value' value='42.50' required /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Unit of Measure</label><input class='form-input' type='text' name='ts_unit' value='celsius' /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Dimension Tags (JSON)</label><input class='form-input' style='font-family:monospace;' type='text' name='ts_tags' value='{\"host\": \"server-01\", \"rack\": \"A3\"}' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-plus'></i> Ingest Time Point</button>\n");
                sb.append("</form>");
            }
            case "COLUMN" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-table' style='color:#f97316; margin-right:8px;'></i> Insert OLAP Columnar Row</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Fast analytical row insertion into columnar contiguous arrays.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='margin-bottom:10px;'><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Row Key</label><input class='form-input' type='text' name='target_id' value='order_").append(System.currentTimeMillis() % 10000).append("' required /></div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Column Values (JSON or Key=Value pairs)</label>\n");
                sb.append("  <textarea name='col_data' class='form-input' style='height: 90px; font-family: monospace; font-size: 12px;' required>{\n  \"customer_id\": 101,\n  \"order_total\": 450.00,\n  \"tax\": 31.50,\n  \"status\": \"COMPLETED\"\n}</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-save'></i> Save Column Row</button>\n");
                sb.append("</form>");
            }
            case "GEOSPATIAL" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-globe-americas' style='color:#14b8a6; margin-right:8px;'></i> Register 2D Geospatial Coordinate</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Store geographical points with Latitude/Longitude and metadata properties.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Location ID</label><input class='form-input' type='text' name='target_id' value='loc_panama_").append(System.currentTimeMillis() % 1000).append("' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Location Name</label><input class='form-input' type='text' name='geo_name' value='Panama Logistics Hub' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Latitude</label><input class='form-input' type='number' step='any' name='geo_lat' value='8.9824' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Longitude</label><input class='form-input' type='number' step='any' name='geo_lon' value='-79.5199' required /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>GIS Metadata</label>\n");
                sb.append("  <textarea name='geo_meta' class='form-input' style='height: 45px; font-family: monospace; font-size: 12px;'>{\"city\": \"Panama City\", \"radius_km\": 15, \"active\": true}</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-map-marker-alt'></i> Register Geo Point</button>\n");
                sb.append("</form>");
            }
            case "OBJECT" -> {
                sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-archive' style='color:#a855f7; margin-right:8px;'></i> Store Binary BLOB / Serialized Stream</h3>");
                sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Persist binary objects, files, and serialized class payloads.</p>");
                sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
                sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
                sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
                sb.append("  <div style='display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;'>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Object Key / Filename</label><input class='form-input' type='text' name='target_id' value='invoice_2026_").append(System.currentTimeMillis() % 1000).append(".pdf' required /></div>\n");
                sb.append("    <div><label style='font-size:12px; color:#94a3b8; font-weight:600;'>MIME / Content Type</label><input class='form-input' type='text' name='obj_mime' value='application/pdf' /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <div style='margin-bottom:10px;'><label style='font-size:12px; color:#94a3b8; font-weight:600;'>Java Object Wrapper Class</label><input class='form-input' type='text' name='obj_class' value='com.jettra.storage.BlobDocument' /></div>\n");
                sb.append("  <label style='font-size:12px; color:#94a3b8; font-weight:600;'>Payload (Base64 / Stream Text)</label>\n");
                sb.append("  <textarea name='obj_payload' class='form-input' style='height: 60px; font-family: monospace; font-size: 12px;' required>JVBERi0xLjQKJcTl8uXr...[Base64 Stream Payload]...</textarea>\n");
                sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top: 10px;'><i class='fas fa-upload'></i> Save Object BLOB</button>\n");
                sb.append("</form>");
            }
        }
        return Paragraph.of(sb.toString());
    }

    private Widget buildEngineQuerySearchForm(String engineKey, String targetDb, String queryResultDisplay) {
        StringBuilder sb = new StringBuilder();
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);
        String jsonDisplay = queryResultDisplay.isEmpty() ? "{\n  \"message\": \"Execute a query, lookup, or similarity search to view live results.\"\n}" : queryResultDisplay;

        sb.append("<h3 style='margin: 0 0 10px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-search' style='color:#a78bfa; margin-right:8px;'></i> Query & Search Inspector</h3>");
        sb.append("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 14px;'>Execute primary key lookups or specialized queries (e.g. Vector Cosine Search, Geo Haversine).</p>");

        // Standard ID Query Form
        sb.append("<form method='POST' action='").append(actionUrl).append("' style='margin-bottom:12px;'>\n");
        sb.append("  <input type='hidden' name='action' value='query_object' />\n");
        sb.append("  <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
        sb.append("  <div style='display:flex; gap:8px;'>\n");
        sb.append("    <input class='form-input' style='flex:1;' type='text' name='target_id' placeholder='Enter Object ID / Key' required />\n");
        sb.append("    <button type='submit' class='btn-action btn-secondary'><i class='fas fa-bolt'></i> Fetch</button>\n");
        sb.append("  </div>\n");
        sb.append("</form>\n");

        // Specialized Search for VECTOR
        if ("VECTOR".equalsIgnoreCase(engineKey)) {
            sb.append("<div style='background:rgba(30,41,59,0.7); border:1px solid rgba(139,92,246,0.3); border-radius:8px; padding:10px; margin-bottom:12px;'>\n");
            sb.append("  <div style='font-size:12px; font-weight:600; color:#c084fc; margin-bottom:6px;'><i class='fas fa-brain'></i> Top-K Cosine Similarity Search</div>\n");
            sb.append("  <form method='POST' action='").append(actionUrl).append("'>\n");
            sb.append("    <input type='hidden' name='action' value='search_vector' />\n");
            sb.append("    <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
            sb.append("    <div style='display:grid; grid-template-columns: 2fr 1fr; gap:8px; margin-bottom:6px;'>\n");
            sb.append("      <input class='form-input' style='font-size:12px;' type='text' name='query_vector' value='0.10, 0.44, 0.85, 0.30' placeholder='Query Vector float[]' required />\n");
            sb.append("      <input class='form-input' style='font-size:12px;' type='number' name='top_k' value='5' min='1' max='50' />\n");
            sb.append("    </div>\n");
            sb.append("    <button type='submit' class='btn-action btn-primary' style='padding:4px 10px; font-size:12px;'><i class='fas fa-search'></i> Run ANN Cosine Search</button>\n");
            sb.append("  </form>\n");
            sb.append("</div>\n");
        }

        // Specialized Tool for GEOSPATIAL
        if ("GEOSPATIAL".equalsIgnoreCase(engineKey)) {
            sb.append("<div style='background:rgba(30,41,59,0.7); border:1px solid rgba(20,184,166,0.3); border-radius:8px; padding:10px; margin-bottom:12px;'>\n");
            sb.append("  <div style='font-size:12px; font-weight:600; color:#2dd4bf; margin-bottom:6px;'><i class='fas fa-route'></i> Haversine Distance Calculator</div>\n");
            sb.append("  <form method='POST' action='").append(actionUrl).append("'>\n");
            sb.append("    <input type='hidden' name='action' value='calc_distance' />\n");
            sb.append("    <input type='hidden' name='target_db' value='").append(targetDb).append("' />\n");
            sb.append("    <div style='display:grid; grid-template-columns: 1fr 1fr; gap:6px; margin-bottom:6px;'>\n");
            sb.append("      <input class='form-input' style='font-size:11px;' type='number' step='any' name='dist_lat1' value='8.9824' placeholder='Lat 1' />\n");
            sb.append("      <input class='form-input' style='font-size:11px;' type='number' step='any' name='dist_lon1' value='-79.5199' placeholder='Lon 1' />\n");
            sb.append("      <input class='form-input' style='font-size:11px;' type='number' step='any' name='dist_lat2' value='8.9745' placeholder='Lat 2' />\n");
            sb.append("      <input class='form-input' style='font-size:11px;' type='number' step='any' name='dist_lon2' value='-79.5532' placeholder='Lon 2' />\n");
            sb.append("    </div>\n");
            sb.append("    <button type='submit' class='btn-action btn-secondary' style='padding:4px 10px; font-size:12px;'><i class='fas fa-calculator'></i> Calculate Distance (km)</button>\n");
            sb.append("  </form>\n");
            sb.append("</div>\n");
        }

        // Live Engine Result Display
        sb.append("<div style='background: rgba(15,23,42,0.9); border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 10px;'>\n");
        sb.append("  <div style='font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 4px;'><i class='fas fa-terminal'></i> LIVE ENGINE RESULT</div>\n");
        sb.append("  <pre style='margin:0; font-family: monospace; font-size: 12px; color: #38bdf8; max-height: 120px; overflow-y: auto;'>").append(jsonDisplay).append("</pre>\n");
        sb.append("</div>\n");

        return Paragraph.of(sb.toString());
    }

    private Widget createLiveObjectsExplorer(String engineKey, String targetDb) {
        StringBuilder sb = new StringBuilder();
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        sb.append("<div style='display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;'>\n");
        sb.append("  <h3 style='margin:0; font-size:18px; font-weight:600;'><i class='fas fa-list' style='color:#38bdf8; margin-right:8px;'></i> Stored Objects in ").append(engineKey).append(" [").append(targetDb).append("]</h3>\n");
        sb.append("  <a href='").append(actionUrl).append("&target_db=").append(targetDb).append("' class='btn-action btn-secondary' style='padding:4px 10px; font-size:12px;'><i class='fas fa-sync'></i> Refresh Objects</a>\n");
        sb.append("</div>\n");

        sb.append("<div class='table-responsive'>\n");
        sb.append("  <table class='jettra-table'>\n");
        sb.append("    <thead>\n");
        sb.append("      <tr>\n");
        sb.append("        <th>Object ID / Key</th>\n");
        sb.append("        <th>Type & Specific Representation</th>\n");
        sb.append("        <th>Storage Preview</th>\n");
        sb.append("        <th>Actions</th>\n");
        sb.append("      </tr>\n");
        sb.append("    </thead>\n");
        sb.append("    <tbody>\n");

        int count = 0;
        switch (engineKey) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    Map<String, JsonObject> items = de.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge badge-active'>DOCUMENT (JSON)</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    Map<String, String> items = ke.list(targetDb);
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String val = entry.getValue();
                        if (val.length() > 65) val = val.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge badge-engine'>KEY-VALUE STRING</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(val).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) {
                    Map<String, JsonObject> items = ve.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(139,92,246,0.2); color:#c084fc;'>VECTOR (float[])</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) {
                    Map<String, JsonObject> nodes = ge.listNodes(targetDb);
                    for (Map.Entry<String, JsonObject> entry : nodes.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(236,72,153,0.2); color:#f472b6;'>VERTEX (Node)</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    Map<String, JsonObject> points = te.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : points.entrySet()) {
                        count++;
                        String ts = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        sb.append("<tr>");
                        sb.append("<td><b>TS: ").append(ts).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(6,182,212,0.2); color:#22d3ee;'>TIME-SERIES POINT</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, ts)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) {
                    Map<String, JsonObject> rows = ce.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : rows.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(249,115,22,0.2); color:#fb923c;'>COLUMNAR ROW</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (ge != null) {
                    Map<String, JsonObject> locs = ge.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : locs.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(20,184,166,0.2); color:#2dd4bf;'>GIS 2D POINT</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) {
                    Map<String, JsonObject> objs = oe.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : objs.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        sb.append("<tr>");
                        sb.append("<td><b>").append(id).append("</b></td>");
                        sb.append("<td><span class='store-badge' style='background:rgba(168,85,247,0.2); color:#c084fc;'>OBJECT BLOB</span></td>");
                        sb.append("<td><code style='font-size:11px;'>").append(preview).append("</code></td>");
                        sb.append("<td>").append(buildDeleteButton(actionUrl, targetDb, id)).append("</td>");
                        sb.append("</tr>");
                    }
                }
            }
        }

        if (count == 0) {
            sb.append("<tr><td colspan='4' style='text-align:center; color:#94a3b8; padding:20px;'>No objects currently stored in ").append(engineKey).append(" [").append(targetDb).append("]. Use the form above to add objects.</td></tr>");
        }

        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("</div>\n");

        return Div.of(Paragraph.of(sb.toString())).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom:24px;"));
    }

    private String buildDeleteButton(String actionUrl, String db, String id) {
        return "<form method='POST' action='" + actionUrl + "' style='display:inline; margin:0;'>\n" +
               "  <input type='hidden' name='action' value='delete_object' />\n" +
               "  <input type='hidden' name='target_db' value='" + db + "' />\n" +
               "  <input type='hidden' name='target_id' value='" + id + "' />\n" +
               "  <button type='submit' class='btn-action btn-secondary' style='color:#ef4444; padding:3px 8px; font-size:11px;' onclick='return confirm(\"Are you sure you want to delete object " + id + "?\");'><i class='fas fa-trash'></i> Delete</button>\n" +
               "</form>";
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
                "      <tr><td><i class='fas fa-key' style='color:#10b981; margin-right:6px;'></i> <b>KEYVALUE</b></td><td>Session Cache, Distributed Key-Value</td><td>LSM MemTable + SSTable</td><td>Raft Sync</td><td><code>/api/model/keyvalue/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-project-diagram' style='color:#8b5cf6; margin-right:6px;'></i> <b>VECTOR</b></td><td>AI Embeddings, Cosine Similarity, ANN</td><td>Vector Index (float[])</td><td>Raft Sync</td><td><code>/api/model/vector/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-share-alt' style='color:#ec4899; margin-right:6px;'></i> <b>GRAPH</b></td><td>Knowledge Graphs, Social Networks, Traversal</td><td>Adjacency List + B-Tree</td><td>Raft Sync</td><td><code>/api/model/graph/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-chart-line' style='color:#06b6d4; margin-right:6px;'></i> <b>TIMESERIES</b></td><td>IoT Telemetry, Metrics, Server Logs</td><td>Append-only Chunked WAL</td><td>Raft Sync</td><td><code>/api/model/timeseries/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-table' style='color:#f97316; margin-right:6px;'></i> <b>COLUMN</b></td><td>OLAP Big Data Aggregations</td><td>Column Vectors & Run-Length</td><td>Raft Sync</td><td><code>/api/model/column/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-globe-americas' style='color:#14b8a6; margin-right:6px;'></i> <b>GEOSPATIAL</b></td><td>Spatial Coordinates, Radius, GIS</td><td>Geohash / QuadTree</td><td>Raft Sync</td><td><code>/api/model/geospatial/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "      <tr><td><i class='fas fa-archive' style='color:#a855f7; margin-right:6px;'></i> <b>OBJECT</b></td><td>Binary BLOBs, Serialized Stream Files</td><td>Chunked Block Store</td><td>Raft Sync</td><td><code>/api/model/object/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }
}
