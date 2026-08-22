package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.*;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.server.JettraServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Interactive Type-Specific Database and Object Administrator for all 9 Multi-Model Storage Engines in JettraStoreEngine.
 * Provides specialized management interfaces for Document, KeyValue, Vector, Graph,
 * TimeSeries, Column, Geospatial, Object, and Records engines, built entirely with JettraFlux components.
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
        Widget titleHeading = Header.of(1,
            Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
            Text.of("Multi-Model Database & Objects Administrator")
        ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;"));

        Widget titleDesc = Paragraph.of(
            Text.of("Administer databases and manage native typed objects across all 9 multi-model engines with specialized controls.")
        ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"));

        Widget backLink = Link.of(JettraServer.resolvePath("/dashboard"),
            Icon.of("fas fa-arrow-left"),
            Text.of(" Dashboard")
        ).modifier(new Modifier().cssClass("btn-action btn-secondary"));

        Widget titleBlock = Row.of(
            Column.of(titleHeading, titleDesc),
            Row.of(backLink).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner (if any)
        Widget alertWidget = alertMessage.isEmpty() ? Div.of() : Div.of(
            Div.of(
                Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; font-size:18px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:14px; color:#f8fafc; font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
            Span.of("STATUS").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;"));

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
            case "RECORDS" -> "records_store";
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
            case "RECORDS" -> {
                RecordsEngine recEngine = (RecordsEngine) engine.getEngine("RECORDS");
                if (recEngine != null) {
                    String recordClass = params.getOrDefault("rec_class", "com.jettra.model.PersonRecord");
                    String payload = params.getOrDefault("rec_payload", "{}");
                    JsonObject comps = parseJsonOrWrap(payload);
                    recEngine.saveRecord(db, targetId, recordClass, comps);
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
            case "RECORDS" -> {
                RecordsEngine recEngine = (RecordsEngine) engine.getEngine("RECORDS");
                if (recEngine != null) {
                    JsonObject res = recEngine.getRecord(db, id);
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
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) re.deleteRecord(db, id);
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
        String[] engines = {"DOCUMENT", "KEYVALUE", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT", "RECORDS"};
        String[] icons = {"fas fa-file-alt", "fas fa-key", "fas fa-project-diagram", "fas fa-share-alt", "fas fa-chart-line", "fas fa-table", "fas fa-globe-americas", "fas fa-archive", "fas fa-id-card"};
        String[] types = {"NoSQL JSON", "KV Cache", "AI Vector ANN", "LPG Graph", "IoT Telemetry", "OLAP Columns", "2D GIS Spatial", "Binary BLOB", "Java 25 Record"};

        List<Widget> pills = new ArrayList<>();
        for (int i = 0; i < engines.length; i++) {
            String eng = engines[i];
            String icon = icons[i];
            String typeBadge = types[i];
            boolean active = eng.equalsIgnoreCase(current);
            String bg = active ? "background: #3b82f6; color: #ffffff; font-weight: 600; box-shadow: 0 0 12px rgba(59,130,246,0.4);" : "background: transparent; color: #94a3b8;";

            pills.add(Link.of(JettraServer.resolvePath("/engines?engine=" + eng),
                Icon.of(icon),
                Span.of(eng),
                Span.of(typeBadge).modifier(new Modifier().style("font-size:10px; opacity:0.8; background:rgba(0,0,0,0.2); padding:2px 6px; border-radius:4px;"))
            ).modifier(new Modifier().style("display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 8px; text-decoration: none; font-size: 13px; transition: all 0.2s; " + bg)));
        }

        return Div.of(pills.toArray(new Widget[0]))
            .modifier(new Modifier().style("display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; padding: 6px; background: rgba(30, 41, 59, 0.5); border-radius: 12px; border: 1px solid rgba(255,255,255,0.06);"));
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
            case "RECORDS" -> "Records Collection / Namespace";
            default -> "Database";
        };

        // Discover databases
        Set<String> discoveredDbs = new TreeSet<>();
        discoveredDbs.add(currentDb);
        discoveredDbs.add(getDefaultDbForEngine(engineKey));
        String prefix = switch (engineKey.toUpperCase()) {
            case "RECORDS" -> "rec:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "KEYVALUE" -> "kv:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };
        Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(prefix);
        for (String k : scanned.keySet()) {
            String rest = k.substring(prefix.length());
            int idx = rest.indexOf(':');
            if (idx > 0) {
                discoveredDbs.add(rest.substring(0, idx));
            }
        }

        Widget leftInfo = Column.of(
            Div.of(
                Icon.of("fas fa-folder-open").modifier(new Modifier().style("color:#38bdf8; margin-right:6px;")),
                Text.of("Active " + dbLabel + ": "),
                Span.of(currentDb).modifier(new Modifier().style("color:#38bdf8; font-weight:700;"))
            ).modifier(new Modifier().style("font-size:14px; font-weight:600; color:#f8fafc;")),
            Div.of(
                Text.of("Engine Type: "),
                Span.of(engineKey).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;")),
                Text.of(" (Raft Synchronized & LSM-Tree Indexed) | "),
                Link.of(JettraServer.resolvePath("/databases"),
                    Icon.of("fas fa-server"),
                    Text.of(" View All Databases")
                ).modifier(new Modifier().style("color:#38bdf8; text-decoration:none;"))
            ).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
        );

        Dropdown dbSelect = Dropdown.of(new ArrayList<>(discoveredDbs))
            .selected(currentDb)
            .placeholder(null);
        dbSelect.attribute("onchange", "window.location.href='" + JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=") + "' + this.value");
        dbSelect.modifier(new Modifier().style("padding:6px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#38bdf8; font-size:13px;"));

        Widget switchForm = Form.of(
            Hidden.of("action", "create_db"),
            dbSelect,
            TextField.of("target_db", "New DB / Collection")
                .modifier(new Modifier().cssClass("form-input").style("width:180px; padding:6px 10px; font-size:13px;")),
            Button.of(
                Icon.of("fas fa-plus"),
                Text.of(" Switch / Create")
            ).attribute("type", "submit")
             .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:6px 12px; font-size:13px;"))
        ).action(JettraServer.resolvePath("/engines?engine=" + engineKey))
         .method("POST")
         .modifier(new Modifier().style("display:flex; gap:8px; margin:0; align-items:center;"));

        return Div.of(
            Row.of(
                leftInfo,
                switchForm
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center;"))
        ).modifier(new Modifier().cssClass("store-card").style("margin-bottom: 20px; padding: 14px 20px;"));
    }

    private Widget createTypeSpecificCrudCard(String engineKey, String targetDb, String queryResultDisplay) {
        Widget insertFormWidget = buildEngineInsertForm(engineKey, targetDb);
        Widget querySearchWidget = buildEngineQuerySearchForm(engineKey, targetDb, queryResultDisplay);

        return Div.of(
            Row.of(
                Column.of(insertFormWidget),
                Column.of(querySearchWidget)
            ).modifier(new Modifier().style("display: grid; grid-template-columns: 1.1fr 1fr; gap: 24px;"))
        ).modifier(new Modifier().cssClass("store-card").style("margin-bottom: 24px;"));
    }

    private Widget buildEngineInsertForm(String engineKey, String targetDb) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        switch (engineKey) {
            case "DOCUMENT" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-file-alt").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Insert Document (JSON / Schema)")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Save structured JSON documents with optional Java Class and JettraRules validation.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Document ID").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "ID").value("doc_" + (System.currentTimeMillis() % 10000)).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Schema / _class (Optional)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("doc_class", "com.jettra.model.Customer").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("Document JSON Fields").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("doc_payload").rows(5).value("{\n  \"name\": \"Global Enterprise Inc\",\n  \"tier\": \"Platinum\",\n  \"active\": true,\n  \"credit_limit\": 75000\n}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 110px; font-family: monospace; font-size: 12px; resize: vertical;")),
                    Button.of(Icon.of("fas fa-save"), Text.of(" Save Document"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "KEYVALUE" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-key").modifier(new Modifier().style("color:#10b981; margin-right:8px;")),
                    Text.of("Set Key-Value Pair (High Speed Cache)")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Store native string/raw cache keys with sub-millisecond MemTable reads.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Dropdown kvTypeDropdown = Dropdown.of("string", "json", "number")
                    .selected("string")
                    .placeholder(null);
                kvTypeDropdown.attribute("name", "kv_type");
                kvTypeDropdown.modifier(new Modifier().cssClass("form-input"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Key (Lookup Identifier)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "Key").value("session_tok_" + (System.currentTimeMillis() % 1000)).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Value Type").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            kvTypeDropdown
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1.2fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("Value Data").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("kv_value").rows(3).value("ACTIVE_USER_TOKEN_99A8BC71")
                        .modifier(new Modifier().cssClass("form-input").style("height: 80px; font-family: monospace; font-size: 13px; resize: vertical;")),
                    Button.of(Icon.of("fas fa-save"), Text.of(" Put Key-Value"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "VECTOR" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-project-diagram").modifier(new Modifier().style("color:#8b5cf6; margin-right:8px;")),
                    Text.of("Store Vector Embedding (AI / LLM)")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Insert high-dimensional float vectors with associated metadata attributes.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Vector ID").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "ID").value("vec_" + (System.currentTimeMillis() % 1000)).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Label / Classification").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("vector_label", "Label").value("semantic_doc_embedding").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("Float Vector Components (comma-separated)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextField.of("vector_coords", "Floats").value("0.12, 0.45, 0.88, 0.31")
                        .modifier(new Modifier().cssClass("form-input").style("font-family:monospace; margin-bottom:10px;")),
                    Label.of("Metadata JSON").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("vector_meta").rows(2).value("{\"title\": \"LSM B-Tree Whitepaper\", \"category\": \"database\"}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 50px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-save"), Text.of(" Save Vector Embedding"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "GRAPH" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-share-alt").modifier(new Modifier().style("color:#ec4899; margin-right:8px;")),
                    Text.of("Graph Vertex / Edge Manager")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Manage Labeled Property Graph (LPG) vertices and directed relationships.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Dropdown modeDropdown = Dropdown.of("node", "edge").selected("node").placeholder(null);
                modeDropdown.attribute("name", "graph_mode");
                modeDropdown.modifier(new Modifier().cssClass("form-input"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Element Type").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            modeDropdown
                        ),
                        Div.of(
                            Label.of("Node ID / Edge From").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "ID").value("node_" + (System.currentTimeMillis() % 1000)).modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Div.of(
                        Div.of(
                            Label.of("Edge Target (To) / Label").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("edge_to", "node_2 (if edge)").modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Label / Relation").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("node_label", "Label").value("KNOWS").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("Attributes / Properties JSON").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("node_props").rows(2).value("{\"name\": \"Alice\", \"role\": \"Lead Engineer\", \"weight\": 1.0}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 55px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-plus-circle"), Text.of(" Save Graph Entity"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "TIMESERIES" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-chart-line").modifier(new Modifier().style("color:#06b6d4; margin-right:8px;")),
                    Text.of("Ingest Time-Series Data Point")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Append-only telemetry logs with monotonic timestamp ordering.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Timestamp (Millis)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("ts_timestamp", "Timestamp").value(String.valueOf(System.currentTimeMillis())).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Metric Value (Numeric)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("ts_value", "Value").value("42.50").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1.2fr 1fr; gap:10px; margin-bottom:10px;")),
                    Div.of(
                        Div.of(
                            Label.of("Unit of Measure").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("ts_unit", "Unit").value("celsius").modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Dimension Tags (JSON)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("ts_tags", "JSON").value("{\"host\": \"server-01\", \"rack\": \"A3\"}").modifier(new Modifier().cssClass("form-input").style("font-family:monospace;"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Button.of(Icon.of("fas fa-plus"), Text.of(" Ingest Time Point"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "COLUMN" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-table").modifier(new Modifier().style("color:#f97316; margin-right:8px;")),
                    Text.of("Insert OLAP Columnar Row")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Fast analytical row insertion into columnar contiguous arrays.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Label.of("Row Key").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                        TextField.of("target_id", "Row Key").value("order_" + (System.currentTimeMillis() % 10000)).modifier(new Modifier().cssClass("form-input"))
                    ).modifier(new Modifier().style("margin-bottom:10px;")),
                    Label.of("Column Values (JSON or Key=Value pairs)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("col_data").rows(4).value("{\n  \"customer_id\": 101,\n  \"order_total\": 450.00,\n  \"tax\": 31.50,\n  \"status\": \"COMPLETED\"\n}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 90px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-save"), Text.of(" Save Column Row"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "GEOSPATIAL" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-globe-americas").modifier(new Modifier().style("color:#14b8a6; margin-right:8px;")),
                    Text.of("Register 2D Geospatial Coordinate")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Store geographical points with Latitude/Longitude and metadata properties.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Location ID").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "ID").value("loc_panama_" + (System.currentTimeMillis() % 1000)).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Location Name").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("geo_name", "Panama Logistics Hub").value("Panama Logistics Hub").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Div.of(
                        Div.of(
                            Label.of("Latitude").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("geo_lat", "8.9824").value("8.9824").modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Longitude").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("geo_lon", "-79.5199").value("-79.5199").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("GIS Metadata").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("geo_meta").rows(2).value("{\"city\": \"Panama City\", \"radius_km\": 15, \"active\": true}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 45px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-map-marker-alt"), Text.of(" Register Geo Point"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "OBJECT" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-archive").modifier(new Modifier().style("color:#a855f7; margin-right:8px;")),
                    Text.of("Store Binary BLOB / Serialized Stream")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Persist binary objects, files, and serialized class payloads.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Object Key / Filename").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "Filename").value("invoice_2026_" + (System.currentTimeMillis() % 1000) + ".pdf").modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("MIME / Content Type").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("obj_mime", "application/pdf").value("application/pdf").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Div.of(
                        Label.of("Java Object Wrapper Class").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                        TextField.of("obj_class", "com.jettra.storage.BlobDocument").value("com.jettra.storage.BlobDocument").modifier(new Modifier().cssClass("form-input"))
                    ).modifier(new Modifier().style("margin-bottom:10px;")),
                    Label.of("Payload (Base64 / Stream Text)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("obj_payload").rows(3).value("JVBERi0xLjQKJcTl8uXr...[Base64 Stream Payload]...")
                        .modifier(new Modifier().cssClass("form-input").style("height: 60px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-upload"), Text.of(" Save Object BLOB"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            case "RECORDS" -> {
                Widget heading = Header.of(3,
                    Icon.of("fas fa-id-card").modifier(new Modifier().style("color:#f43f5e; margin-right:8px;")),
                    Text.of("Store Immutable Java Record")
                ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

                Widget desc = Paragraph.of(
                    Text.of("Persist typed Java records with structural schema reflection and component validation.")
                ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

                Widget form = Form.of(
                    Hidden.of("action", "insert_object"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        Div.of(
                            Label.of("Record ID").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("target_id", "ID").value("rec_" + (System.currentTimeMillis() % 1000)).modifier(new Modifier().cssClass("form-input"))
                        ),
                        Div.of(
                            Label.of("Record Class").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                            TextField.of("rec_class", "com.jettra.model.PersonRecord").value("com.jettra.model.PersonRecord").modifier(new Modifier().cssClass("form-input"))
                        )
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:10px; margin-bottom:10px;")),
                    Label.of("Record Components JSON (Fields & Values)").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600;")),
                    TextArea.create().name("rec_payload").rows(4).value("{\n  \"id\": \"rec_01\",\n  \"fullName\": \"Alice Monroe\",\n  \"email\": \"alice@enterprise.org\",\n  \"active\": true,\n  \"salary\": 92500.00\n}")
                        .modifier(new Modifier().cssClass("form-input").style("height: 90px; font-family: monospace; font-size: 12px;")),
                    Button.of(Icon.of("fas fa-save"), Text.of(" Save Java Record"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("margin-top: 10px;"))
                ).action(actionUrl).method("POST");

                return Div.of(heading, desc, form);
            }
            default -> {
                return Div.of();
            }
        }
    }

    private Widget buildEngineQuerySearchForm(String engineKey, String targetDb, String queryResultDisplay) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);
        String jsonDisplay = queryResultDisplay.isEmpty() ? "{\n  \"message\": \"Execute a query, lookup, or similarity search to view live results.\"\n}" : queryResultDisplay;

        Widget heading = Header.of(3,
            Icon.of("fas fa-search").modifier(new Modifier().style("color:#a78bfa; margin-right:8px;")),
            Text.of("Query & Search Inspector")
        ).modifier(new Modifier().style("margin: 0 0 10px 0; font-size: 16px; font-weight: 600;"));

        Widget desc = Paragraph.of(
            Text.of("Execute primary key lookups or specialized queries (e.g. Vector Cosine Search, Geo Haversine).")
        ).modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 14px;"));

        // Standard ID Query Form
        Widget idQueryForm = Form.of(
            Hidden.of("action", "query_object"),
            Hidden.of("target_db", targetDb),
            Div.of(
                TextField.of("target_id", "Enter Object ID / Key")
                    .modifier(new Modifier().cssClass("form-input").style("flex:1;")),
                Button.of(Icon.of("fas fa-bolt"), Text.of(" Fetch"))
                    .attribute("type", "submit")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary"))
            ).modifier(new Modifier().style("display:flex; gap:8px;"))
        ).action(actionUrl).method("POST").modifier(new Modifier().style("margin-bottom:12px;"));

        // Specialized Search for VECTOR
        Widget vectorSearchWidget = Div.of();
        if ("VECTOR".equalsIgnoreCase(engineKey)) {
            vectorSearchWidget = Div.of(
                Div.of(
                    Icon.of("fas fa-brain"),
                    Text.of(" Top-K Cosine Similarity Search")
                ).modifier(new Modifier().style("font-size:12px; font-weight:600; color:#c084fc; margin-bottom:6px;")),
                Form.of(
                    Hidden.of("action", "search_vector"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        TextField.of("query_vector", "Query Vector float[]").value("0.10, 0.44, 0.85, 0.30")
                            .modifier(new Modifier().cssClass("form-input").style("font-size:12px;")),
                        TextField.of("top_k", "5").value("5")
                            .modifier(new Modifier().cssClass("form-input").style("font-size:12px;"))
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 2fr 1fr; gap:8px; margin-bottom:6px;")),
                    Button.of(Icon.of("fas fa-search"), Text.of(" Run ANN Cosine Search"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:4px 10px; font-size:12px;"))
                ).action(actionUrl).method("POST")
            ).modifier(new Modifier().style("background:rgba(30,41,59,0.7); border:1px solid rgba(139,92,246,0.3); border-radius:8px; padding:10px; margin-bottom:12px;"));
        }

        // Specialized Tool for GEOSPATIAL
        Widget geoCalcWidget = Div.of();
        if ("GEOSPATIAL".equalsIgnoreCase(engineKey)) {
            geoCalcWidget = Div.of(
                Div.of(
                    Icon.of("fas fa-route"),
                    Text.of(" Haversine Distance Calculator")
                ).modifier(new Modifier().style("font-size:12px; font-weight:600; color:#2dd4bf; margin-bottom:6px;")),
                Form.of(
                    Hidden.of("action", "calc_distance"),
                    Hidden.of("target_db", targetDb),
                    Div.of(
                        TextField.of("dist_lat1", "Lat 1").value("8.9824").modifier(new Modifier().cssClass("form-input").style("font-size:11px;")),
                        TextField.of("dist_lon1", "Lon 1").value("-79.5199").modifier(new Modifier().cssClass("form-input").style("font-size:11px;")),
                        TextField.of("dist_lat2", "Lat 2").value("8.9745").modifier(new Modifier().cssClass("form-input").style("font-size:11px;")),
                        TextField.of("dist_lon2", "Lon 2").value("-79.5532").modifier(new Modifier().cssClass("form-input").style("font-size:11px;"))
                    ).modifier(new Modifier().style("display:grid; grid-template-columns: 1fr 1fr; gap:6px; margin-bottom:6px;")),
                    Button.of(Icon.of("fas fa-calculator"), Text.of(" Calculate Distance (km)"))
                        .attribute("type", "submit")
                        .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:4px 10px; font-size:12px;"))
                ).action(actionUrl).method("POST")
            ).modifier(new Modifier().style("background:rgba(30,41,59,0.7); border:1px solid rgba(20,184,166,0.3); border-radius:8px; padding:10px; margin-bottom:12px;"));
        }

        // Live Engine Result Display
        Widget liveResultDisplay = Div.of(
            Div.of(
                Icon.of("fas fa-terminal"),
                Text.of(" LIVE ENGINE RESULT")
            ).modifier(new Modifier().style("font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 4px;")),
            RawHtml.of("<pre style='margin:0; font-family: monospace; font-size: 12px; color: #38bdf8; max-height: 120px; overflow-y: auto;'>" + jsonDisplay + "</pre>")
        ).modifier(new Modifier().style("background: rgba(15,23,42,0.9); border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 10px;"));

        return Div.of(
            heading,
            desc,
            idQueryForm,
            vectorSearchWidget,
            geoCalcWidget,
            liveResultDisplay
        );
    }

    private Widget createLiveObjectsExplorer(String engineKey, String targetDb) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        Widget explorerHeader = Row.of(
            Header.of(3,
                Icon.of("fas fa-list").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Text.of("Stored Objects in " + engineKey + " [" + targetDb + "]")
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:600;")),
            Link.of(actionUrl + "&target_db=" + targetDb,
                Icon.of("fas fa-sync"),
                Text.of(" Refresh Objects")
            ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:4px 10px; font-size:12px;"))
        ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-bottom:14px;"));

        List<Widget> tableHeaders = List.of(
            Text.of("Object ID / Key"),
            Text.of("Type & Specific Representation"),
            Text.of("Storage Preview"),
            Text.of("Actions")
        );

        List<List<Widget>> tableRows = new ArrayList<>();

        switch (engineKey) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    Map<String, JsonObject> items = de.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("DOCUMENT (JSON)").modifier(new Modifier().cssClass("store-badge badge-active")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    Map<String, String> items = ke.list(targetDb);
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        String id = entry.getKey();
                        String val = entry.getValue();
                        if (val.length() > 65) val = val.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("KEY-VALUE STRING").modifier(new Modifier().cssClass("store-badge badge-engine")),
                            RawHtml.of("<code style='font-size:11px;'>" + val + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) {
                    Map<String, JsonObject> items = ve.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("VECTOR (float[])").modifier(new Modifier().cssClass("store-badge").style("background:rgba(139,92,246,0.2); color:#c084fc;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) {
                    Map<String, JsonObject> nodes = ge.listNodes(targetDb);
                    for (Map.Entry<String, JsonObject> entry : nodes.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("VERTEX (Node)").modifier(new Modifier().cssClass("store-badge").style("background:rgba(236,72,153,0.2); color:#f472b6;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    Map<String, JsonObject> points = te.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : points.entrySet()) {
                        String ts = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        tableRows.add(List.of(
                            Span.of("TS: " + ts).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("TIME-SERIES POINT").modifier(new Modifier().cssClass("store-badge").style("background:rgba(6,182,212,0.2); color:#22d3ee;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, ts)
                        ));
                    }
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) {
                    Map<String, JsonObject> rows = ce.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : rows.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("COLUMNAR ROW").modifier(new Modifier().cssClass("store-badge").style("background:rgba(249,115,22,0.2); color:#fb923c;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (ge != null) {
                    Map<String, JsonObject> locs = ge.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : locs.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("GIS 2D POINT").modifier(new Modifier().cssClass("store-badge").style("background:rgba(20,184,166,0.2); color:#2dd4bf;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) {
                    Map<String, JsonObject> objs = oe.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : objs.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("OBJECT BLOB").modifier(new Modifier().cssClass("store-badge").style("background:rgba(168,85,247,0.2); color:#c084fc;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) {
                    Map<String, JsonObject> recs = re.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : recs.entrySet()) {
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        if (preview.length() > 65) preview = preview.substring(0, 65) + "...";
                        tableRows.add(List.of(
                            Span.of(id).modifier(new Modifier().style("font-weight:bold;")),
                            Span.of("RECORD (Java 25)").modifier(new Modifier().cssClass("store-badge").style("background:rgba(244,63,94,0.2); color:#fb7185;")),
                            RawHtml.of("<code style='font-size:11px;'>" + preview + "</code>"),
                            buildDeleteButtonWidget(actionUrl, targetDb, id)
                        ));
                    }
                }
            }
        }

        if (tableRows.isEmpty()) {
            tableRows.add(List.of(
                Span.of("No objects currently stored in " + engineKey + " [" + targetDb + "]. Use the form above to add objects.")
                    .modifier(new Modifier().style("color:#94a3b8; text-align:center;")),
                Span.of(""),
                Span.of(""),
                Span.of("")
            ));
        }

        Datatable datatable = Datatable.ofWidgets(tableHeaders, tableRows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget tableResponsive = Div.of(datatable).modifier(new Modifier().cssClass("table-responsive"));

        return Div.of(explorerHeader, tableResponsive)
            .modifier(new Modifier().cssClass("store-card").style("margin-bottom:24px;"));
    }

    private Widget buildDeleteButtonWidget(String actionUrl, String db, String id) {
        Button delBtn = Button.of(
            Icon.of("fas fa-trash"),
            Text.of(" Delete")
        );
        delBtn.attribute("type", "submit");
        delBtn.attribute("onclick", "return confirm(\"Are you sure you want to delete object " + id + "?\");");
        delBtn.modifier(new Modifier().cssClass("btn-action btn-secondary").style("color:#ef4444; padding:3px 8px; font-size:11px;"));

        return Form.of(
            Hidden.of("action", "delete_object"),
            Hidden.of("target_db", db),
            Hidden.of("target_id", id),
            delBtn
        ).action(actionUrl).method("POST").modifier(new Modifier().style("display:inline; margin:0;"));
    }

    private Widget createEngineMatrixTable() {
        Widget header = Header.of(3,
            Icon.of("fas fa-table").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
            Text.of("All 9 Supported Multi-Model Engines")
        ).modifier(new Modifier().style("margin: 0 0 16px 0; font-size: 18px; font-weight: 600;"));

        List<Widget> headers = List.of(
            Text.of("Engine Name"),
            Text.of("Primary Use Case"),
            Text.of("Storage Schema"),
            Text.of("Replication"),
            Text.of("REST API Route"),
            Text.of("Status")
        );

        String[][] matrixData = {
            {"fas fa-file-alt", "#3b82f6", "DOCUMENT", "Hierarchical JSON / NoSQL documents", "B-Tree / LSM Hybrid", "Raft Sync", "/api/document/{coll}/{id}", "ACTIVE"},
            {"fas fa-key", "#10b981", "KEYVALUE", "Session Cache, Distributed Key-Value", "LSM MemTable + SSTable", "Raft Sync", "/api/model/keyvalue/*", "ACTIVE"},
            {"fas fa-project-diagram", "#8b5cf6", "VECTOR", "AI Embeddings, Cosine Similarity, ANN", "Vector Index (float[])", "Raft Sync", "/api/model/vector/*", "ACTIVE"},
            {"fas fa-share-alt", "#ec4899", "GRAPH", "Knowledge Graphs, Social Networks, Traversal", "Adjacency List + B-Tree", "Raft Sync", "/api/model/graph/*", "ACTIVE"},
            {"fas fa-chart-line", "#06b6d4", "TIMESERIES", "IoT Telemetry, Metrics, Server Logs", "Append-only Chunked WAL", "Raft Sync", "/api/model/timeseries/*", "ACTIVE"},
            {"fas fa-table", "#f97316", "COLUMN", "OLAP Big Data Aggregations", "Column Vectors & Run-Length", "Raft Sync", "/api/model/column/*", "ACTIVE"},
            {"fas fa-globe-americas", "#14b8a6", "GEOSPATIAL", "Spatial Coordinates, Radius, GIS", "Geohash / QuadTree", "Raft Sync", "/api/model/geospatial/*", "ACTIVE"},
            {"fas fa-archive", "#a855f7", "OBJECT", "Binary BLOBs, Serialized Stream Files", "Chunked Block Store", "Raft Sync", "/api/model/object/*", "ACTIVE"},
            {"fas fa-id-card", "#f43f5e", "RECORDS", "Immutable Java 25 Records, Component Validation", "Compact Object Headers (rec:)", "Raft Sync", "/api/model/records/*", "ACTIVE"}
        };

        List<List<Widget>> rows = new ArrayList<>();
        for (String[] rowData : matrixData) {
            Widget nameCell = Div.of(
                Icon.of(rowData[0]).modifier(new Modifier().style("color:" + rowData[1] + "; margin-right:6px;")),
                Span.of(rowData[2]).modifier(new Modifier().style("font-weight:bold;"))
            );
            Widget useCaseCell = Text.of(rowData[3]);
            Widget schemaCell = Text.of(rowData[4]);
            Widget replCell = Text.of(rowData[5]);
            Widget routeCell = RawHtml.of("<code>" + rowData[6] + "</code>");
            Widget statusCell = Span.of(rowData[7]).modifier(new Modifier().cssClass("store-badge badge-active"));

            rows.add(List.of(nameCell, useCaseCell, schemaCell, replCell, routeCell, statusCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget tableResponsive = Div.of(datatable).modifier(new Modifier().cssClass("table-responsive"));

        return Div.of(header, tableResponsive).modifier(new Modifier().cssClass("store-card"));
    }
}
