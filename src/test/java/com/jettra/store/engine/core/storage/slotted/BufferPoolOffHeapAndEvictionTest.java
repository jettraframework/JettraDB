package com.jettra.store.engine.core.storage.slotted;

import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest suite validating Off-Heap BufferPool management, page pinning,
 * and LRU / Clock eviction strategies.
 */
@NotRequiresRunningServer
public class BufferPoolOffHeapAndEvictionTest {

    private Path tempDir;
    private Path jetrraFile;
    private FileChannel channel;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_buffer_pool_test");
        jetrraFile = tempDir.resolve("test_storage" + SlottedPageConstants.FILE_EXTENSION);
        @SuppressWarnings("resource")
        RandomAccessFile raf = new RandomAccessFile(jetrraFile.toFile(), "rw");
        channel = raf.getChannel();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("BufferPool acquires, pins, releases, and caches pages across multiple reads")
    public void testBufferPoolPinUnpinAndCaching() throws IOException {
        int maxPages = 4;
        try (BufferPool pool = new BufferPool(maxPages, SlottedPageConstants.PAGE_SIZE_4KB, true, new LruPageEvictionStrategy())) {
            assertEquals(0, pool.getLoadedPageCount());

            // Acquire page 0
            Page page0 = pool.acquirePage(jetrraFile, channel, 0L);
            assertNotNull(page0);
            assertEquals(1, page0.getPinCount(), "Acquired page must be pinned once");
            assertEquals(1, pool.getLoadedPageCount(), "Pool loaded count must be 1");

            // Acquire page 0 again -> must return identical cached instance and increment pin
            Page page0Ref = pool.acquirePage(jetrraFile, channel, 0L);
            assertSame(page0, page0Ref, "BufferPool must return identical cached page instance");
            assertEquals(2, page0.getPinCount(), "Pin count must increment to 2");

            // Release references
            pool.releasePage(page0Ref, false);
            assertEquals(1, page0.getPinCount());

            pool.releasePage(page0, false);
            assertEquals(0, page0.getPinCount(), "Page must be completely unpinned");
            assertEquals(1, pool.getLoadedPageCount(), "Page stays cached in pool while unpinned");
        }
    }

    @JettraTest
    @DisplayName("LRU Eviction replaces least recently accessed unpinned page when pool exceeds capacity")
    public void testLruEvictionUnderCapacityPressure() throws IOException {
        int maxPages = 3;
        try (BufferPool pool = new BufferPool(maxPages, SlottedPageConstants.PAGE_SIZE_4KB, true, new LruPageEvictionStrategy())) {
            // Load 3 pages (pages 0, 1, 2)
            Page p0 = pool.acquirePage(jetrraFile, channel, 0L);
            p0.insertRecord("key0", "Payload 0".getBytes(StandardCharsets.UTF_8), 1, 100L);
            pool.releasePage(p0, true);

            Page p1 = pool.acquirePage(jetrraFile, channel, 1L);
            p1.insertRecord("key1", "Payload 1".getBytes(StandardCharsets.UTF_8), 1, 200L);
            pool.releasePage(p1, true);

            Page p2 = pool.acquirePage(jetrraFile, channel, 2L);
            p2.insertRecord("key2", "Payload 2".getBytes(StandardCharsets.UTF_8), 1, 300L);
            pool.releasePage(p2, true);

            assertEquals(3, pool.getLoadedPageCount(), "Pool must be full at 3 pages");

            // Access p0 again, making p1 the least recently used
            Page p0Refresh = pool.acquirePage(jetrraFile, channel, 0L);
            pool.releasePage(p0Refresh, false);

            // Now load page 3 -> must evict p1 (LRU) and write dirty page to disk!
            Page p3 = pool.acquirePage(jetrraFile, channel, 3L);
            p3.insertRecord("key3", "Payload 3".getBytes(StandardCharsets.UTF_8), 1, 400L);
            pool.releasePage(p3, true);

            assertEquals(3, pool.getLoadedPageCount(), "Pool count must remain at capacity 3");

            // Page 1 was flushed to disk! Verify by reloading it from channel
            Page reloadedP1 = pool.acquirePage(jetrraFile, channel, 1L);
            assertNotNull(reloadedP1);
            byte[] readP1 = reloadedP1.readRecordPayload(0);
            assertNotNull(readP1, "Evicted dirty page must have been persisted to disk");
            assertEquals("Payload 1", new String(readP1, StandardCharsets.UTF_8));
            pool.releasePage(reloadedP1, false);
        }
    }

    @JettraTest
    @DisplayName("Pinned pages are protected from eviction even under pool capacity pressure")
    public void testPinnedPagesResistEviction() throws IOException {
        int maxPages = 2;
        try (BufferPool pool = new BufferPool(maxPages, SlottedPageConstants.PAGE_SIZE_4KB, true, new LruPageEvictionStrategy())) {
            // Keep page 0 pinned
            Page p0 = pool.acquirePage(jetrraFile, channel, 0L);

            // Load and release page 1
            Page p1 = pool.acquirePage(jetrraFile, channel, 1L);
            pool.releasePage(p1, false);

            // Load page 2: p0 cannot be evicted because pinCount=1. p1 must be evicted!
            Page p2 = pool.acquirePage(jetrraFile, channel, 2L);
            assertNotNull(p2);

            assertEquals(1, p0.getPinCount(), "Page 0 must still remain pinned");
            pool.releasePage(p0, false);
            pool.releasePage(p2, false);
        }
    }
}
