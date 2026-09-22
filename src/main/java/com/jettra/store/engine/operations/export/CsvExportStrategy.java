package com.jettra.store.engine.operations.export;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Strategy implementation for CSV (Comma-Separated Values) formatted tabular export.
 */
public final class CsvExportStrategy implements ExportStrategy {

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String displayName() {
        return "CSV (.csv) - Comma-Separated Values Table";
    }

    @Override
    public String mimeType() {
        return "text/csv; charset=UTF-8";
    }

    @Override
    public String fileExtension() {
        return "csv";
    }

    @Override
    public byte[] export(String database, String engineFilter, String collectionFilter, Map<String, String> recordsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("Key,Database,Collection_Unit,ID,Payload\n");
        if (recordsMap != null) {
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                String k = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue().replace("\"", "\"\"") : "";
                String[] parts = k.split(":");
                String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                String id = parts.length > 0 ? parts[parts.length - 1] : k;
                sb.append("\"").append(k).append("\",")
                  .append("\"").append(database).append("\",")
                  .append("\"").append(unit).append("\",")
                  .append("\"").append(id).append("\",")
                  .append("\"").append(val).append("\"\n");
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
