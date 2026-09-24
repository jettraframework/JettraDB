package com.jettra.store.engine.core.storage.slotted;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.DEFAULT_PAGE_SIZE;

/**
 * High-Performance Off-Heap Buffer Pool for JettraDB Slotted Pages.
 * Manages an in-memory cache of direct ByteBuffers, eliminating Garbage Collection (GC) pauses
 * and coordinating page pinning, dirty tracking, eviction, and FileChannel synchronization.
 */
public class BufferPool implements AutoCloseable {

    private record BufferKey(Path filePath, long pageId) {}

    private final int maxPages;
    private final int pageSize;
    private final boolean directOffHeap;
    private final boolean memoryMapped;
    private final PageEvictionStrategy evictionStrategy;
    private final Map<BufferKey, Page> pool;
    private final Map<Long, Page> pageIdLookup;
    private final Map<BufferKey, FileChannel> channelRegistry;
    private final ReentrantLock poolLock;
    private volatile boolean closed = false;

    public BufferPool(int maxPages, int pageSize, boolean directOffHeap, PageEvictionStrategy evictionStrategy, boolean memoryMapped) {
        this.maxPages = Math.max(1, maxPages);
        this.pageSize = (pageSize >= SlottedPageConstants.PAGE_SIZE_4KB) ? pageSize : DEFAULT_PAGE_SIZE;
        this.directOffHeap = directOffHeap;
        this.memoryMapped = memoryMapped;
        this.evictionStrategy = (evictionStrategy != null) ? evictionStrategy : new LruPageEvictionStrategy();
        this.pool = new ConcurrentHashMap<>();
        this.pageIdLookup = new ConcurrentHashMap<>();
        this.channelRegistry = new ConcurrentHashMap<>();
        this.poolLock = new ReentrantLock();
    }

    public BufferPool(int maxPages, int pageSize, boolean directOffHeap, PageEvictionStrategy evictionStrategy) {
        this(maxPages, pageSize, directOffHeap, evictionStrategy, false);
    }

    public BufferPool(int maxPages) {
        this(maxPages, DEFAULT_PAGE_SIZE, true, new LruPageEvictionStrategy(), false);
    }

    public int getMaxPages() { return maxPages; }
    public int getPageSize() { return pageSize; }
    public int getLoadedPageCount() { return pool.size(); }
    public boolean isMemoryMapped() { return memoryMapped; }

    /**
     * Acquires and pins a page in the buffer pool.
     * Loads the page from the FileChannel or virtual memory mapping if not currently present in memory.
     */
    public Page acquirePage(Path filePath, FileChannel channel, long pageId) throws IOException {
        Objects.requireNonNull(filePath, "filePath cannot be null");
        Objects.requireNonNull(channel, "channel cannot be null");
        BufferKey key = new BufferKey(filePath, pageId);

        poolLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("BufferPool is closed");
            }

            Page page = pool.get(key);
            if (page != null) {
                page.pin();
                evictionStrategy.recordAccess(pageId);
                return page;
            }

            // Pool is full - evict an unpinned page
            if (pool.size() >= maxPages) {
                evictVictim();
            }

            // Read from disk or initialize new page
            long fileOffset = pageId * pageSize;
            if (memoryMapped) {
                long targetSize = (pageId + 1) * (long) pageSize;
                boolean isNewPage = channel.size() < targetSize;
                if (isNewPage) {
                    channel.position(targetSize - 1);
                    channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
                }
                page = SlottedPageBuilder.fromMappedChannel(channel, FileChannel.MapMode.READ_WRITE, pageId, pageSize);
                if (isNewPage) {
                    Page template = SlottedPageBuilder.create()
                        .withPageId(pageId)
                        .withPageSize(pageSize)
                        .withOffHeap(true)
                        .build();
                    template.getHeader().writeTo(page.getByteBuffer());
                    page.updateChecksum();
                }
            } else if (fileOffset < channel.size()) {
                page = SlottedPageBuilder.fromChannel(channel, pageId, pageSize, directOffHeap);
            }

            if (page == null) {
                page = SlottedPageBuilder.create()
                    .withPageId(pageId)
                    .withPageSize(pageSize)
                    .withOffHeap(directOffHeap)
                    .build();
            }

            page.pin();
            pool.put(key, page);
            pageIdLookup.put(pageId, page);
            channelRegistry.put(key, channel);
            evictionStrategy.recordAccess(pageId);
            return page;
        } finally {
            poolLock.unlock();
        }
    }

    /**
     * Releases a pinned page, optionally flagging it as dirty.
     */
    public void releasePage(Page page, boolean isDirty) {
        if (page == null) return;
        if (isDirty) {
            page.setDirty(true);
        }
        page.unpin();
    }

    /**
     * Flushes a specific page to disk via its FileChannel.
     */
    public void flushPage(Path filePath, FileChannel channel, Page page) throws IOException {
        if (page == null || !page.isDirty()) return;
        long fileOffset = page.getPageId() * pageSize;
        page.writeTo(channel, fileOffset);
        page.setDirty(false);
    }

    /**
     * Flushes all dirty pages belonging to a specific file.
     */
    public void flushFile(Path filePath, FileChannel channel) throws IOException {
        poolLock.lock();
        try {
            for (Map.Entry<BufferKey, Page> entry : pool.entrySet()) {
                if (entry.getKey().filePath().equals(filePath)) {
                    flushPage(filePath, channel, entry.getValue());
                }
            }
            if (channel != null && channel.isOpen()) {
                channel.force(false);
            }
        } finally {
            poolLock.unlock();
        }
    }

    /**
     * Evicts an unpinned victim page from the buffer pool.
     */
    private void evictVictim() throws IOException {
        Page victim = evictionStrategy.selectVictim(pageIdLookup);
        if (victim == null) {
            // All pages are pinned! Cannot evict safely without risking corruption
            return;
        }

        BufferKey victimKey = null;
        for (Map.Entry<BufferKey, Page> entry : pool.entrySet()) {
            if (entry.getValue() == victim) {
                victimKey = entry.getKey();
                break;
            }
        }

        if (victimKey != null) {
            FileChannel ch = channelRegistry.get(victimKey);
            if (victim.isDirty() && ch != null && ch.isOpen()) {
                flushPage(victimKey.filePath(), ch, victim);
            }
            pool.remove(victimKey);
            pageIdLookup.remove(victim.getPageId());
            channelRegistry.remove(victimKey);
            evictionStrategy.onPageRemoved(victim.getPageId());
        }
    }

    /**
     * Flushes all dirty pages in the pool and ensures disk persistence.
     */
    public void flushAll() throws IOException {
        poolLock.lock();
        try {
            for (Map.Entry<BufferKey, Page> entry : pool.entrySet()) {
                FileChannel ch = channelRegistry.get(entry.getKey());
                if (ch != null && ch.isOpen() && entry.getValue().isDirty()) {
                    flushPage(entry.getKey().filePath(), ch, entry.getValue());
                }
            }
            for (FileChannel ch : channelRegistry.values()) {
                if (ch != null && ch.isOpen()) {
                    ch.force(false);
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        poolLock.lock();
        try {
            if (closed) return;
            closed = true;
            flushAll();
            pool.clear();
            pageIdLookup.clear();
            channelRegistry.clear();
        } finally {
            poolLock.unlock();
        }
    }
}
