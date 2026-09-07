package com.jettra.store.engine.exception;

import java.util.List;

/**
 * Thrown when an entity violates engine, model or database business constraints.
 */
public class ConstraintViolationException extends RuntimeException {

    private final List<String> violations;

    public ConstraintViolationException(String message) {
        this(message, List.of());
    }

    public ConstraintViolationException(String message, List<String> violations) {
        super(message);
        this.violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public List<String> getViolations() {
        return violations;
    }
}
