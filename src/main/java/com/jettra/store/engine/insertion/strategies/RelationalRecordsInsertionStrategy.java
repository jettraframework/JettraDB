package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.RelationalRecordPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.RecordModelPayloadHandler;
import com.jettra.store.engine.insertion.RecordModelPayloadHandler.CanonicalRecord;
import com.jettra.store.engine.insertion.ValidationResult;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.JettraFluxRecordForm;
import io.jettra.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy implementation for Relational / Tabular (Java 25 Records) storage engine.
 * Dispatches canonical immutable Record schemas (_recordClass, _timestamp, _version, _schema, components)
 * to JettraDB RecordsEngine with zero-copy I/O compaction.
 */
public class RelationalRecordsInsertionStrategy implements EngineRecordInsertionStrategy<RelationalRecordPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.RelationalRecords();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        return RecordModelPayloadHandler.validateParameters(params);
    }

    @Override
    public RelationalRecordPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        CanonicalRecord canonical = RecordModelPayloadHandler.buildCanonicalRecord(database, unit, id, params);

        JsonObject compJson = new JsonObject();
        for (Map.Entry<String, Object> entry : canonical.components().entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Number n) {
                compJson.addProperty(entry.getKey(), n);
            } else if (val instanceof Boolean b) {
                compJson.addProperty(entry.getKey(), b);
            } else if (val != null) {
                compJson.addProperty(entry.getKey(), val.toString());
            }
        }

        JsonObject schemaJson = new JsonObject();
        for (Map.Entry<String, String> entry : canonical.schema().entrySet()) {
            schemaJson.addProperty(entry.getKey(), entry.getValue());
        }

        String rawJson = canonical.toCompactJson();
        return new RelationalRecordPayload(
                canonical.table(),
                canonical.recordId(),
                canonical.recordClass(),
                compJson,
                schemaJson,
                rawJson,
                canonical.version(),
                canonical.timestamp()
        );
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, RelationalRecordPayload payload) {
        Map<String, Object> comps = new LinkedHashMap<>();
        if (payload.columns() != null) {
            for (Map.Entry<String, Object> entry : payload.columns().entrySet()) {
                comps.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, String> schema = new LinkedHashMap<>();
        if (payload.schema() != null && !payload.schema().getMap().isEmpty()) {
            for (Map.Entry<String, Object> entry : payload.schema().entrySet()) {
                schema.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "String");
            }
        } else {
            // Infer schema if missing
            for (Map.Entry<String, Object> entry : comps.entrySet()) {
                schema.put(entry.getKey(), RecordModelPayloadHandler.inferType(entry.getValue()));
            }
        }

        CanonicalRecord canonical = new CanonicalRecord(
                payload.recordClass(),
                payload.timestamp(),
                payload.version(),
                schema,
                comps,
                payload.table(),
                payload.recordId()
        );

        return RecordModelPayloadHandler.dispatchToEngine(storageEngine, database, canonical);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String activeUnit = (currentUnit != null && !currentUnit.isBlank() && !"default".equalsIgnoreCase(currentUnit))
                ? currentUnit
                : "employees";

        return JettraFluxRecordForm.of("insert_rec", "com.jettra.model.EmployeeRecord", activeUnit)
                .sampleEmployeeRecord();
    }

    @Override
    public RelationalRecordPayload generateSamplePayload(String database, String unit) {
        String table = (unit != null && !unit.isBlank()) ? unit : "employees";
        JsonObject schema = new JsonObject();
        schema.addProperty("first_name", "String");
        schema.addProperty("last_name", "String");
        schema.addProperty("email", "String");
        schema.addProperty("age", "Integer");
        schema.addProperty("salary", "Double");
        schema.addProperty("department", "String");
        schema.addProperty("created_at", "String");
        schema.addProperty("_table", "String");

        JsonObject comp = new JsonObject();
        comp.addProperty("first_name", "John");
        comp.addProperty("last_name", "Doe");
        comp.addProperty("email", "john.doe@company.org");
        comp.addProperty("age", 34);
        comp.addProperty("salary", 85000.0);
        comp.addProperty("department", "ENGINEERING");
        comp.addProperty("created_at", "2026-09-07T10:00:00Z");
        comp.addProperty("_table", table);

        JsonObject root = new JsonObject();
        root.addProperty("_recordClass", "com.jettra.model.EmployeeRecord");
        root.addProperty("_timestamp", System.currentTimeMillis());
        root.addProperty("_version", 1L);
        root.add("_schema", schema);
        root.add("components", comp);

        String json = root.toString();
        return new RelationalRecordPayload(table, "emp_1042", "com.jettra.model.EmployeeRecord", comp, schema, json, 1L, System.currentTimeMillis());
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        String table = (unit != null && !unit.isBlank()) ? unit : "employees";
        map.put("target_coll", table);
        map.put("target_id", "emp_1042");
        map.put("rec_class", "com.jettra.model.EmployeeRecord");
        map.put("rec_payload", "{\n"
                + "  \"_recordClass\": \"com.jettra.model.EmployeeRecord\",\n"
                + "  \"_timestamp\": 1788809869770,\n"
                + "  \"_version\": 1,\n"
                + "  \"_schema\": {\n"
                + "    \"first_name\": \"String\",\n"
                + "    \"last_name\": \"String\",\n"
                + "    \"email\": \"String\",\n"
                + "    \"age\": \"Integer\",\n"
                + "    \"salary\": \"Double\",\n"
                + "    \"department\": \"String\",\n"
                + "    \"created_at\": \"String\",\n"
                + "    \"_table\": \"String\"\n"
                + "  },\n"
                + "  \"components\": {\n"
                + "    \"first_name\": \"John\",\n"
                + "    \"last_name\": \"Doe\",\n"
                + "    \"email\": \"john.doe@company.org\",\n"
                + "    \"age\": 34,\n"
                + "    \"salary\": 85000,\n"
                + "    \"department\": \"ENGINEERING\",\n"
                + "    \"created_at\": \"2026-09-07T10:00:00Z\",\n"
                + "    \"_table\": \"" + table + "\"\n"
                + "  }\n"
                + "}");
        return map;
    }
}
