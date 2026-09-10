package com.jettra.store.engine.web.validation;

import java.util.UUID;

/**
 * Encapsulates contextual parameters required to validate user creation or modification.
 */
public record UserValidationContext(
    String username,
    String email,
    String password,
    UUID excludeUserId,
    boolean isUpdate
) {
    public static UserValidationContext forCreate(String username, String email, String password) {
        return new UserValidationContext(username, email, password, null, false);
    }

    public static UserValidationContext forUpdate(UUID userId, String username, String email, String password) {
        return new UserValidationContext(username, email, password, userId, true);
    }

    public static UserValidationContext forUpdate(String username, String email, String password, UUID userId) {
        return new UserValidationContext(username, email, password, userId, true);
    }

    public static UserValidationContext forCheck(String username, UUID excludeUserId) {
        return new UserValidationContext(username, null, null, excludeUserId, excludeUserId != null);
    }

    public static UserValidationContext forCheck(String username) {
        return forCheck(username, null);
    }
}
