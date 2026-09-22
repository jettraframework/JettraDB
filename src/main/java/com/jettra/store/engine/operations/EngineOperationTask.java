package com.jettra.store.engine.operations;

import java.util.Objects;

/**
 * Immutable Java 25 Record encapsulating the complete configuration for an
 * Engine Lifecycle Operation (Backup, Restore, Export).
 * Implements the Builder Pattern for fluent, type-safe configuration.
 */
public record EngineOperationTask(
    EngineOperationType type,
    String database,
    String engineType,
    String collection,
    String destinationDirectory,
    String fileName,
    String sourceFilePath,
    String exportFormat,
    long createdAt
) {

    public EngineOperationTask {
        Objects.requireNonNull(type, "EngineOperationType must not be null");
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Target database name must not be blank");
        }
        database = database.trim().toLowerCase();
        engineType = (engineType != null && !engineType.isBlank()) ? engineType.trim().toUpperCase() : "ALL";
        collection = (collection != null) ? collection.trim() : "";
        exportFormat = (exportFormat != null && !exportFormat.isBlank()) ? exportFormat.trim().toLowerCase() : "json";
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
    }

    public static Builder builder(EngineOperationType type) {
        return new Builder(type);
    }

    public static Builder backup(String database) {
        return new Builder(EngineOperationType.BACKUP).database(database);
    }

    public static Builder restore(String database) {
        return new Builder(EngineOperationType.RESTORE).database(database);
    }

    public static Builder restore(String database, String sourceFilePath) {
        return new Builder(EngineOperationType.RESTORE).database(database).sourceFilePath(sourceFilePath);
    }

    public static Builder export(String database) {
        return new Builder(EngineOperationType.EXPORT).database(database);
    }

    public static Builder export(String database, String exportFormat) {
        return new Builder(EngineOperationType.EXPORT).database(database).exportFormat(exportFormat);
    }

    /**
     * Builder class for fluent configuration of EngineOperationTask.
     */
    public static final class Builder {
        private final EngineOperationType type;
        private String database;
        private String engineType = "ALL";
        private String collection = "";
        private String destinationDirectory = "";
        private String fileName = "";
        private String sourceFilePath = "";
        private String exportFormat = "json";
        private String requestedBy = "root";
        private long createdAt = System.currentTimeMillis();

        public Builder(EngineOperationType type) {
            this.type = Objects.requireNonNull(type, "type must not be null");
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder engineType(String engineType) {
            this.engineType = engineType;
            return this;
        }

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder destinationDirectory(String destinationDirectory) {
            this.destinationDirectory = destinationDirectory;
            return this;
        }

        public Builder outputPath(String outputPath) {
            this.destinationDirectory = outputPath;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder filename(String filename) {
            this.fileName = filename;
            return this;
        }

        public Builder sourceFilePath(String sourceFilePath) {
            this.sourceFilePath = sourceFilePath;
            return this;
        }

        public Builder filePath(String filePath) {
            this.sourceFilePath = filePath;
            return this;
        }

        public Builder exportFormat(String exportFormat) {
            this.exportFormat = exportFormat;
            return this;
        }

        public Builder format(String format) {
            this.exportFormat = format;
            return this;
        }

        public Builder requestedBy(String requestedBy) {
            this.requestedBy = requestedBy;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public EngineOperationTask build() {
            if (database == null || database.isBlank()) {
                throw new IllegalStateException("Database must be specified for engine operation");
            }
            if (type == EngineOperationType.RESTORE && (sourceFilePath == null || sourceFilePath.isBlank())) {
                throw new IllegalStateException("Source file path must be specified for database restoration");
            }
            return new EngineOperationTask(
                type,
                database,
                engineType,
                collection,
                destinationDirectory,
                fileName,
                sourceFilePath,
                exportFormat,
                createdAt
            );
        }
    }
}
