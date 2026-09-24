package com.jettra.store.engine.core.storage.slotted;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.*;

/**
 * 64-byte binary page header for slotted pages.
 * Manages fixed layout metadata, free space tracking, and CRC32 integrity validation.
 */
public final class PageHeader {

    private int magicNumber;
    private short formatVersion;
    private short pageType;
    private int pageSize;
    private short flags;
    private long pageId;
    private int slotCount;
    private int activeRecordCount;
    private int freeSpaceStart;
    private int freeSpaceEnd;
    private int fragmentedFreeSpace;
    private long lsn;
    private long checksum;

    public PageHeader(long pageId, int pageSize, short pageType) {
        this.magicNumber = MAGIC_NUMBER;
        this.formatVersion = FORMAT_VERSION;
        this.pageType = pageType;
        this.pageSize = pageSize;
        this.flags = PAGE_FLAG_CLEAN;
        this.pageId = pageId;
        this.slotCount = 0;
        this.activeRecordCount = 0;
        this.freeSpaceStart = HEADER_SIZE;
        this.freeSpaceEnd = pageSize;
        this.fragmentedFreeSpace = 0;
        this.lsn = System.currentTimeMillis();
        this.checksum = 0L;
    }

    /**
     * Reads a page header directly from the first 64 bytes of a ByteBuffer.
     */
    public static PageHeader readFrom(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        int originalPos = buffer.position();
        buffer.position(0);

        int magic = buffer.getInt();
        if (magic != MAGIC_NUMBER) {
            buffer.position(originalPos);
            throw new IllegalStateException("Invalid slotted page magic: 0x" + Integer.toHexString(magic));
        }

        short version = buffer.getShort();
        short pType = buffer.getShort();
        int pSize = buffer.getInt();
        short flags = buffer.getShort();
        buffer.getShort(); // reserved short

        long pId = buffer.getLong();
        int sCount = buffer.getInt();
        int activeCount = buffer.getInt();
        int fsStart = buffer.getInt();
        int fsEnd = buffer.getInt();
        int fragSpace = buffer.getInt();
        long lsnVal = buffer.getLong();
        long storedChecksum = buffer.getLong();
        buffer.getInt(); // padding to 64 bytes

        PageHeader header = new PageHeader(pId, pSize, pType);
        header.formatVersion = version;
        header.flags = flags;
        header.slotCount = sCount;
        header.activeRecordCount = activeCount;
        header.freeSpaceStart = fsStart;
        header.freeSpaceEnd = fsEnd;
        header.fragmentedFreeSpace = fragSpace;
        header.lsn = lsnVal;
        header.checksum = storedChecksum;

        buffer.position(originalPos);
        return header;
    }

    /**
     * Writes this page header into the first 64 bytes of the ByteBuffer.
     */
    public void writeTo(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        int originalPos = buffer.position();
        buffer.position(0);

        buffer.putInt(magicNumber);
        buffer.putShort(formatVersion);
        buffer.putShort(pageType);
        buffer.putInt(pageSize);
        buffer.putShort(flags);
        buffer.putShort((short) 0); // reserved

        buffer.putLong(pageId);
        buffer.putInt(slotCount);
        buffer.putInt(activeRecordCount);
        buffer.putInt(freeSpaceStart);
        buffer.putInt(freeSpaceEnd);
        buffer.putInt(fragmentedFreeSpace);
        buffer.putLong(lsn);
        buffer.putLong(checksum);
        buffer.putInt(0); // padding

        buffer.position(originalPos);
    }

    /**
     * Calculates the CRC32 checksum of the page, excluding the checksum field itself.
     */
    public long computeChecksum(ByteBuffer buffer) {
        CRC32 crc = new CRC32();
        int originalPos = buffer.position();

        // Checksum bytes 0..51 of header
        byte[] temp = new byte[52];
        buffer.position(0);
        buffer.get(temp);
        crc.update(temp);

        // Skip 8 bytes of checksum (bytes 52..59), checksum remaining header bytes 60..63
        byte[] pad = new byte[4];
        buffer.position(60);
        buffer.get(pad);
        crc.update(pad);

        // Checksum page body from HEADER_SIZE to pageSize
        byte[] body = new byte[pageSize - HEADER_SIZE];
        buffer.position(HEADER_SIZE);
        buffer.get(body);
        crc.update(body);

        buffer.position(originalPos);
        return crc.getValue();
    }

    public void updateChecksum(ByteBuffer buffer) {
        this.checksum = computeChecksum(buffer);
        buffer.putLong(52, this.checksum);
    }

    public boolean validateChecksum(ByteBuffer buffer) {
        long computed = computeChecksum(buffer);
        return this.checksum == 0L || this.checksum == computed;
    }

    // Getters and Setters
    public int getMagicNumber() { return magicNumber; }
    public short getFormatVersion() { return formatVersion; }
    public short getPageType() { return pageType; }
    public int getPageSize() { return pageSize; }
    public short getFlags() { return flags; }
    public void setFlags(short flags) { this.flags = flags; }
    public long getPageId() { return pageId; }
    public int getSlotCount() { return slotCount; }
    public void setSlotCount(int slotCount) { this.slotCount = slotCount; }
    public int getActiveRecordCount() { return activeRecordCount; }
    public void setActiveRecordCount(int activeRecordCount) { this.activeRecordCount = activeRecordCount; }
    public int getFreeSpaceStart() { return freeSpaceStart; }
    public void setFreeSpaceStart(int freeSpaceStart) { this.freeSpaceStart = freeSpaceStart; }
    public int getFreeSpaceEnd() { return freeSpaceEnd; }
    public void setFreeSpaceEnd(int freeSpaceEnd) { this.freeSpaceEnd = freeSpaceEnd; }
    public int getFragmentedFreeSpace() { return fragmentedFreeSpace; }
    public void setFragmentedFreeSpace(int fragmentedFreeSpace) { this.fragmentedFreeSpace = fragmentedFreeSpace; }
    public long getLsn() { return lsn; }
    public void setLsn(long lsn) { this.lsn = lsn; }
    public long getChecksum() { return checksum; }
    public void setChecksum(long checksum) { this.checksum = checksum; }

    public int getContiguousFreeSpace() {
        return Math.max(0, freeSpaceEnd - freeSpaceStart);
    }

    public int getTotalFreeSpace() {
        return getContiguousFreeSpace() + fragmentedFreeSpace;
    }
}
