package com.jettra.store.engine.core.generational;

import com.jettra.store.engine.core.JettraFileManager;
import com.jettra.store.engine.core.storage.StoragePathBuilder;
import com.jettra.store.engine.core.storage.StorageRecordPath;
import com.jettra.store.engine.core.storage.StorageRecordRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Old Generation Area (Tenured Storage) for JettraDB.
 * Manages long-lived, stabilized records promoted from {@link YoungArea}.
 * Combines in-memory sparse disk indexes, an adaptive warm read cache,
 * and physical file storage on disk (data_0.jettra and hierarchical record files).
 */
public class OldArea implements AutoCloseable {

    private static final int MAX_WARM_CACHE_SIZE = 5000;
    private final String dbName;
    private final Map<String, Long> diskIndex;
    private final Map<String, byte[]> warmCache;
    private final AtomicLong promotionsCount;
    private final AtomicLong oldReadHits;

    public OldArea(String dbName) {
        this(dbName, new ConcurrentHashMap<>());
    }

    public OldArea(String dbName, Map<String, Long> sharedDiskIndex) {
        this.dbName = dbName;
        this.diskIndex = (sharedDiskIndex != null) ? sharedDiskIndex : new ConcurrentHashMap<>();
        this.warmCache = new ConcurrentHashMap<>();
        this.promotionsCount = new AtomicLong(0);
        this.oldReadHits = new AtomicLong(0);
    }

    /**
     * Promotes an active record from Young Area to Old Area.
     * Persists the record to data_0.jettra and repository, indexing its offset.
     */
    public synchronized long promote(GenerationalRecord record,
                                    JettraFileManager fileManager,
                                    StorageRecordRepository repo,
                                    Path rootStorageDir) throws IOException {
        if (record == null || record.isTombstone()) {
            return -1L;
        }

        byte[] payload = record.getHeapData();
        if (payload == null || payload.length == 0) {
            return -1L;
        }

        String key = record.getKey();
        long offset = 0L;
        if (fileManager != null) {
            offset = fileManager.append(payload, false);
        }

        diskIndex.put(key, offset);
        if (warmCache.size() < MAX_WARM_CACHE_SIZE) {
            warmCache.put(key, payload);
        }

        if (repo != null && rootStorageDir != null) {
            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, payload);
                repo.save(path, payload, record.getTimestamp(), record.getVersion());
            } catch (Exception ignored) {}
        }

        promotionsCount.incrementAndGet();
        return offset;
    }

    /**
     * Fast retrieval from Old Generation (checks warm cache first, then file manager offset, then repository).
     */
    public byte[] get(String key,
                      JettraFileManager fileManager,
                      StorageRecordRepository repo,
                      Path rootStorageDir) {
        if (key == null) return null;

        // 1. Check in-memory warm cache
        byte[] cached = warmCache.get(key);
        if (cached != null && cached.length > 0) {
            oldReadHits.incrementAndGet();
            return cached;
        }

        // 2. Direct read via FileChannel offset in data_0.jettra
        Long offset = diskIndex.get(key);
        if (offset != null && offset > 0 && fileManager != null) {
            try {
                byte[] val = fileManager.read(offset);
                if (val != null && val.length > 0) {
                    oldReadHits.incrementAndGet();
                    if (warmCache.size() < MAX_WARM_CACHE_SIZE) {
                        warmCache.put(key, val);
                    }
                    return val;
                }
            } catch (IOException ignored) {}
        }

        // 3. Fallback to physical repository
        if (repo != null && rootStorageDir != null) {
            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, null);
                byte[] val = repo.readPayload(path);
                if (val != null && val.length > 0) {
                    oldReadHits.incrementAndGet();
                    if (warmCache.size() < MAX_WARM_CACHE_SIZE) {
                        warmCache.put(key, val);
                    }
                    return val;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    public void delete(String key, StorageRecordRepository repo, Path rootStorageDir) {
        if (key == null) return;
        diskIndex.remove(key);
        warmCache.remove(key);
        if (repo != null && rootStorageDir != null) {
            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, null);
                repo.delete(path);
            } catch (Exception ignored) {}
        }
    }

    public boolean containsKey(String key) {
        return key != null && (diskIndex.containsKey(key) || warmCache.containsKey(key));
    }

    public Map<String, Long> getDiskIndex() {
        return diskIndex;
    }

    public Map<String, byte[]> getWarmCache() {
        return warmCache;
    }

    public void rebuildIndex(Map<String, Long> newDiskIndex) {
        diskIndex.clear();
        diskIndex.putAll(newDiskIndex);
        warmCache.clear();
    }

    public int size() {
        return diskIndex.size();
    }

    public long getPromotionsCount() {
        return promotionsCount.get();
    }

    public long getOldReadHits() {
        return oldReadHits.get();
    }

    public String getDbName() {
        return dbName;
    }

    public void clear() {
        diskIndex.clear();
        warmCache.clear();
    }

    @Override
    public void close() {
        clear();
    }
}
