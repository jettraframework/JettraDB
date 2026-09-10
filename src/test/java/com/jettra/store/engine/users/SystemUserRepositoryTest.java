package com.jettra.store.engine.users;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.exception.ImmutableAccountException;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class SystemUserRepositoryTest {

    private Path tempSystemDbDir;
    private SystemUserRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        tempSystemDbDir = Files.createTempDirectory("jettra_system_db_test");
        repository = new SystemUserRepositoryImpl(tempSystemDbDir);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
        if (tempSystemDbDir != null && Files.exists(tempSystemDbDir)) {
            try {
                Files.walk(tempSystemDbDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("1. Repository bootstraps default protected 'admin' user into system_db on initialization")
    void testBootstrapDefaultAdmin() {
        Optional<SystemUser> adminOpt = repository.findByUsername("admin");
        assertTrue(adminOpt.isPresent(), "Admin user must be automatically provisioned");
        SystemUser admin = adminOpt.get();
        assertEquals("admin", admin.username());
        assertTrue(admin.isAdmin(), "Admin must have admin privileges");
        assertTrue(admin.assignedDatabases().contains("*"), "Admin must have global access");
        assertTrue(repository.count() >= 1, "Count must be at least 1");
    }

    @JettraTest
    @DisplayName("2. Persist new SystemUser to disk under system_db and retrieve by ID and username")
    void testPersistAndRetrieveUser() {
        SystemUser newUser = SystemUser.create(
            "elena_rostova",
            SystemUserRepositoryImpl.hashPassword("secret123"),
            "elena@jettra.io",
            "READ_WRITE",
            Set.of("system_db", "analytics_db")
        );

        SystemUser saved = repository.save(newUser);
        assertNotNull(saved);
        assertEquals(newUser.id(), saved.id());

        // Retrieve by ID
        Optional<SystemUser> byId = repository.findById(newUser.id());
        assertTrue(byId.isPresent(), "User should be found by UUID");
        assertEquals("elena_rostova", byId.get().username());
        assertEquals("elena@jettra.io", byId.get().email());
        assertTrue(byId.get().hasDatabaseAccess("analytics_db"));
        assertFalse(byId.get().hasDatabaseAccess("finance_db"));

        // Retrieve by username (exact match)
        Optional<SystemUser> byUsername = repository.findByUsername("elena_rostova");
        assertTrue(byUsername.isPresent(), "User should be found by exact username");
    }

    @JettraTest
    @DisplayName("3. Case-insensitive lookup and uniqueness detection in system_db")
    void testCaseInsensitiveUniqueness() {
        SystemUser user = SystemUser.create(
            "marcos_silva",
            SystemUserRepositoryImpl.hashPassword("pass"),
            "marcos@jettra.io",
            "READ_WRITE",
            Set.of("*")
        );
        repository.save(user);

        // Case-insensitive exists and find
        assertTrue(repository.existsByUsername("MARCOS_SILVA"), "Uppercase query must detect user");
        assertTrue(repository.existsByUsername("Marcos_Silva"), "Mixed case query must detect user");

        Optional<SystemUser> found = repository.findByUsername("MARCOS_SILVA");
        assertTrue(found.isPresent(), "Must find user regardless of casing");
        assertEquals("marcos_silva", found.get().username());

        // Duplicate rejection with different casing
        SystemUser duplicate = SystemUser.create(
            "MARCOS_SILVA",
            SystemUserRepositoryImpl.hashPassword("pass2"),
            "other@jettra.io",
            "READ_WRITE",
            Set.of("*")
        );

        boolean duplicateRejected = false;
        try {
            repository.save(duplicate);
        } catch (IllegalArgumentException e) {
            duplicateRejected = true;
            assertTrue(e.getMessage().contains("Duplicate username"), "Exception must state duplicate username");
        }
        assertTrue(duplicateRejected, "Repository must strictly reject case-insensitive collided username");
    }

    @JettraTest
    @DisplayName("4. Update user profile while preserving unique identity")
    void testUpdateUserProfile() {
        SystemUser user = SystemUser.create(
            "lucas_dev",
            SystemUserRepositoryImpl.hashPassword("old_pass"),
            "lucas@jettra.io",
            "READ_WRITE",
            Set.of("dev_db")
        );
        repository.save(user);

        SystemUser updated = user.withUpdatedProfile("lucas.new@jettra.io", "DB_ADMIN", false, Set.of("dev_db", "prod_db"));
        repository.save(updated);

        Optional<SystemUser> fetched = repository.findById(user.id());
        assertTrue(fetched.isPresent());
        assertEquals("lucas.new@jettra.io", fetched.get().email());
        assertEquals("DB_ADMIN", fetched.get().role());
        assertFalse(fetched.get().active(), "Account should be deactivated");
        assertTrue(fetched.get().hasDatabaseAccess("prod_db"));
    }

    @JettraTest
    @DisplayName("5. Deleting 'admin' throws ImmutableAccountException while normal users can be revoked")
    void testDeleteAndAdminProtection() {
        // Attempting to delete admin
        boolean adminProtected = false;
        try {
            repository.deleteByUsername("admin");
        } catch (ImmutableAccountException e) {
            adminProtected = true;
            assertTrue(e.getMessage().contains("admin"), "Exception must reference protected admin user");
        }
        assertTrue(adminProtected, "Deleting admin must be strictly prevented");

        // Normal user deletion
        SystemUser temp = SystemUser.create("temp_user", "hash", "temp@jettra.io", "READ_ONLY", Set.of("*"));
        repository.save(temp);
        assertTrue(repository.existsByUsername("temp_user"));

        boolean deleted = repository.delete(temp.id());
        assertTrue(deleted, "Delete should return true for existing non-admin user");
        assertFalse(repository.existsByUsername("temp_user"), "User should no longer exist");
    }

    @JettraTest
    @DisplayName("6. AuthManager integration validates credentials directly against system_db")
    void testAuthManagerWithSystemUserRepository() throws Exception {
        AuthManager authManager = new AuthManager(repository);

        // Authenticate default admin
        assertTrue(authManager.authenticate("admin", "admin"), "Default admin credentials must authenticate");
        String adminToken = authManager.login("admin", "admin");
        assertNotNull(adminToken);
        assertTrue(authManager.validateToken(adminToken));

        // Register user via AuthManager persists to system_db
        authManager.register("auth_tester", "mypassword123");
        assertTrue(repository.existsByUsername("auth_tester"), "Registered user must reside in system_db");

        // Login with newly registered user
        String userToken = authManager.login("auth_tester", "mypassword123");
        assertNotNull(userToken);
        assertTrue(authManager.validateToken(userToken));

        // Change password in system_db
        authManager.changePassword("auth_tester", "mypassword123", "newpassword456");
        assertTrue(authManager.authenticate("auth_tester", "newpassword456"));
        assertFalse(authManager.authenticate("auth_tester", "mypassword123"));

        // Unregister removes user from system_db
        authManager.unregister("auth_tester");
        assertFalse(repository.existsByUsername("auth_tester"), "Unregistered user must be purged from system_db");
    }
}
