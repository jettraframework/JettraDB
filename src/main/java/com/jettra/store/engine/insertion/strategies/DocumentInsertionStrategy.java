package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.DocumentPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.DocumentEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Document / JSON storage engine.
 */
public class DocumentInsertionStrategy implements EngineRecordInsertionStrategy<DocumentPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.Document();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String coll = params.get("target_coll");
        if (coll == null || coll.isBlank()) {
            errors.add("La colección de destino es requerida.");
        }
        String payload = params.get("doc_payload");
        if (payload == null || payload.isBlank()) {
            payload = params.get("doc_json");
        }
        if (payload == null || payload.isBlank()) {
            payload = params.get("raw_payload");
        }
        if (payload == null || payload.isBlank()) {
            errors.add("El payload JSON del documento no puede estar vacío.");
        } else {
            try {
                JsonObject obj = JsonPayloadHelper.parseJsonOrEmpty(payload);
                if (obj.keySet().isEmpty() && !payload.trim().equals("{}")) {
                    errors.add("El payload no contiene una sintaxis JSON válida.");
                }
            } catch (Exception e) {
                errors.add("Error de sintaxis en el JSON: " + e.getMessage());
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public DocumentPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String coll = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "customers");
        String docId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "doc_auto");
        String docClass = params.get("doc_class");
        String rawJson = params.get("doc_payload");
        if (rawJson == null || rawJson.isBlank()) {
            rawJson = params.get("doc_json");
        }
        if (rawJson == null || rawJson.isBlank()) {
            rawJson = params.getOrDefault("raw_payload", "{}");
        }
        JsonObject doc = JsonPayloadHelper.parseJsonOrWrap(rawJson, "content");
        if (docClass != null && !docClass.isBlank()) {
            doc.addProperty("_class", docClass.trim());
        }
        return new DocumentPayload(docId, coll, docClass, doc, rawJson);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, DocumentPayload payload) {
        DocumentEngine docEngine = (DocumentEngine) storageEngine.getEngine("DOCUMENT");
        if (docEngine == null) {
            return InsertionResult.ofError("DOCUMENT", database, payload.collection(), payload.id(), "DocumentEngine not found in storage orchestrator");
        }
        docEngine.insert(database, payload.collection(), payload.id(), payload.jsonContent());
        return InsertionResult.ofSuccess("DOCUMENT", database, payload.collection(), payload.id(),
                "Document '" + payload.id() + "' successfully inserted in collection [" + payload.collection() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleJson = "{\n  \"name\": \"TechCorp Global\",\n  \"tier\": \"ENTERPRISE\",\n  \"active\": true,\n  \"contact\": {\n    \"email\": \"admin@techcorp.io\",\n    \"phone\": \"+1-800-555-0199\"\n  },\n  \"tags\": [\"saas\", \"b2b\", \"cloud\"]\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Colección de Documentos:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_doc_coll").binding("target_coll").value(currentUnit != null ? currentUnit : "customers")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Java Entity Class (_class metadata):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_doc_class").binding("doc_class").value("com.jettra.models.Customer")
                        .modifier(new Modifier().attribute("placeholder", "com.jettra.entity.MyClass").style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("insert_doc_payload", "Document JSON Body", sampleJson)
                    .name("doc_payload")
                    .height("210px")
            )
        );
    }

    @Override
    public DocumentPayload generateSamplePayload(String database, String unit) {
        String sampleJson = "{\"name\":\"Apex Logistics\",\"status\":\"ACTIVE\",\"metrics\":{\"latencyMs\":12.4,\"requests\":4500}}";
        JsonObject json = JsonPayloadHelper.parseJsonOrWrap(sampleJson, "data");
        return new DocumentPayload("apex_001", unit != null ? unit : "customers", "com.jettra.model.Enterprise", json, sampleJson);
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "customers");
        map.put("target_id", "cust_apex_99");
        map.put("doc_class", "com.jettra.models.EnterpriseCustomer");
        map.put("doc_payload", "{\n  \"company\": \"Apex Logistics Worldwide\",\n  \"tier\": \"PLATINUM\",\n  \"sla\": \"99.99%\",\n  \"billing\": {\n    \"currency\": \"USD\",\n    \"limit\": 500000\n  },\n  \"enabledFeatures\": [\"ha_cluster\", \"geo_replication\", \"ai_vector_search\"]\n}");
        return map;
    }
}
