package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.WideColumnPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.ColumnEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Wide-Column analytical storage engine.
 */
public class WideColumnInsertionStrategy implements EngineRecordInsertionStrategy<WideColumnPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.WideColumn();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String family = params.get("target_coll");
        if (family == null || family.isBlank()) family = params.get("col_family");
        if (family == null || family.isBlank()) {
            errors.add("El nombre de la Column Family es requerido.");
        }
        String rowKey = params.get("target_id");
        if (rowKey == null || rowKey.isBlank()) rowKey = params.get("col_row_key");
        if (rowKey == null || rowKey.isBlank()) {
            errors.add("El Row Key es obligatorio.");
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public WideColumnPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String family = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "user_profiles");
        String rowKey = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "row_usr_101");
        String qualifier = params.getOrDefault("col_qualifier", "info");
        String cellVal = params.getOrDefault("col_value", "");
        Long ts = System.currentTimeMillis();
        String tsStr = params.get("col_timestamp");
        if (tsStr != null && !tsStr.isBlank()) {
            try { ts = Long.parseLong(tsStr.trim()); } catch (Exception ignored) {}
        }
        String rawCols = params.getOrDefault("col_data", "{}");
        JsonObject rowObj = JsonPayloadHelper.parseJsonOrWrap(rawCols, "data");
        rowObj.addProperty("_family", family);
        if (!qualifier.isBlank() && !cellVal.isBlank() && !rowObj.has(qualifier)) {
            rowObj.addProperty(qualifier, cellVal);
        }

        return new WideColumnPayload(rowKey, family, qualifier, ts, cellVal, rowObj, rawCols);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, WideColumnPayload payload) {
        ColumnEngine colEngine = (ColumnEngine) storageEngine.getEngine("COLUMN");
        if (colEngine == null) {
            return InsertionResult.ofError("COLUMN", database, payload.columnFamily(), payload.rowKey(), "ColumnEngine not registered in storage orchestrator");
        }
        colEngine.insertRow(database, payload.rowKey(), payload.columns());
        return InsertionResult.ofSuccess("COLUMN", database, payload.columnFamily(), payload.rowKey(),
                "Wide-column row '" + payload.rowKey() + "' saved under Family [" + payload.columnFamily() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleColumns = "{\n  \"basic:username\": \"alovelace\",\n  \"basic:email\": \"ada@analytical.org\",\n  \"metrics:login_count\": 42,\n  \"metrics:last_ip\": \"10.0.4.12\",\n  \"flags:verified\": true\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Column Family:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_col_family").binding("target_coll").value(currentUnit != null ? currentUnit : "user_analytics")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Column Qualifier (Opcional):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_col_qualifier").binding("col_qualifier").value("profile:full")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("insert_col_data", "Dynamic Columns & Qualifier Map (JSON)", sampleColumns)
                    .name("col_data").height("180px")
            )
        );
    }

    @Override
    public WideColumnPayload generateSamplePayload(String database, String unit) {
        JsonObject cols = new JsonObject();
        cols.addProperty("stat:views", 12040);
        cols.addProperty("stat:shares", 312);
        cols.addProperty("_family", unit != null ? unit : "post_analytics");
        return new WideColumnPayload("row_post_89", unit != null ? unit : "post_analytics", "stats", System.currentTimeMillis(), "active", cols, cols.toString());
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "user_analytics");
        map.put("target_id", "row_usr_101");
        map.put("col_qualifier", "activity");
        map.put("col_data", "{\n  \"activity:logins\": 84,\n  \"activity:last_login\": \"2026-09-07T08:30:00Z\",\n  \"tier:level\": \"GOLD\"\n}");
        return map;
    }
}
