package com.jettra.store.engine.core.storage;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable Java 25 Record representing the hierarchical storage coordinates
 * of an individual record file within JettraDB:
 * {rootPath}/databases/{database}/{engine}/{unit}/{recordId}.dat
 */
public record StorageRecordPath(
    Path rootPath,
    String database,
    String engine,
    String unit,
    String recordId,
    Path filePath
) {
    public StorageRecordPath {
        Objects.requireNonNull(rootPath, "rootPath must not be null");
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(recordId, "recordId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
    }

    /**
     * Resolves the canonical directory containing records for this unit.
     */
    public Path unitDirectory() {
        return filePath.getParent();
    }

    /**
     * Resolves the canonical engine directory for this database.
     */
    public Path engineDirectory() {
        Path parent = unitDirectory();
        return parent != null ? parent.getParent() : null;
    }

    /**
     * Resolves the database root directory.
     */
    public Path databaseDirectory() {
        Path engDir = engineDirectory();
        return engDir != null ? engDir.getParent() : null;
    }

    /**
     * Reconstructs the internal multi-model key.
     */
    public String toInternalKey(String prefix) {
        String pfx = (prefix != null && !prefix.isBlank()) ? prefix : "doc:";
        if (!pfx.endsWith(":")) {
            pfx = pfx + ":";
        }
        if ("default".equalsIgnoreCase(unit)) {
            return pfx + database + ":" + recordId;
        }
        return pfx + database + ":" + unit + ":" + recordId;
    }
}
