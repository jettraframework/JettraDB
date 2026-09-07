package com.jettra.store.engine.exception;

import java.util.List;

/**
 * Immutable Java 25 Record modeling structured JSON error responses.
 */
public record ErrorResponse(
    int status,
    String error,
    String message,
    List<String> errors,
    String path,
    long timestamp
) {
    public static ErrorResponse ofBadRequest(String message, List<String> errors, String path) {
        return new ErrorResponse(400, "Bad Request", message != null ? message : "Bad Request", errors != null ? List.copyOf(errors) : List.of(), path != null ? path : "", System.currentTimeMillis());
    }

    public static ErrorResponse ofNotFound(String message, String path) {
        return new ErrorResponse(404, "Not Found", message != null ? message : "Not Found", List.of(), path != null ? path : "", System.currentTimeMillis());
    }

    public static ErrorResponse ofInternalError(String message, String path) {
        return new ErrorResponse(500, "Internal Server Error", message != null ? message : "Internal Server Error", List.of(), path != null ? path : "", System.currentTimeMillis());
    }
}
