package com.jettra.store.engine.web;

import com.jettra.store.engine.core.DatabaseBackupManager;
import com.jettra.store.engine.core.DatabaseBackupManager.BackupFileInfo;
import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.models.*;
import com.jettra.store.engine.ref.JettraReference;
import com.jettra.store.engine.ref.JettraReferenceResolver;
import com.jettra.store.engine.samples.SampleDatasetManager;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
    private final JettraReferenceResolver refResolver;

    public StoreEnginesPage(JettraStorageEngine engine) {
        this.engine = engine;
        this.refResolver = new JettraReferenceResolver(engine);
        if (engine != null && engine.getStorageCore() != null && 
            (engine.getStorageCore().scanPrefix("doc:ExampleDBReferences:").isEmpty() ||
             engine.getStorageCore().scanPrefix("rec:ExampleDBReferences:").isEmpty() ||
             engine.getStorageCore().scanPrefix("geo:ExampleDBReferences:").isEmpty())) {
            new SampleDatasetManager(engine).loadExampleDBReferencesDataset();
        }
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
        if (params != null && "resolve_ref".equalsIgnoreCase(params.get("action"))) {
            handleResolveReference(exchange, params);
            return true;
        }
        return false;
    }

    private void handleResolveReference(HttpExchange exchange, Map<String, String> params) throws IOException {
        String uri = params != null ? params.get("uri") : null;
        if ((uri == null || uri.isBlank()) && exchange != null && exchange.getRequestURI() != null) {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                for (String part : rawQuery.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && "uri".equalsIgnoreCase(kv[0])) {
                        uri = kv[1];
                        break;
                    }
                }
            }
        }
        if (uri != null) {
            try {
                uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
                if (uri.contains("%")) {
                    uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            uri = uri.trim();
        }

        JsonObject res = new JsonObject();
        if (uri == null || uri.isBlank()) {
            res.addProperty("error", "Missing uri parameter");
            res.addProperty("exists", false);
        } else {
            try {
                JettraReferenceResolver.ResolvedEntity resolved = refResolver.resolve(uri);
                res.addProperty("exists", resolved.exists());
                res.addProperty("uri", uri);
                res.addProperty("engine", resolved.reference() != null ? resolved.reference().engine() : "DOCUMENT");
                res.addProperty("database", resolved.reference() != null ? resolved.reference().database() : "");
                res.addProperty("entityId", resolved.reference() != null ? resolved.reference().entityId() : "");
                res.addProperty("primaryStorageAddress", resolved.primaryStorageAddress());
                res.addProperty("clusterNode", resolved.clusterNode());
                res.addProperty("version", resolved.version());
                if (resolved.jsonPayload() != null) {
                    res.add("jsonPayload", resolved.jsonPayload());
                } else if (resolved.rawPayload() != null) {
                    res.addProperty("rawPayload", resolved.rawPayload());
                }
            } catch (Exception e) {
                res.addProperty("error", e.getMessage());
                res.addProperty("exists", false);
            }
        }
        byte[] b = jsonParser.toJson(res).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
            os.flush();
        }
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
    public Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";
        String alertMessage = "";
        String alertType = "badge-active";
        String queryResultDisplay = "";
        String targetDb = params != null && params.containsKey("target_db") ? params.get("target_db") : getDefaultDbForEngine(selectedEngine);

        // Handle POST Operations
        if (exchange != null && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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
                    String searchMode = params.getOrDefault("search_mode", "UNIVERSAL");
                    String searchEng = params.getOrDefault("search_engine", selectedEngine);
                    String searchDb = params.getOrDefault("target_db", targetDb);
                    String searchColl = params.getOrDefault("target_coll", "");
                    String searchKey = params.getOrDefault("search_key", params.getOrDefault("target_id", ""));
                    String searchKeyword = params.getOrDefault("search_keyword", "");
                    queryResultDisplay = executeAdvancedSearch(searchMode, searchEng, searchDb, searchColl, searchKey, searchKeyword, params);
                    targetDb = searchDb;
                    alertMessage = "Búsqueda avanzada [" + searchMode + "] ejecutada en base de datos [" + searchDb + "]!";
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

        Widget titleBlock = Div.of(titleHeading, titleDesc)
            .modifier(new Modifier().style("margin-bottom: 20px;"));

        // Status Alert / Flash message
        Widget alertWidget = Row.of(
            Div.of(
                Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; font-size:18px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:14px; color:#f8fafc; font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
            Span.of("STATUS").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;"));

        // Document Collections Management Section (if DOCUMENT engine selected)
        String currentCollection = params != null && params.containsKey("coll") ? params.get("coll") : "default";

        // Hierarchical Multi-Model Explorer (Tree or Table View Mode)
        Widget hierarchyTreeCard = createHierarchyTreeCard(selectedEngine, targetDb, currentCollection, params);

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
        long now = System.currentTimeMillis();
        String pfx = getPrefixForEngine(engineName);
        String directKey = pfx + db + ":" + id;
        String collKey = pfx + db + ":" + coll + ":" + id;
        String simpleKey = db + ":" + id;

        // Determine which primary key is the master key for this entity
        String targetKey = directKey;
        if (engine.getStorageCore().get(collKey) != null && engine.getStorageCore().get(directKey) == null) {
            targetKey = collKey;
        } else if (engine.getStorageCore().get(directKey) != null) {
            targetKey = directKey;
        } else if (engine.getStorageCore().get(simpleKey) != null) {
            targetKey = directKey;
        }

        String finalPayloadStr = payload != null ? payload : "{}";

        switch (engineName) {
            case "DOCUMENT" -> {
                String json = params.getOrDefault("doc_payload", payload);
                JsonObject doc = parseJsonOrWrap(json);
                String docClass = params.get("doc_class");
                if (docClass != null && !docClass.isBlank()) doc.addProperty("_class", docClass.trim());
                finalPayloadStr = jsonParser.toJson(doc);
            }
            case "KEYVALUE" -> {
                finalPayloadStr = params.getOrDefault("kv_value", payload);
            }
            case "VECTOR" -> {
                float[] coords = new float[]{0.12f, 0.45f, 0.88f, 0.31f};
                if (params.containsKey("vector_coords") && !params.get("vector_coords").isBlank()) {
                    coords = parseFloats(params.get("vector_coords"));
                }
                String metaStr = params.getOrDefault("vector_meta", payload);
                JsonObject vecObj = parseJsonOrWrap(metaStr);
                vecObj.addProperty("_index", coll != null ? coll : "default");
                finalPayloadStr = jsonParser.toJson(vecObj);
            }
            case "GRAPH" -> {
                String nodeProps = params.getOrDefault("node_props", payload);
                JsonObject gObj = parseJsonOrWrap(nodeProps);
                String nodeLabel = params.getOrDefault("node_label", coll != null && !coll.isBlank() ? coll : "Vertex");
                gObj.addProperty("label", nodeLabel);
                finalPayloadStr = jsonParser.toJson(gObj);
            }
            case "TIMESERIES" -> {
                String tagsStr = params.getOrDefault("ts_tags", payload);
                JsonObject tsObj = parseJsonOrWrap(tagsStr);
                if (params.containsKey("ts_value")) {
                    try { tsObj.addProperty("value", Double.parseDouble(params.get("ts_value"))); } catch (Exception ignored) {}
                }
                if (params.containsKey("ts_unit") && !params.get("ts_unit").isBlank()) {
                    tsObj.addProperty("unit", params.get("ts_unit"));
                }
                tsObj.addProperty("metric", coll != null ? coll : "telemetry");
                finalPayloadStr = jsonParser.toJson(tsObj);
            }
            case "COLUMN" -> {
                String colData = params.getOrDefault("col_data", payload);
                JsonObject colObj = parseJsonOrColumns(colData);
                colObj.addProperty("_family", coll != null ? coll : "analytics");
                finalPayloadStr = jsonParser.toJson(colObj);
            }
            case "GEOSPATIAL" -> {
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
                geoObj.addProperty("lat", lat);
                geoObj.addProperty("lon", lon);
                geoObj.addProperty("_layer", coll != null ? coll : "stores_layer");
                finalPayloadStr = jsonParser.toJson(geoObj);
            }
            case "OBJECT" -> {
                String objMime = params.getOrDefault("obj_mime", "application/json");
                String objPayload = params.getOrDefault("obj_payload", payload);
                JsonObject state = new JsonObject();
                state.addProperty("mimeType", objMime);
                state.addProperty("bucket", coll != null ? coll : "media_bucket");
                state.addProperty("sizeBytes", objPayload.getBytes(StandardCharsets.UTF_8).length);
                state.addProperty("content", objPayload);
                finalPayloadStr = jsonParser.toJson(state);
            }
            case "RECORDS" -> {
                String recClass = params.getOrDefault("rec_class", "com.jettra.model.PersonRecord");
                String recPayload = params.getOrDefault("rec_payload", payload);
                JsonObject recObj = parseJsonOrWrap(recPayload);
                recObj.addProperty("_table", coll != null ? coll : "default");
                recObj.addProperty("_recordClass", recClass);
                finalPayloadStr = jsonParser.toJson(recObj);
            }
        }

        byte[] payloadBytes = finalPayloadStr.getBytes(StandardCharsets.UTF_8);

        // Put strictly once to targetKey with new timestamp to increment version by +1
        engine.getStorageCore().put(targetKey, payloadBytes, now);

        // Mirror to collKey if different and already exists
        if (!targetKey.equals(collKey) && engine.getStorageCore().get(collKey) != null) {
            engine.getStorageCore().put(collKey, payloadBytes, now);
        }
        // Mirror to simpleKey if already exists
        if (engine.getStorageCore().get(simpleKey) != null) {
            engine.getStorageCore().put(simpleKey, payloadBytes, now);
        }
    }

    private Widget renderItemDetailSummary(String engineName, String db, String unitName, String itemId, String payload, int vCount, String payloadB64, String versionsB64) {
        String pfx = getPrefixForEngine(engineName);
        String primaryAddr = pfx + db + ":" + (unitName.equals("default") ? "" : unitName + ":") + itemId;
        JsonObject parsed = parseJsonOrWrap(payload);

        List<Widget> detailElements = new ArrayList<>();

        // 1. Meta Header Bar
        Widget metaHeader = Div.of(
            Span.of("📍 " + primaryAddr).modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:600; font-size:8.5px;")),
            Span.of("Engine: " + engineName + " | v" + vCount).modifier(new Modifier().style("color:#38bdf8; font-size:8px; font-weight:500;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid rgba(255,255,255,0.06); padding-bottom:3px; margin-bottom:4px;"));
        detailElements.add(metaHeader);

        // 2. Field Attributes Preview (Max 8 attributes displayed cleanly)
        List<Widget> propRows = new ArrayList<>();
        int propCount = 0;
        for (String key : parsed.keySet()) {
            if (propCount >= 8) {
                propRows.add(Span.of("... and " + (parsed.keySet().size() - propCount) + " more field(s)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
                break;
            }
            propCount++;
            Object val = parsed.get(key);
            String valStr = val != null ? val.toString() : "null";
            if (valStr.length() > 60) valStr = valStr.substring(0, 60) + "...";

            boolean isJref = valStr.contains("jref://");
            Widget valWidget = Span.of(valStr).modifier(new Modifier().style("color:" + (isJref ? "#38bdf8" : "#f1f5f9") + "; font-family:monospace; font-size:8px;"));

            Widget propRow = Div.of(
                Span.of(key + ": ").modifier(new Modifier().style("color:#94a3b8; font-weight:600; font-size:8px; margin-right:4px;")),
                valWidget
            ).modifier(new Modifier().style("padding:1px 0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));
            propRows.add(propRow);
        }

        if (propRows.isEmpty()) {
            propRows.add(Span.of("(No structured properties or empty payload)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
        }

        Widget propsContainer = Div.of(propRows.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:1px; background:rgba(0,0,0,0.25); padding:4px 6px; border-radius:3px;"));
        detailElements.add(propsContainer);

        // 3. Quick Action Buttons
        Widget quickActions = Div.of(
            Button.of(Icon.of("fas fa-search-plus"), Text.of(" Inspeccionar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('" + escapeJs(engineName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + payloadB64 + "', " + vCount + ")").style("background:none; border:none; color:#38bdf8; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;")),
            Button.of(Icon.of("fas fa-edit"), Text.of(" Editar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + escapeJs(engineName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + payloadB64 + "')").style("background:none; border:none; color:#fbbf24; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;")),
            Button.of(Icon.of("fas fa-history"), Text.of(" Historial (v" + vCount + ")"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + escapeJs(engineName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + versionsB64 + "')").style("background:none; border:none; color:#c084fc; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;"))
        ).modifier(new Modifier().style("display:flex; gap:8px; align-items:center; margin-top:4px; border-top:1px dashed rgba(255,255,255,0.06); padding-top:3px;"));
        detailElements.add(quickActions);

        return Div.of(detailElements.toArray(new Widget[0]));
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
        String directKey = prefix + db + ":" + id;
        String collKey = prefix + db + ":" + coll + ":" + id;
        String simpleKey = db + ":" + id;

        if (engine.getStorageCore().get(directKey) != null) {
            return engine.getStorageCore().getVersionCount(directKey);
        }
        if (engine.getStorageCore().get(collKey) != null) {
            return engine.getStorageCore().getVersionCount(collKey);
        }
        if (engine.getStorageCore().get(simpleKey) != null) {
            return engine.getStorageCore().getVersionCount(simpleKey);
        }
        return 1;
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

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private void executeTypeSpecificDelete(String engineName, String db, String id, String coll, Map<String, String> params) {
        if (id == null || id.isBlank()) return;
        String prefix = getPrefixForEngine(engineName);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            prefix + coll + ":" + id,
            prefix + id,
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
                if (k.endsWith(":" + id) || k.equals(id) || k.endsWith(":" + coll + ":" + id) || k.equals(pfx + db + ":" + id) || k.equals(pfx + db + ":" + coll + ":" + id)) {
                    engine.getStorageCore().delete(k, System.currentTimeMillis());
                }
            }
            if (coll != null && !coll.isBlank() && !coll.equals("default")) {
                Map<String, byte[]> collKeys = engine.getStorageCore().scanPrefix(pfx + coll + ":");
                for (String k : collKeys.keySet()) {
                    if (k.endsWith(":" + id) || k.equals(id)) {
                        engine.getStorageCore().delete(k, System.currentTimeMillis());
                    }
                }
            }
        }

        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    if (coll != null && !coll.isBlank() && !coll.equals("default")) {
                        de.delete(db, coll, id);
                    }
                    de.delete(db, id);
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    if (coll != null && !coll.isBlank() && !coll.equals("default")) {
                        ke.delete(db, coll + ":" + id);
                    }
                    ke.delete(db, id);
                    ke.delete(coll, id);
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
                if (ge != null) {
                    ge.deleteNode(db, id);
                    ge.deleteEdge(db, id);
                    if (coll != null && !coll.isBlank()) {
                        ge.deleteNode(coll, id);
                        ge.deleteEdge(coll, id);
                    }
                }
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

    private String executeAdvancedSearch(String searchMode, String engineName, String db, String coll, String keyPattern, String keyword, Map<String, String> params) {
        JsonObject result = new JsonObject();
        JsonArray matches = new JsonArray();
        long startTime = System.currentTimeMillis();

        if (searchMode == null || searchMode.isBlank()) searchMode = "UNIVERSAL";
        searchMode = searchMode.toUpperCase();

        switch (searchMode) {
            case "QUERY" -> {
                String field = params != null ? params.getOrDefault("query_field", "").trim() : "";
                String op = params != null ? params.getOrDefault("query_op", "EQUALS").trim().toUpperCase() : "EQUALS";
                String val = params != null ? params.getOrDefault("query_val", "").trim() : "";

                String scanPrefix = (coll != null && !coll.isBlank() && !coll.equals("default"))
                    ? (db + ":" + coll + ":")
                    : (db + ":");
                Map<String, byte[]> scanned = new LinkedHashMap<>(engine.getStorageCore().scanPrefix(scanPrefix));
                String[] pfxs = {"doc:", "rec:", "col:", "kv:", "obj:"};
                for (String pfx : pfxs) {
                    scanned.putAll(engine.getStorageCore().scanPrefix(pfx + scanPrefix));
                }

                for (Map.Entry<String, byte[]> entry : scanned.entrySet()) {
                    String k = entry.getKey();
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payloadStr = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
                    if (payloadStr.isEmpty() || "__TOMBSTONE__".equals(payloadStr)) continue;

                    boolean isMatch = false;
                    String extractedVal = "";
                    try {
                        JsonObject obj = parseJsonOrWrap(payloadStr);
                        if (!field.isEmpty()) {
                            if (obj.has(field)) {
                                Object elem = obj.get(field);
                                extractedVal = elem != null ? elem.toString().replace("\"", "") : "";
                                isMatch = evaluateQueryCondition(extractedVal, op, val);
                            }
                        } else {
                            for (String propName : obj.keySet()) {
                                Object elem = obj.get(propName);
                                String propVal = elem != null ? elem.toString().replace("\"", "") : "";
                                if (evaluateQueryCondition(propVal, op, val)) {
                                    isMatch = true;
                                    field = propName;
                                    extractedVal = propVal;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        isMatch = payloadStr.contains(val);
                    }

                    if (isMatch) {
                        JsonObject item = new JsonObject();
                        item.addProperty("key", k);
                        item.addProperty("matchedField", field);
                        item.addProperty("fieldValue", extractedVal);
                        item.addProperty("condition", field + " " + op + " " + val);
                        item.addProperty("preview", payloadStr.length() > 160 ? payloadStr.substring(0, 160) + "..." : payloadStr);
                        matches.add(item);
                    }
                }
            }
            case "VECTOR" -> {
                String rawVector = params != null ? params.getOrDefault("vector_raw", "[0.12, 0.45, 0.88, 0.31]") : "[0.12, 0.45, 0.88, 0.31]";
                String metric = params != null ? params.getOrDefault("vector_metric", "COSINE").toUpperCase() : "COSINE";
                int topK = 10;
                try { if (params != null) topK = Integer.parseInt(params.getOrDefault("vector_topk", "10")); } catch (Exception ignored) {}
                float[] queryVec = parseFloats(rawVector);

                Map<String, byte[]> vecKeys = engine.getStorageCore().scanPrefix("vec:" + db + ":");
                vecKeys.putAll(engine.getStorageCore().scanPrefix("vec:"));

                List<JsonObject> vectorResults = new ArrayList<>();
                for (Map.Entry<String, byte[]> entry : vecKeys.entrySet()) {
                    String k = entry.getKey();
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payload = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (payload.isEmpty() || "__TOMBSTONE__".equals(payload)) continue;

                    try {
                        JsonObject obj = parseJsonOrWrap(payload);
                        float[] itemVec = null;
                        if (obj.has("coordinates")) {
                            itemVec = parseFloats(obj.get("coordinates").toString());
                        } else if (obj.has("embedding")) {
                            itemVec = parseFloats(obj.get("embedding").toString());
                        } else if (obj.has("vector")) {
                            itemVec = parseFloats(obj.get("vector").toString());
                        }

                        if (itemVec != null && itemVec.length > 0) {
                            double dist = metric.equals("COSINE") ? computeCosineDistance(queryVec, itemVec) : computeEuclideanDistance(queryVec, itemVec);
                            JsonObject item = new JsonObject();
                            item.addProperty("key", k);
                            item.addProperty("metric", metric);
                            item.addProperty("distance", Math.round(dist * 10000.0) / 10000.0);
                            item.addProperty("similarityScore", Math.round((1.0 / (1.0 + dist)) * 10000.0) / 10000.0);
                            item.addProperty("preview", payload.length() > 140 ? payload.substring(0, 140) + "..." : payload);
                            vectorResults.add(item);
                        }
                    } catch (Exception ignored) {}
                }

                vectorResults.sort((a, b) -> {
                    double d1 = a.has("distance") ? Double.parseDouble(a.get("distance").toString()) : 0.0;
                    double d2 = b.has("distance") ? Double.parseDouble(b.get("distance").toString()) : 0.0;
                    return Double.compare(d1, d2);
                });
                for (int i = 0; i < Math.min(topK, vectorResults.size()); i++) {
                    matches.add(vectorResults.get(i));
                }
            }
            case "GEOSPATIAL" -> {
                double targetLat = 8.9824;
                double targetLon = -79.5199;
                double maxRadiusKm = 50.0;
                try { if (params != null) targetLat = Double.parseDouble(params.getOrDefault("geo_lat", "8.9824")); } catch (Exception ignored) {}
                try { if (params != null) targetLon = Double.parseDouble(params.getOrDefault("geo_lon", "-79.5199")); } catch (Exception ignored) {}
                try { if (params != null) maxRadiusKm = Double.parseDouble(params.getOrDefault("geo_radius", "50.0")); } catch (Exception ignored) {}

                Map<String, byte[]> geoKeys = engine.getStorageCore().scanPrefix("geo:" + db + ":");
                geoKeys.putAll(engine.getStorageCore().scanPrefix("geo:"));

                List<JsonObject> geoResults = new ArrayList<>();
                for (Map.Entry<String, byte[]> entry : geoKeys.entrySet()) {
                    String k = entry.getKey();
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payload = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (payload.isEmpty() || "__TOMBSTONE__".equals(payload)) continue;

                    try {
                        JsonObject obj = parseJsonOrWrap(payload);
                        double lat = 0.0;
                        if (obj.has("lat")) {
                            lat = Double.parseDouble(obj.get("lat").toString().replace("\"", ""));
                        } else if (obj.has("latitude")) {
                            lat = Double.parseDouble(obj.get("latitude").toString().replace("\"", ""));
                        }
                        double lon = 0.0;
                        if (obj.has("lon")) {
                            lon = Double.parseDouble(obj.get("lon").toString().replace("\"", ""));
                        } else if (obj.has("longitude")) {
                            lon = Double.parseDouble(obj.get("longitude").toString().replace("\"", ""));
                        }
                        double distKm = computeHaversineDistance(targetLat, targetLon, lat, lon);

                        if (distKm <= maxRadiusKm) {
                            JsonObject item = new JsonObject();
                            item.addProperty("key", k);
                            item.addProperty("location", lat + ", " + lon);
                            item.addProperty("distanceKm", Math.round(distKm * 1000.0) / 1000.0);
                            item.addProperty("name", obj.has("name") ? obj.get("name").toString().replace("\"", "") : k);
                            item.addProperty("preview", payload.length() > 140 ? payload.substring(0, 140) + "..." : payload);
                            geoResults.add(item);
                        }
                    } catch (Exception ignored) {}
                }

                geoResults.sort((a, b) -> {
                    double d1 = a.has("distanceKm") ? Double.parseDouble(a.get("distanceKm").toString()) : 0.0;
                    double d2 = b.has("distanceKm") ? Double.parseDouble(b.get("distanceKm").toString()) : 0.0;
                    return Double.compare(d1, d2);
                });
                for (JsonObject jo : geoResults) {
                    matches.add(jo);
                }
            }
            case "TIMESERIES" -> {
                long fromTs = 0;
                long toTs = Long.MAX_VALUE;
                try {
                    String fromStr = params != null ? params.getOrDefault("ts_from", "") : "";
                    if (!fromStr.isBlank()) fromTs = Long.parseLong(fromStr);
                } catch (Exception ignored) {}
                try {
                    String toStr = params != null ? params.getOrDefault("ts_to", "") : "";
                    if (!toStr.isBlank()) toTs = Long.parseLong(toStr);
                } catch (Exception ignored) {}

                Map<String, byte[]> tsKeys = engine.getStorageCore().scanPrefix("ts:" + db + ":");
                tsKeys.putAll(engine.getStorageCore().scanPrefix("ts:"));

                for (Map.Entry<String, byte[]> entry : tsKeys.entrySet()) {
                    String k = entry.getKey();
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payload = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (payload.isEmpty() || "__TOMBSTONE__".equals(payload)) continue;

                    try {
                        JsonObject obj = parseJsonOrWrap(payload);
                        long ts = 0;
                        if (obj.has("timestamp")) {
                            ts = Long.parseLong(obj.get("timestamp").toString().replace("\"", ""));
                        }
                        if (ts >= fromTs && ts <= toTs) {
                            JsonObject item = new JsonObject();
                            item.addProperty("key", k);
                            item.addProperty("timestamp", ts);
                            item.addProperty("value", obj.has("value") ? obj.get("value").toString().replace("\"", "") : "");
                            item.addProperty("unit", obj.has("unit") ? obj.get("unit").toString().replace("\"", "") : "");
                            item.addProperty("preview", payload);
                            matches.add(item);
                        }
                    } catch (Exception ignored) {}
                }
            }
            case "GRAPH" -> {
                String fromNode = params != null ? params.getOrDefault("graph_from_node", "").trim() : "";
                String edgeLabel = params != null ? params.getOrDefault("graph_edge_label", "").trim() : "";

                Map<String, byte[]> graphKeys = engine.getStorageCore().scanPrefix("graph:" + db + ":");
                graphKeys.putAll(engine.getStorageCore().scanPrefix("graph:"));

                for (Map.Entry<String, byte[]> entry : graphKeys.entrySet()) {
                    String k = entry.getKey();
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payload = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (payload.isEmpty() || "__TOMBSTONE__".equals(payload)) continue;

                    if (!fromNode.isEmpty() && !k.contains(fromNode)) continue;
                    if (!edgeLabel.isEmpty() && !k.contains(edgeLabel)) continue;

                    JsonObject item = new JsonObject();
                    item.addProperty("key", k);
                    item.addProperty("type", k.contains(":edge:") ? "EDGE" : "NODE");
                    item.addProperty("preview", payload.length() > 140 ? payload.substring(0, 140) + "..." : payload);
                    matches.add(item);
                }
            }
            default -> { // UNIVERSAL
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
                    if (k.contains("@") || entry.getValue() == null || entry.getValue().length == 0) continue;
                    String payloadStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (payloadStr.isEmpty() || "__TOMBSTONE__".equals(payloadStr)) continue;
                    
                    if (coll != null && !coll.isBlank() && !coll.equals("default") && !k.contains(":" + coll + ":") && !k.contains(":" + coll)) {
                        continue;
                    }
                    
                    if (!keyLower.isBlank() && !k.toLowerCase().contains(keyLower)) {
                        continue;
                    }
                    
                    if (!kwLower.isBlank() && !payloadStr.toLowerCase().contains(kwLower)) {
                        continue;
                    }
                    
                    JsonObject item = new JsonObject();
                    item.addProperty("key", k);
                    item.addProperty("length", entry.getValue().length);
                    item.addProperty("preview", payloadStr.length() > 140 ? payloadStr.substring(0, 140) + "..." : payloadStr);
                    matches.add(item);
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        result.addProperty("searchMode", searchMode);
        result.addProperty("database", db);
        result.addProperty("engine", engineName);
        result.addProperty("executionTimeMs", elapsedMs);
        result.addProperty("matchCount", matches.size());
        result.add("matches", matches);
        return jsonParser.toJson(result);
    }

    private boolean evaluateQueryCondition(String actualVal, String op, String targetVal) {
        if (actualVal == null) return false;
        actualVal = actualVal.trim();
        targetVal = targetVal.trim();

        try {
            double actualNum = Double.parseDouble(actualVal);
            double targetNum = Double.parseDouble(targetVal);
            return switch (op) {
                case "GT", ">" -> actualNum > targetNum;
                case "LT", "<" -> actualNum < targetNum;
                case "GTE", ">=" -> actualNum >= targetNum;
                case "LTE", "<=" -> actualNum <= targetNum;
                case "NOT_EQUALS", "NE", "!=" -> actualNum != targetNum;
                default -> actualNum == targetNum;
            };
        } catch (Exception e) {
            return switch (op) {
                case "CONTAINS" -> actualVal.toLowerCase().contains(targetVal.toLowerCase());
                case "NOT_EQUALS", "NE", "!=" -> !actualVal.equalsIgnoreCase(targetVal);
                case "STARTS_WITH" -> actualVal.toLowerCase().startsWith(targetVal.toLowerCase());
                case "ENDS_WITH" -> actualVal.toLowerCase().endsWith(targetVal.toLowerCase());
                default -> actualVal.equalsIgnoreCase(targetVal);
            };
        }
    }

    private double computeCosineDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0) return 1.0;
        int len = Math.min(v1.length, v2.length);
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < len; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) return 1.0;
        double sim = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, 1.0 - sim);
    }

    private double computeEuclideanDistance(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0) return 0.0;
        int len = Math.min(v1.length, v2.length);
        double sum = 0.0;
        for (int i = 0; i < len; i++) {
            double diff = v1[i] - v2[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private double computeHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
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
            if (e.getValue() == null || e.getValue().length == 0) continue;
            String valStr = new String(e.getValue(), StandardCharsets.UTF_8).trim();
            if (valStr.isEmpty() || "__TOMBSTONE__".equals(valStr)) continue;

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
                if (e.getValue() == null || e.getValue().length == 0) continue;
                String valStr = new String(e.getValue(), StandardCharsets.UTF_8).trim();
                if (valStr.isEmpty() || "__TOMBSTONE__".equals(valStr)) continue;

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

    private Widget createHierarchyTreeCard(String selectedEngine, String targetDb, String currentColl, Map<String, String> params) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=");
        Set<String> allDbs = discoverAllDatabases();
        if (!allDbs.contains(targetDb)) {
            allDbs.add(targetDb);
        }

        String viewMode = params != null ? params.getOrDefault("view_mode", "tree").toLowerCase() : "tree";
        boolean isTableView = "table".equals(viewMode);

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

        // Header toolbar with View Mode Switcher and Expand/Collapse All
        Widget treeHeader = Row.of(
            Row.of(
                Header.of(3,
                    Icon.of(isTableView ? "fas fa-table" : "fas fa-sitemap").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:13px;")),
                    Text.of("Multi-Model Storage Hierarchy Explorer")
                ).modifier(new Modifier().style("margin:0; font-size:13px; font-weight:600;")),
                Row.of(
                    Button.of(Icon.of("fas fa-sitemap"), Text.of(" Tree View"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "location.href='" + actionUrl + selectedEngine + "&target_db=" + escapeJs(targetDb) + "&coll=" + escapeJs(currentColl) + "&view_mode=tree'").cssClass(!isTableView ? "btn-action btn-primary" : "btn-action btn-secondary").style("padding:3px 8px; font-size:9.5px; margin-left:12px; margin-right:4px;")),
                    Button.of(Icon.of("fas fa-table"), Text.of(" Table View"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "location.href='" + actionUrl + selectedEngine + "&target_db=" + escapeJs(targetDb) + "&coll=" + escapeJs(currentColl) + "&view_mode=table'").cssClass(isTableView ? "btn-action btn-primary" : "btn-action btn-secondary").style("padding:3px 8px; font-size:9.5px; margin-right:4px;")),
                    Button.of(Icon.of("fas fa-expand-alt"), Text.of(" Expand All"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "expandAllTreeNodes()").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px; background:rgba(56,189,248,0.1); border-color:rgba(56,189,248,0.3); color:#38bdf8;")),
                    Button.of(Icon.of("fas fa-compress-alt"), Text.of(" Collapse All"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "collapseAllTreeNodes()").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:4px; background:rgba(148,163,184,0.1); border-color:rgba(148,163,184,0.3); color:#94a3b8;"))
                ).modifier(new Modifier().style("display:flex; align-items:center;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;")),
            Row.of(
                Button.of(Icon.of("fas fa-database"), Text.of(" + DB"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "showModal('createDbModal')").cssClass("btn-action btn-primary").style("padding:3px 6px; font-size:9px; margin-right:3px;")),
                Button.of(Icon.of("fas fa-folder-plus"), Text.of(" + Unit"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "showModal('createUnitModal')").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px;")),
                Button.of(Icon.of("fas fa-download"), Text.of(" Backup"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openBackupDbModal('" + escapeJs(targetDb) + "')").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px; background:rgba(34,197,94,0.15); border-color:rgba(34,197,94,0.3); color:#4ade80;")),
                Button.of(Icon.of("fas fa-upload"), Text.of(" Restore"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openRestoreDbModal('" + escapeJs(targetDb) + "')").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px; background:rgba(168,85,247,0.15); border-color:rgba(168,85,247,0.3); color:#c084fc;")),
                Button.of(Icon.of("fas fa-file-export"), Text.of(" Export"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openExportDataModal('" + escapeJs(selectedEngine) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(currentColl) + "')").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px; background:rgba(234,179,8,0.15); border-color:rgba(234,179,8,0.3); color:#fde047;")),
                Button.of(Icon.of("fas fa-search-plus"), Text.of(" Búsqueda Avanzada"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAdvancedSearchModal('" + escapeJs(selectedEngine) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(currentColl) + "')").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; background:rgba(56,189,248,0.15); border-color:rgba(56,189,248,0.3); color:#38bdf8;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:3px;"))
        ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:6px;"));

        if (isTableView) {
            record FlatRecordItem(String engine, String color, String icon, String db, String unit, String id, int vCount, String payload, String payloadB64, String versionsB64) {}
            List<FlatRecordItem> flatItems = new ArrayList<>();

            for (String[] spec : allEngSpecs) {
                String engName = spec[0];
                String engColor = spec[1];
                String engIcon = spec[2];
                Map<String, List<String>> unitsAndItems = discoverUnitsAndItems(engName, targetDb);
                for (Map.Entry<String, List<String>> entry : unitsAndItems.entrySet()) {
                    String uName = entry.getKey();
                    for (String itemId : entry.getValue()) {
                        int vCount = getItemVersionCount(engName, targetDb, uName, itemId);
                        String itemPayload = getItemPayload(engName, targetDb, uName, itemId);
                        String itemVersions = getVersionsJson(engName, targetDb, uName, itemId);
                        String payloadB64 = Base64.getEncoder().encodeToString(itemPayload.getBytes(StandardCharsets.UTF_8));
                        String versionsB64 = Base64.getEncoder().encodeToString(itemVersions.getBytes(StandardCharsets.UTF_8));
                        flatItems.add(new FlatRecordItem(engName, engColor, engIcon, targetDb, uName, itemId, vCount, itemPayload, payloadB64, versionsB64));
                    }
                }
            }

            int pageSize = 15;
            try {
                if (params != null && params.containsKey("table_size")) {
                    pageSize = Math.max(5, Integer.parseInt(params.get("table_size")));
                }
            } catch (Exception ignored) {}

            int currentPage = 1;
            try {
                if (params != null && params.containsKey("table_page")) {
                    currentPage = Math.max(1, Integer.parseInt(params.get("table_page")));
                }
            } catch (Exception ignored) {}

            int totalItems = flatItems.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
            if (currentPage > totalPages) currentPage = totalPages;

            int startIndex = (currentPage - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, totalItems);
            List<FlatRecordItem> pageItems = totalItems > 0 ? flatItems.subList(startIndex, endIndex) : Collections.emptyList();

            // Table Filter Bar
            Widget quickFilterInput = TextField.of("table_quick_filter", "Quick filter by Record ID, unit, engine, or payload content...")
                .id("tableExplorerQuickFilter")
                .modifier(new Modifier()
                    .attribute("onkeyup", "filterExplorerTable()")
                    .style("flex:1; min-width:220px; padding:6px 12px; background:#0f172a; border:1px solid rgba(56,189,248,0.3); border-radius:6px; color:#f8fafc; font-size:12px;"));

            Widget activeDbBadge = Span.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:4px; color:#38bdf8;")),
                Text.of("DB: " + targetDb)
            ).modifier(new Modifier().style("color:#38bdf8; font-weight:bold; font-size:12px; background:rgba(56,189,248,0.1); border:1px solid rgba(56,189,248,0.25); padding:5px 10px; border-radius:6px;"));

            Widget resolveRefCheckbox = Label.of(
                RawHtml.of("<input type=\"checkbox\" id=\"chkAutoResolveRefsGlobal\" checked onchange=\"toggleGlobalReferenceResolution(this.checked)\" style=\"accent-color:#38bdf8; width:14px; height:14px; cursor:pointer; margin-right:4px;\" />"),
                Icon.of("fas fa-link").modifier(new Modifier().style("color:#38bdf8; margin-right:4px; font-size:11px;")),
                Span.of("Cargar Objetos Referenciados (Auto-Resolve Jref)").modifier(new Modifier().style("color:#cbd5e1; font-size:11px; font-weight:600;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center; cursor:pointer; background:rgba(56,189,248,0.1); border:1px solid rgba(56,189,248,0.25); padding:4px 8px; border-radius:6px;"));

            Widget totalCountBadge = Span.of(totalItems + " Total Records").id("tableFilterVisibleCount")
                .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:11px; padding:4px 8px;"));

            Widget tableFilterBar = Div.of(
                activeDbBadge,
                resolveRefCheckbox,
                quickFilterInput,
                totalCountBadge
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-bottom:12px; background:rgba(15,23,42,0.6); padding:8px 12px; border-radius:6px; border:1px solid rgba(255,255,255,0.05);"));

            // Table Headers & Rows
            List<Widget> tableRows = new ArrayList<>();

            // Header Row
            Widget tableHeaderRow = Div.of(
                Span.of("ENGINE").modifier(new Modifier().style("width:130px; font-weight:700; color:#94a3b8; font-size:11px;")),
                Span.of("UNIT / COLLECTION").modifier(new Modifier().style("width:160px; font-weight:700; color:#94a3b8; font-size:11px;")),
                Span.of("RECORD ID").modifier(new Modifier().style("width:170px; font-weight:700; color:#94a3b8; font-size:11px;")),
                Span.of("VERSION").modifier(new Modifier().style("width:75px; font-weight:700; color:#94a3b8; font-size:11px;")),
                Span.of("PAYLOAD PREVIEW").modifier(new Modifier().style("flex:1; min-width:180px; font-weight:700; color:#94a3b8; font-size:11px;")),
                Span.of("ACTIONS").modifier(new Modifier().style("width:130px; text-align:right; font-weight:700; color:#94a3b8; font-size:11px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:rgba(30,41,59,0.8); border-bottom:2px solid rgba(255,255,255,0.1); border-radius:6px 6px 0 0; gap:8px;"));

            tableRows.add(tableHeaderRow);

            if (pageItems.isEmpty()) {
                tableRows.add(
                    Div.of(
                        Span.of("No records found in database [" + targetDb + "]. Click [+ DB], [+ Unit], or insert objects to begin.")
                            .modifier(new Modifier().style("font-style:italic; color:#94a3b8; font-size:12px;"))
                    ).modifier(new Modifier().style("padding:24px; text-align:center; background:#0f172a; border-bottom:1px solid rgba(255,255,255,0.05);"))
                );
            } else {
                for (FlatRecordItem item : pageItems) {
                    Widget engCell = Span.of(
                        Icon.of(item.icon()).modifier(new Modifier().style("color:" + item.color() + "; margin-right:4px; font-size:11px;")),
                        Span.of(item.engine()).modifier(new Modifier().style("font-weight:700; font-size:10.5px; color:" + item.color() + ";"))
                    ).modifier(new Modifier().style("width:130px; display:flex; align-items:center;"));

                    Widget unitCell = Span.of(
                        Text.of("📁 "),
                        Span.of(item.unit()).modifier(new Modifier().style("color:#cbd5e1; font-size:11px; font-weight:500;"))
                    ).modifier(new Modifier().style("width:160px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

                    Widget idCell = Span.of(item.id())
                        .modifier(new Modifier().style("width:170px; color:#f8fafc; font-family:monospace; font-weight:700; font-size:11.5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

                    Widget versionCell = Span.of("v" + item.vCount())
                        .modifier(new Modifier().cssClass("store-badge").style("width:75px; background:rgba(56,189,248,0.15); color:#38bdf8; font-size:10px; padding:2px 6px; text-align:center;"));

                    String preview = item.payload().length() > 75 ? item.payload().substring(0, 75) + "..." : item.payload();
                    Widget previewCell = Span.of(preview)
                        .modifier(new Modifier().style("flex:1; min-width:180px; color:#94a3b8; font-family:monospace; font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

                    List<Widget> actionBtns = new ArrayList<>();
                    actionBtns.add(
                        Button.of(Icon.of("fas fa-eye"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "', " + item.vCount() + ")").attribute("title", "Inspect record details").style("background:none; border:1px solid rgba(56,189,248,0.4); color:#38bdf8; font-size:10px; padding:3px 6px; border-radius:3px; cursor:pointer;"))
                    );
                    actionBtns.add(
                        Button.of(Icon.of("fas fa-edit"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "')").attribute("title", "Edit record").style("background:none; border:1px solid rgba(56,189,248,0.4); color:#38bdf8; font-size:10px; padding:3px 6px; border-radius:3px; cursor:pointer;"))
                    );
                    actionBtns.add(
                        Button.of(Icon.of("fas fa-history"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.versionsB64() + "')").attribute("title", "Version history v" + item.vCount()).style("background:none; border:1px solid rgba(168,85,247,0.4); color:#a855f7; font-size:10px; padding:3px 6px; border-radius:3px; cursor:pointer;"))
                    );
                    actionBtns.add(
                        Button.of(Icon.of("fas fa-trash-alt"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalDeleteModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "')").attribute("title", "Delete record").style("background:none; border:1px solid rgba(239,68,68,0.4); color:#ef4444; font-size:10px; padding:3px 6px; border-radius:3px; cursor:pointer;"))
                    );

                    Widget actionsCell = Div.of(actionBtns.toArray(new Widget[0]))
                        .modifier(new Modifier().style("width:130px; display:flex; justify-content:flex-end; align-items:center; gap:3px;"));

                    Widget row = Div.of(engCell, unitCell, idCell, versionCell, previewCell, actionsCell)
                        .modifier(new Modifier().cssClass("explorer-table-row").style("display:flex; align-items:center; padding:8px 12px; border-bottom:1px solid rgba(255,255,255,0.05); background:#0f172a; gap:8px;"));

                    tableRows.add(row);
                }
            }

            Widget tableContainer = Div.of(tableRows.toArray(new Widget[0]))
                .modifier(new Modifier().style("border:1px solid rgba(255,255,255,0.1); border-radius:6px; overflow-x:auto; margin-bottom:12px;"));

            // Pagination Controls
            String baseTableUrl = actionUrl + selectedEngine + "&target_db=" + targetDb + "&coll=" + currentColl + "&view_mode=table&table_size=" + pageSize;

            List<Widget> pageButtons = new ArrayList<>();
            if (currentPage > 1) {
                pageButtons.add(Link.of(baseTableUrl + "&table_page=1", "« First").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-right:3px;")));
                pageButtons.add(Link.of(baseTableUrl + "&table_page=" + (currentPage - 1), "‹ Prev").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-right:3px;")));
            }
            pageButtons.add(
                Span.of("Page " + currentPage + " / " + totalPages).modifier(new Modifier().style("color:#38bdf8; font-weight:bold; font-size:11.5px; padding:3px 8px;"))
            );
            if (currentPage < totalPages) {
                pageButtons.add(Link.of(baseTableUrl + "&table_page=" + (currentPage + 1), "Next ›").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-left:3px;")));
                pageButtons.add(Link.of(baseTableUrl + "&table_page=" + totalPages, "Last »").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-left:3px;")));
            }

            Widget paginationFooter = Div.of(
                Span.of("Showing " + (totalItems == 0 ? 0 : startIndex + 1) + " - " + endIndex + " of " + totalItems + " records (Page " + currentPage + " of " + totalPages + ")")
                    .modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:500;")),
                Div.of(pageButtons.toArray(new Widget[0])).modifier(new Modifier().style("display:flex; align-items:center; gap:2px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; padding:6px 4px;"));

            return Div.of(treeHeader, tableFilterBar, tableContainer, paginationFooter)
                .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; border: 1px solid rgba(56,189,248,0.3); background:rgba(18,24,38,0.9); padding:16px;"));
        }

        List<Widget> dbCardWidgets = new ArrayList<>();
        int dbIdx = 0;

        for (String db : allDbs) {
            dbIdx++;
            boolean isActiveDb = db.equalsIgnoreCase(targetDb);
            String dbContainerId = "db_content_" + dbIdx;

            Widget dbToggleIcon = Icon.of(isActiveDb ? "fas fa-chevron-down tree-toggle-icon" : "fas fa-chevron-right tree-toggle-icon")
                .id("icon_" + dbContainerId)
                .modifier(new Modifier().style("margin-right:5px; color:#38bdf8; font-size:10px; cursor:pointer;"));

            Widget dbLeft = Div.of(
                dbToggleIcon,
                Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:4px; color:#38bdf8; font-size:11px;")),
                Span.of(db).modifier(new Modifier().style("color:" + (isActiveDb ? "#38bdf8" : "#cbd5e1") + "; font-weight:700; font-size:11px; cursor:pointer;"))
            ).modifier(new Modifier().attribute("onclick", "toggleSubtree('" + dbContainerId + "')").style("display:inline-flex; align-items:center; cursor:pointer;"));

            List<Widget> dbRightWidgets = new ArrayList<>();
            if (isActiveDb) {
                dbRightWidgets.add(Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:8px; padding:1px 5px; margin-left:4px;")));
            } else {
                dbRightWidgets.add(Link.of(actionUrl + selectedEngine + "&target_db=" + db, "[Explore DB]").modifier(new Modifier().style("color:#38bdf8; font-size:9.5px; margin-left:4px; text-decoration:none; font-weight:600;")));
            }
            Widget dbRight = Div.of(dbRightWidgets.toArray(new Widget[0]));

            Widget dbHeaderRow = Div.of(dbLeft, dbRight)
                .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:3px 4px;"));

            List<Widget> dbContentWidgets = new ArrayList<>();
            dbContentWidgets.add(dbHeaderRow);

            if (isActiveDb) {
                List<Widget> engineSubtreeWidgets = new ArrayList<>();
                int engIdx = 0;

                for (String[] spec : allEngSpecs) {
                    engIdx++;
                    String engName = spec[0];
                    String engColor = spec[1];
                    String engIcon = spec[2];
                    String unitPlural = spec[3];
                    String unitSingle = spec[4];
                    String itemLabel = spec[5];
                    String itemIcon = spec[6];
                    boolean isEngActive = engName.equalsIgnoreCase(selectedEngine);
                    String engContainerId = "eng_subtree_" + dbIdx + "_" + engIdx;

                    Map<String, List<String>> unitsAndItems = discoverUnitsAndItems(engName, db);
                    int totalItems = unitsAndItems.values().stream().mapToInt(List::size).sum();

                    Widget engToggleIcon = Icon.of("fas fa-chevron-down tree-toggle-icon")
                        .id("icon_" + engContainerId)
                        .modifier(new Modifier().attribute("onclick", "toggleSubtree('" + engContainerId + "')").style("margin-right:4px; color:" + engColor + "; font-size:9px; cursor:pointer;"));

                    Widget engHeaderLink = Div.of(
                        engToggleIcon,
                        Icon.of(engIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:3px; font-size:9.5px;")),
                        Link.of(actionUrl + engName + "&target_db=" + db,
                            Span.of(engName).modifier(new Modifier().style("font-weight:700; font-size:9.5px; text-transform:uppercase;")),
                            Text.of(" → "),
                            Span.of(unitPlural + " (" + unitsAndItems.size() + " " + (unitsAndItems.size() == 1 ? unitSingle : unitPlural) + ", " + totalItems + " items)").modifier(new Modifier().style("color:#cbd5e1; font-size:8.5px; font-weight:normal;"))
                        ).modifier(new Modifier().style("text-decoration:none; font-size:9.5px; color:" + (isEngActive ? "#38bdf8; font-weight:700;" : "#94a3b8;") + ";"))
                    ).modifier(new Modifier().style("display:inline-flex; align-items:center; font-size:9.5px;"));

                    Widget engAddUnitBtn = Button.of("+ " + unitSingle)
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddUnitModal('" + escapeJs(engName) + "', '" + escapeJs(unitSingle) + "', '" + escapeJs(db) + "')").style("background:none; border:1px solid " + engColor + "55; color:" + engColor + "; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer;"));

                    Widget engHeaderRow = Div.of(engHeaderLink, engAddUnitBtn)
                        .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:2px 2px;"));

                    List<Widget> unitListWidgets = new ArrayList<>();
                    int unitIdx = 0;

                    for (Map.Entry<String, List<String>> unitEntry : unitsAndItems.entrySet()) {
                        unitIdx++;
                        String unitName = unitEntry.getKey();
                        List<String> items = unitEntry.getValue();
                        boolean isCurrColl = isEngActive && unitName.equalsIgnoreCase(currentColl);
                        String unitContainerId = "unit_subtree_" + dbIdx + "_" + engIdx + "_" + unitIdx;

                        Widget unitToggleIcon = Icon.of("fas fa-chevron-down tree-toggle-icon")
                            .id("icon_" + unitContainerId)
                            .modifier(new Modifier().attribute("onclick", "toggleSubtree('" + unitContainerId + "')").style("margin-right:3px; color:#cbd5e1; font-size:8.5px; cursor:pointer;"));

                        Widget unitLeft = Div.of(
                            unitToggleIcon,
                            Text.of("📁 "),
                            Link.of(actionUrl + engName + "&target_db=" + db + "&coll=" + unitName, unitName).modifier(new Modifier().style("color:inherit; text-decoration:none; font-size:9.5px; font-weight:600;")),
                            Text.of(" "),
                            Span.of("(" + items.size() + ")").modifier(new Modifier().style("font-size:8px; color:#94a3b8; font-weight:normal; margin-left:2px;"))
                        ).modifier(new Modifier().style("display:inline-flex; align-items:center; color:" + (isCurrColl ? "#38bdf8" : "#cbd5e1") + "; font-size:9.5px; font-weight:600;"));

                        Widget unitAddObjBtn = Button.of("[+ " + itemLabel + "]")
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddObjectModal('" + escapeJs(engName) + "', '" + escapeJs(unitName) + "', '" + escapeJs(db) + "')").style("background:none; border:none; color:" + engColor + "; font-size:8.5px; cursor:pointer; padding:0;"));

                        Widget unitHeaderRow = Div.of(unitLeft, unitAddObjBtn)
                            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:1.5px 0;"));

                        List<Widget> itemWidgets = new ArrayList<>();
                        int pageSize = 10;
                        int totalUnitItems = items.size();
                        int totalPages = Math.max(1, (int) Math.ceil((double) totalUnitItems / pageSize));

                        if (items.isEmpty()) {
                            Widget emptyItem = Div.of(
                                Span.of("└── "),
                                Span.of("(Empty unit - click [+ " + itemLabel + "] to insert)").modifier(new Modifier().style("font-style:italic; font-size:9px;"))
                            ).modifier(new Modifier().style("font-size:9px; color:#64748b; padding:1px 0;"));
                            itemWidgets.add(emptyItem);
                        } else {
                            for (int itemI = 0; itemI < items.size(); itemI++) {
                                String itemId = items.get(itemI);
                                int pageNum = (itemI / pageSize) + 1;
                                int vCount = getItemVersionCount(engName, db, unitName, itemId);
                                String itemPayload = getItemPayload(engName, db, unitName, itemId);
                                String itemVersions = getVersionsJson(engName, db, unitName, itemId);
                                String payloadB64 = Base64.getEncoder().encodeToString(itemPayload.getBytes(StandardCharsets.UTF_8));
                                String versionsB64 = Base64.getEncoder().encodeToString(itemVersions.getBytes(StandardCharsets.UTF_8));
                                String itemDetailId = "item_detail_" + dbIdx + "_" + engIdx + "_" + unitIdx + "_" + itemI;

                                Widget itemToggleCaret = Icon.of("fas fa-caret-right tree-toggle-icon")
                                    .id("icon_" + itemDetailId)
                                    .modifier(new Modifier().attribute("onclick", "toggleSubtree('" + itemDetailId + "')").style("margin-right:3px; color:#94a3b8; font-size:8px; cursor:pointer;"));

                                Widget itemLeft = Span.of(
                                    Text.of("└── "),
                                    itemToggleCaret,
                                    Icon.of(itemIcon).modifier(new Modifier().style("color:" + engColor + "; margin-right:3px; font-size:8.5px;")),
                                    Span.of(itemId).modifier(new Modifier().attribute("onclick", "toggleSubtree('" + itemDetailId + "')").style("color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace; cursor:pointer;")),
                                    Text.of(" "),
                                    Span.of("v" + vCount).modifier(new Modifier().cssClass("store-badge").style("background:rgba(56,189,248,0.15); color:#38bdf8; font-size:7.5px; padding:0.5px 3px; line-height:1;"))
                                );

                                List<Widget> itemBtnWidgets = new ArrayList<>();
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-eye"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('" + escapeJs(engName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + payloadB64 + "', " + vCount + ")").attribute("title", "Inspect record details").style("background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-edit"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + escapeJs(engName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + payloadB64 + "')").attribute("title", "Edit record").style("background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-history"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + escapeJs(engName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "', '" + versionsB64 + "')").attribute("title", "Version history v" + vCount).style("background:none; border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                                );
                                itemBtnWidgets.add(
                                    Button.of(Icon.of("fas fa-trash-alt"))
                                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalDeleteModal('" + escapeJs(engName) + "', '" + escapeJs(db) + "', '" + escapeJs(unitName) + "', '" + escapeJs(itemId) + "')").attribute("title", "Delete record").style("background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                                );

                                Widget itemRight = Div.of(itemBtnWidgets.toArray(new Widget[0]))
                                    .modifier(new Modifier().style("display:flex; align-items:center; gap:2px;"));

                                String itemDisplay = (pageNum == 1) ? "display:flex;" : "display:none;";
                                Widget itemRow = Div.of(itemLeft, itemRight)
                                    .modifier(new Modifier()
                                        .cssClass("item-row-" + unitContainerId)
                                        .attribute("data-page", String.valueOf(pageNum))
                                        .style(itemDisplay + " font-size:9px; color:#94a3b8; justify-content:space-between; align-items:center; padding:1.5px 0; line-height:1.2;"));

                                itemWidgets.add(itemRow);

                                // Level 5: Collapsed Item Details Subtree Panel
                                Widget itemDetailPanel = Div.of(
                                    renderItemDetailSummary(engName, db, unitName, itemId, itemPayload, vCount, payloadB64, versionsB64)
                                ).id(itemDetailId)
                                 .modifier(new Modifier()
                                    .cssClass("tree-collapsible-content item-detail-" + unitContainerId)
                                    .attribute("data-page", String.valueOf(pageNum))
                                    .style("display:none; margin-left:14px; margin-top:2px; margin-bottom:4px; padding:4px 8px; background:rgba(15,23,42,0.85); border:1px solid rgba(56,189,248,0.18); border-left:2px solid " + engColor + "; border-radius:4px; font-size:8.5px; line-height:1.35;"));

                                itemWidgets.add(itemDetailPanel);
                            }

                            // Add Unit Pagination Controls if > 1 page
                            if (totalPages > 1) {
                                Widget prevBtn = Button.of(Text.of("‹ Prev"))
                                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "changeSubtreePage('" + unitContainerId + "', -1, " + totalPages + ")").style("background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.15); color:#cbd5e1; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:3px;"));
                                Widget pageLabel = Span.of("Pág 1 / " + totalPages + " (" + totalUnitItems + " total)")
                                    .id("page_label_" + unitContainerId)
                                    .modifier(new Modifier().style("font-size:8.5px; color:#38bdf8; font-weight:600; padding:0 3px;"));
                                Widget nextBtn = Button.of(Text.of("Next ›"))
                                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "changeSubtreePage('" + unitContainerId + "', 1, " + totalPages + ")").style("background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.15); color:#cbd5e1; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-left:3px;"));

                                Widget unitPaginationBar = Div.of(prevBtn, pageLabel, nextBtn)
                                    .modifier(new Modifier().style("display:flex; align-items:center; justify-content:flex-end; padding:2px 0; margin-top:2px; border-top:1px dashed rgba(255,255,255,0.06);"));
                                itemWidgets.add(unitPaginationBar);
                            }
                        }

                        Widget itemsContainer = Div.of(itemWidgets.toArray(new Widget[0]))
                            .id(unitContainerId)
                            .modifier(new Modifier().cssClass("tree-collapsible-content").style("margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px; display:block;"));

                        Widget unitBlock = Div.of(unitHeaderRow, itemsContainer)
                            .modifier(new Modifier().style("margin-bottom:3px; margin-top:2px;"));

                        unitListWidgets.add(unitBlock);
                    }

                    Widget unitSubtreeContainer = Div.of(unitListWidgets.toArray(new Widget[0]))
                        .id(engContainerId)
                        .modifier(new Modifier().cssClass("tree-collapsible-content").style("margin-left:8px; border-left: 2px dotted rgba(255,255,255,0.12); padding-left:6px; margin-top:3px; display:block;"));

                    Widget engineBlock = Div.of(engHeaderRow, unitSubtreeContainer)
                        .modifier(new Modifier().style("margin-bottom:4px; background:" + (isEngActive ? "rgba(30,41,59,0.7)" : "rgba(15,23,42,0.3)") + "; padding:4px 6px; border-radius:4px; border:1px solid rgba(255,255,255,0.04);"));

                    engineSubtreeWidgets.add(engineBlock);
                }

                // Render Indexes & Schemas Subtree for this Database
                Map<String, JsonObject> dbIndexes = discoverIndexes(db);
                Map<String, JsonObject> dbSchemas = discoverSchemas(db);
                String idxSchemasContainerId = "idx_schemas_" + dbIdx;

                Widget idxSchemasToggleIcon = Icon.of("fas fa-chevron-down tree-toggle-icon")
                    .id("icon_" + idxSchemasContainerId)
                    .modifier(new Modifier().attribute("onclick", "toggleSubtree('" + idxSchemasContainerId + "')").style("margin-right:4px; color:#eab308; font-size:9px; cursor:pointer;"));

                Widget idxSchemasHeaderLeft = Div.of(
                    idxSchemasToggleIcon,
                    Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:3px; font-size:9.5px;")),
                    Span.of("INDEXES & SCHEMAS").modifier(new Modifier().style("font-weight:bold; font-size:9.5px; color:#eab308;")),
                    Text.of(" → "),
                    Span.of("(" + dbIndexes.size() + " Indexes, " + dbSchemas.size() + " Schemas)").modifier(new Modifier().style("color:#cbd5e1; font-size:8.5px; font-weight:normal;"))
                ).modifier(new Modifier().style("display:inline-flex; align-items:center; font-size:9.5px;"));

                Widget idxSchemasHeaderRight = Div.of(
                    Button.of(Icon.of("fas fa-plus"), Text.of(" Index"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddIndexModal('" + escapeJs(db) + "')").style("background:none; border:1px solid rgba(234,179,8,0.5); color:#eab308; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:3px;")),
                    Button.of(Icon.of("fas fa-shield-alt"), Text.of(" Schema"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddSchemaModal('" + escapeJs(db) + "')").style("background:none; border:1px solid rgba(56,189,248,0.5); color:#38bdf8; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; gap:2px;"));

                Widget idxSchemasHeaderRow = Div.of(idxSchemasHeaderLeft, idxSchemasHeaderRight)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:2px 2px;"));

                // Secondary & Composite Indexes Unit
                Widget indexesUnitLeft = Span.of(
                    Text.of("📁 Secondary & Composite Indexes "),
                    Span.of("(" + dbIndexes.size() + ")").modifier(new Modifier().style("font-size:8px; color:#94a3b8; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#fde047; font-size:9.5px; font-weight:600;"));

                Widget indexesUnitAddBtn = Button.of("[+ Index]")
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddIndexModal('" + escapeJs(db) + "')").style("background:none; border:none; color:#eab308; font-size:8.5px; cursor:pointer; padding:0;"));

                Widget indexesUnitHeaderRow = Div.of(indexesUnitLeft, indexesUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> indexItemWidgets = new ArrayList<>();
                if (dbIndexes.isEmpty()) {
                    indexItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No secondary indexes)").modifier(new Modifier().style("font-style:italic; font-size:9px;"))
                        ).modifier(new Modifier().style("font-size:9px; color:#64748b; padding:1px 0;"))
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
                            Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#eab308; margin-right:3px; font-size:8.5px;")),
                            Span.of(idxName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace;")),
                            Text.of(" "),
                            Span.of(idxType).modifier(new Modifier().cssClass("store-badge").style("background:rgba(234,179,8,0.15); color:#fde047; font-size:7.5px; padding:0.5px 3px;")),
                            Text.of(" on '"),
                            Span.of(idxField).modifier(new Modifier().style("color:#38bdf8; font-family:monospace; font-size:8.5px;")),
                            Text.of("' (" + idxColl + ")")
                        );

                        Widget idxItemRight = Div.of(
                            Button.of(Icon.of("fas fa-trash-alt"))
                                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openDeleteIndexModal('" + escapeJs(db) + "', '" + escapeJs(idxName) + "')").style("background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                        );

                        Widget idxRow = Div.of(idxItemLeft, idxItemRight)
                            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; font-size:9px; padding:1.5px 0; color:#94a3b8;"));
                        indexItemWidgets.add(idxRow);
                    }
                }

                Widget indexesSubtree = Div.of(
                    indexesUnitHeaderRow,
                    Div.of(indexItemWidgets.toArray(new Widget[0])).modifier(new Modifier().style("margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px;"))
                ).modifier(new Modifier().style("margin-bottom:3px; margin-top:2px;"));

                // Schemas Unit
                Widget schemasUnitLeft = Span.of(
                    Text.of("📁 Schema Definitions "),
                    Span.of("(" + dbSchemas.size() + ")").modifier(new Modifier().style("font-size:8px; color:#94a3b8; font-weight:normal;"))
                ).modifier(new Modifier().style("color:#38bdf8; font-size:9.5px; font-weight:600;"));

                Widget schemasUnitAddBtn = Button.of("[+ Schema]")
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openAddSchemaModal('" + escapeJs(db) + "')").style("background:none; border:none; color:#38bdf8; font-size:8.5px; cursor:pointer; padding:0;"));

                Widget schemasUnitHeaderRow = Div.of(schemasUnitLeft, schemasUnitAddBtn)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center;"));

                List<Widget> schemaItemWidgets = new ArrayList<>();
                if (dbSchemas.isEmpty()) {
                    schemaItemWidgets.add(
                        Div.of(
                            Span.of("└── "),
                            Span.of("(No registered schemas)").modifier(new Modifier().style("font-style:italic; font-size:9px;"))
                        ).modifier(new Modifier().style("font-size:9px; color:#64748b; padding:1px 0;"))
                    );
                } else {
                    for (Map.Entry<String, JsonObject> scEntry : dbSchemas.entrySet()) {
                        String scName = scEntry.getKey();
                        JsonObject scObj = scEntry.getValue();
                        String scJson = scObj.has("schema") ? scObj.get("schema").toString() : "{}";
                        String scB64 = Base64.getEncoder().encodeToString(scJson.getBytes(StandardCharsets.UTF_8));

                        Widget scItemLeft = Span.of(
                            Text.of("└── "),
                            Icon.of("fas fa-shield-alt").modifier(new Modifier().style("color:#38bdf8; margin-right:3px; font-size:8.5px;")),
                            Span.of(scName).modifier(new Modifier().style("color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace;"))
                        );

                        Widget scItemRight = Div.of(
                            Button.of(Icon.of("fas fa-eye"))
                                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('SCHEMA', '" + escapeJs(db) + "', 'schemas', '" + escapeJs(scName) + "', '" + scB64 + "', 1)").style("background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:2px;")),
                            Button.of(Icon.of("fas fa-trash-alt"))
                                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openDeleteSchemaModal('" + escapeJs(db) + "', '" + escapeJs(scName) + "')").style("background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"))
                        ).modifier(new Modifier().style("display:flex; gap:2px;"));

                        Widget scRow = Div.of(scItemLeft, scItemRight)
                            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; font-size:9px; padding:1.5px 0; color:#94a3b8;"));
                        schemaItemWidgets.add(scRow);
                    }
                }

                Widget schemasSubtree = Div.of(
                    schemasUnitHeaderRow,
                    Div.of(schemaItemWidgets.toArray(new Widget[0])).modifier(new Modifier().style("margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px;"))
                ).modifier(new Modifier().style("margin-bottom:3px; margin-top:2px;"));

                Widget idxSchemasSubtreeContainer = Div.of(indexesSubtree, schemasSubtree)
                    .id(idxSchemasContainerId)
                    .modifier(new Modifier().cssClass("tree-collapsible-content").style("margin-left:8px; border-left: 2px dotted rgba(234,179,8,0.3); padding-left:6px; margin-top:3px; display:block;"));

                Widget idxSchemasBlock = Div.of(idxSchemasHeaderRow, idxSchemasSubtreeContainer)
                    .modifier(new Modifier().style("margin-bottom:4px; background:rgba(30,41,59,0.7); padding:4px 6px; border-radius:4px; border:1px solid rgba(234,179,8,0.25);"));

                engineSubtreeWidgets.add(idxSchemasBlock);

                Widget dbSubtreeContainer = Div.of(engineSubtreeWidgets.toArray(new Widget[0]))
                    .id(dbContainerId)
                    .modifier(new Modifier().cssClass("tree-collapsible-content").style("margin-left:8px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:6px; margin-top:3px; display:block;"));

                dbContentWidgets.add(dbSubtreeContainer);
            }

            Widget dbCard = Div.of(dbContentWidgets.toArray(new Widget[0]))
                .modifier(new Modifier().style("margin-bottom:6px; padding:4px 8px; border-radius:6px; background:" + (isActiveDb ? "rgba(56,189,248,0.06)" : "transparent") + "; border:" + (isActiveDb ? "1px solid rgba(56,189,248,0.2)" : "1px solid transparent") + ";"));

            dbCardWidgets.add(dbCard);
        }

        Widget treeBody = Div.of(dbCardWidgets.toArray(new Widget[0]))
            .modifier(new Modifier().style("max-height:600px; overflow-y:auto; padding-right:4px;"));

        return Div.of(treeHeader, treeBody)
            .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; border: 1px solid rgba(56,189,248,0.3); background:rgba(18,24,38,0.9); padding:16px;"));
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
        modals.add(buildInspectRecordModal(actionUrl));
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
        modals.add(buildAdvancedSearchHelpModal());
        modals.add(buildReferenceWarningModal());
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
                .style("width:" + width + "; max-width:94%; max-height:90vh; overflow-y:auto; background:#1e293b; border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.85); padding:24px; border-radius:12px; position:relative; z-index:100000;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.8); backdrop-filter:blur(6px); z-index:99999; align-items:center; justify-content:center;"));
    }

    private Widget createConfirmationModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(header, content).modifier(new Modifier().cssClass("store-card")
                .style("width:" + width + "; max-width:94%; max-height:90vh; overflow-y:auto; background:#1e293b; border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.85); padding:24px; border-radius:12px; position:relative; z-index:100001;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.8); backdrop-filter:blur(6px); z-index:100000; align-items:center; justify-content:center;"));
    }

    private Widget createModalHeader(String title, String iconClass, String iconColor, String modalId) {
        return Div.of(
            Header.of(3,
                Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; margin-right:8px;")),
                Text.of(" " + title)
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc;")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "hideModal('" + modalId + "')")
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
                .attribute("onclick", "hideModal('" + modalId + "')")
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
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('" + modalId + "')").cssClass("btn-action btn-secondary")),
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
            InputHidden.of("target_db", targetDb).id("modalUnitDbInput"),
            Inputs.of(
                createLabel("Target Database:"),
                createTextInput("target_db_display", "", targetDb, "#38bdf8").id("modalUnitDbDisplay").attribute("disabled", "true")
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

    private Widget buildInspectRecordModal(String actionUrl) {
        Widget header = createModalHeader("Inspect Record & Reference Details", "fas fa-eye", "#38bdf8", "inspectRecordModal");

        Widget infoGrid = Div.of(
            Div.of(
                Span.of("Engine: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                Span.of("").id("inspectRecordEngineDisplay").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px;"))
            ).modifier(new Modifier().style("flex:1; min-width:110px;")),
            Div.of(
                Span.of("Database: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                Span.of("").id("inspectRecordDbDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:bold; font-size:12px;"))
            ).modifier(new Modifier().style("flex:1; min-width:110px;")),
            Div.of(
                Span.of("Unit/Coll: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                Span.of("").id("inspectRecordCollDisplay").modifier(new Modifier().style("color:#cbd5e1; font-weight:bold; font-size:12px;"))
            ).modifier(new Modifier().style("flex:1; min-width:110px;")),
            Div.of(
                Span.of("Record ID: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                Span.of("").id("inspectRecordIdDisplay").modifier(new Modifier().style("color:#f8fafc; font-family:monospace; font-weight:bold; font-size:12px;"))
            ).modifier(new Modifier().style("flex:1.5; min-width:140px;")),
            Div.of(
                Span.of("Version: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px;")),
                Span.of("").id("inspectRecordVersionDisplay").modifier(new Modifier().cssClass("store-badge badge-records").style("font-size:10px;"))
            ).modifier(new Modifier().style("flex:0.8; min-width:80px;"))
        ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:10px; background:rgba(15,23,42,0.8); border:1px solid rgba(56,189,248,0.2); padding:10px 14px; border-radius:6px; margin-bottom:12px; align-items:center;"));

        Widget resolveRefToggle = Div.of(
            Label.of(
                RawHtml.of("<input type=\"checkbox\" id=\"chkInspectResolveRefs\" checked onchange=\"toggleInspectReferenceResolution(this.checked)\" style=\"accent-color:#38bdf8; width:15px; height:15px; cursor:pointer; margin-right:6px;\" />"),
                Icon.of("fas fa-link").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:12px;")),
                Span.of("Cargar Objetos Referenciados (Auto-Resolve Jref & Dirección Primaria)").modifier(new Modifier().style("color:#38bdf8; font-weight:600; font-size:11.5px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center; cursor:pointer;")),
            Span.of("").id("inspectReferencesCountBadge").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10.5px; display:none;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:space-between; background:rgba(56,189,248,0.12); border:1px solid rgba(56,189,248,0.3); padding:7px 12px; border-radius:6px; margin-bottom:12px;"));

        Widget payloadLabel = createLabel("JSON Record Payload & Properties:");
        Widget payloadArea = createTextArea("inspect_payload", 12, "", "{}")
            .id("inspectRecordPayloadDisplay")
            .modifier(new Modifier()
                .attribute("readonly", "readonly")
                .style("width:100%; height:250px; background:#0b1120; border:1px solid rgba(56,189,248,0.3); border-radius:6px; color:#38bdf8; font-family:monospace; font-size:12px; padding:12px; box-sizing:border-box; resize:vertical; line-height:1.4;"));

        Widget refObjectsTitle = Div.of(
            Icon.of("fas fa-project-diagram").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:12px;")),
            Span.of("Objetos Referenciados Detectados (Direct Storage Address & Cluster Nodes):").modifier(new Modifier().style("color:#cbd5e1; font-size:11.5px; font-weight:700;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; margin-bottom:8px;"));

        Widget refObjectsList = Div.of()
            .id("inspectRecordReferencesList")
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:6px;"));

        Widget refObjectsContainer = Div.of(refObjectsTitle, refObjectsList)
            .id("inspectRecordReferencesContainer")
            .modifier(new Modifier().style("display:none; margin-top:12px; background:rgba(15,23,42,0.9); border:1px solid rgba(56,189,248,0.3); border-radius:8px; padding:12px; max-height:220px; overflow-y:auto;"));

        Widget actionButtons = Div.of(
            Button.of(Icon.of("fas fa-copy"), Text.of(" Copy JSON"))
                .id("btnCopyInspect")
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "copyInspectRecordPayload()").cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 14px; background:rgba(56,189,248,0.15); border-color:rgba(56,189,248,0.4); color:#38bdf8; margin-right:8px;")),
//            Button.of(Icon.of("fas fa-edit"), Text.of(" Edit Record"))
//                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "editFromInspectModal()").cssClass("btn-action btn-primary").style("font-size:12px; padding:6px 14px; margin-right:8px;")),
//            Button.of(Icon.of("fas fa-history"), Text.of(" Versions"))
//                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "historyFromInspectModal()").cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 14px; background:rgba(168,85,247,0.15); border-color:rgba(168,85,247,0.4); color:#c084fc; margin-right:8px;")),
            Button.of(Text.of("Close"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('inspectRecordModal').style.display='none'").cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 14px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:14px; flex-wrap:wrap; gap:6px;"));

        Widget content = Div.of(infoGrid, resolveRefToggle, payloadLabel, payloadArea, refObjectsContainer, actionButtons);
        return createModalOverlay("inspectRecordModal", "750px", "rgba(56,189,248,0.4)", header, content);
    }

    private Widget buildReferenceWarningModal() {
        Widget header = createModalHeader("Advertencia de Referencia", "fas fa-exclamation-triangle", "#f59e0b", "referenceWarningModal");

        Widget banner = Div.of(
            Icon.of("fas fa-exclamation-circle").modifier(new Modifier().style("color:#f59e0b; font-size:24px; margin-right:12px;")),
            Div.of(
                Header.of(4, Text.of("No se pudo cargar el objeto referenciado"))
                    .modifier(new Modifier().style("margin:0; font-size:14px; font-weight:700; color:#f8fafc;")),
                Span.of("El puntero de referencia no se pudo resolver en el almacenamiento o el nodo de cluster especificado no está accesible.")
                    .modifier(new Modifier().style("color:#94a3b8; font-size:11.5px;"))
            ).modifier(new Modifier().style("flex:1;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; background:rgba(245,158,11,0.12); border:1px solid rgba(245,158,11,0.3); padding:12px 14px; border-radius:8px; margin-bottom:14px;"));

        Widget detailsBox = Div.of(
            Div.of(
                Span.of("URI Referenciada: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px; font-weight:600; width:140px; min-width:140px;")),
                Span.of("").id("refWarnUriDisplay").modifier(new Modifier().style("color:#38bdf8; font-family:monospace; font-weight:700; font-size:12px; word-break:break-all;"))
            ).modifier(new Modifier().style("display:flex; margin-bottom:8px;")),
            Div.of(
                Span.of("Motor / Base de Datos: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px; font-weight:600; width:140px; min-width:140px;")),
                Span.of("").id("refWarnEngineDbDisplay").modifier(new Modifier().style("color:#f8fafc; font-size:11.5px; font-weight:600;"))
            ).modifier(new Modifier().style("display:flex; margin-bottom:8px;")),
            Div.of(
                Span.of("Dirección Primaria: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px; font-weight:600; width:140px; min-width:140px;")),
                Span.of("").id("refWarnAddressDisplay").modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:700; font-size:11.5px;"))
            ).modifier(new Modifier().style("display:flex; margin-bottom:8px;")),
            Div.of(
                Span.of("Nodo / Cluster: ").modifier(new Modifier().style("color:#94a3b8; font-size:11px; font-weight:600; width:140px; min-width:140px;")),
                Span.of("").id("refWarnClusterDisplay").modifier(new Modifier().style("color:#c084fc; font-weight:600; font-size:11.5px;"))
            ).modifier(new Modifier().style("display:flex;"))
        ).modifier(new Modifier().style("background:#0b1120; border:1px solid rgba(255,255,255,0.08); padding:12px 14px; border-radius:6px; margin-bottom:14px;"));

        Widget advice = Span.of("Sugerencia: Verifique que la base de datos esté creada, que el dataset esté precargado desde el menú o Explorer, o que el registro exista en el motor correspondiente.")
            .modifier(new Modifier().style("display:block; color:#64748b; font-size:11px; font-style:italic; margin-bottom:14px;"));

        Widget actionButtons = Div.of(
            Button.of(Text.of("Entendido / Cerrar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('referenceWarningModal').style.display='none'").cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 16px; background:rgba(245,158,11,0.15); border-color:rgba(245,158,11,0.4); color:#fde047;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end;"));

        Widget content = Div.of(banner, detailsBox, advice, actionButtons);
        return createModalOverlay("referenceWarningModal", "560px", "rgba(245,158,11,0.4)", header, content);
    }

    private Widget createAdvancedSearchModalHeader() {
        return Div.of(
            Header.of(3,
                Icon.of("fas fa-search-plus").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Text.of(" Búsqueda Avanzada Multi-Model Explorer")
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc; display:flex; align-items:center; gap:6px;")),
            Div.of(
                Button.of(Icon.of("fas fa-question-circle"), Text.of(" Guía y Ejemplos"))
                    .attribute("type", "button")
                    .attribute("onclick", "openAdvSearchHelpModal('all')")
                    .modifier(new Modifier().style("background:rgba(56,189,248,0.15); border:1px solid rgba(56,189,248,0.4); color:#38bdf8; font-size:12px; font-weight:600; padding:4px 10px; border-radius:6px; cursor:pointer; margin-right:8px; display:inline-flex; align-items:center; gap:6px; transition:all 0.2s;")),
                Button.of(Icon.of("fas fa-times"))
                    .attribute("type", "button")
                    .attribute("onclick", "document.getElementById('advancedSearchModal').style.display='none'")
                    .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; flex-wrap:wrap; gap:8px;"));
    }

    private Widget buildAdvancedSearchModal(String actionUrl, String targetDb, String currentColl) {
        Widget header = createAdvancedSearchModalHeader();
        Set<String> dbs = discoverAllDatabases();
        Map<String, String> dbMap = new LinkedHashMap<>();
        for (String d : dbs) {
            dbMap.put(d, d);
        }
        if (!dbMap.containsKey(targetDb)) dbMap.put(targetDb, targetDb);

        Map<String, String> searchModes = new LinkedHashMap<>();
        searchModes.put("UNIVERSAL", "Universal Multi-Model Key & Keyword Scan");
        searchModes.put("QUERY", "Jettra Query Engine (JSON Field & Condition Filter)");
        searchModes.put("VECTOR", "Vector Similarity Search (Cosine / Euclidean ANN)");
        searchModes.put("GEOSPATIAL", "Geospatial Proximity Search (GPS Radius)");
        searchModes.put("TIMESERIES", "TimeSeries Metrics Search (Timestamp & Range)");
        searchModes.put("GRAPH", "Graph Traversal Search (Node & Edge Relations)");

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

        Map<String, String> queryOps = new LinkedHashMap<>();
        queryOps.put("EQUALS", "= Equals (Exact match)");
        queryOps.put("CONTAINS", "Contains substring");
        queryOps.put("GT", "> Greater than numeric");
        queryOps.put("LT", "< Less than numeric");
        queryOps.put("GTE", ">= Greater than or equal");
        queryOps.put("LTE", "<= Less than or equal");
        queryOps.put("NOT_EQUALS", "!= Not equals");
        queryOps.put("STARTS_WITH", "Starts with prefix");

        Map<String, String> vectorMetrics = new LinkedHashMap<>();
        vectorMetrics.put("COSINE", "Cosine Similarity");
        vectorMetrics.put("EUCLIDEAN", "Euclidean (L2) Distance");

        Widget universalSection = Div.of(
            Inputs.of(
                createLabel("Storage Engine:"),
                createSelectOne("search_engine", "", "#a855f7", "advSearchEngineSelect", enginesMap, "ALL")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Unit / Collection (optional):"),
                createTextInput("target_coll", "e.g. users, default, sensor_temp", "", "#f8fafc").id("advSearchCollInput")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Record ID / Key Wildcard (e.g. doc_* or user_101):"),
                createTextInput("search_key", "Key pattern or wildcard *", "", "#f8fafc").id("advSearchKeyInput")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Content Keyword Search (JSON payload match):"),
                createTextInput("search_keyword", "e.g. VIP, active, John, 25.4", "", "#f8fafc").id("advSearchKeywordInput")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionUniversal").modifier(new Modifier().style("display:block;"));

        Widget querySection = Div.of(
            Inputs.of(
                createLabel("Field Name to Query (leave blank to search all properties):"),
                createTextInput("query_field", "e.g. role, status, amount, age, category", "", "#38bdf8").id("advSearchQueryField")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Comparison Operator:"),
                createSelectOne("query_op", "", "#38bdf8", "advSearchQueryOp", queryOps, "EQUALS")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Target Comparison Value:"),
                createTextInput("query_val", "e.g. Maintainer, COMPLETED, 100", "", "#f8fafc").id("advSearchQueryVal")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionQuery").modifier(new Modifier().style("display:none;"));

        Widget vectorSection = Div.of(
            Inputs.of(
                createLabel("Query Vector Coordinates (JSON array or comma-separated floats):"),
                createTextInput("vector_raw", "[0.12, 0.45, 0.88, 0.31]", "[0.12, 0.45, 0.88, 0.31]", "#a855f7").id("advSearchVectorRaw")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Similarity Metric:"),
                createSelectOne("vector_metric", "", "#a855f7", "advSearchVecMetric", vectorMetrics, "COSINE")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Top-K Nearest Neighbors Limit:"),
                createTextInput("vector_topk", "10", "10", "#f8fafc").id("advSearchVectorTopK")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionVector").modifier(new Modifier().style("display:none;"));

        Widget geoSection = Div.of(
            Inputs.of(
                createLabel("Center Latitude:"),
                createTextInput("geo_lat", "8.9824", "8.9824", "#14b8a6").id("advSearchGeoLat")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Center Longitude:"),
                createTextInput("geo_lon", "-79.5199", "-79.5199", "#14b8a6").id("advSearchGeoLon")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Search Radius (Kilometers):"),
                createTextInput("geo_radius", "50.0", "50.0", "#f8fafc").id("advSearchGeoRadius")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionGeo").modifier(new Modifier().style("display:none;"));

        Widget tsSection = Div.of(
            Inputs.of(
                createLabel("From Timestamp (epoch ms or 0 for beginning):"),
                createTextInput("ts_from", "0", "0", "#06b6d4").id("advSearchTsFrom")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("To Timestamp (epoch ms, leave blank for current):"),
                createTextInput("ts_to", "", "", "#06b6d4").id("advSearchTsTo")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionTimeseries").modifier(new Modifier().style("display:none;"));

        Widget graphSection = Div.of(
            Inputs.of(
                createLabel("Start Node ID (Filter by originating vertex):"),
                createTextInput("graph_from_node", "e.g. user_1, node_A", "", "#ec4899").id("advSearchGraphFromNode")
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Inputs.of(
                createLabel("Edge Relationship Label (optional):"),
                createTextInput("graph_edge_label", "e.g. FOLLOWS, PURCHASED, CONNECTS", "", "#ec4899").id("advSearchGraphEdgeLabel")
            ).modifier(new Modifier().style("margin-bottom:10px;"))
        ).id("advSectionGraph").modifier(new Modifier().style("display:none;"));

        Widget helpBanner = Div.of(
            Div.of(
                Icon.of("fas fa-lightbulb").modifier(new Modifier().style("color:#f59e0b; margin-right:6px; font-size:13px;")),
                Span.of("¿Dudas sobre cómo hacer consultas o la sintaxis de cada motor? ").modifier(new Modifier().style("color:#cbd5e1; font-size:12px;")),
                Button.of(Icon.of("fas fa-book-open"), Text.of(" Ver Guía con Ejemplos Interactivos"))
                    .attribute("type", "button")
                    .attribute("onclick", "openAdvSearchHelpModal('all')")
                    .modifier(new Modifier().style("background:rgba(56,189,248,0.18); border:1px solid rgba(56,189,248,0.45); color:#38bdf8; font-size:11.5px; font-weight:600; padding:3px 8px; border-radius:4px; cursor:pointer; margin-left:4px; transition:all 0.2s;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;"))
        ).modifier(new Modifier().style("background:rgba(15,23,42,0.6); border:1px dashed rgba(56,189,248,0.3); border-radius:6px; padding:8px 12px; margin-bottom:14px;"));

        Widget form = Form.of(
            InputHidden.of("action", "advanced_search"),
            helpBanner,
            Inputs.of(
                createLabel("Target Database:"),
                createSelectOne("target_db", "", "#38bdf8", "advSearchDbSelect", dbMap, targetDb)
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            Inputs.of(
                createLabel("Search Engine / Strategy:"),
                createSelectOne("search_mode", "", "#38bdf8", "advSearchModeSelect", searchModes, "UNIVERSAL")
                    .modifier(new Modifier().attribute("onchange", "onSearchModeChange(this.value)"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),
            universalSection,
            querySection,
            vectorSection,
            geoSection,
            tsSection,
            graphSection,
            createModalFormActions("advancedSearchModal", "Ejecutar Búsqueda", "fas fa-search-plus", "#38bdf8")
        ).method("POST").action(actionUrl);

        return createModalOverlay("advancedSearchModal", "620px", "rgba(59,130,246,0.4)", header, form);
    }

    private Widget createHelpExampleBox(String title, String desc, String codePreview, String onclickJs) {
        return Div.of(
            Div.of(
                Span.of(title).modifier(new Modifier().style("font-size:12px; font-weight:700; color:#38bdf8;")),
                Button.of(Icon.of("fas fa-play"), Text.of(" Cargar en Búsqueda"))
                    .attribute("type", "button")
                    .attribute("onclick", onclickJs)
                    .modifier(new Modifier().style("background:rgba(56,189,248,0.2); border:1px solid rgba(56,189,248,0.5); color:#38bdf8; font-size:11px; font-weight:600; padding:3px 10px; border-radius:5px; cursor:pointer; display:inline-flex; align-items:center; gap:5px; transition:all 0.2s;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; flex-wrap:wrap; gap:4px;")),
            Paragraph.of(Text.of(desc)).modifier(new Modifier().style("margin:0 0 6px 0; font-size:11.5px; color:#94a3b8; line-height:1.4;")),
            Div.of(
                Span.of(codePreview).modifier(new Modifier().style("font-family:monospace; font-size:11.5px; color:#e2e8f0; white-space:pre-wrap; word-break:break-all; display:block;"))
            ).modifier(new Modifier().style("background:#090d16; border:1px solid rgba(255,255,255,0.08); border-radius:5px; padding:8px 10px;"))
        ).modifier(new Modifier().style("background:rgba(15,23,42,0.7); border:1px solid rgba(255,255,255,0.08); border-radius:8px; padding:12px; margin-bottom:10px;"));
    }

    private Widget createHelpEngineCard(String id, String title, String badgeText, String badgeColor, String iconClass, String iconColor, String description, Widget detailsWidget, Widget... examples) {
        List<Widget> children = new ArrayList<>();
        children.add(
            Div.of(
                Div.of(
                    Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; font-size:16px; margin-right:8px;")),
                    Span.of(title).modifier(new Modifier().style("font-size:14px; font-weight:700; color:#f8fafc;"))
                ).modifier(new Modifier().style("display:flex; align-items:center;")),
                Span.of(badgeText).modifier(new Modifier().style("font-size:10px; font-weight:700; padding:2px 8px; border-radius:4px; background:" + badgeColor + "22; color:" + badgeColor + "; border:1px solid " + badgeColor + "55;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;"))
        );
        children.add(
            Paragraph.of(Text.of(description)).modifier(new Modifier().style("margin:0 0 10px 0; font-size:12px; color:#cbd5e1; line-height:1.5;"))
        );
        if (detailsWidget != null) {
            children.add(detailsWidget);
        }
        for (Widget ex : examples) {
            children.add(ex);
        }
        return Div.of(children.toArray(new Widget[0]))
            .id(id)
            .modifier(new Modifier().cssClass("help-engine-card").style("background:rgba(30,41,59,0.7); border:1px solid rgba(255,255,255,0.1); border-radius:10px; padding:16px; margin-bottom:16px; display:block;"));
    }

    private Widget buildAdvancedSearchHelpModal() {
        Widget header = Div.of(
            Div.of(
                Header.of(3,
                    Icon.of("fas fa-graduation-cap").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of(" Guía de Motores de Búsqueda y Ejemplos de Consultas")
                ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:#f8fafc; display:flex; align-items:center;")),
                Paragraph.of(
                    Text.of("Aprende la sintaxis, operadores soportados y carga ejemplos listos para ejecutar en cada motor de búsqueda de Jettra.")
                ).modifier(new Modifier().style("margin:4px 0 0 0; font-size:12px; color:#94a3b8;"))
            ),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('advSearchHelpModal').style.display='none'")
                .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:16px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:12px;"));

        Widget tabsBar = Div.of(
            Button.of(Text.of("Todos los Motores")).id("tab-btn-all").attribute("type", "button").attribute("onclick", "filterHelpTab('all')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(56,189,248,0.2); color:#38bdf8; border:1px solid rgba(56,189,248,0.5); cursor:pointer;")),
            Button.of(Text.of("Jettra Query")).id("tab-btn-query").attribute("type", "button").attribute("onclick", "filterHelpTab('query')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;")),
            Button.of(Text.of("Universal Scan")).id("tab-btn-universal").attribute("type", "button").attribute("onclick", "filterHelpTab('universal')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;")),
            Button.of(Text.of("Vector (ANN)")).id("tab-btn-vector").attribute("type", "button").attribute("onclick", "filterHelpTab('vector')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;")),
            Button.of(Text.of("Geospatial (GPS)")).id("tab-btn-geo").attribute("type", "button").attribute("onclick", "filterHelpTab('geo')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;")),
            Button.of(Text.of("TimeSeries (IoT)")).id("tab-btn-ts").attribute("type", "button").attribute("onclick", "filterHelpTab('ts')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;")),
            Button.of(Text.of("Graph (Grafos)")).id("tab-btn-graph").attribute("type", "button").attribute("onclick", "filterHelpTab('graph')")
                .modifier(new Modifier().cssClass("help-engine-tab").style("padding:6px 12px; font-size:11.5px; font-weight:600; border-radius:6px; background:rgba(255,255,255,0.06); color:#cbd5e1; border:1px solid rgba(255,255,255,0.1); cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:6px; margin-bottom:18px;"));

        // 1. QUERY ENGINE CARD
        Widget queryOpsPills = Div.of(
            Span.of("Operadores Disponibles:").modifier(new Modifier().style("font-size:11px; font-weight:700; color:#94a3b8; display:block; margin-bottom:6px;")),
            Div.of(
                Span.of("EQUALS (= Coincidencia Exacta)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("CONTAINS (Subcadena)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("GT ( > Mayor numérico)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("LT ( < Menor numérico)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("GTE ( >= Mayor o Igual)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("LTE ( <= Menor o Igual)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("NOT_EQUALS (!= Distinto)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);")),
                Span.of("STARTS_WITH (Prefijo)").modifier(new Modifier().style("font-size:10.5px; background:rgba(56,189,248,0.15); color:#38bdf8; padding:3px 8px; border-radius:4px; border:1px solid rgba(56,189,248,0.3);"))
            ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;"))
        );

        Widget queryCard = createHelpEngineCard(
            "help-card-query",
            "Jettra Query Engine",
            "JSON Filter",
            "#38bdf8",
            "fas fa-filter",
            "#38bdf8",
            "Motor de consulta estructurada sobre campos y propiedades JSON en colecciones de Documentos, Columnas y Registros inmutables. Permite evaluar condiciones sobre propiedades individuales o escanear todas las propiedades dejando el campo en blanco.",
            queryOpsPills,
            createHelpExampleBox(
                "Ejemplo 1: Filtrar por Campo Exacto (EQUALS)",
                "Busca documentos donde el campo 'role' tenga el valor exacto 'Maintainer'.",
                "Field: role\nOperator: EQUALS (=)\nValue: Maintainer",
                "applySearchExample('QUERY', {advSearchQueryField:'role', advSearchQueryOp:'EQUALS', advSearchQueryVal:'Maintainer'})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Comparación Numérica Mayor o Igual (GTE)",
                "Filtra registros o documentos donde el campo numérico 'amount' o 'price' sea >= 100.",
                "Field: amount\nOperator: GTE (>=)\nValue: 100",
                "applySearchExample('QUERY', {advSearchQueryField:'amount', advSearchQueryOp:'GTE', advSearchQueryVal:'100'})"
            ),
            createHelpExampleBox(
                "Ejemplo 3: Búsqueda de Subcadena (CONTAINS)",
                "Busca usuarios cuyo campo 'email' contenga '@jettra.io'.",
                "Field: email\nOperator: CONTAINS\nValue: @jettra.io",
                "applySearchExample('QUERY', {advSearchQueryField:'email', advSearchQueryOp:'CONTAINS', advSearchQueryVal:'@jettra.io'})"
            ),
            createHelpExampleBox(
                "Ejemplo 4: Búsqueda Universal en Todas las Propiedades",
                "Dejar el campo en blanco para que evalúe si CUALQUIER propiedad del objeto contiene 'VIP'.",
                "Field: (vacío)\nOperator: CONTAINS\nValue: VIP",
                "applySearchExample('QUERY', {advSearchQueryField:'', advSearchQueryOp:'CONTAINS', advSearchQueryVal:'VIP'})"
            )
        );

        // 2. UNIVERSAL SCAN CARD
        Widget universalCard = createHelpEngineCard(
            "help-card-universal",
            "Universal Multi-Model Key & Keyword Scan",
            "Multi-Model Scan",
            "#a855f7",
            "fas fa-globe",
            "#a855f7",
            "Escanea exhaustivamente todas las estructuras multi-modelo (DOCUMENT, KEYVALUE, VECTOR, GRAPH, TIMESERIES, COLUMN, GEOSPATIAL, OBJECT, RECORDS) a través de prefijos de claves, comodines (*) y coincidencia de texto completo sobre el payload serializado.",
            null,
            createHelpExampleBox(
                "Ejemplo 1: Búsqueda por Patrón de Clave con Comodín (*)",
                "Busca todas las claves que comiencen con el prefijo 'doc_' en todos los motores de la base de datos seleccionada.",
                "Engine: ALL\nCollection: (opcional)\nRecord ID / Key: doc_*\nKeyword: (vacío)",
                "applySearchExample('UNIVERSAL', {advSearchEngineSelect:'ALL', advSearchCollInput:'', advSearchKeyInput:'doc_*', advSearchKeywordInput:''})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Búsqueda de Palabra Clave en Colección Específica",
                "Busca la palabra 'active' en el contenido JSON de la colección 'users' dentro del motor DOCUMENT.",
                "Engine: DOCUMENT\nCollection: users\nRecord ID / Key: (vacío)\nKeyword: active",
                "applySearchExample('UNIVERSAL', {advSearchEngineSelect:'DOCUMENT', advSearchCollInput:'users', advSearchKeyInput:'', advSearchKeywordInput:'active'})"
            ),
            createHelpExampleBox(
                "Ejemplo 3: Búsqueda de Clave Específica en Cualquier Motor",
                "Localiza el registro exacto con identificador 'user_101' sin importar en qué motor esté almacenado.",
                "Engine: ALL\nCollection: (vacío)\nRecord ID / Key: user_101\nKeyword: (vacío)",
                "applySearchExample('UNIVERSAL', {advSearchEngineSelect:'ALL', advSearchCollInput:'', advSearchKeyInput:'user_101', advSearchKeywordInput:''})"
            )
        );

        // 3. VECTOR SIMILARITY CARD
        Widget vectorMetricsPills = Div.of(
            Span.of("Métricas de Distancia Disponibles:").modifier(new Modifier().style("font-size:11px; font-weight:700; color:#94a3b8; display:block; margin-bottom:6px;")),
            Div.of(
                Span.of("COSINE: Similitud Coseno (Óptimo para embeddings de texto, LLMs y NLP)").modifier(new Modifier().style("font-size:10.5px; background:rgba(168,85,247,0.15); color:#c084fc; padding:3px 8px; border-radius:4px; border:1px solid rgba(168,85,247,0.3);")),
                Span.of("EUCLIDEAN: Distancia Euclidiana L2 (Distancia geométrica espacial directa)").modifier(new Modifier().style("font-size:10.5px; background:rgba(168,85,247,0.15); color:#c084fc; padding:3px 8px; border-radius:4px; border:1px solid rgba(168,85,247,0.3);"))
            ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;"))
        );

        Widget vectorCard = createHelpEngineCard(
            "help-card-vector",
            "Vector Similarity Search (ANN)",
            "AI Embeddings",
            "#c084fc",
            "fas fa-project-diagram",
            "#a855f7",
            "Búsqueda vectorial de vecinos más cercanos (ANN - Approximate Nearest Neighbors) comparando un vector numérico de consulta contra las coordenadas de embeddings almacenadas en la base de datos.",
            vectorMetricsPills,
            createHelpExampleBox(
                "Ejemplo 1: Similitud Coseno con Top-10 Vecinos",
                "Compara un vector de consulta 4D contra los embeddings almacenados retornando los 10 más cercanos ordenados por similitud.",
                "Query Vector: [0.12, 0.45, 0.88, 0.31]\nMetric: COSINE\nTop-K: 10",
                "applySearchExample('VECTOR', {advSearchVectorRaw:'[0.12, 0.45, 0.88, 0.31]', advSearchVecMetric:'COSINE', advSearchVectorTopK:'10'})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Distancia Euclidiana con Top-5 Vecinos",
                "Calcula la distancia euclidiana espacial L2 para recuperar los 5 vectores más cercanos.",
                "Query Vector: [0.85, 0.15, 0.33, 0.67]\nMetric: EUCLIDEAN\nTop-K: 5",
                "applySearchExample('VECTOR', {advSearchVectorRaw:'[0.85, 0.15, 0.33, 0.67]', advSearchVecMetric:'EUCLIDEAN', advSearchVectorTopK:'5'})"
            )
        );

        // 4. GEOSPATIAL CARD
        Widget geoCard = createHelpEngineCard(
            "help-card-geo",
            "Geospatial Proximity Search (GPS)",
            "GIS Layers",
            "#14b8a6",
            "fas fa-globe-americas",
            "#14b8a6",
            "Filtrado geoespacial por radio de proximidad sobre puntos de interés, sucursales y capas GIS utilizando la fórmula esférica de Haversine para calcular distancias exactas en kilómetros.",
            null,
            createHelpExampleBox(
                "Ejemplo 1: Radio de Cobertura en Panamá Centro (50 Km)",
                "Busca todas las ubicaciones y puntos GIS dentro de un radio de 50 km de Ciudad de Panamá.",
                "Center Latitude: 8.9824\nCenter Longitude: -79.5199\nSearch Radius (Km): 50.0",
                "applySearchExample('GEOSPATIAL', {advSearchGeoLat:'8.9824', advSearchGeoLon:'-79.5199', advSearchGeoRadius:'50.0'})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Radio de Cobertura en Madrid (25 Km)",
                "Encuentra puntos de interés dentro de 25 km en el área metropolitana de Madrid.",
                "Center Latitude: 40.4168\nCenter Longitude: -3.7038\nSearch Radius (Km): 25.0",
                "applySearchExample('GEOSPATIAL', {advSearchGeoLat:'40.4168', advSearchGeoLon:'-3.7038', advSearchGeoRadius:'25.0'})"
            )
        );

        // 5. TIMESERIES CARD
        Widget tsCard = createHelpEngineCard(
            "help-card-ts",
            "TimeSeries Metrics Search (IoT)",
            "Telemetry & Logs",
            "#06b6d4",
            "fas fa-chart-line",
            "#06b6d4",
            "Filtrado y consulta temporal de métricas, telemetría IoT y lecturas continuas de sensores indexadas por marcas de tiempo Epoch en milisegundos (ms).",
            null,
            createHelpExampleBox(
                "Ejemplo 1: Histórico Completo de Telemetría",
                "Consulta todas las lecturas registradas desde el inicio histórico (timestamp 0) hasta la actualidad.",
                "From Timestamp: 0\nTo Timestamp: (dejar vacío para hasta la actualidad)",
                "applySearchExample('TIMESERIES', {advSearchTsFrom:'0', advSearchTsTo:''})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Ventana Temporal Específica",
                "Filtra datos de sensores capturados en un intervalo de tiempo concreto.",
                "From Timestamp: 1700000000000\nTo Timestamp: 1750000000000",
                "applySearchExample('TIMESERIES', {advSearchTsFrom:'1700000000000', advSearchTsTo:'1750000000000'})"
            )
        );

        // 6. GRAPH CARD
        Widget graphCard = createHelpEngineCard(
            "help-card-graph",
            "Graph Traversal Search (Grafos)",
            "Nodes & Edges",
            "#ec4899",
            "fas fa-share-alt",
            "#ec4899",
            "Exploración de vértices (nodos) y relaciones dirigidas (edges) en grafos de conocimiento, redes sociales y topologías de dependencias.",
            null,
            createHelpExampleBox(
                "Ejemplo 1: Relaciones desde Nodo de Origen",
                "Filtra todas las relaciones y aristas que parten del vértice 'user_1'.",
                "Start Node ID: user_1\nEdge Relationship Label: (vacío)",
                "applySearchExample('GRAPH', {advSearchGraphFromNode:'user_1', advSearchGraphEdgeLabel:''})"
            ),
            createHelpExampleBox(
                "Ejemplo 2: Filtrar por Tipo de Relación (FOLLOWS)",
                "Busca todas las conexiones del grafo clasificadas bajo la etiqueta 'FOLLOWS'.",
                "Start Node ID: (vacío)\nEdge Relationship Label: FOLLOWS",
                "applySearchExample('GRAPH', {advSearchGraphFromNode:'', advSearchGraphEdgeLabel:'FOLLOWS'})"
            ),
            createHelpExampleBox(
                "Ejemplo 3: Relación Específica desde un Nodo",
                "Busca si el nodo 'user_1' tiene una conexión de tipo 'PURCHASED'.",
                "Start Node ID: user_1\nEdge Relationship Label: PURCHASED",
                "applySearchExample('GRAPH', {advSearchGraphFromNode:'user_1', advSearchGraphEdgeLabel:'PURCHASED'})"
            )
        );

        Widget scrollableContent = Div.of(
            tabsBar,
            queryCard,
            universalCard,
            vectorCard,
            geoCard,
            tsCard,
            graphCard
        ).modifier(new Modifier().style("max-height:68vh; overflow-y:auto; padding-right:6px;"));

        Widget footer = Div.of(
            Span.of("Haz clic en 'Cargar en Búsqueda' en cualquiera de los ejemplos para aplicar automáticamente los parámetros al formulario de búsqueda avanzada.")
                .modifier(new Modifier().style("font-size:11.5px; color:#94a3b8; font-style:italic;")),
            Button.of(Text.of("Cerrar Guía"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('advSearchHelpModal').style.display='none'")
                .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 16px; font-size:12px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-top:16px; border-top:1px solid rgba(255,255,255,0.08); padding-top:12px; flex-wrap:wrap; gap:8px;"));

        Widget modalContent = Div.of(header, scrollableContent, footer);

        return Div.of(
            Div.of(modalContent).modifier(new Modifier().cssClass("store-card")
                .style("width:820px; max-width:94%; background:#1e293b; border:1px solid rgba(56,189,248,0.4); box-shadow:0 20px 50px rgba(0,0,0,0.75); padding:22px; border-radius:12px;"))
        ).id("advSearchHelpModal").modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.8); backdrop-filter:blur(6px); z-index:10050; align-items:center; justify-content:center;"));
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
  function showModal(id) {
    if (!id) return;
    var el = document.getElementById(id);
    if (el) {
      if (el.parentElement !== document.body) {
        document.body.appendChild(el);
      }
      el.style.display = 'flex';
      el.style.visibility = 'visible';
      el.style.opacity = '1';
    }
  }

  function hideModal(id) {
    if (!id) return;
    var el = document.getElementById(id);
    if (el) {
      el.style.display = 'none';
    }
  }

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
    showModal('createIndexModal');
  }

  function openAddSchemaModal(db) {
    setElementValues({ createSchemaDbInput: db, createSchemaDbDisplay: db });
    showModal('createSchemaModal');
  }

  function openAddUnitModal(engine, label, db) {
    var vals = { modalUnitEngineSelect: engine, modalUnitNameLabel: label + ' Name:' };
    if (db) {
      vals.modalUnitDbInput = db;
      vals.modalUnitDbDisplay = db;
    }
    setElementValues(vals);
    showModal('createUnitModal');
  }

  function openAddObjectModal(engine, unit, db) {
    var modalMap = {
      DOCUMENT: 'addDocumentModal', KEYVALUE: 'addKeyValueModal', VECTOR: 'addVectorModal',
      GRAPH: 'addGraphModal', TIMESERIES: 'addTimeSeriesModal', COLUMN: 'addColumnModal',
      GEOSPATIAL: 'addGeoModal', OBJECT: 'addObjectModal', RECORDS: 'addRecordsModal'
    };
    var modalId = modalMap[(engine || 'DOCUMENT').toUpperCase()] || 'addDocumentModal';
    var modal = document.getElementById(modalId);
    if (modal) {
      var unitInput = modal.querySelector('input[name="target_coll"], input[name="node_label"]');
      if (unitInput && unit) unitInput.value = unit;
      var dbInput = modal.querySelector('input[name="target_db"]');
      if (dbInput && db) dbInput.value = db;
    }
    showModal(modalId);
  }

  function openBackupDbModal(db) {
    var sel = document.getElementById('backupDbSelect');
    if (sel && db) {
      sel.value = db;
      onBackupDbChange(sel);
    }
    showModal('backupDbModal');
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
    showModal('restoreDbModal');
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
    showModal('confirmDbRestoreModal');
  }

  function openExportDataModal(engine, db, coll) {
    var engSel = document.getElementById('exportEngineSelect');
    var dbSel = document.getElementById('exportDbSelect');
    var collInput = document.getElementById('exportCollInput');
    if (engSel && engine) engSel.value = engine;
    if (dbSel && db) dbSel.value = db;
    if (collInput) collInput.value = (coll && coll !== 'default') ? coll : '';
    showModal('exportDataModal');
  }

  function openAdvancedSearchModal(engine, db, coll) {
    var engSel = document.getElementById('advSearchEngineSelect');
    var dbSel = document.getElementById('advSearchDbSelect');
    var collInput = document.getElementById('advSearchCollInput');
    if (engSel && engine) engSel.value = engine;
    if (dbSel && db) dbSel.value = db;
    if (collInput) collInput.value = (coll && coll !== 'default') ? coll : '';
    showModal('advancedSearchModal');
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

    var vecCoords = '0.12, 0.45, 0.88, 0.31';
    if (Array.isArray(p.coordinates)) vecCoords = p.coordinates.join(', ');
    else if (Array.isArray(p.embedding)) vecCoords = p.embedding.join(', ');
    else if (Array.isArray(p.vector)) vecCoords = p.vector.join(', ');
    else if (p.coordinates || p.embedding || p.vector) vecCoords = String(p.coordinates || p.embedding || p.vector);

    var cfg = {
      DOCUMENT:   { id: 'editDocumentModal',   vals: { editDocDbInput: db, editDocDbDisplay: db, editDocCollInput: unit || 'default', editDocIdInput: id, editDocIdDisplay: id, editDocClassInput: p._class || '', editDocPayloadInput: pretty } },
      KEYVALUE:   { id: 'editKeyValueModal',   vals: { editKvDbInput: db, editKvDbDisplay: db, editKvCollInput: unit || 'default', editKvIdInput: id, editKvIdDisplay: id, editKvValueInput: payload } },
      VECTOR:     { id: 'editVectorModal',     vals: { editVecDbInput: db, editVecDbDisplay: db, editVecCollInput: unit || 'default', editVecIdInput: id, editVecIdDisplay: id, editVecCoordsInput: vecCoords, editVecMetaInput: pretty } },
      GRAPH:      { id: 'editGraphModal',      vals: { editGraphDbInput: db, editGraphDbDisplay: db, editGraphCollInput: p.label || unit || 'Vertex', editGraphIdInput: id, editGraphIdDisplay: id, editGraphPropsInput: pretty } },
      TIMESERIES: { id: 'editTimeSeriesModal', vals: { editTsDbInput: db, editTsDbDisplay: db, editTsCollInput: p.metric || unit || 'telemetry', editTsIdInput: id, editTsIdDisplay: id, editTsTimestampInput: p.timestamp || id, editTsValueInput: p.value !== undefined ? p.value : '25.4', editTsUnitInput: p.unit || 'celsius', editTsTagsInput: pretty } },
      COLUMN:     { id: 'editColumnModal',     vals: { editColDbInput: db, editColDbDisplay: db, editColCollInput: p._family || unit || 'analytics', editColIdInput: id, editColIdDisplay: id, editColDataInput: pretty } },
      GEOSPATIAL: { id: 'editGeoModal',        vals: { editGeoDbInput: db, editGeoDbDisplay: db, editGeoCollInput: p._layer || unit || 'stores_layer', editGeoIdInput: id, editGeoIdDisplay: id, editGeoLatInput: p.lat !== undefined ? p.lat : (p.latitude !== undefined ? p.latitude : '8.9824'), editGeoLonInput: p.lon !== undefined ? p.lon : (p.longitude !== undefined ? p.longitude : '-79.5199'), editGeoNameInput: p.name || id } },
      OBJECT:     { id: 'editObjectModal',     vals: { editObjDbInput: db, editObjDbDisplay: db, editObjCollInput: p.bucket || unit || 'media_bucket', editObjIdInput: id, editObjIdDisplay: id, editObjMimeInput: p.mimeType || 'application/json', editObjPayloadInput: p.content || payload } },
      RECORDS:    { id: 'editRecordsModal',    vals: { editRecDbInput: db, editRecDbDisplay: db, editRecCollInput: p._table || unit || 'default', editRecIdInput: id, editRecIdDisplay: id, editRecClassInput: p._class || 'com.jettra.model.PersonRecord', editRecPayloadInput: pretty } }
    }[(engine || 'DOCUMENT').toUpperCase()];

    if (cfg) {
      setElementValues(cfg.vals);
      showModal(cfg.id);
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
    if (container) {
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
            var safeDate = (v.formattedDate || v.timestamp || '').toString().replace(/['"\\\\]/g, ' ');
            html += '<tr style="border-bottom:1px solid rgba(255,255,255,0.05);">';
            html += '<td style="padding:8px 12px; font-weight:bold;">' + badge + '</td>';
            html += '<td style="padding:8px 12px; color:#cbd5e1;">' + safeDate + '</td>';
            html += '<td style="padding:8px 12px; color:#94a3b8; font-family:monospace;">' + (v.preview || '{}') + '</td>';
            html += '<td style="padding:8px 12px; text-align:right;">';
            if (!v.isCurrent) {
              html += '<button type="button" class="btn-action btn-primary btn-restore-version" data-ts="' + v.timestamp + '" data-date="' + safeDate + '" style="background:#a855f7; padding:3px 10px; font-size:11px;"><i class="fas fa-undo"></i> Restore</button>';
            } else {
              html += '<span style="color:#10b981; font-size:11px;">Active</span>';
            }
            html += '</td></tr>';
          }
          html += '</table>';
          container.innerHTML = html;
          container.onclick = function(e) {
            var btn = e.target.closest('.btn-restore-version');
            if (btn) {
              openConfirmRestoreModal(btn.getAttribute('data-ts'), btn.getAttribute('data-date'));
            }
          };
        }
      } catch(e) {
        container.innerHTML = '<div style="padding:16px; color:#ef4444;">Error parsing version list: ' + e.message + '</div>';
      }
    }
    showModal('universalRestoreModal');
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
    showModal('confirmRestoreModal');
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
    showModal('confirmDeleteModal');
  }

  function toggleSubtree(elementId) {
    var el = document.getElementById(elementId);
    var icon = document.getElementById('icon_' + elementId);
    if (!el) return;
    var isHidden = (el.style.display === 'none' || el.style.display === '');
    if (isHidden) {
      el.style.display = 'block';
      if (icon) {
        if (icon.className.indexOf('fa-caret-') >= 0) {
          icon.className = 'fas fa-caret-down tree-toggle-icon';
        } else {
          icon.className = 'fas fa-chevron-down tree-toggle-icon';
        }
      }
    } else {
      el.style.display = 'none';
      if (icon) {
        if (icon.className.indexOf('fa-caret-') >= 0) {
          icon.className = 'fas fa-caret-right tree-toggle-icon';
        } else {
          icon.className = 'fas fa-chevron-right tree-toggle-icon';
        }
      }
    }
  }

  function expandAllTreeNodes() {
    var nodes = document.querySelectorAll('.tree-collapsible-content');
    for (var i = 0; i < nodes.length; i++) {
      if (!nodes[i].id || !nodes[i].id.startsWith('item_detail_')) {
        nodes[i].style.display = 'block';
      }
    }
    var icons = document.querySelectorAll('.tree-toggle-icon');
    for (var j = 0; j < icons.length; j++) {
      if (icons[j].id && icons[j].id.startsWith('icon_item_detail_')) continue;
      icons[j].className = 'fas fa-chevron-down tree-toggle-icon';
    }
  }

  function collapseAllTreeNodes() {
    var nodes = document.querySelectorAll('.tree-collapsible-content');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.display = 'none';
    }
    var icons = document.querySelectorAll('.tree-toggle-icon');
    for (var j = 0; j < icons.length; j++) {
      if (icons[j].className.indexOf('fa-caret-') >= 0) {
        icons[j].className = 'fas fa-caret-right tree-toggle-icon';
      } else {
        icons[j].className = 'fas fa-chevron-right tree-toggle-icon';
      }
    }
  }

  var subtreePaginationState = {};
  function changeSubtreePage(unitId, delta, totalPages) {
    if (!subtreePaginationState[unitId]) subtreePaginationState[unitId] = 1;
    var newPage = subtreePaginationState[unitId] + delta;
    if (newPage < 1) newPage = 1;
    if (newPage > totalPages) newPage = totalPages;
    subtreePaginationState[unitId] = newPage;
    
    var items = document.querySelectorAll('.item-row-' + unitId);
    for (var i = 0; i < items.length; i++) {
      var itemPage = parseInt(items[i].getAttribute('data-page') || '1');
      items[i].style.display = (itemPage === newPage) ? 'flex' : 'none';
    }
    var details = document.querySelectorAll('.item-detail-' + unitId);
    for (var d = 0; d < details.length; d++) {
      var detailPage = parseInt(details[d].getAttribute('data-page') || '1');
      if (detailPage !== newPage) {
        details[d].style.display = 'none';
        var dId = details[d].id;
        var icon = document.getElementById('icon_' + dId);
        if (icon) icon.className = 'fas fa-caret-right tree-toggle-icon';
      }
    }
    var label = document.getElementById('page_label_' + unitId);
    if (label) {
      label.innerText = 'Pág ' + newPage + ' / ' + totalPages + ' (' + items.length + ' total)';
    }
  }

  var currentInspectRecord = null;
  var resolvedCache = {};

  function showReferenceWarningModal(uri, engine, db, id, address, cluster) {
    setElementValues({
      refWarnUriDisplay: uri || 'N/A',
      refWarnEngineDbDisplay: (engine || 'DOCUMENT') + ' / ' + (db || 'default') + (id ? ' (ID: ' + id + ')' : ''),
      refWarnAddressDisplay: address || 'N/A',
      refWarnClusterDisplay: cluster || 'Local Cluster (Primary)'
    });
    showModal('referenceWarningModal');
  }

  function parseJrefUri(uri) {
    if (!uri || typeof uri !== 'string') return null;
    var clean = uri.trim();
    var idx = clean.indexOf('jref://');
    if (idx < 0) return null;
    clean = clean.substring(idx);
    var rest = clean.substring(7);
    var node = 'Local Cluster (Primary)';
    var atIdx = rest.indexOf('@');
    if (atIdx > 0) {
      node = rest.substring(0, atIdx);
      rest = rest.substring(atIdx + 1);
    }
    var slashIdx = rest.indexOf('/');
    if (slashIdx <= 0) return null;
    var left = rest.substring(0, slashIdx);
    var id = rest.substring(slashIdx + 1);
    var engine = 'DOCUMENT';
    var db = left;
    var colonIdx = left.indexOf(':');
    if (colonIdx > 0) {
      engine = left.substring(0, colonIdx).toUpperCase();
      db = left.substring(colonIdx + 1);
    }
    var pfx = {
      'RECORDS': 'rec:',
      'KEYVALUE': 'kv:',
      'VECTOR': 'vec:',
      'GRAPH': 'graph:',
      'TIMESERIES': 'ts:',
      'COLUMN': 'col:',
      'GEOSPATIAL': 'geo:',
      'OBJECT': 'obj:'
    }[engine] || 'doc:';
    var primaryKey = pfx + db + ':' + id;
    return { uri: clean, node: node, engine: engine, database: db, entityId: id, primaryStorageAddress: primaryKey };
  }

  function findJrefsInObject(obj, list, seenUris) {
    if (!obj) return;
    if (!seenUris) seenUris = {};

    if (typeof obj === 'string') {
      var startIdx = 0;
      while ((startIdx = obj.indexOf('jref://', startIdx)) !== -1) {
        var endIdx = startIdx + 7;
        while (endIdx < obj.length) {
          var code = obj.charCodeAt(endIdx);
          if (code <= 32 || code === 34 || code === 39 || code === 44 || code === 93 || code === 125) {
            break;
          }
          endIdx++;
        }
        var u = obj.substring(startIdx, endIdx);
        if (u && !seenUris[u]) {
          seenUris[u] = true;
          var parsed = parseJrefUri(u);
          if (parsed) list.push({ fieldKey: 'inline', parsed: parsed });
        }
        startIdx = endIdx + 1;
      }
      return;
    }

    if (Array.isArray(obj)) {
      for (var a = 0; a < obj.length; a++) {
        findJrefsInObject(obj[a], list, seenUris);
      }
      return;
    }

    if (typeof obj === 'object') {
      if (obj['$jref'] && typeof obj['$jref'] === 'string') {
        var u = obj['$jref'].trim();
        if (!seenUris[u]) {
          seenUris[u] = true;
          var parsedObj = parseJrefUri(u);
          if (parsedObj) list.push({ fieldKey: '$jref', parsed: parsedObj });
        }
      }

      for (var k in obj) {
        if (!obj.hasOwnProperty(k)) continue;
        var v = obj[k];
        if (typeof v === 'string') {
          var startIdx = 0;
          while ((startIdx = v.indexOf('jref://', startIdx)) !== -1) {
            var endIdx = startIdx + 7;
            while (endIdx < v.length) {
              var code = v.charCodeAt(endIdx);
              if (code <= 32 || code === 34 || code === 39 || code === 44 || code === 93 || code === 125) {
                break;
              }
              endIdx++;
            }
            var u = v.substring(startIdx, endIdx);
            if (u && !seenUris[u]) {
              seenUris[u] = true;
              var parsedItem = parseJrefUri(u);
              if (parsedItem) list.push({ fieldKey: k, parsed: parsedItem });
            }
            startIdx = endIdx + 1;
          }
        } else if (v && typeof v === 'object') {
          findJrefsInObject(v, list, seenUris);
        }
      }
    }
  }

  function openInspectRecordModal(engine, db, unit, id, payloadB64, vCount) {
    var payload = decodeUtf8Base64(payloadB64);
    var parsed = null;
    try { parsed = JSON.parse(payload); } catch(e) {}
    
    var refs = [];
    if (parsed) {
      findJrefsInObject(parsed, refs, {});
    }

    currentInspectRecord = {
      engine: engine,
      db: db,
      unit: unit || 'default',
      id: id,
      rawPayload: payload,
      parsed: parsed,
      refs: refs,
      payloadB64: payloadB64,
      vCount: vCount || 1
    };
    
    setElementValues({
      inspectRecordEngineDisplay: engine,
      inspectRecordDbDisplay: db,
      inspectRecordCollDisplay: unit || 'default',
      inspectRecordIdDisplay: id,
      inspectRecordVersionDisplay: 'v' + (vCount || 1)
    });

    var chk = document.getElementById('chkInspectResolveRefs');
    var shouldResolve = chk ? chk.checked : true;
    toggleInspectReferenceResolution(shouldResolve);

    showModal('inspectRecordModal');
  }

  function toggleInspectReferenceResolution(shouldResolve) {
    if (!currentInspectRecord) return;
    var payloadEl = document.getElementById('inspectRecordPayloadDisplay');
    var refContainer = document.getElementById('inspectRecordReferencesContainer');
    var refList = document.getElementById('inspectRecordReferencesList');
    var refBadge = document.getElementById('inspectReferencesCountBadge');

    var refs = currentInspectRecord.refs || [];

    if (!shouldResolve || refs.length === 0) {
      var prettyRaw = currentInspectRecord.parsed ? JSON.stringify(currentInspectRecord.parsed, null, 2) : currentInspectRecord.rawPayload;
      if (payloadEl) payloadEl.value = prettyRaw;
      if (refContainer) refContainer.style.display = 'none';
      if (refBadge) {
        if (refs.length > 0) {
          refBadge.innerText = refs.length + ' Ref(s) (Unresolved)';
          refBadge.style.display = 'inline-block';
          refBadge.style.background = 'rgba(148,163,184,0.2)';
          refBadge.style.color = '#94a3b8';
        } else {
          refBadge.style.display = 'none';
        }
      }
      return;
    }

    // Resolve references and display enriched view
    if (refBadge) {
      refBadge.innerText = refs.length + ' Ref(s) Auto-Resolved';
      refBadge.style.display = 'inline-block';
      refBadge.style.background = 'rgba(56,189,248,0.2)';
      refBadge.style.color = '#38bdf8';
    }

    var promises = [];
    var resolvedMap = {};

    var getRefEndpoint = function(uriStr) {
      var base = window.location.pathname || '/engines';
      if (base.indexOf('?') >= 0) base = base.split('?')[0];
      return base + '?action=resolve_ref&uri=' + encodeURIComponent(uriStr);
    };

    refs.forEach(function(item) {
      var u = item.parsed.uri;
      if (resolvedCache[u]) {
        resolvedMap[u] = resolvedCache[u];
      } else {
        var p = fetch(getRefEndpoint(u))
          .then(function(res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
          })
          .then(function(data) {
            resolvedCache[u] = data;
            resolvedMap[u] = data;
          }).catch(function() {
            resolvedMap[u] = { exists: false, uri: u, primaryStorageAddress: item.parsed.primaryStorageAddress, clusterNode: item.parsed.node };
          });
        promises.push(p);
      }
    });

    Promise.all(promises).then(function() {
      var enrichedJson = enrichObjectWithRefs(JSON.parse(JSON.stringify(currentInspectRecord.parsed)), resolvedMap);
      if (payloadEl) {
        payloadEl.value = JSON.stringify(enrichedJson, null, 2);
      }
      renderReferencedCards(refs, resolvedMap, refList);
      if (refContainer) refContainer.style.display = 'block';
    });
  }

  function enrichObjectWithRefs(obj, resolvedMap) {
    if (!obj || typeof obj !== 'object') return obj;
    for (var k in obj) {
      if (!obj.hasOwnProperty(k)) continue;
      if (k === '_resolved') continue;
      var v = obj[k];
      if (typeof v === 'string' && v.indexOf('jref://') >= 0) {
        var p = parseJrefUri(v);
        if (p && resolvedMap[p.uri] && (resolvedMap[p.uri].exists || resolvedMap[p.uri].jsonPayload || resolvedMap[p.uri].rawPayload)) {
          var res = resolvedMap[p.uri];
          obj[k] = {
            '$jref': p.uri,
            '_primaryAddress': res.primaryStorageAddress || p.primaryStorageAddress,
            '_clusterNode': res.clusterNode || p.node,
            '_engine': res.engine || p.engine,
            '_database': res.database || p.database,
            '_version': res.version || 1,
            '_resolved': res.jsonPayload || res.rawPayload || {}
          };
        }
      } else if (v && typeof v === 'object') {
        if (v['$jref'] && typeof v['$jref'] === 'string') {
          var p2 = parseJrefUri(v['$jref']);
          var lookupKey = p2 ? p2.uri : v['$jref'];
          if (resolvedMap[lookupKey] && (resolvedMap[lookupKey].exists || resolvedMap[lookupKey].jsonPayload || resolvedMap[lookupKey].rawPayload)) {
            var resObj = resolvedMap[lookupKey];
            v['_primaryAddress'] = resObj.primaryStorageAddress || (p2 ? p2.primaryStorageAddress : '');
            v['_clusterNode'] = resObj.clusterNode || (p2 ? p2.node : 'Local Cluster (Primary)');
            v['_engine'] = resObj.engine || (p2 ? p2.engine : 'DOCUMENT');
            v['_database'] = resObj.database || (p2 ? p2.database : '');
            v['_version'] = resObj.version || 1;
            v['_resolved'] = resObj.jsonPayload || resObj.rawPayload || {};
          }
        } else {
          enrichObjectWithRefs(v, resolvedMap);
        }
      }
    }
    return obj;
  }

  function renderReferencedCards(refs, resolvedMap, container) {
    if (!container) return;
    container.innerHTML = '';
    
    var engColors = {
      'DOCUMENT': '#3b82f6',
      'RECORDS': '#f43f5e',
      'VECTOR': '#8b5cf6',
      'GEOSPATIAL': '#14b8a6',
      'OBJECT': '#a855f7',
      'KEYVALUE': '#10b981',
      'TIMESERIES': '#06b6d4',
      'GRAPH': '#ec4899',
      'COLUMN': '#f97316'
    };

    var engIcons = {
      'DOCUMENT': 'fas fa-file-code',
      'RECORDS': 'fas fa-id-card',
      'VECTOR': 'fas fa-brain',
      'GEOSPATIAL': 'fas fa-location-dot',
      'OBJECT': 'fas fa-box-archive',
      'KEYVALUE': 'fas fa-key',
      'TIMESERIES': 'fas fa-stopwatch',
      'GRAPH': 'fas fa-circle-nodes',
      'COLUMN': 'fas fa-table'
    };

    refs.forEach(function(item) {
      var p = item.parsed;
      var res = resolvedMap[p.uri] || {};
      var color = engColors[p.engine] || '#38bdf8';
      var icon = engIcons[p.engine] || 'fas fa-link';
      var exists = res.exists !== false;
      var primaryAddr = res.primaryStorageAddress || p.primaryStorageAddress;
      var cluster = res.clusterNode || p.node;

      var card = document.createElement('div');
      card.style.display = 'flex';
      card.style.alignItems = 'center';
      card.style.justifyContent = 'space-between';
      card.style.padding = '8px 12px';
      card.style.background = 'rgba(15,23,42,0.85)';
      card.style.border = '1px solid ' + (exists ? 'rgba(56,189,248,0.25)' : 'rgba(239,68,68,0.3)');
      card.style.borderRadius = '6px';
      card.style.gap = '8px';

      var leftInfo = document.createElement('div');
      leftInfo.style.display = 'flex';
      leftInfo.style.alignItems = 'center';
      leftInfo.style.gap = '8px';
      leftInfo.style.flex = '1';
      leftInfo.style.minWidth = '0';

      var badge = document.createElement('span');
      badge.className = 'store-badge';
      badge.style.background = 'rgba(255,255,255,0.08)';
      badge.style.color = color;
      badge.style.border = '1px solid ' + color;
      badge.style.fontSize = '10px';
      badge.innerHTML = '<i class="' + icon + '" style="margin-right:4px;"></i>' + p.engine;

      var textCol = document.createElement('div');
      textCol.style.overflow = 'hidden';
      textCol.style.textOverflow = 'ellipsis';
      textCol.style.whiteSpace = 'nowrap';

      var uriText = document.createElement('div');
      uriText.style.fontFamily = 'monospace';
      uriText.style.fontSize = '11px';
      uriText.style.fontWeight = 'bold';
      uriText.style.color = '#f8fafc';
      uriText.innerText = p.uri;

      var addressText = document.createElement('div');
      addressText.style.fontSize = '10.5px';
      addressText.style.color = '#94a3b8';
      addressText.innerHTML = '<span style="color:#4ade80;"><i class="fas fa-database"></i> ' + primaryAddr + '</span> | <span style="color:#c084fc;"><i class="fas fa-network-wired"></i> ' + cluster + '</span>' + (exists ? ' | <span style="color:#38bdf8;">v' + (res.version || 1) + '</span>' : ' | <span style="color:#ef4444;">Not Resolved</span>');

      textCol.appendChild(uriText);
      textCol.appendChild(addressText);
      leftInfo.appendChild(badge);
      leftInfo.appendChild(textCol);

      var btnInspect = document.createElement('button');
      btnInspect.type = 'button';
      btnInspect.className = 'btn-action btn-secondary';
      btnInspect.style.fontSize = '10.5px';
      btnInspect.style.padding = '3px 8px';
      btnInspect.style.color = color;
      btnInspect.style.borderColor = color;
      btnInspect.innerHTML = '<i class="fas fa-external-link-alt"></i> Ver Objeto';
      btnInspect.onclick = (function(uriToInspect, eng, dbName, entId, pAddr, clNode) {
        return function() {
          inspectReferencedEntity(uriToInspect, eng, dbName, entId, pAddr, clNode);
        };
      })(p.uri, p.engine, p.database, p.entityId, primaryAddr, cluster);

      card.appendChild(leftInfo);
      card.appendChild(btnInspect);
      container.appendChild(card);
    });
  }

  function inspectReferencedEntity(uriOrEngine, db, unitOrDb, id, directAddr, clusterNode) {
    var uri = '';
    var targetEngine = 'DOCUMENT';
    var targetDb = 'default';
    var targetId = '';
    var targetAddr = directAddr || '';
    var targetCluster = clusterNode || 'Local Cluster (Primary)';

    if (typeof uriOrEngine === 'string' && uriOrEngine.indexOf('jref://') >= 0) {
      uri = uriOrEngine.trim();
      var p = parseJrefUri(uri);
      if (p) {
        targetEngine = p.engine;
        targetDb = p.database;
        targetId = p.entityId;
        if (!targetAddr) targetAddr = p.primaryStorageAddress;
        if (!targetCluster || targetCluster === 'Local Cluster (Primary)') targetCluster = p.node;
      }
    } else {
      targetEngine = uriOrEngine || 'DOCUMENT';
      targetDb = db || 'default';
      targetId = id || '';
      uri = 'jref://' + targetEngine + ':' + targetDb + '/' + targetId;
      var pfx = {
        'RECORDS': 'rec:',
        'KEYVALUE': 'kv:',
        'VECTOR': 'vec:',
        'GRAPH': 'graph:',
        'TIMESERIES': 'ts:',
        'COLUMN': 'col:',
        'GEOSPATIAL': 'geo:',
        'OBJECT': 'obj:'
      }[targetEngine] || 'doc:';
      if (!targetAddr) targetAddr = pfx + targetDb + ':' + targetId;
    }

    var base = window.location.pathname || '/engines';
    if (base.indexOf('?') >= 0) base = base.split('?')[0];
    var endpoint = base + '?action=resolve_ref&uri=' + encodeURIComponent(uri);

    fetch(endpoint)
      .then(function(res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function(data) {
        if (!data || data.exists === false) {
          showReferenceWarningModal(uri, targetEngine, targetDb, targetId, (data ? data.primaryStorageAddress : targetAddr), (data ? data.clusterNode : targetCluster));
          return;
        }
        var rawPayload = data.rawPayload || (data.jsonPayload ? JSON.stringify(data.jsonPayload) : '{}');
        var b64 = btoa(unescape(encodeURIComponent(rawPayload)));
        openInspectRecordModal(data.engine || targetEngine, data.database || targetDb, 'default', data.entityId || targetId, b64, data.version || 1);
      })
      .catch(function(err) {
        showReferenceWarningModal(uri, targetEngine, targetDb, targetId, targetAddr, targetCluster);
      });
  }

  function toggleGlobalReferenceResolution(checked) {
    var inspectChk = document.getElementById('chkInspectResolveRefs');
    if (inspectChk) {
      inspectChk.checked = checked;
      toggleInspectReferenceResolution(checked);
    }
  }

  function copyInspectRecordPayload() {
    var el = document.getElementById('inspectRecordPayloadDisplay');
    if (el) {
      navigator.clipboard.writeText(el.value);
      var btn = document.getElementById('btnCopyInspect');
      if (btn) {
        var orig = btn.innerHTML;
        btn.innerHTML = '<i class="fas fa-check"></i> Copied!';
        setTimeout(function() { btn.innerHTML = orig; }, 1800);
      }
    }
  }

  function editFromInspectModal() {
    if (currentInspectRecord) {
      hideModal('inspectRecordModal');
      openUniversalEditModal(currentInspectRecord.engine, currentInspectRecord.db, currentInspectRecord.unit, currentInspectRecord.id, currentInspectRecord.payloadB64);
    }
  }

  function historyFromInspectModal() {
    if (currentInspectRecord) {
      hideModal('inspectRecordModal');
      var versionsJson = JSON.stringify([{ versionNumber: currentInspectRecord.vCount, isCurrent: true, preview: currentInspectRecord.id, timestamp: Date.now() }]);
      openUniversalRestoreModal(currentInspectRecord.engine, currentInspectRecord.db, currentInspectRecord.unit, currentInspectRecord.id, btoa(versionsJson));
    }
  }

  function filterExplorerTable() {
    var input = document.getElementById('tableExplorerQuickFilter');
    var filter = input ? input.value.toLowerCase().trim() : '';
    var rows = document.querySelectorAll('.explorer-table-row');
    var visibleCount = 0;
    for (var i = 0; i < rows.length; i++) {
      var text = rows[i].innerText.toLowerCase();
      if (!filter || text.indexOf(filter) > -1) {
        rows[i].style.display = 'flex';
        visibleCount++;
      } else {
        rows[i].style.display = 'none';
      }
    }
    var counter = document.getElementById('tableFilterVisibleCount');
    if (counter) counter.innerText = visibleCount + ' Records Visible';
  }

  function onSearchModeChange(mode) {
    var sections = ['advSectionUniversal', 'advSectionQuery', 'advSectionVector', 'advSectionGeo', 'advSectionTimeseries', 'advSectionGraph'];
    for (var i = 0; i < sections.length; i++) {
      var el = document.getElementById(sections[i]);
      if (el) el.style.display = 'none';
    }
    var modeMap = {
      'UNIVERSAL': 'advSectionUniversal',
      'QUERY': 'advSectionQuery',
      'VECTOR': 'advSectionVector',
      'GEOSPATIAL': 'advSectionGeo',
      'TIMESERIES': 'advSectionTimeseries',
      'GRAPH': 'advSectionGraph'
    };
    var targetId = modeMap[mode] || 'advSectionUniversal';
    var targetEl = document.getElementById(targetId);
    if (targetEl) targetEl.style.display = 'block';
  }

  function openAdvSearchHelpModal(tab) {
    showModal('advSearchHelpModal');
    if (tab) {
      filterHelpTab(tab);
    }
  }

  function filterHelpTab(tabId) {
    var tabs = document.querySelectorAll('.help-engine-tab');
    var contents = document.querySelectorAll('.help-engine-card');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].style.background = 'rgba(255,255,255,0.06)';
      tabs[i].style.color = '#cbd5e1';
      tabs[i].style.borderColor = 'rgba(255,255,255,0.1)';
    }
    for (var j = 0; j < contents.length; j++) {
      contents[j].style.display = 'none';
    }
    var activeTab = document.getElementById('tab-btn-' + tabId);
    if (activeTab) {
      activeTab.style.background = 'rgba(56,189,248,0.2)';
      activeTab.style.color = '#38bdf8';
      activeTab.style.borderColor = 'rgba(56,189,248,0.5)';
    }
    if (tabId === 'all') {
      for (var k = 0; k < contents.length; k++) {
        contents[k].style.display = 'block';
      }
    } else {
      var activeContent = document.getElementById('help-card-' + tabId);
      if (activeContent) activeContent.style.display = 'block';
    }
  }

  function applySearchExample(mode, values) {
    var modeSelect = document.getElementById('advSearchModeSelect');
    if (modeSelect) {
      modeSelect.value = mode;
      onSearchModeChange(mode);
    }
    if (values) {
      setElementValues(values);
    }
    hideModal('advSearchHelpModal');
    showModal('advancedSearchModal');
  }

  // Teleport all modals to document.body on load so they escape any CSS containing blocks
  document.addEventListener('DOMContentLoaded', function() {
    var modalIds = [
      'createDbModal', 'createUnitModal', 'addDocumentModal', 'addKeyValueModal',
      'addVectorModal', 'addGraphModal', 'addTimeSeriesModal', 'addColumnModal',
      'addGeoModal', 'addObjectModal', 'addRecordsModal', 'editDocumentModal',
      'editKeyValueModal', 'editVectorModal', 'editGraphModal', 'editTimeSeriesModal',
      'editColumnModal', 'editGeoModal', 'editObjectModal', 'editRecordsModal',
      'universalRestoreModal', 'confirmRestoreModal', 'confirmDeleteModal',
      'inspectRecordModal', 'referenceWarningModal', 'advancedSearchModal',
      'advSearchHelpModal', 'backupDbModal', 'restoreDbModal', 'confirmDbRestoreModal',
      'exportDataModal', 'createIndexModal', 'createSchemaModal'
    ];
    modalIds.forEach(function(mid) {
      var el = document.getElementById(mid);
      if (el && el.parentElement && el.parentElement !== document.body) {
        document.body.appendChild(el);
      }
    });
  });
""";
        return RawScript.of(js);
    }
}
