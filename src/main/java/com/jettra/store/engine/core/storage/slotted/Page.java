package com.jettra.store.engine.core.storage.slotted;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.*;

/**
 * Fixed-Size Slotted Page (Slotted-Page Architecture).
 *
 * <p>Memory Layout:
 * <pre>
 * +-------------------------------------------------------------------------+
 * | Page Header (64 bytes):                                                 |
 * |  Magic (4B), Version (2B), PageType (2B), PageSize (4B), Flags (2B),     |
 * |  PageID (8B), SlotCount (4B), ActiveCount (4B), FreeSpaceStart (4B),   |
 * |  FreeSpaceEnd (4B), FragSpace (4B), LSN (8B), Checksum (8B), Pad (4B)  |
 * +-------------------------------------------------------------------------+
 * | Slot Directory Array (starts at byte 64, grows UPWARD ->):             |
 * |  Slot 0: offset(4B), length(4B), status(2B), flags(2B), version(4B)     |
 * |  Slot 1: offset(4B), length(4B), status(2B), flags(2B), version(4B)     |
 * |  ...                                                                    |
 * |  Slot N: (ends at FreeSpaceStart)                                       |
 * +-------------------------------------------------------------------------+
 * |                   CONTIGUOUS FREE SPACE GAP                             |
 * |  (from FreeSpaceStart to FreeSpaceEnd)                                  |
 * +-------------------------------------------------------------------------+
 * | Record Data Area (grows DOWNWARD <- from PageSize down to FreeSpaceEnd):|
 * |  For each record:                                                       |
 * |    keyLength (2B), keyBytes (NB), timestamp (8B), payloadLength (4B),   |
 * |    payload (raw bytes)                                                  |
 * +-------------------------------------------------------------------------+
 * </pre>
 *
 * <p>Supports in-place updates, zero-copy buffer operations, GC-free Off-Heap memory,
 * and internal defragmentation via {@link PageCompactionStrategy}.
 */
public class Page {

    public record RecordEntry(String key, byte[] payload, int version, long timestamp) {}

    private final ByteBuffer buffer;
    private final PageHeader header;
    private final PageCompactionStrategy compactionStrategy;
    private final AtomicInteger pinCount;
    private final ReentrantReadWriteLock rwLock;
    private final boolean memoryMapped;
    private volatile boolean dirty;

    /**
     * Initializes a new fixed-size slotted page.
     *
     * @param pageId The unique identifier of this page.
     * @param pageSize Total byte size of the page (e.g. 4096, 8192, 65536).
     * @param direct If true, allocates an Off-Heap Direct ByteBuffer to avoid GC pauses.
     * @param compactionStrategy Strategy for internal page defragmentation.
     */
    public Page(long pageId, int pageSize, boolean direct, PageCompactionStrategy compactionStrategy) {
        if (pageSize < PAGE_SIZE_4KB || (pageSize % 512 != 0)) {
            throw new IllegalArgumentException("Page size must be a multiple of 512 and >= 4KB: " + pageSize);
        }
        this.buffer = direct ? ByteBuffer.allocateDirect(pageSize) : ByteBuffer.allocate(pageSize);
        this.header = new PageHeader(pageId, pageSize, PAGE_TYPE_DATA);
        this.compactionStrategy = (compactionStrategy != null) ? compactionStrategy : new InPlacePageCompactionStrategy();
        this.pinCount = new AtomicInteger(0);
        this.rwLock = new ReentrantReadWriteLock();
        this.memoryMapped = false;
        this.dirty = true;

        this.header.writeTo(this.buffer);
        updateChecksum();
    }

