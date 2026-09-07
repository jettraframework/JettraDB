package com.jettra.store.engine.insertion;

import java.util.Collections;
import java.util.List;

/**
 * Immutable validation result for engine record payloads.
 */
public record ValidationResult(boolean isValid, List<String> errors) {

    public static ValidationResult success() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult failure(String... errors) {
        return new ValidationResult(false, List.of(errors));
    }

    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(false, errors != null ? List.copyOf(errors) : Collections.emptyList());
    }

    public String firstError() {
        return errors.isEmpty() ? "Unknown error" : errors.get(0);
    }
}
