package com.jettra.store.engine.web.validation;

public class UsernameRequiredRule implements UserValidationRule {

    @Override
    public ValidationResult validate(UserValidationContext context) {
        if (context == null || context.username() == null || context.username().isBlank()) {
            return ValidationResult.invalid(
                "username",
                "USERNAME_REQUIRED",
                "El nombre de usuario es obligatorio y no puede estar vacío."
            );
        }
        return ValidationResult.valid();
    }
}
