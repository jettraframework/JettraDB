package com.jettra.store.engine.web;

import com.jettra.store.engine.core.DatabaseBackupManager;
import com.jettra.store.engine.core.DatabaseBackupManager.BackupFileInfo;
import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.models.*;
import com.jettra.store.engine.ref.JettraReference;
import com.jettra.store.engine.ref.JettraReferenceResolver;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.hierarchy.HierarchyJsonStreamer;
import com.jettra.store.engine.hierarchy.HierarchyNode;
import com.jettra.store.engine.hierarchy.HierarchyResult;
import com.jettra.store.engine.hierarchy.MultiModelSubtreeFactory;
import com.jettra.store.engine.hierarchy.StorageEngineType;
import com.jettra.store.engine.samples.SampleDatasetManager;
import com.jettra.store.engine.samples.lifecycle.InstallState;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseDefinition;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseService;
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
    private final HierarchyExplorerService hierarchyService;
    private final SampleDatabaseService sampleDbService;
    private final RestoreActionHandler restoreHandler;

    public StoreEnginesPage(JettraStorageEngine engine) {
        this.engine = engine;
        this.refResolver = new JettraReferenceResolver(engine);
        this.hierarchyService = new HierarchyExplorerService(engine);
        this.sampleDbService = new SampleDatabaseService(engine);
        this.restoreHandler = new RestoreActionHandler(engine);
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
        if (params != null && ("load_hierarchy".equalsIgnoreCase(params.get("action")) || "expand_db".equalsIgnoreCase(params.get("action")))) {
            handleLoadHierarchy(exchange, params);
            return true;
        }
        if (params != null && "list_sample_dbs".equalsIgnoreCase(params.get("action"))) {
            handleListSampleDatabases(exchange, params);
            return true;
        }
        if (params != null && "install_sample_db".equalsIgnoreCase(params.get("action"))) {
            handleInstallSampleDatabase(exchange, params);
            return true;
        }
        if (params != null && "uninstall_sample_db".equalsIgnoreCase(params.get("action"))) {
            handleUninstallSampleDatabase(exchange, params);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        String action = params != null ? params.get("action") : null;
        String reqWith = exchange.getRequestHeaders() != null ? exchange.getRequestHeaders().getFirst("X-Requested-With") : null;
        if (action != null && (action.endsWith("_ajax") || "true".equalsIgnoreCase(params.get("is_ajax")) || "XMLHttpRequest".equalsIgnoreCase(reqWith)
            || "install_sample_db".equalsIgnoreCase(action) || "uninstall_sample_db".equalsIgnoreCase(action) || "list_sample_dbs".equalsIgnoreCase(action))) {
            handleAjaxPost(exchange, params);
            return true;
        }
        return false;
    }

    public void handleAjaxPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        String action = params != null ? params.get("action") : "";
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";
        String targetDb = params != null && params.containsKey("target_db") ? params.get("target_db") : getDefaultDbForEngine(selectedEngine);

        try {
            if ("insert_object".equalsIgnoreCase(action) || "insert_object_ajax".equalsIgnoreCase(action)) {
                InsertResult res = executeTypeSpecificInsert(selectedEngine, targetDb, params);
                JsonObject resp = new JsonObject();
                resp.addProperty("status", "SUCCESS");
                resp.addProperty("database", res.database());
                resp.addProperty("engine", res.engineName());
                resp.addProperty("collection", res.targetColl());
                resp.addProperty("itemId", res.targetId());
                resp.addProperty("message", "Object '" + res.targetId() + "' successfully created in " + res.engineName() + " [" + res.database() + ":" + res.targetColl() + "]!");
                sendJsonResponse(exchange, resp, 200);
            } else if ("install_sample_db".equalsIgnoreCase(action) || "install_sample_db_ajax".equalsIgnoreCase(action)) {
                handleInstallSampleDatabase(exchange, params);
            } else if ("uninstall_sample_db".equalsIgnoreCase(action) || "uninstall_sample_db_ajax".equalsIgnoreCase(action)) {
                handleUninstallSampleDatabase(exchange, params);
            } else if ("list_sample_dbs".equalsIgnoreCase(action)) {
                handleListSampleDatabases(exchange, params);
            } else if ("create_unit".equalsIgnoreCase(action) || "create_unit_ajax".equalsIgnoreCase(action)) {
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
                    initDoc.addProperty("status", "INITIALIZED");
                    initDoc.addProperty("createdAt", System.currentTimeMillis());
                    engine.getStorageCore().put(internalKey, initDoc.toString().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                    JsonObject resp = new JsonObject();
                    resp.addProperty("status", "SUCCESS");
                    resp.addProperty("database", targetDb);
                    resp.addProperty("engine", engType);
                    resp.addProperty("collection", cleanUnit);
                    resp.addProperty("itemId", "init_01");
                    resp.addProperty("message", "Subtree Unit '" + cleanUnit + "' created in " + engType + "!");
                    sendJsonResponse(exchange, resp, 200);
                } else {
                    sendJsonError(exchange, "Unit name cannot be empty");
                }
            } else if ("create_db".equalsIgnoreCase(action) || "create_db_ajax".equalsIgnoreCase(action)) {
                String newDb = params.get("new_db_name");
                if (newDb == null || newDb.isBlank()) newDb = params.get("target_db");
                String initEngine = params.getOrDefault("initial_engine", "DOCUMENT");
                String initUnit = params.getOrDefault("initial_unit", "default");
                if (newDb != null && !newDb.isBlank()) {
                    String cleanDb = newDb.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    initializeDatabaseEngineSubtrees(cleanDb, initEngine, initUnit);
                    JsonObject resp = new JsonObject();
                    resp.addProperty("status", "SUCCESS");
                    resp.addProperty("database", cleanDb);
                    resp.addProperty("engine", initEngine);
                    resp.addProperty("collection", initUnit);
                    resp.addProperty("itemId", "init_01");
                    resp.addProperty("message", "Database '" + cleanDb + "' created with all 9 multi-model engine subtrees ready!");
                    sendJsonResponse(exchange, resp, 200);
                } else {
                    sendJsonError(exchange, "Database name cannot be empty");
                }
            } else if ("edit_document".equalsIgnoreCase(action) || "edit_object".equalsIgnoreCase(action) || "edit_record".equalsIgnoreCase(action) || "update_object".equalsIgnoreCase(action)) {
                String id = params.get("target_id");
                String coll = params.getOrDefault("target_coll", "default");
                String engType = params.getOrDefault("engine_type", selectedEngine);
                String payload = params.get("record_payload");
                if (payload == null) payload = params.get("doc_payload");
                if (payload == null) payload = params.get("raw_payload");
                if (payload == null) payload = params.get("kv_value");
                if (payload == null) payload = params.get("rec_payload");
                executeTypeSpecificEdit(engType, targetDb, id, coll, payload, params);
                JsonObject resp = new JsonObject();
                resp.addProperty("status", "SUCCESS");
                resp.addProperty("database", targetDb);
                resp.addProperty("engine", selectedEngine);
                resp.addProperty("collection", coll);
                resp.addProperty("itemId", id);
                resp.addProperty("message", "Entity '" + id + "' updated successfully in " + selectedEngine + "!");
            } else if ("restore_version".equalsIgnoreCase(action) || "restore_version_ajax".equalsIgnoreCase(action)) {
                long targetTs = Long.parseLong(params.getOrDefault("version_ts", "0"));
                String engType = params.getOrDefault("engine_type", selectedEngine);
                String coll = params.getOrDefault("target_coll", "default");
                String id = params.getOrDefault("target_id", "");
                int vNum = 0;
                try { vNum = Integer.parseInt(params.getOrDefault("version_number", "0")); } catch (Exception ignored) {}
                String rDb = params.getOrDefault("target_db", targetDb);

                RollbackCommand cmd = new RollbackCommand(engType, rDb, coll, id, targetTs, vNum, "web_admin", "UI Version Rollback Request");
                java.util.concurrent.CompletableFuture<RestoreActionHandler.RestoreResult> future = restoreHandler.executeRollbackAsync(cmd);
                RestoreActionHandler.RestoreResult result = future.join();

                JsonObject resp = new JsonObject();
                resp.addProperty("status", result.success() ? "SUCCESS" : "WARNING");
                resp.addProperty("database", result.database());
                resp.addProperty("engine", result.engineType());
                resp.addProperty("collection", result.collection());
                resp.addProperty("itemId", result.recordId());
                resp.addProperty("timestamp", result.timestamp());
                resp.addProperty("message", result.message());
                sendJsonResponse(exchange, resp, 200);
            } else if ("load_version_history_table".equalsIgnoreCase(action)) {
                String engType = params.getOrDefault("engine", selectedEngine);
                String rDb = params.getOrDefault("target_db", targetDb);
                String coll = params.getOrDefault("target_coll", "default");
                String id = params.getOrDefault("target_id", "");

                List<RecordVersionSnapshot> snapshots = hierarchyService.getVersionSnapshots(engType, rDb, coll, id);
                Widget tableWidget = HistoricalVersionsDialog.renderVersionTable(engType, rDb, coll, id, snapshots);
                String html = tableWidget.render(io.jettra.flux.theme.Themes.FlatTheme());

                JsonObject resp = new JsonObject();
                resp.addProperty("status", "SUCCESS");
                resp.addProperty("html", html);
                sendJsonResponse(exchange, resp, 200);
            } else if ("delete_object".equalsIgnoreCase(action) || "delete_record".equalsIgnoreCase(action)) {
                String id = params.get("target_id");
                String coll = params.getOrDefault("target_coll", "default");
                executeTypeSpecificDelete(selectedEngine, targetDb, id, coll, params);
                JsonObject resp = new JsonObject();
                resp.addProperty("status", "SUCCESS");
                resp.addProperty("database", targetDb);
                resp.addProperty("engine", selectedEngine);
                resp.addProperty("collection", coll);
                resp.addProperty("itemId", id);
                resp.addProperty("message", "Entity '" + id + "' deleted successfully from " + selectedEngine + "!");
                sendJsonResponse(exchange, resp, 200);
            } else {
                JsonObject resp = new JsonObject();
                resp.addProperty("status", "SUCCESS");
                resp.addProperty("action", action);
                sendJsonResponse(exchange, resp, 200);
            }
        } catch (Exception e) {
            sendJsonError(exchange, "Operation failed: " + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    public void handleListSampleDatabases(HttpExchange exchange, Map<String, String> params) throws IOException {
        JsonArray arr = new JsonArray();
        for (SampleDatabaseDefinition def : sampleDbService.getCatalog()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", def.id());
            obj.addProperty("engineType", def.engineType());
            obj.addProperty("databaseName", def.databaseName());
            obj.addProperty("displayName", def.displayName());
            obj.addProperty("description", def.description());
            obj.addProperty("estimatedRecords", def.estimatedRecords());
            obj.addProperty("icon", def.icon());
            InstallState state = sampleDbService.getInstallState(def.databaseName());
            obj.addProperty("installState", state.name());
            obj.addProperty("badgeCss", state.getBadgeCss());
            obj.addProperty("badgeLabel", state.getLabel());
            obj.addProperty("badgeIcon", state.getIcon());
            obj.addProperty("isInstalled", state == InstallState.INSTALLED);
            obj.addProperty("recordCount", sampleDbService.getInstalledRecordCount(def.databaseName()));
            arr.add(obj);
        }
        JsonObject res = new JsonObject();
        res.addProperty("status", "SUCCESS");
        res.add("databases", arr);
        sendJsonResponse(exchange, res, 200);
    }

    public void handleInstallSampleDatabase(HttpExchange exchange, Map<String, String> params) throws IOException {
        String dbName = params != null ? (params.containsKey("target_db") ? params.get("target_db") : params.get("db_name")) : null;
        if (dbName == null || dbName.isBlank()) {
            sendJsonError(exchange, "Missing target_db parameter");
            return;
        }
        HierarchyResult<Integer> res = sampleDbService.install(dbName.trim());
        if (res.isSuccess()) {
            JsonObject resp = new JsonObject();
            resp.addProperty("status", "SUCCESS");
            resp.addProperty("database", dbName);
            resp.addProperty("installedRecords", res.getOrNull());
            resp.addProperty("message", "Sample database '" + dbName + "' installed successfully (" + res.getOrNull() + " records created)!");
            sendJsonResponse(exchange, resp, 200);
        } else {
            sendJsonError(exchange, res.errorMessage());
        }
    }

    public void handleUninstallSampleDatabase(HttpExchange exchange, Map<String, String> params) throws IOException {
        String dbName = params != null ? (params.containsKey("target_db") ? params.get("target_db") : params.get("db_name")) : null;
        if (dbName == null || dbName.isBlank()) {
            sendJsonError(exchange, "Missing target_db parameter");
            return;
        }
        HierarchyResult<Integer> res = sampleDbService.uninstall(dbName.trim());
        if (res.isSuccess()) {
            JsonObject resp = new JsonObject();
            resp.addProperty("status", "SUCCESS");
            resp.addProperty("database", dbName);
            resp.addProperty("deletedRecords", res.getOrNull());
            resp.addProperty("message", "Sample database '" + dbName + "' uninstalled successfully (" + res.getOrNull() + " records purged)!");
            sendJsonResponse(exchange, resp, 200);
        } else {
            sendJsonError(exchange, res.errorMessage());
        }
    }

    private void sendJsonResponse(HttpExchange exchange, JsonObject obj, int status) throws IOException {
        byte[] b = jsonParser.toJson(obj).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
            os.flush();
        }
    }

    private void sendJsonError(HttpExchange exchange, String errorMsg) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("status", "ERROR");
        err.addProperty("message", errorMsg);
        sendJsonResponse(exchange, err, 400);
    }

    public void handleLoadHierarchy(HttpExchange exchange, Map<String, String> params) throws IOException {
        String db = params != null ? params.get("target_db") : null;
        if (db == null || db.isBlank()) {
            db = params != null ? params.get("db") : null;
        }
        if (db == null || db.isBlank()) {
            db = "customers_db";
        }
        db = db.trim();

        HierarchyResult<HierarchyNode.DatabaseNode> res = hierarchyService.resolveDatabaseHierarchy(db);
        byte[] b;
        if (res instanceof HierarchyResult.Success<HierarchyNode.DatabaseNode>(var dbNode)) {
            String json = HierarchyJsonStreamer.toJson(dbNode);
            b = json.getBytes(StandardCharsets.UTF_8);
        } else {
            String err = res.errorMessage() != null ? res.errorMessage() : "Unknown error";
            JsonObject errObj = new JsonObject();
            errObj.addProperty("database", db);
            errObj.addProperty("hasComponents", false);
            errObj.addProperty("totalItems", 0);
            errObj.addProperty("status", "ERROR");
            errObj.addProperty("error", err);
            b = jsonParser.toJson(errObj).getBytes(StandardCharsets.UTF_8);
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
            os.flush();
        }
    }

    public JsonObject buildDatabaseHierarchyJson(String dbName) {
        HierarchyResult<HierarchyNode.DatabaseNode> res = hierarchyService.resolveDatabaseHierarchy(dbName);
        if (res instanceof HierarchyResult.Success<HierarchyNode.DatabaseNode>(var dbNode)) {
            String json = HierarchyJsonStreamer.toJson(dbNode);
            JsonObject obj = jsonParser.fromJson(json, JsonObject.class);
            return obj != null ? obj : new JsonObject();
        }
        JsonObject errObj = new JsonObject();
        errObj.addProperty("database", dbName);
        errObj.addProperty("hasComponents", false);
        errObj.addProperty("totalItems", 0);
        errObj.addProperty("status", "ERROR");
        errObj.addProperty("error", res.errorMessage());
        return errObj;
    }

    public Map<String, List<String>> discoverUnitsAndItems(String engineKey, String db) {
        return hierarchyService.discoverUnitsAndItems(engineKey, db);
    }

    public String getItemPayload(String engineKey, String db, String coll, String id) {
        return hierarchyService.getItemPayload(engineKey, db, coll, id);
    }

    public int getItemVersionCount(String engineKey, String db, String coll, String id) {
        return hierarchyService.getItemVersionCount(engineKey, db, coll, id);
    }

    public String getVersionsJson(String engineKey, String db, String coll, String id) {
        return hierarchyService.getVersionsJson(engineKey, db, coll, id);
    }

    public List<RecordVersionSnapshot> getVersionSnapshots(String engineKey, String db, String coll, String id) {
        return hierarchyService.getVersionSnapshots(engineKey, db, coll, id);
    }

    public String resolveExistingDatabaseName(String dbName) {
        return hierarchyService.resolveExistingDatabaseName(dbName);
    }

    @Override
    protected Set<String> getAvailableDatabases() {
        return discoverAllDatabases();
    }

    public Set<String> discoverAllDatabases() {
        return hierarchyService.discoverAllDatabases();
    }

    public String getPrefixForEngine(String engineKey) {
        return hierarchyService.getPrefixForEngine(engineKey);
    }

    public void initializeDatabaseEngineSubtrees(String cleanDb, String initEngine, String initUnit) {
        MultiModelSubtreeFactory.initializeDatabaseStorage(engine.getStorageCore(), cleanDb, initEngine, initUnit);
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
                res.addProperty("status", resolved.status());
                res.addProperty("diagnostic", resolved.diagnosticMessage());
                res.addProperty("uri", uri);
                res.addProperty("engine", resolved.reference() != null ? resolved.reference().engine() : "DOCUMENT");
                res.addProperty("database", resolved.reference() != null ? resolved.reference().database() : "");
                res.addProperty("entityId", resolved.reference() != null ? resolved.reference().entityId() : "");
                res.addProperty("primaryStorageAddress", resolved.primaryStorageAddress());
                res.addProperty("clusterNode", resolved.clusterNode());
                res.addProperty("version", resolved.version());
                if (resolved.jsonPayload() != null) {
                    res.add("jsonPayload", resolved.jsonPayload());
                }
                if (resolved.rawPayload() != null) {
                    res.addProperty("rawPayload", resolved.rawPayload());
                } else if (resolved.jsonPayload() != null) {
                    res.addProperty("rawPayload", jsonParser.toJson(resolved.jsonPayload()));
                }
            } catch (Exception e) {
                res.addProperty("error", e.getMessage());
                res.addProperty("status", "ERROR");
                res.addProperty("diagnostic", e.getMessage());
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

        // Handle POST Operations or Direct Actions
        if ((exchange != null && "POST".equalsIgnoreCase(exchange.getRequestMethod())) || (params != null && params.containsKey("action"))) {
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
                        initializeDatabaseEngineSubtrees(cleanDb, initEngine, initUnit);
                        targetDb = cleanDb;
                        selectedEngine = initEngine;
                        alertMessage = "Database '" + cleanDb + "' successfully created with all 9 multi-model engine subtrees!";
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
                } else if ("edit_document".equalsIgnoreCase(action) || "edit_object".equalsIgnoreCase(action) || "edit_record".equalsIgnoreCase(action) || "update_object".equalsIgnoreCase(action)) {
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
                    String restoreDb = params.getOrDefault("target_db", targetDb);
                    int vNum = 0;
                    try { vNum = Integer.parseInt(params.getOrDefault("version_number", "0")); } catch (Exception ignored) {}
                    RollbackCommand cmd = new RollbackCommand(engType, restoreDb, coll, targetId, targetTs, vNum, "web_admin", "UI Version Rollback");
                    RestoreActionHandler.RestoreResult result = restoreHandler.executeRollback(cmd);
                    targetDb = result.database();
                    selectedEngine = result.engineType();
                    alertMessage = result.message();
                    alertType = result.success() ? "badge-active" : "badge-raft";
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
        Widget alertWidget = !alertMessage.isBlank() ? Row.of(
            Div.of(
                Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; font-size:14px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:12px; color:var(--j-text-primary); font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:8px;")),
            Span.of("STATUS").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: var(--j-bg-surface); border: 1px solid var(--j-border); padding: 8px 16px; border-radius: 8px; margin: 8px 16px 0 16px; display: flex; align-items: center; justify-content: space-between;")) : RawHtml.of("");

        String currentCollection = params != null && params.containsKey("coll") ? params.get("coll") : "default";

        // Two-Column Studio Layout: Left TYPES sidebar + Right workspace canvas matching screenshot
        Widget studioLayout = createStudioWorkspaceLayout(selectedEngine, targetDb, currentCollection, alertWidget, params);

        Widget modalsWidget = createEngineModals(selectedEngine, targetDb, currentCollection);

        return Div.of(
            studioLayout,
            modalsWidget
        ).modifier(new Modifier().style("width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden;"));
    }

    private Widget createStudioWorkspaceLayout(String selectedEngine, String targetDb, String currentColl, Widget alertWidget, Map<String, String> params) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=");
        String currentTab = params != null ? params.getOrDefault("tab", "schema").toLowerCase() : "schema";
        boolean hasExplicitSelection = params != null && (params.containsKey("coll") || params.containsKey("view_mode") || params.containsKey("engine"));

        // Count metrics for each type
        Map<String, List<String>> verticesMap = discoverUnitsAndItems("GRAPH", targetDb);
        int verticesCount = verticesMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> edgesMap = discoverUnitsAndItems("GRAPH", targetDb);
        int edgesCount = edgesMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> docMap = discoverUnitsAndItems("DOCUMENT", targetDb);
        int docCount = docMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> tsMap = discoverUnitsAndItems("TIMESERIES", targetDb);
        int tsCount = tsMap.values().stream().mapToInt(List::size).sum();

        Map<String, JsonObject> schemasMap = discoverSchemas(targetDb);
        int matViewsCount = schemasMap.size();
        int graphViewsCount = 0;

        Map<String, List<String>> kvMap = discoverUnitsAndItems("KEYVALUE", targetDb);
        int kvCount = kvMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> vecMap = discoverUnitsAndItems("VECTOR", targetDb);
        int vecCount = vecMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> colMap = discoverUnitsAndItems("COLUMN", targetDb);
        int colCount = colMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> geoMap = discoverUnitsAndItems("GEOSPATIAL", targetDb);
        int geoCount = geoMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> objMap = discoverUnitsAndItems("OBJECT", targetDb);
        int objCount = objMap.values().stream().mapToInt(List::size).sum();

        Map<String, List<String>> recMap = discoverUnitsAndItems("RECORDS", targetDb);
        int recCount = recMap.values().stream().mapToInt(List::size).sum();

        // TYPES Sidebar (matching the screenshot)
        Widget typesSidebar = Div.of(
            Div.of(
                Span.of("TYPES").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-secondary); letter-spacing:0.8px; text-transform:uppercase;")),
                Button.of(Icon.of("fas fa-sync-alt")).modifier(new Modifier().attribute("type", "button").attribute("title", "Refresh Types").attribute("onclick", "location.reload()").style("background:none; border:none; color:var(--j-text-muted); cursor:pointer; font-size:11px; padding:2px;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:12px 14px; border-bottom:1px solid var(--j-border);")),

            Div.of(
                createTypeRow("DOCUMENT", "fas fa-file-alt", docCount, "#38bdf8", "DOCUMENT", targetDb, actionUrl, "DOCUMENT".equalsIgnoreCase(selectedEngine)),
                createTypeRow("KEY-VALUE", "fas fa-key", kvCount, "#10b981", "KEYVALUE", targetDb, actionUrl, "KEYVALUE".equalsIgnoreCase(selectedEngine)),
                createTypeRow("VECTOR", "fas fa-brain", vecCount, "#8b5cf6", "VECTOR", targetDb, actionUrl, "VECTOR".equalsIgnoreCase(selectedEngine)),
                createTypeRow("GRAPH", "fas fa-share-alt", verticesCount + edgesCount, "#ec4899", "GRAPH", targetDb, actionUrl, "GRAPH".equalsIgnoreCase(selectedEngine)),
                createTypeRow("TIMESERIES", "fas fa-chart-line", tsCount, "#06b6d4", "TIMESERIES", targetDb, actionUrl, "TIMESERIES".equalsIgnoreCase(selectedEngine)),
                createTypeRow("COLUMN", "fas fa-table-columns", colCount, "#f97316", "COLUMN", targetDb, actionUrl, "COLUMN".equalsIgnoreCase(selectedEngine)),
                createTypeRow("GEOSPATIAL", "fas fa-globe-americas", geoCount, "#14b8a6", "GEOSPATIAL", targetDb, actionUrl, "GEOSPATIAL".equalsIgnoreCase(selectedEngine)),
                createTypeRow("OBJECT", "fas fa-box-archive", objCount, "#a855f7", "OBJECT", targetDb, actionUrl, "OBJECT".equalsIgnoreCase(selectedEngine)),
                createTypeRow("RECORDS", "fas fa-id-card", recCount, "#f43f5e", "RECORDS", targetDb, actionUrl, "RECORDS".equalsIgnoreCase(selectedEngine))
            ).modifier(new Modifier().style("padding:6px 0; display:flex; flex-direction:column; overflow-y:auto; flex:1;"))
        ).modifier(new Modifier().style("width:240px; min-width:240px; background:var(--j-bg-surface); border-right:1px solid var(--j-border); display:flex; flex-direction:column; height:100%;"));

        // Main Right Canvas
        Widget canvasContent;
        if ("dashboard".equalsIgnoreCase(params != null ? params.get("view_mode") : "") || ("schema".equals(currentTab) && !hasExplicitSelection)) {
            canvasContent = createMainDashboardView(selectedEngine, targetDb, actionUrl, docCount, kvCount, vecCount, verticesCount + edgesCount, tsCount, colCount, geoCount, objCount, recCount);
        } else {
            canvasContent = createHierarchyTreeCard(selectedEngine, targetDb, currentColl, params);
        }

        Widget rightCanvas = Div.of(
            alertWidget,
            Div.of(canvasContent).modifier(new Modifier().style("flex:1; overflow-y:auto; padding:16px 20px;"))
        ).modifier(new Modifier().style("flex:1; display:flex; flex-direction:column; height:100%; overflow:hidden; background:var(--j-bg-body);"));

        return Div.of(
            typesSidebar,
            rightCanvas
        ).modifier(new Modifier().style("display:flex; width:100%; height:100%; overflow:hidden;"));
    }

    private Widget createTypeRow(String label, String icon, int count, String color, String engineName, String targetDb, String actionUrl, boolean isActive) {
        return Div.of(
            Link.of(actionUrl + engineName + "&target_db=" + targetDb + "&view_mode=table",
                Icon.of(icon).modifier(new Modifier().style("margin-right:8px; font-size:11px; color:" + color + "; width:14px; text-align:center;")),
                Span.of(label + "(" + count + ")").modifier(new Modifier().style("font-size:11.5px; font-weight:" + (isActive ? "700" : "500") + "; color:" + (isActive ? "var(--j-primary)" : "var(--j-text-secondary)") + "; text-transform:uppercase; letter-spacing:0.3px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; text-decoration:none; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;")),
            Button.of(Icon.of("fas fa-plus"))
                .modifier(new Modifier().attribute("type", "button").attribute("title", "Add " + label).attribute("onclick", "openAddObjectModal('" + engineName + "', 'default', '" + targetDb + "')").style("background:none; border:none; color:var(--j-text-muted); cursor:pointer; font-size:11px; padding:2px 6px; opacity:0.7;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:7px 14px; transition:background 0.15s; background:" + (isActive ? "var(--j-primary-light)" : "transparent") + "; border-left:" + (isActive ? "3px solid var(--j-primary)" : "3px solid transparent") + ";"));
    }

    private Widget createMainDashboardView(String selectedEngine, String targetDb, String actionUrl,
                                           int docCount, int kvCount, int vecCount, int graphCount,
                                           int tsCount, int colCount, int geoCount, int objCount, int recCount) {
        return StorageDashboardView.build(selectedEngine, targetDb, actionUrl, docCount, kvCount, vecCount, graphCount, tsCount, colCount, geoCount, objCount, recCount);
    }

    private Widget createMiniEngineLegend(String label, int count, String color) {
        return Div.of(
            Span.of("").modifier(new Modifier().style("width:8px; height:8px; border-radius:50%; background:" + color + "; margin-right:5px; display:inline-block;")),
            Span.of(label + ": ").modifier(new Modifier().style("font-size:11px; color:var(--j-text-secondary); font-weight:500;")),
            Span.of(String.valueOf(count)).modifier(new Modifier().style("font-size:11px; color:var(--j-text-primary); font-weight:700; font-family:monospace;"))
        ).modifier(new Modifier().style("display:inline-flex; align-items:center; background:var(--j-bg-subsurface); padding:3px 8px; border-radius:4px; border:1px solid var(--j-border);"));
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

    public record InsertResult(String database, String engineName, String targetColl, String targetId) {}

    private InsertResult executeTypeSpecificInsert(String engineName, String db, Map<String, String> params) {
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
        return new InsertResult(db, engineName, targetColl, targetId);
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
                    Icon.of(isTableView ? "fas fa-table" : "fas fa-sitemap").modifier(new Modifier().style("color:var(--j-primary); margin-right:6px; font-size:13px;")),
                    Text.of("Multi-Model Storage Hierarchy Explorer")
                ).modifier(new Modifier().style("margin:0; font-size:13px; font-weight:600; color:var(--j-text-primary);")),
                Row.of(
                    Button.of(Icon.of("fas fa-sitemap"), Text.of(" Tree View"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "location.href='" + actionUrl + selectedEngine + "&target_db=" + escapeJs(targetDb) + "&coll=" + escapeJs(currentColl) + "&view_mode=tree'").cssClass(!isTableView ? "btn-action btn-primary" : "btn-action btn-secondary").style("padding:3px 8px; font-size:9.5px; margin-left:12px; margin-right:4px;")),
                    Button.of(Icon.of("fas fa-table"), Text.of(" Table View"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "location.href='" + actionUrl + selectedEngine + "&target_db=" + escapeJs(targetDb) + "&coll=" + escapeJs(currentColl) + "&view_mode=table'").cssClass(isTableView ? "btn-action btn-primary" : "btn-action btn-secondary").style("padding:3px 8px; font-size:9.5px; margin-right:4px;")),
                    Button.of(Icon.of("fas fa-expand-alt"), Text.of(" Expand All"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "expandAllTreeNodes()").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:3px; background:var(--j-primary-light); border-color:var(--j-primary); color:var(--j-primary);")),
                    Button.of(Icon.of("fas fa-compress-alt"), Text.of(" Collapse All"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "collapseAllTreeNodes()").cssClass("btn-action btn-secondary").style("padding:3px 6px; font-size:9px; margin-right:4px; background:var(--j-bg-subsurface); border-color:var(--j-border); color:var(--j-text-muted);"))
                ).modifier(new Modifier().style("display:flex; align-items:center;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;"))
        ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:6px;"));

        if (isTableView) {
            List<StorageTableView.FlatRecordItem> flatItems = new ArrayList<>();
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
                        flatItems.add(new StorageTableView.FlatRecordItem(engName, engColor, engIcon, targetDb, uName, itemId, vCount, itemPayload, payloadB64, versionsB64));
                    }
                }
            }
            Widget tableBody = StorageTableView.build(selectedEngine, targetDb, currentColl, actionUrl, flatItems, params, jsonParser);
            return Div.of(treeHeader, tableBody)
                .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; border: 1px solid var(--j-border); background:var(--j-bg-surface); color:var(--j-text-primary); padding:16px; border-radius:8px; box-shadow:0 1px 3px rgba(0,0,0,0.05);"));
        }

        Widget treeBody = StorageTreeView.build(selectedEngine, targetDb, currentColl, actionUrl, params, hierarchyService);
        return Div.of(treeHeader, treeBody)
            .modifier(new Modifier().cssClass("store-card").style("margin-bottom:20px; border: 1px solid var(--j-border); background:var(--j-bg-surface); color:var(--j-text-primary); padding:16px; border-radius:8px; box-shadow:0 1px 3px rgba(0,0,0,0.05);"));
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
        modals.add(StorageModalCommands.buildUniversalEditModal(actionUrl));
        modals.add(HistoricalVersionsDialog.build(actionUrl));
        modals.add(buildConfirmDeleteModal(actionUrl));
        modals.add(buildAdvancedSearchModal(actionUrl, targetDb, currentColl));
        modals.add(buildAdvancedSearchHelpModal());
        modals.add(buildReferenceWarningModal());
        modals.add(buildCreateIndexModal(actionUrl));
        modals.add(buildCreateSchemaModal(actionUrl));
        modals.add(buildSampleDatabasesModal(actionUrl));
        modals.add(buildDatabaseSwitchModal(actionUrl, targetDb));
        modals.add(buildModalsScript());

        return Div.of(modals.toArray(new Widget[0]));
    }

    private Widget buildDatabaseSwitchModal(String actionUrl, String currentTargetDb) {
        Set<String> allDbs = discoverAllDatabases();
        if (!allDbs.contains(currentTargetDb)) {
            allDbs.add(currentTargetDb);
        }

        List<Widget> dbRowWidgets = new ArrayList<>();
        for (String dbName : allDbs) {
            boolean isActive = dbName.equalsIgnoreCase(currentTargetDb);
            String switchUrl = JettraServer.resolvePath("/engines?target_db=" + dbName + "&tab=schema");

            dbRowWidgets.add(
                Div.of(
                    Row.of(
                        Icon.of("fas fa-database").modifier(new Modifier().style("color:" + (isActive ? "#38bdf8" : "#94a3b8") + "; font-size:16px; margin-right:12px;")),
                        Column.of(
                            Span.of(dbName).modifier(new Modifier().style("font-size:14px; font-weight:" + (isActive ? "700" : "600") + "; color:" + (isActive ? "#38bdf8" : "#f8fafc") + ";")),
                            Span.of(isActive ? "Active Database • 9 Multi-Model Engines Provisioned" : "9 Engines Available • Ready to Explore")
                                .modifier(new Modifier().style("font-size:11px; color:#94a3b8; margin-top:2px;"))
                        )
                    ).modifier(new Modifier().style("align-items:center;")),
                    isActive
                        ? Span.of(Span.of("").modifier(new Modifier().style("width:6px; height:6px; border-radius:50%; background:#22c55e; display:inline-block; margin-right:4px;")), Text.of("CURRENT"))
                            .modifier(new Modifier().cssClass("store-badge badge-active"))
                        : Button.of(Icon.of("fas fa-arrow-right"), Text.of(" Switch"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "location.href='" + switchUrl + "'").cssClass("btn-studio-primary").style("padding:4px 10px; font-size:11px;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:12px 14px; border-radius:8px; margin-bottom:8px; background:" + (isActive ? "rgba(56,189,248,0.12)" : "rgba(255,255,255,0.03)") + "; border:" + (isActive ? "1px solid rgba(56,189,248,0.4)" : "1px solid rgba(255,255,255,0.06)") + ";"))
            );
        }

        Widget header = createModalHeader("Switch Active Database", "fas fa-database", "#38bdf8", "switchDbModal");

        Widget quickFooter = Row.of(
            Button.of(Icon.of("fas fa-plus"), Text.of(" Create New DB"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('switchDbModal'); showModal('createDbModal');").cssClass("btn-studio-secondary")),
            Button.of(Icon.of("fas fa-cubes"), Text.of(" Sample Catalog"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('switchDbModal'); openSampleDatabasesModal();").cssClass("btn-studio-secondary").style("color:#ec4899;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:8px; margin-top:16px; border-top:1px solid rgba(255,255,255,0.08); padding-top:14px;"));

        Widget body = Column.of(
            Div.of(dbRowWidgets.toArray(new Widget[0])).modifier(new Modifier().style("max-height:360px; overflow-y:auto; padding-right:4px;")),
            quickFooter
        );

        return createModalOverlay("switchDbModal", "560px", "rgba(56,189,248,0.4)", header, body);
    }

    private Widget createLabel(String text) {
        return Label.of(text).modifier(new Modifier().style("display:block; font-size:12px; font-weight:600; color:#cbd5e1; margin-bottom:4px;"));
    }

    private Widget createLabel(String text, String id) {
        return Label.of(text).id(id).modifier(new Modifier().style("display:block; font-size:12px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px;"));
    }

    private TextField createTextInput(String name, String placeholder, String value, String color) {
        TextField tf = TextField.of(name, placeholder != null ? placeholder : "");
        if (value != null && !value.isEmpty()) tf.value(value);
        String textColor = (color != null && !color.isEmpty()) ? color : "var(--j-text-primary)";
        tf.modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-subsurface); border:1px solid var(--j-border); border-radius:6px; color:" + textColor + "; font-size:13px; box-sizing:border-box;"));
        return tf;
    }

    private TextArea createTextArea(String name, int rows, String placeholder, String value) {
        TextArea ta = TextArea.create().name(name).rows(rows);
        if (placeholder != null && !placeholder.isEmpty()) ta.placeholder(placeholder);
        if (value != null && !value.isEmpty()) ta.value(value);
        ta.modifier(new Modifier().style("width:100%; padding:10px 12px; background:var(--j-bg-subsurface); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px; font-family:monospace; box-sizing:border-box;"));
        return ta;
    }

    private Widget createSelectOne(String name, String id, String color, String onChange, Map<String, String> options, String selectedValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<select name='").append(name).append("' ");
        if (id != null && !id.isEmpty()) sb.append("id='").append(id).append("' ");
        if (onChange != null && !onChange.isEmpty()) sb.append("onchange='").append(onChange).append("' ");
        String textColor = (color != null && !color.isEmpty()) ? color : "var(--j-text-primary)";
        sb.append("style='width:100%; padding:8px 12px; background:var(--j-bg-subsurface); border:1px solid var(--j-border); border-radius:6px; color:").append(textColor).append("; font-size:13px; box-sizing:border-box;'>\n");
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
                .style("width:" + width + "; max-width:94%; max-height:90vh; overflow-y:auto; background:var(--j-bg-surface); border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.5); padding:24px; border-radius:12px; position:relative; z-index:100000;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.7); backdrop-filter:blur(6px); z-index:99999; align-items:center; justify-content:center;"));
    }

    private Widget createConfirmationModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(header, content).modifier(new Modifier().cssClass("store-card")
                .style("width:" + width + "; max-width:94%; max-height:90vh; overflow-y:auto; background:var(--j-bg-surface); border:1px solid " + borderColor + "; box-shadow:0 20px 50px rgba(0,0,0,0.5); padding:24px; border-radius:12px; position:relative; z-index:100001;"))
        ).id(modalId).modifier(new Modifier().style("display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.7); backdrop-filter:blur(6px); z-index:100000; align-items:center; justify-content:center;"));
    }

    private Widget createModalHeader(String title, String iconClass, String iconColor, String modalId) {
        return Div.of(
            Header.of(3,
                Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; margin-right:8px;")),
                Text.of(" " + title)
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:var(--j-text-primary);")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "hideModal('" + modalId + "')")
                .modifier(new Modifier().style("background:none; border:none; color:var(--j-text-muted); font-size:18px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));
    }

    private Widget createModalHeaderWithSpan(String prefixTitle, String spanId, String spanColor, String iconClass, String iconColor, String modalId) {
        return Div.of(
            Header.of(3,
                Icon.of(iconClass).modifier(new Modifier().style("color:" + iconColor + "; margin-right:8px;")),
                Text.of(" " + prefixTitle + " "),
                Span.of("").id(spanId).modifier(new Modifier().style("color:" + spanColor + ";"))
            ).modifier(new Modifier().style("margin:0; font-size:18px; font-weight:700; color:var(--j-text-primary);")),
            Button.of(Icon.of("fas fa-times"))
                .attribute("type", "button")
                .attribute("onclick", "hideModal('" + modalId + "')")
                .modifier(new Modifier().style("background:none; border:none; color:var(--j-text-muted); font-size:18px; cursor:pointer;"))
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
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "handleModalFormSubmit(event, '" + modalId + "')").cssClass("btn-action btn-primary").style(submitColor.isEmpty() ? "" : "background:" + submitColor + ";"))
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
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:11px; margin-top:2px;")),
                Span.of("Configuración automática: Al crear la base de datos se configuran de manera predeterminada todos los motores multi-modelo (Document, KeyValue, Vector, Graph, TimeSeries, Column, Geospatial, Object, Records) dentro de su Tree para procesar elementos de inmediato.")
                    .modifier(new Modifier().style("color:#94a3b8; font-size:9.5px; line-height:1.35;"))
            ).modifier(new Modifier().style("display:flex; align-items:flex-start; background:rgba(56,189,248,0.08); border:1px solid rgba(56,189,248,0.2); border-radius:4px; padding:6px 8px; margin-bottom:16px;")),
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
                Span.of("Cargar Objetos Referenciados (Auto-Resolve Jref)").modifier(new Modifier().style("color:#38bdf8; font-weight:600; font-size:11.5px;"))
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
            Span.of("Objetos Referenciados Detectados (Jref Operator):").modifier(new Modifier().style("color:#cbd5e1; font-size:11.5px; font-weight:700;"))
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

    private Widget buildSampleDatabasesModal(String actionUrl) {
        Widget header = createModalHeader("Sample Databases & Datasets Catalog", "fas fa-cubes", "#ec4899", "sampleDatabasesModal");

        Widget subtitle = Paragraph.of("Explore, install, and uninstall on-demand sample datasets across all 9 Multi-Model Storage Engines with atomic lifecycle operations.")
            .modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin:0 0 16px 0; line-height:1.4;"));

        Widget loadingIndicator = Div.of(
            Icon.of("fas fa-spinner fa-spin").modifier(new Modifier().style("font-size:24px; color:#ec4899; margin-bottom:8px;")),
            Paragraph.of("Loading sample database catalog...").modifier(new Modifier().style("font-size:12px; color:#cbd5e1; margin:0;"))
        ).id("sampleDbsLoadingContainer").modifier(new Modifier().style("display:flex; flex-direction:column; align-items:center; justify-content:center; padding:30px;"));

        Widget gridContainer = Div.of()
            .id("sampleDbsCatalogContainer")
            .modifier(new Modifier().style("display:none; flex-direction:column; gap:12px; max-height:480px; overflow-y:auto; padding-right:4px;"));

        Widget actions = Div.of(
            Button.of(Icon.of("fas fa-sync-alt"), Text.of(" Refresh Catalog"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "refreshSampleDatabasesList()").cssClass("btn-action btn-secondary").style("padding:6px 14px; font-size:12px; margin-right:8px; background:rgba(56,189,248,0.1); border-color:rgba(56,189,248,0.3); color:#38bdf8;")),
            Button.of(Icon.of("fas fa-times"), Text.of(" Close"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('sampleDatabasesModal')").cssClass("btn-action btn-secondary").style("padding:6px 14px; font-size:12px; background:rgba(148,163,184,0.15); color:#cbd5e1;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:16px; border-top:1px solid rgba(255,255,255,0.08); padding-top:12px;"));

        Widget body = Div.of(subtitle, loadingIndicator, gridContainer, actions);

        return createModalOverlay("sampleDatabasesModal", "780px", "rgba(236,72,153,0.4)", header, body);
    }

    private Widget buildModalsScript() {
        String js1 = """
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

  function openDatabaseSwitchModal() {
    showModal('switchDbModal');
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
    var pretty = parsed ? JSON.stringify(parsed, null, 2) : (payload || '{}');
    var p = parsed || {};

    var normEngine = (engine || 'DOCUMENT').toUpperCase();
    if (normEngine === 'RECORD') normEngine = 'RECORDS';
    if (normEngine === 'KEY_VALUE' || normEngine === 'KEY-VALUE') normEngine = 'KEYVALUE';
    if (normEngine === 'TIME_SERIES' || normEngine === 'TIMESERIE') normEngine = 'TIMESERIES';
    if (normEngine === 'GEO') normEngine = 'GEOSPATIAL';

    var vecCoords = '0.12, 0.45, 0.88, 0.31';
    if (Array.isArray(p.coordinates)) vecCoords = p.coordinates.join(', ');
    else if (Array.isArray(p.embedding)) vecCoords = p.embedding.join(', ');
    else if (Array.isArray(p.vector)) vecCoords = p.vector.join(', ');
    else if (p.coordinates || p.embedding || p.vector) vecCoords = String(p.coordinates || p.embedding || p.vector);

    var cfgMap = {
      DOCUMENT:   { id: 'editDocumentModal',   vals: { editDocDbInput: db, editDocDbDisplay: db, editDocCollInput: unit || 'default', editDocIdInput: id, editDocIdDisplay: id, editDocClassInput: p._class || '', editDocPayloadInput: pretty } },
      KEYVALUE:   { id: 'editKeyValueModal',   vals: { editKvDbInput: db, editKvDbDisplay: db, editKvCollInput: unit || 'default', editKvIdInput: id, editKvIdDisplay: id, editKvValueInput: payload || pretty } },
      VECTOR:     { id: 'editVectorModal',     vals: { editVecDbInput: db, editVecDbDisplay: db, editVecCollInput: unit || 'default', editVecIdInput: id, editVecIdDisplay: id, editVecCoordsInput: vecCoords, editVecMetaInput: pretty } },
      GRAPH:      { id: 'editGraphModal',      vals: { editGraphDbInput: db, editGraphDbDisplay: db, editGraphCollInput: p.label || unit || 'Vertex', editGraphIdInput: id, editGraphIdDisplay: id, editGraphPropsInput: pretty } },
      TIMESERIES: { id: 'editTimeSeriesModal', vals: { editTsDbInput: db, editTsDbDisplay: db, editTsCollInput: p.metric || unit || 'telemetry', editTsIdInput: id, editTsIdDisplay: id, editTsTimestampInput: p.timestamp || id, editTsValueInput: p.value !== undefined ? p.value : '25.4', editTsUnitInput: p.unit || 'celsius', editTsTagsInput: pretty } },
      COLUMN:     { id: 'editColumnModal',     vals: { editColDbInput: db, editColDbDisplay: db, editColCollInput: p._family || unit || 'analytics', editColIdInput: id, editColIdDisplay: id, editColDataInput: pretty } },
      GEOSPATIAL: { id: 'editGeoModal',        vals: { editGeoDbInput: db, editGeoDbDisplay: db, editGeoCollInput: p._layer || unit || 'stores_layer', editGeoIdInput: id, editGeoIdDisplay: id, editGeoLatInput: p.lat !== undefined ? p.lat : (p.latitude !== undefined ? p.latitude : '8.9824'), editGeoLonInput: p.lon !== undefined ? p.lon : (p.longitude !== undefined ? p.longitude : '-79.5199'), editGeoNameInput: p.name || id } },
      OBJECT:     { id: 'editObjectModal',     vals: { editObjDbInput: db, editObjDbDisplay: db, editObjCollInput: p.bucket || unit || 'media_bucket', editObjIdInput: id, editObjIdDisplay: id, editObjMimeInput: p.mimeType || 'application/json', editObjPayloadInput: p.content || payload || pretty } },
      RECORDS:    { id: 'editRecordsModal',    vals: { editRecDbInput: db, editRecDbDisplay: db, editRecCollInput: p._table || unit || 'default', editRecIdInput: id, editRecIdDisplay: id, editRecClassInput: p._class || 'com.jettra.model.PersonRecord', editRecPayloadInput: pretty } }
    };
    var cfg = cfgMap[normEngine] || cfgMap.DOCUMENT;

    if (cfg) {
      setElementValues(cfg.vals);
      showModal(cfg.id);
    }
  }

  function openUniversalRestoreModal(engine, db, unit, id, versionsJsonB64) {
    if (typeof window.openUniversalRestoreModal === 'function') {
      window.openUniversalRestoreModal(engine, db, unit, id, versionsJsonB64);
    }
  }

  function openConfirmRestoreModal(ts, formattedDate, engine, db, coll, id, vNum) {
    if (typeof window.openConfirmRestoreModal === 'function') {
      window.openConfirmRestoreModal(ts, formattedDate, engine, db, coll, id, vNum);
    }
  }

  function openUniversalDeleteModal(engine, db, unit, id) {
    var normEngine = (engine || 'DOCUMENT').toUpperCase();
    if (normEngine === 'RECORD') normEngine = 'RECORDS';
    if (normEngine === 'KEY_VALUE' || normEngine === 'KEY-VALUE') normEngine = 'KEYVALUE';
    if (normEngine === 'TIME_SERIES' || normEngine === 'TIMESERIE') normEngine = 'TIMESERIES';
    if (normEngine === 'GEO') normEngine = 'GEOSPATIAL';

    setElementValues({
      confirmDeleteEngineInput: normEngine,
      confirmDeleteEngineDisplay: normEngine,
      confirmDeleteDbInput: db,
      confirmDeleteDbDisplay: db,
      confirmDeleteCollInput: unit || 'default',
      confirmDeleteCollDisplay: unit || 'default',
      confirmDeleteIdInput: id,
      confirmDeleteIdDisplay: id
    });
    showModal('confirmDeleteModal');
  }
""";

        String js2 = """
  var dbHierarchyCache = {};

  // Reactive Tree State Manager using sessionStorage
  var TreeStateManager = function() {
    this.storageKey = 'jettra_tree_explorer_state';
    this.defaultState = {
      expandedNodeIds: [],
      selectedNodeId: null,
      targetDatabase: null,
      focusedUnitId: null,
      scrollTop: 0
    };
  };

  TreeStateManager.prototype.getState = function() {
    try {
      var raw = sessionStorage.getItem(this.storageKey);
      if (raw) {
        var parsed = JSON.parse(raw);
        if (!parsed.expandedNodeIds) parsed.expandedNodeIds = [];
        return parsed;
      }
    } catch(e) {
      console.warn('Failed to parse tree state from sessionStorage:', e);
    }
    return JSON.parse(JSON.stringify(this.defaultState));
  };

  TreeStateManager.prototype.saveState = function(state) {
    try {
      sessionStorage.setItem(this.storageKey, JSON.stringify(state));
    } catch(e) {
      console.warn('Failed to save tree state to sessionStorage:', e);
    }
  };

  TreeStateManager.prototype.expandNode = function(nodeId) {
    if (!nodeId) return;
    var state = this.getState();
    if (state.expandedNodeIds.indexOf(nodeId) === -1) {
      state.expandedNodeIds.push(nodeId);
      this.saveState(state);
    }
  };

  TreeStateManager.prototype.collapseNode = function(nodeId) {
    if (!nodeId) return;
    var state = this.getState();
    var idx = state.expandedNodeIds.indexOf(nodeId);
    if (idx !== -1) {
      state.expandedNodeIds.splice(idx, 1);
      this.saveState(state);
    }
  };

  TreeStateManager.prototype.isNodeExpanded = function(nodeId) {
    if (!nodeId) return false;
    var state = this.getState();
    return state.expandedNodeIds.indexOf(nodeId) !== -1;
  };

  TreeStateManager.prototype.setTargetContext = function(db, engine, unit, itemId) {
    var state = this.getState();
    state.targetDatabase = db;
    state.focusedUnitId = unit;
    state.selectedNodeId = itemId;
    this.saveState(state);
  };

  TreeStateManager.prototype.restoreState = function() {
    var state = this.getState();
    var treeBody = document.getElementById('treeHierarchyContainer');
    if (treeBody && state.scrollTop > 0) {
      treeBody.scrollTop = state.scrollTop;
    }
    if (state.expandedNodeIds && state.expandedNodeIds.length > 0) {
      var dbContainers = document.querySelectorAll('.db-subtree-container');
      for (var i = 0; i < dbContainers.length; i++) {
        var dc = dbContainers[i];
        if (state.expandedNodeIds.indexOf(dc.id) !== -1) {
          var db = dc.getAttribute('data-db');
          var dbIdx = dc.getAttribute('data-db-idx') || (i + 1);
          var actionUrl = window.lastActionUrl || '/engines?engine=';
          var selectedEngine = window.lastSelectedEngine || 'DOCUMENT';
          dc.style.display = 'block';
          dc.setAttribute('aria-expanded', 'true');
          var icon = document.getElementById('icon_' + dc.id);
          var header = document.getElementById('db_header_' + dbIdx);
          var btn = document.getElementById('btn_toggle_' + dc.id) || document.getElementById('btn_toggle_' + dbIdx);
          if (header) header.setAttribute('aria-expanded', 'true');
          if (btn) btn.setAttribute('aria-expanded', 'true');
          if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';
          loadDbHierarchy(null, dc.id, db, selectedEngine, actionUrl, dbIdx, false);
        }
      }
    }
  };

  var treeStateManager = new TreeStateManager();

  function showTreeToast(message, type) {
    var toastId = 'tree_toast_' + Date.now();
    var bg = type === 'error' ? 'rgba(239, 68, 68, 0.95)' : 'rgba(16, 185, 129, 0.95)';
    var icon = type === 'error' ? 'fas fa-exclamation-triangle' : 'fas fa-check-circle';
    var toastContainer = document.getElementById('treeExplorerToastContainer');
    if (!toastContainer) {
      toastContainer = document.createElement('div');
      toastContainer.id = 'treeExplorerToastContainer';
      toastContainer.style.cssText = 'position:fixed; bottom:24px; right:24px; z-index:999999; display:flex; flex-direction:column; gap:8px; pointer-events:none;';
      document.body.appendChild(toastContainer);
    }
    var toast = document.createElement('div');
    toast.id = toastId;
    toast.style.cssText = 'background:' + bg + '; color:#fff; padding:10px 16px; border-radius:8px; font-size:12px; font-weight:600; box-shadow:0 10px 25px rgba(0,0,0,0.5); display:flex; align-items:center; gap:8px; pointer-events:auto; transition:all 0.3s ease; opacity:0; transform:translateY(10px);';
    toast.innerHTML = '<i class="' + icon + '" style="font-size:14px;"></i> <span>' + escapeHtml(message) + '</span>';
    toastContainer.appendChild(toast);
    setTimeout(function() {
      toast.style.opacity = '1';
      toast.style.transform = 'translateY(0)';
    }, 20);
    setTimeout(function() {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(10px)';
      setTimeout(function() {
        if (toast.parentElement) toast.parentElement.removeChild(toast);
      }, 300);
    }, 3500);
  }

  function highlightAndFocusTreeItem(itemId, unitContainerId) {
    if (!itemId) return;
    if (unitContainerId) {
      var unitEl = document.getElementById(unitContainerId);
      if (unitEl) {
        unitEl.style.display = 'block';
        unitEl.setAttribute('aria-expanded', 'true');
        var uIcon = document.getElementById('icon_' + unitContainerId);
        if (uIcon) uIcon.className = 'fas fa-chevron-down tree-toggle-icon';
        treeStateManager.expandNode(unitContainerId);
      }
    }

    var candidateRows = document.querySelectorAll('.tree-collapsible-content div[class*="item-row-"]');
    var targetRow = null;
    for (var r = 0; r < candidateRows.length; r++) {
      var rowText = candidateRows[r].innerText || '';
      if (rowText.indexOf(itemId) !== -1) {
        targetRow = candidateRows[r];
        break;
      }
    }

    if (targetRow) {
      var parentUnit = targetRow.closest('.tree-collapsible-content');
      if (parentUnit && parentUnit.id && parentUnit.id.startsWith('unit_subtree_')) {
        var unitId = parentUnit.id;
        var itemPage = parseInt(targetRow.getAttribute('data-page') || '1');
        if (typeof changeSubtreePage === 'function' && itemPage > 1) {
          var totalPages = 10;
          changeSubtreePage(unitId, itemPage - 1, totalPages);
        }
        parentUnit.style.display = 'block';
        parentUnit.setAttribute('aria-expanded', 'true');
        var pIcon = document.getElementById('icon_' + unitId);
        if (pIcon) pIcon.className = 'fas fa-chevron-down tree-toggle-icon';
        treeStateManager.expandNode(unitId);

        var engParent = parentUnit.parentElement ? parentUnit.parentElement.closest('.tree-collapsible-content') : null;
        if (engParent && engParent.id && engParent.id.startsWith('eng_subtree_')) {
          engParent.style.display = 'block';
          engParent.setAttribute('aria-expanded', 'true');
          var engIcon = document.getElementById('icon_' + engParent.id);
          if (engIcon) engIcon.className = 'fas fa-chevron-down tree-toggle-icon';
          treeStateManager.expandNode(engParent.id);
        }
      }

      targetRow.classList.add('pulse-highlight-node');
      setTimeout(function() {
        try {
          targetRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } catch(e) {
          targetRow.scrollIntoView();
        }
      }, 50);

      setTimeout(function() {
        targetRow.classList.remove('pulse-highlight-node');
      }, 4000);
    }
  }

  function handleModalFormSubmit(event, modalId) {
    if (event) {
      if (typeof event.preventDefault === 'function') event.preventDefault();
      if (typeof event.stopPropagation === 'function') event.stopPropagation();
    }

    var modal = document.getElementById(modalId);
    if (!modal) return false;
    var form = modal.querySelector('form');
    if (!form) {
      if (event && event.target) {
        form = event.target.closest('form');
      }
    }
    if (!form) return false;

    var submitBtn = modal.querySelector('button[type="submit"], button.btn-primary');
    var origBtnHtml = submitBtn ? submitBtn.innerHTML : '';
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.innerHTML = '<i class="fas fa-circle-notch fa-spin"></i> Saving...';
    }

    var treeBodyContainer = document.getElementById('treeHierarchyContainer');
    var savedScrollTop = treeBodyContainer ? treeBodyContainer.scrollTop : 0;
    var state = treeStateManager.getState();
    state.scrollTop = savedScrollTop;
    treeStateManager.saveState(state);

    var formData = new FormData(form);
    var params = new URLSearchParams();
    formData.forEach(function(val, key) {
      params.append(key, val);
    });
    if (!params.has('is_ajax')) {
      params.append('is_ajax', 'true');
    }

    var targetDb = params.get('target_db') || params.get('db') || 'customers_db';
    var targetEngine = params.get('engine_type') || params.get('engine') || 'DOCUMENT';
    var targetUnit = params.get('target_coll') || params.get('coll') || params.get('unit_name') || 'default';
    var targetId = params.get('target_id') || params.get('id') || params.get('custom_id') || '';

    var actionUrl = form.getAttribute('action') || window.lastActionUrl || '/engines';

    fetch(actionUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: params.toString()
    })
    .then(function(res) {
      if (!res.ok) throw new Error('HTTP ' + res.status + ' - ' + res.statusText);
      return res.json();
    })
    .then(function(data) {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.innerHTML = origBtnHtml;
      }

      if (data.status === 'ERROR') {
        showTreeToast(data.message || 'Failed to save record', 'error');
        return;
      }

      // Hide modal and reset form
      hideModal(modalId);
      form.reset();

      var successMsg = data.message || ('Successfully saved record to ' + (data.database || targetDb));
      showTreeToast(successMsg, 'success');

      var finalDb = data.database || targetDb;
      var finalEngine = data.engine || targetEngine;
      var finalUnit = data.collection || targetUnit;
      var finalId = data.itemId || targetId;

      treeStateManager.setTargetContext(finalDb, finalEngine, finalUnit, finalId);

      var dbContainers = document.querySelectorAll('.db-subtree-container');
      var matchedContainer = null;
      var matchedDbIdx = 1;
      for (var i = 0; i < dbContainers.length; i++) {
        var dc = dbContainers[i];
        if ((dc.getAttribute('data-db') || '').toLowerCase() === finalDb.toLowerCase()) {
          matchedContainer = dc;
          matchedDbIdx = dc.getAttribute('data-db-idx') || (i + 1);
          break;
        }
      }

      if (!matchedContainer) {
        var treeContainer = document.getElementById('treeHierarchyContainer');
        if (treeContainer) {
          var newDbIdx = document.querySelectorAll('.db-subtree-container').length + 1;
          var newContainerId = 'db_content_' + newDbIdx;
          var newHeaderId = 'db_header_' + newDbIdx;
          var newToggleBtnId = 'btn_toggle_' + newDbIdx;

          var dbCardDiv = document.createElement('div');
          dbCardDiv.style.cssText = 'margin-bottom:6px; padding:4px 8px; border-radius:6px; background:rgba(56,189,248,0.06); border:1px solid rgba(56,189,248,0.2);';
          
          dbCardDiv.innerHTML = '<div style="display:flex; justify-content:space-between; align-items:center; padding:3px 4px;">' +
            '<div id="' + newHeaderId + '" data-db="' + escapeHtml(finalDb) + '" data-state="collapsed" role="treeitem" tabindex="0" aria-expanded="false" aria-controls="' + newContainerId + '" style="display:inline-flex; align-items:center; cursor:pointer; outline:none; user-select:none;" onclick="toggleLazyDbSubtree(event, \'' + newContainerId + '\', \'' + escapeJsString(finalDb) + '\', \'' + escapeJsString(finalEngine) + '\', \'' + escapeJsString(actionUrl) + '\', ' + newDbIdx + ')">' +
              '<button type="button" id="' + newToggleBtnId + '" aria-label="Toggle ' + escapeHtml(finalDb) + ' database subtree" aria-controls="' + newContainerId + '" aria-expanded="false" data-db="' + escapeHtml(finalDb) + '" data-container-id="' + newContainerId + '" onclick="toggleLazyDbSubtree(event, \'' + newContainerId + '\', \'' + escapeJsString(finalDb) + '\', \'' + escapeJsString(finalEngine) + '\', \'' + escapeJsString(actionUrl) + '\', ' + newDbIdx + ')" style="background:none; border:none; padding:2px 5px; margin-right:3px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;">' +
                '<i id="icon_' + newContainerId + '" class="fas fa-chevron-right tree-toggle-icon" style="color:#38bdf8; font-size:10px; pointer-events:none;"></i>' +
              '</button>' +
              '<i class="fas fa-database" style="margin-right:4px; color:#38bdf8; font-size:11px; pointer-events:none;"></i>' +
              '<span style="color:#38bdf8; font-weight:700; font-size:11px; cursor:pointer;">' + escapeHtml(finalDb) + '</span>' +
            '</div>' +
            '<div style="display:inline-flex; align-items:center;">' +
              '<span class="store-badge badge-active" style="font-size:8px; padding:1px 5px; margin-left:4px;">NEW</span>' +
              '<button type="button" title="Refresh database hierarchy" onclick="event.stopPropagation(); refreshLazyDbSubtree(event, \'' + newContainerId + '\', \'' + escapeJsString(finalDb) + '\', \'' + escapeJsString(finalEngine) + '\', \'' + escapeJsString(actionUrl) + '\', ' + newDbIdx + ')" style="background:none; border:none; color:#94a3b8; font-size:9px; cursor:pointer; padding:1px 4px; margin-right:2px;"><i class="fas fa-sync-alt"></i></button>' +
            '</div>' +
          '</div>' +
          '<div id="' + newContainerId + '" data-db="' + escapeHtml(finalDb) + '" data-loaded="false" data-state="collapsed" data-db-idx="' + newDbIdx + '" aria-expanded="false" class="tree-collapsible-content db-subtree-container" style="margin-left:8px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:6px; margin-top:3px; display:none;"></div>';

          treeContainer.appendChild(dbCardDiv);
          matchedContainer = document.getElementById(newContainerId);
          matchedDbIdx = newDbIdx;
        }
      }

      if (matchedContainer) {
        matchedContainer.style.display = 'block';
        matchedContainer.setAttribute('aria-expanded', 'true');
        treeStateManager.expandNode(matchedContainer.id);
        var icon = document.getElementById('icon_' + matchedContainer.id);
        var header = document.getElementById('db_header_' + matchedDbIdx);
        var btn = document.getElementById('btn_toggle_' + matchedContainer.id) || document.getElementById('btn_toggle_' + matchedDbIdx);
        if (header) header.setAttribute('aria-expanded', 'true');
        if (btn) btn.setAttribute('aria-expanded', 'true');
        if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';

        delete dbHierarchyCache[finalDb];

        window.targetMutatedEngine = finalEngine;
        window.targetMutatedUnit = finalUnit;
        window.targetMutatedItemId = finalId;

        loadDbHierarchy(null, matchedContainer.id, finalDb, finalEngine, actionUrl, matchedDbIdx, true, function() {
          setTimeout(function() {
            highlightAndFocusTreeItem(finalId);
            if (treeBodyContainer && savedScrollTop > 0) {
              treeBodyContainer.scrollTop = savedScrollTop;
            }
          }, 60);
        });
      } else {
        var baseAct = actionUrl.indexOf('?') > -1 ? actionUrl.substring(0, actionUrl.indexOf('?')) : actionUrl;
        window.location.href = baseAct + '?engine=' + encodeURIComponent(finalEngine) + '&target_db=' + encodeURIComponent(finalDb);
      }
    })
    .catch(function(err) {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.innerHTML = origBtnHtml;
      }
      showTreeToast('Server communication error: ' + err.message, 'error');
    });

    return false;
  }

  function bindModalFormInterceptors() {
    var modals = document.querySelectorAll('.store-card');
    for (var m = 0; m < modals.length; m++) {
      var form = modals[m].querySelector('form');
      if (form && !form.getAttribute('data-ajax-bound')) {
        form.setAttribute('data-ajax-bound', 'true');
        form.onsubmit = function(e) {
          var modalParent = this.closest('[id]');
          var mId = modalParent ? modalParent.id : '';
          return handleModalFormSubmit(e, mId);
        };
      }
    }
  }

  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function escapeJsString(str) {
    if (str === null || str === undefined) return '';
    return String(str).replace(/\\\\/g, '\\\\\\\\').replace(/'/g, "\\\\'").replace(/"/g, '\\\\"');
  }

  function buildHierarchyFetchUrl(actionUrl, selectedEngine, dbName) {
    var base = actionUrl || window.lastActionUrl || '/engines';
    var eng = selectedEngine || window.lastSelectedEngine || 'DOCUMENT';
    var cleanBase = base.indexOf('?') >= 0 ? base.split('?')[0] : base;
    return cleanBase + '?action=load_hierarchy&engine=' + encodeURIComponent(eng) + '&target_db=' + encodeURIComponent(dbName);
  }

  function toggleLazyDbSubtree(evt, containerId, dbName, selectedEngine, actionUrl, dbIdx) {
    if (evt) {
      if (typeof evt.stopPropagation === 'function') evt.stopPropagation();
      if (typeof evt.preventDefault === 'function' && evt.type === 'keydown') evt.preventDefault();
    }
    // Support polymorphic calls: toggleLazyDbSubtree(containerId, dbName, ...) or toggleLazyDbSubtree(evt, containerId, ...)
    if (typeof evt === 'string' && !containerId) {
      containerId = evt;
    } else if (typeof evt === 'string' && typeof dbName === 'string') {
      var origArgs = Array.prototype.slice.call(arguments);
      dbIdx = origArgs[4];
      actionUrl = origArgs[3];
      selectedEngine = origArgs[2];
      dbName = origArgs[1];
      containerId = origArgs[0];
    }

    var el = document.getElementById(containerId);
    var icon = document.getElementById('icon_' + containerId);
    var btn = document.getElementById('btn_toggle_' + containerId) || document.getElementById('btn_toggle_' + dbIdx);
    var header = document.getElementById('db_header_' + dbIdx);
    if (!el) return;

    if (!dbName) dbName = el.getAttribute('data-db') || 'default';
    if (!dbIdx) dbIdx = el.getAttribute('data-db-idx') || 1;

    var isHidden = (el.style.display === 'none' || el.style.display === '');
    if (isHidden) {
      el.style.display = 'block';
      el.setAttribute('aria-expanded', 'true');
      el.setAttribute('data-state', 'expanded');
      if (typeof treeStateManager !== 'undefined' && treeStateManager.expandNode) {
        treeStateManager.expandNode(containerId);
      }
      if (header) {
        header.setAttribute('aria-expanded', 'true');
        header.setAttribute('data-state', 'expanded');
      }
      if (btn) {
        btn.setAttribute('aria-expanded', 'true');
        btn.setAttribute('data-state', 'expanded');
      }
      if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';

      var isLoaded = el.getAttribute('data-loaded') === 'true';
      if (!isLoaded) {
        loadDbHierarchy(evt, containerId, dbName, selectedEngine, actionUrl, dbIdx, false);
      }
    } else {
      el.style.display = 'none';
      el.setAttribute('aria-expanded', 'false');
      el.setAttribute('data-state', 'collapsed');
      if (typeof treeStateManager !== 'undefined' && treeStateManager.collapseNode) {
        treeStateManager.collapseNode(containerId);
      }
      if (header) {
        header.setAttribute('aria-expanded', 'false');
        header.setAttribute('data-state', 'collapsed');
      }
      if (btn) {
        btn.setAttribute('aria-expanded', 'false');
        btn.setAttribute('data-state', 'collapsed');
      }
      if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';
    }
  }

  function refreshLazyDbSubtree(evt, containerId, dbName, selectedEngine, actionUrl, dbIdx) {
    if (evt && typeof evt.stopPropagation === 'function') evt.stopPropagation();
    if (typeof evt === 'string' && !containerId) {
      containerId = evt;
    } else if (typeof evt === 'string' && typeof dbName === 'string') {
      var origArgs = Array.prototype.slice.call(arguments);
      dbIdx = origArgs[4];
      actionUrl = origArgs[3];
      selectedEngine = origArgs[2];
      dbName = origArgs[1];
      containerId = origArgs[0];
    }
    var el = document.getElementById(containerId);
    var icon = document.getElementById('icon_' + containerId);
    var header = document.getElementById('db_header_' + dbIdx);
    var btn = document.getElementById('btn_toggle_' + containerId) || document.getElementById('btn_toggle_' + dbIdx);
    if (el) {
      el.style.display = 'block';
      el.setAttribute('aria-expanded', 'true');
      treeStateManager.expandNode(containerId);
      if (header) header.setAttribute('aria-expanded', 'true');
      if (btn) btn.setAttribute('aria-expanded', 'true');
      if (!dbName) dbName = el.getAttribute('data-db') || 'default';
      delete dbHierarchyCache[dbName];
      loadDbHierarchy(evt, containerId, dbName, selectedEngine, actionUrl, dbIdx, true);
    }
  }

  function handleLazyTreeKeyDown(e, containerId, dbName, selectedEngine, actionUrl, dbIdx) {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      var el = document.getElementById(containerId);
      if (!el) return;
      var isHidden = (el.style.display === 'none' || el.style.display === '');
      if ((e.key === 'ArrowRight' && isHidden) || (e.key === 'ArrowLeft' && !isHidden) || e.key === 'Enter' || e.key === ' ') {
        toggleLazyDbSubtree(e, containerId, dbName, selectedEngine, actionUrl, dbIdx);
      }
    }
  }

  function loadDbHierarchy(evt, containerId, dbName, selectedEngine, actionUrl, dbIdx, forceRefresh, callback) {
    // Support polymorphic arguments
    if (typeof evt === 'string' && !containerId) {
      containerId = evt;
    } else if (typeof evt === 'string' && typeof dbName === 'string') {
      var origArgs = Array.prototype.slice.call(arguments);
      callback = origArgs[6];
      forceRefresh = origArgs[5];
      dbIdx = origArgs[4];
      actionUrl = origArgs[3];
      selectedEngine = origArgs[2];
      dbName = origArgs[1];
      containerId = origArgs[0];
    }

    var el = document.getElementById(containerId);
    var icon = document.getElementById('icon_' + containerId);
    var header = document.getElementById('db_header_' + dbIdx);
    var btn = document.getElementById('btn_toggle_' + containerId) || document.getElementById('btn_toggle_' + dbIdx);
    if (!el) return;

    if (!dbName) dbName = el.getAttribute('data-db') || 'default';
    if (!dbIdx) dbIdx = el.getAttribute('data-db-idx') || 1;

    if (!forceRefresh && dbHierarchyCache[dbName]) {
      renderDbHierarchyHtml(dbHierarchyCache[dbName], containerId, dbName, selectedEngine, actionUrl, dbIdx);
      el.setAttribute('data-loaded', 'true');
      el.setAttribute('data-state', 'expanded');
      if (header) header.setAttribute('data-state', 'expanded');
      if (btn) btn.setAttribute('data-state', 'expanded');
      if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';
      if (callback) callback();
      return;
    }

    // Set loading state
    el.setAttribute('data-state', 'loading');
    if (header) header.setAttribute('data-state', 'loading');
    if (btn) btn.setAttribute('data-state', 'loading');
    if (icon) icon.className = 'fas fa-circle-notch fa-spin tree-toggle-icon';

    el.innerHTML = '<div class="tree-lazy-spinner" style="padding:10px 14px; margin:4px 0; background:rgba(15,23,42,0.6); border:1px solid rgba(56,189,248,0.2); border-radius:6px; color:#38bdf8; font-size:11px; display:flex; align-items:center; gap:8px;">' +
      '<i class="fas fa-circle-notch fa-spin" style="font-size:13px; color:#38bdf8;"></i>' +
      '<span>Resolving components for <b>' + escapeHtml(dbName) + '</b>...</span>' +
      '</div>';

    var fetchUrl = buildHierarchyFetchUrl(actionUrl, selectedEngine, dbName);
    fetch(fetchUrl)
      .then(function(res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.text();
      })
      .then(function(text) {
        var data;
        try {
          data = JSON.parse(text);
        } catch (parseErr) {
          throw new Error('Invalid JSON payload (' + parseErr.message + ')');
        }
        if (data && data.status === 'ERROR') {
          throw new Error(data.error || 'Server reported hierarchy resolution failure');
        }
        dbHierarchyCache[dbName] = data;
        renderDbHierarchyHtml(data, containerId, dbName, selectedEngine, actionUrl, dbIdx);
        el.setAttribute('data-loaded', 'true');
        el.setAttribute('data-state', 'expanded');
        if (header) header.setAttribute('data-state', 'expanded');
        if (btn) btn.setAttribute('data-state', 'expanded');
        if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';
        if (callback) callback();
      })
      .catch(function(err) {
        delete dbHierarchyCache[dbName];
        el.setAttribute('data-loaded', 'false');
        el.setAttribute('data-state', 'error');
        if (header) header.setAttribute('data-state', 'error');
        if (btn) btn.setAttribute('data-state', 'error');
        if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';

        el.innerHTML = '<div style="padding:10px 14px; margin:4px 0; background:rgba(239,68,68,0.1); border:1px solid rgba(239,68,68,0.3); border-radius:6px; color:#ef4444; font-size:11px; display:flex; align-items:center; justify-content:space-between;">' +
          '<span><i class="fas fa-exclamation-triangle" style="margin-right:6px;"></i>Failed to load hierarchy: ' + escapeHtml(err.message) + '</span>' +
          '<button type="button" onclick="loadDbHierarchy(event, \\'' + containerId + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(selectedEngine) + '\\', \\'' + escapeJsString(actionUrl) + '\\', ' + dbIdx + ', true)" class="btn-action btn-primary" style="padding:2px 8px; font-size:9px;">Retry</button>' +
          '</div>';
      });
  }

  function renderDbHierarchyHtml(data, containerId, dbName, selectedEngine, actionUrl, dbIdx) {
    var el = document.getElementById(containerId);
    if (!el) return;

    if (!data || !data.hasComponents) {
      el.innerHTML = '<div style="padding:8px 12px; margin:4px 0; background:rgba(15,23,42,0.5); border:1px dashed rgba(255,255,255,0.12); border-radius:6px; color:#94a3b8; font-size:10px; display:flex; align-items:center; gap:8px;">' +
        '<i class="fas fa-folder-open" style="color:#64748b; font-size:12px;"></i>' +
        '<span>No collections or engines registered</span>' +
        '</div>';
      return;
    }

    var html = '';
    var engines = data.engines || [];
    var actUrl = actionUrl || window.lastActionUrl || '/engines?engine=';

    for (var engIdx = 0; engIdx < engines.length; engIdx++) {
      var eng = engines[engIdx];
      var engNum = engIdx + 1;
      var isEngActive = (eng.name || '').toUpperCase() === (selectedEngine || window.lastSelectedEngine || '').toUpperCase();
      var engContainerId = 'eng_subtree_' + dbIdx + '_' + engNum;
      var totalUnits = eng.units ? eng.units.length : 0;
      var totalItems = eng.totalItems || 0;

      var unitSingle = eng.unitSingle || 'Collection';
      var unitPlural = eng.unitPlural || 'Collections';
      var itemLabel = eng.itemLabel || 'Item';

      var isEngExpanded = treeStateManager.isNodeExpanded(engContainerId) || isEngActive || (window.targetMutatedEngine && window.targetMutatedEngine.toUpperCase() === (eng.name || '').toUpperCase());
      if (isEngExpanded) {
        treeStateManager.expandNode(engContainerId);
      }

      html += '<div style="margin-bottom:4px; background:' + (isEngActive ? 'rgba(30,41,59,0.7)' : 'rgba(15,23,42,0.3)') + '; padding:4px 6px; border-radius:4px; border:1px solid rgba(255,255,255,0.04);">';
      
      // Engine Header Row
      html += '<div style="display:flex; justify-content:space-between; align-items:center; padding:2px 2px;">';
      html += '<div style="display:inline-flex; align-items:center; font-size:9.5px;">';
      html += '<i id="icon_' + engContainerId + '" class="fas ' + (isEngExpanded ? 'fa-chevron-down' : 'fa-chevron-right') + ' tree-toggle-icon" onclick="toggleSubtree(\\'' + engContainerId + '\\', event)" style="margin-right:4px; color:' + eng.color + '; font-size:9px; cursor:pointer;"></i>';
      html += '<i class="' + eng.icon + '" style="color:' + eng.color + '; margin-right:3px; font-size:9.5px;"></i>';
      html += '<a href="' + actUrl + eng.name + '&target_db=' + encodeURIComponent(dbName) + '" style="text-decoration:none; font-size:9.5px; color:' + (isEngActive ? '#38bdf8; font-weight:700;' : '#94a3b8;') + '">';
      html += '<span style="font-weight:700; font-size:9.5px; text-transform:uppercase;">' + escapeHtml(eng.name) + '</span>';
      html += ' → <span style="color:#cbd5e1; font-size:8.5px; font-weight:normal;">' + escapeHtml(unitPlural) + ' (' + totalUnits + ' ' + (totalUnits === 1 ? escapeHtml(unitSingle) : escapeHtml(unitPlural)) + ', ' + totalItems + ' items)</span>';
      html += '</a></div>';
      
      html += '<button type="button" onclick="openAddUnitModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(unitSingle) + '\\', \\'' + escapeJsString(dbName) + '\\')" style="background:none; border:1px solid ' + eng.color + '55; color:' + eng.color + '; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer;">+ ' + escapeHtml(unitSingle) + '</button>';
      html += '</div>';

      // Units container
      html += '<div id="' + engContainerId + '" class="tree-collapsible-content" style="margin-left:8px; border-left: 2px dotted rgba(255,255,255,0.12); padding-left:6px; margin-top:3px; display:' + (isEngExpanded ? 'block' : 'none') + ';">';
      
      var units = eng.units || [];
      for (var uIdx = 0; uIdx < units.length; uIdx++) {
        var u = units[uIdx];
        var uNum = uIdx + 1;
        var unitContainerId = 'unit_subtree_' + dbIdx + '_' + engNum + '_' + uNum;
        var uItems = u.items || [];
        var totalUnitItems = uItems.length;
        var pageSize = 10;
        var totalPages = Math.max(1, Math.ceil(totalUnitItems / pageSize));

        var isUnitTargeted = (window.targetMutatedUnit && window.targetMutatedUnit.toLowerCase() === (u.name || '').toLowerCase());
        var isUnitExpanded = treeStateManager.isNodeExpanded(unitContainerId) || isUnitTargeted || true;
        if (isUnitExpanded) {
          treeStateManager.expandNode(unitContainerId);
        }

        html += '<div style="margin-bottom:3px; margin-top:2px;">';
        
        // Unit Header Row
        html += '<div style="display:flex; justify-content:space-between; align-items:center; padding:1.5px 0;">';
        html += '<div style="display:inline-flex; align-items:center; color:#cbd5e1; font-size:9.5px; font-weight:600;">';
        html += '<i id="icon_' + unitContainerId + '" class="fas ' + (isUnitExpanded ? 'fa-chevron-down' : 'fa-chevron-right') + ' tree-toggle-icon" onclick="toggleSubtree(\\'' + unitContainerId + '\\', event)" style="margin-right:3px; color:#cbd5e1; font-size:8.5px; cursor:pointer;"></i>';
        html += '📁 <a href="' + actUrl + eng.name + '&target_db=' + encodeURIComponent(dbName) + '&coll=' + encodeURIComponent(u.name) + '" style="color:inherit; text-decoration:none; font-size:9.5px; font-weight:600; margin-left:2px;">' + escapeHtml(u.name) + '</a>';
        html += ' <span style="font-size:8px; color:#94a3b8; font-weight:normal; margin-left:2px;">(' + totalUnitItems + ')</span>';
        html += '</div>';
        
        html += '<button type="button" onclick="openAddObjectModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(dbName) + '\\')" style="background:none; border:none; color:' + eng.color + '; font-size:8.5px; cursor:pointer; padding:0;">[+ ' + escapeHtml(itemLabel) + ']</button>';
        html += '</div>';

        // Items container
        html += '<div id="' + unitContainerId + '" data-unit="' + escapeHtml(u.name) + '" class="tree-collapsible-content" style="margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px; display:' + (isUnitExpanded ? 'block' : 'none') + ';">';
        
        if (uItems.length === 0) {
          html += '<div style="font-size:9px; color:#64748b; padding:1px 0;"><span>└── </span><span style="font-style:italic; font-size:9px;">(Empty unit - click [+ ' + escapeHtml(itemLabel) + '] to insert)</span></div>';
        } else {
          for (var itmI = 0; itmI < uItems.length; itmI++) {
            var itm = uItems[itmI];
            var pageNum = Math.floor(itmI / pageSize) + 1;
            var itemDetailId = 'item_detail_' + dbIdx + '_' + engNum + '_' + uNum + '_' + itmI;
            var itemDisplay = (pageNum === 1) ? 'display:flex;' : 'display:none;';
            var isDetailExpanded = treeStateManager.isNodeExpanded(itemDetailId);

            html += '<div class="item-row-' + unitContainerId + '" data-page="' + pageNum + '" data-item-id="' + escapeHtml(itm.id) + '" style="' + itemDisplay + ' font-size:9px; color:#94a3b8; justify-content:space-between; align-items:center; padding:1.5px 0; line-height:1.2;">';
            html += '<span style="display:inline-flex; align-items:center;">';
            html += '<span>└── </span>';
            html += '<i id="icon_' + itemDetailId + '" class="fas ' + (isDetailExpanded ? 'fa-caret-down' : 'fa-caret-right') + ' tree-toggle-icon" onclick="toggleSubtree(\\'' + itemDetailId + '\\', event)" style="margin-right:3px; color:#94a3b8; font-size:8px; cursor:pointer;"></i>';
            html += '<i class="' + (eng.itemIcon || 'fas fa-file-code') + '" style="color:' + eng.color + '; margin-right:3px; font-size:8.5px;"></i>';
            html += '<span onclick="toggleSubtree(\\'' + itemDetailId + '\\', event)" style="color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace; cursor:pointer;">' + escapeHtml(itm.id) + '</span> ';
            html += '<span class="store-badge" style="background:rgba(56,189,248,0.15); color:#38bdf8; font-size:7.5px; padding:0.5px 3px; line-height:1;">v' + (itm.versionCount || 1) + '</span>';
            html += '</span>';

            html += '<div style="display:flex; align-items:center; gap:2px;">';
            html += '<button type="button" onclick="openInspectRecordModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.payloadB64 + '\\', ' + (itm.versionCount || 1) + ')" title="Inspect record details" style="background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-eye"></i></button>';
            html += '<button type="button" onclick="openUniversalEditModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.payloadB64 + '\\')" title="Edit record" style="background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-edit"></i></button>';
            html += '<button type="button" onclick="openUniversalRestoreModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.versionsB64 + '\\')" title="Version history v' + (itm.versionCount || 1) + '" style="background:none; border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-history"></i></button>';
            html += '<button type="button" onclick="openUniversalDeleteModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\')" title="Delete record" style="background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-trash-alt"></i></button>';
            html += '</div></div>';

            // Collapsed Item Details Subtree Panel
            html += '<div id="' + itemDetailId + '" class="tree-collapsible-content item-detail-' + unitContainerId + '" data-page="' + pageNum + '" style="display:' + (isDetailExpanded ? 'block' : 'none') + '; margin-left:14px; margin-top:2px; margin-bottom:4px; padding:4px 8px; background:rgba(15,23,42,0.85); border:1px solid rgba(56,189,248,0.18); border-left:2px solid ' + eng.color + '; border-radius:4px; font-size:8.5px; line-height:1.35;">';
            html += '<div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid rgba(255,255,255,0.06); padding-bottom:3px; margin-bottom:4px;">';
            var pfxMap = { 'RECORDS': 'rec:', 'KEYVALUE': 'kv:', 'VECTOR': 'vec:', 'GRAPH': 'graph:', 'TIMESERIES': 'ts:', 'COLUMN': 'col:', 'GEOSPATIAL': 'geo:', 'OBJECT': 'obj:' };
            var addrPfx = pfxMap[eng.name.toUpperCase()] || 'doc:';
            var primaryAddr = addrPfx + dbName + ':' + (u.name === 'default' ? '' : u.name + ':') + itm.id;
            html += '<span style="color:#4ade80; font-family:monospace; font-weight:600; font-size:8.5px;">📍 ' + escapeHtml(primaryAddr) + '</span>';
            html += '<span style="color:#38bdf8; font-size:8px; font-weight:500;">Engine: ' + escapeHtml(eng.name) + ' | v' + (itm.versionCount || 1) + '</span>';
            html += '</div>';

            // Props
            html += '<div style="display:flex; flex-direction:column; gap:1px; background:rgba(0,0,0,0.25); padding:4px 6px; border-radius:3px;">';
            var sp = itm.summaryProps || {};
            var hasProps = false;
            for (var pKey in sp) {
              hasProps = true;
              var pVal = sp[pKey];
              var isJref = pVal && String(pVal).indexOf('jref://') >= 0;
              html += '<div style="padding:1px 0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">';
              html += '<span style="color:#94a3b8; font-weight:600; font-size:8px; margin-right:4px;">' + escapeHtml(pKey) + ': </span>';
              html += '<span style="color:' + (isJref ? '#38bdf8' : '#f1f5f9') + '; font-family:monospace; font-size:8px;">' + escapeHtml(pVal) + '</span>';
              html += '</div>';
            }
            if (!hasProps) {
              html += '<span style="color:#64748b; font-style:italic; font-size:8px;">(No structured properties or empty payload)</span>';
            }
            html += '</div>';

            // Quick actions
            html += '<div style="display:flex; gap:8px; align-items:center; margin-top:4px; border-top:1px dashed rgba(255,255,255,0.06); padding-top:3px;">';
            html += '<button type="button" onclick="openInspectRecordModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.payloadB64 + '\\', ' + (itm.versionCount || 1) + ')" style="background:none; border:none; color:#38bdf8; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;"><i class="fas fa-search-plus"></i> Inspeccionar</button>';
            html += '<button type="button" onclick="openUniversalEditModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.payloadB64 + '\\')" style="background:none; border:none; color:#fbbf24; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;"><i class="fas fa-edit"></i> Editar</button>';
            html += '<button type="button" onclick="openUniversalRestoreModal(\\'' + escapeJsString(eng.name) + '\\', \\'' + escapeJsString(dbName) + '\\', \\'' + escapeJsString(u.name) + '\\', \\'' + escapeJsString(itm.id) + '\\', \\'' + itm.versionsB64 + '\\')" style="background:none; border:none; color:#c084fc; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;"><i class="fas fa-history"></i> Historial (v' + (itm.versionCount || 1) + ')</button>';
            html += '</div>';

            html += '</div>';
          }

          if (totalPages > 1) {
            html += '<div style="display:flex; align-items:center; justify-content:flex-end; padding:2px 0; margin-top:2px; border-top:1px dashed rgba(255,255,255,0.06);">';
            html += '<button type="button" onclick="changeSubtreePage(\\'' + unitContainerId + '\\', -1, ' + totalPages + ')" style="background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.15); color:#cbd5e1; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:3px;">‹ Prev</button>';
            html += '<span id="page_label_' + unitContainerId + '" style="font-size:8.5px; color:#38bdf8; font-weight:600; padding:0 3px;">Pág 1 / ' + totalPages + ' (' + totalUnitItems + ' total)</span>';
            html += '<button type="button" onclick="changeSubtreePage(\\'' + unitContainerId + '\\', 1, ' + totalPages + ')" style="background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.15); color:#cbd5e1; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-left:3px;">Next ›</button>';
            html += '</div>';
          }
        }

        html += '</div>'; // end unit items container
        html += '</div>'; // end unit block
      }

      html += '</div>'; // end eng units container
      html += '</div>'; // end eng block
    }

    // Indexes and Schemas
    var idxSchemasContainerId = 'idx_schemas_' + dbIdx;
    var indexes = data.indexes || [];
    var schemas = data.schemas || [];
    var isIdxSchemasExpanded = treeStateManager.isNodeExpanded(idxSchemasContainerId);

    html += '<div style="margin-bottom:4px; background:rgba(30,41,59,0.7); padding:4px 6px; border-radius:4px; border:1px solid rgba(234,179,8,0.25);">';
    html += '<div style="display:flex; justify-content:space-between; align-items:center; padding:2px 2px;">';
    html += '<div style="display:inline-flex; align-items:center; font-size:9.5px;">';
    html += '<i id="icon_' + idxSchemasContainerId + '" class="fas ' + (isIdxSchemasExpanded ? 'fa-chevron-down' : 'fa-chevron-right') + ' tree-toggle-icon" onclick="toggleSubtree(\\'' + idxSchemasContainerId + '\\', event)" style="margin-right:4px; color:#eab308; font-size:9px; cursor:pointer;"></i>';
    html += '<i class="fas fa-bolt" style="color:#eab308; margin-right:3px; font-size:9.5px;"></i>';
    html += '<span style="font-weight:bold; font-size:9.5px; color:#eab308;">INDEXES & SCHEMAS</span>';
    html += ' → <span style="color:#cbd5e1; font-size:8.5px; font-weight:normal;">(' + indexes.length + ' Indexes, ' + schemas.length + ' Schemas)</span>';
    html += '</div>';
    html += '<div style="display:flex; gap:2px;">';
    html += '<button type="button" onclick="openAddIndexModal(\\'' + escapeJsString(dbName) + '\\')" style="background:none; border:1px solid rgba(234,179,8,0.5); color:#eab308; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:3px;"><i class="fas fa-plus"></i> Index</button>';
    html += '<button type="button" onclick="openAddSchemaModal(\\'' + escapeJsString(dbName) + '\\')" style="background:none; border:1px solid rgba(56,189,248,0.5); color:#38bdf8; font-size:8.5px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-shield-alt"></i> Schema</button>';
    html += '</div>';
    html += '</div>';

    html += '<div id="' + idxSchemasContainerId + '" class="tree-collapsible-content" style="margin-left:8px; border-left: 2px dotted rgba(234,179,8,0.3); padding-left:6px; margin-top:3px; display:' + (isIdxSchemasExpanded ? 'block' : 'none') + ';">';
    
    // Indexes
    html += '<div style="margin-bottom:3px; margin-top:2px;">';
    html += '<div style="display:flex; justify-content:space-between; align-items:center;">';
    html += '<span style="color:#fde047; font-size:9.5px; font-weight:600;">📁 Secondary & Composite Indexes <span style="font-size:8px; color:#94a3b8; font-weight:normal;">(' + indexes.length + ')</span></span>';
    html += '<button type="button" onclick="openAddIndexModal(\\'' + escapeJsString(dbName) + '\\')" style="background:none; border:none; color:#eab308; font-size:8.5px; cursor:pointer; padding:0;">[+ Index]</button>';
    html += '</div>';
    html += '<div style="margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px;">';
    if (indexes.length === 0) {
      html += '<div style="font-size:9px; color:#64748b; padding:1px 0;"><span>└── </span><span style="font-style:italic; font-size:9px;">(No secondary indexes)</span></div>';
    } else {
      for (var idxI = 0; idxI < indexes.length; idxI++) {
        var idxObj = indexes[idxI];
        var idxName = idxObj.name ? String(idxObj.name).replace(/"/g, '') : ('idx_' + idxI);
        var idxType = idxObj.type ? String(idxObj.type).replace(/"/g, '') : 'BTREE';
        var idxField = idxObj.field ? String(idxObj.field).replace(/"/g, '') : '_id';
        var idxColl = idxObj.collection ? String(idxObj.collection).replace(/"/g, '') : 'default';

        html += '<div style="display:flex; justify-content:space-between; align-items:center; font-size:9px; padding:1.5px 0; color:#94a3b8;">';
        html += '<span>';
        html += '<span>└── </span>';
        html += '<i class="fas fa-bolt" style="color:#eab308; margin-right:3px; font-size:8.5px;"></i>';
        html += '<span style="color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace;">' + escapeHtml(idxName) + '</span> ';
        html += '<span class="store-badge" style="background:rgba(234,179,8,0.15); color:#fde047; font-size:7.5px; padding:0.5px 3px;">' + escapeHtml(idxType) + '</span> ';
        html += 'on <span style="color:#38bdf8; font-family:monospace; font-size:8.5px;">' + escapeHtml(idxField) + '</span> (' + escapeHtml(idxColl) + ')';
        html += '</span>';
        html += '<button type="button" onclick="openDeleteIndexModal(\\\'' + escapeJsString(dbName) + '\\\', \\\'' + escapeJsString(idxName) + '\\\')" style="background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-trash-alt"></i></button>';
        html += '</div>';
      }
    }
    html += '</div></div>';

    // Schemas
    html += '<div style="margin-bottom:3px; margin-top:2px;">';
    html += '<div style="display:flex; justify-content:space-between; align-items:center;">';
    html += '<span style="color:#38bdf8; font-size:9.5px; font-weight:600;">📁 Schema Definitions <span style="font-size:8px; color:#94a3b8; font-weight:normal;">(' + schemas.length + ')</span></span>';
    html += '<button type="button" onclick="openAddSchemaModal(\\\'' + escapeJsString(dbName) + '\\\')" style="background:none; border:none; color:#38bdf8; font-size:8.5px; cursor:pointer; padding:0;">[+ Schema]</button>';
    html += '</div>';
    html += '<div style="margin-left:8px; border-left: 1px dashed rgba(255,255,255,0.08); padding-left:6px; margin-top:2px;">';
    if (schemas.length === 0) {
      html += '<div style="font-size:9px; color:#64748b; padding:1px 0;"><span>└── </span><span style="font-style:italic; font-size:9px;">(No registered schemas)</span></div>';
    } else {
      for (var scI = 0; scI < schemas.length; scI++) {
        var scObj = schemas[scI];
        var scName = scObj.name || ('schema_' + scI);
        var scB64 = scObj.schemaB64 || '';
        html += '<div style="display:flex; justify-content:space-between; align-items:center; font-size:9px; padding:1.5px 0; color:#94a3b8;">';
        html += '<span>';
        html += '<span>└── </span>';
        html += '<i class="fas fa-shield-alt" style="color:#38bdf8; margin-right:3px; font-size:8.5px;"></i>';
        html += '<span style="color:#f8fafc; font-weight:bold; font-size:9px; font-family:monospace;">' + escapeHtml(scName) + '</span>';
        html += '</span>';
        html += '<div style="display:flex; gap:2px;">';
        html += '<button type="button" onclick="openInspectRecordModal(\\\'SCHEMA\\\', \\\'' + escapeJsString(dbName) + '\\\', \\\'schemas\\\', \\\'' + escapeJsString(scName) + '\\\', \\\'' + scB64 + '\\\', 1)" style="background:none; border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer; margin-right:2px;"><i class="fas fa-eye"></i></button>';
        html += '<button type="button" onclick="openDeleteSchemaModal(\\\'' + escapeJsString(dbName) + '\\\', \\\'' + escapeJsString(scName) + '\\\')" style="background:none; border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8px; padding:1px 4px; border-radius:3px; cursor:pointer;"><i class="fas fa-trash-alt"></i></button>';
        html += '</div></div>';
      }
    }
    html += '</div></div>';

    html += '</div></div>'; // end indexes & schemas

    el.innerHTML = html;
  }

  function toggleSubtree(elementId, evt) {
    if (evt && typeof evt.stopPropagation === 'function') evt.stopPropagation();
    var el = document.getElementById(elementId);
    var icon = document.getElementById('icon_' + elementId);
    if (!el) return;
    var isHidden = (el.style.display === 'none' || el.style.display === '');
    if (isHidden) {
      el.style.display = 'block';
      el.setAttribute('aria-expanded', 'true');
      treeStateManager.expandNode(elementId);
      if (icon) {
        if (icon.className.indexOf('fa-caret-') >= 0) {
          icon.className = 'fas fa-caret-down tree-toggle-icon';
        } else {
          icon.className = 'fas fa-chevron-down tree-toggle-icon';
        }
      }
    } else {
      el.style.display = 'none';
      el.setAttribute('aria-expanded', 'false');
      treeStateManager.collapseNode(elementId);
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
    if (window.FluxTree && typeof window.FluxTree.expandAll === 'function') {
      window.FluxTree.expandAll('storage-hierarchy-tree');
    }
    var dbContainers = document.querySelectorAll('.db-subtree-container');
    for (var i = 0; i < dbContainers.length; i++) {
      var c = dbContainers[i];
      var db = c.getAttribute('data-db');
      var dbIdx = c.getAttribute('data-db-idx') || (i + 1);
      c.style.display = 'block';
      c.setAttribute('aria-expanded', 'true');
      c.setAttribute('data-state', 'expanded');
      treeStateManager.expandNode(c.id);
      var icon = document.getElementById('icon_' + c.id);
      var header = document.getElementById('db_header_' + dbIdx);
      var btn = document.getElementById('btn_toggle_' + c.id) || document.getElementById('btn_toggle_' + dbIdx);
      if (header) { header.setAttribute('aria-expanded', 'true'); header.setAttribute('data-state', 'expanded'); }
      if (btn) btn.setAttribute('aria-expanded', 'true');
      if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';
      if (c.getAttribute('data-loaded') !== 'true') {
        var actionUrl = window.lastActionUrl || '/engines?engine=';
        var selectedEngine = window.lastSelectedEngine || 'DOCUMENT';
        loadDbHierarchy(null, c.id, db, selectedEngine, actionUrl, dbIdx, false);
      }
    }
    var nodes = document.querySelectorAll('.tree-collapsible-content, .flux-tree-group');
    for (var j = 0; j < nodes.length; j++) {
      nodes[j].style.display = 'block';
      nodes[j].setAttribute('aria-expanded', 'true');
      if (nodes[j].id) treeStateManager.expandNode(nodes[j].id);
    }
    var icons = document.querySelectorAll('.tree-toggle-icon, .flux-tree-toggle-icon');
    for (var k = 0; k < icons.length; k++) {
      icons[k].className = 'fas fa-chevron-down tree-toggle-icon flux-tree-toggle-icon';
    }
  }

  function collapseAllTreeNodes() {
    if (window.FluxTree && typeof window.FluxTree.collapseAll === 'function') {
      window.FluxTree.collapseAll('storage-hierarchy-tree');
    }
    var dbContainers = document.querySelectorAll('.db-subtree-container');
    for (var cIdx = 0; cIdx < dbContainers.length; cIdx++) {
      var dc = dbContainers[cIdx];
      var dIdx = dc.getAttribute('data-db-idx') || (cIdx + 1);
      var dHeader = document.getElementById('db_header_' + dIdx);
      var dBtn = document.getElementById('btn_toggle_' + dc.id) || document.getElementById('btn_toggle_' + dIdx);
      dc.style.display = 'none';
      dc.setAttribute('aria-expanded', 'false');
      dc.setAttribute('data-state', 'collapsed');
      treeStateManager.collapseNode(dc.id);
      if (dHeader) {
        dHeader.setAttribute('aria-expanded', 'false');
        dHeader.setAttribute('data-state', 'collapsed');
      }
      if (dBtn) {
        dBtn.setAttribute('aria-expanded', 'false');
        dBtn.setAttribute('data-state', 'collapsed');
      }
    }
    var nodes = document.querySelectorAll('.tree-collapsible-content, .flux-tree-group');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.display = 'none';
      nodes[i].setAttribute('aria-expanded', 'false');
      if (nodes[i].id) treeStateManager.collapseNode(nodes[i].id);
    }
    var icons = document.querySelectorAll('.tree-toggle-icon, .flux-tree-toggle-icon');
    for (var j = 0; j < icons.length; j++) {
      icons[j].className = 'fas fa-chevron-right tree-toggle-icon flux-tree-toggle-icon';
    }
  }
""";

        String js3 = """
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
    var schemeLen = 7;
    if (idx < 0) {
      idx = clean.indexOf('jettra://');
      schemeLen = 9;
    }
    if (idx < 0) {
      idx = clean.indexOf('ref://');
      schemeLen = 6;
    }
    if (idx < 0) return null;
    clean = clean.substring(idx);
    var rest = clean.substring(schemeLen);
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

    var extractUrisFromStr = function(str, fieldKey) {
      if (!str) return;
      var fk = (fieldKey || '').toLowerCase();
      if (fk === 'primarystorageaddress' || fk === '_primaryaddress' || fk === '_storageaddress' || fk.indexOf('remote') >= 0 || fk.indexOf('cluster') >= 0) return;
      var schemes = ['jref://'];
      for (var s = 0; s < schemes.length; s++) {
        var scheme = schemes[s];
        var startIdx = 0;
        while ((startIdx = str.indexOf(scheme, startIdx)) !== -1) {
          var endIdx = startIdx + scheme.length;
          while (endIdx < str.length) {
            var code = str.charCodeAt(endIdx);
            if (code <= 32 || code === 34 || code === 39 || code === 44 || code === 93 || code === 125) {
              break;
            }
            endIdx++;
          }
          var u = str.substring(startIdx, endIdx);
          // Omit remote node/cluster routing URIs (e.g. node@...) to avoid overload
          if (u && u.indexOf('@') >= 0) {
            startIdx = endIdx + 1;
            continue;
          }
          if (u && !seenUris[u]) {
            seenUris[u] = true;
            var parsed = parseJrefUri(u);
            if (parsed) list.push({ fieldKey: fieldKey || 'inline', parsed: parsed });
          }
          startIdx = endIdx + 1;
        }
      }
    };

    if (typeof obj === 'string') {
      extractUrisFromStr(obj, 'inline');
      return;
    }

    if (Array.isArray(obj)) {
      for (var a = 0; a < obj.length; a++) {
        findJrefsInObject(obj[a], list, seenUris);
      }
      return;
    }

    if (typeof obj === 'object') {
      var refVal = (obj['$jref'] && typeof obj['$jref'] === 'string') ? obj['$jref'] : null;
      if (refVal && refVal.indexOf('@') < 0) {
        var u = refVal.trim();
        if (!seenUris[u]) {
          seenUris[u] = true;
          var parsedObj = parseJrefUri(u);
          if (parsedObj) list.push({ fieldKey: '$jref', parsed: parsedObj });
        }
      }

      for (var k in obj) {
        if (!obj.hasOwnProperty(k)) continue;
        var kLower = k.toLowerCase();
        if (kLower === 'primarystorageaddress' || kLower === '_primaryaddress' || kLower === '_storageaddress' || kLower.indexOf('remote') >= 0 || kLower.indexOf('cluster') >= 0) continue;
        var v = obj[k];
        if (typeof v === 'string') {
          extractUrisFromStr(v, k);
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

  function handleTreeKeyDown(e, elementId) {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      var el = document.getElementById(elementId);
      if (!el) return;
      var isHidden = (el.style.display === 'none' || el.style.display === '');
      if ((e.key === 'ArrowRight' && isHidden) || (e.key === 'ArrowLeft' && !isHidden) || e.key === 'Enter' || e.key === ' ') {
        toggleSubtree(elementId);
      }
    }
  }

  function toggleInspectReferenceResolution(shouldResolve) {
    if (!currentInspectRecord) return;
    var payloadEl = document.getElementById('inspectRecordPayloadDisplay');
    var refContainer = document.getElementById('inspectRecordReferencesContainer');
    var refList = document.getElementById('inspectRecordReferencesList');
    var refBadge = document.getElementById('inspectReferencesCountBadge');

    var refs = currentInspectRecord.refs || [];

    if (!shouldResolve || refs.length === 0) {
      // Manual Exploration Mode: show original unexpanded payload, display manual list and action buttons
      var prettyRaw = currentInspectRecord.parsed ? JSON.stringify(currentInspectRecord.parsed, null, 2) : currentInspectRecord.rawPayload;
      if (payloadEl) payloadEl.value = prettyRaw;
      if (refContainer) refContainer.style.display = (refs.length > 0) ? 'block' : 'none';
      if (refBadge) {
        if (refs.length > 0) {
          refBadge.innerText = refs.length + ' Ref(s) (Modo Manual)';
          refBadge.style.display = 'inline-block';
          refBadge.style.background = 'rgba(148,163,184,0.2)';
          refBadge.style.color = '#94a3b8';
        } else {
          refBadge.style.display = 'none';
        }
      }
      if (refs.length > 0) {
        renderManualReferenceCards(refs, resolvedCache, refList);
      }
      return;
    }

    // Auto-Resolve Mode: hide manual reference list container and inline resolved references into payload viewer
    if (refContainer) refContainer.style.display = 'none';
    if (refBadge) {
      refBadge.innerText = refs.length + ' Ref(s) Auto-Resolving...';
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
          }).catch(function(err) {
            resolvedMap[u] = {
              exists: false,
              status: 'NODE_UNREACHABLE',
              diagnostic: 'Network request failed: ' + (err && err.message ? err.message : ''),
              uri: u,
              primaryStorageAddress: item.parsed.primaryStorageAddress,
              clusterNode: item.parsed.node
            };
          });
        promises.push(p);
      }
    });

    Promise.all(promises).then(function() {
      var enrichedJson = enrichObjectWithRefs(JSON.parse(JSON.stringify(currentInspectRecord.parsed)), resolvedMap);
      if (payloadEl) {
        payloadEl.value = JSON.stringify(enrichedJson, null, 2);
      }
      if (refBadge) {
        refBadge.innerText = refs.length + ' Ref(s) Auto-Resolved';
      }
    });
  }

  function enrichObjectWithRefs(obj, resolvedMap) {
    if (!obj || typeof obj !== 'object') return obj;
    for (var k in obj) {
      if (!obj.hasOwnProperty(k)) continue;
      var kLower = k.toLowerCase();
      if (k === '_resolved' || kLower === 'primarystorageaddress' || kLower === '_primaryaddress' || kLower === '_storageaddress' || kLower.indexOf('remote') >= 0 || kLower.indexOf('cluster') >= 0) continue;
      var v = obj[k];
      if (typeof v === 'string' && v.indexOf('jref://') >= 0 && v.indexOf('@') < 0) {
        var p = parseJrefUri(v);
        if (p && resolvedMap[p.uri] && (resolvedMap[p.uri].exists || resolvedMap[p.uri].jsonPayload || resolvedMap[p.uri].rawPayload)) {
          var res = resolvedMap[p.uri];
          obj[k] = {
            '$jref': p.uri,
            '_resolved': res.jsonPayload || res.rawPayload || {}
          };
        }
      } else if (v && typeof v === 'object') {
        var refVal = (v['$jref'] && typeof v['$jref'] === 'string') ? v['$jref'] : null;
        if (refVal && refVal.indexOf('@') < 0) {
          var p2 = parseJrefUri(refVal);
          var lookupKey = p2 ? p2.uri : refVal;
          if (resolvedMap[lookupKey] && (resolvedMap[lookupKey].exists || resolvedMap[lookupKey].jsonPayload || resolvedMap[lookupKey].rawPayload)) {
            var resObj = resolvedMap[lookupKey];
            delete v['$ref'];
            delete v['_primaryAddress'];
            delete v['_clusterNode'];
            delete v['_engine'];
            delete v['_database'];
            delete v['_version'];
            delete v['_status'];
            v['$jref'] = lookupKey;
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

    var seenUrisInRender = {};

    refs.forEach(function(item) {
      var p = item.parsed;
      if (!p || !p.uri) return;
      if (seenUrisInRender[p.uri]) return; // Deduplicate per inspect session
      seenUrisInRender[p.uri] = true;

      var res = resolvedMap[p.uri] || {};
      var color = engColors[p.engine] || '#38bdf8';
      var icon = engIcons[p.engine] || 'fas fa-link';
      var exists = res.exists === true;
      var cluster = res.clusterNode || p.node;

      var card = document.createElement('div');
      card.style.display = 'flex';
      card.style.alignItems = 'center';
      card.style.justifyContent = 'space-between';
      card.style.padding = '8px 12px';
      card.style.background = 'rgba(15,23,42,0.85)';
      card.style.border = '1px solid ' + (exists ? 'rgba(74,222,128,0.3)' : (res.status === 'NODE_UNREACHABLE' ? 'rgba(245,158,11,0.4)' : 'rgba(239,68,68,0.3)'));
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

      var statusBadge = '';
      if (exists && res.diagnostic && res.diagnostic.toLowerCase().indexOf('failover') >= 0) {
        statusBadge = '<span style="color:#38bdf8;"><i class="fas fa-arrows-rotate"></i> Resolved (Failover)</span>';
      } else if (exists) {
        statusBadge = '<span style="color:#4ade80;"><i class="fas fa-check-circle"></i> Resolved</span>';
      } else if (res.status === 'NODE_UNREACHABLE') {
        statusBadge = '<span style="color:#f59e0b;"><i class="fas fa-exclamation-triangle"></i> Node Unreachable</span>';
      } else if (res.status === 'NOT_FOUND') {
        statusBadge = '<span style="color:#ef4444;"><i class="fas fa-times-circle"></i> Record Not Found</span>';
      } else {
        statusBadge = '<span style="color:#ef4444;"><i class="fas fa-times-circle"></i> ' + (res.diagnostic || 'Not Resolved') + '</span>';
      }

      var payloadSummary = '';
      if (exists && res.jsonPayload) {
        var jp = res.jsonPayload;
        if (p.engine === 'GEOSPATIAL') {
          if (jp.lat !== undefined && jp.lon !== undefined) {
            payloadSummary = ' | <span style="color:#14b8a6;"><i class="fas fa-map-pin"></i> [' + jp.lat + ', ' + jp.lon + ']' + (jp.name ? ' ' + jp.name : '') + '</span>';
          } else if (jp.coordinates && jp.coordinates.lat !== undefined) {
            payloadSummary = ' | <span style="color:#14b8a6;"><i class="fas fa-map-pin"></i> [' + jp.coordinates.lat + ', ' + jp.coordinates.lon + ']</span>';
          }
        } else if (p.engine === 'RECORDS' && (jp.fullName || jp.role)) {
          payloadSummary = ' | <span style="color:#f43f5e;"><i class="fas fa-user-tag"></i> ' + (jp.fullName || '') + (jp.role ? ' (' + jp.role + ')' : '') + '</span>';
        } else if (p.engine === 'DOCUMENT' && (jp.companyName || jp.title || jp.description)) {
          payloadSummary = ' | <span style="color:#38bdf8;"><i class="fas fa-file-lines"></i> ' + (jp.companyName || jp.title || jp.description) + '</span>';
        }
      }

      var addressText = document.createElement('div');
      addressText.style.fontSize = '10.5px';
      addressText.style.color = '#94a3b8';
      addressText.innerHTML = '<span style="color:#c084fc;"><i class="fas fa-network-wired"></i> ' + cluster + '</span> | ' + statusBadge + payloadSummary;

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
      })(p.uri, p.engine, p.database, p.entityId, p.primaryStorageAddress, cluster);

      card.appendChild(leftInfo);
      card.appendChild(btnInspect);
      container.appendChild(card);
    });
  }

  function renderManualReferenceCards(refs, cachedMap, container) {
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

    var seenUrisInRender = {};

    refs.forEach(function(item) {
      var p = item.parsed;
      if (!p || !p.uri) return;
      if (seenUrisInRender[p.uri]) return;
      seenUrisInRender[p.uri] = true;

      var color = engColors[p.engine] || '#38bdf8';
      var icon = engIcons[p.engine] || 'fas fa-link';
      var cluster = p.node || 'Local Cluster (Primary)';
      var cached = cachedMap ? cachedMap[p.uri] : null;

      var card = document.createElement('div');
      card.style.display = 'flex';
      card.style.alignItems = 'center';
      card.style.justifyContent = 'space-between';
      card.style.padding = '8px 12px';
      card.style.background = 'rgba(15,23,42,0.85)';
      card.style.border = '1px solid rgba(56,189,248,0.2)';
      card.style.borderRadius = '6px';
      card.style.gap = '8px';
      card.style.marginBottom = '4px';

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

      var statusText = document.createElement('div');
      statusText.className = 'manual-ref-status-line';
      statusText.style.fontSize = '10.5px';
      statusText.style.color = '#94a3b8';

      var updateCardStatus = function(data) {
        if (!data) {
          statusText.innerHTML = '<span style="color:#c084fc;"><i class="fas fa-network-wired"></i> ' + cluster + '</span> | <span style="color:#94a3b8;"><i class="fas fa-clock"></i> Pendiente de carga manual</span>';
          return;
        }
        var sBadge = '';
        if (data.exists === true) {
          sBadge = '<span style="color:#4ade80;"><i class="fas fa-check-circle"></i> Resolved (v' + (data.version || 1) + ')</span>';
        } else if (data.status === 'NODE_UNREACHABLE') {
          sBadge = '<span style="color:#f59e0b;"><i class="fas fa-exclamation-triangle"></i> Node Unreachable</span>';
        } else if (data.status === 'NOT_FOUND') {
          sBadge = '<span style="color:#ef4444;"><i class="fas fa-times-circle"></i> Record Not Found</span>';
        } else {
          sBadge = '<span style="color:#ef4444;"><i class="fas fa-times-circle"></i> ' + (data.diagnostic || 'Not Resolved') + '</span>';
        }

        var pSummary = '';
        if (data.exists && data.jsonPayload) {
          var jp = data.jsonPayload;
          if (p.engine === 'GEOSPATIAL') {
            if (jp.lat !== undefined && jp.lon !== undefined) {
              pSummary = ' | <span style="color:#14b8a6;"><i class="fas fa-map-pin"></i> [' + jp.lat + ', ' + jp.lon + ']' + (jp.name ? ' ' + jp.name : '') + '</span>';
            } else if (jp.coordinates && jp.coordinates.lat !== undefined) {
              pSummary = ' | <span style="color:#14b8a6;"><i class="fas fa-map-pin"></i> [' + jp.coordinates.lat + ', ' + jp.coordinates.lon + ']</span>';
            }
          } else if (p.engine === 'RECORDS' && (jp.fullName || jp.role)) {
            pSummary = ' | <span style="color:#f43f5e;"><i class="fas fa-user-tag"></i> ' + (jp.fullName || '') + (jp.role ? ' (' + jp.role + ')' : '') + '</span>';
          } else if (p.engine === 'DOCUMENT' && (jp.companyName || jp.title || jp.description)) {
            pSummary = ' | <span style="color:#38bdf8;"><i class="fas fa-file-lines"></i> ' + (jp.companyName || jp.title || jp.description) + '</span>';
          }
        }
        statusText.innerHTML = '<span style="color:#c084fc;"><i class="fas fa-network-wired"></i> ' + (data.clusterNode || cluster) + '</span> | ' + sBadge + pSummary;
        card.style.border = '1px solid ' + (data.exists ? 'rgba(74,222,128,0.3)' : (data.status === 'NODE_UNREACHABLE' ? 'rgba(245,158,11,0.4)' : 'rgba(239,68,68,0.3)'));
      };

      updateCardStatus(cached);

      textCol.appendChild(uriText);
      textCol.appendChild(statusText);
      leftInfo.appendChild(badge);
      leftInfo.appendChild(textCol);

      var btnGroup = document.createElement('div');
      btnGroup.style.display = 'flex';
      btnGroup.style.gap = '4px';
      btnGroup.style.alignItems = 'center';

      var btnLoad = document.createElement('button');
      btnLoad.type = 'button';
      btnLoad.className = 'btn-action btn-secondary';
      btnLoad.style.fontSize = '10.5px';
      btnLoad.style.padding = '3px 8px';
      btnLoad.style.color = '#38bdf8';
      btnLoad.style.borderColor = 'rgba(56,189,248,0.4)';
      btnLoad.innerHTML = cached ? '<i class="fas fa-sync"></i> Recargar' : '<i class="fas fa-download"></i> Cargar Datos';
      btnLoad.onclick = function() {
        btnLoad.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Cargando...';
        var base = window.location.pathname || '/engines';
        if (base.indexOf('?') >= 0) base = base.split('?')[0];
        fetch(base + '?action=resolve_ref&uri=' + encodeURIComponent(p.uri))
          .then(function(res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
          .then(function(data) {
            resolvedCache[p.uri] = data;
            updateCardStatus(data);
            btnLoad.innerHTML = '<i class="fas fa-sync"></i> Recargar';
          })
          .catch(function(err) {
            var failData = { exists: false, status: 'NODE_UNREACHABLE', diagnostic: 'Network error: ' + (err && err.message ? err.message : '') };
            resolvedCache[p.uri] = failData;
            updateCardStatus(failData);
            btnLoad.innerHTML = '<i class="fas fa-redo"></i> Reintentar';
          });
      };

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
      })(p.uri, p.engine, p.database, p.entityId, p.primaryStorageAddress, cluster);

      btnGroup.appendChild(btnLoad);
      btnGroup.appendChild(btnInspect);

      card.appendChild(leftInfo);
      card.appendChild(btnGroup);
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

    if (typeof uriOrEngine === 'string' && (uriOrEngine.indexOf('jref://') >= 0 || uriOrEngine.indexOf('jettra://') >= 0 || uriOrEngine.indexOf('ref://') >= 0)) {
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

  function toggleTableRowDetail(detailId) {
    var el = document.getElementById(detailId);
    var icon = document.getElementById('icon_' + detailId);
    if (!el) return;
    var isHidden = (el.style.display === 'none' || el.style.display === '');
    if (isHidden) {
      el.style.display = 'block';
      if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';
    } else {
      el.style.display = 'none';
      if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';
    }
  }

  function onTableDatabaseChange(newDb, baseUrl, pageSize) {
    if (!newDb) return;
    var container = document.getElementById('tableExplorerContainer');
    if (container) {
      container.innerHTML = '<div style="padding:48px 24px; text-align:center; color:#38bdf8;"><i class="fas fa-circle-notch fa-spin" style="font-size:28px; margin-bottom:12px; display:block;"></i><div style="font-weight:700; font-size:14px; color:#f8fafc;">Switching database to [' + newDb + ']...</div><div style="font-size:12px; color:#94a3b8; margin-top:4px;">Purging prior table cache and scoping multi-model engines...</div></div>';
    }
    // Collapse any open details and reset filters
    var details = document.querySelectorAll('.explorer-table-detail-row');
    for (var i = 0; i < details.length; i++) {
      details[i].style.display = 'none';
    }
    var qf = document.getElementById('tableExplorerQuickFilter');
    if (qf) qf.value = '';
    location.href = baseUrl + '&view_mode=table&target_db=' + encodeURIComponent(newDb) + '&coll=default&table_page=1&table_size=' + pageSize;
  }

  function filterExplorerTable() {
    var input = document.getElementById('tableExplorerQuickFilter');
    var filter = input ? input.value.toLowerCase().trim() : '';
    var rows = document.querySelectorAll('.explorer-table-row');
    var visibleCount = 0;
    for (var i = 0; i < rows.length; i++) {
      var text = rows[i].innerText.toLowerCase();
      var textMatch = (!filter || text.indexOf(filter) > -1);
      var detailId = rows[i].getAttribute('data-detail-id');
      var detailEl = detailId ? document.getElementById(detailId) : null;
      if (textMatch) {
        rows[i].style.display = 'flex';
        visibleCount++;
      } else {
        rows[i].style.display = 'none';
        if (detailEl) {
          detailEl.style.display = 'none';
          var icon = document.getElementById('icon_' + detailId);
          if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';
        }
      }
    }
    var counter = document.getElementById('tableFilterVisibleCount');
    if (counter) counter.innerText = visibleCount + ' Total Records';
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

  function openSampleDatabasesModal() {
    showModal('sampleDatabasesModal');
    refreshSampleDatabasesList();
  }

  function refreshSampleDatabasesList() {
    var loadEl = document.getElementById('sampleDbsLoadingContainer');
    var listEl = document.getElementById('sampleDbsCatalogContainer');
    if (loadEl) loadEl.style.display = 'flex';
    if (listEl) listEl.style.display = 'none';

    fetch('/engines?action=list_sample_dbs', {
      headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (loadEl) loadEl.style.display = 'none';
      if (!listEl) return;
      listEl.innerHTML = '';
      listEl.style.display = 'flex';

      if (data && data.databases && data.databases.length > 0) {
        data.databases.forEach(function(db) {
          var isInst = db.isInstalled;
          var card = document.createElement('div');
          card.style.cssText = 'background:#1e293b; border:1px solid rgba(255,255,255,0.08); border-radius:8px; padding:12px 16px; display:flex; justify-content:space-between; align-items:center; gap:16px; transition:border-color 0.2s;';
          card.id = 'sample-db-card-' + db.databaseName;

          var left = document.createElement('div');
          left.style.cssText = 'flex:1; min-width:0;';

          var titleRow = document.createElement('div');
          titleRow.style.cssText = 'display:flex; align-items:center; gap:8px; margin-bottom:4px; flex-wrap:wrap;';

          var iconEl = document.createElement('i');
          iconEl.className = db.icon || 'fas fa-database';
          iconEl.style.cssText = 'color:#ec4899; font-size:14px;';

          var nameEl = document.createElement('span');
          nameEl.style.cssText = 'font-weight:700; color:#f8fafc; font-size:13px;';
          nameEl.innerText = db.databaseName;

          var engBadge = document.createElement('span');
          engBadge.style.cssText = 'font-size:10px; font-weight:700; padding:2px 6px; border-radius:4px; background:rgba(56,189,248,0.15); color:#38bdf8; border:1px solid rgba(56,189,248,0.3);';
          engBadge.innerText = db.engineType;

          var statusBadge = document.createElement('span');
          statusBadge.id = 'sample-status-' + db.databaseName;
          if (isInst) {
            statusBadge.style.cssText = 'font-size:10px; font-weight:700; padding:2px 8px; border-radius:12px; background:rgba(34,197,94,0.15); color:#4ade80; border:1px solid rgba(34,197,94,0.3); display:inline-flex; align-items:center; gap:4px;';
            statusBadge.innerHTML = '<i class="fas fa-check-circle"></i> Installed (' + (db.recordCount || db.estimatedRecords) + ' records)';
          } else {
            statusBadge.style.cssText = 'font-size:10px; font-weight:600; padding:2px 8px; border-radius:12px; background:rgba(148,163,184,0.1); color:#94a3b8; border:1px solid rgba(148,163,184,0.25); display:inline-flex; align-items:center; gap:4px;';
            statusBadge.innerHTML = '<i class="fas fa-download"></i> Available (~' + db.estimatedRecords + ' records)';
          }

          titleRow.appendChild(iconEl);
          titleRow.appendChild(nameEl);
          titleRow.appendChild(engBadge);
          titleRow.appendChild(statusBadge);

          var descEl = document.createElement('div');
          descEl.style.cssText = 'font-size:11px; color:#cbd5e1; line-height:1.4; margin-bottom:2px;';
          descEl.innerText = db.description;

          left.appendChild(titleRow);
          left.appendChild(descEl);

          var right = document.createElement('div');
          right.style.cssText = 'display:flex; align-items:center; gap:8px; flex-shrink:0;';
          right.id = 'sample-actions-' + db.databaseName;

          if (isInst) {
            var uninstBtn = document.createElement('button');
            uninstBtn.type = 'button';
            uninstBtn.className = 'btn-action btn-secondary';
            uninstBtn.style.cssText = 'padding:5px 12px; font-size:11px; background:rgba(239,68,68,0.15); border-color:rgba(239,68,68,0.3); color:#f87171; cursor:pointer;';
            uninstBtn.innerHTML = '<i class="fas fa-trash-alt" style="margin-right:4px;"></i> Uninstall';
            uninstBtn.onclick = function() { uninstallSampleDb(db.databaseName); };
            right.appendChild(uninstBtn);
          } else {
            var instBtn = document.createElement('button');
            instBtn.type = 'button';
            instBtn.className = 'btn-action btn-primary';
            instBtn.style.cssText = 'padding:5px 12px; font-size:11px; background:#ec4899; border-color:#ec4899; color:#fff; cursor:pointer;';
            instBtn.innerHTML = '<i class="fas fa-download" style="margin-right:4px;"></i> Install Dataset';
            instBtn.onclick = function() { installSampleDb(db.databaseName); };
            right.appendChild(instBtn);
          }

          card.appendChild(left);
          card.appendChild(right);
          listEl.appendChild(card);
        });
      }
    })
    .catch(function(err) {
      if (loadEl) loadEl.style.display = 'none';
      if (listEl) {
        listEl.style.display = 'block';
        listEl.innerHTML = '<div style="color:#f87171; font-size:12px; padding:16px;">Failed to load catalog: ' + (err.message || err) + '</div>';
      }
    });
  }

  function installSampleDb(dbName) {
    var actionsEl = document.getElementById('sample-actions-' + dbName);
    var statusEl = document.getElementById('sample-status-' + dbName);
    if (actionsEl) actionsEl.innerHTML = '<span style="color:#ec4899; font-size:11px; display:inline-flex; align-items:center; gap:6px;"><i class="fas fa-spinner fa-spin"></i> Installing...</span>';
    if (statusEl) {
      statusEl.style.cssText = 'font-size:10px; font-weight:700; padding:2px 8px; border-radius:12px; background:rgba(234,179,8,0.15); color:#facc15; border:1px solid rgba(234,179,8,0.3); display:inline-flex; align-items:center; gap:4px;';
      statusEl.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Seeding database...';
    }

    fetch('/engines', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: 'action=install_sample_db_ajax&target_db=' + encodeURIComponent(dbName)
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data && data.status === 'SUCCESS') {
        if (typeof showTransientToast === 'function') {
          showTransientToast(data.message || ('Dataset ' + dbName + ' installed!'), 'success');
        }
        if (typeof treeStateManager !== 'undefined' && treeStateManager.invalidateTreeCache) {
          treeStateManager.invalidateTreeCache(dbName);
        }
        refreshSampleDatabasesList();
        if (typeof reloadExplorerHierarchy === 'function') {
          reloadExplorerHierarchy(dbName);
        }
      } else {
        alert('Installation failed: ' + (data ? data.message : 'Unknown error'));
        refreshSampleDatabasesList();
      }
    })
    .catch(function(err) {
      alert('Installation failed: ' + (err.message || err));
      refreshSampleDatabasesList();
    });
  }

  function uninstallSampleDb(dbName) {
    if (!confirm('Are you sure you want to uninstall and purge sample database "' + dbName + '"? All stored records and components will be permanently deleted.')) {
      return;
    }

    var actionsEl = document.getElementById('sample-actions-' + dbName);
    var statusEl = document.getElementById('sample-status-' + dbName);
    if (actionsEl) actionsEl.innerHTML = '<span style="color:#f87171; font-size:11px; display:inline-flex; align-items:center; gap:6px;"><i class="fas fa-spinner fa-spin"></i> Removing...</span>';
    if (statusEl) {
      statusEl.style.cssText = 'font-size:10px; font-weight:700; padding:2px 8px; border-radius:12px; background:rgba(239,68,68,0.15); color:#f87171; border:1px solid rgba(239,68,68,0.3); display:inline-flex; align-items:center; gap:4px;';
      statusEl.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Purging keys...';
    }

    fetch('/engines', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: 'action=uninstall_sample_db_ajax&target_db=' + encodeURIComponent(dbName)
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data && data.status === 'SUCCESS') {
        if (typeof showTransientToast === 'function') {
          showTransientToast(data.message || ('Dataset ' + dbName + ' uninstalled!'), 'success');
        }
        if (typeof treeStateManager !== 'undefined' && treeStateManager.invalidateTreeCache) {
          treeStateManager.invalidateTreeCache(dbName);
        }
        refreshSampleDatabasesList();
        if (typeof reloadExplorerHierarchy === 'function') {
          reloadExplorerHierarchy(dbName);
        }
      } else {
        alert('Uninstallation failed: ' + (data ? data.message : 'Unknown error'));
        refreshSampleDatabasesList();
      }
    })
    .catch(function(err) {
      alert('Uninstallation failed: ' + (err.message || err));
      refreshSampleDatabasesList();
    });
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
      'exportDataModal', 'createIndexModal', 'createSchemaModal', 'sampleDatabasesModal'
    ];
    modalIds.forEach(function(mid) {
      var el = document.getElementById(mid);
      if (el && el.parentElement && el.parentElement !== document.body) {
        document.body.appendChild(el);
      }
    });
    if (typeof bindModalFormInterceptors === 'function') {
      bindModalFormInterceptors();
    }
    if (typeof treeStateManager !== 'undefined' && treeStateManager.restoreState) {
      treeStateManager.restoreState();
    }
  });
""";
        return RawScript.of(js1 + js2 + js3);
    }
}
