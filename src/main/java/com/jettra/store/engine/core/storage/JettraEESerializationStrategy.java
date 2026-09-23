package com.jettra.store.engine.core.storage;

import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.ee.serialization.JettraSerializedRecord;

/**
 * Concrete strategy utilizing {@link JettraSerialization} from the JettraEE ecosystem
 * for ultra-fast, compact binary serialization with Java 25 Compact Object Headers.
 */
public class JettraEESerializationStrategy implements RecordSerializationStrategy {

    private static final String STRATEGY_NAME = "JettraSerialization (JettraEE Native)";

    @Override
    public byte[] serialize(String recordId, int version, long timestamp, byte[] payload) {
        return JettraSerialization.serializeRecord(recordId, version, timestamp, payload);
    }

    @Override
    public StorageRecordFile deserialize(String recordId, byte[] rawBytes) {
        JettraSerializedRecord rec = JettraSerialization.deserializeRecord(recordId, rawBytes);
        return new StorageRecordFile(rec.recordId(), rec.version(), rec.timestamp(), rec.payload());
    }

    @Override
    public byte[] extractPayload(byte[] rawBytes) {
        return JettraSerialization.extractPayload(rawBytes);
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }
}
