package com.jettra.store.engine.web;

import com.jettra.store.engine.core.DatabaseBackupManager;
import com.jettra.store.engine.core.DatabaseBackupManager.BackupFileInfo;
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    protected boolean onGet(HttpExchange exchange, Map<String, String> params) throws IOException {
        if (params != null && "export_data".equalsIgnoreCase(params.get("action"))) {
            handleExportData(exchange, params);
            return true;
        }
        return false;
    }

    private void handleExportData(HttpExchange exchange, Map<String, String> params) throws IOException {
        String db = params.getOrDefault("target_db", "customers_db");
        String eng = params.getOrDefault("engine_type", params.getOrDefault("engine", "ALL")).toUpperCase();
        String coll = params.getOrDefault("target_coll", params.getOrDefault("coll", "")).trim();
        String format = params.getOrDefault("format", "json").toLowerCase();

        Map<String, String> recordsMap = new LinkedHashMap<>();
        String[] prefixes;
        if ("ALL".equalsIgnoreCase(eng) || eng.isBlank()) {
            prefixes = new String[]{"rec:" + db + ":", "doc:" + db + ":", "vec:" + db + ":", "graph:" + db + ":", "ts:" + db + ":", "col:" + db + ":", "kv:" + db + ":", "geo:" + db + ":", "obj:" + db + ":", db + ":"};
        } else {
            String pfx = getPrefixForEngine(eng);
            prefixes = new String[]{pfx + db + ":", db + ":"};
        }

        for (String p : prefixes) {
            Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(p);
            for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                String k = e.getKey();
                if (k.contains("@")) continue;
                if (!coll.isBlank() && !k.contains(":" + coll + ":") && !k.contains(":" + coll)) continue;
                recordsMap.put(k, new String(e.getValue(), StandardCharsets.UTF_8));
            }
        }

        byte[] outputBytes;
        String contentType;
        String fileExt;

        if ("csv".equalsIgnoreCase(format)) {
            contentType = "text/csv; charset=UTF-8";
            fileExt = "csv";
            StringBuilder sb = new StringBuilder();
            sb.append("Key,Database,Collection_Unit,ID,Payload\n");
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                String k = entry.getKey();
                String val = entry.getValue().replace("\"", "\"\"");
                String[] parts = k.split(":");
                String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                String id = parts.length > 0 ? parts[parts.length - 1] : k;
                sb.append("\"").append(k).append("\",")
                  .append("\"").append(db).append("\",")
                  .append("\"").append(unit).append("\",")
                  .append("\"").append(id).append("\",")
                  .append("\"").append(val).append("\"\n");
            }
            outputBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        } else if ("excel".equalsIgnoreCase(format) || "xls".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            contentType = "application/vnd.ms-excel; charset=UTF-8";
            fileExt = "xls";
            StringBuilder sb = new StringBuilder();
            sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">");
            sb.append("<head><meta charset=\"utf-8\"/><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>Export</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>");
            sb.append("<body><table border=\"1\" style=\"border-collapse:collapse; font-family:Arial,sans-serif; font-size:12px;\">");
            sb.append("<tr style=\"background:#1e293b; color:#38bdf8; font-weight:bold; height:30px;\"><th>Storage Key</th><th>Database</th><th>Unit / Collection</th><th>Record ID</th><th>Payload JSON / Content</th></tr>");
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                String k = entry.getKey();
                String val = entry.getValue();
                String[] parts = k.split(":");
                String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                String id = parts.length > 0 ? parts[parts.length - 1] : k;
                sb.append("<tr>")
                  .append("<td style=\"font-weight:bold; color:#0f172a;\">").append(k).append("</td>")
                  .append("<td>").append(db).append("</td>")
                  .append("<td>").append(unit).append("</td>")
                  .append("<td style=\"font-family:monospace;\">").append(id).append("</td>")
                  .append("<td style=\"font-family:monospace;\">").append(val.replace("<", "&lt;").replace(">", "&gt;")).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table></body></html>");
            outputBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            contentType = "application/json; charset=UTF-8";
            fileExt = "json";
            JsonObject root = new JsonObject();
            root.addProperty("database", db);
            root.addProperty("engine", eng);
            root.addProperty("exportedAt", System.currentTimeMillis());
            root.addProperty("totalRecords", recordsMap.size());
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                dataObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("records", dataObj);
            outputBytes = jsonParser.toJson(root).getBytes(StandardCharsets.UTF_8);
        }

        String filename = db + "_" + (coll.isBlank() ? "all" : coll) + "_" + System.currentTimeMillis() + "." + fileExt;
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.sendResponseHeaders(200, outputBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(outputBytes);
            os.flush();
        }
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
                    String delDb = params.getOrDefault("target_db", targetDb);
                    String delId = params.getOrDefault("target_id", targetId);
                    String coll = params.getOrDefault("target_coll", params.getOrDefault("coll", "default"));
                    executeTypeSpecificDelete(engType, delDb, delId, coll, params);
                    targetDb = delDb;
                    selectedEngine = engType;
                    alertMessage = "[" + engType + "] Record '" + delId + "' successfully deleted from [" + delDb + "]!";
                    alertType = "badge-raft";
                } else if ("backup_database".equalsIgnoreCase(action)) {
                    String backupDb = params.getOrDefault("target_db", targetDb);
                    String backupDir = params.get("backup_dir");
                    String backupFilename = params.get("backup_filename");
                    var res = DatabaseBackupManager.createDatabaseBackup(engine, backupDb, backupDir, backupFilename);
                    alertMessage = res.message();
                    alertType = res.success() ? "badge-active" : "badge-raft";
                    targetDb = backupDb;
                } else if ("restore_database".equalsIgnoreCase(action)) {
                    String restoreDb = params.getOrDefault("target_db", targetDb);
                    String restoreFilePath = params.get("restore_file_path");
                    var res = DatabaseBackupManager.restoreDatabaseBackup(engine, restoreDb, restoreFilePath);
                    alertMessage = res.message();
                    alertType = res.success() ? "badge-active" : "badge-raft";
                    targetDb = restoreDb;
                } else if ("advanced_search".equalsIgnoreCase(action)) {
                    String searchEng = params.getOrDefault("search_engine", selectedEngine);
                    String searchDb = params.getOrDefault("target_db", targetDb);
                    String searchColl = params.getOrDefault("target_coll", "");
                    String searchKey = params.getOrDefault("search_key", params.getOrDefault("target_id", ""));
                    String searchKeyword = params.getOrDefault("search_keyword", "");
                    queryResultDisplay = executeAdvancedSearch(searchEng, searchDb, searchColl, searchKey, searchKeyword);
                    targetDb = searchDb;
                    alertMessage = "Advanced search executed on database [" + searchDb + "] (" + searchEng + ")!";
                    alertType = "badge-engine";
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
            prefix + coll + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id,
            coll + ":" + id,
            id
        };
        for (String k : candidateKeys) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
        }

        // Deep scan across all engine prefixes to catch any custom keyed records
        String[] pfxs = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        for (String pfx : pfxs) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(pfx + db + ":");
            for (String k : keys.keySet()) {
                if (k.endsWith(":" + id) || k.equals(id) || k.endsWith(":" + coll + ":" + id)) {
                    engine.getStorageCore().delete(k, System.currentTimeMillis());
                }
            }
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

    private String executeAdvancedSearch(String engineName, String db, String coll, String keyPattern, String keyword) {
        JsonObject result = new JsonObject();
        JsonArray matches = new JsonArray();
        String prefix = ("ALL".equalsIgnoreCase(engineName) || engineName.isBlank()) ? "" : getPrefixForEngine(engineName);
        String scanPrefix = prefix.isEmpty() ? (db + ":") : (prefix + db + ":");
        
        Map<String, byte[]> scanned = new LinkedHashMap<>(engine.getStorageCore().scanPrefix(scanPrefix));
        if (prefix.isEmpty()) {
            String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
            for (String p : prefixes) {
                scanned.putAll(engine.getStorageCore().scanPrefix(p + db + ":"));
            }
        }
        
        String keyLower = (keyPattern != null) ? keyPattern.trim().toLowerCase().replace("*", "") : "";
        String kwLower = (keyword != null) ? keyword.trim().toLowerCase() : "";
        
        for (Map.Entry<String, byte[]> entry : scanned.entrySet()) {
            String k = entry.getKey();
            if (k.contains("@")) continue;
            
            if (coll != null && !coll.isBlank() && !k.contains(":" + coll + ":") && !k.contains(":" + coll)) {
                continue;
            }
            
            if (!keyLower.isBlank() && !k.toLowerCase().contains(keyLower)) {
                continue;
            }
            
            String payloadStr = new String(entry.getValue(), StandardCharsets.UTF_8);
            if (!kwLower.isBlank() && !payloadStr.toLowerCase().contains(kwLower)) {
                continue;
            }
            
            JsonObject item = new JsonObject();
            item.addProperty("key", k);
            item.addProperty("length", entry.getValue().length);
            item.addProperty("preview", payloadStr.length() > 140 ? payloadStr.substring(0, 140) + "..." : payloadStr);
            matches.add(item);
        }
        
        result.addProperty("database", db);
        result.addProperty("engine", engineName);
        result.addProperty("matchCount", matches.size());
        result.add("matches", matches);
        return jsonParser.toJson(result);
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
                Icon.of("fas fa-sitemap").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:13px;")),
                Text.of("Multi-Model Storage Hierarchy Explorer")
            ).modifier(new Modifier().style("margin:0; font-size:13px; font-weight:600;")),
            Row.of(
                Button.of(Icon.of("fas fa-database"), Text.of(" + DB"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('createDbModal').style.display='flex'").cssClass("btn-action btn-primary").style("padding:2px 6px; font-size:9.5px; margin-right:4px;")),
                Button.of(Icon.of("fas fa-folder-plus"), Text.of(" + Unit"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('createUnitModal').style.display='flex'").cssClass("btn-action btn-secondary").style("padding:2px 6px; font-size:9.5px; margin-right:4px;")),
                Button.of(Icon.of("fas fa-download"), Text.of(" Backup"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openBackupDbModal('" + targetDb + "')").cssClass("btn-action btn-secondary").style("padding:2px 6px; font-size:9.5px; margin-right:4px; background:rgba(34,197,94,0.15); border-color:rgba(34,197,94,0.3); color:#4ade80;")),
                Button.of(Icon.of("fas fa-upload"), Text.of(" Restore"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openRestoreDbModal('" + targetDb + "')").cssClass("btn-action btn-secondary").style("padding:2px 6px; font-size:9.5px; margin-right:4px; background:rgba(168,85,247,0.15); border-color:rgba(168,85,247,0.3); color:#c084fc;")),
                Button.of(Icon.of("fas fa-file-export"), Text.of(" Export"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openExportDataModal('" + selectedEngine + "', '" + targetDb + "', '" + currentColl + "')").cssClass("btn-action btn-secondary").style("padding:2px 6px; font-size:9.5px; margin-right:4px; background:rgba(234,179,8,0.15); border-color:rgba(234,179,8,0.3); color:#fde047;")),
                Button.of(Icon.of("fas fa-search-plus"), Text.of(" Búsqueda Avanzada"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAdvancedSearchModal('" + selectedEngine + "', '" + targetDb + "', '" + currentColl + "')").cssClass("btn-action btn-secondary").style("padding:2px 6px; font-size:9.5px; background:rgba(56,189,248,0.15); border-color:rgba(56,189,248,0.3); color:#38bdf8;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:3px;"))
        ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-bottom:8px; flex-wrap:wrap; gap:6px;"));

        String[][] allEngSpecs = {
            {"DOCUMENT", "#3b82f6", "fas fa-file-alt", "Collections", "Collection", "Document", "fas fa-file-code"},
            {"KEYVALUE", "#10b981", "fas fa-key", "Namespaces", "Namespace", "Key-Value Pair", "fas fa-cube"},
            {"VECTOR", "#8b5cf6", "fas fa-project-diagram", "Vector Indexes", "Vector Index", "Embedding", "fas fa-braille"},
            {"GRAPH", "#ec4899", "fas fa-share-alt", "Labels", "Label", "Vertex / Edge", "fas fa-circle-nodes"},
            {"TIMESERIES", "#06b6d4", "fas fa-chart-line", "Metrics", "Metric", "Time Point", "fas fa-stopwatch"},
            {"COLUMN", "#f97316", "fas fa-table", "Column Families", "Column Family", "Dynamic Row", "fas fa-bars-staggered"},
            {"GEOSPATIAL", "#14b8a6", "fas fa-globe-americas", "Spatial Layers", "Spatial Layer", "GIS Feature", "fas fa-location-dot"},
            {"OBJECT", "#a855f7", "fas fa-archive", "Buckets", "Bucket", "BLOB Object", "fas fa-box-archive"},
            {"RECORDS", "#f43f5e", "fas fa-id-card", "Record Tables", "Record Table", "Record", "fas fa-address-card"}
        };

        List<Widget> dbCardWidgets = new ArrayList<>();

        for (String db : allDbs) {
            boolean isActiveDb = db.equalsIgnoreCase(targetDb);

            Widget dbLeft = Span.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:4px; color:#38bdf8; font-size:10px;")),
                Span.of(db).modifier(new Modifier().style("color:" + (isActiveDb ? "#38bdf8" : "#cbd5e1") + "; font-weight:700; font-size:10.5px;"))
            );

            List<Widget> dbRightWidgets = new ArrayList<>();
            if (isActiveDb) {
                dbRightWidgets.add(Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:7.5px; padding:1px 4px; margin-left:3px;")));
            } else {
                dbRightWidgets.add(Link.of(actionUrl + selectedEngine + "&target_db=" + db, "[Explore DB]").modifier(new Modifier().style("color:#38bdf8; font-size:8.5px; margin-left:4px; text-decoration:none; font-weight:600;")));
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
                        Icon.of(engIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:3px; font-size:7.5px;")),
                        Span.of(engName).modifier(new Modifier().style("font-weight:700; font-size:7.5px; text-transform:uppercase;")),
                        Text.of(" → "),
                        Span.of(unitPlural + " (" + unitsAndItems.size() + " " + (unitsAndItems.size() == 1 ? unitSingle : unitPlural) + ", " + totalItems + " items)").modifier(new Modifier().style("color:#cbd5e1; font-size:7px;"))
                    ).modifier(new Modifier().style("text-decoration:none; font-size:7.5px; color:" + (isEngActive ? "#38bdf8; font-weight:700;" : "#94a3b8;") + ";"));

                    Widget engAddUnitBtn = Button.of("+ " + unitSingle)
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddUnitModal('" + engName + "', '" + unitSingle + "')").style("background:none; border:1px solid " + engColor + "55; color:" + engColor + "; font-size:6.5px; padding:0 3px; border-radius:2px; cursor:pointer;"));

                    Widget engHeaderRow = Div.of(engHeaderLink, engAddUnitBtn)
                        .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                    List<Widget> unitListWidgets = new ArrayList<>();

                    for (Map.Entry<String, List<String>> unitEntry : unitsAndItems.entrySet()) {
                        String unitName = unitEntry.getKey();
                        List<String> items = unitEntry.getValue();
                        boolean isCurrColl = isEngActive && unitName.equalsIgnoreCase(currentColl);

                        Widget unitLeft = Span.of(
                            Text.of("📁 "),
                            Link.of(actionUrl + engName + "&target_db=" + db + "&coll=" + unitName, unitName).modifier(new Modifier().style("color:inherit; text-decoration:none; font-size:7.5px; font-weight:600;")),
                            Text.of(" "),
                            Span.of("(" + items.size() + ")").modifier(new Modifier().style("font-size:6.5px; color:#64748b; font-weight:normal;"))
                        ).modifier(new Modifier().style("color:" + (isCurrColl ? "#38bdf8" : "#cbd5e1") + "; font-size:7.5px; font-weight:600;"));

                        Widget unitAddObjBtn = Button.of("[+ " + itemLabel + "]")
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddObjectModal('" + engName + "', '" + unitName + "')").style("background:none; border:none; color:" + engColor + "; font-size:6.5px; cursor:pointer; padding:0;"));

                        Widget unitHeaderRow = Div.of(unitLeft, unitAddObjBtn)
                            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                        List<Widget> itemWidgets = new ArrayList<>();
                        if (items.isEmpty()) {
                            Widget emptyItem = Div.of(
                                Span.of("└── "),
                                Span.of("(Empty unit - click [+ " + itemLabel + "] to insert)").modifier(new Modifier().style("font-style:italic; font-size:6.5px;"))
                            ).modifier(new Modifier().style("font-size:6.5px; color:#64748b; padding:0;"));
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
                                    Icon.of(itemIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:2px; font-size:6.5px;")),
                                    Span.of(itemId).modifier(new Modifier().style("color:#f8fafc; font-weight:bold; font-size:7px; font-family:monospace;")),
                                    Text.of(" "),
                                    Span.of("v" + vCount).modifier(new Modifier().cssClass("store-badge").style("background:rgba(56,189,248,0.15); color:#38bdf8; font-size:6px; padding:0 2px; line-height:1;"))
                                );

                                List<Widget> itemBtnWidgets = new ArrayList<>();
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-edit"), Text.of(" Edit"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "', '" + payloadB64 + "')").style("background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:6.5px; padding:0 2px; border-radius:2px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-history"), Text.of(" v" + vCount))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "', '" + versionsB64 + "')").style("background:none; border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:6.5px; padding:0 2px; border-radius:2px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-trash-alt"), Text.of(" Delete"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalDeleteModal('" + engName + "', '" + db + "', '" + unitName + "', '" + itemId + "')").attribute("title", "Delete record").style("background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:6.5px; padding:0 2px; border-radius:2px; cursor:pointer;"))
                                );

                                if ("DOCUMENT".equalsIgnoreCase(engName)) {
                                    itemBtnWidgets.add(Link.of(actionUrl + engName + "&target_db=" + db + "&coll=" + unitName + "&target_id=" + itemId, "[Select]").modifier(new Modifier().style("color:#94a3b8; text-decoration:none; font-size:6.5px; margin-left:2px;")));
                                } else {
                                    itemBtnWidgets.add(Link.of(actionUrl + engName + "&target_db=" + db + "&target_id=" + itemId, "[Inspect]").modifier(new Modifier().style("color:#94a3b8; text-decoration:none; font-size:6.5px; margin-left:2px;")));
                                }

                                Widget itemRight = Div.of(itemBtnWidgets.toArray(new Widget[0]))
                                    .modifier(new Modifier().style("display:flex; align-items:center; gap:2px;"));

                                Widget itemRow = Div.of(itemLeft, itemRight)
                                    .modifier(new Modifier().style("font-size:7px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:0; line-height:1.2;"));

                                itemWidgets.add(itemRow);
                            }
                        }

                        Widget itemsContainer = Div.of(itemWidgets.toArray(new Widget[0]))
                            .modifier(new Modifier().style("margin-left:6px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:4px; margin-top:1px;"));

                        Widget unitBlock = Div.of(unitHeaderRow, itemsContainer)
                            .modifier(new Modifier().style("margin-bottom:2px; margin-top:1px;"));

                        unitListWidgets.add(unitBlock);
                    }

                    Widget unitSubtreeContainer = Div.of(unitListWidgets.toArray(new Widget[0]))
                        .modifier(new Modifier().style("margin-left:6px; border-left: 2px dotted rgba(255,255,255,0.12); padding-left:5px; margin-top:2px;"));

                    Widget engineBlock = Div.of(engHeaderRow, unitSubtreeContainer)
                        .modifier(new Modifier().style("margin-bottom:3px; background:" + (isEngActive ? "rgba(30,41,59,0.7)" : "rgba(15,23,42,0.3)") + "; padding:2px 4px; border-radius:4px; border:1px solid rgba(255,255,255,0.04);"));

                    engineSubtreeWidgets.add(engineBlock);
                }

                // Render Indexes & Schemas Subtree for this Database
                Map<String, JsonObject> dbIndexes = discoverIndexes(db);
                Map<String, JsonObject> dbSchemas = discoverSchemas(db);

                Widget idxSchemasHeaderLeft = Span.of(
                    Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:3px; font-size:7.5px;")),
                    Span.of("INDEXES & SCHEMAS").modifier(new Modifier().style("font-weight:bold; font-size:7.5px;")),
                    Text.of(" → "),
                    Span.of("(" + dbIndexes.size() + " Indexes, " + dbSchemas.size() + " Schemas)").modifier(new Modifier().style("color:#cbd5e1; font-size:7px;"))
                ).modifier(new Modifier().style("color:#eab308; font-weight:bold;"));

                Widget idxSchemasHeaderRight = Div.of(
                    Button.of(Icon.of("fas fa-plus"), Text.of(" Index"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddIndexModal('" + db + "')").style("background:none; border:1px solid rgba(234,179,8,0.5); color:#eab308; font-size:6.5px; padding:0 3px; border-radius:2px; cursor:pointer; margin-right:2px;")),
                    Button.of(Icon.of("fas fa-shield-alt"), Text.of(" Schema"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddSchemaModal('" + db + "')").style("background:none; border:1px solid rgba(56,189,248,0.5); color:#38bdf8; font-size:6.5px; padding:0 3px; border-radius:2px; cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; gap:2px;"));

                Widget idxSchemasHeaderRow = Div.of(idxSchemasHeaderLeft, idxSchemasHeaderRight)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                // Secondary & Composite Indexes Unit
                Widget indexesUnitLeft = Span.of(
                    Text.of("📁 Secondary & Composite Indexes "),
                    Span.of("(" + dbIndexes.size() + ")").modifier(new Modifier().style("font-size:6.5px; color:#64748b; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#fde047; font-size:7.5px; font-weight:600;"));

                Widget indexesUnitAddBtn = Button.of("[+ Index]")
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddIndexModal('" + db + "')").style("background:none; border:none; color:#eab308; font-size:6.5px; cursor:pointer; padding:0;"));

                Widget indexesUnitHeaderRow = Div.of(indexesUnitLeft, indexesUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> indexItemWidgets = new ArrayList<>();
                if (dbIndexes.isEmpty()) {
                    indexItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No secondary indexes)").modifier(new Modifier().style("font-style:italic; font-size:6.5px;"))
                        ).modifier(new Modifier().style("font-size:6.5px; color:#64748b; padding:0;"))
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
                            Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:2px; font-size:6.5px;")),
                            Span.of(idxName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold; font-size:7px; font-family:monospace;")),
                            Text.of(" "),
                            Span.of(idxType).modifier(new Modifier().cssClass("store-badge").style("background:rgba(234,179,8,0.15); color:#fde047; font-size:6px; padding:0 2px;")),
                            Text.of(" on '"),
                            Span.of(idxField).modifier(new Modifier().style("color:#38bdf8; font-family:monospace; font-size:6.5px;")),
                            Text.of("' (" + idxColl + ")")
                        );

                        Widget deleteIdxForm = Form.of(
                            InputHidden.of("action", "delete_index"),
                            InputHidden.of("target_db", db),
                            InputHidden.of("index_name", idxName),
                            Button.of(Icon.of("fas fa-trash"))
                                .modifier(new Modifier().attribute("type", "submit").attribute("onclick", "return confirm('Delete index " + idxName + "?');").style("background:none; border:none; color:#ef4444; font-size:6.5px; cursor:pointer; padding:0;"))
                        ).action(actionUrl + selectedEngine).method("POST").modifier(new Modifier().style("display:inline; margin:0;"));

                        Widget idxItemRow = Div.of(idxItemLeft, deleteIdxForm)
                            .modifier(new Modifier().style("font-size:7px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:0; line-height:1.2;"));

                        indexItemWidgets.add(idxItemRow);
                    }
                }

                Widget indexItemsContainer = Div.of(indexItemWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:6px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:4px; margin-top:1px;"));

                Widget indexesUnitBlock = Div.of(indexesUnitHeaderRow, indexItemsContainer)
                    .modifier(new Modifier().style("margin-bottom:2px; margin-top:2px;"));

                // Validation Schemas Unit
                Widget schemasUnitLeft = Span.of(
                    Text.of("📁 Validation Schemas "),
                    Span.of("(" + dbSchemas.size() + ")").modifier(new Modifier().style("font-size:6.5px; color:#64748b; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#38bdf8; font-size:7.5px; font-weight:600;"));

                Widget schemasUnitAddBtn = Button.of("[+ Schema]")
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddSchemaModal('" + db + "')").style("background:none; border:none; color:#38bdf8; font-size:6.5px; cursor:pointer; padding:0;"));

                Widget schemasUnitHeaderRow = Div.of(schemasUnitLeft, schemasUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> schemaItemWidgets = new ArrayList<>();
                if (dbSchemas.isEmpty()) {
                    schemaItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No validation schemas)").modifier(new Modifier().style("font-style:italic; font-size:6.5px;"))
                        ).modifier(new Modifier().style("font-size:6.5px; color:#64748b; padding:0;"))
                    );
                } else {
                    for (Map.Entry<String, JsonObject> scEntry : dbSchemas.entrySet()) {
                        String scName = scEntry.getKey();

                        Widget scItemLeft = Span.of(
                            Text.of("└── "),
                            Icon.of("fas fa-shield-alt").modifier(new Modifier().style("color:#38bdf8; margin-right:2px; font-size:6.5px;")),
                            Span.of(scName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold; font-size:7px; font-family:monospace;"))
                        );

                        Widget deleteScForm = Form.of(
                            InputHidden.of("action", "delete_schema"),
                            InputHidden.of("target_db", db),
                            InputHidden.of("schema_name", scName),
                            Button.of(Icon.of("fas fa-trash"))
                                .modifier(new Modifier().attribute("type", "submit").attribute("onclick", "return confirm('Delete schema " + scName + "?');").style("background:none; border:none; color:#ef4444; font-size:6.5px; cursor:pointer; padding:0;"))
                        ).action(actionUrl + selectedEngine).method("POST").modifier(new Modifier().style("display:inline; margin:0;"));

                        Widget scItemRow = Div.of(scItemLeft, deleteScForm)
                            .modifier(new Modifier().style("font-size:7px; color:#94a3b8; display:flex; justify-content:space-between; align-items:center; padding:0; line-height:1.2;"));

                        schemaItemWidgets.add(scItemRow);
                    }
                }

                Widget schemaItemsContainer = Div.of(schemaItemWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:6px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:4px; margin-top:1px;"));

                Widget schemasUnitBlock = Div.of(schemasUnitHeaderRow, schemaItemsContainer)
                    .modifier(new Modifier().style("margin-bottom:2px; margin-top:2px;"));

                Widget idxSchemasSubtreeContainer = Div.of(indexesUnitBlock, schemasUnitBlock)
                    .modifier(new Modifier().style("margin-left:6px; border-left: 2px dotted rgba(234,179,8,0.3); padding-left:5px; margin-top:2px;"));

                Widget idxSchemasBlock = Div.of(idxSchemasHeaderRow, idxSchemasSubtreeContainer)
                    .modifier(new Modifier().style("margin-bottom:4px; background:rgba(30,41,59,0.7); padding:3px 5px; border-radius:4px; border:1px solid rgba(234,179,8,0.25);"));

                engineSubtreeWidgets.add(idxSchemasBlock);

                Widget dbSubtreeContainer = Div.of(engineSubtreeWidgets.toArray(new Widget[0]))
                    .modifier(new Modifier().style("margin-left:8px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:6px; margin-top:3px;"));

                dbContentWidgets.add(dbSubtreeContainer);
            }

            Widget dbCard = Div.of(dbContentWidgets.toArray(new Widget[0]))
                .modifier(new Modifier().style("margin-bottom:5px; padding:3px 6px; border-radius:6px; background:" + (isActiveDb ? "rgba(56,189,248,0.06)" : "transparent") + "; border:" + (isActiveDb ? "1px solid rgba(56,189,248,0.2)" : "1px solid transparent") + ";"));

            dbCardWidgets.add(dbCard);
        }

        Widget treeBody = Div.of(dbCardWidgets.toArray(new Widget[0]))
            .modifier(new Modifier().style("max-height:600px; overflow-y:auto; padding-right:4px;"));

        return Div.of(treeHeader, treeBody)
            .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; border: 1px solid rgba(56,189,248,0.3); background:rgba(18,24,38,0.9);"));
    }

    private Widget createEngineModals(String engineKey, String targetDb, String currentColl) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey);

        List<Widget> modals = new ArrayList<>();
        modals.add(buildCreateDbModal(engineKey, actionUrl));
        modals.add(buildCreateUnitModal(targetDb, actionUrl));
        modals.add(buildBackupDbModal(actionUrl, targetDb));
        modals.add(buildRestoreDbModal(actionUrl, targetDb));
        modals.add(buildConfirmDbRestoreModal(actionUrl));
        modals.add(buildExportDataModal(actionUrl, targetDb, currentColl, engineKey));
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

    private Widget createLabel(String text) {
        return Label.of(text).modifier(new Modifier().style("display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;"));
    }

    private Widget createLabel(String text, String id) {
        return Label.of(text).id(id).modifier(new Modifier().style("display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;"));
    }

    private TextField createTextInput(String name, String placeholder, String value, String color) {
        TextField tf = TextField.of(name, placeholder != null ? placeholder : "");
        if (value != null && !value.isEmpty()) tf.value(value);
        String textColor = (color != null && !color.isEmpty()) ? color : "#f8fafc";
        tf.modifier(new Modifier().style("width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:" + textColor + "; font-size:13px; box-sizing:border-box;"));
        return tf;
    }

    private TextArea createTextArea(String name, int rows, String placeholder, String value) {
        TextArea ta = TextArea.create().name(name).rows(rows);
        if (placeholder != null && !placeholder.isEmpty()) ta.placeholder(placeholder);
        if (value != null && !value.isEmpty()) ta.value(value);
        ta.modifier(new Modifier().style("width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; font-size:12px; font-family:monospace; box-sizing:border-box;"));
        return ta;
    }

    private Widget createSelectOne(String name, String id, String color, String onChange, Map<String, String> options, String selectedValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<select name='").append(name).append("' ");
        if (id != null && !id.isEmpty()) sb.append("id='").append(id).append("' ");
        if (onChange != null && !onChange.isEmpty()) sb.append("onchange='").append(onChange).append("' ");
        String textColor = (color != null && !color.isEmpty()) ? color : "#f8fafc";
        sb.append("style='width:100%; padding:8px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:").append(textColor).append("; font-size:13px; box-sizing:border-box;'>\n");
        for (Map.Entry<String, String> opt : options.entrySet()) {
            String sel = opt.getKey().equalsIgnoreCase(selectedValue) ? " selected" : "";
            sb.append("  <option value='").append(opt.getKey()).append("'").append(sel).append(">").append(opt.getValue()).append("</option>\n");
        }
        sb.append("</select>");
        return SelectOne.of(RawHtml.of(sb.toString()));
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
        Map<String, String> idModes = new LinkedHashMap<>();
        idModes.put("UUID", "1. UUID (Composite: CPU + Time + DB + UUID Entropy)");
        idModes.put("AUTOINCREMENT", "2. Autoincrementable (Sequential Counter: 1, 2, 3...)");
        idModes.put("MANUAL", "3. Manual Mode (Custom User Specified ID)");

        return Inputs.of(
            Inputs.of(
                createLabel("ID Generation Strategy:"),
                createSelectOne("id_gen_mode", "", engColor, "handleIdModeChange(this)", idModes, "UUID")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel(targetIdLabel),
                createTextInput("target_id", targetIdPlaceholder, "", "#f8fafc")
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
                Span.of("Database: ").modifier(new Modifier().style("font-weight:bold;")),
                Span.of("").id(dbSpanId).modifier(new Modifier().style("color:#f8fafc;"))
            ),
            Div.of(
                Span.of("Engine: ").modifier(new Modifier().style("font-weight:bold;")),
                Span.of(badgeText).modifier(new Modifier().cssClass("store-badge " + badgeClass))
            )
        ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px; font-size:12px; color:#94a3b8; background:rgba(255,255,255,0.03); padding:8px 12px; border-radius:6px;"));
    }

    private Widget createModalFormActions(String modalId, String submitText, String submitIcon, String submitColor) {
        return Div.of(
            Button.of(Text.of("Cancel"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('" + modalId + "').style.display='none'").cssClass("btn-action btn-secondary")),
            Button.of(Icon.of(submitIcon), Text.of(" " + submitText))
                .modifier(new Modifier().attribute("type", "submit").cssClass("btn-action btn-primary").style(submitColor.isEmpty() ? "" : "background:" + submitColor + ";"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:8px;"));
    }

    private Widget buildCreateDbModal(String engineKey, String actionUrl) {
        Widget header = createModalHeader("Create Multi-Model Database", "fas fa-database", "#38bdf8", "createDbModal");

        Map<String, String> enginesMap = new LinkedHashMap<>();
        enginesMap.put("DOCUMENT", "DOCUMENT (NoSQL Collections)");
        enginesMap.put("KEYVALUE", "KEYVALUE (Cache & Buckets)");
        enginesMap.put("VECTOR", "VECTOR (AI Embeddings)");
        enginesMap.put("GRAPH", "GRAPH (Node & Edge Labels)");
        enginesMap.put("TIMESERIES", "TIMESERIES (IoT Metrics)");
        enginesMap.put("COLUMN", "COLUMN (Column Families)");
        enginesMap.put("GEOSPATIAL", "GEOSPATIAL (Spatial Layers)");
        enginesMap.put("OBJECT", "OBJECT (BLOB Buckets)");
        enginesMap.put("RECORDS", "RECORDS (Java 25 Record Tables)");

        Widget form = Form.of(
            InputHidden.of("action", "create_db"),
            Inputs.of(
                createLabel("Database Name (StorageContainer):"),
                createTextInput("new_db_name", "e.g. ecommerce_db, inventory_db", "", "#f8fafc").attribute("required", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Primary Multi-Model Engine Subtree:"),
                createSelectOne("initial_engine", "", "#38bdf8", "", enginesMap, engineKey)
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Initial Subtree Unit (Collection / Bucket / Table):"),
                createTextInput("initial_unit", "", "default", "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createDbModal", "Initialize Database", "fas fa-plus", "")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createDbModal", "520px", "rgba(56,189,248,0.4)", header, form);
    }

    private Widget buildCreateUnitModal(String targetDb, String actionUrl) {
        Widget header = createModalHeader("Add Subtree Unit", "fas fa-folder-plus", "#a855f7", "createUnitModal");

        Map<String, String> unitTypes = new LinkedHashMap<>();
        unitTypes.put("DOCUMENT", "DOCUMENT (Colección / Collection)");
        unitTypes.put("KEYVALUE", "KEYVALUE (Namespace / Bucket)");
        unitTypes.put("VECTOR", "VECTOR (Vector Index / Collection)");
        unitTypes.put("GRAPH", "GRAPH (Node & Edge Label)");
        unitTypes.put("TIMESERIES", "TIMESERIES (Metric / Series Feed)");
        unitTypes.put("COLUMN", "COLUMN (Column Family / Table)");
        unitTypes.put("GEOSPATIAL", "GEOSPATIAL (Spatial Layer)");
        unitTypes.put("OBJECT", "OBJECT (Storage Bucket / Container)");
        unitTypes.put("RECORDS", "RECORDS (Record Table / Schema)");

        Widget form = Form.of(
            InputHidden.of("action", "create_unit"),
            InputHidden.of("target_db", targetDb),
            Inputs.of(
                createLabel("Target Database:"),
                createTextInput("target_db_display", "", targetDb, "#38bdf8").attribute("disabled", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Engine Subtree Type:"),
                createSelectOne("engine_type", "modalUnitEngineSelect", "#f8fafc", "", unitTypes, "DOCUMENT")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Unit Name:", "modalUnitNameLabel"),
                createTextInput("unit_name", "e.g. products, cache_layer, telemetry", "", "#f8fafc").attribute("required", "true")
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
            Inputs.of(
                createLabel("Target Collection:"),
                createTextInput("target_coll", "", currentColl, "#38bdf8")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#38bdf8", "Custom Document ID:", "e.g. prod_1001, doc_special"),
            Inputs.of(
                createLabel("Class / Schema (Optional):"),
                createTextInput("doc_class", "com.jettra.model.Customer", "", "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("JSON Payload:"),
                createTextArea("doc_payload", 5, "", "{\n  \"name\": \"Sample Document\",\n  \"status\": \"ACTIVE\",\n  \"rating\": 4.9\n}")
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
            Inputs.of(
                createLabel("Bucket / Namespace:"),
                createTextInput("target_coll", "", "default", "#10b981")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#10b981", "Custom Key Name:", "e.g. sess_token_99, config_app"),
            Inputs.of(
                createLabel("String or JSON Value:"),
                createTextArea("kv_value", 4, "Enter value to cache/store...", "")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addKeyValueModal", "Store Key-Value", "fas fa-save", "#10b981")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=KEYVALUE"));

        return createModalOverlay("addKeyValueModal", "520px", "rgba(160,185,129,0.4)", header, form);
    }

    private Widget buildAddVectorModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Vector Embedding]", "fas fa-project-diagram", "#8b5cf6", "addVectorModal");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Inputs.of(
                createLabel("Vector Index:"),
                createTextInput("target_coll", "", "default", "#8b5cf6")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#8b5cf6", "Custom Vector ID:", "e.g. vec_emb_001"),
            Inputs.of(
                createLabel("Vector Coordinates (float array):"),
                createTextInput("vector_coords", "", "0.12, 0.45, 0.88, 0.31, 0.65", "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Metadata / Payload JSON:"),
                createTextArea("vector_meta", 3, "", "{\"category\": \"AI Model\", \"source\": \"embeddings_v3\"}")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("addVectorModal", "Insert Vector", "fas fa-project-diagram", "#8b5cf6")
        ).method("POST").action(JettraServer.resolvePath("/engines?engine=VECTOR"));

        return createModalOverlay("addVectorModal", "520px", "rgba(139,92,246,0.4)", header, form);
    }

    private Widget buildAddGraphModal(String targetDb) {
        Widget header = createModalHeader("[+ Add Vertex / Edge]", "fas fa-share-alt", "#ec4899", "addGraphModal");

        Map<String, String> graphModes = new LinkedHashMap<>();
        graphModes.put("node", "Vertex (Node)");
        graphModes.put("edge", "Edge (Relationship)");

        Widget form = Form.of(
            InputHidden.of("action", "insert_object"),
            InputHidden.of("target_db", targetDb),
            Div.of(
                Inputs.of(
                    createLabel("Element Type:"),
                    createSelectOne("graph_mode", "", "#ec4899", "", graphModes, "node")
                ),
                Inputs.of(
                    createLabel("Node Label / Group:"),
                    createTextInput("target_coll", "", "User", "#f8fafc")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            createIdStrategySection("#ec4899", "Custom Vertex ID:", "e.g. user_node_101"),
            Inputs.of(
                createLabel("Graph Properties JSON:"),
                createTextArea("node_props", 3, "", "{\"name\": \"Alice\", \"role\": \"Admin\"}")
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
            Inputs.of(
                createLabel("Metric Name:"),
                createTextInput("target_coll", "", "telemetry", "#06b6d4")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#06b6d4", "Custom Point ID:", "e.g. ts_pt_1001"),
            Div.of(
                Inputs.of(
                    createLabel("Value (Double):"),
                    createTextInput("ts_value", "", "98.6", "#f8fafc").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                ),
                Inputs.of(
                    createLabel("Unit / Scale:"),
                    createTextInput("ts_unit", "celsius, ms, %", "", "#f8fafc")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Inputs.of(
                createLabel("Timestamp (ms):"),
                createTextInput("ts_timestamp", "", String.valueOf(System.currentTimeMillis()), "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Tags JSON:"),
                createTextArea("ts_tags", 2, "", "{\"sensor_id\": \"SN-01\", \"zone\": \"rack_A\"}")
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
            Inputs.of(
                createLabel("Column Family:"),
                createTextInput("target_coll", "", "analytics", "#f97316")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#f97316", "Custom Row Key:", "e.g. row_2026_01"),
            Inputs.of(
                createLabel("Column Data (JSON or col:val):"),
                createTextArea("col_data", 4, "", "{\"views\": 1520, \"status\": \"PROCESSED\", \"latency_p99\": 14.2}")
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
            Inputs.of(
                createLabel("Spatial Layer:"),
                createTextInput("target_coll", "", "stores_layer", "#14b8a6")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#14b8a6", "Custom Feature ID:", "e.g. poi_station_01"),
            Div.of(
                Inputs.of(
                    createLabel("Latitude (-90..90):"),
                    createTextInput("geo_lat", "", "8.9833", "#f8fafc").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                ),
                Inputs.of(
                    createLabel("Longitude (-180..180):"),
                    createTextInput("geo_lon", "", "-79.5167", "#f8fafc").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Inputs.of(
                createLabel("Place Name / Metadata:"),
                createTextInput("geo_name", "", "Metropolitan Hub", "#f8fafc")
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
            Inputs.of(
                createLabel("Storage Bucket:"),
                createTextInput("target_coll", "", "media_bucket", "#a855f7")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#a855f7", "Custom Object ID:", "e.g. media_video_100"),
            Inputs.of(
                createLabel("MIME Content-Type:"),
                createTextInput("obj_mime", "", "application/json", "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Payload / Raw Content:"),
                createTextArea("obj_payload", 4, "", "Binary BLOB chunk stream content")
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
            Inputs.of(
                createLabel("Record Table:"),
                createTextInput("target_coll", "", "employee_records", "#f43f5e")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            createIdStrategySection("#f43f5e", "Custom Record ID:", "e.g. emp_101, rec_person_01"),
            Inputs.of(
                createLabel("Java 25 Record Class:"),
                createTextInput("rec_class", "", "com.jettra.model.PersonRecord", "#f8fafc")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Record Components JSON:"),
                createTextArea("rec_payload", 4, "", "{\"name\": \"Carlos Ruiz\", \"role\": \"Lead Architect\", \"department\": \"Engineering\"}")
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
            InputHidden.of("target_db", "").id("editDocDbInput"),
            InputHidden.of("target_id", "").id("editDocIdInput"),
            createEditInfoBox("editDocDbDisplay", "badge-active", "DOCUMENT"),
            Inputs.of(
                createLabel("Collection:"),
                createTextInput("target_coll", "", "", "#38bdf8").id("editDocCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Class / Schema (Optional):"),
                createTextInput("doc_class", "com.jettra.model.Customer", "", "#f8fafc").id("editDocClassInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("JSON Document Payload:"),
                createTextArea("doc_payload", 6, "", "").id("editDocPayloadInput")
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
            InputHidden.of("target_db", "").id("editKvDbInput"),
            InputHidden.of("target_id", "").id("editKvIdInput"),
            createEditInfoBox("editKvDbDisplay", "badge-kv", "KEYVALUE"),
            Inputs.of(
                createLabel("Bucket / Namespace:"),
                createTextInput("target_coll", "", "", "#10b981").id("editKvCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Stored Value / Payload:"),
                createTextArea("kv_value", 6, "", "").id("editKvValueInput")
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
            InputHidden.of("target_db", "").id("editVecDbInput"),
            InputHidden.of("target_id", "").id("editVecIdInput"),
            createEditInfoBox("editVecDbDisplay", "badge-vector", "VECTOR"),
            Inputs.of(
                createLabel("Vector Index / Collection:"),
                createTextInput("target_coll", "", "", "#c084fc").id("editVecCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Vector Coordinates (float array):"),
                createTextInput("vector_coords", "0.12, 0.45, 0.88, 0.31", "", "#f8fafc").id("editVecCoordsInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Metadata JSON:"),
                createTextArea("vector_meta", 4, "", "").id("editVecMetaInput")
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
            InputHidden.of("target_db", "").id("editGraphDbInput"),
            InputHidden.of("target_id", "").id("editGraphIdInput"),
            createEditInfoBox("editGraphDbDisplay", "badge-graph", "GRAPH"),
            Inputs.of(
                createLabel("Node Label / Group:"),
                createTextInput("target_coll", "", "", "#ec4899").id("editGraphCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Graph Properties JSON:"),
                createTextArea("node_props", 5, "", "").id("editGraphPropsInput")
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
            InputHidden.of("target_db", "").id("editTsDbInput"),
            InputHidden.of("target_id", "").id("editTsIdInput"),
            createEditInfoBox("editTsDbDisplay", "badge-ts", "TIMESERIES"),
            Inputs.of(
                createLabel("Metric Name / Series:"),
                createTextInput("target_coll", "", "", "#06b6d4").id("editTsCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                Inputs.of(
                    createLabel("Value (Double):"),
                    createTextInput("ts_value", "", "", "#f8fafc").id("editTsValueInput").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                ),
                Inputs.of(
                    createLabel("Unit / Scale:"),
                    createTextInput("ts_unit", "celsius, ms, %", "", "#f8fafc").id("editTsUnitInput")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Inputs.of(
                createLabel("Timestamp (ms):"),
                createTextInput("ts_timestamp", "", "", "#f8fafc").id("editTsTimestampInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Tags JSON:"),
                createTextArea("ts_tags", 3, "", "").id("editTsTagsInput")
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
            InputHidden.of("target_db", "").id("editColDbInput"),
            InputHidden.of("target_id", "").id("editColIdInput"),
            createEditInfoBox("editColDbDisplay", "badge-column", "COLUMN"),
            Inputs.of(
                createLabel("Column Family:"),
                createTextInput("target_coll", "", "", "#f97316").id("editColCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Column Data (JSON):"),
                createTextArea("col_data", 5, "", "").id("editColDataInput")
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
            InputHidden.of("target_db", "").id("editGeoDbInput"),
            InputHidden.of("target_id", "").id("editGeoIdInput"),
            createEditInfoBox("editGeoDbDisplay", "badge-geo", "GEOSPATIAL"),
            Inputs.of(
                createLabel("Spatial Layer:"),
                createTextInput("target_coll", "", "", "#14b8a6").id("editGeoCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Div.of(
                Inputs.of(
                    createLabel("Latitude (-90..90):"),
                    createTextInput("geo_lat", "", "", "#f8fafc").id("editGeoLatInput").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                ),
                Inputs.of(
                    createLabel("Longitude (-180..180):"),
                    createTextInput("geo_lon", "", "", "#f8fafc").id("editGeoLonInput").attribute("type", "number").attribute("step", "any").attribute("required", "true")
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;")),
            Inputs.of(
                createLabel("Place Name / Metadata:"),
                createTextInput("geo_name", "", "", "#f8fafc").id("editGeoNameInput")
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
            InputHidden.of("target_db", "").id("editObjDbInput"),
            InputHidden.of("target_id", "").id("editObjIdInput"),
            createEditInfoBox("editObjDbDisplay", "badge-object", "OBJECT"),
            Inputs.of(
                createLabel("Storage Bucket:"),
                createTextInput("target_coll", "", "", "#a855f7").id("editObjCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("MIME Content-Type:"),
                createTextInput("obj_mime", "", "", "#f8fafc").id("editObjMimeInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Payload / Raw Content:"),
                createTextArea("obj_payload", 5, "", "").id("editObjPayloadInput")
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
            InputHidden.of("target_db", "").id("editRecDbInput"),
            InputHidden.of("target_id", "").id("editRecIdInput"),
            createEditInfoBox("editRecDbDisplay", "badge-records", "RECORDS"),
            Inputs.of(
                createLabel("Record Table:"),
                createTextInput("target_coll", "", "", "#f43f5e").id("editRecCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Java 25 Record Class:"),
                createTextInput("rec_class", "", "", "#f8fafc").id("editRecClassInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Record Components JSON:"),
                createTextArea("rec_payload", 5, "", "").id("editRecPayloadInput")
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
            InputHidden.of("engine_type", "").id("restoreEngineTypeInput"),
            InputHidden.of("target_db", "").id("restoreRecordDbInput"),
            InputHidden.of("target_coll", "").id("restoreRecordCollInput"),
            InputHidden.of("target_id", "").id("restoreRecordIdInput"),
            InputHidden.of("version_ts", "").id("restoreVersionTsInput"),
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
            InputHidden.of("engine_type", "").id("confirmRestoreEngineInput"),
            InputHidden.of("target_db", "").id("confirmRestoreDbInput"),
            InputHidden.of("target_coll", "").id("confirmRestoreCollInput"),
            InputHidden.of("target_id", "").id("confirmRestoreIdInput"),
            InputHidden.of("version_ts", "").id("confirmRestoreTsInput"),
            Div.of(
                Paragraph.of(Text.of("Are you sure you want to restore item version from timestamp "), Span.of("").id("confirmRestoreTsDisplay").modifier(new Modifier().style("color:#c084fc; font-weight:bold;")), Text.of("?")).modifier(new Modifier().style("margin:0 0 8px 0;")),
                Div.of(
                    Span.of(Text.of("Record ID: "), Span.of("").id("confirmRestoreIdDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;"))),
                    Span.of(Text.of("Engine: "), Span.of("").id("confirmRestoreEngineDisplay").modifier(new Modifier().cssClass("store-badge badge-active"))).modifier(new Modifier().style("margin-left:12px;")),
                    Span.of(Text.of("Date: "), Span.of("").id("confirmRestoreDateDisplay").modifier(new Modifier().style("color:#f8fafc;"))).modifier(new Modifier().style("margin-left:12px;"))
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
            InputHidden.of("engine_type", "").id("confirmDeleteEngineInput"),
            InputHidden.of("target_db", "").id("confirmDeleteDbInput"),
            InputHidden.of("target_coll", "").id("confirmDeleteCollInput"),
            InputHidden.of("target_id", "").id("confirmDeleteIdInput"),
            Div.of(
                Paragraph.of(Text.of("Are you sure you want to permanently delete record "), Span.of("").id("confirmDeleteIdDisplay").modifier(new Modifier().style("color:#ef4444; font-weight:bold;")), Text.of("?")).modifier(new Modifier().style("margin:0 0 8px 0;")),
                Div.of(
                    Span.of(Text.of("Engine: "), Span.of("").id("confirmDeleteEngineDisplay").modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))),
                    Span.of(Text.of("Database: "), Span.of("").id("confirmDeleteDbDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;"))).modifier(new Modifier().style("margin-left:12px;")),
                    Span.of(Text.of("Unit: "), Span.of("").id("confirmDeleteCollDisplay").modifier(new Modifier().style("color:#cbd5e1; font-weight:bold;"))).modifier(new Modifier().style("margin-left:12px;"))
                ).modifier(new Modifier().style("font-size:11px; color:#cbd5e1;"))
            ).modifier(new Modifier().style("background:rgba(239,68,68,0.1); border-left:3px solid #ef4444; padding:12px 14px; border-radius:6px; margin-bottom:16px; font-size:13px; color:#f8fafc;")),
            Paragraph.of("This operation is immediate and removes the record from storage.").modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin:0 0 16px 0;")),
            createModalFormActions("confirmDeleteModal", "Confirm Delete", "fas fa-trash-alt", "#ef4444")
        ).method("POST").action(actionUrl);

        return createConfirmationModalOverlay("confirmDeleteModal", "500px", "rgba(239,68,68,0.5)", header, form);
    }

    private Widget buildBackupDbModal(String actionUrl, String targetDb) {
        Widget header = createModalHeader("Database Backup (Snapshot .ZIP)", "fas fa-download", "#22c55e", "backupDbModal");
        Set<String> dbs = discoverAllDatabases();
        Map<String, String> dbMap = new LinkedHashMap<>();
        for (String d : dbs) {
            dbMap.put(d, d);
        }
        if (!dbMap.containsKey(targetDb)) dbMap.put(targetDb, targetDb);

        String defaultDir = DatabaseBackupManager.getDefaultBackupDir(targetDb).toAbsolutePath().toString();
        String defaultFilename = DatabaseBackupManager.generateBackupFileName(targetDb);

        Widget form = Form.of(
            InputHidden.of("action", "backup_database"),
            Inputs.of(
                createLabel("Target Database:"),
                createSelectOne("target_db", "", "#22c55e", "backupDbSelect", dbMap, targetDb)
                    .modifier(new Modifier().attribute("onchange", "onBackupDbChange(this)"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Destination Directory (Storage Path):"),
                createTextInput("backup_dir", "e.g. ~/data/backup/" + targetDb, defaultDir, "#f8fafc").id("backupDirInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Backup File Name (.ZIP):"),
                createTextInput("backup_filename", "<db>yyyy-mm-dd-hh-mm-ss.zip", defaultFilename, "#f8fafc").id("backupFilenameInput")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            Paragraph.of("Creates a compressed archive containing all multi-model storage partitions, indexes, and schema definitions.").modifier(new Modifier().style("font-size:11px; color:#94a3b8; margin:0 0 16px 0;")),
            createModalFormActions("backupDbModal", "Create Backup (.ZIP)", "fas fa-file-archive", "#22c55e")
        ).method("POST").action(actionUrl);

        return createModalOverlay("backupDbModal", "560px", "rgba(34,197,94,0.4)", header, form);
    }

    private Widget buildRestoreDbModal(String actionUrl, String targetDb) {
        Widget header = createModalHeader("Database Restore (from .ZIP)", "fas fa-upload", "#a855f7", "restoreDbModal");
        Set<String> dbs = discoverAllDatabases();
        Map<String, String> dbMap = new LinkedHashMap<>();
        for (String d : dbs) {
            dbMap.put(d, d);
        }
        if (!dbMap.containsKey(targetDb)) dbMap.put(targetDb, targetDb);

        String defaultDir = DatabaseBackupManager.getDefaultBackupDir(targetDb).toAbsolutePath().toString();
        List<BackupFileInfo> availableBackups = DatabaseBackupManager.listBackups(targetDb, defaultDir);

        List<Widget> formElements = new ArrayList<>();
        formElements.add(
            Inputs.of(
                createLabel("Database to Restore:"),
                createSelectOne("target_db_restore", "", "#a855f7", "restoreDbSelect", dbMap, targetDb)
                    .modifier(new Modifier().attribute("onchange", "onRestoreDbChange(this)"))
            ).modifier(new Modifier().style("margin-bottom:12px;"))
        );
        formElements.add(
            Inputs.of(
                createLabel("Backup Directory Path:"),
                createTextInput("restore_dir_path", "e.g. ~/data/backup/" + targetDb, defaultDir, "#f8fafc").id("restoreDirInput")
            ).modifier(new Modifier().style("margin-bottom:12px;"))
        );
        formElements.add(
            Inputs.of(
                createLabel("Backup Archive File (.ZIP Path):"),
                createTextInput("restore_file_path_input", "Select a backup below or enter path", availableBackups.isEmpty() ? "" : availableBackups.get(0).fullPath(), "#f8fafc").id("restoreFileInput")
            ).modifier(new Modifier().style("margin-bottom:14px;"))
        );

        List<Widget> backupItems = new ArrayList<>();
        if (availableBackups.isEmpty()) {
            backupItems.add(Div.of(Text.of("No backups found in " + defaultDir + ". You can enter a custom .zip path above.")).modifier(new Modifier().style("font-size:11px; color:#94a3b8; padding:8px; text-align:center; font-style:italic;")));
        } else {
            for (BackupFileInfo info : availableBackups) {
                Widget item = Div.of(
                    Div.of(
                        Icon.of("fas fa-file-archive").modifier(new Modifier().style("color:#a855f7; margin-right:6px; font-size:12px;")),
                        Span.of(info.fileName()).modifier(new Modifier().style("font-weight:bold; font-size:11px; color:#f8fafc; font-family:monospace;")),
                        Span.of(" (" + (info.sizeBytes() / 1024) + " KB, " + info.formattedDate() + ")").modifier(new Modifier().style("font-size:10px; color:#94a3b8;"))
                    ),
                    Button.of(Text.of("Select"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('restoreFileInput').value='" + info.fullPath().replace("\\", "\\\\") + "'").style("background:rgba(168,85,247,0.2); border:1px solid rgba(168,85,247,0.4); color:#c084fc; font-size:10px; padding:2px 8px; border-radius:3px; cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:6px 8px; border-bottom:1px solid rgba(255,255,255,0.05);"));
                backupItems.add(item);
            }
        }

        Widget backupsListContainer = Div.of(
            createLabel("Available Backups in Directory:"),
            Div.of(backupItems.toArray(new Widget[0])).id("restoreBackupsList").modifier(new Modifier().style("max-height:140px; overflow-y:auto; background:rgba(15,23,42,0.6); border:1px solid rgba(255,255,255,0.08); border-radius:6px; margin-bottom:14px;"))
        );
        formElements.add(backupsListContainer);

        Widget actions = Div.of(
            Button.of(Text.of("Cancel"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('restoreDbModal').style.display='none'").cssClass("btn-action btn-secondary")),
            Button.of(Icon.of("fas fa-undo"), Text.of(" Restore Backup"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openConfirmDbRestoreModal()").cssClass("btn-action btn-primary").style("background:#a855f7;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:8px;"));
        formElements.add(actions);

        Widget form = Div.of(formElements.toArray(new Widget[0]));
        return createModalOverlay("restoreDbModal", "600px", "rgba(168,85,247,0.4)", header, form);
    }

    private Widget buildConfirmDbRestoreModal(String actionUrl) {
        Widget header = createModalHeader("Confirm Database Restoration", "fas fa-exclamation-triangle", "#f59e0b", "confirmDbRestoreModal");

        Widget form = Form.of(
            InputHidden.of("action", "restore_database"),
            InputHidden.of("target_db", "").id("confirmRestoreDbNameInput"),
            InputHidden.of("restore_file_path", "").id("confirmRestoreFilePathInput"),
            Div.of(
                Paragraph.of(Text.of("Are you sure you want to restore database "), Span.of("").id("confirmRestoreDbDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;")), Text.of(" from the backup archive?")).modifier(new Modifier().style("margin:0 0 10px 0; font-size:13px; font-weight:600; color:#f8fafc;")),
                Div.of(
                    Span.of("Archive Path: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                    Span.of("").id("confirmRestoreFileDisplay").modifier(new Modifier().style("color:#c084fc; font-family:monospace; font-size:11px; word-break:break-all;"))
                ).modifier(new Modifier().style("margin-bottom:8px;")),
                Paragraph.of("Warning: Existing records with the same keys in storage will be overwritten with the snapshot contents.").modifier(new Modifier().style("margin:0; font-size:11px; color:#fde047;"))
            ).modifier(new Modifier().style("background:rgba(245,158,11,0.12); border-left:3px solid #f59e0b; padding:12px 14px; border-radius:6px; margin-bottom:16px;")),
            createModalFormActions("confirmDbRestoreModal", "Proceed with Restoration", "fas fa-undo", "#a855f7")
        ).method("POST").action(actionUrl);

        return createConfirmationModalOverlay("confirmDbRestoreModal", "520px", "rgba(245,158,11,0.5)", header, form);
    }

    private Widget buildExportDataModal(String actionUrl, String targetDb, String currentColl, String selectedEngine) {
        Widget header = createModalHeader("Export Database Records", "fas fa-file-export", "#f59e0b", "exportDataModal");
        Set<String> dbs = discoverAllDatabases();
        Map<String, String> dbMap = new LinkedHashMap<>();
        for (String d : dbs) {
            dbMap.put(d, d);
        }
        if (!dbMap.containsKey(targetDb)) dbMap.put(targetDb, targetDb);

        Map<String, String> formats = new LinkedHashMap<>();
        formats.put("json", "JSON (.json) - Full Multimodel Object Graph");
        formats.put("csv", "CSV (.csv) - Comma-Separated Values Table");
        formats.put("excel", "Excel (.xls) - Spreadsheet Workbook Table");

        Map<String, String> enginesMap = new LinkedHashMap<>();
        enginesMap.put("ALL", "All Engines (Full Database Dump)");
        enginesMap.put("DOCUMENT", "DOCUMENT");
        enginesMap.put("KEYVALUE", "KEYVALUE");
        enginesMap.put("VECTOR", "VECTOR");
        enginesMap.put("GRAPH", "GRAPH");
        enginesMap.put("TIMESERIES", "TIMESERIES");
        enginesMap.put("COLUMN", "COLUMN");
        enginesMap.put("GEOSPATIAL", "GEOSPATIAL");
        enginesMap.put("OBJECT", "OBJECT");
        enginesMap.put("RECORDS", "RECORDS");

        Widget form = Form.of(
            InputHidden.of("action", "export_data"),
            Inputs.of(
                createLabel("Export Format:"),
                createSelectOne("format", "", "#f59e0b", "exportFormatSelect", formats, "json")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Target Database:"),
                createSelectOne("target_db", "", "#38bdf8", "exportDbSelect", dbMap, targetDb)
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Engine Filter:"),
                createSelectOne("engine_type", "", "#a855f7", "exportEngineSelect", enginesMap, selectedEngine)
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Unit / Collection Filter (optional):"),
                createTextInput("target_coll", "Leave blank for all units", currentColl.equals("default") ? "" : currentColl, "#f8fafc").id("exportCollInput")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("exportDataModal", "Download Export File", "fas fa-download", "#f59e0b; color:#0f172a")
        ).method("GET").action(JettraServer.resolvePath("/engines"));

        return createModalOverlay("exportDataModal", "560px", "rgba(245,158,11,0.4)", header, form);
    }

    private Widget buildAdvancedSearchModal(String actionUrl, String targetDb, String currentColl) {
        Widget header = createModalHeader("Búsqueda Avanzada Multi-Model Explorer", "fas fa-search-plus", "#38bdf8", "advancedSearchModal");
        Set<String> dbs = discoverAllDatabases();
        Map<String, String> dbMap = new LinkedHashMap<>();
        for (String d : dbs) {
            dbMap.put(d, d);
        }
        if (!dbMap.containsKey(targetDb)) dbMap.put(targetDb, targetDb);

        Map<String, String> enginesMap = new LinkedHashMap<>();
        enginesMap.put("ALL", "All Engines (Universal Scan)");
        enginesMap.put("DOCUMENT", "DOCUMENT (Collections)");
        enginesMap.put("KEYVALUE", "KEYVALUE (Namespaces)");
        enginesMap.put("VECTOR", "VECTOR (Embeddings)");
        enginesMap.put("GRAPH", "GRAPH (Nodes & Edges)");
        enginesMap.put("TIMESERIES", "TIMESERIES (Metrics)");
        enginesMap.put("COLUMN", "COLUMN (Column Families)");
        enginesMap.put("GEOSPATIAL", "GEOSPATIAL (Spatial Layers)");
        enginesMap.put("OBJECT", "OBJECT (Buckets)");
        enginesMap.put("RECORDS", "RECORDS (Record Tables)");

        Widget form = Form.of(
            InputHidden.of("action", "advanced_search"),
            Inputs.of(
                createLabel("Target Database:"),
                createSelectOne("target_db", "", "#38bdf8", "advSearchDbSelect", dbMap, targetDb)
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Storage Engine:"),
                createSelectOne("search_engine", "", "#a855f7", "advSearchEngineSelect", enginesMap, "ALL")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Unit / Collection (optional):"),
                createTextInput("target_coll", "e.g. users, default, sensor_temp", "", "#f8fafc").id("advSearchCollInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Record ID / Key Pattern (e.g. doc_* or user_101):"),
                createTextInput("search_key", "Key pattern or wildcard *", "", "#f8fafc").id("advSearchKeyInput")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Content Keyword Search (JSON payload value match):"),
                createTextInput("search_keyword", "e.g. VIP, active, John, 25.4", "", "#f8fafc").id("advSearchKeywordInput")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("advancedSearchModal", "Ejecutar Búsqueda", "fas fa-search-plus", "#38bdf8")
        ).method("POST").action(actionUrl);

        return createModalOverlay("advancedSearchModal", "580px", "rgba(59,130,246,0.4)", header, form);
    }

    private Widget buildCreateIndexModal(String actionUrl) {
        Widget header = createModalHeader("Create Secondary / Composite Index", "fas fa-bolt", "#eab308", "createIndexModal");

        Map<String, String> indexTypes = new LinkedHashMap<>();
        indexTypes.put("BTREE", "B-Tree (Balanced Index for equality & range queries)");
        indexTypes.put("HASH", "Hash (O(1) exact equality lookup index)");
        indexTypes.put("FULLTEXT", "Full-Text (Inverted index for token/keyword search)");
        indexTypes.put("VECTOR_HNSW", "Vector HNSW (Hierarchical Navigable Small World for ANN)");
        indexTypes.put("SPATIAL_2D", "Spatial 2D (QuadTree/Geohash spatial index)");

        Widget form = Form.of(
            InputHidden.of("action", "create_index"),
            InputHidden.of("target_db", "").id("createIndexDbInput"),
            Inputs.of(
                createLabel("Target Database:"),
                createTextInput("target_db_display", "", "", "#eab308").id("createIndexDbDisplay").attribute("disabled", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Index Name:"),
                createTextInput("index_name", "e.g. idx_email, idx_price, idx_coords", "", "#f8fafc").attribute("required", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Indexed Field / Property:"),
                createTextInput("index_field", "e.g. email, status, coordinates", "", "#f8fafc").attribute("required", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Index Algorithm / Structure:"),
                createSelectOne("index_type", "", "#fde047", "", indexTypes, "BTREE")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createIndexModal", "Build Index", "fas fa-bolt", "#eab308; color:#0f172a")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createIndexModal", "520px", "rgba(234,179,8,0.4)", header, form);
    }

    private Widget buildCreateSchemaModal(String actionUrl) {
        Widget header = createModalHeader("Register Validation Schema", "fas fa-shield-alt", "#38bdf8", "createSchemaModal");

        Widget form = Form.of(
            InputHidden.of("action", "save_schema"),
            InputHidden.of("target_db", "").id("createSchemaDbInput"),
            Inputs.of(
                createLabel("Target Database:"),
                createTextInput("target_db_display", "", "", "#38bdf8").id("createSchemaDbDisplay").attribute("disabled", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Schema Class / Name:"),
                createTextInput("schema_name", "e.g. com.enterprise.model.CustomerSchema", "", "#f8fafc").attribute("required", "true")
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("JSON Schema Definition:"),
                createTextArea("schema_json", 5, "", "{\n  \"type\": \"object\",\n  \"required\": [\"name\", \"active\"],\n  \"properties\": {\n    \"name\": {\"type\": \"string\"},\n    \"active\": {\"type\": \"boolean\"}\n  }\n}")
            ).modifier(new Modifier().style("margin-bottom:16px;")),
            createModalFormActions("createSchemaModal", "Register Schema", "fas fa-shield-alt", "#38bdf8")
        ).method("POST").action(actionUrl);

        return createModalOverlay("createSchemaModal", "560px", "rgba(56,189,248,0.4)", header, form);
    }

    private Widget buildModalsScript() {
        String js = """
  function setElementValues(map) {
    for (var id in map) {
      var el = document.getElementById(id);
      if (el) {
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
          el.value = map[id];
        } else {
          el.innerText = map[id];
        }
      }
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
      try { return atob(b64); } catch (e2) { return b64; }
    }
  }

  function openAddIndexModal(db) {
    setElementValues({ createIndexDbInput: db, createIndexDbDisplay: db });
    document.getElementById('createIndexModal').style.display = 'flex';
  }

  function openAddSchemaModal(db) {
    setElementValues({ createSchemaDbInput: db, createSchemaDbDisplay: db });
    document.getElementById('createSchemaModal').style.display = 'flex';
  }

  function openAddUnitModal(engine, label) {
    setElementValues({ modalUnitEngineSelect: engine, modalUnitNameLabel: label + ' Name:' });
    document.getElementById('createUnitModal').style.display = 'flex';
  }

  function openAddObjectModal(engine, unit) {
    var modalMap = {
      DOCUMENT: 'addDocumentModal', KEYVALUE: 'addKeyValueModal', VECTOR: 'addVectorModal',
      GRAPH: 'addGraphModal', TIMESERIES: 'addTimeSeriesModal', COLUMN: 'addColumnModal',
      GEOSPATIAL: 'addGeoModal', OBJECT: 'addObjectModal', RECORDS: 'addRecordsModal'
    };
    var modal = document.getElementById(modalMap[engine] || 'addDocumentModal');
    if (modal) {
      var unitInput = modal.querySelector('input[name="target_coll"], input[name="node_label"]');
      if (unitInput && unit) unitInput.value = unit;
      modal.style.display = 'flex';
    }
  }

  function openBackupDbModal(db) {
    var sel = document.getElementById('backupDbSelect');
    if (sel && db) {
      sel.value = db;
      onBackupDbChange(sel);
    }
    document.getElementById('backupDbModal').style.display = 'flex';
  }

  function onBackupDbChange(selectElem) {
    var db = selectElem.value;
    var now = new Date();
    var pad = function(n) { return n < 10 ? '0' + n : n; };
    var fn = db + now.getFullYear() + '-' + pad(now.getMonth()+1) + '-' + pad(now.getDate()) + '-' + pad(now.getHours()) + '-' + pad(now.getMinutes()) + '-' + pad(now.getSeconds()) + '.zip';
    var dir = '~/data/backup/' + db;
    var dirEl = document.getElementById('backupDirInput');
    var fnEl = document.getElementById('backupFilenameInput');
    if (dirEl) dirEl.value = dir;
    if (fnEl) fnEl.value = fn;
  }

  function openRestoreDbModal(db) {
    var sel = document.getElementById('restoreDbSelect');
    if (sel && db) {
      sel.value = db;
      onRestoreDbChange(sel);
    }
    document.getElementById('restoreDbModal').style.display = 'flex';
  }

  function onRestoreDbChange(selectElem) {
    var db = selectElem.value;
    var dirEl = document.getElementById('restoreDirInput');
    if (dirEl) dirEl.value = '~/data/backup/' + db;
  }

  function openConfirmDbRestoreModal() {
    var sel = document.getElementById('restoreDbSelect');
    var fileInput = document.getElementById('restoreFileInput');
    var db = sel ? sel.value : 'database';
    var path = fileInput ? fileInput.value : '';
    if (!path || path.trim() === '') {
      alert('Por favor especifique o seleccione un archivo .zip de backup.');
      return;
    }
    setElementValues({
      confirmRestoreDbNameInput: db,
      confirmRestoreFilePathInput: path,
      confirmRestoreDbDisplay: db,
      confirmRestoreFileDisplay: path
    });
    document.getElementById('confirmDbRestoreModal').style.display = 'flex';
  }

  function openExportDataModal(engine, db, coll) {
    var engSel = document.getElementById('exportEngineSelect');
    var dbSel = document.getElementById('exportDbSelect');
    var collInput = document.getElementById('exportCollInput');
    if (engSel && engine) engSel.value = engine;
    if (dbSel && db) dbSel.value = db;
    if (collInput) collInput.value = (coll && coll !== 'default') ? coll : '';
    document.getElementById('exportDataModal').style.display = 'flex';
  }

  function openAdvancedSearchModal(engine, db, coll) {
    var engSel = document.getElementById('advSearchEngineSelect');
    var dbSel = document.getElementById('advSearchDbSelect');
    var collInput = document.getElementById('advSearchCollInput');
    if (engSel && engine) engSel.value = engine;
    if (dbSel && db) dbSel.value = db;
    if (collInput) collInput.value = (coll && coll !== 'default') ? coll : '';
    document.getElementById('advancedSearchModal').style.display = 'flex';
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

  function openUniversalEditModal(engine, db, unit, id, payloadB64) {
    var payload = decodeUtf8Base64(payloadB64);
    var parsed = null;
    try { if (typeof payload === 'string' && (payload.trim().startsWith('{') || payload.trim().startsWith('['))) parsed = JSON.parse(payload); } catch (e) {}
    var pretty = parsed ? JSON.stringify(parsed, null, 2) : payload;
    var p = parsed || {};

    var cfg = {
      DOCUMENT:   { id: 'editDocumentModal',   vals: { editDocDbInput: db, editDocDbDisplay: db, editDocCollInput: unit || 'default', editDocIdInput: id, editDocIdDisplay: id, editDocClassInput: p._class || '', editDocPayloadInput: pretty } },
      KEYVALUE:   { id: 'editKeyValueModal',   vals: { editKvDbInput: db, editKvDbDisplay: db, editKvCollInput: unit || 'default', editKvIdInput: id, editKvIdDisplay: id, editKvValueInput: payload } },
      VECTOR:     { id: 'editVectorModal',     vals: { editVecDbInput: db, editVecDbDisplay: db, editVecCollInput: unit || 'default', editVecIdInput: id, editVecIdDisplay: id, editVecCoordsInput: (p.coordinates || p.embedding || p.vector || [0.12, 0.45, 0.88, 0.31]).join(', '), editVecMetaInput: pretty } },
      GRAPH:      { id: 'editGraphModal',      vals: { editGraphDbInput: db, editGraphDbDisplay: db, editGraphCollInput: p.label || unit || 'Vertex', editGraphIdInput: id, editGraphIdDisplay: id, editGraphPropsInput: pretty } },
      TIMESERIES: { id: 'editTimeSeriesModal', vals: { editTsDbInput: db, editTsDbDisplay: db, editTsCollInput: p.metric || unit || 'telemetry', editTsIdInput: id, editTsIdDisplay: id, editTsTimestampInput: p.timestamp || id, editTsValueInput: p.value !== undefined ? p.value : '25.4', editTsUnitInput: p.unit || 'celsius', editTsTagsInput: pretty } },
      COLUMN:     { id: 'editColumnModal',     vals: { editColDbInput: db, editColDbDisplay: db, editColCollInput: p._family || unit || 'analytics', editColIdInput: id, editColIdDisplay: id, editColDataInput: pretty } },
      GEOSPATIAL: { id: 'editGeoModal',        vals: { editGeoDbInput: db, editGeoDbDisplay: db, editGeoCollInput: p._layer || unit || 'stores_layer', editGeoIdInput: id, editGeoIdDisplay: id, editGeoLatInput: p.lat !== undefined ? p.lat : (p.latitude !== undefined ? p.latitude : '8.9824'), editGeoLonInput: p.lon !== undefined ? p.lon : (p.longitude !== undefined ? p.longitude : '-79.5199'), editGeoNameInput: p.name || id } },
      OBJECT:     { id: 'editObjectModal',     vals: { editObjDbInput: db, editObjDbDisplay: db, editObjCollInput: p.bucket || unit || 'media_bucket', editObjIdInput: id, editObjIdDisplay: id, editObjMimeInput: p.mimeType || 'application/json', editObjPayloadInput: p.content || payload } },
      RECORDS:    { id: 'editRecordsModal',    vals: { editRecDbInput: db, editRecDbDisplay: db, editRecCollInput: p._table || unit || 'default', editRecIdInput: id, editRecIdDisplay: id, editRecClassInput: p._class || 'com.jettra.model.PersonRecord', editRecPayloadInput: pretty } }
    }[engine];

    if (cfg) {
      setElementValues(cfg.vals);
      document.getElementById(cfg.id).style.display = 'flex';
    }
  }

  function openUniversalRestoreModal(engine, db, unit, id, versionsJsonB64) {
    var versionsJsonStr = decodeUtf8Base64(versionsJsonB64);
    setElementValues({
      restoreEngineLabel: engine,
      restoreEngineTypeInput: engine,
      restoreRecordDbInput: db,
      restoreRecordCollInput: unit || 'default',
      restoreRecordIdInput: id,
      restoreRecordIdLabel: id
    });
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
    var get = function(id) { var el = document.getElementById(id); return el ? el.value : ''; };
    setElementValues({
      confirmRestoreEngineInput: get('restoreEngineTypeInput'),
      confirmRestoreEngineDisplay: get('restoreEngineTypeInput'),
      confirmRestoreDbInput: get('restoreRecordDbInput'),
      confirmRestoreCollInput: get('restoreRecordCollInput') || 'default',
      confirmRestoreIdInput: get('restoreRecordIdInput'),
      confirmRestoreIdDisplay: get('restoreRecordIdInput'),
      confirmRestoreTsInput: ts,
      confirmRestoreTsDisplay: ts,
      confirmRestoreDateDisplay: formattedDate || ts
    });
    document.getElementById('confirmRestoreModal').style.display = 'flex';
  }

  function openUniversalDeleteModal(engine, db, unit, id) {
    setElementValues({
      confirmDeleteEngineInput: engine,
      confirmDeleteEngineDisplay: engine,
      confirmDeleteDbInput: db,
      confirmDeleteDbDisplay: db,
      confirmDeleteCollInput: unit || 'default',
      confirmDeleteCollDisplay: unit || 'default',
      confirmDeleteIdInput: id,
      confirmDeleteIdDisplay: id
    });
    document.getElementById('confirmDeleteModal').style.display = 'flex';
  }
""";
        return RawScript.of(js);
    }
}
