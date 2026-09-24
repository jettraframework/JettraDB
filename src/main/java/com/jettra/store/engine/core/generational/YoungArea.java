package com.jettra.store.engine.core.generational;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Young Generation Area (Eden + Survivor) for JettraDB.
 * Serves as the primary high-throughput in-memory write buffer and hot read tier.
 * Backed by Java 25 Off-Heap Panama {@link GenerationalArena} to eliminate GC pauses.
 */
public class YoungArea implements AutoCloseable {

    public static final int DEFAULT_MAX_YOUNG_ENTRIES = 1000;
    public static final int DEFAULT_TENURE_THRESHOLD = 1;

    private final String dbName;
    private final int maxEntries;
    private final int tenureThreshold;
    private final GenerationalArena arena;
    private final ConcurrentHashMap<String, GenerationalRecord> records;
    private final AtomicLong totalWrites;
    private final AtomicLong youngReadHits;

    public YoungArea(String dbName) {
        this(dbName, DEFAULT_MAX_YOUNG_ENTRIES, DEFAULT_TENURE_THRESHOLD);
    }

    public YoungArea(String dbName, int maxEntries, int tenureThreshold) {
        this.dbName = dbName;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_YOUNG_ENTRIES;
        this.tenureThreshold = tenureThreshold >= 0 ? tenureThreshold : DEFAULT_TENURE_THRESHOLD;
        this.arena = new GenerationalArena();
        this.records = new ConcurrentHashMap<>();
        this.totalWrites = new AtomicLong(0);
        this.youngReadHits = new AtomicLong(0);
    }

    /**
     * Inserts or updates a record in the Young Area.
     * Off-heap native memory is allocated for the payload.
     */
    public GenerationalRecord put(String key, byte[] data, long timestamp, int version) {
        if (key == null) return null;
        totalWrites.incrementAndGet();

        long offset = -1;
        if (data != null && data.length > 0) {
            offset = arena.allocateRecord(data);
        }

        GenerationalRecord record = GenerationalRecord.active(key, data, timestamp, version, offset);
        records.put(key, record);
        return record;
    }

    /**
     * Fast-path read from Young Area.
     * Returns byte array payload if active, or null if deleted / not in Young Area.
     */
    public byte[] get(String key) {
        if (key == null) return null;
        GenerationalRecord record = records.get(key);
        if (record == null) {
            return null;
        }
        if (record.isTombstone()) {
            return null; // Fast tombstone short-circuit: record was deleted!
        }
        record.recordAccess();
        youngReadHits.incrementAndGet();
        return record.resolveData(arena);
    }

    /**
     * Checks if a key exists in Young Area as an active (non-tombstone) record.
     */
    public boolean containsActiveKey(String key) {
        if (key == null) return false;
        GenerationalRecord record = records.get(key);
        return record != null && !record.isTombstone();
    }

    /**
     * Checks if a key is marked as a tombstone in Young Area.
     */
    public boolean isTombstone(String key) {
        if (key == null) return false;
        GenerationalRecord record = records.get(key);
        return record != null && record.isTombstone();
    }

    /**
     * Marks a record as deleted (tombstone) in the Young Area.
     */
    public void delete(String key, long timestamp) {
        if (key == null) return;
        totalWrites.incrementAndGet();
        GenerationalRecord tombstone = GenerationalRecord.tombstone(key, timestamp);
        records.put(key, tombstone);
    }

    /**
     * Scans active records with the given prefix.
     */
    public Map<String, byte[]> scanPrefix(String prefix) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, GenerationalRecord> entry : records.entrySet()) {
            String k = entry.getKey();
            if (prefix == null || prefix.isEmpty() || k.startsWith(prefix)) {
                GenerationalRecord rec = entry.getValue();
                if (!rec.isTombstone()) {
                    byte[] data = rec.resolveData(arena);
                    if (data != null && data.length > 0) {
                        result.put(k, data);
                    }
                }
            }
        }
        return result;
    }

    public Set<String> scanPrefixKeys(String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, GenerationalRecord> entry : records.entrySet()) {
            String k = entry.getKey();
            if (prefix == null || prefix.isEmpty() || k.startsWith(prefix)) {
                if (!entry.getValue().isTombstone()) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    public boolean isThresholdReached() {
        return records.size() >= maxEntries || arena.getUtilizationPercentage() > 85.0;
    }

    public Map<String, GenerationalRecord> getRecords() {
        return records;
    }

    public GenerationalArena getArena() {
        return arena;
    }

    public int getTenureThreshold() {
        return tenureThreshold;
    }

    public int size() {
        return records.size();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public long getYoungReadHits() {
        return youngReadHits.get();
    }

    public long getTotalWrites() {
        return totalWrites.get();
    }

    public String getDbName() {
        return dbName;
    }

    public void remove(String key) {
        if (key != null) {
            records.remove(key);
        }
    }

    public void clear() {
        records.clear();
        arena.reset();
    }

    @Override
    public void close() {
        records.clear();
        arena.close();
    }
}
