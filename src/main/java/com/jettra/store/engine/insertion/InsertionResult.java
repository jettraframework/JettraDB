package com.jettra.store.engine.insertion;

/**
 * Immutable outcome result for record insertion in JettraDB multi-model engines.
 */
public record InsertionResult(
        boolean success,
        String engine,
        String database,
        String unit,
        String id,
        String message,
        long timestamp,
        int versionCount
) {
    public static InsertionResult ofSuccess(String engine, String database, String unit, String id, String message, int versionCount) {
        return new InsertionResult(true, engine, database, unit, id, message, System.currentTimeMillis(), versionCount);
    }

    public static InsertionResult ofError(String engine, String database, String unit, String id, String errorMessage) {
        return new InsertionResult(false, engine, database, unit, id, errorMessage, System.currentTimeMillis(), 0);
    }
}
