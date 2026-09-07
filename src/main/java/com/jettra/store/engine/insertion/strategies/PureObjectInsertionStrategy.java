package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.PureObjectPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.ObjectEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Pure Java Objects and Binary Large Object (BLOB) buckets engine.
 */
public class PureObjectInsertionStrategy implements EngineRecordInsertionStrategy<PureObjectPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.PureObject();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String bucket = params.get("target_coll");
        if (bucket == null || bucket.isBlank()) bucket = params.get("obj_bucket");
        if (bucket == null || bucket.isBlank()) {
            errors.add("El nombre del Bucket de objetos es requerido.");
        }
        String id = params.get("target_id");
        if (id == null || id.isBlank()) id = params.get("obj_id");
        if (id == null || id.isBlank()) {
            errors.add("El ID de objeto es obligatorio.");
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public PureObjectPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String bucket = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "media_assets");
        String objId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "blob_obj_01");
        String className = params.getOrDefault("obj_class", "com.jettra.blob.BinaryPayload");
        String mime = params.getOrDefault("obj_mime", "application/json");
        String content = params.getOrDefault("obj_payload", "");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        return new PureObjectPayload(objId, bucket, className, mime, content, bytes);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, PureObjectPayload payload) {
        ObjectEngine objEngine = (ObjectEngine) storageEngine.getEngine("OBJECT");
        if (objEngine == null) {
            return InsertionResult.ofError("OBJECT", database, payload.bucket(), payload.id(), "ObjectEngine not registered in storage orchestrator");
        }
        JsonObject state = new JsonObject();
        state.addProperty("mimeType", payload.mimeType());
        state.addProperty("bucket", payload.bucket());
        state.addProperty("sizeBytes", payload.contentBytes().length);
        state.addProperty("content", payload.contentText());

        objEngine.saveObject(database, payload.id(), payload.className(), state);
        return InsertionResult.ofSuccess("OBJECT", database, payload.bucket(), payload.id(),
                "Binary Object '" + payload.id() + "' (" + payload.contentBytes().length + " bytes) saved in bucket [" + payload.bucket() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Bucket Name:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_obj_bucket").binding("target_coll").value(currentUnit != null ? currentUnit : "media_bucket")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Canonical Java Object Class:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_obj_class").binding("obj_class").value("com.jettra.storage.BinaryBlob")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("MIME / Content Type:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_obj_mime").binding("obj_mime").value("application/json")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Contenido / Payload Serializado (Texto / Base64 / JSON):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.of("").id("insert_obj_payload").binding("obj_payload")
                    .modifier(new Modifier().attribute("rows", "6").attribute("placeholder", "Escriba el contenido serializado del objeto...")
                        .style("width:100%; padding:10px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:12px; resize:vertical;"))
            )
        );
    }

    @Override
    public PureObjectPayload generateSamplePayload(String database, String unit) {
        String content = "{\"asset\":\"banner.png\",\"width\":1920,\"height\":1080,\"encoding\":\"binary\"}";
        return new PureObjectPayload("obj_asset_77", unit != null ? unit : "static_media", "com.jettra.storage.MediaFile", "application/json", content, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "media_bucket");
        map.put("target_id", "blob_obj_771");
        map.put("obj_class", "com.jettra.storage.MediaFile");
        map.put("obj_mime", "application/json");
        map.put("obj_payload", "{\n  \"filename\": \"architecture_diagram.webp\",\n  \"size\": 1048576,\n  \"checksum\": \"sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069\"\n}");
        return map;
    }
}
