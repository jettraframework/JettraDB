package com.jettra.store.engine.web.validation;

import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;
import java.util.Optional;

public class UsernameUniquenessRule implements UserValidationRule {

    private final JUserRepository userRepository;

    public UsernameUniquenessRule(JUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public ValidationResult validate(UserValidationContext context) {
        if (context == null || context.username() == null || context.username().isBlank()) {
            return ValidationResult.valid();
        }
        String cleanUsername = context.username().trim();
        Optional<JUser> existing = userRepository.findByUsername(cleanUsername);
        if (existing.isPresent()) {
            JUser user = existing.get();
            if (context.isUpdate() && context.excludeUserId() != null && context.excludeUserId().equals(user.id())) {
                return ValidationResult.valid();
            }
            return ValidationResult.invalid(
                "username",
                "USERNAME_DUPLICATE",
                "El nombre de usuario '" + cleanUsername + "' ya está registrado. Por favor elija un nombre diferente."
            );
        }
        return ValidationResult.valid();
    }
}
