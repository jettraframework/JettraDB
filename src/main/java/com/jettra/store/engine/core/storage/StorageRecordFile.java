package com.jettra.store.engine.core.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable Java 25 Record representing the persistent binary format of an individual record (.dat).
 * <p>
 * Format specification (21-byte compact header):
 * - Magic bytes (4 bytes): 0x4A, 0x44, 0x41, 0x54 ("JDAT")
 * - Format version (1 byte): 0x01
 * - Record version (4 bytes): int
 * - Timestamp (8 bytes): long (epoch millis)
 * - Payload length (4 bytes): int
 * - Payload bytes: raw bytes
 */
public record StorageRecordFile(
    String recordId,
    int version,
    long timestamp,
    byte[] payload
) {
    public static final byte[] MAGIC = io.jettra.ee.serialization.CompactBinaryHeader.MAGIC_JDAT;
    public static final byte FORMAT_VERSION = io.jettra.ee.serialization.CompactBinaryHeader.DEFAULT_FORMAT_VERSION;
    public static final int HEADER_SIZE = io.jettra.ee.serialization.CompactBinaryHeader.HEADER_SIZE;

    public StorageRecordFile {
        Objects.requireNonNull(recordId, "recordId must not be null");
        payload = (payload != null) ? payload : new byte[0];
    }

    /**
     * Serializes this record into optimized binary format using the active JettraSerialization strategy.
     */
    public byte[] serialize() {
        return StorageEngineFactory.getDefaultStrategy().serialize(recordId, version, timestamp, payload);
    }

    /**
     * Serializes record attributes into an optimized binary array using the active JettraSerialization strategy.
     */
    public static byte[] serialize(int version, long timestamp, byte[] payload) {
        return StorageEngineFactory.getDefaultStrategy().serialize("", version, timestamp, payload);
    }

    /**
     * Deserializes an individual .dat file buffer into a StorageRecordFile using JettraSerialization.
     * Supports automatic fallback if bytes are legacy or non-header raw payloads.
     */
    public static StorageRecordFile deserialize(String recordId, byte[] rawBytes) {
        return StorageEngineFactory.getDefaultStrategy().deserialize(recordId, rawBytes);
    }

    /**
     * Extracts only payload bytes, bypassing full object creation if needed.
     */
    public static byte[] extractPayload(byte[] rawBytes) {
        return StorageEngineFactory.getDefaultStrategy().extractPayload(rawBytes);
    }

    /**
     * Returns payload as UTF-8 string.
     */
    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
