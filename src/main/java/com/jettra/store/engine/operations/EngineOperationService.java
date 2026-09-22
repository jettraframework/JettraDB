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
                long duration = System.currentTimeMillis() - start;
                return EngineOperationResult.failure(
                        EngineOperationType.RESTORE,
                        task.database(),
                        "Restore source archive file path is required. Please select or provide a .zip backup archive.",
                        null,
                        duration
                );
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
                return EngineOperationResult.failure(
                        EngineOperationType.RESTORE,
                        task.database(),
                        res.message(),
                        null,
                        duration
                );
            }
        } catch (EngineOperationException e) {
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return EngineOperationResult.failure(
                    EngineOperationType.RESTORE,
                    task.database(),
                    "Restore failed: " + e.getMessage(),
                    e.toString(),
                    duration
            );
        }
    }

    public EngineOperationResult executeExport(EngineOperationTask task) {
        return executeExport(task, System.currentTimeMillis());
    }

    private EngineOperationResult executeExport(EngineOperationTask task, long start) {
        try {
            String db = resolveDatabaseName(task.database());
            String eng = task.engineType();
            String coll = task.collection() != null ? task.collection().trim() : "";
            String format = task.exportFormat();

            Map<String, String> recordsMap = new LinkedHashMap<>();
            String[] prefixes;
            if ("ALL".equalsIgnoreCase(eng) || eng == null || eng.isBlank()) {
                prefixes = new String[]{
                    "rec:" + db + ":", "doc:" + db + ":", "vec:" + db + ":",
                    "graph:" + db + ":", "ts:" + db + ":", "col:" + db + ":",
                    "kv:" + db + ":", "geo:" + db + ":", "obj:" + db + ":", db + ":"
                };
            } else {
                String pfx = resolveEnginePrefix(eng);
                if ("DOCUMENT".equalsIgnoreCase(eng)) {
                    prefixes = new String[]{pfx + db + ":", db + ":"};
                } else {
                    prefixes = new String[]{pfx + db + ":"};
                }
            }

            for (String p : prefixes) {
                Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(p);
                for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                    String k = e.getKey();
                    if (k.contains("@") || k.contains(":v_") || k.endsWith(":init_01")) continue;
                    if (k.startsWith("meta:") || k.startsWith("schema:") || k.startsWith("rule:") || k.startsWith("idx:")) continue;

                    // If scanning un-prefixed db + ":", ensure key does not belong to another engine
                    if (p.equals(db + ":")) {
                        if (k.startsWith("rec:") || k.startsWith("doc:") || k.startsWith("vec:")
                                || k.startsWith("graph:") || k.startsWith("ts:") || k.startsWith("col:")
                                || k.startsWith("kv:") || k.startsWith("geo:") || k.startsWith("obj:")) {
                            continue;
                        }
                    }

                    byte[] rawVal = e.getValue();
                    if (rawVal == null || rawVal.length == 0) continue;
                    String valStr = new String(rawVal, StandardCharsets.UTF_8);
                    if ("__TOMBSTONE__".equals(valStr)) continue;

                    // Filter by collection/unit if specified
                    if (!coll.isBlank() && !coll.equalsIgnoreCase("default") && !coll.equalsIgnoreCase("ALL")) {
                        String remainder = k.startsWith(p) ? k.substring(p.length()) : k;
                        String[] parts = remainder.split(":", 2);
                        boolean matchesUnit = (parts.length > 1 && parts[0].equalsIgnoreCase(coll))
                                || k.contains(":" + coll + ":") || k.endsWith(":" + coll);
                        if (!matchesUnit) {
                            continue;
                        }
                    } else if ("default".equalsIgnoreCase(coll)) {
                        String remainder = k.startsWith(p) ? k.substring(p.length()) : k;
                        String[] parts = remainder.split(":", 2);
                        if (parts.length > 1 && !parts[0].equalsIgnoreCase("default")) {
                            continue;
                        }
                    }

                    recordsMap.put(k, valStr);
                }
            }

            com.jettra.store.engine.operations.export.ExportStrategy strategy =
                    com.jettra.store.engine.operations.export.ExportStrategyRegistry.getStrategy(format);
            byte[] outputBytes = strategy.export(db, eng, coll, recordsMap);
            String contentType = strategy.mimeType();
            String fileExt = strategy.fileExtension();

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

    private String resolveDatabaseName(String dbName) {
        if (dbName == null || dbName.isBlank()) return "system_db";
        Map<String, byte[]> all = engine.getStorageCore().scanPrefix("");
        for (String k : all.keySet()) {
            if (k.startsWith("meta:") || k.startsWith("schema:") || k.startsWith("rule:") || k.startsWith("idx:")) {
                String[] p = k.split(":");
                if (p.length > 1 && p[1].equalsIgnoreCase(dbName)) {
                    return p[1];
                }
            } else {
                String[] p = k.split(":");
                if (p.length > 1 && p[1].equalsIgnoreCase(dbName)) {
                    return p[1];
                }
                if (p.length > 0 && p[0].equalsIgnoreCase(dbName)) {
                    return p[0];
                }
            }
        }
        return dbName;
    }
}
