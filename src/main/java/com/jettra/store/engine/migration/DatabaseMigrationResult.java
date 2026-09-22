package com.jettra.store.engine.migration;

/**
 * Java 25 Record encapsulating the complete outcome of a database cloning and migration operation.
 *
 * @param success               whether the migration completed successfully
 * @param sourceDatabase        original database name that was migrated and retired
 * @param targetDatabase        newly created and populated database name
 * @param keysMigrated          total number of multi-model keys cloned from source to target
 * @param usersMigrated         total number of user permission assignments updated
 * @param executionTimeMillis   elapsed execution time in milliseconds
 * @param message               user-facing status or diagnostic message
 */
public record DatabaseMigrationResult(
    boolean success,
    String sourceDatabase,
    String targetDatabase,
    int keysMigrated,
    int usersMigrated,
    long executionTimeMillis,
    String message
) {

    public static DatabaseMigrationResult success(
        String sourceDb,
        String targetDb,
        int keysMigrated,
        int usersMigrated,
        long elapsed
    ) {
        String msg = "Database '" + sourceDb + "' successfully renamed and cloned to '" + targetDb
                + "' (" + keysMigrated + " keys migrated across all multi-model engines, "
                + usersMigrated + " user assignments preserved).";
        return new DatabaseMigrationResult(true, sourceDb, targetDb, keysMigrated, usersMigrated, elapsed, msg);
    }

    public static DatabaseMigrationResult failure(String sourceDb, String targetDb, String errorMsg, long elapsed) {
        return new DatabaseMigrationResult(false, sourceDb, targetDb, 0, 0, elapsed, errorMsg);
    }
}
