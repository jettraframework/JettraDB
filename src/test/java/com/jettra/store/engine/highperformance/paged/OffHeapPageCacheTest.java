package com.jettra.store.engine.highperformance.paged;

import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.Test;
import static io.jettra.test.core.JettraAssert.*;

import java.nio.charset.StandardCharsets;

@io.jettra.test.annotation.NotRequiresRunningServer
public class OffHeapPageCacheTest {

    private OffHeapPageCache cache;

    @BeforeEach
    public void setUp() {
        // Cache with 3 pages max (192 KB off-heap)
        cache = new OffHeapPageCache(3);
    }

    @AfterEach
    public void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    public void testPutAndGetFromOffHeap() {
        Page page1 = new Page(1, 0L);
        page1.appendRecord("OffHeapPayload1".getBytes(StandardCharsets.UTF_8));
        page1.updateChecksum();

        cache.put(page1);
        assertEquals(1, cache.getCachedPageCount());

        Page retrieved = cache.get(1, 0L);
        assertNotNull(retrieved);
        assertEquals(1, retrieved.getFileId());
        assertEquals(0L, retrieved.getPageIndex());
        assertEquals(1, retrieved.getRecordCount());
        byte[] data = retrieved.readRecord(Page.HEADER_SIZE);
        assertEquals("OffHeapPayload1", new String(data, StandardCharsets.UTF_8));
    }

    @Test
    public void testLruEviction() {
        Page page1 = new Page(1, 0L);
        Page page2 = new Page(1, 1L);
        Page page3 = new Page(1, 2L);
        Page page4 = new Page(1, 3L);

        cache.put(page1);
        cache.put(page2);
        cache.put(page3);
        assertEquals(3, cache.getCachedPageCount());

        // Access page1 to make page2 the LRU candidate
        assertNotNull(cache.get(1, 0L));

        // Adding page4 must evict page2
        cache.put(page4);
        assertEquals(3, cache.getCachedPageCount());

        assertNotNull(cache.get(1, 0L), "page1 should still be cached");
        assertNull(cache.get(1, 1L), "page2 should have been evicted by LRU");
        assertNotNull(cache.get(1, 2L), "page3 should still be cached");
        assertNotNull(cache.get(1, 3L), "page4 should be cached");
    }

    @Test
    public void testEvictAndClear() {
        Page page1 = new Page(2, 0L);
        cache.put(page1);
        assertEquals(1, cache.getCachedPageCount());

        cache.evict(2, 0L);
        assertEquals(0, cache.getCachedPageCount());
        assertNull(cache.get(2, 0L));
    }
}
