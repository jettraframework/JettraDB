package com.jettra.store.engine.web;

import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;

/**
 * Utility ensuring that whenever tests execute, test users in securitydb
 * are completely purged, leaving exclusively the immutable 'admin' user.
 */
public final class SecurityDbTestCleanup {

    private SecurityDbTestCleanup() {}

    public static void purgeNonAdminTestUsers() {
        // No-op by default to prevent accidental production database wipes
    }

    public static void purgeNonAdminTestUsers(JUserRepository userRepo, JCredentialRepository credRepo) {
        purgeNonAdminTestUsers(userRepo, credRepo, null);
    }

    public static void purgeNonAdminTestUsers(JUserRepository userRepo, JCredentialRepository credRepo, com.jettra.store.engine.users.SystemUserRepository sysRepo) {
        if (userRepo != null) {
            try {
                for (JUser user : userRepo.findAll()) {
                    if (!"admin".equalsIgnoreCase(user.firstName())) {
                        try {
                            userRepo.delete(user.id());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        if (credRepo != null) {
            try {
                for (JCredential cred : credRepo.findAll()) {
                    if (!"admin".equalsIgnoreCase(cred.username())) {
                        try {
                            credRepo.delete(cred.id());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        if (sysRepo != null) {
            // Strict safeguard: only purge if storage path is explicitly a test/temp directory
            if (sysRepo.getStoragePath() != null) {
                String pathStr = sysRepo.getStoragePath().toAbsolutePath().toString().toLowerCase();
                boolean isTestOrTemp = pathStr.contains("tmp") || pathStr.contains("temp") || pathStr.contains("test");
                if (!isTestOrTemp) {
                    System.err.println("[SecurityDbTestCleanup] Refusing to purge non-admin users: " + pathStr + " is a production database path!");
                    return;
                }
            }
            try {
                for (com.jettra.store.engine.users.SystemUser su : sysRepo.findAll()) {
                    if (!"admin".equalsIgnoreCase(su.username())) {
                        try {
                            sysRepo.purgeTestUserForTestingOnly(su.id());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
