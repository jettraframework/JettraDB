package com.jettra.store.engine.web.validation;

/**
 * Sealed interface representing validation outcomes for user domain operations.
 */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

    record Valid() implements ValidationResult {}

    record Invalid(String field, String errorCode, String message) implements ValidationResult {
        public boolean isDuplicate() {
            return "USERNAME_DUPLICATE".equalsIgnoreCase(errorCode);
        }
    }

    static ValidationResult valid() {
        return new Valid();
    }

    static ValidationResult invalid(String field, String errorCode, String message) {
        return new Invalid(field, errorCode, message);
    }

    default boolean isValid() {
        return this instanceof Valid;
    }

    default boolean isInvalid() {
        return this instanceof Invalid;
    }

    default String message() {
        return switch (this) {
            case Valid v -> "";
            case Invalid inv -> inv.message();
        };
    }
}
