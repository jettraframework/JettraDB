package com.jettra.store.engine.web;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable Command representing a document/record update or new version creation request.
 * Follows the Command Pattern under Java 25+.
 */
public record EditDocumentCommand(
    String engineType,
    String database,
    String collection,
    String recordId,
    String payload,
    Map<String, String> extraParams,
    String author,
    String reason
) {
    public EditDocumentCommand {
        extraParams = extraParams != null ? Collections.unmodifiableMap(extraParams) : Collections.emptyMap();
    }

    public static EditDocumentCommand of(String engineType, String database, String collection, String recordId, String payload) {
        return new EditDocumentCommand(engineType, database, collection, recordId, payload, Collections.emptyMap(), "web_admin", "UI Version Update");
    }

    public static EditDocumentCommand of(String engineType, String database, String collection, String recordId, String payload, Map<String, String> params) {
        return new EditDocumentCommand(engineType, database, collection, recordId, payload, params, "web_admin", "UI Version Update");
    }
}
