package com.jettra.store.engine.operations;

/**
 * Immutable Java 25 Record representing the outcome of an Engine Lifecycle Operation
 * (Backup, Restore, Export).
 */
public record EngineOperationResult(
    boolean success,
    EngineOperationType type,
    String database,
    String message,
    int recordCount,
    long sizeBytes,
    String outputFilePath,
    String contentType,
    byte[] outputData,
    long durationMs,
    String error
) {

    public static EngineOperationResult success(
        EngineOperationType type,
        String database,
        String message,
        int recordCount,
        long sizeBytes,
        String outputFilePath,
        String contentType,
        byte[] outputData,
        long durationMs
    ) {
        return new EngineOperationResult(
            true,
            type,
            database,
            message,
            recordCount,
            sizeBytes,
            outputFilePath,
            contentType,
            outputData,
            durationMs,
            null
        );
    }

    public static EngineOperationResult failure(
        EngineOperationType type,
        String database,
        String message,
        String error,
        long durationMs
    ) {
        return new EngineOperationResult(
            false,
            type,
            database,
            message,
            0,
            0L,
            null,
            null,
            null,
            durationMs,
            error
        );
    }
}
