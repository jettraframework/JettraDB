package com.jettra.store.engine.web;

/**
 * Immutable Result Payload returned after document editing and version persistence.
 */
public record EditDocumentResult(
    boolean success,
    String engineType,
    String database,
    String collection,
    String recordId,
    long timestamp,
    int versionCount,
    String message,
    String error
) {
    public static EditDocumentResult success(String engineType, String database, String collection, String recordId, long timestamp, int versionCount, String message) {
        return new EditDocumentResult(true, engineType, database, collection, recordId, timestamp, versionCount, message, null);
    }

    public static EditDocumentResult failure(String engineType, String database, String collection, String recordId, String error) {
        return new EditDocumentResult(false, engineType, database, collection, recordId, System.currentTimeMillis(), 0, null, error);
    }
}
