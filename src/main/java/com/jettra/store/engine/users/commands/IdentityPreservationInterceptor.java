package com.jettra.store.engine.users.commands;

import com.jettra.store.engine.exception.UnsupportedUserDeletionException;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;

import java.util.Optional;

/**
 * Interceptor implementing JettraDB's Identity Preservation Policy.
 * Inspects commands via Java 25 pattern matching and vetos physical user
 * deletion attempts from non-privileged sources with UnsupportedUserDeletionException,
 * while allowing authorized operations from privileged administrative identities (DB_ADMIN/admin).
 */
public class IdentityPreservationInterceptor implements UserAdminInterceptor {

    private final SystemUserRepository systemUserRepo;

    public IdentityPreservationInterceptor() {
        this(null);
    }

    public IdentityPreservationInterceptor(SystemUserRepository systemUserRepo) {
        this.systemUserRepo = systemUserRepo;
    }

    @Override
    public void intercept(UserAdminCommand command) {
        switch (command) {
            case UserAdminCommand.DeleteUserAttemptCommand del -> {
                if (!isPrivileged(del)) {
                    throw new UnsupportedUserDeletionException(
                        del.targetIdentifier(),
                        del.targetId(),
                        del.source().name()
                    );
                }
                if (isManagerInitiator(del) && isTargetAdmin(del)) {
                    throw new com.jettra.store.engine.exception.ImmutableAccountException(
                        "Operación denegada: El usuario con perfil MANAGER no puede alterar ni eliminar el usuario ADMIN."
                    );
                }
            }
            case UserAdminCommand.CreateUserCommand ignored -> {}
            case UserAdminCommand.UpdateUserProfileCommand ignored -> {}
            case UserAdminCommand.AssignDatabaseScopeCommand ignored -> {}
            case UserAdminCommand.RevokeDatabaseScopeCommand ignored -> {}
            case UserAdminCommand.ToggleUserStatusCommand ignored -> {}
        }
    }

    private boolean isPrivileged(UserAdminCommand.DeleteUserAttemptCommand del) {
        if (del.initiatingUser() != null && !del.initiatingUser().isBlank()) {
            String init = del.initiatingUser().trim();
            if ("admin".equalsIgnoreCase(init) || "root".equalsIgnoreCase(init)) {
                return true;
            }
            if (systemUserRepo != null) {
                Optional<SystemUser> su = systemUserRepo.findByUsername(init);
                if (su.isPresent() && (su.get().isAdmin() || "MANAGER".equalsIgnoreCase(su.get().role()))) {
                    return true;
                }
            }
            return false;
        }

        return false;
    }

    private boolean isManagerInitiator(UserAdminCommand.DeleteUserAttemptCommand del) {
        if (del.initiatingUser() != null && !del.initiatingUser().isBlank() && systemUserRepo != null) {
            Optional<SystemUser> su = systemUserRepo.findByUsername(del.initiatingUser().trim());
            return su.isPresent() && "MANAGER".equalsIgnoreCase(su.get().role());
        }
        return false;
    }

    private boolean isTargetAdmin(UserAdminCommand.DeleteUserAttemptCommand del) {
        if ("admin".equalsIgnoreCase(del.targetIdentifier())) {
            return true;
        }
        if (del.targetId() != null && systemUserRepo != null) {
            return systemUserRepo.findById(del.targetId()).map(SystemUser::isAdmin).orElse(false);
        }
        return false;
    }
}
