package com.jettra.store.engine.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates RQ-2: Per-Database Dedicated Storage Architecture.
 * Ensures each database operates in isolated file partitions on disk,
 * maintaining separate WAL and SSTables without cross-database interference.
 */
public class DatabaseStoragePartitionTest {

    private Path tempDir;
    private LsmBTreeHybrid storage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_storage_partition_test_");
        storage = new LsmBTreeHybrid(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            storage.close();
        }
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                      });
            }
        }
    }

    @Test
    void testPerDatabaseFileIsolation() {
        long now = System.currentTimeMillis();

        // 1. Write records to Database A
        storage.put("doc:DatabaseAlpha:doc_1", "{\"name\":\"Alpha Item 1\"}".getBytes(StandardCharsets.UTF_8), now);
        storage.put("rec:DatabaseAlpha:rec_1", "{\"name\":\"Alpha Record 1\"}".getBytes(StandardCharsets.UTF_8), now);

        // 2. Write records to Database B
        storage.put("doc:DatabaseBeta:doc_2", "{\"name\":\"Beta Item 2\"}".getBytes(StandardCharsets.UTF_8), now);
        storage.put("geo:DatabaseBeta:hub_1", "{\"lat\":8.98,\"lon\":-79.52}".getBytes(StandardCharsets.UTF_8), now);

        // 3. Verify on-disk directories and files exist independently
        Path dirAlpha = tempDir.resolve("databases").resolve("DatabaseAlpha");
        Path dirBeta = tempDir.resolve("databases").resolve("DatabaseBeta");

        assertTrue(Files.exists(dirAlpha), "DatabaseAlpha directory must exist");
        assertTrue(Files.exists(dirAlpha.resolve("wal.jettra")), "DatabaseAlpha must have its own wal.jettra");
        assertTrue(Files.exists(dirAlpha.resolve("data_0.jettra")), "DatabaseAlpha must have its own data_0.jettra");

        assertTrue(Files.exists(dirBeta), "DatabaseBeta directory must exist");
        assertTrue(Files.exists(dirBeta.resolve("wal.jettra")), "DatabaseBeta must have its own wal.jettra");
        assertTrue(Files.exists(dirBeta.resolve("data_0.jettra")), "DatabaseBeta must have its own data_0.jettra");

        // 4. Verify read operations resolve correctly from separate partitions
        byte[] a1 = storage.get("doc:DatabaseAlpha:doc_1");
        assertNotNull(a1);
        assertTrue(new String(a1, StandardCharsets.UTF_8).contains("Alpha Item 1"));

        byte[] b2 = storage.get("doc:DatabaseBeta:doc_2");
        assertNotNull(b2);
        assertTrue(new String(b2, StandardCharsets.UTF_8).contains("Beta Item 2"));

        // 5. Verify isolated database scanning
        Map<String, byte[]> scanAlpha = storage.scanPrefix("doc:DatabaseAlpha:");
        assertEquals(1, scanAlpha.size());
        assertTrue(scanAlpha.containsKey("doc:DatabaseAlpha:doc_1"));
        assertFalse(scanAlpha.containsKey("doc:DatabaseBeta:doc_2"));

        Map<String, byte[]> scanBeta = storage.scanPrefix("doc:DatabaseBeta:");
        assertEquals(1, scanBeta.size());
        assertTrue(scanBeta.containsKey("doc:DatabaseBeta:doc_2"));
    }

    @Test
    void testIndependentDatabaseDrop() {
        long now = System.currentTimeMillis();

        storage.put("rec:DropTargetDb:item_1", "{\"status\":\"temp\"}".getBytes(StandardCharsets.UTF_8), now);
        storage.put("rec:KeepDb:item_2", "{\"status\":\"permanent\"}".getBytes(StandardCharsets.UTF_8), now);

        Path dropDir = tempDir.resolve("databases").resolve("DropTargetDb");
        Path keepDir = tempDir.resolve("databases").resolve("KeepDb");

        assertTrue(Files.exists(dropDir), "DropTargetDb directory must exist before drop");
        assertTrue(Files.exists(keepDir), "KeepDb directory must exist");

        // Drop DropTargetDb
        storage.dropDatabase("DropTargetDb");

        // Verify DropTargetDb files are deleted from disk
        assertFalse(Files.exists(dropDir), "DropTargetDb directory must be deleted after drop");
        assertNull(storage.get("rec:DropTargetDb:item_1"), "Dropped record must return null");

        // Verify KeepDb remains fully intact and readable
        assertTrue(Files.exists(keepDir), "KeepDb directory must remain intact");
        assertNotNull(storage.get("rec:KeepDb:item_2"), "KeepDb record must remain intact");
    }

    @Test
    void testWalRestorationPerDatabase() {
        long now = System.currentTimeMillis();

        storage.put("doc:PersistenceDb:order_99", "{\"amount\":500}".getBytes(StandardCharsets.UTF_8), now);
        storage.put("doc:PersistenceDb:order_99", "{\"amount\":750}".getBytes(StandardCharsets.UTF_8), now + 100);
        storage.close();

        // Instantiate fresh storage pointing to same directory to verify independent WAL restoration
        LsmBTreeHybrid restoredStorage = new LsmBTreeHybrid(tempDir);
        byte[] payload = restoredStorage.get("doc:PersistenceDb:order_99");
        assertNotNull(payload, "Record must be restored from database-specific WAL");
        assertTrue(new String(payload, StandardCharsets.UTF_8).contains("750"));

        var history = restoredStorage.getVersionHistory("doc:PersistenceDb:order_99");
        assertEquals(2, history.size(), "Version history must be preserved in partition WAL");
        restoredStorage.close();
    }
}
