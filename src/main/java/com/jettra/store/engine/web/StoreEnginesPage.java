package com.jettra.store.engine.web;

import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.IdGenerator.IdMode;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion;
import com.jettra.store.engine.models.*;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.report.Report;
import io.jettra.server.JettraServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Map;
import java.util.List;

/**
 * Interactive Type-Specific Database and Object Administrator for all 9 Multi-Model Storage Engines in JettraStoreEngine.
 * Provides specialized management interfaces for Document, KeyValue, Vector, Graph, TimeSeries, Column,
 * Geospatial, Object, and Records engines, with full CRUD, editing, versioning with diffs and restorations,
 * real-time filtering, and multi-format export (Excel, CSV, PDF) using JettraReport.
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

    /**
     * Handles HTTP GET actions such as file export to Excel, CSV, or PDF via JettraReport.
     */
    @Override
    protected boolean onGet(HttpExchange exchange, Map<String, String> params) throws IOException {
        if (params != null && "export".equalsIgnoreCase(params.get("action"))) {
            handleExportReport(exchange, params);
            return true;
        }
        return false;
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String selectedEngine = params != null && params.containsKey("engine") ? params.get("engine").toUpperCase() : "DOCUMENT";
        String targetDb = params != null && params.containsKey("target_db") ? params.get("target_db") : getDefaultDbForEngine(selectedEngine);
        String editId = params != null ? params.get("edit_id") : null;
        String historyId = params != null ? params.get("view_history") : null;
        String filterQuery = params != null ? params.get("filter") : "";

        String alertMessage = "";
        String alertType = "badge-active";
        String queryResultDisplay = "";

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
                    alertMessage = "Object '" + targetId + "' successfully created/updated and new version persisted in " + selectedEngine + " [" + targetDb + "]!";
                    alertType = "badge-active";
                } else if ("edit_object".equalsIgnoreCase(action)) {
                    executeTypeSpecificInsert(selectedEngine, targetDb, params);
                    alertMessage = "Record '" + targetId + "' updated successfully! New version registered.";
                    alertType = "badge-active";
                    editId = null; // Clear edit view after saving
                } else if ("restore_version".equalsIgnoreCase(action)) {
                    String versionTsStr = params.get("version_ts");
                    if (versionTsStr != null && !versionTsStr.isBlank()) {
                        long ts = Long.parseLong(versionTsStr.trim());
                        String storageKey = resolveStorageKey(selectedEngine, targetDb, targetId);
                        boolean restored = engine.getStorageCore().restoreVersion(storageKey, ts);
                        if (restored) {
                            alertMessage = "Record '" + targetId + "' successfully restored to version from " + new Date(ts) + "!";
                            alertType = "badge-active";
                        } else {
                            alertMessage = "Could not restore version for record '" + targetId + "'.";
                            alertType = "badge-raft";
                        }
                    }
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
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Administer databases, edit records, inspect full version histories with diffs, and export via JettraReport across all 9 engines.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner
        Widget alertWidget = alertMessage.isEmpty() ? Paragraph.of("") : Paragraph.of(
            "<div style='background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;'>\n" +
            "  <div style='display:flex; align-items:center; gap:10px;'><i class='fas fa-info-circle' style='color:#38bdf8; font-size:18px;'></i> <span style='font-size:14px; color:#f8fafc; font-weight:500;'>" + alertMessage + "</span></div>\n" +
            "  <span class='store-badge " + alertType + "'>STATUS</span>\n" +
            "</div>\n"
        );

        Widget engineNavPills = createEngineNavPills(selectedEngine);
        Widget dbProvisionBar = createDatabaseProvisionBar(selectedEngine, targetDb);
        Widget editRecordCard = (editId != null && !editId.isBlank()) ? createEditRecordCard(selectedEngine, targetDb, editId) : Paragraph.of("");
        Widget versionHistoryModal = (historyId != null && !historyId.isBlank()) ? createVersionHistoryModal(selectedEngine, targetDb, historyId) : Paragraph.of("");
        Widget typeSpecificCrudCard = createTypeSpecificCrudCard(selectedEngine, targetDb, queryResultDisplay);
        Widget liveObjectsExplorer = createLiveObjectsExplorer(selectedEngine, targetDb, filterQuery);
        Widget engineMatrix = createEngineMatrixTable();

        return Column.of(
            titleBlock,
            alertWidget,
            versionHistoryModal,
            engineNavPills,
            dbProvisionBar,
            editRecordCard,
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

    public static String resolveStorageKey(String engineKey, String db, String id) {
        return switch (engineKey.toUpperCase()) {
            case "DOCUMENT" -> db + ":" + id;
            case "KEYVALUE" -> "kv:" + db + ":" + id;
            case "VECTOR" -> "vec:" + db + ":" + id;
            case "GRAPH" -> "graph:" + db + ":node:" + id;
            case "TIMESERIES" -> "ts:" + db + ":" + id;
            case "COLUMN" -> "col:" + db + ":" + id;
            case "GEOSPATIAL" -> "geo:" + db + ":" + id;
            case "OBJECT" -> "obj:" + db + ":" + id;
            case "RECORDS" -> "rec:" + db + ":" + id;
            default -> db + ":" + id;
        };
    }

    // Record structure for JettraReport exports
    public static record ExportRow(String id, String type, String payload, int versions) {}

    private void handleExportReport(HttpExchange exchange, Map<String, String> params) throws IOException {
        String engineKey = params.getOrDefault("engine", "DOCUMENT").toUpperCase();
        String targetDb = params.getOrDefault("target_db", getDefaultDbForEngine(engineKey));
        String format = params.getOrDefault("format", "excel").toLowerCase();

        List<ExportRow> dataList = new ArrayList<>();
        collectEngineExportData(engineKey, targetDb, dataList);

        Report report = new Report("JettraStoreEngine " + engineKey + " Records Report");
        report.getPageSettings().setOrientation(Report.PageSettings.Orientation.LANDSCAPE);
        report.getHeader().addElement(new Report.TextElement("JettraStoreEngine - Engine: " + engineKey + " | Database: " + targetDb + " | Total Records: " + dataList.size()));

        Report.Table table = new Report.Table();
        table.addColumn(new Report.Column("Object ID / Key", "id", 140));
        table.addColumn(new Report.Column("Engine Type", "type", 110));
        table.addColumn(new Report.Column("Content Payload", "payload", 320));
        table.addColumn(new Report.Column("Versions", "versions", 60));

        report.getDetail().addElement(table);
        report.setData(dataList);
        report.getFooter().addElement(new Report.TextElement("Generated by JettraReport on " + new Date()));

        String ext;
        String mimeType;
        if ("csv".equalsIgnoreCase(format)) {
            ext = "csv";
            mimeType = "text/csv; charset=UTF-8";
        } else if ("pdf".equalsIgnoreCase(format)) {
            ext = "pdf";
            mimeType = "application/pdf";
        } else {
            ext = "xlsx";
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        File tempFile = File.createTempFile("jettra_export_" + engineKey.toLowerCase() + "_", "." + ext);
        try {
            if ("csv".equalsIgnoreCase(format)) {
                report.exportToCsv(tempFile.getAbsolutePath());
            } else if ("pdf".equalsIgnoreCase(format)) {
                report.exportToPdf(tempFile.getAbsolutePath());
            } else {
                report.exportToExcel(tempFile.getAbsolutePath());
            }

            byte[] fileBytes = Files.readAllBytes(tempFile.toPath());
            String fileName = "jettra_export_" + engineKey.toLowerCase() + "_" + targetDb + "." + ext;
            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fileBytes);
                os.flush();
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void collectEngineExportData(String engineKey, String targetDb, List<ExportRow> list) {
        switch (engineKey) {
            case "DOCUMENT" -> {
                DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (de != null) {
                    Map<String, JsonObject> items = de.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "DOCUMENT", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (ke != null) {
                    Map<String, String> items = ke.list(targetDb);
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "KEYVALUE", entry.getValue(), vCount));
                    }
                }
            }
            case "VECTOR" -> {
                VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
                if (ve != null) {
                    Map<String, JsonObject> items = ve.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : items.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "VECTOR", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "GRAPH" -> {
                GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH");
                if (ge != null) {
                    Map<String, JsonObject> nodes = ge.listNodes(targetDb);
                    for (Map.Entry<String, JsonObject> entry : nodes.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "GRAPH_NODE", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) {
                    Map<String, JsonObject> points = te.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : points.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "TIMESERIES", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "COLUMN" -> {
                ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN");
                if (ce != null) {
                    Map<String, JsonObject> rows = ce.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : rows.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "COLUMN", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine geo = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geo != null) {
                    Map<String, JsonObject> locs = geo.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : locs.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "GEOSPATIAL", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "OBJECT" -> {
                ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT");
                if (oe != null) {
                    Map<String, JsonObject> objs = oe.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : objs.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "OBJECT", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) {
                    Map<String, JsonObject> recs = re.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : recs.entrySet()) {
                        String sk = resolveStorageKey(engineKey, targetDb, entry.getKey());
                        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(sk));
                        list.add(new ExportRow(entry.getKey(), "RECORDS", entry.getValue() != null ? entry.getValue().toString() : "{}", vCount));
                    }
                }
            }
        }
    }

    private void executeTypeSpecificInsert(String engineName, String db, Map<String, String> params) {
        String rawId = params.get("target_id");
        IdMode idMode = IdMode.fromString(params.get("id_mode"));
        String targetId = IdGenerator.generateId(db, idMode, rawId);

        switch (engineName) {
            case "DOCUMENT" -> {
                DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
                if (docEngine != null) {
                    String jsonPayload = params.getOrDefault("doc_payload", "{}");
                    docEngine.insert(db, targetId, parseJsonOrWrap(jsonPayload), idMode);
                }
            }
            case "KEYVALUE" -> {
                KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
                if (kvEngine != null) {
                    kvEngine.put(db, targetId, params.getOrDefault("kv_value", ""));
                }
            }
            case "VECTOR" -> {
                VectorEngine vecEngine = (VectorEngine) engine.getEngine("VECTOR");
                if (vecEngine != null) {
                    vecEngine.insertVector(db, targetId, parseFloats(params.getOrDefault("vector_coords", "0.1")), parseJsonOrWrap(params.getOrDefault("vector_meta", "{}")));
                }
            }
            case "GRAPH" -> {
                GraphEngine graphEngine = (GraphEngine) engine.getEngine("GRAPH");
                if (graphEngine != null) {
                    graphEngine.addNode(db, targetId, parseJsonOrWrap(params.getOrDefault("node_props", "{}")));
                }
            }
            case "TIMESERIES" -> {
                TimeSeriesEngine tsEngine = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (tsEngine != null) {
                    tsEngine.insert(db, System.currentTimeMillis(), parseJsonOrWrap(params.getOrDefault("ts_tags", "{}")));
                }
            }
            case "COLUMN" -> {
                ColumnEngine colEngine = (ColumnEngine) engine.getEngine("COLUMN");
                if (colEngine != null) {
                    colEngine.insertRow(db, targetId, parseJsonOrColumns(params.getOrDefault("col_data", "{}")));
                }
            }
            case "GEOSPATIAL" -> {
                GeospatialEngine geoEngine = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
                if (geoEngine != null) {
                    geoEngine.insertLocation(db, targetId, Double.parseDouble(params.getOrDefault("geo_lat", "0")), Double.parseDouble(params.getOrDefault("geo_lon", "0")), parseJsonOrWrap(params.getOrDefault("geo_meta", "{}")));
                }
            }
            case "OBJECT" -> {
                ObjectEngine objEngine = (ObjectEngine) engine.getEngine("OBJECT");
                if (objEngine != null) {
                    objEngine.saveObject(db, targetId, "GenericBlob", parseJsonOrWrap(params.getOrDefault("obj_payload", "{}")));
                }
            }
            case "RECORDS" -> {
                RecordsEngine recEngine = (RecordsEngine) engine.getEngine("RECORDS");
                if (recEngine != null) {
                    recEngine.saveRecord(db, targetId, params.getOrDefault("rec_class", "Record"), parseJsonOrWrap(params.getOrDefault("rec_payload", "{}")));
                }
            }
        }
    }

    private String executeTypeSpecificQuery(String engineName, String db, String id, Map<String, String> params) {
        return switch (engineName) {
            case "DOCUMENT" -> { DocumentEngine e = (DocumentEngine) engine.getEngine("DOCUMENT"); yield e != null && e.get(db, id) != null ? e.get(db, id).toString() : null; }
            case "KEYVALUE" -> { KeyValueEngine e = (KeyValueEngine) engine.getEngine("KEYVALUE"); yield e != null ? e.get(db, id) : null; }
            case "VECTOR" -> { VectorEngine e = (VectorEngine) engine.getEngine("VECTOR"); yield e != null && e.getVector(db, id) != null ? e.getVector(db, id).toString() : null; }
            case "GRAPH" -> { GraphEngine e = (GraphEngine) engine.getEngine("GRAPH"); yield e != null && e.getNode(db, id) != null ? e.getNode(db, id).toString() : null; }
            case "TIMESERIES" -> {
                TimeSeriesEngine e = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                try { yield e != null && e.get(db, Long.parseLong(id)) != null ? e.get(db, Long.parseLong(id)).toString() : null; } catch (Exception ex) { yield null; }
            }
            case "COLUMN" -> { ColumnEngine e = (ColumnEngine) engine.getEngine("COLUMN"); yield e != null && e.getRow(db, id) != null ? e.getRow(db, id).toString() : null; }
            case "GEOSPATIAL" -> { GeospatialEngine e = (GeospatialEngine) engine.getEngine("GEOSPATIAL"); yield e != null && e.getLocation(db, id) != null ? e.getLocation(db, id).toString() : null; }
            case "OBJECT" -> { ObjectEngine e = (ObjectEngine) engine.getEngine("OBJECT"); yield e != null && e.getObject(db, id) != null ? e.getObject(db, id).toString() : null; }
            case "RECORDS" -> { RecordsEngine e = (RecordsEngine) engine.getEngine("RECORDS"); yield e != null && e.getRecord(db, id) != null ? e.getRecord(db, id).toString() : null; }
            default -> null;
        };
    }

    private String executeVectorSearch(String db, Map<String, String> params) {
        VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR");
        return ve != null ? jsonParser.toJson(ve.searchVector(db, parseFloats(params.getOrDefault("query_vector", "0.1")), Integer.parseInt(params.getOrDefault("top_k", "5")))) : "[]";
    }

    private String executeGeoDistance(Map<String, String> params) {
        GeospatialEngine geo = (GeospatialEngine) engine.getEngine("GEOSPATIAL");
        if (geo != null) {
            double d = geo.calculateDistance(Double.parseDouble(params.getOrDefault("dist_lat1", "0")), Double.parseDouble(params.getOrDefault("dist_lon1", "0")), Double.parseDouble(params.getOrDefault("dist_lat2", "0")), Double.parseDouble(params.getOrDefault("dist_lon2", "0")));
            return "{\"distanceKm\": " + d + "}";
        }
        return "{}";
    }

    private void executeTypeSpecificDelete(String engineName, String db, String id, Map<String, String> params) {
        switch (engineName) {
            case "DOCUMENT" -> { DocumentEngine de = (DocumentEngine) engine.getEngine("DOCUMENT"); if (de != null) de.delete(db, id); }
            case "KEYVALUE" -> { KeyValueEngine ke = (KeyValueEngine) engine.getEngine("KEYVALUE"); if (ke != null) ke.delete(db, id); }
            case "VECTOR" -> { VectorEngine ve = (VectorEngine) engine.getEngine("VECTOR"); if (ve != null) ve.deleteVector(db, id); }
            case "GRAPH" -> { GraphEngine ge = (GraphEngine) engine.getEngine("GRAPH"); if (ge != null) ge.deleteNode(db, id); }
            case "TIMESERIES" -> {
                TimeSeriesEngine te = (TimeSeriesEngine) engine.getEngine("TIMESERIES");
                if (te != null) { try { te.delete(db, Long.parseLong(id)); } catch (Exception ignored) {} }
            }
            case "COLUMN" -> { ColumnEngine ce = (ColumnEngine) engine.getEngine("COLUMN"); if (ce != null) ce.deleteRow(db, id); }
            case "GEOSPATIAL" -> { GeospatialEngine ge = (GeospatialEngine) engine.getEngine("GEOSPATIAL"); if (ge != null) ge.deleteLocation(db, id); }
            case "OBJECT" -> { ObjectEngine oe = (ObjectEngine) engine.getEngine("OBJECT"); if (oe != null) oe.deleteObject(db, id); }
            case "RECORDS" -> { RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS"); if (re != null) re.deleteRecord(db, id); }
        }
    }

    private float[] parseFloats(String raw) {
        String[] parts = raw.split("[,\\s]+");
        float[] arr = new float[parts.length];
        for (int i = 0; i < parts.length; i++) try { arr[i] = Float.parseFloat(parts[i]); } catch (Exception ignored) {}
        return arr;
    }

    private JsonObject parseJsonOrWrap(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonObject parsed = jsonParser.fromJson(payload.trim(), JsonObject.class);
            if (parsed != null && !parsed.getMap().isEmpty()) {
                return parsed;
            }
        } catch (Exception ignored) {}
        JsonObject w = new JsonObject();
        w.addProperty("raw", payload);
        return w;
    }

    private JsonObject parseJsonOrColumns(String colData) {
        try { return jsonParser.fromJson(colData, JsonObject.class); } catch (Exception e) { JsonObject o = new JsonObject(); for(String p : colData.split(";")) { String[] kv = p.split("="); if(kv.length==2) o.addProperty(kv[0], kv[1]); } return o; }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private Widget createEngineNavPills(String current) {
        String[] engines = {"DOCUMENT", "KEYVALUE", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT", "RECORDS"};
        StringBuilder sb = new StringBuilder("<div style='display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; padding: 6px; background: rgba(30, 41, 59, 0.5); border-radius: 12px; border: 1px solid rgba(255,255,255,0.06);'>\n");
        for (String eng : engines) {
            String style = eng.equalsIgnoreCase(current) ? "background: #3b82f6; color: #ffffff;" : "background: transparent; color: #94a3b8;";
            sb.append("<a href='").append(JettraServer.resolvePath("/engines?engine=" + eng)).append("' style='padding: 8px 14px; border-radius: 8px; text-decoration: none; font-size: 13px; font-weight:600; ").append(style).append("'>").append(eng).append("</a>\n");
        }
        return Paragraph.of(sb.append("</div>\n").toString());
    }

    private Widget createDatabaseProvisionBar(String engineKey, String currentDb) {
        Set<String> discoveredDbs = new TreeSet<>();
        if (currentDb != null && !currentDb.isBlank()) {
            discoveredDbs.add(currentDb);
        }
        String defaultDb = getDefaultDbForEngine(engineKey);
        if (defaultDb != null && !defaultDb.isBlank()) {
            discoveredDbs.add(defaultDb);
        }
        String prefix = switch (engineKey.toUpperCase()) {
            case "RECORDS" -> "rec:"; case "VECTOR" -> "vec:"; case "GRAPH" -> "graph:"; case "TIMESERIES" -> "ts:"; case "COLUMN" -> "col:"; case "KEYVALUE" -> "kv:"; case "GEOSPATIAL" -> "geo:"; case "OBJECT" -> "obj:"; default -> "";
        };
        Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(prefix);
        for (String k : scanned.keySet()) {
            String rest = prefix.isEmpty() ? k : k.substring(prefix.length());
            int idx = rest.indexOf(':');
            if (idx > 0) discoveredDbs.add(rest.substring(0, idx));
        }

        StringBuilder options = new StringBuilder();
        for (String d : discoveredDbs) options.append("<option value='").append(d).append("'").append(d.equals(currentDb) ? " selected" : "").append(">").append(d).append("</option>\n");

        return Div.of(Row.of(
            Column.of(Paragraph.of("<div style='font-size:14px; font-weight:600; color:#f8fafc;'><i class='fas fa-folder-open' style='color:#38bdf8; margin-right:6px;'></i> Active DB: <span style='color:#38bdf8;'>" + currentDb + "</span></div>")),
            Paragraph.of("<form method='POST' action='" + JettraServer.resolvePath("/engines?engine=" + engineKey) + "' style='display:flex; gap:8px;'><input type='hidden' name='action' value='create_db' /><select onchange=\"window.location.href='" + JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=") + "' + this.value\" style='padding:6px; background:#0f172a; border-radius:6px; color:#38bdf8; border: 1px solid rgba(255,255,255,0.1);'>" + options + "</select><input type='text' name='target_db' class='form-input' style='width:140px;' placeholder='New DB Name' /><button type='submit' class='btn-action btn-primary'><i class='fas fa-plus'></i> Add DB</button></form>")
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center;"))).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 20px; padding: 14px 20px;"));
    }

    private Widget createEditRecordCard(String engineKey, String targetDb, String editId) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=" + targetDb);
        String currentVal = executeTypeSpecificQuery(engineKey, targetDb, editId, Map.of());
        if (currentVal == null || currentVal.trim().isEmpty() || currentVal.equals("{}")) {
            String storageKey = resolveStorageKey(engineKey, targetDb, editId);
            byte[] raw = engine.getStorageCore().get(storageKey);
            if (raw != null && raw.length > 0) {
                String rawStr = new String(raw, StandardCharsets.UTF_8);
                if (!rawStr.isBlank()) {
                    currentVal = rawStr;
                }
            }
        }
        if (currentVal == null) currentVal = "{}";

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;'>\n");
        sb.append("  <h3 style='margin:0; font-size:18px; color:#38bdf8; display:flex; align-items:center; gap:8px;'>\n");
        sb.append("    <i class='fas fa-edit'></i> Edit Record: <span style='color:#f8fafc;'>").append(escapeHtml(editId)).append("</span>\n");
        sb.append("  </h3>\n");
        sb.append("  <a href='").append(actionUrl).append("' class='btn-action btn-secondary' style='font-size:12px;'><i class='fas fa-times'></i> Cancel</a>\n");
        sb.append("</div>\n");

        sb.append("<p style='font-size:13px; color:#94a3b8; margin-bottom:16px;'>Modify the object contents below. Saving will generate a new historical version with timestamp tracking.</p>\n");

        sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
        sb.append("  <input type='hidden' name='action' value='edit_object' />\n");
        sb.append("  <input type='hidden' name='target_id' value='").append(escapeHtml(editId)).append("' />\n");

        switch (engineKey) {
            case "KEYVALUE" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>String Value</label>\n");
                sb.append("    <input type='text' name='kv_value' class='form-input' value='").append(escapeHtml(currentVal)).append("' required />\n");
                sb.append("  </div>\n");
            }
            case "VECTOR" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Vector Coordinates (floats)</label>\n");
                sb.append("    <input type='text' name='vector_coords' class='form-input' value='0.1, 0.2, 0.3' required />\n");
                sb.append("  </div>\n");
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Vector Metadata (JSON)</label>\n");
                sb.append("    <textarea name='vector_meta' class='form-input' rows='4'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "GRAPH" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Node Properties (JSON)</label>\n");
                sb.append("    <textarea name='node_props' class='form-input' rows='5'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "TIMESERIES" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Telemetry Metrics (JSON)</label>\n");
                sb.append("    <textarea name='ts_tags' class='form-input' rows='5'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "COLUMN" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Column Values (JSON)</label>\n");
                sb.append("    <textarea name='col_data' class='form-input' rows='5'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "GEOSPATIAL" -> {
                sb.append("  <div style='display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:12px;'>\n");
                sb.append("    <div><label class='form-label'>Latitude</label><input type='text' name='geo_lat' class='form-input' value='8.98' required /></div>\n");
                sb.append("    <div><label class='form-label'>Longitude</label><input type='text' name='geo_lon' class='form-input' value='-79.52' required /></div>\n");
                sb.append("  </div>\n");
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Geo Properties (JSON)</label>\n");
                sb.append("    <textarea name='geo_meta' class='form-input' rows='4'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "OBJECT" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Object Payload (JSON)</label>\n");
                sb.append("    <textarea name='obj_payload' class='form-input' rows='5'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            case "RECORDS" -> {
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Record Schema / Class Name</label>\n");
                sb.append("    <input type='text' name='rec_class' class='form-input' value='RecordModel' required />\n");
                sb.append("  </div>\n");
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Record Data (JSON)</label>\n");
                sb.append("    <textarea name='rec_payload' class='form-input' rows='5'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
            default -> { // DOCUMENT
                sb.append("  <div class='form-group'>\n");
                sb.append("    <label class='form-label'>Document JSON Body</label>\n");
                sb.append("    <textarea name='doc_payload' class='form-input' rows='7'>").append(escapeHtml(currentVal)).append("</textarea>\n");
                sb.append("  </div>\n");
            }
        }

        sb.append("  <div style='display:flex; gap:10px; margin-top:16px;'>\n");
        sb.append("    <button type='submit' class='btn-action btn-primary'><i class='fas fa-save'></i> Save Changes (New Version)</button>\n");
        sb.append("    <a href='").append(actionUrl).append("' class='btn-action btn-secondary'>Cancel</a>\n");
        sb.append("  </div>\n");
        sb.append("</form>\n");

        return Div.of(Paragraph.of(sb.toString())).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom:24px; border: 1px solid #3b82f6;"));
    }

    private Widget createVersionHistoryModal(String engineKey, String targetDb, String historyId) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=" + targetDb);
        String storageKey = resolveStorageKey(engineKey, targetDb, historyId);
        List<RecordVersion> versions = engine.getStorageCore().getVersionHistory(storageKey);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='espresso-modal-overlay' style='display: flex; position: fixed; z-index: 1050; left: 0; top: 0; width: 100%; height: 100%; overflow: auto; background-color: rgba(0,0,0,0.75); backdrop-filter: blur(6px); justify-content: center; align-items: center;'>\n");
        sb.append("  <div class='espresso-modal-content store-card' style='background: #0f172a; padding: 24px; border: 1px solid #8b5cf6; border-radius: 14px; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.9); max-width: 840px; width: 95%; max-height: 85vh; overflow-y: auto; position: relative;'>\n");

        sb.append("    <div style='display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:12px;'>\n");
        sb.append("      <h3 style='margin:0; font-size:18px; color:#c084fc; display:flex; align-items:center; gap:8px;'>\n");
        sb.append("        <i class='fas fa-history'></i> Version History & Point-in-Time Restore: <span style='color:#f8fafc;'>").append(escapeHtml(historyId)).append("</span>\n");
        sb.append("      </h3>\n");
        sb.append("      <a href='").append(actionUrl).append("' class='btn-action btn-secondary' style='font-size:13px;'><i class='fas fa-times'></i> Close</a>\n");
        sb.append("    </div>\n");

        if (versions.isEmpty()) {
            sb.append("    <p style='color:#94a3b8; font-size:13px;'>No historical versions recorded for key: <code>").append(storageKey).append("</code></p>\n");
        } else {
            sb.append("    <div style='display:flex; flex-direction:column; gap:14px;'>\n");
            for (int i = 0; i < versions.size(); i++) {
                RecordVersion ver = versions.get(i);
                RecordVersion prevVer = (i < versions.size() - 1) ? versions.get(i + 1) : null;

                String badgeClass = ver.isCurrent() ? "badge-active" : "badge-engine";
                String badgeText = ver.isCurrent() ? "v" + ver.versionNumber() + " (CURRENT VERSION)" : "v" + ver.versionNumber();

                sb.append("    <div style='background:rgba(15,23,42,0.85); border:1px solid rgba(255,255,255,0.1); border-radius:10px; padding:16px;'>\n");
                sb.append("      <div style='display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;'>\n");
                sb.append("        <div style='display:flex; align-items:center; gap:10px;'>\n");
                sb.append("          <span class='store-badge ").append(badgeClass).append("'>").append(badgeText).append("</span>\n");
                sb.append("          <span style='font-size:12px; color:#94a3b8;'><i class='fas fa-clock'></i> ").append(ver.formattedDate()).append(" (Timestamp: ").append(ver.timestamp()).append(")</span>\n");
                sb.append("        </div>\n");

                if (!ver.isCurrent()) {
                    sb.append("        <form method='POST' action='").append(actionUrl).append("' style='margin:0;'>\n");
                    sb.append("          <input type='hidden' name='action' value='restore_version' />\n");
                    sb.append("          <input type='hidden' name='target_id' value='").append(escapeHtml(historyId)).append("' />\n");
                    sb.append("          <input type='hidden' name='version_ts' value='").append(ver.timestamp()).append("' />\n");
                    sb.append("          <button type='submit' class='btn-action' style='background:#8b5cf6; color:white; padding:6px 14px; font-size:12px; border-radius:6px; font-weight:500; cursor:pointer;'>\n");
                    sb.append("            <i class='fas fa-undo'></i> Restore to this Version\n");
                    sb.append("          </button>\n");
                    sb.append("        </form>\n");
                }
                sb.append("      </div>\n");

                // Diff Box if previous version exists
                if (prevVer != null) {
                    sb.append("      <div style='margin-bottom:10px; background:rgba(0,0,0,0.35); border-radius:6px; padding:10px 12px; font-size:12px; border:1px solid rgba(56,189,248,0.2);'>\n");
                    sb.append("        <span style='color:#38bdf8; font-weight:600;'><i class='fas fa-code-branch'></i> Differences against v").append(prevVer.versionNumber()).append(":</span>\n");
                    sb.append(renderDiffVisual(prevVer.payload(), ver.payload()));
                    sb.append("      </div>\n");
                }

                sb.append("      <pre style='margin:0; background:rgba(0,0,0,0.6); padding:12px; border-radius:6px; font-size:12px; color:#e2e8f0; overflow-x:auto; border:1px solid rgba(255,255,255,0.05);'><code>")
                  .append(escapeHtml(ver.payload()))
                  .append("</code></pre>\n");

                sb.append("    </div>\n");
            }
            sb.append("    </div>\n");
        }

        sb.append("    <div style='margin-top:20px; text-align:right; border-top:1px solid rgba(255,255,255,0.08); padding-top:14px;'>\n");
        sb.append("      <a href='").append(actionUrl).append("' class='btn-action btn-secondary'><i class='fas fa-times'></i> Close Modal</a>\n");
        sb.append("    </div>\n");

        sb.append("  </div>\n");
        sb.append("</div>\n");

        return Paragraph.of(sb.toString());
    }

    private String renderDiffVisual(String oldPayload, String newPayload) {
        if (oldPayload == null) oldPayload = "";
        if (newPayload == null) newPayload = "";
        if (oldPayload.equals(newPayload)) {
            return "<div style='color:#94a3b8; font-size:11px; margin-top:4px;'>Identical payload (no modification)</div>";
        }
        return "<div style='display:flex; flex-direction:column; gap:2px; font-family:monospace; font-size:11px; margin-top:4px;'>\n" +
               "  <div style='color:#f87171;'>- " + escapeHtml(oldPayload.length() > 90 ? oldPayload.substring(0, 90) + "..." : oldPayload) + "</div>\n" +
               "  <div style='color:#4ade80;'>+ " + escapeHtml(newPayload.length() > 90 ? newPayload.substring(0, 90) + "..." : newPayload) + "</div>\n" +
               "</div>";
    }

    private Widget createTypeSpecificCrudCard(String engineKey, String targetDb, String queryResultDisplay) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=" + targetDb);
        StringBuilder sb = new StringBuilder();

        sb.append("<div style='display:grid; grid-template-columns: 1fr 1fr; gap:20px;'>\n");

        // INSERT / CREATE SECTION
        sb.append("<div>\n");
        sb.append("<h3 style='margin:0 0 12px 0; font-size:16px; color:#38bdf8;'><i class='fas fa-plus-circle'></i> Insert / Persist New ").append(engineKey).append(" Object</h3>\n");
        sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
        sb.append("  <input type='hidden' name='action' value='insert_object' />\n");
        sb.append("  <div style='display:grid; grid-template-columns: 1.1fr 1fr; gap:10px; margin-bottom:12px;'>\n");
        sb.append("    <div>\n");
        sb.append("      <label class='form-label'>ID Strategy</label>\n");
        sb.append("      <select name='id_mode' class='form-input' style='background:#0f172a; color:#38bdf8; border:1px solid rgba(255,255,255,0.1);'>\n");
        sb.append("        <option value='MANUAL'>1. Manual ID</option>\n");
        sb.append("        <option value='AUTOINCREMENT'>2. Auto-increment Sequence</option>\n");
        sb.append("        <option value='UUID'>3. Composite UUID (Host+Time+DB+Entropy)</option>\n");
        sb.append("      </select>\n");
        sb.append("    </div>\n");
        sb.append("    <div>\n");
        sb.append("      <label class='form-label'>Object / Document ID</label>\n");
        sb.append("      <input type='text' name='target_id' class='form-input' placeholder='ID (optional for Auto/UUID)' />\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");

        switch (engineKey) {
            case "KEYVALUE" -> sb.append("  <div class='form-group'><label class='form-label'>String Value</label><input type='text' name='kv_value' class='form-input' placeholder='value data...' required /></div>\n");
            case "VECTOR" -> {
                sb.append("  <div class='form-group'><label class='form-label'>Vector Coords (floats)</label><input type='text' name='vector_coords' class='form-input' placeholder='0.12, 0.45, 0.88' required /></div>\n");
                sb.append("  <div class='form-group'><label class='form-label'>Metadata (JSON)</label><textarea name='vector_meta' class='form-input' rows='3'>{\"model\":\"text-embedding-3\",\"author\":\"system\"}</textarea></div>\n");
            }
            case "GRAPH" -> sb.append("  <div class='form-group'><label class='form-label'>Node Properties (JSON)</label><textarea name='node_props' class='form-input' rows='4'>{\"name\":\"User Node\",\"role\":\"admin\",\"connections\":4}</textarea></div>\n");
            case "TIMESERIES" -> sb.append("  <div class='form-group'><label class='form-label'>Telemetry Metrics (JSON)</label><textarea name='ts_tags' class='form-input' rows='4'>{\"temperature\":24.5,\"cpu_load\":42,\"status\":\"OK\"}</textarea></div>\n");
            case "COLUMN" -> sb.append("  <div class='form-group'><label class='form-label'>Row Data (JSON or key=val;)</label><textarea name='col_data' class='form-input' rows='4'>{\"region\":\"US-East\",\"revenue\":15200,\"quarter\":\"Q3\"}</textarea></div>\n");
            case "GEOSPATIAL" -> {
                sb.append("  <div style='display:grid; grid-template-columns:1fr 1fr; gap:8px;'><input type='text' name='geo_lat' class='form-input' placeholder='Lat (e.g. 8.98)' required /><input type='text' name='geo_lon' class='form-input' placeholder='Lon (e.g. -79.52)' required /></div>\n");
                sb.append("  <div class='form-group' style='margin-top:8px;'><label class='form-label'>Geo Metadata (JSON)</label><textarea name='geo_meta' class='form-input' rows='3'>{\"place\":\"Panama City Hub\",\"type\":\"warehouse\"}</textarea></div>\n");
            }
            case "OBJECT" -> sb.append("  <div class='form-group'><label class='form-label'>Object JSON / Blob</label><textarea name='obj_payload' class='form-input' rows='4'>{\"fileName\":\"report.pdf\",\"sizeBytes\":1048576,\"mime\":\"application/pdf\"}</textarea></div>\n");
            case "RECORDS" -> {
                sb.append("  <div class='form-group'><label class='form-label'>Java 25 Record Type</label><input type='text' name='rec_class' class='form-input' value='CustomerProfile' required /></div>\n");
                sb.append("  <div class='form-group'><label class='form-label'>Record Data (JSON)</label><textarea name='rec_payload' class='form-input' rows='3'>{\"firstName\":\"Carlos\",\"email\":\"carlos@example.com\",\"verified\":true}</textarea></div>\n");
            }
            default -> sb.append("  <div class='form-group'><label class='form-label'>Document JSON Body</label><textarea name='doc_payload' class='form-input' rows='4'>{\"title\":\"Sample Title\",\"status\":\"ACTIVE\",\"score\":98}</textarea></div>\n");
        }
        sb.append("  <button type='submit' class='btn-action btn-primary' style='margin-top:10px;'><i class='fas fa-save'></i> Persist to ").append(engineKey).append("</button>\n");
        sb.append("</form>\n");
        sb.append("</div>\n");

        // QUERY / QUERY SPECIALIZED SECTION
        sb.append("<div>\n");
        sb.append("<h3 style='margin:0 0 12px 0; font-size:16px; color:#10b981;'><i class='fas fa-search'></i> Lookup / Query Engine Data</h3>\n");
        sb.append("<form method='POST' action='").append(actionUrl).append("'>\n");
        sb.append("  <input type='hidden' name='action' value='query_object' />\n");
        sb.append("  <div class='form-group'><label class='form-label'>Lookup by ID / Key</label><div style='display:flex; gap:8px;'><input type='text' name='target_id' class='form-input' placeholder='Enter ID...' required /><button type='submit' class='btn-action btn-primary'><i class='fas fa-search'></i> Find</button></div></div>\n");
        sb.append("</form>\n");

        if (queryResultDisplay != null && !queryResultDisplay.isBlank()) {
            sb.append("<div style='margin-top:14px;'><label class='form-label' style='color:#10b981;'>Query Output Result:</label><pre style='background:rgba(0,0,0,0.5); padding:10px; border-radius:6px; font-size:12px; color:#a7f3d0; max-height:160px; overflow-y:auto;'><code>").append(escapeHtml(queryResultDisplay)).append("</code></pre></div>\n");
        }
        sb.append("</div>\n");

        sb.append("</div>\n");

        return Div.of(Paragraph.of(sb.toString())).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom:24px;"));
    }

    private Widget createLiveObjectsExplorer(String engineKey, String targetDb, String filterQuery) {
        String actionUrl = JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=" + targetDb);
        String exportBaseUrl = JettraServer.resolvePath("/engines?engine=" + engineKey + "&target_db=" + targetDb + "&action=export");

        StringBuilder sb = new StringBuilder();

        // Header with Live Search Filter and Export Buttons
        sb.append("<div style='display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px;'>\n");
        sb.append("  <div>\n");
        sb.append("    <h3 style='margin:0; font-size:18px; font-weight:600;'><i class='fas fa-table' style='color:#38bdf8; margin-right:8px;'></i> Stored Objects in [").append(targetDb).append("]</h3>\n");
        sb.append("    <p style='margin:2px 0 0 0; font-size:12px; color:#94a3b8;'>Inspect live records, edit fields, navigate version history diffs, and export reports.</p>\n");
        sb.append("  </div>\n");

        sb.append("  <div style='display:flex; align-items:center; gap:8px; flex-wrap:wrap;'>\n");
        // Real-time table search input (client-side JS filtering)
        sb.append("    <div style='position:relative;'>\n");
        sb.append("      <input type='text' id='liveTableSearch' class='form-input' style='padding-left:30px; font-size:12px; height:32px; width:180px;' placeholder='Filter records...' onkeyup='filterLiveTable()' />\n");
        sb.append("      <i class='fas fa-search' style='position:absolute; left:10px; top:9px; color:#94a3b8; font-size:12px;'></i>\n");
        sb.append("    </div>\n");

        // Export dropdown / buttons using JettraReport
        sb.append("    <div style='display:flex; gap:6px;'>\n");
        sb.append("      <a href='").append(exportBaseUrl).append("&format=excel' class='btn-action btn-secondary' style='font-size:12px; padding:6px 10px; color:#10b981; border-color:rgba(16,185,129,0.3);'><i class='fas fa-file-excel'></i> Excel</a>\n");
        sb.append("      <a href='").append(exportBaseUrl).append("&format=csv' class='btn-action btn-secondary' style='font-size:12px; padding:6px 10px; color:#38bdf8; border-color:rgba(56,189,248,0.3);'><i class='fas fa-file-csv'></i> CSV</a>\n");
        sb.append("      <a href='").append(exportBaseUrl).append("&format=pdf' class='btn-action btn-secondary' style='font-size:12px; padding:6px 10px; color:#f43f5e; border-color:rgba(244,63,94,0.3);'><i class='fas fa-file-pdf'></i> PDF</a>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");

        sb.append("<div class='table-responsive'>\n");
        sb.append("  <table class='jettra-table' id='recordsTable'>\n");
        sb.append("    <thead>\n");
        sb.append("      <tr>\n");
        sb.append("        <th style='width:18%;'>Object ID / Key</th>\n");
        sb.append("        <th style='width:18%;'>Type & Representation</th>\n");
        sb.append("        <th style='width:38%;'>Storage Preview</th>\n");
        sb.append("        <th style='width:10%; text-align:center;'>Versions</th>\n");
        sb.append("        <th style='width:16%; text-align:right;'>Actions</th>\n");
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "DOCUMENT (JSON)", "badge-active", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "KEY-VALUE STRING", "badge-engine", val));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "VECTOR (float[])", "badge-engine", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "VERTEX (Node)", "badge-engine", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, ts, "TIME-SERIES POINT", "badge-active", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "COLUMNAR ROW", "badge-engine", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "GIS 2D POINT", "badge-active", preview));
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
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "OBJECT BLOB", "badge-engine", preview));
                    }
                }
            }
            case "RECORDS" -> {
                RecordsEngine re = (RecordsEngine) engine.getEngine("RECORDS");
                if (re != null) {
                    Map<String, JsonObject> recs = re.list(targetDb);
                    for (Map.Entry<String, JsonObject> entry : recs.entrySet()) {
                        count++;
                        String id = entry.getKey();
                        String preview = entry.getValue() != null ? entry.getValue().toString() : "{}";
                        sb.append(buildTableRow(actionUrl, engineKey, targetDb, id, "RECORD (Java 25)", "badge-active", preview));
                    }
                }
            }
        }

        if (count == 0) {
            sb.append("<tr id='noRecordsRow'><td colspan='5' style='text-align:center; color:#94a3b8; padding:24px;'>No objects currently stored in ").append(engineKey).append(" [").append(targetDb).append("]. Use the form above to add objects.</td></tr>");
        }

        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("</div>\n");

        // Client-side JavaScript for real-time table searching/filtering
        sb.append("<script>\n");
        sb.append("function filterLiveTable() {\n");
        sb.append("  const input = document.getElementById('liveTableSearch');\n");
        sb.append("  const filter = input.value.toLowerCase();\n");
        sb.append("  const table = document.getElementById('recordsTable');\n");
        sb.append("  const trs = table.getElementsByTagName('tr');\n");
        sb.append("  for (let i = 1; i < trs.length; i++) {\n");
        sb.append("    if (trs[i].id === 'noRecordsRow') continue;\n");
        sb.append("    const text = trs[i].textContent.toLowerCase();\n");
        sb.append("    trs[i].style.display = text.indexOf(filter) > -1 ? '' : 'none';\n");
        sb.append("  }\n");
        sb.append("}\n");
        sb.append("</script>\n");

        return Div.of(Paragraph.of(sb.toString())).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom:24px;"));
    }

    private String buildTableRow(String actionUrl, String engineKey, String targetDb, String id, String badgeLabel, String badgeClass, String payload) {
        String displayPreview = payload != null ? payload : "{}";
        if (displayPreview.trim().isEmpty() || displayPreview.equals("{}")) {
            String storageKey = resolveStorageKey(engineKey, targetDb, id);
            byte[] raw = engine.getStorageCore().get(storageKey);
            if (raw != null && raw.length > 0) {
                String rawStr = new String(raw, StandardCharsets.UTF_8);
                if (!rawStr.isBlank() && !rawStr.equals("{}")) {
                    displayPreview = rawStr;
                }
            }
        }
        if (displayPreview.length() > 65) displayPreview = displayPreview.substring(0, 65) + "...";

        String storageKey = resolveStorageKey(engineKey, targetDb, id);
        int vCount = Math.max(1, engine.getStorageCore().getVersionCount(storageKey));

        String editUrl = actionUrl + "&edit_id=" + id;
        String historyUrl = actionUrl + "&view_history=" + id;

        return "<tr>" +
               "  <td><b>" + escapeHtml(id) + "</b></td>" +
               "  <td><span class='store-badge " + badgeClass + "'>" + badgeLabel + "</span></td>" +
               "  <td><code style='font-size:11px;'>" + escapeHtml(displayPreview) + "</code></td>" +
               "  <td style='text-align:center;'><a href='" + historyUrl + "' style='text-decoration:none;'><span class='store-badge' style='background:rgba(139,92,246,0.2); color:#c084fc; cursor:pointer;' title='Click to view version history modal'><i class='fas fa-layer-group'></i> v" + vCount + "</span></a></td>" +
               "  <td style='text-align:right; white-space:nowrap;'>" +
               "    <a href='" + historyUrl + "' class='btn-action btn-secondary' style='color:#c084fc; padding:4px 8px; font-size:11px; margin-right:4px;' title='View Version History Modal'><i class='fas fa-history'></i></a>" +
               "    <a href='" + editUrl + "' class='btn-action btn-secondary' style='color:#38bdf8; padding:4px 8px; font-size:11px; margin-right:4px;' title='Edit Record'><i class='fas fa-edit'></i></a>" +
               "    " + buildDeleteButton(actionUrl, targetDb, id, engineKey) +
               "  </td>" +
               "</tr>\n";
    }

    private String buildDeleteButton(String actionUrl, String db, String id, String engineKey) {
        String dlgId = "delete_dlg_" + Math.abs((engineKey + "_" + db + "_" + id).hashCode());
        return "<!-- Delete Trigger -->\n" +
               "<button type='button' class='btn-action btn-secondary' style='color:#ef4444; padding:4px 8px; font-size:11px;' onclick=\"document.getElementById('" + dlgId + "').showModal();\" title='Delete Record'><i class='fas fa-trash'></i></button>\n" +
               "<!-- JettraFlux Native Confirmation Dialog -->\n" +
               "<dialog id='" + dlgId + "' style='border: 1px solid rgba(239,68,68,0.4); border-radius: 12px; padding: 0; background: #0f172a; color: #f8fafc; max-width: 440px; width: 90%; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.8); backdrop-filter: blur(8px); margin:auto;'>\n" +
               "  <div style='padding: 18px 20px; border-bottom: 1px solid rgba(255,255,255,0.08); display: flex; justify-content: space-between; align-items: center;'>\n" +
               "    <div style='display:flex; align-items:center; gap:10px;'>\n" +
               "      <div style='width:34px; height:34px; border-radius:8px; background:rgba(239,68,68,0.15); display:flex; align-items:center; justify-content:center; color:#ef4444; font-size:16px;'>\n" +
               "        <i class='fas fa-exclamation-triangle'></i>\n" +
               "      </div>\n" +
               "      <div>\n" +
               "        <h3 style='margin:0; font-size:15px; font-weight:600; color:#f8fafc;'>Confirm Deletion</h3>\n" +
               "        <p style='margin:0; font-size:11px; color:#94a3b8;'>Engine: " + escapeHtml(engineKey) + " | Database: " + escapeHtml(db) + "</p>\n" +
               "      </div>\n" +
               "    </div>\n" +
               "    <button type='button' onclick=\"document.getElementById('" + dlgId + "').close();\" style='background:none; border:none; color:#94a3b8; font-size:20px; cursor:pointer; line-height:1;'>&times;</button>\n" +
               "  </div>\n" +
               "  <div style='padding: 20px; font-size: 13px; line-height: 1.5; color: #cbd5e1;'>\n" +
               "    Are you sure you want to permanently delete object <b style='color:#f87171; font-family:monospace; background:rgba(239,68,68,0.1); padding:2px 6px; border-radius:4px;'>" + escapeHtml(id) + "</b>?\n" +
               "    <p style='margin:8px 0 0 0; font-size:11px; color:#94a3b8;'>This will write a deletion tombstone and remove it from active queries.</p>\n" +
               "  </div>\n" +
               "  <div style='padding: 14px 20px; border-top: 1px solid rgba(255,255,255,0.08); display: flex; justify-content: flex-end; gap: 10px; background: rgba(15,23,42,0.6);'>\n" +
               "    <button type='button' onclick=\"document.getElementById('" + dlgId + "').close();\" class='btn-action btn-secondary'>Cancel</button>\n" +
               "    <form method='POST' action='" + actionUrl + "' style='margin:0;'>\n" +
               "      <input type='hidden' name='action' value='delete_object' />\n" +
               "      <input type='hidden' name='target_db' value='" + escapeHtml(db) + "' />\n" +
               "      <input type='hidden' name='target_id' value='" + escapeHtml(id) + "' />\n" +
               "      <button type='submit' class='btn-action' style='background:#ef4444; color:#fff; font-weight:500;'><i class='fas fa-trash'></i> Delete Object</button>\n" +
               "    </form>\n" +
               "  </div>\n" +
               "</dialog>\n";
    }

    private Widget createEngineMatrixTable() {
        return Div.of(
            Paragraph.of("<h3 style='margin: 0 0 16px 0; font-size: 18px; font-weight: 600;'><i class='fas fa-table' style='color:#38bdf8; margin-right:8px;'></i> All 9 Supported Multi-Model Engines</h3>"),
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
                "      <tr><td><i class='fas fa-id-card' style='color:#f43f5e; margin-right:6px;'></i> <b>RECORDS</b></td><td>Immutable Java 25 Records, Component Validation</td><td>Compact Object Headers (rec:)</td><td>Raft Sync</td><td><code>/api/model/records/*</code></td><td><span class='store-badge badge-active'>ACTIVE</span></td></tr>\n" +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));
    }
}
