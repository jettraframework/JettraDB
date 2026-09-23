package com.jettra.store.engine.core.storage;

/**
 * Strategy interface (Strategy Pattern) defining record serialization and deserialization
 * mechanisms for individual file storage in JettraDB.
 */
public interface RecordSerializationStrategy {

    /**
     * Serializes record attributes and payload into an optimized binary array.
     */
    byte[] serialize(String recordId, int version, long timestamp, byte[] payload);

    /**
     * Deserializes a binary array into a {@link StorageRecordFile}.
     */
    StorageRecordFile deserialize(String recordId, byte[] rawBytes);

    /**
     * Extracts only the payload bytes directly from raw storage bytes without
     * creating unnecessary object allocations.
     */
    byte[] extractPayload(byte[] rawBytes);

    /**
     * Returns the descriptive name and origin of this serialization strategy.
     */
    String getStrategyName();
}
