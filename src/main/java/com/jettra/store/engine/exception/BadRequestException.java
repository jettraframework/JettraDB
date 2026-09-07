package com.jettra.store.engine.exception;

import java.util.List;

/**
 * Thrown when an incoming HTTP or multi-model payload fails validation or format constraints.
 */
public class BadRequestException extends RuntimeException {

    private final List<String> errors;

    public BadRequestException(String message) {
        this(message, List.of());
    }

    public BadRequestException(String message, List<String> errors) {
        super(message);
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public List<String> getErrors() {
        return errors;
    }
}
