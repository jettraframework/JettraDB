package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.RecordVersionSnapshot;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Controller and Reactive Command Handler for editing multi-model documents and records,
 * creating new immutable versions in JettraDB.
 * Implements:
 * 1. Command Pattern (EditDocumentCommand).
 * 2. Observer Pattern with thread-safe reactive listeners.
 * 3. Java 25 Virtual Threads (Thread.ofVirtual()) via virtual thread per task executor.
 * 4. Resilience & Explicit Timeouts to prevent indefinite blocking/deadlocks.
 */
public class EditActionHandler {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private final JettraJson jsonParser = new JettraJson();
    private final List<Consumer<EditDocumentEvent>> observers = new CopyOnWriteArrayList<>();
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    public EditActionHandler(JettraStorageEngine engine) {
        this.engine = engine;
        this.hierarchyService = new HierarchyExplorerService(engine);
    }

    public EditActionHandler(JettraStorageEngine engine, HierarchyExplorerService hierarchyService) {
        this.engine = engine;
        this.hierarchyService = hierarchyService;
    }

    public void registerObserver(Consumer<EditDocumentEvent> observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public void removeObserver(Consumer<EditDocumentEvent> observer) {
        if (observer != null) {
            observers.remove(observer);
        }
    }

    private void notifyObservers(EditDocumentEvent event) {
        for (Consumer<EditDocumentEvent> obs : observers) {
            try {
                obs.accept(event);
            } catch (Exception ignored) {
                // Keep event bus resilient
            }
        }
    }

    /**
     * Executes the edit command synchronously and returns the result.
     */
    public EditDocumentResult executeEdit(EditDocumentCommand cmd) {
        if (cmd == null) {
            return EditDocumentResult.failure("UNKNOWN", "", "", "", "Command cannot be null");
        }

        String engType = cmd.engineType() != null ? cmd.engineType().toUpperCase() : "DOCUMENT";
        String db = cmd.database() != null ? cmd.database() : "default_db";
        String coll = cmd.collection() != null ? cmd.collection() : "default";
        String id = cmd.recordId() != null ? cmd.recordId() : "";
        Map<String, String> params = cmd.extraParams();

        if (id.isBlank()) {
            EditDocumentResult fail = EditDocumentResult.failure(engType, db, coll, id, "Target Record/Document ID cannot be empty");
            notifyObservers(new EditDocumentFailureEvent(cmd, fail.error(), System.currentTimeMillis()));
            return fail;
        }

        try {
            long now = System.currentTimeMillis();
            String pfx = getPrefixForEngine(engType);
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

            String finalPayloadStr = formatPayload(engType, coll, id, cmd.payload(), params);
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

            // Version calculation
            int vCount = 1;
            try {
                List<RecordVersionSnapshot> snapshots = hierarchyService.getVersionSnapshots(engType, db, coll, id);
                if (snapshots != null && !snapshots.isEmpty()) {
                    vCount = snapshots.size();
                }
            } catch (Exception ignored) {
                vCount = 1;
            }

            String msg = "[" + engType + "] Record '" + id + "' updated successfully (new version v" + vCount + " created)!";
            EditDocumentResult result = EditDocumentResult.success(engType, db, coll, id, now, vCount, msg);
            notifyObservers(new EditDocumentSuccessEvent(cmd, result, now));
            return result;

        } catch (Exception e) {
            String errorMsg = "Persistence error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            EditDocumentResult fail = EditDocumentResult.failure(engType, db, coll, id, errorMsg);
            notifyObservers(new EditDocumentFailureEvent(cmd, errorMsg, System.currentTimeMillis()));
            return fail;
        }
    }

    /**
     * Executes the edit command asynchronously using Java 25 Virtual Threads with default timeout.
     */
    public CompletableFuture<EditDocumentResult> executeEditAsync(EditDocumentCommand cmd) {
        return executeEditAsync(cmd, DEFAULT_TIMEOUT);
    }

    /**
     * Executes the edit command asynchronously using Java 25 Virtual Threads with explicit timeout.
     */
    public CompletableFuture<EditDocumentResult> executeEditAsync(EditDocumentCommand cmd, Duration timeout) {
        CompletableFuture<EditDocumentResult> future = CompletableFuture.supplyAsync(() -> executeEdit(cmd), VIRTUAL_EXECUTOR);

        Duration effectiveTimeout = (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? timeout : DEFAULT_TIMEOUT;

        return future.orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                String errorMsg;
                if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                    errorMsg = "Operation timed out after " + effectiveTimeout.toSeconds() + "s. Storage operation suspended.";
                } else {
                    errorMsg = "Async execution failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
                String eng = cmd != null && cmd.engineType() != null ? cmd.engineType() : "DOCUMENT";
                String db = cmd != null && cmd.database() != null ? cmd.database() : "default";
                String coll = cmd != null && cmd.collection() != null ? cmd.collection() : "default";
                String id = cmd != null && cmd.recordId() != null ? cmd.recordId() : "";

                EditDocumentResult timeoutFail = EditDocumentResult.failure(eng, db, coll, id, errorMsg);
                if (cmd != null) {
                    notifyObservers(new EditDocumentFailureEvent(cmd, errorMsg, System.currentTimeMillis()));
                }
                return timeoutFail;
            });
    }

    private String formatPayload(String engineName, String coll, String id, String payload, Map<String, String> params) {
        String finalPayloadStr = payload != null ? payload : "{}";
        if (params == null) params = Map.of();

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
                state.addProperty("sizeBytes", objPayload != null ? objPayload.getBytes(StandardCharsets.UTF_8).length : 0);
                state.addProperty("content", objPayload != null ? objPayload : "");
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
            default -> {
                if (payload != null && !payload.isBlank()) {
                    finalPayloadStr = payload;
                }
            }
        }
        return finalPayloadStr;
    }

    private JsonObject parseJsonOrWrap(String payload) {
        if (payload == null || payload.isBlank()) return new JsonObject();
        try {
            JsonObject obj = jsonParser.fromJson(payload, JsonObject.class);
            return obj != null ? obj : new JsonObject();
        } catch (Exception e) {
            JsonObject wrap = new JsonObject();
            wrap.addProperty("rawContent", payload);
            wrap.addProperty("parsedAt", System.currentTimeMillis());
            return wrap;
        }
    }

    private JsonObject parseJsonOrColumns(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "OK");
            return obj;
        }
        try {
            JsonObject obj = jsonParser.fromJson(raw, JsonObject.class);
            if (obj != null) return obj;
        } catch (Exception ignored) {
        }
        JsonObject obj = new JsonObject();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.contains("=")) {
                String[] kv = line.split("=", 2);
                obj.addProperty(kv[0].trim(), kv[1].trim());
            } else if (line.contains(":")) {
                String[] kv = line.split(":", 2);
                obj.addProperty(kv[0].trim(), kv[1].trim());
            }
        }
        if (obj.entrySet().isEmpty()) {
            obj.addProperty("content", raw);
        }
        return obj;
    }

    private float[] parseFloats(String str) {
        try {
            String clean = str.replace("[", "").replace("]", "").trim();
            String[] parts = clean.split(",");
            float[] res = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                res[i] = Float.parseFloat(parts[i].trim());
            }
            return res;
        } catch (Exception e) {
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        }
    }

    private String getPrefixForEngine(String engine) {
        if (engine == null) return "doc:";
        return switch (engine.toUpperCase()) {
            case "DOCUMENT" -> "doc:";
            case "KEYVALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            case "RECORDS" -> "rec:";
            default -> "doc:";
        };
    }
}
