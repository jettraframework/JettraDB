package com.jettra.store.engine.core.generational;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metadata and payload descriptor for records living in JettraDB Generational Storage.
 * Encapsulates tenure age, access frequency, off-heap arena pointer, and tombstone state.
 */
public class GenerationalRecord {

    private final String key;
    private final long timestamp;
    private final int version;
    private final AtomicInteger tenureAge;
    private final AtomicInteger accessCount;
    private final AtomicLong lastAccessTime;
    private volatile long arenaOffset;
    private volatile byte[] heapData;
    private final boolean isTombstone;

    public GenerationalRecord(String key, byte[] data, long timestamp, int version, long arenaOffset, boolean isTombstone) {
        this.key = key;
        this.timestamp = timestamp;
        this.version = version;
        this.tenureAge = new AtomicInteger(0);
        this.accessCount = new AtomicInteger(1);
        this.lastAccessTime = new AtomicLong(System.currentTimeMillis());
        this.arenaOffset = arenaOffset;
        this.heapData = data;
        this.isTombstone = isTombstone;
    }

    public static GenerationalRecord active(String key, byte[] data, long timestamp, int version, long arenaOffset) {
        return new GenerationalRecord(key, data, timestamp, version, arenaOffset, false);
    }

    public static GenerationalRecord tombstone(String key, long timestamp) {
        return new GenerationalRecord(key, new byte[0], timestamp, 1, -1, true);
    }

    public String getKey() {
        return key;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getVersion() {
        return version;
    }

    public int getTenureAge() {
        return tenureAge.get();
    }

    public int incrementTenureAge() {
        return tenureAge.incrementAndGet();
    }

    public int getAccessCount() {
        return accessCount.get();
    }

    public void recordAccess() {
        accessCount.incrementAndGet();
        lastAccessTime.set(System.currentTimeMillis());
    }

    public long getLastAccessTime() {
        return lastAccessTime.get();
    }

    public long getArenaOffset() {
        return arenaOffset;
    }

    public void setArenaOffset(long arenaOffset) {
        this.arenaOffset = arenaOffset;
    }

    public byte[] getHeapData() {
        return heapData;
    }

    public void setHeapData(byte[] heapData) {
        this.heapData = heapData;
    }

    public boolean isTombstone() {
        return isTombstone || (heapData != null && heapData.length == 0);
    }

    /**
     * Resolves the record bytes from heap cache or reads from the off-heap Arena if cached copy was cleared.
     */
    public byte[] resolveData(GenerationalArena arena) {
        if (isTombstone()) {
            return null;
        }
        if (heapData != null && heapData.length > 0) {
            return heapData;
        }
        if (arenaOffset >= 0 && arena != null && !arena.isClosed()) {
            byte[] fromArena = arena.readRecord(arenaOffset);
            if (fromArena != null) {
                return fromArena;
            }
        }
        return null;
    }
}
