package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.json.JsonArray;
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
     * Supports Java 25 primitives, Date, temporal types (LocalDate, Instant, etc.),
     * collections (List, Set), arrays, enums, and nested Record references.
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
                    Class<?> type = rc.getType();
                    String typeName = type.getSimpleName();

                    if (java.util.List.class.isAssignableFrom(type)) {
                        typeName = "List<String>";
                    } else if (java.util.Set.class.isAssignableFrom(type)) {
                        typeName = "Set<String>";
                    } else if (java.util.Collection.class.isAssignableFrom(type)) {
                        typeName = "Collection<String>";
                    } else if (type.isArray()) {
                        typeName = "Array<" + type.getComponentType().getSimpleName() + ">";
                    } else if (type.isEnum()) {
                        typeName = "Enum<" + type.getSimpleName() + ">";
                    } else if (type.isRecord()) {
                        typeName = type.getSimpleName();
                    }
                    schema.addProperty(fieldName, typeName);
                    
                    Object val = rc.getAccessor().invoke(recordObject);
                    if (val != null) {
                        serializeValueToComponent(components, fieldName, val);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Reflection error extracting record components: " + e.getMessage());
        }

        saveRecord(collection, recordId, className, components, schema);
    }

    /**
     * Serializes any component value (including nested records, temporals, collections) into a JsonObject.
     */
    public static void serializeValueToComponent(JsonObject target, String key, Object val) {
        if (val == null) return;
        if (val instanceof Number n) {
            target.addProperty(key, n);
        } else if (val instanceof Boolean b) {
            target.addProperty(key, b);
        } else if (val instanceof Character c) {
            target.addProperty(key, c);
        } else if (val instanceof Enum<?> e) {
            target.addProperty(key, e.name());
        } else if (val instanceof java.time.temporal.Temporal || val instanceof java.util.Date) {
            target.addProperty(key, val.toString());
        } else if (val instanceof Record rec) {
            target.add(key, recordToJson(rec));
        } else if (val instanceof JsonObject jo) {
            target.add(key, jo);
        } else if (val instanceof JsonArray ja) {
            target.add(key, ja);
        } else if (val instanceof Iterable<?> iter) {
            JsonArray arr = new JsonArray();
            for (Object item : iter) {
                if (item instanceof Number n) arr.add(n);
                else if (item instanceof Boolean b) arr.add(b);
                else if (item instanceof Record r) arr.add(recordToJson(r));
                else if (item != null) arr.add(item.toString());
            }
            target.add(key, arr);
        } else if (val.getClass().isArray()) {
            JsonArray arr = new JsonArray();
            int len = java.lang.reflect.Array.getLength(val);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(val, i);
                if (item instanceof Number n) arr.add(n);
                else if (item instanceof Boolean b) arr.add(b);
                else if (item instanceof Record r) arr.add(recordToJson(r));
                else if (item != null) arr.add(item.toString());
            }
            target.add(key, arr);
        } else {
            target.addProperty(key, val.toString());
        }
    }

    /**
     * Converts a Java Record instance recursively into a JsonObject.
     */
    public static JsonObject recordToJson(Record recordObject) {
        JsonObject json = new JsonObject();
        if (recordObject == null) return json;
        json.addProperty("_recordClass", recordObject.getClass().getName());
        try {
            RecordComponent[] components = recordObject.getClass().getRecordComponents();
            if (components != null) {
                for (RecordComponent rc : components) {
                    Object v = rc.getAccessor().invoke(recordObject);
                    if (v == null) continue;
                    String k = rc.getName();
                    serializeValueToComponent(json, k, v);
                }
            }
        } catch (Exception ignored) {}
        return json;
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

        String jsonString = gson.toJson(wrapper);
        engine.getStorageCore().put(internalKey, jsonString.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        String command = "PUT " + internalKey + " " + jsonString;
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
        engine.getStorageCore().delete(internalKey, System.currentTimeMillis());
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
