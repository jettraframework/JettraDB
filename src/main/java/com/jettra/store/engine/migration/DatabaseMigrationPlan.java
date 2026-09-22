package com.jettra.store.engine.migration;

import java.util.Objects;

/**
 * Immutable Java 25 Record representing a database cloning and migration plan,
 * constructed using the Builder Pattern.
 *
 * @param sourceDatabase          Source database to be cloned and retired
 * @param targetDatabase          Destination database name
 * @param migrateMultiModelKeys   Whether to clone all multi-model keys across engines
 * @param migrateUserAssignments  Whether to clone user authorizations and roles
 * @param purgeSourceDatabase     Whether to safely drop and delete source database after cloning
 * @param timestamp               Creation timestamp of the migration plan
 */
public record DatabaseMigrationPlan(
    String sourceDatabase,
    String targetDatabase,
    boolean migrateMultiModelKeys,
    boolean migrateUserAssignments,
    boolean purgeSourceDatabase,
    long timestamp
) {

    public DatabaseMigrationPlan {
        Objects.requireNonNull(sourceDatabase, "Source database cannot be null");
        Objects.requireNonNull(targetDatabase, "Target database cannot be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Sanitizes destination database name into canonical identifier.
     */
    public String cleanTargetDatabase() {
        return targetDatabase.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    public String cleanSourceDatabase() {
        return sourceDatabase.trim();
    }

    public static class Builder {
        private String sourceDatabase;
        private String targetDatabase;
        private boolean migrateMultiModelKeys = true;
        private boolean migrateUserAssignments = true;
        private boolean purgeSourceDatabase = true;
        private long timestamp = System.currentTimeMillis();

        public Builder sourceDatabase(String sourceDatabase) {
            this.sourceDatabase = sourceDatabase;
            return this;
        }

        public Builder targetDatabase(String targetDatabase) {
            this.targetDatabase = targetDatabase;
            return this;
        }

        public Builder migrateMultiModelKeys(boolean migrateMultiModelKeys) {
            this.migrateMultiModelKeys = migrateMultiModelKeys;
            return this;
        }

        public Builder migrateUserAssignments(boolean migrateUserAssignments) {
            this.migrateUserAssignments = migrateUserAssignments;
            return this;
        }

        public Builder purgeSourceDatabase(boolean purgeSourceDatabase) {
            this.purgeSourceDatabase = purgeSourceDatabase;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public DatabaseMigrationPlan build() {
            if (sourceDatabase == null || sourceDatabase.isBlank()) {
                throw new IllegalArgumentException("Source database name is required.");
            }
            if (targetDatabase == null || targetDatabase.isBlank()) {
                throw new IllegalArgumentException("Target database name is required.");
            }
            return new DatabaseMigrationPlan(
                sourceDatabase.trim(),
                targetDatabase.trim(),
                migrateMultiModelKeys,
                migrateUserAssignments,
                purgeSourceDatabase,
                timestamp
            );
        }
    }
}
