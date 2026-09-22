package com.jettra.store.engine.operations;

import com.jettra.store.engine.core.DatabaseBackupManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Service coordinating engine lifecycle operations (Backup, Restore, Export) across
 * multi-model storage partitions in JettraDB.
 * Leverages Java 25+ pattern matching, Records, and robust exception handling.
 */
public class EngineOperationService {

    private final JettraStorageEngine engine;
    private final JettraJson jsonParser = new JettraJson();

    public EngineOperationService(JettraStorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "JettraStorageEngine must not be null");
    }

    /**
     * Executes the requested operation task using Java 25 pattern matching.
     */
    public EngineOperationResult execute(EngineOperationTask task) {
        Objects.requireNonNull(task, "EngineOperationTask must not be null");
        long start = System.currentTimeMillis();
        try {
            return switch (task.type()) {
                case BACKUP -> executeBackup(task, start);
                case RESTORE -> executeRestore(task, start);
                case EXPORT -> executeExport(task, start);
            };
        } catch (EngineOperationException e) {
            long duration = System.currentTimeMillis() - start;
            return EngineOperationResult.failure(task.type(), task.database(), e.getMessage(), e.toString(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = "Unexpected error executing " + task.type() + " on database '" + task.database() + "': " + e.getMessage();
            return EngineOperationResult.failure(task.type(), task.database(), msg, e.toString(), duration);
        }
    }

    private EngineOperationResult executeBackup(EngineOperationTask task, long start) {
        try {
            String dir = (task.destinationDirectory() != null && !task.destinationDirectory().isBlank())
                    ? task.destinationDirectory()
                    : null;
            String fn = (task.fileName() != null && !task.fileName().isBlank())
                    ? task.fileName()
                    : null;

            DatabaseBackupManager.BackupOperationResult res = DatabaseBackupManager.createDatabaseBackup(
                    engine, task.database(), dir, fn
            );

            long duration = System.currentTimeMillis() - start;
            if (res.success()) {
                return EngineOperationResult.success(
                        EngineOperationType.BACKUP,
                        task.database(),
                        res.message(),
                        res.keyCount(),
                        res.sizeBytes(),
                        res.filePath(),
                        "application/zip",
                        null,
                        duration
                );
            } else {
                throw new EngineOperationException(EngineOperationType.BACKUP, task.database(), res.message());
            }
        } catch (EngineOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineOperationException(EngineOperationType.BACKUP, task.database(), "Backup failed: " + e.getMessage(), e);
        }
    }

    private EngineOperationResult executeRestore(EngineOperationTask task, long start) {
        try {
            if (task.sourceFilePath() == null || task.sourceFilePath().isBlank()) {
                throw new IllegalArgumentException("Restore source archive file path is required");
            }

            DatabaseBackupManager.RestoreOperationResult res = DatabaseBackupManager.restoreDatabaseBackup(
                    engine, task.database(), task.sourceFilePath()
            );

            long duration = System.currentTimeMillis() - start;
            if (res.success()) {
                return EngineOperationResult.success(
                        EngineOperationType.RESTORE,
                        task.database(),
                        res.message(),
                        res.keyCount(),
                        0L,
                        task.sourceFilePath(),
                        "application/zip",
                        null,
                        duration
                );
            } else {
                throw new EngineOperationException(EngineOperationType.RESTORE, task.database(), res.message());
            }
        } catch (EngineOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineOperationException(EngineOperationType.RESTORE, task.database(), "Restore failed: " + e.getMessage(), e);
        }
    }

    public EngineOperationResult executeExport(EngineOperationTask task) {
        return executeExport(task, System.currentTimeMillis());
    }

    private EngineOperationResult executeExport(EngineOperationTask task, long start) {
        try {
            String db = task.database();
            String eng = task.engineType();
            String coll = task.collection();
            String format = task.exportFormat();

            Map<String, String> recordsMap = new LinkedHashMap<>();
            String[] prefixes;
            if ("ALL".equalsIgnoreCase(eng) || eng.isBlank()) {
                prefixes = new String[]{
                    "rec:" + db + ":", "doc:" + db + ":", "vec:" + db + ":",
                    "graph:" + db + ":", "ts:" + db + ":", "col:" + db + ":",
                    "kv:" + db + ":", "geo:" + db + ":", "obj:" + db + ":", db + ":"
                };
            } else {
                String pfx = resolveEnginePrefix(eng);
                prefixes = new String[]{pfx + db + ":", db + ":"};
            }

            for (String p : prefixes) {
                Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(p);
                for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                    String k = e.getKey();
                    if (k.contains("@")) continue;
                    if (!coll.isBlank() && !k.contains(":" + coll + ":") && !k.contains(":" + coll)) continue;
                    recordsMap.put(k, new String(e.getValue(), StandardCharsets.UTF_8));
                }
            }

            byte[] outputBytes;
            String contentType;
            String fileExt;

            if ("csv".equalsIgnoreCase(format)) {
                contentType = "text/csv; charset=UTF-8";
                fileExt = "csv";
                StringBuilder sb = new StringBuilder();
                sb.append("Key,Database,Collection_Unit,ID,Payload\n");
                for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                    String k = entry.getKey();
                    String val = entry.getValue().replace("\"", "\"\"");
                    String[] parts = k.split(":");
                    String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                    String id = parts.length > 0 ? parts[parts.length - 1] : k;
                    sb.append("\"").append(k).append("\",")
                      .append("\"").append(db).append("\",")
                      .append("\"").append(unit).append("\",")
                      .append("\"").append(id).append("\",")
                      .append("\"").append(val).append("\"\n");
                }
                outputBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            } else if ("excel".equalsIgnoreCase(format) || "xls".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
                contentType = "application/vnd.ms-excel; charset=UTF-8";
                fileExt = "xls";
                StringBuilder sb = new StringBuilder();
                sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">");
                sb.append("<head><meta charset=\"utf-8\"/><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>Export</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>");
                sb.append("<body><table border=\"1\" style=\"border-collapse:collapse; font-family:Arial,sans-serif; font-size:12px;\">");
                sb.append("<tr style=\"background:#1e293b; color:#38bdf8; font-weight:bold; height:30px;\"><th>Storage Key</th><th>Database</th><th>Unit / Collection</th><th>Record ID</th><th>Payload JSON / Content</th></tr>");
                for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                    String k = entry.getKey();
                    String val = entry.getValue();
                    String[] parts = k.split(":");
                    String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                    String id = parts.length > 0 ? parts[parts.length - 1] : k;
                    sb.append("<tr>")
                      .append("<td style=\"font-weight:bold; color:#0f172a;\">").append(k).append("</td>")
                      .append("<td>").append(db).append("</td>")
                      .append("<td>").append(unit).append("</td>")
                      .append("<td style=\"font-family:monospace;\">").append(id).append("</td>")
                      .append("<td style=\"font-family:monospace;\">").append(val.replace("<", "&lt;").replace(">", "&gt;")).append("</td>")
                      .append("</tr>");
                }
                sb.append("</table></body></html>");
                outputBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                contentType = "application/json; charset=UTF-8";
                fileExt = "json";
                JsonObject root = new JsonObject();
                root.addProperty("_database", db);
                root.addProperty("_engineFilter", eng);
                root.addProperty("_collectionFilter", coll.isBlank() ? "*" : coll);
                root.addProperty("_exportedAt", System.currentTimeMillis());
                root.addProperty("_totalRecords", recordsMap.size());

                JsonObject recordsObj = new JsonObject();
                for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                    try {
                        JsonObject obj = jsonParser.fromJson(entry.getValue(), JsonObject.class);
                        if (obj != null) {
                            recordsObj.add(entry.getKey(), obj);
                        } else {
                            recordsObj.addProperty(entry.getKey(), entry.getValue());
                        }
                    } catch (Exception ex) {
                        recordsObj.addProperty(entry.getKey(), entry.getValue());
                    }
                }
                root.add("records", recordsObj);
                outputBytes = root.toString().getBytes(StandardCharsets.UTF_8);
            }

            long duration = System.currentTimeMillis() - start;
            String outFileName = db + (coll.isBlank() ? "" : "_" + coll) + "_export." + fileExt;
            String msg = "Successfully exported " + recordsMap.size() + " records from '" + db + "' in format " + fileExt.toUpperCase();

            return EngineOperationResult.success(
                    EngineOperationType.EXPORT,
                    db,
                    msg,
                    recordsMap.size(),
                    outputBytes.length,
                    outFileName,
                    contentType,
                    outputBytes,
                    duration
            );
        } catch (Exception e) {
            throw new EngineOperationException(EngineOperationType.EXPORT, task.database(), "Export failed: " + e.getMessage(), e);
        }
    }

    private static String resolveEnginePrefix(String engineName) {
        if (engineName == null) return "doc:";
        return switch (engineName.toUpperCase()) {
            case "RECORDS" -> "rec:";
            case "KEYVALUE", "KEY_VALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES", "TIME_SERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL", "GEO" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };
    }
}
