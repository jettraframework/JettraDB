package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.VectorPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.VectorEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for AI Vector Embeddings and Similarity Search engine.
 */
public class VectorInsertionStrategy implements EngineRecordInsertionStrategy<VectorPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.Vector();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String rawVec = params.get("vector_coords");
        if (rawVec == null || rawVec.isBlank()) {
            errors.add("Las coordenadas / embeddings del vector son requeridas (ej: 0.12, 0.45, 0.88, 0.31).");
        } else {
            float[] floats = JsonPayloadHelper.parseFloats(rawVec);
            if (floats.length == 0) {
                errors.add("No se detectaron valores numéricos válidos en el array de embeddings.");
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public VectorPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String index = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "kb_embeddings");
        String vecId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "vec_doc_01");
        String rawCoords = params.getOrDefault("vector_coords", "0.12, 0.45, 0.88, 0.31");
        float[] floats = JsonPayloadHelper.parseFloats(rawCoords);
        int dim = floats.length;
        String dimStr = params.get("vector_dimension");
        if (dimStr != null && !dimStr.isBlank()) {
            try { dim = Integer.parseInt(dimStr.trim()); } catch (Exception ignored) {}
        }
        String metric = params.getOrDefault("vector_metric", "COSINE");
        String label = params.getOrDefault("vector_label", "default");
        String metaStr = params.getOrDefault("vector_meta", "{}");
        JsonObject meta = JsonPayloadHelper.parseJsonOrWrap(metaStr, "metadata");
        meta.addProperty("label", label);
        meta.addProperty("dimension", dim);
        meta.addProperty("metric", metric);
        meta.addProperty("_index", index);

        return new VectorPayload(vecId, index, dim, floats, metric, label, meta, metaStr);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, VectorPayload payload) {
        VectorEngine vecEngine = (VectorEngine) storageEngine.getEngine("VECTOR");
        if (vecEngine == null) {
            return InsertionResult.ofError("VECTOR", database, payload.index(), payload.id(), "VectorEngine not registered in storage orchestrator");
        }
        vecEngine.insertVector(database, payload.id(), payload.embeddings(), payload.metadata());
        return InsertionResult.ofSuccess("VECTOR", database, payload.index(), payload.id(),
                "Vector embedding '" + payload.id() + "' (Dim: " + payload.dimension() + ", Metric: " + payload.distanceMetric() + ") indexed in [" + payload.index() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Índice Vectorial (Index):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_vec_index").binding("target_coll").value(currentUnit != null ? currentUnit : "semantic_index")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Métrica de Distancia:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    RawHtml.of("<select name=\"vector_metric\" id=\"insert_vec_metric\" style=\"width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;\">" +
                            "<option value=\"COSINE\" selected>Cosine Similarity</option>" +
                            "<option value=\"EUCLIDEAN\">Euclidean (L2)</option>" +
                            "<option value=\"DOT_PRODUCT\">Dot Product (Inner Product)</option>" +
                            "<option value=\"MANHATTAN\">Manhattan (L1)</option></select>")
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Etiqueta / Clase Semántica:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_vec_label").binding("vector_label").value("documentation_v2")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Array de Embeddings (float[] flotantes separados por coma):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.of("0.0824, -0.4121, 0.9312, 0.1456, -0.0219, 0.5123, -0.2874, 0.6391").id("insert_vec_coords").binding("vector_coords")
                    .modifier(new Modifier().attribute("rows", "3")
                        .style("width:100%; padding:10px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#8b5cf6; font-family:monospace; font-size:12px; resize:vertical;"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("insert_vec_meta", "Metadatos Asociados (JSON)", "{\n  \"source_doc\": \"architecture_guide.md\",\n  \"chunk_id\": 42,\n  \"token_count\": 128,\n  \"author\": \"DeepMind Lead\"\n}")
                    .name("vector_meta").height("150px")
            )
        );
    }

    @Override
    public VectorPayload generateSamplePayload(String database, String unit) {
        float[] floats = new float[]{0.05f, 0.91f, -0.32f, 0.74f, -0.11f, 0.44f};
        JsonObject meta = new JsonObject();
        meta.addProperty("source", "knowledge_base");
        meta.addProperty("category", "database_internals");
        return new VectorPayload("vec_kb_789", unit != null ? unit : "ai_kb", 6, floats, "COSINE", "internals", meta, meta.toString());
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "ai_embeddings");
        map.put("target_id", "vec_kb_789");
        map.put("vector_metric", "COSINE");
        map.put("vector_label", "database_internals");
        map.put("vector_coords", "0.052, 0.912, -0.321, 0.745, -0.118, 0.442, 0.812, -0.054");
        map.put("vector_meta", "{\n  \"title\": \"JettraDB Storage Architecture\",\n  \"page\": 14,\n  \"language\": \"es\",\n  \"confidence\": 0.98\n}");
        return map;
    }
}
