package com.jettra.store.engine.operations.export;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Strategy implementation for JSON formatted export.
 * Preserves multi-model hierarchical graphs, nested documents, and metadata.
 */
public final class JsonExportStrategy implements ExportStrategy {

    private static final JettraJson JSON_PARSER = new JettraJson();

    @Override
    public String format() {
        return "json";
    }

    @Override
    public String displayName() {
        return "JSON (.json) - Full Multimodel Object Graph";
    }

    @Override
    public String mimeType() {
        return "application/json; charset=UTF-8";
    }

    @Override
    public String fileExtension() {
        return "json";
    }

    @Override
    public byte[] export(String database, String engineFilter, String collectionFilter, Map<String, String> recordsMap) {
        JsonObject root = new JsonObject();
        root.addProperty("_database", database);
        root.addProperty("_engineFilter", engineFilter != null ? engineFilter : "ALL");
        root.addProperty("_collectionFilter", (collectionFilter == null || collectionFilter.isBlank()) ? "*" : collectionFilter);
        root.addProperty("_exportedAt", System.currentTimeMillis());
        root.addProperty("_totalRecords", recordsMap != null ? recordsMap.size() : 0);

        JsonObject recordsObj = new JsonObject();
        if (recordsMap != null) {
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                try {
                    JsonObject obj = JSON_PARSER.fromJson(entry.getValue(), JsonObject.class);
                    if (obj != null) {
                        recordsObj.add(entry.getKey(), obj);
                    } else {
                        recordsObj.addProperty(entry.getKey(), entry.getValue());
                    }
                } catch (Exception ex) {
                    recordsObj.addProperty(entry.getKey(), entry.getValue());
                }
            }
        }
        root.add("records", recordsObj);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
