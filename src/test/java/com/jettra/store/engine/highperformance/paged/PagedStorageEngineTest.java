package com.jettra.store.engine.highperformance.paged;

import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.Test;
import static io.jettra.test.core.JettraAssert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@io.jettra.test.annotation.NotRequiresRunningServer
public class PagedStorageEngineTest {

    private Path tempDir;
    private PagedStorageEngine storageEngine;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_paged_storage_test_");
        storageEngine = new PagedStorageEngine(tempDir, 16);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    public void testRecordIdSerializationAndFormatting() {
        RecordId rid = new RecordId(1, 1024L, 128);
        assertEquals("#1:1024:128", rid.toString());

        RecordId parsed = RecordId.parse("#1:1024:128");
        assertEquals(rid, parsed);

        RecordId parsedWithoutHash = RecordId.parse("1:1024:128");
        assertEquals(rid, parsedWithoutHash);

        byte[] bytes = rid.toBytes();
        assertEquals(RecordId.BINARY_SIZE, bytes.length);

        RecordId fromBytes = RecordId.fromBytes(bytes);
        assertEquals(rid, fromBytes);
    }

    @Test
    public void testPageHeaderAndRecordAppend() {
        Page page = new Page(1, 0L);
        assertEquals(1, page.getFileId());
        assertEquals(0L, page.getPageIndex());
        assertEquals(Page.HEADER_SIZE, page.getFreeOffset());
        assertEquals(0, page.getRecordCount());

        byte[] payload1 = "HelloWorld".getBytes(StandardCharsets.UTF_8);
        int offset1 = page.appendRecord(payload1);
        assertEquals(Page.HEADER_SIZE, offset1);
        assertEquals(1, page.getRecordCount());

        byte[] readBack = page.readRecord(offset1);
        assertArrayEquals(payload1, readBack);

        // Update checksum and recreate page from raw bytes
        page.updateChecksum();
        Page restored = new Page(page.getRawData());
        assertEquals(1, restored.getFileId());
        assertEquals(0L, restored.getPageIndex());
        assertEquals(1, restored.getRecordCount());
        assertArrayEquals(payload1, restored.readRecord(offset1));
    }

    @Test
    public void testDirectRecordIdReadWriteAndPageSpill() throws IOException {
        int fileId = 10;
        byte[] payload = "HighPerformanceArcadeDBSampleData".getBytes(StandardCharsets.UTF_8);

        // Write a record
        RecordId rid1 = storageEngine.writeRecord(fileId, payload);
        assertNotNull(rid1);
        assertEquals(fileId, rid1.fileId());
        assertEquals(0L, rid1.pageIndex());

        // Read directly by RID in O(1) time
        byte[] readData = storageEngine.readRecord(rid1);
        assertArrayEquals(payload, readData);

        // Write enough records to trigger page spill (each page is 64KB)
        byte[] largeChunk = new byte[8 * 1024]; // 8KB
        RecordId lastRid = null;
        for (int i = 0; i < 10; i++) {
            lastRid = storageEngine.writeRecord(fileId, largeChunk);
        }

        // Should have crossed into page 1
        assertTrue(lastRid.pageIndex() > 0, "Should have spilled to next pageIndex > 0");

        // First record must still be readable directly from page 0
        byte[] readFirst = storageEngine.readRecord(rid1);
        assertArrayEquals(payload, readFirst);
    }

    @Test
    public void testDeleteRecordTombstone() throws IOException {
        int fileId = 5;
        byte[] payload = "RecordToDelete".getBytes(StandardCharsets.UTF_8);

        RecordId rid = storageEngine.writeRecord(fileId, payload);
        assertNotNull(storageEngine.readRecord(rid));

        boolean deleted = storageEngine.deleteRecord(rid);
        assertTrue(deleted);

        // Subsequent read should yield null (tombstone)
        assertNull(storageEngine.readRecord(rid));
    }
}
