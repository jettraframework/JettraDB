package com.jettra.store.engine.web;

/**
 * Immutable Command Pattern payload encapsulating a version rollback request.
 * Contains full context: engine, database, unit/collection, record ID, target timestamp, version, author, and reason.
 */
public record RollbackCommand(
    String engine,
    String database,
    String unit,
    String recordId,
    long targetTimestamp,
    int versionNumber,
    String author,
    String reason
) {
    public RollbackCommand {
        if (engine == null || engine.isBlank()) engine = "DOCUMENT";
        if (database == null || database.isBlank()) database = "customers_db";
        if (unit == null || unit.isBlank()) unit = "default";
        if (recordId == null) recordId = "";
        if (author == null || author.isBlank()) author = "system";
        if (reason == null || reason.isBlank()) reason = "Manual Rollback Request";
    }

    public static RollbackCommand of(String engine, String database, String unit, String recordId, long targetTimestamp) {
        return new RollbackCommand(engine, database, unit, recordId, targetTimestamp, 0, "system", "Manual Rollback Request");
    }

    public static RollbackCommand of(String engine, String database, String unit, String recordId, long targetTimestamp, int versionNumber) {
        return new RollbackCommand(engine, database, unit, recordId, targetTimestamp, versionNumber, "system", "Manual Rollback Request");
    }

    public RestoreCommandHandler.RestoreCommand toRestoreCommand() {
        return new RestoreCommandHandler.RestoreCommand(engine, database, unit, recordId, targetTimestamp, versionNumber);
    }
}
