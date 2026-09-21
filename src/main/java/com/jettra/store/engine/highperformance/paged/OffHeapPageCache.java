package com.jettra.store.engine.highperformance.paged;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-performance off-heap page cache implemented with Java 25 Foreign Function & Memory API
 * (Project Panama: {@link Arena} and {@link MemorySegment}).
 *
 * <p>Stores hot storage pages directly in native off-heap memory, completely bypassing
 * JVM Heap allocation overhead and eliminating GC pause times under high-density concurrency
 * (fully aligned with modern ZGC and Shenandoah low-latency targets).
 */
public class OffHeapPageCache implements AutoCloseable {

    private final int maxCachedPages;
    private final Arena sharedArena;
    private final MemorySegment offHeapSegment;
    private final long pageSize;

    // Mapping: PageKey (fileId, pageIndex) -> SlotIndex (0 .. maxCachedPages - 1)
    private final Map<Long, Integer> lruSlotMap;
    private final Deque<Integer> freeSlots;
    private final ReentrantReadWriteLock rwLock;

    public OffHeapPageCache(int maxCachedPages) {
        if (maxCachedPages <= 0) {
            throw new IllegalArgumentException("maxCachedPages must be greater than 0");
        }
        this.maxCachedPages = maxCachedPages;
        this.pageSize = Page.PAGE_SIZE;
        this.sharedArena = Arena.ofShared();
        long totalMemoryBytes = (long) maxCachedPages * pageSize;

        // Allocate unified off-heap memory buffer
        this.offHeapSegment = sharedArena.allocate(totalMemoryBytes, 8);

        this.lruSlotMap = new LinkedHashMap<>(maxCachedPages, 0.75f, true);
        this.freeSlots = new ArrayDeque<>(maxCachedPages);
        for (int i = 0; i < maxCachedPages; i++) {
            freeSlots.add(i);
        }
        this.rwLock = new ReentrantReadWriteLock();
    }

    private static long makePageKey(int fileId, long pageIndex) {
        return (((long) fileId) << 32) ^ (pageIndex & 0xFFFFFFFFL);
    }

    /**
     * Retrieves a page from off-heap cache if present.
     *
     * @param fileId Physical file identifier.
     * @param pageIndex Zero-based page index.
     * @return Cached {@link Page} instance reconstructed from off-heap memory, or null if not cached.
     */
    public Page get(int fileId, long pageIndex) {
        long key = makePageKey(fileId, pageIndex);
        rwLock.writeLock().lock();
        try {
            Integer slot = lruSlotMap.get(key);
            if (slot == null) {
                return null;
            }
            long offset = (long) slot * pageSize;
            byte[] pageBytes = new byte[Page.PAGE_SIZE];

            // Copy directly from Panama off-heap MemorySegment
            MemorySegment.copy(offHeapSegment, ValueLayout.JAVA_BYTE, offset, pageBytes, 0, Page.PAGE_SIZE);

            return new Page(pageBytes);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Stores a page into the off-heap native memory segment.
     *
     * @param page Page to cache off-heap.
     */
    public void put(Page page) {
        if (page == null) return;
        long key = makePageKey(page.getFileId(), page.getPageIndex());

        rwLock.writeLock().lock();
        try {
            Integer slot = lruSlotMap.get(key);
            if (slot == null) {
                if (!freeSlots.isEmpty()) {
                    slot = freeSlots.poll();
                } else {
                    // Evict least recently used page
                    Map.Entry<Long, Integer> eldest = lruSlotMap.entrySet().iterator().next();
                    lruSlotMap.remove(eldest.getKey());
                    slot = eldest.getValue();
                }
                lruSlotMap.put(key, slot);
            }

            long offset = (long) slot * pageSize;
            byte[] pageBytes = page.getRawData();

            // Direct transfer from byte array to off-heap native memory segment
            MemorySegment.copy(pageBytes, 0, offHeapSegment, ValueLayout.JAVA_BYTE, offset, Page.PAGE_SIZE);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Evicts a specific page from off-heap cache.
     */
    public void evict(int fileId, long pageIndex) {
        long key = makePageKey(fileId, pageIndex);
        rwLock.writeLock().lock();
        try {
            Integer slot = lruSlotMap.remove(key);
            if (slot != null) {
                freeSlots.add(slot);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Clears all cached pages.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            lruSlotMap.clear();
            freeSlots.clear();
            for (int i = 0; i < maxCachedPages; i++) {
                freeSlots.add(i);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int getCachedPageCount() {
        rwLock.readLock().lock();
        try {
            return lruSlotMap.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getMaxCachedPages() {
        return maxCachedPages;
    }

    public long getTotalOffHeapBytes() {
        return (long) maxCachedPages * pageSize;
    }

    @Override
    public void close() {
        rwLock.writeLock().lock();
        try {
            lruSlotMap.clear();
            freeSlots.clear();
            if (sharedArena.scope().isAlive()) {
                sharedArena.close(); // Immediately releases native off-heap memory
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
