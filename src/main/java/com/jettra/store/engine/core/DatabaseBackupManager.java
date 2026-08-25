package com.jettra.store.engine.core;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Manages database-specific Hot Backups and Restorations for JettraStoreEngine.
 * Creates compressed .zip archives named &lt;database-name&gt;yyyy-MM-dd-HH-mm-ss.zip
 * with default location at ~/data/backup/&lt;database-name&gt;.
 */
public class DatabaseBackupManager {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final JettraJson JSON = new JettraJson();

    public record BackupFileInfo(
        String fileName,
        String fullPath,
        long sizeBytes,
        String formattedDate,
        long timestamp
    ) {}

    public record BackupOperationResult(
        boolean success,
        String filePath,
        int keyCount,
        long sizeBytes,
        String message
    ) {}

    public record RestoreOperationResult(
        boolean success,
        int keyCount,
        String message
    ) {}

    /**
     * Resolves the default backup directory path: ~/data/backup/&lt;database-name&gt;
     */
    public static Path getDefaultBackupDir(String dbName) {
        String cleanDb = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "default_db";
        return Path.of(System.getProperty("user.home"), "data", "backup", cleanDb);
    }

    /**
     * Generates standard backup filename: &lt;database-name&gt;yyyy-MM-dd-HH-mm-ss.zip
     */
    public static String generateBackupFileName(String dbName) {
        String cleanDb = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "database";
        String ts = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        return cleanDb + ts + ".zip";
    }