    /**
     * Wraps an existing ByteBuffer into a slotted page with optional memory mapping flag.
     */
    public Page(ByteBuffer existingBuffer, PageCompactionStrategy compactionStrategy, boolean memoryMapped) {
        Objects.requireNonNull(existingBuffer, "existingBuffer cannot be null");
        this.buffer = existingBuffer;
        this.header = PageHeader.readFrom(existingBuffer);
        this.compactionStrategy = (compactionStrategy != null) ? compactionStrategy : new InPlacePageCompactionStrategy();
        this.pinCount = new AtomicInteger(0);
        this.rwLock = new ReentrantReadWriteLock();
        this.memoryMapped = memoryMapped;
        this.dirty = false;
    }

    /**
     * Wraps an existing ByteBuffer into a slotted page.
     */
    public Page(ByteBuffer existingBuffer, PageCompactionStrategy compactionStrategy) {
        this(existingBuffer, compactionStrategy, false);
    }

    public long getPageId() { return header.getPageId(); }
    public int getPageSize() { return header.getPageSize(); }
    public PageHeader getHeader() { return header; }
    public ByteBuffer getByteBuffer() { return buffer; }
    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public int getSlotCount() { return header.getSlotCount(); }
    public int getActiveRecordCount() { return header.getActiveRecordCount(); }
    public int getContiguousFreeSpace() { return header.getContiguousFreeSpace(); }
    public int getTotalFreeSpace() { return header.getTotalFreeSpace(); }

    public int getPinCount() { return pinCount.get(); }
    public void pin() { pinCount.incrementAndGet(); }
    public void unpin() {
        if (pinCount.decrementAndGet() < 0) {
            pinCount.set(0);
        }
    }

    public void lockRead() { rwLock.readLock().lock(); }
    public void unlockRead() { rwLock.readLock().unlock(); }
    public void lockWrite() { rwLock.writeLock().lock(); }
    public void unlockWrite() { rwLock.writeLock().unlock(); }

    /**
     * Checks if this page can accommodate a record of the specified payload length.
     */
    public boolean canAccommodate(int payloadBytes) {
        int required = calculateStoredRecordLength("", payloadBytes) + SLOT_ENTRY_SIZE;
        return getTotalFreeSpace() >= required;
    }

    /**
     * Computes total bytes required on-page to store a serialized record.
     */
    public static int calculateStoredRecordLength(String key, int payloadLength) {
        byte[] kb = (key != null) ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
        // 2 bytes keyLen + keyBytes + 8 bytes timestamp + 4 bytes payloadLen + payload
        return 2 + kb.length + 8 + 4 + payloadLength;
    }

    /**
     * Reads a slot entry from the slot directory array.
     */
    public Slot getSlot(int slotId) {
        if (slotId < 0 || slotId >= header.getSlotCount()) {
            throw new IndexOutOfBoundsException("Invalid slotId " + slotId + ", slotCount=" + header.getSlotCount());
        }
        int slotOffset = HEADER_SIZE + (slotId * SLOT_ENTRY_SIZE);
        int originalPos = buffer.position();
        buffer.position(slotOffset);

        int offset = buffer.getInt();
        int length = buffer.getInt();
        short status = buffer.getShort();
        short flags = buffer.getShort();
        int version = buffer.getInt();

        buffer.position(originalPos);
        return new Slot(slotId, offset, length, status, flags, version);
    }

    /**
     * Writes or updates a slot entry in the slot directory array.
     */
    public void setSlot(int slotId, Slot slot) {
        if (slotId < 0 || slotId >= header.getSlotCount()) {
            throw new IndexOutOfBoundsException("Invalid slotId " + slotId + ", slotCount=" + header.getSlotCount());
        }
        int slotOffset = HEADER_SIZE + (slotId * SLOT_ENTRY_SIZE);
        int originalPos = buffer.position();
        buffer.position(slotOffset);

        buffer.putInt(slot.offset());
        buffer.putInt(slot.length());
        buffer.putShort(slot.status());
        buffer.putShort(slot.flags());
        buffer.putInt(slot.version());

        buffer.position(originalPos);
        this.dirty = true;
    }

