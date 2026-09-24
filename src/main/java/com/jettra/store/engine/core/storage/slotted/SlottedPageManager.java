package com.jettra.store.engine.core.storage.slotted;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.*;

/**
 * Slotted Page Manager for JettraDB Physical Storage.
 *
 * <p>Core Responsibilities:
 * <ul>
 *   <li>Enforces fixed-size page allocation over disk files strictly ending with {@code .jetrra}.</li>
 *   <li>Coordinates the {@link BufferPool}, avoiding GC pauses using Off-Heap direct memory.</li>
 *   <li>Performs in-place updates when record payloads fit existing slots or page free space.</li>
 *   <li>Maintains an in-memory sparse key-to-RID physical index (RecordSlotAddress) for O(1) lookups.</li>
 *   <li>Integrates Builder, Strategy, and Template Method design patterns for page lifecycles.</li>
 * </ul>
 */
public class SlottedPageManager implements AutoCloseable {

    private final Path storageFile;
    private final int pageSize;
    private final BufferPool bufferPool;
    private final SpaceAllocationStrategy allocationStrategy;
    private final PageCompactionStrategy compactionStrategy;
    private final FileChannel fileChannel;
    private final Map<String, RecordSlotAddress> keyIndex;
    private final Map<Long, Page> activePages;
    private final AtomicLong nextPageId;
    private final ReentrantLock managerLock;
    private volatile boolean closed = false;

    public SlottedPageManager(Path storageFile, int pageSize, BufferPool bufferPool,
                              SpaceAllocationStrategy allocationStrategy,
                              PageCompactionStrategy compactionStrategy,
                              boolean memoryMapped) throws IOException {
        Objects.requireNonNull(storageFile, "storageFile must not be null");

        // Validate strictly the mandatory .jetrra / .jettra file extension
        String fileName = storageFile.getFileName().toString();
        if (!SlottedPageConstants.isSlottedFile(fileName)) {
            throw new IllegalArgumentException("Slotted storage file must strictly use " + FILE_EXTENSION + " (or " + ALT_FILE_EXTENSION + ") extension: " + storageFile);
        }

        this.storageFile = storageFile;
        this.pageSize = (pageSize >= PAGE_SIZE_4KB) ? pageSize : DEFAULT_PAGE_SIZE;
        this.bufferPool = (bufferPool != null) ? bufferPool : new BufferPool(128, this.pageSize, true, new LruPageEvictionStrategy(), memoryMapped);
        this.allocationStrategy = (allocationStrategy != null) ? allocationStrategy : new BestFitAllocationStrategy();
        this.compactionStrategy = (compactionStrategy != null) ? compactionStrategy : new InPlacePageCompactionStrategy();
        this.keyIndex = new ConcurrentHashMap<>();
        this.activePages = new ConcurrentHashMap<>();
        this.nextPageId = new AtomicLong(0);
        this.managerLock = new ReentrantLock();

        Path parent = storageFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        @SuppressWarnings("resource")
        RandomAccessFile raf = new RandomAccessFile(storageFile.toFile(), "rw");
        this.fileChannel = raf.getChannel();

        initAndRebuildIndex();
    }

    public SlottedPageManager(Path storageFile, int pageSize, BufferPool bufferPool,
                              SpaceAllocationStrategy allocationStrategy,
                              PageCompactionStrategy compactionStrategy) throws IOException {
        this(storageFile, pageSize, bufferPool, allocationStrategy, compactionStrategy, false);
    }

    public SlottedPageManager(Path storageFile) throws IOException {
        this(storageFile, DEFAULT_PAGE_SIZE, null, null, null, false);
    }

    private void initAndRebuildIndex() throws IOException {
        managerLock.lock();
        try {
            long fileSize = fileChannel.size();
            long totalPages = fileSize / pageSize;
            nextPageId.set(totalPages);

            for (long pId = 0; pId < totalPages; pId++) {
                Page page = bufferPool.acquirePage(storageFile, fileChannel, pId);
                try {
                    activePages.put(pId, page);
                    int slotCount = page.getSlotCount();
                    for (int sId = 0; sId < slotCount; sId++) {
                        Slot slot = page.getSlot(sId);
                        if (slot.isActive()) {
                            Page.RecordEntry entry = page.readRecordEntry(sId);
                            if (entry != null && entry.key() != null && !entry.key().isBlank()) {
                                keyIndex.put(entry.key(), new RecordSlotAddress(pId, sId));
                            }
                        }
                    }
                } finally {
                    bufferPool.releasePage(page, false);
                }
            }
        } finally {
            managerLock.unlock();
        }
    }

    public Path getStorageFile() { return storageFile; }
    public int getPageSize() { return pageSize; }
    public long getPageCount() { return nextPageId.get(); }
    public int getRecordCount() { return keyIndex.size(); }
    public boolean isMemoryMapped() { return bufferPool != null && bufferPool.isMemoryMapped(); }

