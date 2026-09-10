package com.jettra.store.engine.server;

import com.jettra.store.engine.exception.UnsupportedUserDeletionException;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.commands.UserAdminCommand;
import com.jettra.store.engine.users.commands.UserAdminCommand.CommandSource;
import com.jettra.store.engine.users.commands.UserAdminPipeline;
import com.jettra.store.engine.users.commands.UserAdminPipeline.UserCommandResult;
import io.jettra.server.autentification.repository.JUserRepository;

import java.util.Objects;
import java.util.Set;

/**
 * Shell command dispatcher and interpreter for JettraStore CLI / Shell sessions.
 * Intercepts administrative user commands and routes them through the UserAdminPipeline.
 * Specifically intercepts 'DROP USER', 'DELETE USER', and 'user delete', vetoing them
 * with UnsupportedUserDeletionException in strict adherence to the Identity Preservation Policy.
 */
public class JettraShellCommandDispatcher {

    private final UserAdminPipeline pipeline;

    public JettraShellCommandDispatcher(UserAdminPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "UserAdminPipeline cannot be null");
    }

    public JettraShellCommandDispatcher(SystemUserRepository systemUserRepo, JUserRepository userRepo) {
        this(new UserAdminPipeline(systemUserRepo, userRepo));
    }

    /**
     * Interprets and executes shell CLI command lines.
     *
     * @param commandLine the raw shell command string
     * @return result description string
     * @throws UnsupportedUserDeletionException if a physical user deletion was attempted
     */
    public String executeCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return "Empty command";
        }

        String trimmed = commandLine.trim();
        String lower = trimmed.toLowerCase();

        // Check for physical user deletion commands:
        // 'drop user <username>', 'delete user <username>', 'rm user <username>', 'user delete <username>'
        if (lower.startsWith("drop user ") || lower.startsWith("delete user ") || lower.startsWith("rm user ")) {
            String[] parts = trimmed.split("\\s+", 3);
            String targetUser = parts.length >= 3 ? parts[2].trim() : "";
            UserAdminCommand deleteCmd = new UserAdminCommand.DeleteUserAttemptCommand(
                targetUser,
                null,
                CommandSource.SHELL_CLI,
                "Executed CLI command: " + trimmed
            );
            pipeline.execute(deleteCmd);
        }

        if (lower.startsWith("user delete ") || lower.startsWith("user drop ") || lower.startsWith("user rm ")) {
            String[] parts = trimmed.split("\\s+", 3);
            String targetUser = parts.length >= 3 ? parts[2].trim() : "";
            UserAdminCommand deleteCmd = new UserAdminCommand.DeleteUserAttemptCommand(
                targetUser,
                null,
                CommandSource.SHELL_CLI,
                "Executed CLI command: " + trimmed
            );
            pipeline.execute(deleteCmd);
        }

        // Safe database revoke command: 'user revoke <username> <database>'
        if (lower.startsWith("user revoke ")) {
            String[] parts = trimmed.split("\\s+", 4);
            if (parts.length < 4) {
                return "Usage: user revoke <username> <database>";
            }
            String username = parts[2];
            String database = parts[3];
            UserAdminCommand revokeCmd = new UserAdminCommand.RevokeDatabaseScopeCommand(
                username,
                database,
                CommandSource.SHELL_CLI
            );
            UserCommandResult res = pipeline.execute(revokeCmd);
            return res.message();
        }

        // Safe database assign command: 'user assign <username> <database> [role]'
        if (lower.startsWith("user assign ")) {
            String[] parts = trimmed.split("\\s+", 5);
            if (parts.length < 4) {
                return "Usage: user assign <username> <database> [role]";
            }
            String username = parts[2];
            String database = parts[3];
            String role = parts.length >= 5 ? parts[4] : "READ_WRITE";
            UserAdminCommand assignCmd = new UserAdminCommand.AssignDatabaseScopeCommand(
                username,
                database,
                role,
                CommandSource.SHELL_CLI
            );
            UserCommandResult res = pipeline.execute(assignCmd);
            return res.message();
        }

        // Safe account status toggle: 'user status <username> <active|inactive>'
        if (lower.startsWith("user status ")) {
            String[] parts = trimmed.split("\\s+", 4);
            if (parts.length < 4) {
                return "Usage: user status <username> <active|inactive>";
            }
            String username = parts[2];
            boolean active = "active".equalsIgnoreCase(parts[3]) || "true".equalsIgnoreCase(parts[3]);
            UserAdminCommand toggleCmd = new UserAdminCommand.ToggleUserStatusCommand(
                username,
                active,
                CommandSource.SHELL_CLI
            );
            UserCommandResult res = pipeline.execute(toggleCmd);
            return res.message();
        }

        // Safe create user command: 'user create <username> <password> [email] [role] [dbs]'
        if (lower.startsWith("user create ")) {
            String[] parts = trimmed.split("\\s+", 7);
            if (parts.length < 4) {
                return "Usage: user create <username> <password> [email] [role] [dbs]";
            }
            String username = parts[2];
            String password = parts[3];
            String email = parts.length >= 5 ? parts[4] : username + "@jettra.io";
            String role = parts.length >= 6 ? parts[5] : "READ_WRITE";
            String dbsParam = parts.length >= 7 ? parts[6] : "*";
            Set<String> dbs = Set.of(dbsParam.split(","));

            UserAdminCommand createCmd = new UserAdminCommand.CreateUserCommand(
                username,
                email,
                password,
                role,
                dbs,
                CommandSource.SHELL_CLI
            );
            UserCommandResult res = pipeline.execute(createCmd);
            return res.message();
        }

        return "Unrecognized user command: " + trimmed;
    }
}
