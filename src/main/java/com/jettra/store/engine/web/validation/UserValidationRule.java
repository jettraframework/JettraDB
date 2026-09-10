package com.jettra.store.engine.web.validation;

/**
 * Strategy / Chain of Responsibility interface for individual user validation rules.
 */
@FunctionalInterface
public interface UserValidationRule {
    ValidationResult validate(UserValidationContext context);
}
