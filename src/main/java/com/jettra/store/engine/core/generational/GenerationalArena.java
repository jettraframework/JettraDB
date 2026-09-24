package com.jettra.store.engine.core.generational;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Off-Heap Native Memory Arena Manager for JettraDB Generational Storage.
 * Utilizes Java 25 Foreign Function & Memory API (Project Panama: {@link Arena} and {@link MemorySegment})
 * to store hot record payloads off-heap, completely eliminating JVM GC pauses and heap fragmentation.
 */
public class GenerationalArena implements AutoCloseable {

    private static final int DEFAULT_ARENA_SIZE = 16 * 1024 * 1024; // 16 MB per arena segment
    private final Arena arena;
    private MemorySegment segment;
    private final long capacity;
    private final AtomicLong writeOffset;
    private final ReentrantLock allocationLock;
    private volatile boolean closed = false;

    public GenerationalArena() {
        this(DEFAULT_ARENA_SIZE);
    }

    public GenerationalArena(long capacityBytes) {
        this.capacity = capacityBytes > 0 ? capacityBytes : DEFAULT_ARENA_SIZE;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(this.capacity, 8);
        this.writeOffset = new AtomicLong(0);
        this.allocationLock = new ReentrantLock();
    }

    /**
     * Allocates a contiguous native memory slice for the record payload.
     * Layout: [4 bytes payload length][N bytes payload data]
     *
     * @param data Payload byte array.
     * @return Offset handle in the arena, or -1 if the arena cannot fit this record.
     */
    public long allocateRecord(byte[] data) {
        if (closed || data == null) {
            return -1;
        }
        int totalLen = 4 + data.length;
        int paddedLen = (totalLen + 7) & ~7; // 8-byte alignment for hardware and Panama memory
        allocationLock.lock();
        try {
            long currentOffset = writeOffset.get();
            if (currentOffset + paddedLen > capacity) {
                return -1; // Arena full; triggers minor compaction / promotion
            }
            writeOffset.addAndGet(paddedLen);

            // Write 4-byte integer length safely
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, currentOffset, data.length);
            // Write payload data bytes
            MemorySegment.copy(data, 0, segment, ValueLayout.JAVA_BYTE, currentOffset + 4, data.length);

            return currentOffset;
        } finally {
            allocationLock.unlock();
        }
    }

    /**
     * Reads a record payload directly from native off-heap memory.
     *
     * @param offset Offset handle returned by {@link #allocateRecord(byte[])}.
     * @return Reconstructed byte array.
     */
    public byte[] readRecord(long offset) {
        if (closed || offset < 0 || offset + 4 > capacity) {
            return null;
        }
        try {
            int length = segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
            if (length < 0 || offset + 4 + length > capacity) {
                return null;
            }
            byte[] data = new byte[length];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset + 4, data, 0, length);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resets the allocation offset to zero, reclaiming all off-heap arena memory
     * without JVM Garbage Collection overhead.
     */
    public void reset() {
        allocationLock.lock();
        try {
            writeOffset.set(0);
        } finally {
            allocationLock.unlock();
        }
    }

    public long getAllocatedBytes() {
        return writeOffset.get();
    }

    public long getCapacityBytes() {
        return capacity;
    }

    public double getUtilizationPercentage() {
        return (double) writeOffset.get() * 100.0 / (double) capacity;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        allocationLock.lock();
        try {
            arena.close();
        } catch (Exception ignored) {
        } finally {
            allocationLock.unlock();
        }
    }
}
