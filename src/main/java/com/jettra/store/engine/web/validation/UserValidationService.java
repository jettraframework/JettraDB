package com.jettra.store.engine.web.validation;

import com.jettra.store.engine.users.SystemUserRepository;
import io.jettra.server.autentification.repository.JUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Service orchestrating user form validation via Chain of Responsibility and Strategy patterns.
 * Supports asynchronous virtual thread verification for non-blocking I/O checks.
 */
public class UserValidationService {

    private final List<UserValidationRule> rules = new ArrayList<>();
    private final UserValidationChain chain;

    public UserValidationService(SystemUserRepository systemUserRepository) {
        this.chain = UserValidationChain.defaultChain(systemUserRepository);
    }

    public UserValidationService(JUserRepository userRepository) {
        this.chain = null;
        this.rules.add(new UsernameRequiredRule());
        this.rules.add(new UsernameFormatRule());
        this.rules.add(new UsernameUniquenessRule(userRepository));
    }

    public UserValidationService addRule(UserValidationRule rule) {
        if (rule != null) {
            this.rules.add(rule);
        }
        return this;
    }

    public ValidationResult validate(UserValidationContext context) {
        if (chain != null) {
            return chain.validate(context);
        }
        for (UserValidationRule rule : rules) {
            ValidationResult result = rule.validate(context);
            if (result.isInvalid()) {
                return result;
            }
        }
        return ValidationResult.valid();
    }

    public ValidationResult validateUsername(String username) {
        return validate(UserValidationContext.forCreate(username, null, null));
    }

    public CompletableFuture<ValidationResult> validateAsync(UserValidationContext context) {
        return CompletableFuture.supplyAsync(
            () -> validate(context),
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }
}