    /**
     * Creates a hot backup .zip of all multi-model engine keys belonging to the specified database.
     */
    public static BackupOperationResult createDatabaseBackup(JettraStorageEngine engine, String dbName, String customDir, String customFileName) {
        if (engine == null || dbName == null || dbName.isBlank()) {
            return new BackupOperationResult(false, "", 0, 0, "Invalid engine instance or database name.");
        }

        try {
            Path targetDir = (customDir != null && !customDir.isBlank()) ? Path.of(customDir.trim()) : getDefaultBackupDir(dbName);
            Files.createDirectories(targetDir);

            String fileName = (customFileName != null && !customFileName.isBlank()) ? customFileName.trim() : generateBackupFileName(dbName);
            if (!fileName.toLowerCase().endsWith(".zip")) {
                fileName += ".zip";
            }

            Path zipPath = targetDir.resolve(fileName);

            // Collect all database keys across engines and prefixes
            Map<String, String> backupEntries = new LinkedHashMap<>();
            String[] prefixes = {
                "rec:" + dbName + ":",
                "doc:" + dbName + ":",
                "vec:" + dbName + ":",
                "graph:" + dbName + ":",
                "ts:" + dbName + ":",
                "col:" + dbName + ":",
                "kv:" + dbName + ":",
                "geo:" + dbName + ":",
                "obj:" + dbName + ":",
                dbName + ":",
                "idx:" + dbName + ":",
                "schema:" + dbName + ":"
            };

            for (String prefix : prefixes) {
                Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(prefix);
                for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                    String k = e.getKey();
                    byte[] val = e.getValue();
                    if (val != null && val.length > 0) {
                        backupEntries.put(k, Base64.getEncoder().encodeToString(val));
                    }
                }
            }

            // Build Manifest / Dump JSON
            JsonObject dump = new JsonObject();
            dump.addProperty("database", dbName);
            dump.addProperty("createdAt", System.currentTimeMillis());
            dump.addProperty("createdAtFormatted", LocalDateTime.now().toString());
            dump.addProperty("version", "JettraStoreEngine-1.0");
            dump.addProperty("keyCount", backupEntries.size());

            JsonObject keysObj = new JsonObject();
            for (Map.Entry<String, String> entry : backupEntries.entrySet()) {
                keysObj.addProperty(entry.getKey(), entry.getValue());
            }
            dump.add("keys", keysObj);

            String dumpJson = JSON.toJson(dump);

            // Write into ZIP archive
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipPath.toFile())))) {
                ZipEntry entry = new ZipEntry("database_dump.json");
                zos.putNextEntry(entry);
                zos.write(dumpJson.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            long sizeBytes = Files.size(zipPath);
            return new BackupOperationResult(
                true,
                zipPath.toAbsolutePath().toString(),
                backupEntries.size(),
                sizeBytes,
                "Backup for database '" + dbName + "' created successfully (" + backupEntries.size() + " records, " + sizeBytes + " bytes)."
            );
        } catch (Exception e) {
            return new BackupOperationResult(false, "", 0, 0, "Backup error: " + e.getMessage());
        }
    }

    /**
     * Lists all .zip backup files present in the specified directory.
     */
    public static List<BackupFileInfo> listBackups(String dbName, String customDir) {
        Path targetDir = (customDir != null && !customDir.isBlank()) ? Path.of(customDir.trim()) : getDefaultBackupDir(dbName);
        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            return Collections.emptyList();
        }

        List<BackupFileInfo> results = new ArrayList<>();
        File[] files = targetDir.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));
        if (files != null) {
            DateTimeFormatter displayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (File f : files) {
                long lastModified = f.lastModified();
                String formatted = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(lastModified),
                    java.time.ZoneId.systemDefault()
                ).format(displayFmt);

                results.add(new BackupFileInfo(
                    f.getName(),
                    f.getAbsolutePath(),
                    f.length(),
                    formatted,
                    lastModified
                ));
            }
            results.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        }
        return results;
    }

    /**
     * Restores a database backup from a .zip archive into the JettraStorageEngine.
     */
    public static RestoreOperationResult restoreDatabaseBackup(JettraStorageEngine engine, String dbName, String zipFilePath) {
        if (engine == null || zipFilePath == null || zipFilePath.isBlank()) {
            return new RestoreOperationResult(false, 0, "Invalid engine or backup file path.");
        }

        Path path = Path.of(zipFilePath.trim());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return new RestoreOperationResult(false, 0, "Backup file not found at: " + zipFilePath);
        }

        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            ZipEntry entry = zipFile.getEntry("database_dump.json");
            if (entry == null) {
                return new RestoreOperationResult(false, 0, "Invalid backup archive: missing database_dump.json manifest.");
            }

            String dumpJson;
            try (InputStream is = new BufferedInputStream(zipFile.getInputStream(entry))) {
                dumpJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject dump = JSON.fromJson(dumpJson, JsonObject.class);
            if (dump == null || !dump.has("keys")) {
                return new RestoreOperationResult(false, 0, "Failed to parse database dump metadata.");
            }

            Object keysRaw = dump.get("keys");
            Map<?, ?> keysMap = null;
            if (keysRaw instanceof JsonObject jo) {
                keysMap = jo.getMap();
            } else if (keysRaw instanceof Map<?, ?> m) {
                keysMap = m;
            }

            if (keysMap == null || keysMap.isEmpty()) {
                return new RestoreOperationResult(false, 0, "No keys found in database dump manifest.");
            }

            int restoredCount = 0;
            for (Map.Entry<?, ?> e : keysMap.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object valObj = e.getValue();
                if (valObj != null) {
                    String b64 = valObj.toString().replace("\"", "");
                    byte[] data;
                    try {
                        data = Base64.getDecoder().decode(b64);
                    } catch (Exception ex) {
                        data = b64.getBytes(StandardCharsets.UTF_8);
                    }
                    engine.getStorageCore().put(k, data, System.currentTimeMillis());
                    restoredCount++;
                }
            }

            return new RestoreOperationResult(
                true,
                restoredCount,
                "Database restored successfully! (" + restoredCount + " records imported into storage)."
            );
        } catch (Exception e) {
            return new RestoreOperationResult(false, 0, "Restoration error: " + e.getMessage());
        }
    }
}