    /**
     * Inserts a new record into this page.
     *
     * @param key The logical key/ID of the record.
     * @param payload The serialized payload bytes.
     * @param version The record version number.
     * @param timestamp Epoch timestamp.
     * @return The slotId allocated for this record, or -1 if page cannot accommodate it.
     */
    public int insertRecord(String key, byte[] payload, int version, long timestamp) {
        lockWrite();
        try {
            byte[] p = (payload != null) ? payload : new byte[0];
            byte[] kb = (key != null) ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
            int storedLen = 2 + kb.length + 8 + 4 + p.length;

            // 1. Look for a deleted slot to reuse
            int targetSlotId = -1;
            for (int i = 0; i < header.getSlotCount(); i++) {
                Slot existing = getSlot(i);
                if (existing.isDeleted()) {
                    targetSlotId = i;
                    break;
                }
            }

            boolean needsNewSlot = (targetSlotId == -1);
            int requiredTotal = storedLen + (needsNewSlot ? SLOT_ENTRY_SIZE : 0);

            // 2. Check if we need internal defragmentation/compaction
            if (header.getContiguousFreeSpace() < requiredTotal) {
                if (header.getTotalFreeSpace() >= requiredTotal) {
                    compact();
                } else {
                    return -1; // Cannot fit in this page
                }
            }

            if (header.getContiguousFreeSpace() < requiredTotal) {
                return -1;
            }

            // 3. Allocate slot ID if not reusing
            if (needsNewSlot) {
                targetSlotId = header.getSlotCount();
                header.setSlotCount(targetSlotId + 1);
                header.setFreeSpaceStart(HEADER_SIZE + ((targetSlotId + 1) * SLOT_ENTRY_SIZE));
            }

            // 4. Write record bytes starting downward from freeSpaceEnd
            int writeOffset = header.getFreeSpaceEnd() - storedLen;
            int origPos = buffer.position();
            buffer.position(writeOffset);
            buffer.putShort((short) kb.length);
            buffer.put(kb);
            buffer.putLong(timestamp);
            buffer.putInt(p.length);
            buffer.put(p);
            buffer.position(origPos);

            // 5. Update header and write slot entry
            header.setFreeSpaceEnd(writeOffset);
            header.setActiveRecordCount(header.getActiveRecordCount() + 1);
            header.writeTo(buffer);

            Slot newSlot = new Slot(targetSlotId, writeOffset, storedLen, SLOT_STATUS_ACTIVE, (short) 0, version);
            setSlot(targetSlotId, newSlot);

            updateChecksum();
            this.dirty = true;
            return targetSlotId;
        } finally {
            unlockWrite();
        }
    }

    /**
     * Reads the payload bytes of the record at the specified slot.
     */
    public byte[] readRecordPayload(int slotId) {
        lockRead();
        try {
            Slot slot = getSlot(slotId);
            if (!slot.isActive() || slot.length() <= 0) {
                return null;
            }

            int origPos = buffer.position();
            buffer.position(slot.offset());

            short kLen = buffer.getShort();
            buffer.position(buffer.position() + kLen + 8); // skip key and timestamp
            int pLen = buffer.getInt();
            if (pLen < 0 || pLen > slot.length()) {
                buffer.position(origPos);
                throw new IllegalStateException("Corrupt payload length " + pLen + " in slot #" + slotId);
            }

            byte[] payload = new byte[pLen];
            buffer.get(payload);
            buffer.position(origPos);
            return payload;
        } finally {
            unlockRead();
        }
    }

    /**
     * Reads the complete record entry including key, payload, version, and timestamp.
     */
    public RecordEntry readRecordEntry(int slotId) {
        lockRead();
        try {
            Slot slot = getSlot(slotId);
            if (!slot.isActive() || slot.length() <= 0) {
                return null;
            }

            int origPos = buffer.position();
            buffer.position(slot.offset());

            short kLen = buffer.getShort();
            byte[] kb = new byte[kLen];
            buffer.get(kb);
            String key = new String(kb, StandardCharsets.UTF_8);

            long ts = buffer.getLong();
            int pLen = buffer.getInt();
            byte[] payload = new byte[pLen];
            buffer.get(payload);
            buffer.position(origPos);

            return new RecordEntry(key, payload, slot.version(), ts);
        } finally {
            unlockRead();
        }
    }

