package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.LsmBTreeHybrid;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Fluent builder for hierarchical record storage paths and metadata (Builder Pattern).
 * Follows the directory hierarchy:
 * {rootPath}/databases/{DatabaseName}/{engineName}/{unitName}/<record_id>.dat
 */
public final class StoragePathBuilder {

    private Path rootPath;
    private String database;
    private String engine;
    private String unit = "default";
    private String recordId;

    private StoragePathBuilder() {}

    public static StoragePathBuilder create() {
        return new StoragePathBuilder();
    }

    public StoragePathBuilder withRoot(Path rootPath) {
        this.rootPath = rootPath;
        return this;
    }

    public StoragePathBuilder withDatabase(String database) {
        this.database = database;
        return this;
    }

    public StoragePathBuilder withEngine(String engine) {
        this.engine = engine;
        return this;
    }

    public StoragePathBuilder withUnit(String unit) {
        if (unit != null && !unit.isBlank()) {
            this.unit = unit.trim();
        }
        return this;
    }

    public StoragePathBuilder withRecordId(String recordId) {
        this.recordId = recordId;
        return this;
    }

    /**
     * Resolves and builds a StorageRecordPath from an internal storage key and optional payload.
     */
    public static StorageRecordPath fromKey(Path rootPath, String key, byte[] payload) {
        Objects.requireNonNull(rootPath, "rootPath must not be null");
        Objects.requireNonNull(key, "key must not be null");

        String db = LsmBTreeHybrid.extractDatabaseFromKey(key);
        StorageEngineStrategy strategy = EngineStorageStrategyRegistry.resolve(key);

        return strategy.buildPath(rootPath, db, key, payload);
    }

    /**
     * Builds the complete StorageRecordPath instance.
     */
    public StorageRecordPath build() {
        Objects.requireNonNull(rootPath, "rootPath must be configured");

        String effectiveDb = (database != null && !database.isBlank()) ? database.trim() : "_system";
        String effectiveEngine = (engine != null && !engine.isBlank()) ? engine.trim().toLowerCase() : "document";
        String effectiveUnit = (unit != null && !unit.isBlank()) ? unit.trim().toLowerCase() : "default";
        String effectiveId = (recordId != null && !recordId.isBlank()) ? recordId.trim() : "record_" + System.currentTimeMillis();

        if (!effectiveId.endsWith(".dat")) {
            effectiveId = effectiveId + ".dat";
        }

        Path dbBase;
        if ("_system".equalsIgnoreCase(effectiveDb)) {
            dbBase = rootPath.resolve("system");
        } else {
            dbBase = rootPath.resolve("databases").resolve(effectiveDb);
        }

        Path recordFile = dbBase.resolve(effectiveEngine).resolve(effectiveUnit).resolve(effectiveId);
        String rawRecordId = effectiveId.substring(0, effectiveId.length() - 4);

        return new StorageRecordPath(
            rootPath,
            effectiveDb,
            effectiveEngine,
            effectiveUnit,
            rawRecordId,
            recordFile
        );
    }
}
