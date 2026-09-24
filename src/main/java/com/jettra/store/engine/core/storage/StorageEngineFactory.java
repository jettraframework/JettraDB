package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.storage.compression.RecordCompressionStrategy;
import com.jettra.store.engine.core.storage.slotted.SlottedPageConstants;
import com.jettra.store.engine.core.storage.slotted.SlottedPageStorageRecordRepository;

import java.util.Objects;

/**
 * Factory class (Factory Pattern) providing decoupled instantiation of storage repositories,
 * compression algorithms, and serialization strategies for the JettraDB engine.
 */
public final class StorageEngineFactory {

    public enum StorageType {
        SLOTTED_PAGE,
        INDIVIDUAL_FILE
    }

    private static volatile StorageType defaultStorageType = StorageType.SLOTTED_PAGE;
    private static volatile RecordSerializationStrategy defaultStrategy = new JettraEESerializationStrategy();
    private static volatile int defaultPageSize = SlottedPageConstants.PAGE_SIZE_64KB;
    private static volatile int defaultMaxBufferPages = 512;
    private static volatile String defaultFileExtension = SlottedPageConstants.FILE_EXTENSION;

    private StorageEngineFactory() {}

    /**
     * Sets the global default storage type (SLOTTED_PAGE or INDIVIDUAL_FILE).
     */
    public static void setDefaultStorageType(StorageType storageType) {
        defaultStorageType = Objects.requireNonNull(storageType, "StorageType must not be null");
    }

    /**
     * Gets the active global storage type.
     */
    public static StorageType getDefaultStorageType() {
        return defaultStorageType;
    }

    /**
     * Sets the default page size for slotted page repositories.
     */
    public static void setDefaultPageSize(int pageSize) {
        defaultPageSize = pageSize;
    }

    public static int getDefaultPageSize() {
        return defaultPageSize;
    }

    /**
     * Sets the default file extension for slotted page storage files.
     */
    public static void setDefaultFileExtension(String extension) {
        defaultFileExtension = extension;
    }

    public static String getDefaultFileExtension() {
        return defaultFileExtension;
    }

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
     * Creates a new high-performance {@link StorageRecordRepository}.
     * Defaults to the Slotted-Page architecture (.jetrra / .jettra), or legacy
     * individual files when configured.
     */
    public static StorageRecordRepository createRepository() {
        if (defaultStorageType == StorageType.SLOTTED_PAGE) {
            return createSlottedRepository();
        }
        return new FileSystemStorageRecordRepository(defaultStrategy);
    }

    /**
     * Creates a new {@link StorageRecordRepository} configured with a specified serialization strategy.
     */
    public static StorageRecordRepository createRepository(RecordSerializationStrategy strategy) {
        if (defaultStorageType == StorageType.SLOTTED_PAGE) {
            return createSlottedRepository();
        }
        return new FileSystemStorageRecordRepository(strategy != null ? strategy : defaultStrategy);
    }

    /**
     * Creates a new {@link StorageRecordRepository} configured with a specified compression strategy.
     */
    public static StorageRecordRepository createRepository(RecordCompressionStrategy compressionStrategy) {
        if (defaultStorageType == StorageType.SLOTTED_PAGE) {
            return createSlottedRepository();
        }
        return new FileSystemStorageRecordRepository(new JettraEESerializationStrategy(compressionStrategy));
    }

    /**
     * Creates a new high-performance {@link com.jettra.store.engine.core.storage.slotted.SlottedPageStorageRecordRepository}
     * implementing fixed-size Slotted-Page architecture backed by .jetrra / .jettra files.
     */
    public static StorageRecordRepository createSlottedRepository() {
        return new SlottedPageStorageRecordRepository(defaultPageSize, defaultMaxBufferPages, defaultFileExtension);
    }

    /**
     * Creates a new {@link com.jettra.store.engine.core.storage.slotted.SlottedPageStorageRecordRepository}
     * with custom page size and buffer pool capacity.
     */
    public static StorageRecordRepository createSlottedRepository(int pageSize, int maxBufferPages) {
        return new SlottedPageStorageRecordRepository(pageSize, maxBufferPages, defaultFileExtension);
    }

    /**
     * Creates a new {@link com.jettra.store.engine.core.storage.slotted.SlottedPageStorageRecordRepository}
     * with custom page size, buffer pool capacity, and file extension.
     */
    public static StorageRecordRepository createSlottedRepository(int pageSize, int maxBufferPages, String fileExtension) {
        return new SlottedPageStorageRecordRepository(pageSize, maxBufferPages, fileExtension);
    }
}
