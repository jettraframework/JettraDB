package com.jettra.store.engine.users.commands;

import com.jettra.store.engine.exception.ImmutableAccountException;
import com.jettra.store.engine.exception.UnsupportedUserDeletionException;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pipeline for dispatching and executing UserAdminCommands in JettraDB.
 * Enforces the Identity Preservation Policy through registered interceptors,
 * ensuring that user identities are permanent and historical, and that physical
 * deletions are strictly vetoed while database de-associations and status toggles
 * proceed safely.
 */
public class UserAdminPipeline {

    public record UserCommandResult(boolean success, String message, SystemUser user) {
        public static UserCommandResult success(String message, SystemUser user) {
            return new UserCommandResult(true, message, user);
        }

        public static UserCommandResult failure(String message) {
            return new UserCommandResult(false, message, null);
        }
    }

    private final SystemUserRepository systemUserRepo;
    private final JUserRepository userRepo;
    private final List<UserAdminInterceptor> interceptors = new ArrayList<>();

    public UserAdminPipeline(SystemUserRepository systemUserRepo, JUserRepository userRepo) {
        this.systemUserRepo = Objects.requireNonNull(systemUserRepo, "SystemUserRepository cannot be null");
        this.userRepo = userRepo;
        // Default interceptor enforcing strict identity preservation
        this.interceptors.add(new IdentityPreservationInterceptor());
    }

