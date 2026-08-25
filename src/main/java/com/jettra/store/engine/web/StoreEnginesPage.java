package com.jettra.store.engine.web;

import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.models.*;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.json.JsonArray;
import io.jettra.server.JettraServer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
                    String newDb = params.get("new_db_name");
                    if (newDb == null || newDb.isBlank()) newDb = params.get("target_db");
                    if (newDb != null && !newDb.isBlank()) {
                        String cleanDb = newDb.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        String initEngine = params.getOrDefault("initial_engine", selectedEngine);
                        String initUnit = params.getOrDefault("initial_unit", "default");
                        String initId = params.getOrDefault("initial_id", "init_01");
                        String prefix = getPrefixForEngine(initEngine);
                        String internalKey = prefix + cleanDb + ":" + initUnit + ":" + initId;
                        JsonObject initDoc = new JsonObject();
                        initDoc.addProperty("_database", cleanDb);
                        initDoc.addProperty("_engine", initEngine);
                        initDoc.addProperty("_unit", initUnit);
                        initDoc.addProperty("status", "ACTIVE");
                        initDoc.addProperty("createdAt", System.currentTimeMillis());
                        engine.getStorageCore().put(internalKey, initDoc.toString().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        targetDb = cleanDb;
                        selectedEngine = initEngine;
                        alertMessage = "Database '" + cleanDb + "' successfully created with initial [" + initEngine + "] unit '" + initUnit + "'!";
                        alertType = "badge-active";
                    }
                } else if ("create_unit".equalsIgnoreCase(action)) {
                    String unitName = params.get("unit_name");
                    String engType = params.getOrDefault("engine_type", selectedEngine);
                    if (unitName != null && !unitName.isBlank()) {
                        String cleanUnit = unitName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        String prefix = getPrefixForEngine(engType);
                        String internalKey = prefix + targetDb + ":" + cleanUnit + ":init_01";
                        JsonObject initDoc = new JsonObject();
                        initDoc.addProperty("_database", targetDb);
                        initDoc.addProperty("_engine", engType);
                        initDoc.addProperty("_unit", cleanUnit);
                        initDoc.addProperty("status", "ACTIVE");
                        initDoc.addProperty("createdAt", System.currentTimeMillis());
                        engine.getStorageCore().put(internalKey, initDoc.toString().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Subtree Unit '" + cleanUnit + "' successfully added to " + engType + " engine in database '" + targetDb + "'!";
                        alertType = "badge-active";
                    }
                } else if ("drop_db".equalsIgnoreCase(action)) {
                    String dbToDrop = params.get("db_to_drop");
                    if (dbToDrop != null && !dbToDrop.isBlank()) {
                        String[] pfxs = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
                        int purged = 0;
                        for (String pfx : pfxs) {
                            String dbPfx = pfx + dbToDrop.trim() + ":";
                            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPfx);
                            for (String k : keys.keySet()) {
                                engine.getStorageCore().delete(k, System.currentTimeMillis());
                                purged++;
                            }
                        }
                        alertMessage = "Database '" + dbToDrop + "' dropped (" + purged + " keys purged).";
                        alertType = "badge-raft";
                        targetDb = "ecommerce_db";
                    }
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
                } else if ("edit_document".equalsIgnoreCase(action) || "edit_object".equalsIgnoreCase(action) || "edit_record".equalsIgnoreCase(action)) {
                    String engType = params.getOrDefault("engine_type", selectedEngine);
                    String coll = params.getOrDefault("target_coll", params.getOrDefault("coll", "default"));
                    String rawPayload = params.getOrDefault("record_payload", params.getOrDefault("doc_payload", "{}"));
                    executeTypeSpecificEdit(engType, targetDb, targetId, coll, rawPayload, params);
                    alertMessage = "[" + engType + "] Record '" + targetId + "' updated successfully (new version created)!";
                    alertType = "badge-active";
                } else if ("restore_version".equalsIgnoreCase(action)) {
                    long targetTs = Long.parseLong(params.getOrDefault("version_ts", "0"));
                    String engType = params.getOrDefault("engine_type", selectedEngine);
                    String coll = params.getOrDefault("target_coll", params.getOrDefault("coll", "default"));
                    boolean restored = false;
                    if (targetTs > 0) {
                        String prefix = getPrefixForEngine(engType);
                        String[] candidateKeys = {
                            prefix + targetDb + ":" + coll + ":" + targetId,
                            prefix + targetDb + ":" + targetId,
                            targetDb + ":" + coll + ":" + targetId,
                            targetDb + ":" + targetId
                        };
                        for (String k : candidateKeys) {
                            if (engine.getStorageCore().restoreVersion(k, targetTs)) {
                                restored = true;
                                break;
                            }
                        }
                    }
                    if (restored) {
                        alertMessage = "[" + engType + "] Record '" + targetId + "' successfully restored to version snapshot from timestamp " + targetTs + "!";
                    } else {
                        alertMessage = "Restored [" + engType + "] record '" + targetId + "' with timestamp " + targetTs;
                    }
                    alertType = "badge-active";
                } else if ("create_collection".equalsIgnoreCase(action)) {
                    String newColl = params.get("collection_name");
                    if (newColl != null && !newColl.isBlank()) {
                        String cleanColl = newColl.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                        if (de != null) {
                            JsonObject initDoc = new JsonObject();
                            initDoc.addProperty("_collection", cleanColl);
                            initDoc.addProperty("status", "ACTIVE");
                            initDoc.addProperty("createdAt", System.currentTimeMillis());
                            de.insert(targetDb, cleanColl, "init_01", initDoc);
                            alertMessage = "Collection '" + cleanColl + "' initialized in database '" + targetDb + "'!";
                            alertType = "badge-active";
                        }
                    }
                } else if ("create_index".equalsIgnoreCase(action)) {
                    String indexName = params.get("index_name");
                    String fieldName = params.get("index_field");
                    String indexType = params.getOrDefault("index_type", "BTREE");
                    String coll = params.getOrDefault("target_coll", "default");
                    if (indexName != null && !indexName.isBlank()) {
                        JsonObject idxJson = new JsonObject();
                        idxJson.addProperty("name", indexName.trim());
                        idxJson.addProperty("field", fieldName != null && !fieldName.isBlank() ? fieldName.trim() : "id");
                        idxJson.addProperty("type", indexType);
                        idxJson.addProperty("collection", coll);
                        idxJson.addProperty("createdAt", System.currentTimeMillis());
                        engine.getStorageCore().put("idx:" + targetDb + ":" + indexName.trim(), idxJson.toString().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Index '" + indexName + "' (" + indexType + ") on field '" + fieldName + "' created for database '" + targetDb + "'!";
                        alertType = "badge-active";
                    }
                } else if ("delete_index".equalsIgnoreCase(action)) {
                    String indexName = params.get("index_name");
                    if (indexName != null) {
                        engine.getStorageCore().delete("idx:" + targetDb + ":" + indexName, System.currentTimeMillis());
                        alertMessage = "Index '" + indexName + "' deleted from database '" + targetDb + "'!";
                        alertType = "badge-raft";
                    }
                } else if ("save_schema".equalsIgnoreCase(action)) {
                    String schemaName = params.get("schema_name");
                    String schemaJson = params.get("schema_json");
                    if (schemaName != null && !schemaName.isBlank()) {
                        JsonObject sc = new JsonObject();
                        sc.addProperty("name", schemaName.trim());
                        sc.addProperty("schema", schemaJson != null ? schemaJson : "{}");
                        sc.addProperty("createdAt", System.currentTimeMillis());
                        engine.getStorageCore().put("schema:" + targetDb + ":" + schemaName.trim(), sc.toString().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Schema definition '" + schemaName + "' registered and active for '" + targetDb + "'!";
                        alertType = "badge-active";
                    }
                } else if ("delete_schema".equalsIgnoreCase(action)) {
                    String schemaName = params.get("schema_name");
                    if (schemaName != null) {
                        engine.getStorageCore().delete("schema:" + targetDb + ":" + schemaName, System.currentTimeMillis());
                        alertMessage = "Schema '" + schemaName + "' deleted from database '" + targetDb + "'!";
                        alertType = "badge-raft";
                    }
                } else if ("delete_object".equalsIgnoreCase(action)) {
                    String engType = params.getOrDefault("engine_type", selectedEngine);
                    String coll = params.getOrDefault("target_coll", params.getOrDefault("coll", "default"));
                    executeTypeSpecificDelete(engType, targetDb, targetId, coll, params);
                    alertMessage = "[" + engType + "] Object '" + targetId + "' successfully deleted from [" + targetDb + "]!";
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

        // Document Collections Management Section (if DOCUMENT engine selected)
        String currentCollection = params != null && params.containsKey("coll") ? params.get("coll") : "default";

        // Hierarchical Tree View (JettraFlux Tree Component across all databases & engines)
        Widget hierarchyTreeCard = createHierarchyTreeCard(selectedEngine, targetDb, currentCollection);

        // Modals for Advanced Search, Document Edit, Version Recovery, Indexes and Schemas
        Widget modalsWidget = createEngineModals(selectedEngine, targetDb, currentCollection);

        return Column.of(
            titleBlock,
            alertWidget,
            hierarchyTreeCard,
            modalsWidget
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
        String rawMode = params.getOrDefault("id_gen_mode", "UUID");
        IdGenerator.IdMode idMode = IdGenerator.IdMode.fromString(rawMode);
        String manualId = params.get("target_id");
        String targetColl = params.getOrDefault("target_coll", "default");
        String targetId = IdGenerator.generateId(db + ":" + targetColl, idMode, manualId);

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
                    docEngine.insert(db, targetColl, targetId, doc);
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) {
                    String value = params.getOrDefault("kv_value", "");
                    String resolvedKey = (targetColl.equals("default") || targetId.contains(":")) ? targetId : targetColl + ":" + targetId;
                    kvEngine.put(db, resolvedKey, value);
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
                    meta.addProperty("_index", targetColl);
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
                        String label = params.getOrDefault("edge_label", targetColl.equals("default") ? "CONNECTED_TO" : targetColl);
                        String edgeProps = params.getOrDefault("edge_props", "{}");
                        graphEngine.addEdge(db, from, to, label, parseJsonOrWrap(edgeProps));
                    } else {
                        String nodeLabel = params.getOrDefault("node_label", targetColl.equals("default") ? "Vertex" : targetColl);
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
                    dp.addProperty("metric", targetColl);
                    if (!unit.isBlank()) dp.addProperty("unit", unit);
                    tsEngine.insert(db, timestamp, dp);
                }
            }
            case "COLUMN" -> {
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) {
                    String colData = params.getOrDefault("col_data", "{}");
                    JsonObject row = parseJsonOrColumns(colData);
                    row.addProperty("_family", targetColl);
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
                    meta.addProperty("_layer", targetColl);
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
                    state.addProperty("bucket", targetColl);
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
                    comps.addProperty("_table", targetColl);
                    recEngine.saveRecord(db, targetId, recordClass, comps);
                }
            }
        }
    }

    private void executeTypeSpecificEdit(String engineName, String db, String id, String coll, String payload, Map<String, String> params) {
        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    String json = params.getOrDefault("doc_payload", payload);
                    JsonObject doc = parseJsonOrWrap(json);
                    String docClass = params.get("doc_class");
                    if (docClass != null && !docClass.isBlank()) doc.addProperty("_class", docClass.trim());
                    de.insert(db, coll != null && !coll.isBlank() ? coll : "default", id, doc);
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    String val = params.getOrDefault("kv_value", payload);
                    String resolvedKey = (coll == null || coll.equals("default") || id.contains(":")) ? id : coll + ":" + id;
                    ke.put(db, resolvedKey, val);
                }
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) {
                    float[] coords = new float[]{0.12f, 0.45f, 0.88f, 0.31f};
                    if (params.containsKey("vector_coords") && !params.get("vector_coords").isBlank()) {
                        coords = parseFloats(params.get("vector_coords"));
                    }
                    String metaStr = params.getOrDefault("vector_meta", payload);
                    JsonObject vecObj = parseJsonOrWrap(metaStr);
                    vecObj.addProperty("_index", coll != null ? coll : "default");
                    ve.insertVector(db, id, coords, vecObj);
                }
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) {
                    String nodeProps = params.getOrDefault("node_props", payload);
                    JsonObject gObj = parseJsonOrWrap(nodeProps);
                    String nodeLabel = params.getOrDefault("node_label", coll != null && !coll.isBlank() ? coll : "Vertex");
                    gObj.addProperty("label", nodeLabel);
                    ge.addNode(db, id, gObj);
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    long ts = System.currentTimeMillis();
                    String rawTs = params.get("ts_timestamp");
                    if (rawTs != null && !rawTs.isBlank()) {
                        try { ts = Long.parseLong(rawTs.trim()); } catch (Exception ignored) {}
                    } else {
                        try { ts = Long.parseLong(id); } catch (Exception ignored) {}
                    }
                    String tagsStr = params.getOrDefault("ts_tags", payload);
                    JsonObject tsObj = parseJsonOrWrap(tagsStr);
                    if (params.containsKey("ts_value")) {
                        try { tsObj.addProperty("value", Double.parseDouble(params.get("ts_value"))); } catch (Exception ignored) {}
                    }
                    if (params.containsKey("ts_unit") && !params.get("ts_unit").isBlank()) {
                        tsObj.addProperty("unit", params.get("ts_unit"));
                    }
                    tsObj.addProperty("metric", coll != null ? coll : "telemetry");
                    te.insert(db, ts, tsObj);
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) {
                    String colData = params.getOrDefault("col_data", payload);
                    JsonObject colObj = parseJsonOrColumns(colData);
                    colObj.addProperty("_family", coll != null ? coll : "analytics");
                    ce.insertRow(db, id, colObj);
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (ge != null) {
                    double lat = 8.9824;
                    double lon = -79.5199;
                    if (params.containsKey("geo_lat")) {
                        try { lat = Double.parseDouble(params.get("geo_lat")); } catch (Exception ignored) {}
                    }
                    if (params.containsKey("geo_lon")) {
                        try { lon = Double.parseDouble(params.get("geo_lon")); } catch (Exception ignored) {}
                    }
                    String name = params.getOrDefault("geo_name", id);
                    JsonObject geoObj = parseJsonOrWrap(payload);
                    geoObj.addProperty("name", name);
                    geoObj.addProperty("_layer", coll != null ? coll : "stores_layer");
                    ge.insertLocation(db, id, lat, lon, geoObj);
                }
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) {
                    String objMime = params.getOrDefault("obj_mime", "application/json");
                    String objPayload = params.getOrDefault("obj_payload", payload);
                    JsonObject state = new JsonObject();
                    state.addProperty("mimeType", objMime);
                    state.addProperty("bucket", coll != null ? coll : "media_bucket");
                    state.addProperty("sizeBytes", objPayload.getBytes(StandardCharsets.UTF_8).length);
                    state.addProperty("content", objPayload);
                    oe.saveObject(db, id, "GenericBlob", state);
                }
            }
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) {
                    String recClass = params.getOrDefault("rec_class", "com.jettra.model.PersonRecord");
                    String recPayload = params.getOrDefault("rec_payload", payload);
                    JsonObject recObj = parseJsonOrWrap(recPayload);
                    recObj.addProperty("_table", coll != null ? coll : "default");
                    re.saveRecord(db, id, recClass, recObj);
                }
            }
        }
    }

    private String getVersionsJson(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id
        };

        List<LsmBTreeHybrid.RecordVersion> history = new ArrayList<>();
        for (String k : candidateKeys) {
            history = engine.getStorageCore().getVersionHistory(k);
            if (!history.isEmpty()) break;
        }

        JsonArray vArr = new JsonArray();
        for (LsmBTreeHybrid.RecordVersion v : history) {
            JsonObject vo = new JsonObject();
            vo.addProperty("versionNumber", "v" + v.versionNumber());
            vo.addProperty("timestamp", v.timestamp());
            vo.addProperty("formattedDate", v.formattedDate());
            vo.addProperty("isCurrent", v.isCurrent());
            String pShort = v.payload();
            if (pShort != null && pShort.length() > 80) pShort = pShort.substring(0, 80) + "...";
            vo.addProperty("preview", pShort != null ? pShort : "{}");
            vArr.add(vo);
        }
        return vArr.toString();
    }

    private String getItemPayload(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id
        };

        for (String k : candidateKeys) {
            byte[] b = engine.getStorageCore().get(k);
            if (b != null && b.length > 0) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
        return "{}";
    }

    private int getItemVersionCount(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id
        };

        int max = 1;
        for (String k : candidateKeys) {
            int c = engine.getStorageCore().getVersionCount(k);
            if (c > max) max = c;
        }
        return max;
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

    private void executeTypeSpecificDelete(String engineName, String db, String id, String coll, Map<String, String> params) {
        String prefix = getPrefixForEngine(engineName);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id
        };
        for (String k : candidateKeys) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
        }

        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    if (coll != null && !coll.isBlank() && !coll.equals("default")) {
                        de.delete(db, coll, id);
                    } else {
                        de.delete(db, id);
                    }
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    if (coll != null && !coll.isBlank() && !coll.equals("default")) {
                        ke.delete(db, coll + ":" + id);
                    }
                    ke.delete(db, id);
                }
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) {
                    ve.deleteVector(coll != null ? coll : "default", id);
                    ve.deleteVector(db, id);
                }
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) ge.deleteNode(db, id);
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    try { te.delete(coll != null ? coll : "telemetry", Long.parseLong(id)); } catch (Exception ignored) {}
                    try { te.delete(db, Long.parseLong(id)); } catch (Exception ignored) {}
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) {
                    ce.deleteRow(coll != null ? coll : "analytics", id);
                    ce.deleteRow(db, id);
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (ge != null) {
                    ge.deleteLocation(coll != null ? coll : "stores_layer", id);
                    ge.deleteLocation(db, id);
                }
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) {
                    oe.deleteObject(coll != null ? coll : "media_bucket", id);
                    oe.deleteObject(db, id);
                }
            }
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) {
                    re.deleteRecord(coll != null ? coll : "default", id);
                    re.deleteRecord(db, id);
                }
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

    private String getPrefixForEngine(String engineKey) {
        if (engineKey == null) return "doc:";
        return switch (engineKey.toUpperCase()) {
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
    }

    private Set<String> discoverAllDatabases() {
        Set<String> discovered = new TreeSet<>();
        discovered.add("ecommerce_db");
        discovered.add("customers_db");
        discovered.add("records_db");
        discovered.add("ai_search_db");
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
            for (String k : keys.keySet()) {
                String rest = k.substring(p.length());
                int idx = rest.indexOf(':');
                if (idx > 0) {
                    discovered.add(rest.substring(0, idx));
                }
            }
        }
        return discovered;
    }

    private Map<String, JsonObject> discoverIndexes(String dbName) {
        Map<String, JsonObject> indexes = new TreeMap<>();
        String prefix = "idx:" + dbName + ":";
        Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(prefix);
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            String k = e.getKey();
            if (!k.contains("@")) {
                String idxName = k.substring(prefix.length());
                if (!idxName.isBlank()) {
                    String val = new String(e.getValue(), StandardCharsets.UTF_8);
                    indexes.put(idxName, parseJsonOrWrap(val));
                }
            }
        }
        if (indexes.isEmpty()) {
            JsonObject defIdx = new JsonObject();
            defIdx.addProperty("name", "idx_primary_id");
            defIdx.addProperty("field", "_id");
            defIdx.addProperty("type", "BTREE");
            defIdx.addProperty("collection", "default");
            indexes.put("idx_primary_id", defIdx);
        }
        return indexes;
    }

    private Map<String, JsonObject> discoverSchemas(String dbName) {
        Map<String, JsonObject> schemas = new TreeMap<>();
        String prefix = "schema:" + dbName + ":";
        Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(prefix);
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            String k = e.getKey();
            if (!k.contains("@")) {
                String scName = k.substring(prefix.length());
                if (!scName.isBlank()) {
                    String val = new String(e.getValue(), StandardCharsets.UTF_8);
                    schemas.put(scName, parseJsonOrWrap(val));
                }
            }
        }
        return schemas;
    }

    private Map<String, List<String>> discoverUnitsAndItems(String engineKey, String dbName) {
        Map<String, List<String>> unitMap = new TreeMap<>();
        String prefix = getPrefixForEngine(engineKey) + dbName + ":";
        Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(prefix);
        
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            String k = e.getKey();
            if (!k.contains("@")) {
                String rest = k.substring(prefix.length());
                int idx = rest.indexOf(':');
                if (idx > 0) {
                    String unit = rest.substring(0, idx);
                    String itemId = rest.substring(idx + 1);
                    if (!itemId.isBlank() && !itemId.equals("init_01")) {
                        unitMap.computeIfAbsent(unit, u -> new ArrayList<>()).add(itemId);
                    } else if (itemId.equals("init_01")) {
                        unitMap.computeIfAbsent(unit, u -> new ArrayList<>());
                    }
                } else if (!rest.isBlank() && !rest.equals("init_01")) {
                    unitMap.computeIfAbsent("default", u -> new ArrayList<>()).add(rest);
                }
            }
        }

        if ("DOCUMENT".equalsIgnoreCase(engineKey)) {
            String docPrefix = dbName + ":";
            Map<String, byte[]> docKeys = engine.getStorageCore().scanPrefix(docPrefix);
            for (Map.Entry<String, byte[]> e : docKeys.entrySet()) {
                String k = e.getKey();
                if (!k.contains("@")) {
                    String rest = k.substring(docPrefix.length());
                    int idx = rest.indexOf(':');
                    if (idx > 0) {
                        String unit = rest.substring(0, idx);
                        String itemId = rest.substring(idx + 1);
                        if (!itemId.isBlank() && !itemId.equals("init_01")) {
                            List<String> list = unitMap.computeIfAbsent(unit, u -> new ArrayList<>());
                            if (!list.contains(itemId)) list.add(itemId);
                        }
                    } else if (!rest.isBlank() && !rest.equals("init_01")) {
                        List<String> list = unitMap.computeIfAbsent("default", u -> new ArrayList<>());
                        if (!list.contains(rest)) list.add(rest);
                    }
                }
            }
        }

        if (unitMap.isEmpty()) {
            unitMap.put("default", new ArrayList<>());
        }
        return unitMap;
    }

    private Widget createHierarchyTreeCard(String selectedEngine, String targetDb, String currentColl) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=");
        Set<String> allDbs = discoverAllDatabases();
        if (!allDbs.contains(targetDb)) {
            allDbs.add(targetDb);
        }

        Widget treeHeader = Row.of(
            Header.of(3,
                Icon.of("fas fa-sitemap").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Text.of("Multi-Model Storage Hierarchy Explorer (Databases → Engine Subtrees → Units → Items)")
            ).modifier(new Modifier().style("margin:0; font-size:16px; font-weight:600;")),
            Row.of(
                Button.of(Icon.of("fas fa-database"), Text.of(" + Add Database"))
                    .attribute("onclick", "document.getElementById('createDbModal').style.display='flex'")
                    .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:4px 10px; font-size:12px; margin-right:6px;")),
                Button.of(Icon.of("fas fa-folder-plus"), Text.of(" + Add Subtree Unit"))
                    .attribute("onclick", "document.getElementById('createUnitModal').style.display='flex'")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:4px 10px; font-size:12px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;"))
        ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:8px;"));

        String[][] allEngSpecs = {
            {"DOCUMENT", "#3b82f6", "fas fa-file-alt", "Collections", "Collection", "Document", "fas fa-file-code"},
            {"KEYVALUE", "#10b981", "fas fa-key", "Namespaces / Buckets", "Bucket", "Key-Value Pair", "fas fa-cube"},
            {"VECTOR", "#8b5cf6", "fas fa-project-diagram", "Vector Indexes", "Vector Index", "Vector Embedding", "fas fa-braille"},
            {"GRAPH", "#ec4899", "fas fa-share-alt", "Node & Edge Labels", "Label", "Vertex / Edge", "fas fa-circle-nodes"},
            {"TIMESERIES", "#06b6d4", "fas fa-chart-line", "Metrics / Telemetry", "Metric", "Time Point", "fas fa-stopwatch"},
            {"COLUMN", "#f97316", "fas fa-table", "Column Families", "Column Family", "Dynamic Row", "fas fa-bars-staggered"},
            {"GEOSPATIAL", "#14b8a6", "fas fa-globe-americas", "Spatial Layers", "Spatial Layer", "GIS Feature", "fas fa-location-dot"},
            {"OBJECT", "#a855f7", "fas fa-archive", "Storage Buckets", "Storage Bucket", "BLOB Object", "fas fa-box-archive"},
            {"RECORDS", "#f43f5e", "fas fa-id-card", "Record Tables", "Record Table", "Immutable Record", "fas fa-address-card"}
        };

        List<Widget> dbCardWidgets = new ArrayList<>();

        for (String db : allDbs) {
            boolean isActiveDb = db.equalsIgnoreCase(targetDb);

            Widget dbLeft = Span.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:6px; color:#38bdf8;")),
                Text.of("[Level 1: Database Container] " + db)
            ).modifier(new Modifier().style("color:" + (isActiveDb ? "#38bdf8" : "#cbd5e1") + "; font-weight:700; font-size:14px;"));

            List<Widget> dbRightWidgets = new ArrayList<>();
            if (isActiveDb) {
                dbRightWidgets.add(Span.of("ACTIVE (CURRENT)").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px; margin-left:6px;")));
            } else {
                dbRightWidgets.add(Span.of("DATABASE").modifier(new Modifier().cssClass("store-badge").style("font-size:10px; margin-left:6px; background:rgba(255,255,255,0.08);")));
                dbRightWidgets.add(Link.of(actionUrl + selectedEngine + "&target_db=" + db, "[Explore DB]").modifier(new Modifier().style("color:#38bdf8; font-size:11px; margin-left:8px; text-decoration:none; font-weight:600;")));
            }
            Widget dbRight = Div.of(dbRightWidgets.toArray(new Widget[0]));

            Widget dbHeaderRow = Div.of(dbLeft, dbRight)
                .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

            List<Widget> dbContentWidgets = new ArrayList<>();
            dbContentWidgets.add(dbHeaderRow);

            if (isActiveDb) {
                List<Widget> engineSubtreeWidgets = new ArrayList<>();

                for (String[] spec : allEngSpecs) {
                    String engName = spec[0];
                    String engColor = spec[1];
                    String engIcon = spec[2];
                    String unitPlural = spec[3];
                    String unitSingle = spec[4];
                    String itemLabel = spec[5];
                    String itemIcon = spec[6];
                    boolean isEngActive = engName.equalsIgnoreCase(selectedEngine);

                    Map<String, List<String>> unitsAndItems = discoverUnitsAndItems(engName, db);
                    int totalItems = unitsAndItems.values().stream().mapToInt(List::size).sum();

                    Widget engHeaderLink = Link.of(actionUrl + engName + "&target_db=" + db,
                        Icon.of(engIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:6px;")),
                        Span.of(" " + engName + " SUBTREE").modifier(new Modifier().style("font-weight:bold;")),
                        Text.of(" → "),
                        Span.of(unitPlural + " (" + unitsAndItems.size() + " " + (unitsAndItems.size() == 1 ? unitSingle : unitPlural) + ", " + totalItems + " items)").modifier(new Modifier().style("color:#cbd5e1;"))
                    ).modifier(new Modifier().style("text-decoration:none; color:" + (isEngActive ? "#38bdf8; font-weight:bold;" : "#94a3b8;") + ";"));

                    Widget engAddUnitBtn = Button.of("+ " + unitSingle)
                        .attribute("onclick", "openAddUnitModal('" + engName + "', '" + unitSingle + "')")
                        .modifier(new Modifier().style("background:none; border:1px solid " + engColor + "55; color:" + engColor + "; font-size:10px; padding:2px 8px; border-radius:4px; cursor:pointer;"));

                    Widget engHeaderRow = Div.of(engHeaderLink, engAddUnitBtn)
                        .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                    List<Widget> unitListWidgets = new ArrayList<>();

                    for (Map.Entry<String, List<String>> unitEntry : unitsAndItems.entrySet()) {
                        String unitName = unitEntry.getKey();
                        List<String> items = unitEntry.getValue();
                        boolean isCurrColl = isEngActive && unitName.equalsIgnoreCase(currentColl);

                        Widget unitLeft = Span.of(
                            Text.of("📁 [Level 2: Unit / " + unitSingle + "] "),
                            Link.of(actionUrl + engName + "&target_db=" + db + "&coll=" + unitName, unitName).modifier(new Modifier().style("color:inherit; text-decoration:none;")),
                            Text.of(" "),
                            Span.of("(" + items.size() + " items)").modifier(new Modifier().style("font-size:10px; color:#64748b; font-weight:normal;"))
                        ).modifier(new Modifier().style("color:" + (isCurrColl ? "#38bdf8" : "#cbd5e1") + "; font-size:12px; font-weight:600;"));

                        Widget unitAddObjBtn = Button.of("[+ Add " + itemLabel + "]")
                            .attribute("onclick", "openAddObjectModal('" + engName + "', '" + unitName + "')")
                            .modifier(new Modifier().style("background:none; border:none; color:" + engColor + "; font-size:10px; cursor:pointer;"));

                        Widget unitHeaderRow = Div.of(unitLeft, unitAddObjBtn)
                            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                        List<Widget> itemWidgets = new ArrayList<>();
                        if (items.isEmpty()) {
                            Widget emptyItem = Div.of(
                                Span.of("└── "),
                                Span.of("(Empty unit - click [+ Add " + itemLabel + "] to insert)").modifier(new Modifier().style("font-style:italic;"))
                            ).modifier(new Modifier().style("font-size:11px; color:#64748b; padding:2px 0;"));
                            itemWidgets.add(emptyItem);
                        } else {
                            for (String itemId : items) {
                                int vCount = getItemVersionCount(engName, db, unitName, itemId);
                                String itemPayload = getItemPayload(engName, db, unitName, itemId);
                                String itemVersions = getVersionsJson(engName, db, unitName, itemId);
                                String payloadB64 = Base64.getEncoder().encodeToString(itemPayload.getBytes(StandardCharsets.UTF_8));
                                String versionsB64 = Base64.getEncoder().encodeToString(itemVersions.getBytes(StandardCharsets.UTF_8));

                                Widget itemLeft = Span.of(
                                    Text.of("└── "),
                                    Icon.of(itemIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:4px;")),
                                    Text.of("[Level 3: " + itemLabel + "] "),
                                    Span.of(itemId).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;")),
                                    Text.of(" "),
                                    Span.of("v" + vCount).modifier(new Modifier().cssClass("store-badge").style("background:rgba(56,189,248,0.15); color:#38bdf8; font-size:9px; padding:1px 5px;"))
                                );

                                List<Widget> itemBtnWidgets = new ArrayList<>();
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-edit"), Text.of(" Edit"))
                                        .attribute("type", "button")
                                        .attribute("onclick", "openUniversalEditModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "', '" + payloadB64 + "')")
                                        .modifier(new Modifier().style("background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:10px; padding:1px 6px; border-radius:3px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-history"), Text.of(" v" + vCount))
                                        .attribute("type", "button")
                                        .attribute("onclick", "openUniversalRestoreModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "', '" + versionsB64 + "')")
                                        .modifier(new Modifier().style("background:none; border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:10px; padding:1px 6px; border-radius:3px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-trash-alt"), Text.of(" Delete"))
                                        .attribute("type", "button")
                                        .attribute("onclick", "openUniversalDeleteModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "')")
                                        .attribute("title", "Delete record")
                                        .modifier(new Modifier().style("background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:10px; padding:1px 6px; border-radius:3px; cursor:pointer;"))
                                );

                                if ("DOCUMENT".equalsIgnoreCase(engName)) {
                                    itemBtnWidgets.add(Link.of(actionUrl + engName + "&target_db=" + db + "&coll=" + unitName + "&target_id=" + itemId, "[Select]").modifier(new Modifier().style("color:#94a3b8; text-decoration:none; font-size:10px; margin-left:2px;")));
                                } else {
                                    itemBtnWidgets.add(Link.of(actionUrl + engName + "&target_db=" + db + "&target_id=" + itemId, "[Inspect]").modifier(new Modifier().style("color:#94a3b8; text-decoration:none; font-size:10px; margin-left:2px;")));
                                }

                                Widget itemRight = Div.of(itemBtnWidgets.toArray(new Widget[0]))
                                    .modifier(new Modifier().style("display:flex; align-items:center; gap:4px;"));

                                Widget itemRow = Div.of(itemLeft, itemRight)
                                    .modifier(new Modifier().style("font-size:11px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:2px 0;"));

                                itemWidgets.add(itemRow);
                            }
                        }

                        Widget itemsContainer = Div.of(itemWidgets.toArray(new Widget[0]))
                            .modifier(new Modifier().style("margin-left:16px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:10px; margin-top:3px;"));

                        Widget unitBlock = Div.of(unitHeaderRow, itemsContainer)
                            .modifier(new Modifier().style("margin-bottom:8px; margin-top:4px;"));

                        unitListWidgets.add(unitBlock);
                    }

                    Widget unitSubtreeContainer = Div.of(unitListWidgets.toArray(new Widget[0]))
                        .modifier(new Modifier().style("margin-left:18px; border-left: 2px dotted rgba(255,255,255,0.12); padding-left:12px; margin-top:6px;"));

                    Widget engineBlock = Div.of(engHeaderRow, unitSubtreeContainer)
                        .modifier(new Modifier().style("margin-bottom:12px; background:" + (isEngActive ? "rgba(30,41,59,0.7)" : "rgba(15,23,42,0.3)") + "; padding:8px 10px; border-radius:6px; border:1px solid rgba(255,255,255,0.04);"));

                    engineSubtreeWidgets.add(engineBlock);
                }

                // Render Indexes & Schemas Subtree for this Database
                Map<String, JsonObject> dbIndexes = discoverIndexes(db);
                Map<String, JsonObject> dbSchemas = discoverSchemas(db);

                Widget idxSchemasHeaderLeft = Span.of(
                    Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:6px;")),
                    Span.of("INDEXES & SCHEMAS SUBTREE").modifier(new Modifier().style("font-weight:bold;")),
                    Text.of(" → "),
                    Span.of("Index Registry & Validation Schemas (" + dbIndexes.size() + " Indexes, " + dbSchemas.size() + " Schemas)").modifier(new Modifier().style("color:#cbd5e1;"))
                ).modifier(new Modifier().style("color:#eab308; font-weight:bold;"));

                Widget idxSchemasHeaderRight = Div.of(
                    Button.of(Icon.of("fas fa-plus"), Text.of(" Add Index"))
                        .attribute("type", "button")
                        .attribute("onclick", "openAddIndexModal('" + db + "')")
                        .modifier(new Modifier().style("background:none; border:1px solid rgba(234,179,8,0.5); color:#eab308; font-size:10px; padding:2px 8px; border-radius:4px; cursor:pointer; margin-right:4px;")),
                    Button.of(Icon.of("fas fa-shield-alt"), Text.of(" Add Schema"))
                        .attribute("type", "button")
                        .attribute("onclick", "openAddSchemaModal('" + db + "')")
                        .modifier(new Modifier().style("background:none; border:1px solid rgba(56,189,248,0.5); color:#38bdf8; font-size:10px; padding:2px 8px; border-radius:4px; cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; gap:4px;"));

                Widget idxSchemasHeaderRow = Div.of(idxSchemasHeaderLeft, idxSchemasHeaderRight)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                // Level 2: Secondary & Composite Indexes Unit
                Widget indexesUnitLeft = Span.of(
                    Text.of("📁 [Level 2: Unit / Index Family] Secondary & Composite Indexes "),
                    Span.of("(" + dbIndexes.size() + " items)").modifier(new Modifier().style("font-size:10px; color:#64748b; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#fde047; font-size:12px; font-weight:600;"));

                Widget indexesUnitAddBtn = Button.of("[+ Add Index]")
                    .attribute("type", "button")
                    .attribute("onclick", "openAddIndexModal('" + db + "')")
                    .modifier(new Modifier().style("background:none; border:none; color:#eab308; font-size:10px; cursor:pointer;"));

                Widget indexesUnitHeaderRow = Div.of(indexesUnitLeft, indexesUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> indexItemWidgets = new ArrayList<>();
                if (dbIndexes.isEmpty()) {
                    indexItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No secondary indexes - click [+ Add Index] to create)").modifier(new Modifier().style("font-style:italic;"))
                        ).modifier(new Modifier().style("font-size:11px; color:#64748b; padding:2px 0;"))
                    );
                } else {
                    for (Map.Entry<String, JsonObject> idxEntry : dbIndexes.entrySet()) {
                        String idxName = idxEntry.getKey();
                        JsonObject idxObj = idxEntry.getValue();
                        String idxType = idxObj.has("type") && idxObj.get("type") != null ? idxObj.get("type").toString().replace("\"", "") : "BTREE";
                        String idxField = idxObj.has("field") && idxObj.get("field") != null ? idxObj.get("field").toString().replace("\"", "") : "id";
                        String idxColl = idxObj.has("collection") && idxObj.get("collection") != null ? idxObj.get("collection").toString().replace("\"", "") : "default";

                        Widget idxItemLeft = Span.of(
                            Text.of("└── "),
                            Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:4px;")),
                            Text.of("[Level 3: Index] "),
                            Span.of(idxName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;")),
                            Text.of(" "),
                            Span.of(idxType).modifier(new Modifier().cssClass("store-badge").style("background:rgba(234,179,8,0.15); color:#fde047; font-size:9px; padding:1px 5px;")),
                            Text.of(" on field '"),
                            Span.of(idxField).modifier(new Modifier().style("color:#38bdf8; font-family:monospace;")),
                            Text.of("' (unit: " + idxColl + ")")
                        );

                        Widget deleteIdxForm = Form.of(
                            InputHidden.of("action", "delete_index"),
                            InputHidden.of("target_db", db),
                            InputHidden.of("index_name", idxName),
                            Button.of(Icon.of("fas fa-trash"))
                                .attribute("type", "submit")
                                .attribute("onclick", "return confirm('Delete index " + idxName + "?');")
                                .modifier(new Modifier().style("background:none; border:none; color:#ef4444; font-size:10px; cursor:pointer;"))
                        ).action(actionUrl + selectedEngine).method("POST").modifier(new Modifier().style("display:inline; margin:0;"));

                        Widget idxItemRow = Div.of(idxItemLeft, deleteIdxForm)
                            .modifier(new Modifier().style("font-size:11px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:2px 0;"));

                        indexItemWidgets.add(idxItemRow);
                    }
                }

                Widget indexItemsContainer = Div.of(indexItemWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:16px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:10px; margin-top:3px;"));

                Widget indexesUnitBlock = Div.of(indexesUnitHeaderRow, indexItemsContainer)
                    .modifier(new Modifier().style("margin-bottom:8px; margin-top:4px;"));

                // Level 2: Validation Schemas Unit
                Widget schemasUnitLeft = Span.of(
                    Text.of("📁 [Level 2: Unit / Schema Registry] Validation Schemas "),
                    Span.of("(" + dbSchemas.size() + " items)").modifier(new Modifier().style("font-size:10px; color:#64748b; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#38bdf8; font-size:12px; font-weight:600;"));

                Widget schemasUnitAddBtn = Button.of("[+ Add Schema]")
                    .attribute("type", "button")
                    .attribute("onclick", "openAddSchemaModal('" + db + "')")
                    .modifier(new Modifier().style("background:none; border:none; color:#38bdf8; font-size:10px; cursor:pointer;"));

                Widget schemasUnitHeaderRow = Div.of(schemasUnitLeft, schemasUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> schemaItemWidgets = new ArrayList<>();
                if (dbSchemas.isEmpty()) {
                    schemaItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No validation schema registered - click [+ Add Schema] to register)").modifier(new Modifier().style("font-style:italic;"))
                        ).modifier(new Modifier().style("font-size:11px; color:#64748b; padding:2px 0;"))
                    );
                } else {
                    for (Map.Entry<String, JsonObject> scEntry : dbSchemas.entrySet()) {
                        String scName = scEntry.getKey();

                        Widget scItemLeft = Span.of(
                            Text.of("└── "),
                            Icon.of("fas fa-shield-alt").modifier(new Modifier().style("color:#38bdf8; margin-right:4px;")),
                            Text.of("[Level 3: Schema] "),
                            Span.of(scName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))
                        );

                        Widget deleteScForm = Form.of(
                            InputHidden.of("action", "delete_schema"),
                            InputHidden.of("target_db", db),
                            InputHidden.of("schema_name", scName),
                            Button.of(Icon.of("fas fa-trash"))
                                .attribute("type", "submit")
                                .attribute("onclick", "return confirm('Delete schema " + scName + "?');")
                                .modifier(new Modifier().style("background:none; border:none; color:#ef4444; font-size:10px; cursor:pointer;"))
                        ).action(actionUrl + selectedEngine).method("POST").modifier(new Modifier().style("display:inline; margin:0;"));

                        Widget scItemRow = Div.of(scItemLeft, deleteScForm)
                            .modifier(new Modifier().style("font-size:11px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:2px 0;"));

                        schemaItemWidgets.add(scItemRow);
                    }
                }

                Widget schemaItemsContainer = Div.of(schemaItemWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:16px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:10px; margin-top:3px;"));

                Widget schemasUnitBlock = Div.of(schemasUnitHeaderRow, schemaItemsContainer)
                    .modifier(new Modifier().style("margin-bottom:8px; margin-top:4px;"));

                Widget idxSchemasSubtreeContainer = Div.of(indexesUnitBlock, schemasUnitBlock)
                    .modifier(new Modifier().style("margin-left:18px; border-left: 2px dotted rgba(234,179,8,0.3); padding-left:12px; margin-top:6px;"));

                Widget idxSchemasBlock = Div.of(idxSchemasHeaderRow, idxSchemasSubtreeContainer)
                    .modifier(new Modifier().style("margin-bottom:12px; background:rgba(30,41,59,0.7); padding:8px 10px; border-radius:6px; border:1px solid rgba(234,179,8,0.25);"));

                engineSubtreeWidgets.add(idxSchemasBlock);

                Widget dbSubtreeContainer = Div.of(engineSubtreeWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:22px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:16px; margin-top:10px;"));

                dbContentWidgets.add(dbSubtreeContainer);
            }

            Widget dbCard = Div.of(dbContentWidgets.toArray(new Widget[0]))
                .modifier(new Modifier().style("margin-bottom:14px; padding:8px 12px; border-radius:8px; background:" + (isActiveDb ? "rgba(56,189,248,0.06)" : "transparent") + "; border:" + (isActiveDb ? "1px solid rgba(56,189,248,0.2)" : "1px solid transparent") + ";"));

            dbCardWidgets.add(dbCard);
        }

        Widget treeContainer = Div.of(dbCardWidgets.toArray(new Widget[0]))
            .modifier(new Modifier().cssClass("espresso-tree").style("font-family:monospace; font-size:13px; color:#f8fafc; background:rgba(15,23,42,0.6); padding:16px 20px; border-radius:10px; border:1px solid rgba(255,255,255,0.06); max-height:550px; overflow-y:auto;"));

        return Div.of(treeHeader, treeContainer)
            .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; padding:16px 20px;"));
    }

    private Widget createEngineModals(String engineKey, String targetDb, String currentColl) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        List<Widget> modals = new ArrayList<>();
        modals.add(buildCreateDbModal(engineKey, actionUrl));
        modals.add(buildCreateUnitModal(targetDb, actionUrl));
        modals.add(buildAddDocumentModal(targetDb, currentColl));
        modals.add(buildAddKeyValueModal(targetDb));
        modals.add(buildAddVectorModal(targetDb));
        modals.add(buildAddGraphModal(targetDb));
        modals.add(buildAddTimeSeriesModal(targetDb));
        modals.add(buildAddColumnModal(targetDb));
        modals.add(buildAddGeoModal(targetDb));
        modals.add(buildAddObjectModal(targetDb));
        modals.add(buildAddRecordsModal(targetDb));
        modals.add(buildEditDocumentModal(actionUrl));
        modals.add(buildEditKeyValueModal(actionUrl));
        modals.add(buildEditVectorModal(actionUrl));
        modals.add(buildEditGraphModal(actionUrl));
        modals.add(buildEditTimeSeriesModal(actionUrl));
        modals.add(buildEditColumnModal(actionUrl));
        modals.add(buildEditGeoModal(actionUrl));
        modals.add(buildEditObjectModal(actionUrl));
        modals.add(buildEditRecordsModal(actionUrl));
        modals.add(buildUniversalRestoreModal(actionUrl));
        modals.add(buildConfirmRestoreModal(actionUrl));
        modals.add(buildConfirmDeleteModal(actionUrl));
        modals.add(buildAdvancedSearchModal(actionUrl, targetDb, currentColl));
        modals.add(buildCreateIndexModal(actionUrl));
        modals.add(buildCreateSchemaModal(actionUrl));
        modals.add(buildModalsScript());

        return Div.of(modals.toArray(new Widget[0]));
    }

    private Widget createModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(header, content).modifier(new Modifier().cssClass("store-card")
                .style("width:" + width + "; max-width:92%; background:#1e293b; border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:24px;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.75); backdrop-filter:blur(6px); z-index:9999; align-items:center; justify-content:center;"));
    }

    private Widget createConfirmationModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(header, content).modifier(new Modifier().cssClass("store-card")
                .style("width:" + width + "; max-width:92%; background:#1e293b; border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.7); padding:24px;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.75); backdrop-filter:blur(6px); z-index:10000; align-items:center; justify-content:center;"));
    }

    private Widget createModalHeader(String title, String iconClass, String iconColor, String modalId) {
        return Div.of(
            Header.of(3,
                Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; margin-right:8px;")),
                Text.of(" " + title)
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc;")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('" + modalId + "').style.display='none'")
                .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));
    }

    private Widget createModalHeaderWithSpan(String prefixTitle, String spanId, String spanColor, String iconClass, String iconColor, String modalId) {
        return Div.of(
            Header.of(3,
                Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; margin-right:8px;")),
                Text.of(" " + prefixTitle + " "),
                Span.of("").id(spanId).modifier(new Modifier().style("color:" + spanColor + ";"))
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc;")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('" + modalId + "').style.display='none'")
                .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));
    }

    private Widget createIdStrategySection(String engColor, String targetIdLabel, String targetIdPlaceholder) {
        return Div.of(
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>ID Generation Strategy:</label>"),
                RawHtml.of("<select name='id_gen_mode' onchange='handleIdModeChange(this)' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:" + engColor + "; font-size:13px; box-sizing:border-box;'>\n" +
                    "  <option value='UUID' selected>1. UUID (Composite: CPU + Time + DB + UUID Entropy)</option>\n" +
                    "  <option value='AUTOINCREMENT'>2. Autoincrementable (Sequential Counter: 1, 2, 3...)</option>\n" +
                    "  <option value='MANUAL'>3. Manual Mode (Custom User Specified ID)</option>\n" +
                    "</select>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>" + targetIdLabel + "</label>"),
                RawHtml.of("<input type='text' name='target_id' placeholder='" + targetIdPlaceholder + "' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().cssClass("manual-id-group").style("margin-bottom:12px; display:none;")),
            Div.of(
                Icon.of("fas fa-fingerprint").modifier(new Modifier().style("color:" + engColor + "; margin-right:4px;")),
                Span.of("Engine will auto-generate a Composite UUID integrating CPU hardware hash, timestamp, DB digest and UUID entropy.").modifier(new Modifier().cssClass("id-mode-desc"))
            ).modifier(new Modifier().cssClass("id-mode-banner").style("margin-bottom:12px; font-size:11px; color:#94a3b8; background:rgba(255,255,255,0.03); border-left:3px solid " + engColor + "; padding:6px 10px; border-radius:4px;"))
        );
    }

    private Widget createEditInfoBox(String dbSpanId, String badgeClass, String badgeText) {
        return Div.of(
            Div.of(
                RawHtml.of("<strong>Database:</strong> "),
                Span.of("").id(dbSpanId).modifier(new Modifier().style("color:#f8fafc;"))
            ),
            Div.of(
                RawHtml.of("<strong>Engine:</strong> "),
                Span.of(badgeText).modifier(new Modifier().cssClass("store-badge " + badgeClass))
            )
        ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px; font-size:12px; color:#94a3b8; background:rgba(255,255,255,0.03); padding:8px 12px; border-radius:6px;"));
    }

    private Widget createModalFormActions(String modalId, String submitText, String submitIcon, String submitColor) {
        return Div.of(
            Button.of(Text.of("Cancel"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('" + modalId + "').style.display='none'")
                .modifier(new Modifier().cssClass("btn-action btn-secondary")),
            Button.of(Icon.of(submitIcon), Text.of(" " + submitText))
                .attribute("type", "submit")
                .modifier(new Modifier().cssClass("btn-action btn-primary").style(submitColor.isEmpty() ? "" : "background:" + submitColor + ";"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:8px;"));
    }

    private Widget buildCreateDbModal(String engineKey, String actionUrl) {
        Widget header = createModalHeader("Create Multi-Model Database", "fas fa-database", "#38bdf8", "createDbModal");

        String docSel = "DOCUMENT".equalsIgnoreCase(engineKey) ? "selected" : "";
        String kvSel = "KEYVALUE".equalsIgnoreCase(engineKey) ? "selected" : "";
        String vecSel = "VECTOR".equalsIgnoreCase(engineKey) ? "selected" : "";
        String graphSel = "GRAPH".equalsIgnoreCase(engineKey) ? "selected" : "";
        String tsSel = "TIMESERIES".equalsIgnoreCase(engineKey) ? "selected" : "";
        String colSel = "COLUMN".equalsIgnoreCase(engineKey) ? "selected" : "";
        String geoSel = "GEOSPATIAL".equalsIgnoreCase(engineKey) ? "selected" : "";
        String objSel = "OBJECT".equalsIgnoreCase(engineKey) ? "selected" : "";
        String recSel = "RECORDS".equalsIgnoreCase(engineKey) ? "selected" : "";

        Widget form = Form.of(
            InputHidden.of("action", "create_db"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Database Name (StorageContainer):</label>"),
                RawHtml.of("<input type='text' name='new_db_name' required placeholder='e.g. ecommerce_db, inventory_db' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Primary Multi-Model Engine Subtree:</label>"),
                RawHtml.of("<select name='initial_engine' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#38bdf8; font-size:13px; box-sizing:border-box;'>\n" +
                    "  <option value='DOCUMENT' " + docSel + ">DOCUMENT (NoSQL Collections)</option>\n" +
                    "  <option value='KEYVALUE' " + kvSel + ">KEYVALUE (Cache & Buckets)</option>\n" +
                    "  <option value='VECTOR' " + vecSel + ">VECTOR (AI Embeddings)</option>\n" +
                    "  <option value='GRAPH' " + graphSel + ">GRAPH (Node & Edge Labels)</option>\n" +
                    "  <option value='TIMESERIES' " + tsSel + ">TIMESERIES (IoT Metrics)</option>\n" +
                    "  <option value='COLUMN' " + colSel + ">COLUMN (Column Families)</option>\n" +
                    "  <option value='GEOSPATIAL' " + geoSel + ">GEOSPATIAL (Spatial Layers)</option>\n" +
                    "  <option value='OBJECT' " + objSel + ">OBJECT (BLOB Buckets)</option>\n" +
                    "  <option value='RECORDS' " + recSel + ">RECORDS (Java 25 Record Tables)</option>\n" +
                    "</select>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Initial Subtree Unit (Collection / Bucket / Table):</label>"),
                RawHtml.of("<input type='text' name='initial_unit' value='default' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createDbModal", "Initialize Database", "fas fa-plus", "")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createDbModal", "520px", "rgba(56,189,248,0.4)", header, form);
    }

    private Widget buildCreateUnitModal(String targetDb, String actionUrl) {
        Widget header = createModalHeader("Add Subtree Unit (Level 2)", "fas fa-folder-plus", "#a855f7", "createUnitModal");

        Widget form = Form.of(
            InputHidden.of("action", "create_unit"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Target Database:</label>"),
                RawHtml.of("<input type='text' disabled value='" + targetDb + "' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#38bdf8; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Engine Subtree Type:</label>"),
                RawHtml.of("<select name='engine_type' id='modalUnitEngineSelect' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'>\n" +
                    "  <option value='DOCUMENT'>DOCUMENT (Colección / Collection)</option>\n" +
                    "  <option value='KEYVALUE'>KEYVALUE (Namespace / Bucket)</option>\n" +
                    "  <option value='VECTOR'>VECTOR (Vector Index / Collection)</option>\n" +
                    "  <option value='GRAPH'>GRAPH (Node & Edge Label)</option>\n" +
                    "  <option value='TIMESERIES'>TIMESERIES (Metric / Series Feed)</option>\n" +
                    "  <option value='COLUMN'>COLUMN (Column Family / Table)</option>\n" +
                    "  <option value='GEOSPATIAL'>GEOSPATIAL (Spatial Layer)</option>\n" +
                    "  <option value='OBJECT'>OBJECT (Storage Bucket / Container)</option>\n" +
                    "  <option value='RECORDS'>RECORDS (Record Table / Schema)</option>\n" +
                    "</select>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;' id='modalUnitNameLabel'>Unit Name:</label>"),
                RawHtml.of("<input type='text' name='unit_name' required placeholder='e.g. products, cache_layer, telemetry' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createUnitModal", "Add Unit", "fas fa-plus", "#a855f7")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createUnitModal", "520px", "rgba(139,92,246,0.4)", header, form);
    }

    private Widget buildAddDocumentModal(String targetDb, String currentColl) {
        Widget header = createModalHeader("[+ Add Document]", "fas fa-file-code", "#3b82f6", "addDocumentModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Target Collection:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='" + currentColl + "' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#38bdf8; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#38bdf8", "Custom Document ID:", "e.g. prod_1001, doc_special"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Class / Schema (Optional):</label>"),
                RawHtml.of("<input type='text' name='doc_class' placeholder='com.jettra.model.Customer' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>JSON Payload:</label>"),
                RawHtml.of("<textarea name='doc_payload' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\n  \"name\": \"Sample Document\",\n  \"status\": \"ACTIVE\",\n  \"rating\": 4.9\n}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addDocumentModal", "Save Document", "fas fa-plus", "")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=DOCUMENT"));

        return createModalOverlay("addDocumentModal", "560px", "rgba(59,130,246,0.4)", header, form);
    }

    private Widget buildAddKeyValueModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Key-Value Pair]", "fas fa-key", "#10b981", "addKeyValueModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Bucket / Namespace:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='default' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#10b981; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#10b981", "Custom Key Name:", "e.g. sess_token_99, config_app"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>String or JSON Value:</label>"),
                RawHtml.of("<textarea name='kv_value' rows='4' placeholder='Enter value to cache/store...' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addKeyValueModal", "Store Key-Value", "fas fa-save", "#10b981")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=KEYVALUE"));

        return createModalOverlay("addKeyValueModal", "520px", "rgba(16,185,129,0.4)", header, form);
    }

    private Widget buildAddVectorModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Vector Embedding]", "fas fa-project-diagram", "#8b5cf6", "addVectorModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Vector Index:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='default' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#8b5cf6; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#8b5cf6", "Custom Vector ID:", "e.g. vec_emb_001"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Vector Coordinates (float array):</label>"),
                RawHtml.of("<input type='text' name='vector_coords' value='0.12, 0.45, 0.88, 0.31, 0.65' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Metadata / Payload JSON:</label>"),
                RawHtml.of("<textarea name='vector_meta' rows='3' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\"category\": \"AI Model\", \"source\": \"embeddings_v3\"}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addVectorModal", "Insert Vector", "fas fa-project-diagram", "#8b5cf6")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=VECTOR"));

        return createModalOverlay("addVectorModal", "520px", "rgba(139,92,246,0.4)", header, form);
    }

    private Widget buildAddGraphModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Vertex / Edge]", "fas fa-share-alt", "#ec4899", "addGraphModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Element Type:</label>"),
                    RawHtml.of("<select name='graph_mode' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#ec4899; font-size:13px; box-sizing:border-box;'><option value='node'>Vertex (Node)</option><option value='edge'>Edge (Relationship)</option></select>")
                ),
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Node Label / Group:</label>"),
                    RawHtml.of("<input type='text' name='target_coll' value='User' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            createIdStrategySection("#ec4899", "Custom Vertex ID:", "e.g. user_node_101"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Graph Properties JSON:</label>"),
                RawHtml.of("<textarea name='node_props' rows='3' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\"name\": \"Alice\", \"role\": \"Admin\"}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addGraphModal", "Save Graph Item", "fas fa-share-alt", "#ec4899")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=GRAPH"));

        return createModalOverlay("addGraphModal", "520px", "rgba(236,72,153,0.4)", header, form);
    }

    private Widget buildAddTimeSeriesModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Time Point]", "fas fa-chart-line", "#06b6d4", "addTimeSeriesModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Metric Name:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='telemetry' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#06b6d4; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#06b6d4", "Custom Point ID:", "e.g. ts_pt_1001"),
            Div.of(
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Value (Double):</label>"),
                    RawHtml.of("<input type='number' step='any' name='ts_value' value='98.6' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                ),
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Unit / Scale:</label>"),
                    RawHtml.of("<input type='text' name='ts_unit' placeholder='celsius, ms, %' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Timestamp (ms):</label>"),
                RawHtml.of("<input type='text' name='ts_timestamp' value='" + System.currentTimeMillis() + "' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Tags JSON:</label>"),
                RawHtml.of("<textarea name='ts_tags' rows='2' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\"sensor_id\": \"SN-01\", \"zone\": \"rack_A\"}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addTimeSeriesModal", "Add Point", "fas fa-stopwatch", "#06b6d4")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=TIMESERIES"));

        return createModalOverlay("addTimeSeriesModal", "520px", "rgba(6,182,212,0.4)", header, form);
    }

    private Widget buildAddColumnModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Dynamic Row]", "fas fa-table", "#f97316", "addColumnModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Column Family:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='analytics' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f97316; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#f97316", "Custom Row Key:", "e.g. row_2026_01"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Column Data (JSON or col:val):</label>"),
                RawHtml.of("<textarea name='col_data' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\"views\": 1520, \"status\": \"PROCESSED\", \"latency_p99\": 14.2}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addColumnModal", "Insert Row", "fas fa-bars-staggered", "#f97316")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=COLUMN"));

        return createModalOverlay("addColumnModal", "520px", "rgba(249,115,22,0.4)", header, form);
    }

    private Widget buildAddGeoModal(String targetDb) {
        Widget header = createModalHeader("[+ Add GIS Feature]", "fas fa-globe-americas", "#14b8a6", "addGeoModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Spatial Layer:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='stores_layer' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#14b8a6; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#14b8a6", "Custom Feature ID:", "e.g. poi_station_01"),
            Div.of(
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Latitude (-90..90):</label>"),
                    RawHtml.of("<input type='number' step='any' name='geo_lat' value='8.9833' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                ),
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Longitude (-180..180):</label>"),
                    RawHtml.of("<input type='number' step='any' name='geo_lon' value='-79.5167' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Place Name / Metadata:</label>"),
                RawHtml.of("<input type='text' name='geo_name' value='Metropolitan Hub' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addGeoModal", "Save GIS Feature", "fas fa-location-dot", "#14b8a6")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=GEOSPATIAL"));

        return createModalOverlay("addGeoModal", "520px", "rgba(20,184,166,0.4)", header, form);
    }

    private Widget buildAddObjectModal(String targetDb) {
        Widget header = createModalHeader("[+ Add BLOB Object]", "fas fa-archive", "#a855f7", "addObjectModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Storage Bucket:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='media_bucket' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#a855f7; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#a855f7", "Custom Object ID:", "e.g. media_video_100"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>MIME Content-Type:</label>"),
                RawHtml.of("<input type='text' name='obj_mime' value='application/json' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Payload / Raw Content:</label>"),
                RawHtml.of("<textarea name='obj_payload' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>Binary BLOB chunk stream content</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addObjectModal", "Save Object", "fas fa-box-archive", "#a855f7")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=OBJECT"));

        return createModalOverlay("addObjectModal", "520px", "rgba(168,85,247,0.4)", header, form);
    }

    private Widget buildAddRecordsModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Immutable Record]", "fas fa-id-card", "#f43f5e", "addRecordsModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Record Table:</label>"),
                RawHtml.of("<input type='text' name='target_coll' value='employee_records' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f43f5e; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#f43f5e", "Custom Record ID:", "e.g. emp_101, rec_person_01"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Java 25 Record Class:</label>"),
                RawHtml.of("<input type='text' name='rec_class' value='com.jettra.model.PersonRecord' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Record Components JSON:</label>"),
                RawHtml.of("<textarea name='rec_payload' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\"name\": \"Carlos Ruiz\", \"role\": \"Lead Architect\", \"department\": \"Engineering\"}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addRecordsModal", "Save Record", "fas fa-address-card", "#f43f5e")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=RECORDS"));

        return createModalOverlay("addRecordsModal", "520px", "rgba(244,63,94,0.4)", header, form);
    }

    private Widget buildEditDocumentModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Document:", "editDocIdDisplay", "#38bdf8", "fas fa-file-code", "#3b82f6", "editDocumentModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "DOCUMENT"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editDocDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editDocIdInput'/>")),
            createEditInfoBox("editDocDbDisplay", "badge-active", "DOCUMENT"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Collection:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editDocCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#38bdf8; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Class / Schema (Optional):</label>"),
                RawHtml.of("<input type='text' name='doc_class' id='editDocClassInput' placeholder='com.jettra.model.Customer' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>JSON Document Payload:</label>"),
                RawHtml.of("<textarea name='doc_payload' id='editDocPayloadInput' rows='6' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editDocumentModal", "Save Changes (New Version)", "fas fa-save", "")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editDocumentModal", "580px", "rgba(59,130,246,0.4)", header, form);
    }

    private Widget buildEditKeyValueModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Key-Value Pair:", "editKvIdDisplay", "#10b981", "fas fa-key", "#10b981", "editKeyValueModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "KEYVALUE"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editKvDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editKvIdInput'/>")),
            createEditInfoBox("editKvDbDisplay", "badge-kv", "KEYVALUE"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Bucket / Namespace:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editKvCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#10b981; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Stored Value / Payload:</label>"),
                RawHtml.of("<textarea name='kv_value' id='editKvValueInput' rows='6' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editKeyValueModal", "Save Value (New Version)", "fas fa-save", "#10b981")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editKeyValueModal", "540px", "rgba(16,185,129,0.4)", header, form);
    }

    private Widget buildEditVectorModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Vector Embedding:", "editVecIdDisplay", "#c084fc", "fas fa-project-diagram", "#a855f7", "editVectorModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "VECTOR"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editVecDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editVecIdInput'/>")),
            createEditInfoBox("editVecDbDisplay", "badge-vector", "VECTOR"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Vector Index / Collection:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editVecCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#c084fc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Vector Coordinates (float array):</label>"),
                RawHtml.of("<input type='text' name='vector_coords' id='editVecCoordsInput' placeholder='0.12, 0.45, 0.88, 0.31' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Metadata JSON:</label>"),
                RawHtml.of("<textarea name='vector_meta' id='editVecMetaInput' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editVectorModal", "Save Vector (New Version)", "fas fa-save", "#a855f7")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editVectorModal", "560px", "rgba(139,92,246,0.4)", header, form);
    }

    private Widget buildEditGraphModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Vertex / Edge:", "editGraphIdDisplay", "#f472b6", "fas fa-circle-nodes", "#ec4899", "editGraphModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "GRAPH"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editGraphDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editGraphIdInput'/>")),
            createEditInfoBox("editGraphDbDisplay", "badge-graph", "GRAPH"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Node Label / Group:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editGraphCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#ec4899; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Graph Properties JSON:</label>"),
                RawHtml.of("<textarea name='node_props' id='editGraphPropsInput' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editGraphModal", "Save Vertex (New Version)", "fas fa-save", "#ec4899")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editGraphModal", "560px", "rgba(236,72,153,0.4)", header, form);
    }

    private Widget buildEditTimeSeriesModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Time Point:", "editTsIdDisplay", "#22d3ee", "fas fa-clock", "#06b6d4", "editTimeSeriesModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "TIMESERIES"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editTsDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editTsIdInput'/>")),
            createEditInfoBox("editTsDbDisplay", "badge-ts", "TIMESERIES"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Metric Name / Series:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editTsCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#06b6d4; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Value (Double):</label>"),
                    RawHtml.of("<input type='number' step='any' name='ts_value' id='editTsValueInput' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                ),
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Unit / Scale:</label>"),
                    RawHtml.of("<input type='text' name='ts_unit' id='editTsUnitInput' placeholder='celsius, ms, %' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Timestamp (ms):</label>"),
                RawHtml.of("<input type='text' name='ts_timestamp' id='editTsTimestampInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Tags JSON:</label>"),
                RawHtml.of("<textarea name='ts_tags' id='editTsTagsInput' rows='3' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editTimeSeriesModal", "Save Point (New Version)", "fas fa-save", "#06b6d4")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editTimeSeriesModal", "560px", "rgba(6,182,212,0.4)", header, form);
    }

    private Widget buildEditColumnModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Dynamic Row:", "editColIdDisplay", "#fb923c", "fas fa-table", "#f97316", "editColumnModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "COLUMN"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editColDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editColIdInput'/>")),
            createEditInfoBox("editColDbDisplay", "badge-column", "COLUMN"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Column Family:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editColCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f97316; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Column Data (JSON):</label>"),
                RawHtml.of("<textarea name='col_data' id='editColDataInput' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editColumnModal", "Save Row (New Version)", "fas fa-save", "#f97316")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editColumnModal", "560px", "rgba(249,115,22,0.4)", header, form);
    }

    private Widget buildEditGeoModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit GIS Feature:", "editGeoIdDisplay", "#2dd4bf", "fas fa-location-dot", "#14b8a6", "editGeoModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "GEOSPATIAL"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editGeoDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editGeoIdInput'/>")),
            createEditInfoBox("editGeoDbDisplay", "badge-geo", "GEOSPATIAL"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Spatial Layer:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editGeoCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#14b8a6; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Latitude (-90..90):</label>"),
                    RawHtml.of("<input type='number' step='any' name='geo_lat' id='editGeoLatInput' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                ),
                Div.of(
                    RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Longitude (-180..180):</label>"),
                    RawHtml.of("<input type='number' step='any' name='geo_lon' id='editGeoLonInput' required style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Place Name / Metadata:</label>"),
                RawHtml.of("<input type='text' name='geo_name' id='editGeoNameInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editGeoModal", "Save GIS Feature (New Version)", "fas fa-save", "#14b8a6")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editGeoModal", "560px", "rgba(20,184,166,0.4)", header, form);
    }

    private Widget buildEditObjectModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit BLOB Object:", "editObjIdDisplay", "#c084fc", "fas fa-box-archive", "#a855f7", "editObjectModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "OBJECT"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editObjDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editObjIdInput'/>")),
            createEditInfoBox("editObjDbDisplay", "badge-object", "OBJECT"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Storage Bucket:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editObjCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#a855f7; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>MIME Content-Type:</label>"),
                RawHtml.of("<input type='text' name='obj_mime' id='editObjMimeInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Payload / Raw Content:</label>"),
                RawHtml.of("<textarea name='obj_payload' id='editObjPayloadInput' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editObjectModal", "Save Object (New Version)", "fas fa-save", "#a855f7")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editObjectModal", "560px", "rgba(168,85,247,0.4)", header, form);
    }

    private Widget buildEditRecordsModal(String actionUrl) {
        Widget header = createModalHeaderWithSpan("Edit Immutable Record:", "editRecIdDisplay", "#fb7185", "fas fa-address-card", "#f43f5e", "editRecordsModal");

        Widget form = Form.of(
            InputHidden.of("action", "edit_object"),
            InputHidden.of("engine_type", "RECORDS"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='editRecDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='editRecIdInput'/>")),
            createEditInfoBox("editRecDbDisplay", "badge-records", "RECORDS"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Record Table:</label>"),
                RawHtml.of("<input type='text' name='target_coll' id='editRecCollInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f43f5e; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Java 25 Record Class:</label>"),
                RawHtml.of("<input type='text' name='rec_class' id='editRecClassInput' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Record Components JSON:</label>"),
                RawHtml.of("<textarea name='rec_payload' id='editRecPayloadInput' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'></textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("editRecordsModal", "Save Record (New Version)", "fas fa-save", "#f43f5e")
        ).method("POST").action(actionUrl);

        return createModalOverlay("editRecordsModal", "560px", "rgba(244,63,94,0.4)", header, form);
    }

    private Widget buildUniversalRestoreModal(String actionUrl) {
        Widget header = Div.of(
            Header.of(3,
                Icon.of("fas fa-history").modifier(new Modifier().style("color:#a855f7; margin-right:8px;")),
                Text.of("Historical Versions: "),
                Span.of("").id("restoreEngineLabel").modifier(new Modifier().cssClass("store-badge").style("background:rgba(168,85,247,0.2); color:#c084fc; font-size:11px;")),
                Text.of(" ("),
                Span.of("").id("restoreRecordIdLabel").modifier(new Modifier().style("color:#38bdf8;")),
                Text.of(")")
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc;")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('universalRestoreModal').style.display='none'")
                .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));

        Widget form = Form.of(
            InputHidden.of("action", "restore_version"),
            Div.of(RawHtml.of("<input type='hidden' name='engine_type' id='restoreEngineTypeInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='restoreRecordDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_coll' id='restoreRecordCollInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='restoreRecordIdInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='version_ts' id='restoreVersionTsInput'/>")),
            Paragraph.of("Select any previous snapshot version (ordered descending: newest to oldest) to rollback:").modifier(new Modifier().style("font-size:13px; color:#cbd5e1; margin-top:0;")),
            Div.of().id("universalVersionsContainer").modifier(new Modifier().style("max-height:240px; overflow-y:auto; margin-bottom:16px; border:1px solid rgba(255,255,255,0.08); border-radius:8px; background:#0f172a;")),
            Div.of(
                Button.of(Text.of("Close"))
                    .attribute("type", "button")
                    .attribute("onclick", "document.getElementById('universalRestoreModal').style.display='none'")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:8px;"))
        ).method("POST").action(actionUrl);

        return createModalOverlay("universalRestoreModal", "680px", "rgba(168,85,247,0.4)", header, form);
    }

    private Widget buildConfirmRestoreModal(String actionUrl) {
        Widget header = createModalHeader("Confirm Version Rollback", "fas fa-undo", "#a855f7", "confirmRestoreModal");

        Widget form = Form.of(
            InputHidden.of("action", "restore_version"),
            Div.of(RawHtml.of("<input type='hidden' name='engine_type' id='confirmRestoreEngineInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='confirmRestoreDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_coll' id='confirmRestoreCollInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='confirmRestoreIdInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='version_ts' id='confirmRestoreTsInput'/>")),
            Div.of(
                RawHtml.of("<p style='margin:0 0 8px 0;'>Are you sure you want to restore item version from timestamp <strong id='confirmRestoreTsDisplay' style='color:#c084fc;'></strong>?</p>"),
                Div.of(
                    RawHtml.of("<span>Record ID: <strong id='confirmRestoreIdDisplay' style='color:#38bdf8;'></strong></span>"),
                    RawHtml.of("<span style='margin-left:12px;'>Engine: <span id='confirmRestoreEngineDisplay' class='store-badge badge-active'></span></span>"),
                    RawHtml.of("<span style='margin-left:12px;'>Date: <span id='confirmRestoreDateDisplay' style='color:#f8fafc;'></span></span>")
                ).modifier(new Modifier().style("font-size:11px; color:#cbd5e1;"))
            ).modifier(new Modifier().style("background:rgba(168,85,247,0.1); border-left:3px solid #a855f7; padding:12px 14px; border-radius:6px; margin-bottom:16px; font-size:13px; color:#f8fafc;")),
            Paragraph.of("The historical snapshot version will be restored as the active record.").modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin:0 0 16px 0;")),
            createModalFormActions("confirmRestoreModal", "Restore Version", "fas fa-undo", "#a855f7")
        ).method("POST").action(actionUrl);

        return createConfirmationModalOverlay("confirmRestoreModal", "500px", "rgba(168,85,247,0.5)", header, form);
    }

    private Widget buildConfirmDeleteModal(String actionUrl) {
        Widget header = createModalHeader("Confirm Delete Record", "fas fa-trash-alt", "#ef4444", "confirmDeleteModal");

        Widget form = Form.of(
            InputHidden.of("action", "delete_object"),
            Div.of(RawHtml.of("<input type='hidden' name='engine_type' id='confirmDeleteEngineInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='confirmDeleteDbInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_coll' id='confirmDeleteCollInput'/>")),
            Div.of(RawHtml.of("<input type='hidden' name='target_id' id='confirmDeleteIdInput'/>")),
            Div.of(
                RawHtml.of("<p style='margin:0 0 8px 0;'>Are you sure you want to permanently delete record <strong id='confirmDeleteIdDisplay' style='color:#ef4444;'></strong>?</p>"),
                Div.of(
                    RawHtml.of("<span>Engine: <strong id='confirmDeleteEngineDisplay' style='color:#f8fafc;'></strong></span>"),
                    RawHtml.of("<span style='margin-left:12px;'>Database: <strong id='confirmDeleteDbDisplay' style='color:#38bdf8;'></strong></span>"),
                    RawHtml.of("<span style='margin-left:12px;'>Unit: <strong id='confirmDeleteCollDisplay' style='color:#cbd5e1;'></strong></span>")
                ).modifier(new Modifier().style("font-size:11px; color:#cbd5e1;"))
            ).modifier(new Modifier().style("background:rgba(239,68,68,0.1); border-left:3px solid #ef4444; padding:12px 14px; border-radius:6px; margin-bottom:16px; font-size:13px; color:#f8fafc;")),
            Paragraph.of("This operation is immediate and removes the record from storage.").modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin:0 0 16px 0;")),
            createModalFormActions("confirmDeleteModal", "Confirm Delete", "fas fa-trash-alt", "#ef4444")
        ).method("POST").action(actionUrl);

        return createConfirmationModalOverlay("confirmDeleteModal", "500px", "rgba(239,68,68,0.5)", header, form);
    }

    private Widget buildAdvancedSearchModal(String actionUrl, String targetDb, String currentColl) {
        Widget header = createModalHeader("Advanced Search Inspector", "fas fa-search-plus", "#38bdf8", "advancedSearchModal");

        Widget form = Form.of(
            InputHidden.of("action", "query_object"),
            InputHidden.of("target_db", targetDb),
            InputHidden.of("target_coll", currentColl),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Lookup ID / Key Filter:</label>"),
                RawHtml.of("<input type='text' name='target_id' placeholder='e.g. doc_101 or prefix*' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>JSON Property Query (Key : Value):</label>"),
                RawHtml.of("<input type='text' name='prop_filter' placeholder='tier: Platinum' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("advancedSearchModal", "Execute Query", "fas fa-search", "")
        ).method("POST").action(actionUrl);

        return createModalOverlay("advancedSearchModal", "560px", "rgba(59,130,246,0.4)", header, form);
    }

    private Widget buildCreateIndexModal(String actionUrl) {
        Widget header = createModalHeader("Create Secondary / Composite Index", "fas fa-bolt", "#eab308", "createIndexModal");

        Widget form = Form.of(
            InputHidden.of("action", "create_index"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='createIndexDbInput'/>")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Target Database:</label>"),
                RawHtml.of("<input type='text' id='createIndexDbDisplay' disabled style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#eab308; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Index Name:</label>"),
                RawHtml.of("<input type='text' name='index_name' required placeholder='e.g. idx_email, idx_price, idx_coords' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Indexed Field / Property:</label>"),
                RawHtml.of("<input type='text' name='index_field' required placeholder='e.g. email, status, coordinates' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Index Algorithm / Structure:</label>"),
                RawHtml.of("<select name='index_type' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#fde047; font-size:13px; box-sizing:border-box;'>\n" +
                    "  <option value='BTREE' selected>B-Tree (Balanced Index for equality & range queries)</option>\n" +
                    "  <option value='HASH'>Hash (O(1) exact equality lookup index)</option>\n" +
                    "  <option value='FULLTEXT'>Full-Text (Inverted index for token/keyword search)</option>\n" +
                    "  <option value='VECTOR_HNSW'>Vector HNSW (Hierarchical Navigable Small World for ANN)</option>\n" +
                    "  <option value='SPATIAL_2D'>Spatial 2D (QuadTree/Geohash spatial index)</option>\n" +
                    "</select>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createIndexModal", "Build Index", "fas fa-bolt", "#eab308; color:#0f172a")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createIndexModal", "520px", "rgba(234,179,8,0.4)", header, form);
    }

    private Widget buildCreateSchemaModal(String actionUrl) {
        Widget header = createModalHeader("Register Validation Schema", "fas fa-shield-alt", "#38bdf8", "createSchemaModal");

        Widget form = Form.of(
            InputHidden.of("action", "save_schema"),
            Div.of(RawHtml.of("<input type='hidden' name='target_db' id='createSchemaDbInput'/>")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Target Database:</label>"),
                RawHtml.of("<input type='text' id='createSchemaDbDisplay' disabled style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#38bdf8; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>Schema Class / Name:</label>"),
                RawHtml.of("<input type='text' name='schema_name' required placeholder='e.g. com.enterprise.model.CustomerSchema' style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:13px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;'>JSON Schema Definition:</label>"),
                RawHtml.of("<textarea name='schema_json' rows='5' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;'>{\n  \"type\": \"object\",\n  \"required\": [\"name\", \"active\"],\n  \"properties\": {\n    \"name\": {\"type\": \"string\"},\n    \"active\": {\"type\": \"boolean\"}\n  }\n}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createSchemaModal", "Register Schema", "fas fa-shield-alt", "#38bdf8")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createSchemaModal", "560px", "rgba(56,189,248,0.4)", header, form);
    }

    private Widget buildModalsScript() {
        String js = """
  function openAddIndexModal(db) {
    document.getElementById('createIndexDbInput').value = db;
    document.getElementById('createIndexDbDisplay').value = db;
    document.getElementById('createIndexModal').style.display = 'flex';
  }
  function openAddSchemaModal(db) {
    document.getElementById('createSchemaDbInput').value = db;
    document.getElementById('createSchemaDbDisplay').value = db;
    document.getElementById('createSchemaModal').style.display = 'flex';
  }
  function openAddUnitModal(engine, label) {
    document.getElementById('modalUnitEngineSelect').value = engine;
    document.getElementById('modalUnitNameLabel').innerText = label + ' Name:';
    document.getElementById('createUnitModal').style.display = 'flex';
  }
  function openAddObjectModal(engine, unit) {
    var modalMap = {
      'DOCUMENT': 'addDocumentModal',
      'KEYVALUE': 'addKeyValueModal',
      'VECTOR': 'addVectorModal',
      'GRAPH': 'addGraphModal',
      'TIMESERIES': 'addTimeSeriesModal',
      'COLUMN': 'addColumnModal',
      'GEOSPATIAL': 'addGeoModal',
      'OBJECT': 'addObjectModal',
      'RECORDS': 'addRecordsModal'
    };
    var modalId = modalMap[engine] || 'addDocumentModal';
    var modal = document.getElementById(modalId);
    if (modal) {
      var unitInput = modal.querySelector('input[name="target_coll"]') || modal.querySelector('input[name="node_label"]');
      if (unitInput && unit) unitInput.value = unit;
      modal.style.display = 'flex';
    }
  }
  function handleIdModeChange(selectElem) {
    var form = selectElem.closest('form');
    if (!form) return;
    var mode = selectElem.value;
    var manualGroup = form.querySelector('.manual-id-group');
    var desc = form.querySelector('.id-mode-desc');
    var icon = form.querySelector('.id-mode-banner i');
    var input = form.querySelector('.manual-id-group input');
    if (mode === 'MANUAL') {
      if (manualGroup) manualGroup.style.display = 'block';
      if (input) input.required = true;
      if (desc) desc.innerText = 'Manual mode active: enter your custom identifier above.';
      if (icon) icon.className = 'fas fa-keyboard';
    } else if (mode === 'AUTOINCREMENT') {
      if (manualGroup) manualGroup.style.display = 'none';
      if (input) { input.required = false; input.value = ''; }
      if (desc) desc.innerText = 'Autoincrement mode active: engine internal counter will generate next sequential integer (1, 2, 3...).';
      if (icon) icon.className = 'fas fa-sort-numeric-down';
    } else {
      if (manualGroup) manualGroup.style.display = 'none';
      if (input) { input.required = false; input.value = ''; }
      if (desc) desc.innerText = 'Composite UUID mode active: generates unique ID combining CPU signature, timestamp, DB digest and UUID entropy.';
      if (icon) icon.className = 'fas fa-fingerprint';
    }
  }
  function decodeUtf8Base64(b64) {
    if (!b64) return '';
    try {
      var bin = atob(b64);
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return new TextDecoder('utf-8').decode(bytes);
    } catch (e) {
      try {
        return atob(b64);
      } catch (e2) {
        return b64;
      }
    }
  }
  function openUniversalEditModal(engine, db, unit, id, payloadB64) {
    var payload = decodeUtf8Base64(payloadB64);
    var parsed = null;
    try {
      if (typeof payload === 'string' && (payload.trim().startsWith('{') || payload.trim().startsWith('['))) {
        parsed = JSON.parse(payload);
      }
    } catch (e) {}

    var prettyPayload = parsed ? JSON.stringify(parsed, null, 2) : payload;

    if (engine === 'DOCUMENT') {
      document.getElementById('editDocDbInput').value = db;
      document.getElementById('editDocDbDisplay').innerText = db;
      document.getElementById('editDocCollInput').value = unit || 'default';
      document.getElementById('editDocIdInput').value = id;
      document.getElementById('editDocIdDisplay').innerText = id;
      if (parsed && parsed._class) document.getElementById('editDocClassInput').value = parsed._class;
      else document.getElementById('editDocClassInput').value = '';
      document.getElementById('editDocPayloadInput').value = prettyPayload;
      document.getElementById('editDocumentModal').style.display = 'flex';
    } else if (engine === 'KEYVALUE') {
      document.getElementById('editKvDbInput').value = db;
      document.getElementById('editKvDbDisplay').innerText = db;
      document.getElementById('editKvCollInput').value = unit || 'default';
      document.getElementById('editKvIdInput').value = id;
      document.getElementById('editKvIdDisplay').innerText = id;
      document.getElementById('editKvValueInput').value = payload;
      document.getElementById('editKeyValueModal').style.display = 'flex';
    } else if (engine === 'VECTOR') {
      document.getElementById('editVecDbInput').value = db;
      document.getElementById('editVecDbDisplay').innerText = db;
      document.getElementById('editVecCollInput').value = unit || 'default';
      document.getElementById('editVecIdInput').value = id;
      document.getElementById('editVecIdDisplay').innerText = id;
      if (parsed && Array.isArray(parsed.coordinates)) {
        document.getElementById('editVecCoordsInput').value = parsed.coordinates.join(', ');
      } else if (parsed && Array.isArray(parsed.embedding)) {
        document.getElementById('editVecCoordsInput').value = parsed.embedding.join(', ');
      } else if (parsed && Array.isArray(parsed.vector)) {
        document.getElementById('editVecCoordsInput').value = parsed.vector.join(', ');
      } else {
        document.getElementById('editVecCoordsInput').value = '0.12, 0.45, 0.88, 0.31';
      }
      document.getElementById('editVecMetaInput').value = prettyPayload;
      document.getElementById('editVectorModal').style.display = 'flex';
    } else if (engine === 'GRAPH') {
      document.getElementById('editGraphDbInput').value = db;
      document.getElementById('editGraphDbDisplay').innerText = db;
      document.getElementById('editGraphCollInput').value = (parsed && parsed.label) ? parsed.label : (unit || 'Vertex');
      document.getElementById('editGraphIdInput').value = id;
      document.getElementById('editGraphIdDisplay').innerText = id;
      document.getElementById('editGraphPropsInput').value = prettyPayload;
      document.getElementById('editGraphModal').style.display = 'flex';
    } else if (engine === 'TIMESERIES') {
      document.getElementById('editTsDbInput').value = db;
      document.getElementById('editTsDbDisplay').innerText = db;
      document.getElementById('editTsCollInput').value = (parsed && parsed.metric) ? parsed.metric : (unit || 'telemetry');
      document.getElementById('editTsIdInput').value = id;
      document.getElementById('editTsIdDisplay').innerText = id;
      document.getElementById('editTsTimestampInput').value = (parsed && parsed.timestamp) ? parsed.timestamp : id;
      if (parsed && parsed.value !== undefined) document.getElementById('editTsValueInput').value = parsed.value;
      else document.getElementById('editTsValueInput').value = '25.4';
      if (parsed && parsed.unit) document.getElementById('editTsUnitInput').value = parsed.unit;
      else document.getElementById('editTsUnitInput').value = 'celsius';
      document.getElementById('editTsTagsInput').value = prettyPayload;
      document.getElementById('editTimeSeriesModal').style.display = 'flex';
    } else if (engine === 'COLUMN') {
      document.getElementById('editColDbInput').value = db;
      document.getElementById('editColDbDisplay').innerText = db;
      document.getElementById('editColCollInput').value = (parsed && parsed._family) ? parsed._family : (unit || 'analytics');
      document.getElementById('editColIdInput').value = id;
      document.getElementById('editColIdDisplay').innerText = id;
      document.getElementById('editColDataInput').value = prettyPayload;
      document.getElementById('editColumnModal').style.display = 'flex';
    } else if (engine === 'GEOSPATIAL') {
      document.getElementById('editGeoDbInput').value = db;
      document.getElementById('editGeoDbDisplay').innerText = db;
      document.getElementById('editGeoCollInput').value = (parsed && parsed._layer) ? parsed._layer : (unit || 'stores_layer');
      document.getElementById('editGeoIdInput').value = id;
      document.getElementById('editGeoIdDisplay').innerText = id;
      if (parsed && (parsed.lat !== undefined || parsed.latitude !== undefined)) {
        document.getElementById('editGeoLatInput').value = parsed.lat !== undefined ? parsed.lat : parsed.latitude;
      } else {
        document.getElementById('editGeoLatInput').value = '8.9824';
      }
      if (parsed && (parsed.lon !== undefined || parsed.longitude !== undefined)) {
        document.getElementById('editGeoLonInput').value = parsed.lon !== undefined ? parsed.lon : parsed.longitude;
      } else {
        document.getElementById('editGeoLonInput').value = '-79.5199';
      }
      if (parsed && parsed.name) document.getElementById('editGeoNameInput').value = parsed.name;
      else document.getElementById('editGeoNameInput').value = id;
      document.getElementById('editGeoModal').style.display = 'flex';
    } else if (engine === 'OBJECT') {
      document.getElementById('editObjDbInput').value = db;
      document.getElementById('editObjDbDisplay').innerText = db;
      document.getElementById('editObjCollInput').value = (parsed && parsed.bucket) ? parsed.bucket : (unit || 'media_bucket');
      document.getElementById('editObjIdInput').value = id;
      document.getElementById('editObjIdDisplay').innerText = id;
      if (parsed && parsed.mimeType) document.getElementById('editObjMimeInput').value = parsed.mimeType;
      else document.getElementById('editObjMimeInput').value = 'application/json';
      document.getElementById('editObjPayloadInput').value = (parsed && parsed.content) ? parsed.content : payload;
      document.getElementById('editObjectModal').style.display = 'flex';
    } else if (engine === 'RECORDS') {
      document.getElementById('editRecDbInput').value = db;
      document.getElementById('editRecDbDisplay').innerText = db;
      document.getElementById('editRecCollInput').value = (parsed && parsed._table) ? parsed._table : (unit || 'default');
      document.getElementById('editRecIdInput').value = id;
      document.getElementById('editRecIdDisplay').innerText = id;
      if (parsed && parsed._class) document.getElementById('editRecClassInput').value = parsed._class;
      else document.getElementById('editRecClassInput').value = 'com.jettra.model.PersonRecord';
      document.getElementById('editRecPayloadInput').value = prettyPayload;
      document.getElementById('editRecordsModal').style.display = 'flex';
    }
  }
  function openUniversalRestoreModal(engine, db, unit, id, versionsJsonB64) {
    var versionsJsonStr = decodeUtf8Base64(versionsJsonB64);
    document.getElementById('restoreEngineLabel').innerText = engine;
    document.getElementById('restoreEngineTypeInput').value = engine;
    document.getElementById('restoreRecordDbInput').value = db;
    document.getElementById('restoreRecordCollInput').value = unit || 'default';
    document.getElementById('restoreRecordIdInput').value = id;
    document.getElementById('restoreRecordIdLabel').innerText = id;
    var container = document.getElementById('universalVersionsContainer');
    container.innerHTML = '';
    try {
      var versions = JSON.parse(versionsJsonStr);
      if (!versions || versions.length === 0) {
        container.innerHTML = '<div style="padding:16px; color:#94a3b8; text-align:center;">No historical snapshot versions recorded for this item yet. Edit the item to create new versions.</div>';
      } else {
        var html = '<table style="width:100%; border-collapse:collapse; font-size:12px;">';
        html += '<tr style="background:rgba(255,255,255,0.04); color:#94a3b8; text-align:left;"><th style="padding:8px 12px;">Version</th><th style="padding:8px 12px;">Timestamp / Date</th><th style="padding:8px 12px;">Snapshot Preview</th><th style="padding:8px 12px; text-align:right;">Action</th></tr>';
        for (var i = 0; i < versions.length; i++) {
          var v = versions[i];
          var badge = v.isCurrent ? '<span class="store-badge badge-active" style="font-size:10px;">' + v.versionNumber + ' (CURRENT)</span>' : '<span class="store-badge badge-records" style="font-size:10px;">' + v.versionNumber + '</span>';
          html += '<tr style="border-bottom:1px solid rgba(255,255,255,0.05);">';
          html += '<td style="padding:8px 12px; font-weight:bold;">' + badge + '</td>';
          html += '<td style="padding:8px 12px; color:#cbd5e1;">' + (v.formattedDate || v.timestamp) + '</td>';
          html += '<td style="padding:8px 12px; color:#94a3b8; font-family:monospace;">' + (v.preview || '{}') + '</td>';
          html += '<td style="padding:8px 12px; text-align:right;">';
          if (!v.isCurrent) {
            html += '<button type="button" onclick="openConfirmRestoreModal(' + v.timestamp + ', \\'' + (v.formattedDate || v.timestamp) + '\\')" class="btn-action btn-primary" style="background:#a855f7; padding:3px 10px; font-size:11px;"><i class="fas fa-undo"></i> Restore</button>';
          } else {
            html += '<span style="color:#10b981; font-size:11px;">Active</span>';
          }
          html += '</td></tr>';
        }
        html += '</table>';
        container.innerHTML = html;
      }
    } catch(e) {
      container.innerHTML = '<div style="padding:16px; color:#ef4444;">Error parsing version list: ' + e.message + '</div>';
    }
    document.getElementById('universalRestoreModal').style.display = 'flex';
  }
  function openConfirmRestoreModal(ts, formattedDate) {
    var engine = document.getElementById('restoreEngineTypeInput').value;
    var db = document.getElementById('restoreRecordDbInput').value;
    var unit = document.getElementById('restoreRecordCollInput').value;
    var id = document.getElementById('restoreRecordIdInput').value;
    document.getElementById('confirmRestoreEngineInput').value = engine;
    document.getElementById('confirmRestoreEngineDisplay').innerText = engine;
    document.getElementById('confirmRestoreDbInput').value = db;
    document.getElementById('confirmRestoreCollInput').value = unit || 'default';
    document.getElementById('confirmRestoreIdInput').value = id;
    document.getElementById('confirmRestoreIdDisplay').innerText = id;
    document.getElementById('confirmRestoreTsInput').value = ts;
    document.getElementById('confirmRestoreTsDisplay').innerText = ts;
    document.getElementById('confirmRestoreDateDisplay').innerText = formattedDate || ts;
    document.getElementById('confirmRestoreModal').style.display = 'flex';
  }
  function openUniversalDeleteModal(engine, db, unit, id) {
    document.getElementById('confirmDeleteEngineInput').value = engine;
    document.getElementById('confirmDeleteEngineDisplay').innerText = engine;
    document.getElementById('confirmDeleteDbInput').value = db;
    document.getElementById('confirmDeleteDbDisplay').innerText = db;
    document.getElementById('confirmDeleteCollInput').value = unit || 'default';
    document.getElementById('confirmDeleteCollDisplay').innerText = unit || 'default';
    document.getElementById('confirmDeleteIdInput').value = id;
    document.getElementById('confirmDeleteIdDisplay').innerText = id;
    document.getElementById('confirmDeleteModal').style.display = 'flex';
  }
""";
        return RawScript.of(js);
    }
}
