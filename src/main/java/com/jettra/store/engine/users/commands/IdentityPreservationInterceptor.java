package com.jettra.store.engine.users.commands;

import com.jettra.store.engine.exception.UnsupportedUserDeletionException;

/**
 * Interceptor implementing JettraDB's strict Identity Preservation Policy.
 * Inspects commands via Java 25 pattern matching and unconditionally vetos
 * any physical user deletion attempts with UnsupportedUserDeletionException.
 */
public class IdentityPreservationInterceptor implements UserAdminInterceptor {

    @Override
    public void intercept(UserAdminCommand command) {
        switch (command) {
            case UserAdminCommand.DeleteUserAttemptCommand del -> {
                throw new UnsupportedUserDeletionException(
                    del.targetIdentifier(),
                    del.targetId(),
                    del.source().name()
                );
            }
            case UserAdminCommand.CreateUserCommand ignored -> {}
            case UserAdminCommand.UpdateUserProfileCommand ignored -> {}
            case UserAdminCommand.AssignDatabaseScopeCommand ignored -> {}
            case UserAdminCommand.RevokeDatabaseScopeCommand ignored -> {}
            case UserAdminCommand.ToggleUserStatusCommand ignored -> {}
        }
    }
}