    public UserAdminPipeline addInterceptor(UserAdminInterceptor interceptor) {
        if (interceptor != null) {
            this.interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * Executes the administrative command through the interceptor pipeline and pattern-matched handler.
     */
    public synchronized UserCommandResult execute(UserAdminCommand command) {
        Objects.requireNonNull(command, "Command cannot be null");

        // 1. Interception phase (vetos destructive commands)
        for (UserAdminInterceptor interceptor : interceptors) {
            interceptor.intercept(command);
        }

        // 2. Pattern Matching execution phase (Java 25+)
        return switch (command) {
            case UserAdminCommand.DeleteUserAttemptCommand del -> 
                throw new UnsupportedUserDeletionException(del.targetIdentifier(), del.targetId(), del.source().name());

            case UserAdminCommand.RevokeDatabaseScopeCommand revoke -> {
                String uName = revoke.username().trim();
                String targetDb = revoke.targetDatabase().trim();

                Optional<SystemUser> userOpt = systemUserRepo.findByUsername(uName);
                if (userOpt.isEmpty()) {
                    yield UserCommandResult.failure("User '" + uName + "' does not exist in system_db.");
                }

                SystemUser user = userOpt.get();
                if ("admin".equalsIgnoreCase(user.username())) {
                    throw new ImmutableAccountException("Cannot revoke database access from superadministrator 'admin'.");
                }

                Set<String> currentDbs = new TreeSet<>(user.assignedDatabases());
                if (!currentDbs.contains("*")) {
                    currentDbs.remove(targetDb);
                }

                SystemUser updated = user.withUpdatedProfile(user.email(), user.role(), user.active(), currentDbs);
                systemUserRepo.save(updated);

                if (userRepo != null) {
                    Optional<JUser> legOpt = userRepo.findByUsername(uName);
                    if (legOpt.isPresent()) {
                        JUser leg = legOpt.get();
                        userRepo.save(new JUser(leg.id(), leg.firstName(), String.join(", ", currentDbs), leg.email(), leg.phone(), leg.active(), leg.jRoles(), currentDbs));
                    }
                }

                yield UserCommandResult.success("Database scope '" + targetDb + "' revoked for user '" + uName + "' (identity preserved).", updated);
            }

            case UserAdminCommand.AssignDatabaseScopeCommand assign -> {
                String uName = assign.username().trim();
                String targetDb = assign.targetDatabase().trim();
                String role = assign.role() != null ? assign.role().trim() : "READ_WRITE";

                Optional<SystemUser> userOpt = systemUserRepo.findByUsername(uName);
                if (userOpt.isEmpty()) {
                    yield UserCommandResult.failure("User '" + uName + "' does not exist in system_db.");
                }

                SystemUser user = userOpt.get();
                Set<String> currentDbs = new TreeSet<>(user.assignedDatabases());
                currentDbs.add(targetDb);

                SystemUser updated = user.withUpdatedProfile(user.email(), role, user.active(), currentDbs);
                systemUserRepo.save(updated);

                if (userRepo != null) {
                    Optional<JUser> legOpt = userRepo.findByUsername(uName);
                    if (legOpt.isPresent()) {
                        JUser leg = legOpt.get();
                        userRepo.save(new JUser(leg.id(), leg.firstName(), String.join(", ", currentDbs), leg.email(), leg.phone(), leg.active(), leg.jRoles(), currentDbs));
                    }
                }

                yield UserCommandResult.success("Database scope '" + targetDb + "' granted to user '" + uName + "'.", updated);
            }

            case UserAdminCommand.ToggleUserStatusCommand toggle -> {
                String uName = toggle.username().trim();
                Optional<SystemUser> userOpt = systemUserRepo.findByUsername(uName);
                if (userOpt.isEmpty()) {
                    yield UserCommandResult.failure("User '" + uName + "' does not exist in system_db.");
                }

                SystemUser user = userOpt.get();
                if ("admin".equalsIgnoreCase(user.username()) && !toggle.active()) {
                    throw new ImmutableAccountException("Superadministrator 'admin' cannot be deactivated.");
                }

                SystemUser updated = user.withUpdatedProfile(user.email(), user.role(), toggle.active(), user.assignedDatabases());
                systemUserRepo.save(updated);

                if (userRepo != null) {
                    Optional<JUser> legOpt = userRepo.findByUsername(uName);
                    if (legOpt.isPresent()) {
                        JUser leg = legOpt.get();
                        userRepo.save(new JUser(leg.id(), leg.firstName(), leg.lastName(), leg.email(), leg.phone(), toggle.active(), leg.jRoles(), leg.assignedDatabases()));
                    }
                }

                yield UserCommandResult.success("User '" + uName + "' status updated to " + (toggle.active() ? "ACTIVE" : "INACTIVE") + ".", updated);
            }

            case UserAdminCommand.CreateUserCommand create -> {
                String uName = create.username().trim();
                if (systemUserRepo.findByUsername(uName).isPresent()) {
                    yield UserCommandResult.failure("Username '" + uName + "' already exists.");
                }

                UUID id = UUID.randomUUID();
                String rawPass = (create.password() != null && !create.password().isBlank()) ? create.password() : "password123";
                String hashedPass = SystemUserRepositoryImpl.hashPassword(rawPass);
                String email = (create.email() != null && !create.email().isBlank()) ? create.email().trim() : uName + "@jettra.io";
                String role = (create.role() != null && !create.role().isBlank()) ? create.role().trim() : "READ_WRITE";
                Set<String> dbs = new TreeSet<>(create.databases());

                SystemUser newUser = new SystemUser(id, uName, hashedPass, email, role, true, dbs, Instant.now(), Instant.now());
                systemUserRepo.save(newUser);

                if (userRepo != null) {
                    JRole jRole = new JRole(UUID.randomUUID(), role, true);
                    userRepo.save(new JUser(id, uName, String.join(", ", dbs), email, "+123456", true, Set.of(jRole), dbs));
                }

                yield UserCommandResult.success("User '" + uName + "' created successfully.", newUser);
            }

            case UserAdminCommand.UpdateUserProfileCommand update -> {
                String uName = update.username().trim();
                Optional<SystemUser> userOpt = systemUserRepo.findByUsername(uName);
                if (userOpt.isEmpty()) {
                    yield UserCommandResult.failure("User '" + uName + "' not found.");
                }

                SystemUser existing = userOpt.get();
                String role = update.role() != null ? update.role().trim() : existing.role();
                String email = update.email() != null ? update.email().trim() : existing.email();
                Set<String> dbs = !update.databases().isEmpty() ? update.databases() : existing.assignedDatabases();

                SystemUser updated = existing.withUpdatedProfile(email, role, update.active(), dbs);
                systemUserRepo.save(updated);

                yield UserCommandResult.success("User '" + uName + "' profile updated.", updated);
            }
        };
    }
}
