package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.KeyValuePayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.KeyValueEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Key-Value storage engine.
 */
public class KeyValueInsertionStrategy implements EngineRecordInsertionStrategy<KeyValuePayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.KeyValue();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String key = params.get("target_id");
        if (key == null || key.isBlank()) {
            key = params.get("kv_key");
        }
        if (key == null || key.isBlank()) {
            errors.add("El campo 'Key' es obligatorio para el motor Key-Value.");
        }
        String val = params.get("kv_value");
        if (val == null) {
            errors.add("El valor 'Value' no puede ser nulo.");
        }
        String ttlStr = params.get("kv_ttl");
        if (ttlStr != null && !ttlStr.isBlank()) {
            try {
                long ttl = Long.parseLong(ttlStr.trim());
                if (ttl < 0) errors.add("El TTL debe ser un valor positivo en segundos.");
            } catch (NumberFormatException e) {
                errors.add("El TTL debe ser un número entero válido (segundos).");
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public KeyValuePayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String key = (id != null && !id.isBlank()) ? id : params.getOrDefault("kv_key", "sample_key");
        String value = params.getOrDefault("kv_value", "");
        Long ttl = null;
        String ttlStr = params.get("kv_ttl");
        if (ttlStr != null && !ttlStr.isBlank()) {
            try { ttl = Long.parseLong(ttlStr.trim()); } catch (Exception ignored) {}
        }
        return new KeyValuePayload(unit != null ? unit : "default", key, value, ttl);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, KeyValuePayload payload) {
        KeyValueEngine kvEngine = (KeyValueEngine) storageEngine.getEngine("KEYVALUE");
        if (kvEngine == null) {
            return InsertionResult.ofError("KEYVALUE", database, payload.namespace(), payload.key(), "KeyValueEngine not registered in storage orchestrator");
        }
        String resolvedKey = (payload.namespace().equals("default") || payload.key().contains(":"))
                ? payload.key()
                : payload.namespace() + ":" + payload.key();

        kvEngine.put(database, resolvedKey, payload.value());
        return InsertionResult.ofSuccess("KEYVALUE", database, payload.namespace(), payload.key(),
                "Key '" + resolvedKey + "' successfully stored in namespace [" + payload.namespace() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Namespace / Bucket:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_kv_namespace").binding("target_coll").value(currentUnit != null ? currentUnit : "session_cache")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("TTL (Segundos - Opcional):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_kv_ttl").binding("kv_ttl").value("")
                        .modifier(new Modifier().attribute("placeholder", "Ej. 3600 (0 o vacío = Sin expiración)").style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Valor / Contenido:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.of("").id("insert_kv_value").binding("kv_value")
                    .modifier(new Modifier().attribute("rows", "5").attribute("placeholder", "Ingrese cadena, JSON o datos a almacenar...")
                        .style("width:100%; padding:10px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:12px; resize:vertical;"))
            ).modifier(new Modifier().style("margin-bottom:6px;")),

            Paragraph.of(Text.of("Soporta tipos primitivos, cadenas Base64 y payloads serializados."))
                .modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); margin:0;"))
        );
    }

    @Override
    public KeyValuePayload generateSamplePayload(String database, String unit) {
        return new KeyValuePayload(unit != null ? unit : "user_sessions", "usr_token_9841", "{\"userId\":10842,\"role\":\"ADMIN\",\"ip\":\"192.168.1.10\"}", 3600L);
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "user_sessions");
        map.put("target_id", "usr_token_9841");
        map.put("kv_ttl", "3600");
        map.put("kv_value", "{\"userId\":10842,\"role\":\"ADMIN\",\"token\":\"eyJhbGciOiJIUzI1NiJ9\",\"active\":true}");
        return map;
    }
}
