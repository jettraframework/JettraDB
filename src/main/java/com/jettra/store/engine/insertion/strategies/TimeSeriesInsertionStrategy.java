package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.TimeSeriesPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.TimeSeriesEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for IoT Metrics and Time-Series telemetry engine.
 */
public class TimeSeriesInsertionStrategy implements EngineRecordInsertionStrategy<TimeSeriesPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.TimeSeries();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String metric = params.get("target_coll");
        if (metric == null || metric.isBlank()) metric = params.get("ts_metric");
        if (metric == null || metric.isBlank()) {
            errors.add("El nombre de la serie / métrica es obligatorio.");
        }
        String valStr = params.get("ts_value");
        if (valStr == null || valStr.isBlank()) {
            errors.add("El valor numérico de la métrica es obligatorio.");
        } else {
            try {
                Double.parseDouble(valStr.trim());
            } catch (NumberFormatException e) {
                errors.add("El valor debe ser un número decimal válido (ej: 24.5).");
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public TimeSeriesPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String metric = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "cpu_usage");
        long timestamp = System.currentTimeMillis();
        String rawTs = params.get("ts_timestamp");
        if (rawTs != null && !rawTs.isBlank()) {
            try {
                if (rawTs.contains("-") || rawTs.contains("T")) {
                    timestamp = Instant.parse(rawTs.trim()).toEpochMilli();
                } else {
                    timestamp = Long.parseLong(rawTs.trim());
                }
            } catch (Exception ignored) {}
        }
        double val = 0.0;
        try {
            val = Double.parseDouble(params.getOrDefault("ts_value", "0.0").trim());
        } catch (Exception ignored) {}

        String unitStr = params.getOrDefault("ts_unit", "");
        String rawTags = params.getOrDefault("ts_tags", "{}");
        JsonObject dp = JsonPayloadHelper.parseJsonOrWrap(rawTags, "tags");
        dp.addProperty("value", val);
        dp.addProperty("metric", metric);
        if (!unitStr.isBlank()) dp.addProperty("unit", unitStr);

        return new TimeSeriesPayload(metric, timestamp, val, unitStr, dp, rawTags);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, TimeSeriesPayload payload) {
        TimeSeriesEngine tsEngine = (TimeSeriesEngine) storageEngine.getEngine("TIMESERIES");
        if (tsEngine == null) {
            return InsertionResult.ofError("TIMESERIES", database, payload.metric(), payload.entityId(), "TimeSeriesEngine not registered in storage orchestrator");
        }
        tsEngine.insert(database, payload.timestamp(), payload.tags());
        return InsertionResult.ofSuccess("TIMESERIES", database, payload.metric(), payload.entityId(),
                "Time point recorded for [" + payload.metric() + "]: " + payload.value() + " " + payload.unit() + " @ " + payload.timestamp(), 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleTags = "{\n  \"device_id\": \"sensor_iot_alpha_12\",\n  \"datacenter\": \"us-east-1\",\n  \"env\": \"production\",\n  \"firmware\": \"v4.1.2\"\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Nombre de Serie / Métrica:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_ts_metric").binding("target_coll").value(currentUnit != null ? currentUnit : "server_temperature")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1.2;")),
                Div.of(
                    Label.of("Valor Numérico (Double):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_ts_value").binding("ts_value").value("64.8")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Unidad de Medida:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_ts_unit").binding("ts_unit").value("°C")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:0.8;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Div.of(
                    Label.of("Timestamp (Milisegundos Epoch o ISO-8601):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    Div.of(
                        TextField.of().id("insert_ts_timestamp").binding("ts_timestamp").value(String.valueOf(System.currentTimeMillis()))
                            .modifier(new Modifier().style("flex:1; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;")),
                        Button.of(Icon.of("fas fa-clock"), Text.of(" Ahora"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('insert_ts_timestamp').value = Date.now();")
                                .style("padding:8px 12px; font-size:11px; background:var(--j-bg-subsurface); color:var(--j-text-primary); border:1px solid var(--j-border); border-radius:6px; cursor:pointer;"))
                    ).modifier(new Modifier().style("display:flex; gap:8px;"))
                ).modifier(new Modifier().style("margin-bottom:12px;"))
            ),

            Div.of(
                JettraFluxJsonEditor.of("insert_ts_tags", "Tags / Dimensiones / Metadata IoT (JSON)", sampleTags)
                    .name("ts_tags").height("150px")
            )
        );
    }

    @Override
    public TimeSeriesPayload generateSamplePayload(String database, String unit) {
        long now = System.currentTimeMillis();
        JsonObject tags = new JsonObject();
        tags.addProperty("sensor_id", "thermo_bay_4");
        tags.addProperty("zone", "datacenter_core");
        return new TimeSeriesPayload(unit != null ? unit : "temperature_celsius", now, 23.4, "°C", tags, tags.toString());
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "temperature_metrics");
        map.put("ts_value", "72.4");
        map.put("ts_unit", "°C");
        map.put("ts_timestamp", String.valueOf(System.currentTimeMillis()));
        map.put("ts_tags", "{\n  \"host\": \"cluster-node-04\",\n  \"core\": 8,\n  \"cluster\": \"production-us-west\"\n}");
        return map;
    }
}