    /**
     * Performs an in-place update of a record within this slotted page.
     *
     * <p>Update Strategy:
     * <ol>
     *   <li>If new length <= old slot length, overwrites directly in-place without fragmentation.</li>
     *   <li>If new length > old slot length and fits in contiguous free space, marks old area as
     *       fragmented and places new data at freeSpaceEnd.</li>
     *   <li>If contiguous space is insufficient but total free space suffices, compacts the page in-place
     *       and writes the new record.</li>
     * </ol>
     *
     * @return true if updated in-place successfully, false if record does not fit in this page.
     */
    public boolean updateRecordInPlace(int slotId, String key, byte[] newPayload, int newVersion, long timestamp) {
        lockWrite();
        try {
            Slot oldSlot = getSlot(slotId);
            if (!oldSlot.isActive()) {
                return false;
            }

            byte[] p = (newPayload != null) ? newPayload : new byte[0];
            byte[] kb = (key != null) ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
            int newStoredLen = 2 + kb.length + 8 + 4 + p.length;

            // Case 1: Fits within existing slot displacement in-place
            if (newStoredLen <= oldSlot.length()) {
                int origPos = buffer.position();
                buffer.position(oldSlot.offset());
                buffer.putShort((short) kb.length);
                buffer.put(kb);
                buffer.putLong(timestamp);
                buffer.putInt(p.length);
                buffer.put(p);
                buffer.position(origPos);

                int excessSpace = oldSlot.length() - newStoredLen;
                if (excessSpace > 0) {
                    header.setFragmentedFreeSpace(header.getFragmentedFreeSpace() + excessSpace);
                    header.writeTo(buffer);
                }

                Slot updatedSlot = new Slot(slotId, oldSlot.offset(), newStoredLen, SLOT_STATUS_ACTIVE, oldSlot.flags(), newVersion);
                setSlot(slotId, updatedSlot);

                updateChecksum();
                this.dirty = true;
                return true;
            }

            // Case 2: Exceeds old slot length - check if it fits elsewhere in page
            int requiredDelta = newStoredLen;
            if (header.getContiguousFreeSpace() < requiredDelta) {
                // Check if internal compaction yields enough space
                int potentialFree = header.getTotalFreeSpace() + oldSlot.length();
                if (potentialFree >= requiredDelta) {
                    // Mark old slot as deleted temporarily so compaction consolidates its space
                    setSlot(slotId, oldSlot.asDeleted());
                    header.setActiveRecordCount(header.getActiveRecordCount() - 1);
                    header.setFragmentedFreeSpace(header.getFragmentedFreeSpace() + oldSlot.length());
                    compact();

                    // Now contiguous space is consolidated!
                    int writeOffset = header.getFreeSpaceEnd() - newStoredLen;
                    int origPos = buffer.position();
                    buffer.position(writeOffset);
                    buffer.putShort((short) kb.length);
                    buffer.put(kb);
                    buffer.putLong(timestamp);
                    buffer.putInt(p.length);
                    buffer.put(p);
                    buffer.position(origPos);

                    header.setFreeSpaceEnd(writeOffset);
                    header.setActiveRecordCount(header.getActiveRecordCount() + 1);
                    header.writeTo(buffer);

                    Slot updatedSlot = new Slot(slotId, writeOffset, newStoredLen, SLOT_STATUS_ACTIVE, oldSlot.flags(), newVersion);
                    setSlot(slotId, updatedSlot);

                    updateChecksum();
                    this.dirty = true;
                    return true;
                } else {
                    return false; // Cannot fit in this page
                }
            }

            // Fits in contiguous space without compaction
            header.setFragmentedFreeSpace(header.getFragmentedFreeSpace() + oldSlot.length());
            int writeOffset = header.getFreeSpaceEnd() - newStoredLen;
            int origPos = buffer.position();
            buffer.position(writeOffset);
            buffer.putShort((short) kb.length);
            buffer.put(kb);
            buffer.putLong(timestamp);
            buffer.putInt(p.length);
            buffer.put(p);
            buffer.position(origPos);

            header.setFreeSpaceEnd(writeOffset);
            header.writeTo(buffer);

            Slot updatedSlot = new Slot(slotId, writeOffset, newStoredLen, SLOT_STATUS_ACTIVE, oldSlot.flags(), newVersion);
            setSlot(slotId, updatedSlot);

            updateChecksum();
            this.dirty = true;
            return true;
        } finally {
            unlockWrite();
        }
    }

