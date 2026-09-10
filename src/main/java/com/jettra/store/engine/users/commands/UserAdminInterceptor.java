package com.jettra.store.engine.users.commands;

/**
 * Interceptor interface for user administration pipeline in JettraDB.
 * Allows inspecting, validating, or vetoing administrative operations before execution.
 */
@FunctionalInterface
public interface UserAdminInterceptor {

    /**
     * Intercepts the command before execution.
     * Implementations may throw an exception (e.g. UnsupportedUserDeletionException) to veto the command.
     *
     * @param command the user admin command to evaluate
     */
    void intercept(UserAdminCommand command);
}
