package com.jettra.store.engine.highperformance.paged;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Paged Storage Engine inspired by ArcadeDB page management.
 *
 * <p>Directly manages fixed 64KB physical page files on disk with direct FileChannel I/O,
 * integrated with Java 25 off-heap page caching (Project Panama) and O(1) physical pointer
 * resolution via {@link RecordId}.
 */
public class PagedStorageEngine implements AutoCloseable {

    private final Path baseStorageDir;
    private final OffHeapPageCache pageCache;
    private final Map<Integer, FileChannel> channels;
    private final Map<Integer, Long> lastPageIndices;
    private final Map<Long, Page> dirtyPages;
    private final ReentrantLock writeLock;
    private volatile boolean closed = false;

    public PagedStorageEngine(Path baseStorageDir, int maxOffHeapPages) {
        this.baseStorageDir = baseStorageDir;
        this.pageCache = new OffHeapPageCache(maxOffHeapPages);
        this.channels = new ConcurrentHashMap<>();
        this.lastPageIndices = new ConcurrentHashMap<>();
        this.dirtyPages = new ConcurrentHashMap<>();
        this.writeLock = new ReentrantLock();

        try {
            if (!Files.exists(baseStorageDir)) {
                Files.createDirectories(baseStorageDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize paged storage directory: " + baseStorageDir, e);
        }
    }

    private FileChannel getChannel(int fileId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null || !channel.isOpen()) {
            synchronized (channels) {
                channel = channels.get(fileId);
                if (channel == null || !channel.isOpen()) {
                    Path filePath = baseStorageDir.resolve("bucket_" + fileId + ".jpage");
                    RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw");
                    channel = raf.getChannel();
                    channels.put(fileId, channel);

                    long fileSize = channel.size();
                    long pageCount = fileSize / Page.PAGE_SIZE;
                    lastPageIndices.put(fileId, pageCount > 0 ? pageCount - 1 : 0L);
                }
            }
        }
        return channel;
    }

    private static long makePageKey(int fileId, long pageIndex) {
        return (((long) fileId) << 32) ^ (pageIndex & 0xFFFFFFFFL);
    }

    /**
     * Reads a physical page from cache or disk.
     */
    public Page getPage(int fileId, long pageIndex) throws IOException {
        long key = makePageKey(fileId, pageIndex);
        Page dirty = dirtyPages.get(key);
        if (dirty != null) {
            return dirty;
        }

        Page cached = pageCache.get(fileId, pageIndex);
        if (cached != null) {
            return cached;
        }

        // Read directly from disk
        FileChannel channel = getChannel(fileId);
        long fileOffset = pageIndex * Page.PAGE_SIZE;
        if (fileOffset >= channel.size()) {
            // New page
            Page newPage = new Page(fileId, pageIndex);
            pageCache.put(newPage);
            return newPage;
        }

        ByteBuffer buf = ByteBuffer.allocate(Page.PAGE_SIZE);
        int bytesRead = channel.read(buf, fileOffset);
        if (bytesRead < Page.PAGE_SIZE) {
            throw new IOException("Unexpected EOF while reading page #" + fileId + ":" + pageIndex);
        }
        Page diskPage = new Page(buf.array());
        pageCache.put(diskPage);
        return diskPage;
    }

    /**
     * Stores a record and returns its physical {@link RecordId}.
     *
     * @param fileId Physical file/bucket ID.
     * @param recordBytes Serialized record bytes.
     * @return Direct physical RecordId (#fileId:pageIndex:offset).
     */
    public RecordId writeRecord(int fileId, byte[] recordBytes) throws IOException {
        writeLock.lock();
        try {
            long lastPageIndex = lastPageIndices.computeIfAbsent(fileId, f -> 0L);
            Page activePage = getPage(fileId, lastPageIndex);

            if (!activePage.canFit(recordBytes.length)) {
                // Flush current active page and create a new page
                flushPage(activePage);
                lastPageIndex++;
                lastPageIndices.put(fileId, lastPageIndex);
                activePage = new Page(fileId, lastPageIndex);
            }

            int offset = activePage.appendRecord(recordBytes);
            long key = makePageKey(fileId, activePage.getPageIndex());
            dirtyPages.put(key, activePage);
            pageCache.put(activePage);

            return new RecordId(fileId, activePage.getPageIndex(), offset);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reads a record directly via its {@link RecordId} in O(1) time without index traversal.
     *
     * @param rid Direct physical RecordId.
     * @return Record payload bytes, or null if deleted.
     */
    public byte[] readRecord(RecordId rid) throws IOException {
        Page page = getPage(rid.fileId(), rid.pageIndex());
        return page.readRecord(rid.offset());
    }

    /**
     * Marks a record as deleted at physical {@link RecordId}.
     */
    public boolean deleteRecord(RecordId rid) throws IOException {
        writeLock.lock();
        try {
            Page page = getPage(rid.fileId(), rid.pageIndex());
            boolean deleted = page.deleteRecord(rid.offset());
            if (deleted) {
                long key = makePageKey(rid.fileId(), rid.pageIndex());
                dirtyPages.put(key, page);
                pageCache.put(page);
            }
            return deleted;
        } finally {
            writeLock.unlock();
        }
    }

    private void flushPage(Page page) throws IOException {
        FileChannel channel = getChannel(page.getFileId());
        page.updateChecksum();
        ByteBuffer buf = ByteBuffer.wrap(page.getRawData());
        long diskPosition = page.getPageIndex() * Page.PAGE_SIZE;
        channel.write(buf, diskPosition);
        page.setDirty(false);
    }

    /**
     * Flushes all pending dirty pages to disk and forces physical channel sync.
     */
    public void sync() throws IOException {
        writeLock.lock();
        try {
            for (Page page : dirtyPages.values()) {
                flushPage(page);
            }
            dirtyPages.clear();

            for (FileChannel ch : channels.values()) {
                if (ch.isOpen()) {
                    ch.force(false);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public OffHeapPageCache getPageCache() {
        return pageCache;
    }

    public Path getBaseStorageDir() {
        return baseStorageDir;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        writeLock.lock();
        try {
            sync();
            for (FileChannel ch : channels.values()) {
                if (ch.isOpen()) {
                    ch.close();
                }
            }
            channels.clear();
            pageCache.close();
        } finally {
            writeLock.unlock();
        }
    }
}