    /**
     * Inserts a record into a slotted page and returns its physical address (RID).
     */
    public RecordSlotAddress insert(String key, byte[] payload, int version, long timestamp) throws IOException {
        Objects.requireNonNull(key, "Record key must not be null");
        managerLock.lock();
        try {
            ensureOpen();

            // Check if key already exists, route to update
            if (keyIndex.containsKey(key)) {
                update(key, payload, version, timestamp);
                return keyIndex.get(key);
            }

            int requiredPayloadLen = (payload != null) ? payload.length : 0;
            Page targetPage = allocationStrategy.selectPage(activePages.values(), requiredPayloadLen);

            if (targetPage == null) {
                // Allocate a new page
                long newPageId = nextPageId.getAndIncrement();
                targetPage = bufferPool.acquirePage(storageFile, fileChannel, newPageId);
                activePages.put(newPageId, targetPage);
            } else {
                targetPage.pin();
            }

            int slotId = -1;
            try {
                slotId = targetPage.insertRecord(key, payload, version, timestamp);
            } finally {
                bufferPool.releasePage(targetPage, slotId >= 0);
            }

            if (slotId < 0) {
                // If chosen page could not fit (e.g. edge condition), allocate a fresh page
                long newPageId = nextPageId.getAndIncrement();
                Page freshPage = bufferPool.acquirePage(storageFile, fileChannel, newPageId);
                activePages.put(newPageId, freshPage);
                try {
                    slotId = freshPage.insertRecord(key, payload, version, timestamp);
                } finally {
                    bufferPool.releasePage(freshPage, slotId >= 0);
                }
                targetPage = freshPage;
            }

            if (slotId < 0) {
                throw new IOException("Failed to insert record [" + key + "] into any slotted page.");
            }

            RecordSlotAddress address = new RecordSlotAddress(targetPage.getPageId(), slotId);
            keyIndex.put(key, address);
            return address;
        } finally {
            managerLock.unlock();
        }
    }

    /**
     * Reads the payload bytes of a record by key.
     */
    public byte[] get(String key) throws IOException {
        if (key == null) return null;
        RecordSlotAddress addr = keyIndex.get(key);
        if (addr == null) return null;

        Page page = bufferPool.acquirePage(storageFile, fileChannel, addr.pageId());
        try {
            return page.readRecordPayload(addr.slotId());
        } finally {
            bufferPool.releasePage(page, false);
        }
    }

    /**
     * Reads the complete record entry (key, payload, version, timestamp).
     */
    public Page.RecordEntry getRecordEntry(String key) throws IOException {
        if (key == null) return null;
        RecordSlotAddress addr = keyIndex.get(key);
        if (addr == null) return null;

        Page page = bufferPool.acquirePage(storageFile, fileChannel, addr.pageId());
        try {
            return page.readRecordEntry(addr.slotId());
        } finally {
            bufferPool.releasePage(page, false);
        }
    }

    /**
     * Updates an existing record in-place if possible.
     * If the updated record no longer fits within the existing page, it moves to another page.
     */
    public boolean update(String key, byte[] newPayload, int newVersion, long timestamp) throws IOException {
        if (key == null) return false;
        managerLock.lock();
        try {
            ensureOpen();
            RecordSlotAddress addr = keyIndex.get(key);
            if (addr == null) {
                // Record does not exist, insert as new
                insert(key, newPayload, newVersion, timestamp);
                return true;
            }

            Page page = bufferPool.acquirePage(storageFile, fileChannel, addr.pageId());
            boolean updatedInPlace = false;
            try {
                updatedInPlace = page.updateRecordInPlace(addr.slotId(), key, newPayload, newVersion, timestamp);
            } finally {
                bufferPool.releasePage(page, updatedInPlace);
            }

            if (updatedInPlace) {
                return true;
            }

            // Exceeds space on this page even after compaction: delete old slot and reinsert
            delete(key);
            insert(key, newPayload, newVersion, timestamp);
            return true;
        } finally {
            managerLock.unlock();
        }
    }

    /**
     * Deletes a record by marking its slot as tombstone in the slotted page.
     */
    public boolean delete(String key) throws IOException {
        if (key == null) return false;
        managerLock.lock();
        try {
            ensureOpen();
            RecordSlotAddress addr = keyIndex.remove(key);
            if (addr == null) return false;

            Page page = bufferPool.acquirePage(storageFile, fileChannel, addr.pageId());
            boolean deleted = false;
            try {
                deleted = page.deleteRecord(addr.slotId());
            } finally {
                bufferPool.releasePage(page, deleted);
            }
            return deleted;
        } finally {
            managerLock.unlock();
        }
    }

    public boolean exists(String key) {
        return key != null && keyIndex.containsKey(key);
    }

    public Set<String> listKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(keyIndex.keySet()));
    }

    public RecordSlotAddress getRecordAddress(String key) {
        return keyIndex.get(key);
    }

    /**
     * Synchronizes all dirty slotted pages to disk.
     */
    public void sync() throws IOException {
        managerLock.lock();
        try {
            bufferPool.flushFile(storageFile, fileChannel);
        } finally {
            managerLock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed || !fileChannel.isOpen()) {
            throw new IllegalStateException("SlottedPageManager is closed for file: " + storageFile);
        }
    }

    @Override
    public void close() throws IOException {
        managerLock.lock();
        try {
            if (closed) return;
            closed = true;
            sync();
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
            activePages.clear();
            keyIndex.clear();
        } finally {
            managerLock.unlock();
        }
    }
}
