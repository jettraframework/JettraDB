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
        try {
            purgeNonAdminTestUsers(new JUserRepositoryImpl(), new JCredentialRepositoryImpl());
        } catch (Exception ignored) {}
    }

    public static void purgeNonAdminTestUsers(JUserRepository userRepo, JCredentialRepository credRepo) {
        purgeNonAdminTestUsers(userRepo, credRepo, new com.jettra.store.engine.users.SystemUserRepositoryImpl());
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
