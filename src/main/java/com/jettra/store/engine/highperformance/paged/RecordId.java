package com.jettra.store.engine.highperformance.paged;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable physical Record Identifier (RID) inspired by ArcadeDB architecture.
 *
 * <p>Represents a direct pointer to a physical storage location without requiring
 * secondary index traversals or hash lookups:
 * <ul>
 *   <li>{@code fileId}: Identifier of the physical bucket/file partition.</li>
 *   <li>{@code pageIndex}: Zero-based index of the 64KB physical page within the file.</li>
 *   <li>{@code offset}: Byte offset within the page where the record header/body begins.</li>
 * </ul>
 */
public record RecordId(int fileId, long pageIndex, int offset) implements Comparable<RecordId> {

    public static final int BINARY_SIZE = 16; // 4 + 8 + 4 bytes

    public RecordId {
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must be non-negative: " + fileId);
        }
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be non-negative: " + pageIndex);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }
    }

    /**
     * Parses a RID string representation in the canonical format {@code #fileId:pageIndex:offset}
     * or {@code fileId:pageIndex:offset}.
     *
     * @param ridString Canonical string representation.
     * @return Corresponding RecordId instance.
     */
    public static RecordId parse(String ridString) {
        Objects.requireNonNull(ridString, "ridString cannot be null");
        String clean = ridString.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        String[] parts = clean.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid RecordId format: '" + ridString + "'. Expected #fileId:pageIndex:offset");
        }
        try {
            int fileId = Integer.parseInt(parts[0].trim());
            long pageIndex = Long.parseLong(parts[1].trim());
            int offset = Integer.parseInt(parts[2].trim());
            return new RecordId(fileId, pageIndex, offset);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric components in RecordId: " + ridString, e);
        }
    }

    /**
     * Encodes this RecordId into a compact 16-byte buffer.
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(BINARY_SIZE);
        buffer.putInt(fileId);
        buffer.putLong(pageIndex);
        buffer.putInt(offset);
        return buffer.array();
    }

    /**
     * Writes this RecordId into an existing ByteBuffer at current position.
     */
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(fileId);
        buffer.putLong(pageIndex);
        buffer.putInt(offset);
    }

    /**
     * Decodes a RecordId from a 16-byte array.
     */
    public static RecordId fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < BINARY_SIZE) {
            throw new IllegalArgumentException("Byte array must contain at least " + BINARY_SIZE + " bytes for RecordId");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return fromBuffer(buffer);
    }

    /**
     * Reads a RecordId from the current position of a ByteBuffer.
     */
    public static RecordId fromBuffer(ByteBuffer buffer) {
        int fileId = buffer.getInt();
        long pageIndex = buffer.getLong();
        int offset = buffer.getInt();
        return new RecordId(fileId, pageIndex, offset);
    }

    @Override
    public String toString() {
        return "#" + fileId + ":" + pageIndex + ":" + offset;
    }

    @Override
    public int compareTo(RecordId other) {
        if (this.fileId != other.fileId) {
            return Integer.compare(this.fileId, other.fileId);
        }
        if (this.pageIndex != other.pageIndex) {
            return Long.compare(this.pageIndex, other.pageIndex);
        }
        return Integer.compare(this.offset, other.offset);
    }
}
