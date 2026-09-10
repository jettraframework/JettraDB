package com.jettra.store.engine.users.commands;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Modern Java 25 sealed command hierarchy for user identity and security administration in JettraDB.
 * Formalizes all administrative operations across ingestion channels (Web UI, Client Drivers, Shell CLI).
 */
public sealed interface UserAdminCommand permits 
    UserAdminCommand.CreateUserCommand,
    UserAdminCommand.UpdateUserProfileCommand,
    UserAdminCommand.AssignDatabaseScopeCommand,
    UserAdminCommand.RevokeDatabaseScopeCommand,
    UserAdminCommand.ToggleUserStatusCommand,
    UserAdminCommand.DeleteUserAttemptCommand {

    enum CommandSource {
        WEB_UI,
        CLIENT_DRIVER,
        SHELL_CLI,
        STORAGE_ENGINE
    }

    /**
     * Provisions a new permanent user identity.
     */
    record CreateUserCommand(
        String username,
        String email,
        String password,
        String role,
        Set<String> databases,
        CommandSource source
    ) implements UserAdminCommand {
        public CreateUserCommand {
            Objects.requireNonNull(username, "Username cannot be null");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
            databases = databases != null ? Collections.unmodifiableSet(databases) : Set.of("*");
        }
    }

    /**
     * Updates profile metadata (email, role, status, databases) without modifying immutable username.
     */
    record UpdateUserProfileCommand(
        UUID id,
        String username,
        String email,
        String role,
        boolean active,
        Set<String> databases,
        CommandSource source
    ) implements UserAdminCommand {
        public UpdateUserProfileCommand {
            Objects.requireNonNull(username, "Username cannot be null");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
            databases = databases != null ? Collections.unmodifiableSet(databases) : Collections.emptySet();
        }
    }

    /**
     * Grants scoped access to a database without altering identity permanence.
     */
    record AssignDatabaseScopeCommand(
        String username,
        String targetDatabase,
        String role,
        CommandSource source
    ) implements UserAdminCommand {
        public AssignDatabaseScopeCommand {
            Objects.requireNonNull(username, "Username cannot be null");
            Objects.requireNonNull(targetDatabase, "Target database cannot be null");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
        }
    }

    /**
     * Revokes scoped access to a target database while strictly preserving user identity in system_db.
     */
    record RevokeDatabaseScopeCommand(
        String username,
        String targetDatabase,
        CommandSource source
    ) implements UserAdminCommand {
        public RevokeDatabaseScopeCommand {
            Objects.requireNonNull(username, "Username cannot be null");
            Objects.requireNonNull(targetDatabase, "Target database cannot be null");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
        }
    }

    /**
     * Toggles account active status (safe deactivation instead of physical deletion).
     */
    record ToggleUserStatusCommand(
        String username,
        boolean active,
        CommandSource source
    ) implements UserAdminCommand {
        public ToggleUserStatusCommand {
            Objects.requireNonNull(username, "Username cannot be null");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
        }
    }

    /**
     * Intercepted destructive operation representing an attempt to delete/drop a user identity.
     */
    record DeleteUserAttemptCommand(
        String targetIdentifier,
        UUID targetId,
        CommandSource source,
        String reason
    ) implements UserAdminCommand {
        public DeleteUserAttemptCommand {
            targetIdentifier = (targetIdentifier != null && !targetIdentifier.isBlank()) 
                ? targetIdentifier 
                : (targetId != null ? targetId.toString() : "unknown");
            source = source != null ? source : CommandSource.STORAGE_ENGINE;
            reason = reason != null ? reason : "Physical deletion requested";
        }
    }
}
