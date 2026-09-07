package com.jettra.store.engine.exception;

import com.sun.net.httpserver.HttpExchange;
import io.jettra.json.JettraJson;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Centralized Exception Mapper for JettraDB HTTP and REST interactions.
 * Intercepts exceptions and writes structured JSON error payloads, preventing
 * container-level HTML error pages (e.g. <h1>400 Bad Request</h1>).
 */
public final class ExceptionMapper {

    private static final JettraJson JSON = new JettraJson();

    private ExceptionMapper() {}

    /**
     * Maps any throwable to an immutable ErrorResponse record using Java 25 pattern matching.
     */
    public static ErrorResponse toErrorResponse(Throwable throwable, String path) {
        if (throwable == null) {
            return ErrorResponse.ofBadRequest("Error desconocido", List.of(), path);
        }

        Throwable unwrapped = (throwable.getCause() != null && (throwable instanceof java.util.concurrent.CompletionException || throwable instanceof java.util.concurrent.ExecutionException))
                ? throwable.getCause()
                : throwable;

        return switch (unwrapped) {
            case BadRequestException bre ->
                ErrorResponse.ofBadRequest(bre.getMessage(), bre.getErrors(), path);
            case ConstraintViolationException cve ->
                ErrorResponse.ofBadRequest(cve.getMessage(), cve.getViolations(), path);
            case IllegalArgumentException iae when iae.getMessage() != null && !iae.getMessage().isBlank() ->
                ErrorResponse.ofBadRequest("Parámetro inválido: " + iae.getMessage(), List.of(iae.getMessage()), path);
            case NullPointerException npe when npe.getMessage() != null ->
                ErrorResponse.ofBadRequest("Referencia nula obligatoria no proporcionada: " + npe.getMessage(), List.of(), path);
            default ->
                ErrorResponse.ofInternalError("Fallo interno del servidor: " + unwrapped.getMessage(), path);
        };
    }

    /**
     * Intercepts a throwable, maps it, and writes the JSON response to the HttpExchange.
     */
    public static void handleException(Throwable throwable, HttpExchange exchange, String path) throws IOException {
        ErrorResponse err = toErrorResponse(throwable, path);
        writeJsonResponse(exchange, err.status(), err);
    }

    /**
     * Directly writes a 400 Bad Request error response in JSON.
     */
    public static void sendBadRequest(HttpExchange exchange, String message, List<String> errors, String path) throws IOException {
        ErrorResponse err = ErrorResponse.ofBadRequest(message, errors, path);
        writeJsonResponse(exchange, 400, err);
    }

    /**
     * Writes any object as application/json; charset=UTF-8 to the HttpExchange.
     */
    public static void writeJsonResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        String jsonStr = (body instanceof String s) ? s : JSON.toJson(body);
        byte[] bytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }
}
