package com.jettra.store.engine.operations;

/**
 * Specialized domain exception for failures during engine lifecycle operations
 * (Backup, Restore, Export).
 */
public class EngineOperationException extends RuntimeException {

    private final EngineOperationType operationType;
    private final String database;

    public EngineOperationException(EngineOperationType operationType, String database, String message) {
        super(message);
        this.operationType = operationType;
        this.database = database;
    }

    public EngineOperationException(EngineOperationType operationType, String database, String message, Throwable cause) {
        super(message, cause);
        this.operationType = operationType;
        this.database = database;
    }

    public EngineOperationType getOperationType() {
        return operationType;
    }

    public String getDatabase() {
        return database;
    }
}