    /**
     * Marks a record as deleted (tombstone), freeing space for future compaction or slot reuse.
     */
    public boolean deleteRecord(int slotId) {
        lockWrite();
        try {
            Slot slot = getSlot(slotId);
            if (!slot.isActive()) {
                return false;
            }

            setSlot(slotId, slot.asDeleted());
            header.setActiveRecordCount(Math.max(0, header.getActiveRecordCount() - 1));
            header.setFragmentedFreeSpace(header.getFragmentedFreeSpace() + slot.length());
            header.writeTo(buffer);

            updateChecksum();
            this.dirty = true;
            return true;
        } finally {
            unlockWrite();
        }
    }

    /**
     * Triggers internal page defragmentation using the assigned {@link PageCompactionStrategy}.
     */
    public int compact() {
        lockWrite();
        try {
            int reclaimed = compactionStrategy.compact(this);
            updateChecksum();
            this.dirty = true;
            return reclaimed;
        } finally {
            unlockWrite();
        }
    }

    public void updateChecksum() {
        header.updateChecksum(buffer);
    }

    public boolean validateChecksum() {
        return header.validateChecksum(buffer);
    }

    /**
     * Checks if this page buffer is backed by virtual memory mapping (MappedByteBuffer).
     */
    public boolean isMemoryMapped() {
        return memoryMapped;
    }

    /**
     * Synchronizes this page to disk at the designated file position using FileChannel
     * or MappedByteBuffer virtual memory sync.
     */
    public void writeTo(FileChannel channel, long fileOffset) throws IOException {
        lockRead();
        try {
            updateChecksum();
            header.writeTo(buffer);
            if (memoryMapped && buffer instanceof java.nio.MappedByteBuffer mbb) {
                mbb.force();
            } else if (channel != null && channel.isOpen()) {
                buffer.position(0);
                long currentFilePos = fileOffset;
                while (buffer.hasRemaining()) {
                    int written = channel.write(buffer, currentFilePos);
                    currentFilePos += written;
                }
                buffer.position(0);
            }
            this.dirty = false;
        } finally {
            unlockRead();
        }
    }

    /**
     * Restores page contents from disk via FileChannel.
     */
    public void readFrom(FileChannel channel, long fileOffset) throws IOException {
        lockWrite();
        try {
            buffer.position(0);
            long currentFilePos = fileOffset;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, currentFilePos);
                if (read < 0) break;
                currentFilePos += read;
            }
            buffer.position(0);
            PageHeader loaded = PageHeader.readFrom(buffer);
            header.setSlotCount(loaded.getSlotCount());
            header.setActiveRecordCount(loaded.getActiveRecordCount());
            header.setFreeSpaceStart(loaded.getFreeSpaceStart());
            header.setFreeSpaceEnd(loaded.getFreeSpaceEnd());
            header.setFragmentedFreeSpace(loaded.getFragmentedFreeSpace());
            header.setFlags(loaded.getFlags());
            header.setLsn(loaded.getLsn());
            header.setChecksum(loaded.getChecksum());
            this.dirty = false;
        } finally {
            unlockWrite();
        }
    }
}
