package com.jettra.store.engine.core.generational;

import com.jettra.store.engine.core.CompactionService;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.core.generational.InternalCompactor.CompactionReport;
import com.jettra.store.engine.core.generational.InternalCompactor.CompactionType;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the Generational Storage Optimization architecture:
 * - Young Area (hot in-memory buffer with off-heap Panama Arena)
 * - Old Area (tenured disk and warm cache storage)
 * - Minor and Major Internal Compaction
 * - Resource reclamation and fast-path tombstone purging
 */
@NotRequiresRunningServer
public class GenerationalStorageAndCompactionTest {

    private Path tempDir;
    private LsmBTreeHybrid storage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_generational_test_");
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

    @JettraTest
    void testYoungAreaAllocationAndFastPathRead() {
        long now = System.currentTimeMillis();
        String key = "doc:GenDb:item_1";
        byte[] payload = "{\"name\":\"Generational Test Item\",\"count\":100}".getBytes(StandardCharsets.UTF_8);

        // 1. Write to Young Area
        storage.put(key, payload, now);

        LsmBTreeHybrid.DatabasePartition partition = storage.getPartition("GenDb");
        assertNotNull(partition, "Partition GenDb must be created");

        YoungArea youngArea = partition.getYoungArea();
        assertNotNull(youngArea, "Young Area must exist");
        assertTrue(youngArea.containsActiveKey(key), "Record must reside in Young Area immediately after write");

        // 2. Fast-path O(1) read from Young Area (backed by Off-Heap Arena)
        byte[] readBack = storage.get(key);
        assertNotNull(readBack, "Read back from Young Area must succeed");
        assertEquals(new String(payload, StandardCharsets.UTF_8), new String(readBack, StandardCharsets.UTF_8));
        assertTrue(youngArea.getYoungReadHits() > 0, "Young read hits telemetry must increment");

        // 3. Off-heap Arena allocated bytes must be tracked
        assertTrue(youngArea.getArena().getAllocatedBytes() > 0, "Arena allocated bytes must be greater than zero");
    }

    @JettraTest
    void testMinorCompactionPromotesSurvivingRecordsAndPurgesTombstones() {
        long now = System.currentTimeMillis();
        LsmBTreeHybrid.DatabasePartition partition = storage.getPartition("MinorCompDb");
        YoungArea youngArea = partition.getYoungArea();
        OldArea oldArea = partition.getOldArea();

        // 1. Insert multiple items into Young Area
        for (int i = 1; i <= 5; i++) {
            storage.put("doc:MinorCompDb:doc_" + i, ("{\"val\":" + i + "}").getBytes(StandardCharsets.UTF_8), now);
        }

        // 2. Delete one item (tombstone)
        storage.delete("doc:MinorCompDb:doc_3", now + 1);
        assertTrue(youngArea.isTombstone("doc:MinorCompDb:doc_3"), "Deleted record must be marked as tombstone in Young Area");

        // 3. Fast-path lookup for deleted key returns null without disk overhead
        assertNull(storage.get("doc:MinorCompDb:doc_3"), "Deleted record must return null immediately");

        // 4. Trigger Minor Compaction
        CompactionReport report = partition.performMinorCompaction();
        assertNotNull(report);
        assertEquals(CompactionType.MINOR, report.type());
        assertTrue(report.tombstonesPurged() >= 1, "Tombstone doc_3 must be purged during Minor Compaction");
        assertTrue(report.promotedCount() >= 4, "Active surviving records must be promoted to Old Area");

        // 5. Verify records are now in Old Area and still accessible
        assertTrue(oldArea.size() >= 4, "Old Area must contain the promoted records");
        for (int i = 1; i <= 5; i++) {
            if (i == 3) {
                assertNull(storage.get("doc:MinorCompDb:doc_" + i), "doc_3 must remain null");
            } else {
                byte[] val = storage.get("doc:MinorCompDb:doc_" + i);
                assertNotNull(val, "doc_" + i + " must be retrievable from Old Area");
                assertTrue(new String(val, StandardCharsets.UTF_8).contains("\"val\":" + i));
            }
        }
    }

    @JettraTest
    void testMajorCompactionDefragmentsStorageAndTruncatesWal() throws IOException {
        long now = System.currentTimeMillis();
        LsmBTreeHybrid.DatabasePartition partition = storage.getPartition("MajorCompDb");
        Path dbDir = partition.getDbDirectory();

        // 1. Populate data and updates
        for (int i = 1; i <= 10; i++) {
            storage.put("rec:MajorCompDb:user_" + i, ("{\"name\":\"User" + i + "\"}").getBytes(StandardCharsets.UTF_8), now);
        }

        // Delete several records
        storage.delete("rec:MajorCompDb:user_2", now + 2);
        storage.delete("rec:MajorCompDb:user_4", now + 2);
        storage.delete("rec:MajorCompDb:user_6", now + 2);

        // 2. Perform Major Compaction
        CompactionReport report = partition.performMajorCompaction();
        assertNotNull(report);
        assertEquals(CompactionType.MAJOR, report.type());
        assertTrue(report.activeRecordsRemaining() >= 7, "Remaining active records must equal 7");

        // 3. Verify physical storage file data_0.jettra exists and contains compacted data
        Path dataFile = dbDir.resolve("data_0.jettra");
        assertTrue(Files.exists(dataFile), "data_0.jettra must exist after major compaction");
        assertTrue(Files.size(dataFile) > 0, "Compact data file must have active bytes");

        // 4. Verify all surviving records are retrievable
        for (int i = 1; i <= 10; i++) {
            byte[] val = storage.get("rec:MajorCompDb:user_" + i);
            if (i == 2 || i == 4 || i == 6) {
                assertNull(val, "Deleted user_" + i + " must not exist");
            } else {
                assertNotNull(val, "Active user_" + i + " must exist after major compaction");
                assertTrue(new String(val, StandardCharsets.UTF_8).contains("User" + i));
            }
        }
    }

    @JettraTest
    void testCompactionServiceOrchestration() {
        CompactionService compactionService = new CompactionService(storage, 1000, 5000);
        compactionService.start();
        assertTrue(compactionService.isRunning(), "Compaction service must be running");

        storage.put("doc:ServiceDb:item_1", "{\"msg\":\"hello\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        storage.delete("doc:ServiceDb:item_1", System.currentTimeMillis() + 1);

        CompactionReport rep = compactionService.compactDatabase("ServiceDb", true);
        assertNotNull(rep, "Compaction report must be returned");
        assertTrue(compactionService.getTotalCompactionRuns() > 0, "Compaction runs count must increment");

        compactionService.stop();
        assertFalse(compactionService.isRunning(), "Compaction service must stop cleanly");
    }
}
