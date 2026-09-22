package com.jettra.store.engine.operations.export;

import java.util.Map;

/**
 * Strategy interface for formatting and exporting multi-model database records.
 * Implements the Strategy Pattern to decouple format-specific transformations, MIME types,
 * and file extensions from core operation coordination in JettraDB.
 */
public interface ExportStrategy {

    /**
     * Unique format identifier (e.g. "json", "csv", "excel").
     */
    String format();

    /**
     * Display label for UI dropdowns and representations.
     */
    String displayName();

    /**
     * Standard MIME content type.
     */
    String mimeType();

    /**
     * File extension without leading dot (e.g. "json", "csv", "xls").
     */
    String fileExtension();

    /**
     * Serializes multi-model records into the target format's binary representation.
     *
     * @param database target database name
     * @param engineFilter engine filter applied (e.g. "DOCUMENT", "ALL")
     * @param collectionFilter unit or collection filter
     * @param recordsMap key-payload map of records
     * @return encoded byte array ready for download or persistence
     */
    byte[] export(String database, String engineFilter, String collectionFilter, Map<String, String> recordsMap);
}
