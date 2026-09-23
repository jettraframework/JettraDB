package com.jettra.store.engine.core.storage;

import java.nio.file.Path;

/**
 * Strategy interface defining storage semantics and path resolution
 * for multi-model database engines (Strategy Pattern).
 */
public interface StorageEngineStrategy {

    /**
     * Canonical engine name (e.g. "records", "document", "keyvalue", "geospatial", etc.).
     */
    String getEngineName();

    /**
     * Canonical key prefix (e.g. "rec:", "doc:", "kv:", etc.).
     */
    String getPrefix();

    /**
     * Checks if this strategy matches the specified prefix or engine identifier.
     */
    boolean supports(String prefixOrEngine);

    /**
     * Extracts or infers the storage unit name (collection, table, namespace, layer, bucket)
     * from the internal key and optional payload attributes.
     */
    String resolveUnit(String key, byte[] payload);

    /**
     * Extracts the raw record ID from the internal key.
     */
    String resolveRecordId(String key);

    /**
     * Builds the complete StorageRecordPath for this engine strategy.
     */
    StorageRecordPath buildPath(Path rootDir, String database, String key, byte[] payload);
}
