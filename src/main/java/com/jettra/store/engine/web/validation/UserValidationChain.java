package com.jettra.store.engine.web.validation;

import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Idiomatic Chain of Responsibility pipeline for validating user attributes.
 * Enforces presence, format, length, and case-insensitive uniqueness against system_db.
 */
public class UserValidationChain {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");
    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 32;

    private ValidationHandler head;

    public UserValidationChain() {
        // Default pipeline setup
    }

    public static UserValidationChain defaultChain(SystemUserRepository userRepository) {
        UserValidationChain chain = new UserValidationChain();
        chain.addHandler(new PresenceHandler());
        chain.addHandler(new FormatAndLengthHandler());
        chain.addHandler(new CaseInsensitiveUniquenessHandler(userRepository));
        return chain;
    }

    public UserValidationChain addHandler(ValidationHandler handler) {
        if (head == null) {
            head = handler;
        } else {
            ValidationHandler current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = handler;
        }
        return this;
    }

    public ValidationResult validate(UserValidationContext context) {
        if (head == null) {
            return ValidationResult.valid();
        }
        return head.handle(context);
    }

    public CompletableFuture<ValidationResult> validateAsync(UserValidationContext context) {
        return CompletableFuture.supplyAsync(
            () -> validate(context),
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    /**
     * Base abstract handler in the Chain of Responsibility.
     */
    public static abstract class ValidationHandler {
        protected ValidationHandler next;

        public abstract ValidationResult handle(UserValidationContext context);

        protected ValidationResult passToNext(UserValidationContext context) {
            if (next != null) {
                return next.handle(context);
            }
            return ValidationResult.valid();
        }
    }

    /**
     * Handler 1: Presence verification.
     */
    public static class PresenceHandler extends ValidationHandler {
        @Override
        public ValidationResult handle(UserValidationContext context) {
            if (context == null || context.username() == null || context.username().isBlank()) {
                return ValidationResult.invalid(
                    "username",
                    "USERNAME_REQUIRED",
                    "El nombre de usuario es obligatorio y no puede estar vacío."
                );
            }
            return passToNext(context);
        }
    }

    /**
     * Handler 2: Format and Length constraint verification.
     */
    public static class FormatAndLengthHandler extends ValidationHandler {
        @Override
        public ValidationResult handle(UserValidationContext context) {
            String username = context.username().trim();

            if (username.length() < MIN_LENGTH) {
                return ValidationResult.invalid(
                    "username",
                    "USERNAME_TOO_SHORT",
                    "El nombre de usuario debe contener al menos " + MIN_LENGTH + " caracteres."
                );
            }

            if (username.length() > MAX_LENGTH) {
                return ValidationResult.invalid(
                    "username",
                    "USERNAME_TOO_LONG",
                    "El nombre de usuario no puede exceder los " + MAX_LENGTH + " caracteres."
                );
            }

            if (!USERNAME_PATTERN.matcher(username).matches()) {
                return ValidationResult.invalid(
                    "username",
                    "USERNAME_INVALID_FORMAT",
                    "El nombre de usuario solo puede contener letras, números, puntos, guiones y guiones bajos."
                );
            }

            return passToNext(context);
        }
    }

    /**
     * Handler 3: Case-insensitive uniqueness check against system_db.
     */
    public static class CaseInsensitiveUniquenessHandler extends ValidationHandler {
        private final SystemUserRepository userRepository;

        public CaseInsensitiveUniquenessHandler(SystemUserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @Override
        public ValidationResult handle(UserValidationContext context) {
            if (userRepository == null || context == null || context.username() == null) {
                return passToNext(context);
            }

            String clean = context.username().trim();
            Optional<SystemUser> existingOpt = userRepository.findByUsername(clean);

            if (existingOpt.isPresent()) {
                SystemUser existing = existingOpt.get();
                // If updating the same entity, uniqueness is satisfied
                if (context.isUpdate() && context.excludeUserId() != null && context.excludeUserId().equals(existing.id())) {
                    return passToNext(context);
                }
                return ValidationResult.invalid(
                    "username",
                    "USERNAME_DUPLICATE",
                    "El nombre de usuario '" + clean + "' ya está registrado en system_db. Por favor elija un nombre diferente."
                );
            }

            return passToNext(context);
        }
    }
}
