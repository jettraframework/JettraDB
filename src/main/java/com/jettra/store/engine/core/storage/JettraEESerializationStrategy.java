package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.storage.compression.AdaptiveRecordCompressionStrategy;
import com.jettra.store.engine.core.storage.compression.RecordCompressionStrategy;
import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.ee.serialization.JettraSerializedRecord;

import java.util.Objects;

/**
 * Concrete strategy utilizing {@link JettraSerialization} from the JettraEE ecosystem
 * and {@link RecordCompressionStrategy} for ultra-fast, compact binary serialization with Java 25 Compact Object Headers.
 */
public class JettraEESerializationStrategy implements RecordSerializationStrategy {

    private final RecordCompressionStrategy compressionStrategy;

    public JettraEESerializationStrategy() {
        this(new AdaptiveRecordCompressionStrategy());
    }

    public JettraEESerializationStrategy(RecordCompressionStrategy compressionStrategy) {
        this.compressionStrategy = Objects.requireNonNull(compressionStrategy, "Compression strategy must not be null");
    }

    @Override
    public byte[] serialize(String recordId, int version, long timestamp, byte[] payload) {
        if (compressionStrategy.shouldCompress(payload)) {
            return JettraSerialization.serializeRecordCompressed(recordId, version, timestamp, payload);
        }
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

    public static final String DEFAULT_STRATEGY_NAME = "JettraSerialization (JettraEE Native)";

    @Override
    public String getStrategyName() {
        if (compressionStrategy instanceof AdaptiveRecordCompressionStrategy) {
            return DEFAULT_STRATEGY_NAME;
        }
        return "JettraSerialization + " + compressionStrategy.getAlgorithmName();
    }

    public RecordCompressionStrategy getCompressionStrategy() {
        return compressionStrategy;
    }
}
