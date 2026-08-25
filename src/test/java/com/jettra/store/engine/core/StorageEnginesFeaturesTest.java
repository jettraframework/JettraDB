package com.jettra.store.engine.core;

import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.DocumentEngine;
import io.jettra.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StorageEnginesFeaturesTest {

    private Path tempDir;
    private JettraStorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_test_db");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testRecordsEnginePersistenceAndStorageCore() {
        RecordsEngine recEngine = (RecordsEngine) engine.getEngine("RECORDS");
        assertNotNull(recEngine);

        JsonObject recordData = new JsonObject();
        recordData.addProperty("firstName", "Linus");
        recordData.addProperty("lastName", "Torvalds");
        recordData.addProperty("role", "Kernel Maintainer");

        recEngine.saveRecord("programmers", "rec_001", "com.jettra.model.PersonRecord", recordData, null);

        // Verify retrieval from engine
        JsonObject retrieved = recEngine.getRecord("programmers", "rec_001");
        assertNotNull(retrieved);
        assertTrue(retrieved.has("components"));

        // Verify retrieval from storage core
        byte[] rawBytes = engine.getStorageCore().get("rec:programmers:rec_001");
        assertNotNull(rawBytes);

        // Delete record
        recEngine.deleteRecord("programmers", "rec_001");

        assertNull(recEngine.getRecord("programmers", "rec_001"));
        assertNull(engine.getStorageCore().get("rec:programmers:rec_001"));
    }

    @Test
    void testKeyValueEnginePersistenceAndStorageCore() {
        KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
        assertNotNull(kvEngine);

        kvEngine.put("config_db", "sys.timeout", "5000");

        assertEquals("5000", kvEngine.get("config_db", "sys.timeout"));
        assertNotNull(engine.getStorageCore().get("kv:config_db:sys.timeout"));

        kvEngine.delete("config_db", "sys.timeout");

        assertNull(kvEngine.get("config_db", "sys.timeout"));
        assertNull(engine.getStorageCore().get("kv:config_db:sys.timeout"));
    }

    @Test
    void testDatabaseBackupAndRestore() throws IOException {
        String testDb = "orders_db";
        DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
        assertNotNull(docEngine);

        // Populate database with sample records
        for (int i = 1; i <= 5; i++) {
            JsonObject order = new JsonObject();
            order.addProperty("orderId", "ord_" + i);
            order.addProperty("amount", 100.0 * i);
            order.addProperty("status", "COMPLETED");
            docEngine.insert(testDb, "orders", "ord_" + i, order);
        }

        Path backupDir = Files.createTempDirectory("jettra_backup_test");

        // 1. Create Backup
        var backupRes = DatabaseBackupManager.createDatabaseBackup(engine, testDb, backupDir.toString(), "orders_backup.zip");
        assertTrue(backupRes.success(), backupRes.message());
        assertNotNull(backupRes.filePath());
        assertTrue(Files.exists(Path.of(backupRes.filePath())));

        // 2. List Backups
        List<DatabaseBackupManager.BackupFileInfo> backups = DatabaseBackupManager.listBackups(testDb, backupDir.toString());
        assertFalse(backups.isEmpty());
        assertEquals("orders_backup.zip", backups.get(0).fileName());

        // 3. Clear existing database keys
        Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(testDb + ":");
        for (String k : keys.keySet()) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
        }
        assertEquals(0, engine.getStorageCore().scanPrefix(testDb + ":").size());

        // 4. Restore Database from backup .zip
        var restoreRes = DatabaseBackupManager.restoreDatabaseBackup(engine, testDb, backupRes.filePath());
        assertTrue(restoreRes.success(), restoreRes.message());

        // 5. Verify restored items
        Map<String, byte[]> restoredKeys = engine.getStorageCore().scanPrefix(testDb + ":");
        assertTrue(restoredKeys.size() >= 5);

        // Clean up backup directory
        Files.walk(backupDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
