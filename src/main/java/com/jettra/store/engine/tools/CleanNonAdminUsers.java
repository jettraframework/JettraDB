package com.jettra.store.engine.tools;

import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.server.autentification.repository.JettraSecurityDBInitializer;

import java.util.List;
import java.util.Optional;

/**
 * Utility to purge all non-admin users and non-admin credentials,
 * ensuring only the default admin record remains in JettraSecurityDB.
 */
public class CleanNonAdminUsers {

    public static void main(String[] args) {
        System.out.println("=== CLEANUP OF NON-ADMIN USERS IN /users (JettraDB) ===");

        // Ensure default admin user and roles are initialized if needed
        JettraSecurityDBInitializer.initializeIfEmpty();

        JUserRepository userRepo = new JUserRepositoryImpl();
        JCredentialRepository credRepo = new JCredentialRepositoryImpl();

        List<JUser> allUsers = userRepo.findAll();
        System.out.println("Current total users in database: " + allUsers.size());

        Optional<JUser> adminOpt = userRepo.findByUsername("admin");
        if (adminOpt.isEmpty()) {
            System.err.println("FATAL: 'admin' user could not be found or initialized! Aborting.");
            return;
        }

        JUser adminUser = adminOpt.get();
        System.out.println("Preserved Administrator Account: " + adminUser.firstName() + " (ID: " + adminUser.id() + ")");

        int removedUsersCount = 0;
        for (JUser user : allUsers) {
            if ("admin".equalsIgnoreCase(user.firstName())) {
                continue;
            }
            System.out.println("  -> Deleting user: " + user.firstName() + " [id=" + user.id() + "]");
            userRepo.delete(user.id());
            removedUsersCount++;
        }

        List<JCredential> allCreds = credRepo.findAll();
        System.out.println("Current total credentials in database: " + allCreds.size());

        int removedCredsCount = 0;
        for (JCredential cred : allCreds) {
            boolean isSelfAdmin = "admin".equalsIgnoreCase(cred.username())
                || (cred.jUser() != null && "admin".equalsIgnoreCase(cred.jUser().firstName()));
            if (isSelfAdmin) {
                continue;
            }
            System.out.println("  -> Deleting credential for: " + cred.username() + " [id=" + cred.id() + "]");
            credRepo.delete(cred.id());
            removedCredsCount++;
        }

        System.out.println("\n=== SUMMARY OF CLEANUP ===");
        System.out.println("Removed users: " + removedUsersCount);
        System.out.println("Removed credentials: " + removedCredsCount);

        List<JUser> remainingUsers = userRepo.findAll();
        List<JCredential> remainingCreds = credRepo.findAll();

        System.out.println("Remaining users in database: " + remainingUsers.size());
        for (JUser u : remainingUsers) {
            System.out.println("  - " + u.firstName() + " (" + u.email() + ")");
        }
        System.out.println("Remaining credentials in database: " + remainingCreds.size());
        for (JCredential c : remainingCreds) {
            System.out.println("  - " + c.username());
        }
    }
}
