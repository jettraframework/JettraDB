package com.jettra.store.engine.web.validation;

import java.util.regex.Pattern;

public class UsernameFormatRule implements UserValidationRule {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]{3,64}$");

    @Override
    public ValidationResult validate(UserValidationContext context) {
        if (context == null || context.username() == null || context.username().isBlank()) {
            return ValidationResult.valid(); // Delegated to required rule
        }
        String trimmed = context.username().trim();
        if (trimmed.length() < 3) {
            return ValidationResult.invalid(
                "username",
                "USERNAME_TOO_SHORT",
                "El nombre de usuario debe contener al menos 3 caracteres."
            );
        }
        if (trimmed.length() > 64) {
            return ValidationResult.invalid(
                "username",
                "USERNAME_TOO_LONG",
                "El nombre de usuario no puede exceder los 64 caracteres."
            );
        }
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.invalid(
                "username",
                "USERNAME_INVALID_FORMAT",
                "El nombre de usuario solo puede contener letras, números, guiones bajos (_), puntos (.) y guiones (-)."
            );
        }
        return ValidationResult.valid();
    }
}
