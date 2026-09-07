package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.RelationalRecordPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.RecordsEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Relational / Tabular (Java 25 Records) storage engine.
 */
public class RelationalRecordsInsertionStrategy implements EngineRecordInsertionStrategy<RelationalRecordPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.RelationalRecords();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String table = params.get("target_coll");
        if (table == null || table.isBlank()) {
            errors.add("El nombre de la tabla relacional es obligatorio.");
        }
        String payload = params.get("rec_payload");
        if (payload == null || payload.isBlank()) {
            errors.add("Las columnas del registro relacional no pueden estar vacías.");
        } else {
            try {
                JsonObject obj = JsonPayloadHelper.parseJsonOrEmpty(payload);
                if (obj.keySet().isEmpty() && !payload.trim().equals("{}")) {
                    errors.add("El esquema de columnas debe ser un objeto JSON válido.");
                }
            } catch (Exception e) {
                errors.add("Error de sintaxis en columnas: " + e.getMessage());
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public RelationalRecordPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String table = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "person_records");
        String recId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "rec_001");
        String recClass = params.getOrDefault("rec_class", "com.jettra.model.PersonRecord");
        String rawJson = params.getOrDefault("rec_payload", "{}");
        JsonObject cols = JsonPayloadHelper.parseJsonOrWrap(rawJson, "columns");
        cols.addProperty("_table", table);
        return new RelationalRecordPayload(table, recId, recClass, cols, rawJson);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, RelationalRecordPayload payload) {
        RecordsEngine recEngine = (RecordsEngine) storageEngine.getEngine("RECORDS");
        if (recEngine == null) {
            return InsertionResult.ofError("RECORDS", database, payload.table(), payload.recordId(), "RecordsEngine not registered in storage orchestrator");
        }
        String table = (payload.table() != null && !payload.table().isBlank() && !payload.table().equalsIgnoreCase("default"))
                ? database + ":" + payload.table().trim()
                : database;
        recEngine.saveRecord(table, payload.recordId(), payload.recordClass(), payload.columns());
        return InsertionResult.ofSuccess("RECORDS", database, payload.table(), payload.recordId(),
                "Record '" + payload.recordId() + "' stored in relational table [" + payload.table() + "] (Type: " + payload.recordClass() + ")", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleColumns = "{\n  \"first_name\": \"John\",\n  \"last_name\": \"Doe\",\n  \"email\": \"john.doe@company.org\",\n  \"age\": 34,\n  \"salary\": 85000.0,\n  \"department\": \"ENGINEERING\",\n  \"created_at\": \"2026-09-07T10:00:00Z\"\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Nombre de Tabla (Table):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_rec_table").binding("target_coll").value(currentUnit != null ? currentUnit : "employees")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Java 25 Record Canonical Class:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_rec_class").binding("rec_class").value("com.jettra.model.EmployeeRecord")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("insert_rec_payload", "Typed Columns & SQL Field Definitions", sampleColumns)
                    .name("rec_payload")
                    .height("200px")
            )
        );
    }

    @Override
    public RelationalRecordPayload generateSamplePayload(String database, String unit) {
        String json = "{\"id\":501,\"firstName\":\"Elena\",\"lastName\":\"Rostova\",\"role\":\"Principal Architect\",\"active\":true}";
        JsonObject obj = JsonPayloadHelper.parseJsonOrWrap(json, "data");
        return new RelationalRecordPayload(unit != null ? unit : "personnel", "emp_501", "com.jettra.models.StaffRecord", obj, json);
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "employees");
        map.put("target_id", "emp_1042");
        map.put("rec_class", "com.jettra.model.EmployeeRecord");
        map.put("rec_payload", "{\n  \"emp_id\": 1042,\n  \"name\": \"Elena Rostova\",\n  \"role\": \"Principal Architect\",\n  \"salary\": 135000.0,\n  \"hired_date\": \"2024-03-15\",\n  \"is_remote\": true\n}");
        return map;
    }
}
