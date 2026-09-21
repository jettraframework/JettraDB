package com.jettra.store.engine.highperformance.paged;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Physical Storage Page structure inspired by ArcadeDB page architecture.
 *
 * <p>Each page is of fixed size (default 64 KB = 65,536 bytes).
 * Page Layout:
 * <pre>
 * +-----------------------------------------------------------------+
 * | Header (32 bytes):                                             |
 * |  [0..0]   Magic Byte (0x50 'P')                                 |
 * |  [1..1]   Version (1)                                           |
 * |  [2..5]   File ID (int)                                         |
 * |  [6..13]  Page Index (long)                                     |
 * |  [14..17] Free Offset (int)                                     |
 * |  [18..21] Record Count (int)                                    |
 * |  [22..23] Flags (short)                                         |
 * |  [24..31] CRC32 Checksum (long)                                 |
 * +-----------------------------------------------------------------+
 * | Records Payload (starts at byte 32 up to PAGE_SIZE):            |
 * |  For each record:                                               |
 * |    [0..3]  Payload length (int)                                 |
 * |    [4..5]  Record Type / Status (short: 1=active, 2=tombstone)  |
 * |    [6..N]  Raw Record bytes                                     |
 * +-----------------------------------------------------------------+
 * </pre>
 */
public class Page {

    public static final int PAGE_SIZE = 64 * 1024; // 64 KB
    public static final int HEADER_SIZE = 32;
    public static final byte MAGIC_BYTE = 0x50; // 'P'
    public static final byte CURRENT_VERSION = 1;

    public static final short STATUS_ACTIVE = 1;
    public static final short STATUS_DELETED = 2;

    private final int fileId;
    private final long pageIndex;
    private final byte[] rawData;
    private int freeOffset;
    private int recordCount;
    private short flags;
    private boolean dirty;

    /**
     * Creates a new blank physical page.
     */
    public Page(int fileId, long pageIndex) {
        this.fileId = fileId;
        this.pageIndex = pageIndex;
        this.rawData = new byte[PAGE_SIZE];
        this.freeOffset = HEADER_SIZE;
        this.recordCount = 0;
        this.flags = 0;
        this.dirty = true;
        writeHeader();
    }

    /**
     * Restores a page from raw bytes read from disk.
     */
    public Page(byte[] bytes) {
        if (bytes == null || bytes.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page raw data must be exactly " + PAGE_SIZE + " bytes");
        }
        this.rawData = Arrays.copyOf(bytes, PAGE_SIZE);
        ByteBuffer buf = ByteBuffer.wrap(this.rawData);

        byte magic = buf.get();
        if (magic != MAGIC_BYTE) {
            throw new IllegalStateException("Corrupted page: invalid magic byte 0x" + Integer.toHexString(magic));
        }
        byte version = buf.get();
        this.fileId = buf.getInt();
        this.pageIndex = buf.getLong();
        this.freeOffset = buf.getInt();
        this.recordCount = buf.getInt();
        this.flags = buf.getShort();
        long storedChecksum = buf.getLong();

        // Validate checksum
        long calculated = calculateChecksum();
        if (storedChecksum != 0 && calculated != storedChecksum) {
            throw new IllegalStateException("Checksum mismatch on page #" + fileId + ":" + pageIndex);
        }
        this.dirty = false;
    }

    private void writeHeader() {
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.put(MAGIC_BYTE);
        buf.put(CURRENT_VERSION);
        buf.putInt(fileId);
        buf.putLong(pageIndex);
        buf.putInt(freeOffset);
        buf.putInt(recordCount);
        buf.putShort(flags);
        buf.putLong(0L); // Checksum placeholder, computed before disk sync
    }

    public long calculateChecksum() {
        CRC32 crc = new CRC32();
        crc.update(rawData, 0, 24); // header up to checksum position
        crc.update(rawData, HEADER_SIZE, PAGE_SIZE - HEADER_SIZE); // payload
        return crc.getValue();
    }

    public void updateChecksum() {
        long checksum = calculateChecksum();
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.putLong(24, checksum);
    }

    public int getFileId() {
        return fileId;
    }

    public long getPageIndex() {
        return pageIndex;
    }

    public int getFreeOffset() {
        return freeOffset;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public byte[] getRawData() {
        return rawData;
    }

    public int getAvailableSpace() {
        return PAGE_SIZE - freeOffset;
    }

    public boolean canFit(int recordLength) {
        return getAvailableSpace() >= (6 + recordLength); // 4 bytes len + 2 bytes status + payload
    }

    /**
     * Appends a record to this page.
     *
     * @param recordData The serialized record bytes.
     * @return The offset inside this page where the record was stored.
     */
    public synchronized int appendRecord(byte[] recordData) {
        int required = 6 + recordData.length;
        if (getAvailableSpace() < required) {
            throw new IllegalStateException("Page #" + fileId + ":" + pageIndex + " is full. Available: " 
                    + getAvailableSpace() + ", Required: " + required);
        }

        int writeOffset = freeOffset;
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.position(writeOffset);
        buf.putInt(recordData.length);
        buf.putShort(STATUS_ACTIVE);
        buf.put(recordData);

        freeOffset += required;
        recordCount++;
        dirty = true;

        // Update header fields
        buf.putInt(14, freeOffset);
        buf.putInt(18, recordCount);

        return writeOffset;
    }

    /**
     * Reads a record from a specific offset within this page.
     *
     * @param offset Byte offset where the record begins.
     * @return The record payload bytes, or null if deleted.
     */
    public synchronized byte[] readRecord(int offset) {
        if (offset < HEADER_SIZE || offset >= freeOffset) {
            throw new IndexOutOfBoundsException("Invalid record offset " + offset + " in page #" + fileId + ":" + pageIndex);
        }

        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.position(offset);
        int len = buf.getInt();
        short status = buf.getShort();

        if (status == STATUS_DELETED) {
            return null; // Tombstone
        }

        if (len < 0 || (offset + 6 + len) > PAGE_SIZE) {
            throw new IllegalStateException("Corrupted record length " + len + " at offset " + offset);
        }

        byte[] payload = new byte[len];
        buf.get(payload);
        return payload;
    }

    /**
     * Marks a record at the specified offset as deleted (tombstone).
     */
    public synchronized boolean deleteRecord(int offset) {
        if (offset < HEADER_SIZE || offset >= freeOffset) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.position(offset + 4); // skip length int
        buf.putShort(STATUS_DELETED);
        dirty = true;
        return true;
    }
}
