package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.storage.compression.RecordCompressionStrategy;

import java.util.Objects;

/**
 * Factory class (Factory Pattern) providing decoupled instantiation of storage repositories,
 * compression algorithms, and serialization strategies for the JettraDB engine.
 */
public final class StorageEngineFactory {

    private static volatile RecordSerializationStrategy defaultStrategy = new JettraEESerializationStrategy();

    private StorageEngineFactory() {}

    /**
     * Sets the global default record serialization strategy.
     */
    public static void setDefaultStrategy(RecordSerializationStrategy strategy) {
        defaultStrategy = Objects.requireNonNull(strategy, "Strategy must not be null");
    }

    /**
     * Configures the global default serialization strategy with the specified compression strategy.
     */
    public static void setDefaultCompressionStrategy(RecordCompressionStrategy compressionStrategy) {
        defaultStrategy = new JettraEESerializationStrategy(compressionStrategy);
    }

    /**
     * Gets the active global record serialization strategy.
     */
    public static RecordSerializationStrategy getDefaultStrategy() {
        return defaultStrategy;
    }

    /**
     * Creates a new high-performance {@link StorageRecordRepository} configured with
     * the active {@link JettraEESerializationStrategy}.
     */
    public static StorageRecordRepository createRepository() {
        return new FileSystemStorageRecordRepository(defaultStrategy);
    }

    /**
     * Creates a new {@link StorageRecordRepository} configured with a specified serialization strategy.
     */
    public static StorageRecordRepository createRepository(RecordSerializationStrategy strategy) {
        return new FileSystemStorageRecordRepository(strategy != null ? strategy : defaultStrategy);
    }

    /**
     * Creates a new {@link StorageRecordRepository} configured with a specified compression strategy.
     */
    public static StorageRecordRepository createRepository(RecordCompressionStrategy compressionStrategy) {
        return new FileSystemStorageRecordRepository(new JettraEESerializationStrategy(compressionStrategy));
    }
}
