package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Specialized Engine for Java Records and immutable structured record storage.
 * Provides schema reflection, field projections, predicate queries, and component validation.
 */
public class RecordsEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;

    public RecordsEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
    }

    @Override
    public String getName() {
        return "RECORDS";
    }

    @Override
    public void init() {
        System.out.println("Initializing Records Engine (Java 25 Record & Immutable Schema Storage)...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing Records Engine...");
        raftClient.close();
    }

    /**
     * Saves a Java Record instance directly, extracting its canonical record components.
     */
    public void saveRecordObject(String collection, String recordId, Record recordObject) {
        Class<?> recordClass = recordObject.getClass();
        String className = recordClass.getName();
        
        JsonObject components = new JsonObject();
        JsonObject schema = new JsonObject();
        
        try {
            RecordComponent[] recordComponents = recordClass.getRecordComponents();
            if (recordComponents != null) {
                for (RecordComponent rc : recordComponents) {
                    String fieldName = rc.getName();
                    String typeName = rc.getType().getSimpleName();
                    schema.addProperty(fieldName, typeName);
                    
                    Object val = rc.getAccessor().invoke(recordObject);
                    if (val != null) {
                        if (val instanceof Number n) {
                            components.addProperty(fieldName, n);
                        } else if (val instanceof Boolean b) {
                            components.addProperty(fieldName, b);
                        } else {
                            components.addProperty(fieldName, val.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Reflection error extracting record components: " + e.getMessage());
        }

        saveRecord(collection, recordId, className, components, schema);
    }

    /**
     * Saves a Record with explicit class metadata and components.
     */
    public void saveRecord(String collection, String recordId, String recordClass, JsonObject components) {
        saveRecord(collection, recordId, recordClass, components, new JsonObject());
    }

    /**
     * Saves a Record with schema metadata and components, replicating via Raft consensus.
     */
    public void saveRecord(String collection, String recordId, String recordClass, JsonObject components, JsonObject schema) {
        String internalKey = "rec:" + collection + ":" + recordId;
        
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("_recordClass", recordClass != null ? recordClass : "java.lang.Record");
        wrapper.addProperty("_timestamp", System.currentTimeMillis());
        wrapper.addProperty("_version", 1L);
        
        if (schema != null && !schema.getMap().isEmpty()) {
            wrapper.add("_schema", schema);
        } else {
            JsonObject autoSchema = new JsonObject();
            if (components != null) {
                for (Map.Entry<String, Object> entry : components.entrySet()) {
                    Object val = entry.getValue();
                    autoSchema.addProperty(entry.getKey(), val != null ? val.getClass().getSimpleName() : "Object");
                }
            }
            wrapper.add("_schema", autoSchema);
        }
        
        wrapper.add("components", components != null ? components : new JsonObject());

        String command = "PUT " + internalKey + " " + gson.toJson(wrapper);
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate record data via Raft for key: " + internalKey);
        }
    }

    /**
     * Retrieves a stored record by ID.
     */
    public JsonObject getRecord(String collection, String recordId) {
        String internalKey = "rec:" + collection + ":" + recordId;
        byte[] payload = engine.getStorageCore().get(internalKey);
        if (payload != null && payload.length > 0) {
            return gson.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        }
        return null;
    }

    /**
     * Deletes a record by ID via Raft consensus tombstone.
     */
    public void deleteRecord(String collection, String recordId) {
        String internalKey = "rec:" + collection + ":" + recordId;
        String command = "PUT " + internalKey + " ";
        raftClient.sendCommand(command);
    }

    /**
     * Lists all records in a given collection.
     */
    public Map<String, JsonObject> list(String collection) {
        Map<String, JsonObject> records = new LinkedHashMap<>();
        String prefix = "rec:" + collection + ":";
        Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String recordId = entry.getKey().substring(prefix.length());
            try {
                JsonObject record = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                records.put(recordId, record);
            } catch (Exception ignored) {}
        }
        return records;
    }

    /**
     * Returns total count of records in collection.
     */
    public int count(String collection) {
        String prefix = "rec:" + collection + ":";
        return engine.getStorageCore().scanPrefix(prefix).size();
    }

    /**
     * Queries records by a matching field value inside components.
     */
    public List<JsonObject> queryByField(String collection, String fieldName, String expectedValue) {
        List<JsonObject> results = new ArrayList<>();
        Map<String, JsonObject> all = list(collection);
        for (JsonObject rec : all.values()) {
            if (rec.has("components")) {
                JsonObject comps = (JsonObject) rec.get("components");
                if (comps.has(fieldName)) {
                    Object val = comps.get(fieldName);
                    if (val != null && val.toString().equalsIgnoreCase(expectedValue)) {
                        results.add(rec);
                    }
                }
            }
        }
        return results;
    }

    /**
     * Projects only requested fields for a specific record.
     */
    public JsonObject projectFields(String collection, String recordId, List<String> fields) {
        JsonObject full = getRecord(collection, recordId);
        if (full == null) return null;

        JsonObject projected = new JsonObject();
        projected.addProperty("_recordClass", (String) full.get("_recordClass"));
        
        JsonObject comps = full.has("components") ? (JsonObject) full.get("components") : new JsonObject();
        JsonObject projComps = new JsonObject();
        
        for (String f : fields) {
            String cleanField = f.trim();
            if (comps.has(cleanField)) {
                projComps.add(cleanField, (JsonObject) (comps.get(cleanField) instanceof JsonObject ? comps.get(cleanField) : null));
                if (!projComps.has(cleanField)) {
                    Object v = comps.get(cleanField);
                    if (v instanceof Number n) projComps.addProperty(cleanField, n);
                    else if (v instanceof Boolean b) projComps.addProperty(cleanField, b);
                    else projComps.addProperty(cleanField, v != null ? v.toString() : "");
                }
            }
        }
        projected.add("components", projComps);
        return projected;
    }

    /**
     * Partially updates a specific component field in a stored record.
     */
    public boolean updateField(String collection, String recordId, String fieldName, Object newValue) {
        JsonObject existing = getRecord(collection, recordId);
        if (existing == null) return false;

        String recordClass = (String) existing.get("_recordClass");
        JsonObject comps = existing.has("components") ? (JsonObject) existing.get("components") : new JsonObject();
        JsonObject schema = existing.has("_schema") ? (JsonObject) existing.get("_schema") : new JsonObject();

        if (newValue instanceof Number n) {
            comps.addProperty(fieldName, n);
        } else if (newValue instanceof Boolean b) {
            comps.addProperty(fieldName, b);
        } else {
            comps.addProperty(fieldName, newValue != null ? newValue.toString() : "");
        }

        saveRecord(collection, recordId, recordClass, comps, schema);
        return true;
    }

    /**
     * Returns the schema metadata for a stored record.
     */
    public JsonObject getSchema(String collection, String recordId) {
        JsonObject rec = getRecord(collection, recordId);
        if (rec != null && rec.has("_schema")) {
            return (JsonObject) rec.get("_schema");
        }
        return null;
    }
}
