package com.jettra.store.engine.web.validation;

import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.UserUpdateCommand;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit Test Suite for UserValidationService and Domain Validation Rules using JettraTest.
 * Validates Java 25 pattern matching, records, sealed results, and Virtual Threads execution.
 */
@NotRequiresRunningServer
public class UserValidationServiceTest {

    private InMemoryUserRepository mockUserRepo;
    private UserValidationService validationService;

    @BeforeEach
    void setUp() {
        mockUserRepo = new InMemoryUserRepository();
        // Seed an existing user
        mockUserRepo.save(new JUser(
            UUID.randomUUID(),
            "admin",
            "*",
            "admin@jettra.io",
            "+123456",
            true,
            Collections.emptySet(),
            Set.of("*")
        ));
        mockUserRepo.save(new JUser(
            UUID.randomUUID(),
            "existing_operator",
            "records_store",
            "operator@jettra.io",
            "+123456",
            true,
            Collections.emptySet(),
            Set.of("records_store")
        ));

        validationService = new UserValidationService(mockUserRepo);
    }

    @JettraTest
    @DisplayName("UsernameRequiredRule should reject null and blank usernames")
    void testUsernameRequiredRule() {
        UsernameRequiredRule rule = new UsernameRequiredRule();

        ValidationResult nullRes = rule.validate(UserValidationContext.forCreate(null, "test@test.com", "secret"));
        assertTrue(nullRes instanceof ValidationResult.Invalid, "Null username must be invalid");
        ValidationResult.Invalid nullInv = (ValidationResult.Invalid) nullRes;
        assertEquals("username", nullInv.field());
        assertEquals("USERNAME_REQUIRED", nullInv.errorCode());

        ValidationResult blankRes = rule.validate(UserValidationContext.forCreate("   ", "test@test.com", "secret"));
        assertTrue(blankRes instanceof ValidationResult.Invalid, "Blank username must be invalid");
    }

    @JettraTest
    @DisplayName("UsernameFormatRule should enforce minimum length and allowed characters")
    void testUsernameFormatRule() {
        UsernameFormatRule rule = new UsernameFormatRule();

        // Too short (< 3)
        ValidationResult shortRes = rule.validate(UserValidationContext.forCreate("ab", "test@test.com", "secret"));
        assertTrue(shortRes instanceof ValidationResult.Invalid, "Short username must be invalid");
        assertEquals("USERNAME_TOO_SHORT", ((ValidationResult.Invalid) shortRes).errorCode());

        // Invalid characters (spaces, symbols)
        ValidationResult invalidChars = rule.validate(UserValidationContext.forCreate("user name!", "test@test.com", "secret"));
        assertTrue(invalidChars instanceof ValidationResult.Invalid, "Username with spaces or symbols must be invalid");
        assertEquals("USERNAME_INVALID_FORMAT", ((ValidationResult.Invalid) invalidChars).errorCode());

        // Valid usernames
        ValidationResult valid1 = rule.validate(UserValidationContext.forCreate("carlos.mendez_01", "test@test.com", "secret"));
        assertTrue(valid1.isValid(), "Valid formatted username should pass");

        ValidationResult valid2 = rule.validate(UserValidationContext.forCreate("john-doe", "test@test.com", "secret"));
        assertTrue(valid2.isValid(), "Valid formatted username with dash should pass");
    }

    @JettraTest
    @DisplayName("UsernameUniquenessRule should prevent duplicate usernames (case-insensitive)")
    void testUsernameUniquenessRule_DetectsDuplicates() {
        UsernameUniquenessRule rule = new UsernameUniquenessRule(mockUserRepo);

        // Exact match
        ValidationResult resExact = rule.validate(UserValidationContext.forCreate("admin", "admin@domain.com", "secret"));
        assertTrue(resExact instanceof ValidationResult.Invalid, "Duplicate 'admin' must be rejected");
        ValidationResult.Invalid inv = (ValidationResult.Invalid) resExact;
        assertTrue(inv.isDuplicate(), "isDuplicate flag should be true");
        assertEquals("USERNAME_DUPLICATE", inv.errorCode());

        // Case-insensitive match
        ValidationResult resCase = rule.validate(UserValidationContext.forCreate("ADMIN", "admin2@domain.com", "secret"));
        assertTrue(resCase instanceof ValidationResult.Invalid, "Uppercase duplicate 'ADMIN' must be rejected");
        assertEquals("USERNAME_DUPLICATE", ((ValidationResult.Invalid) resCase).errorCode());

        // Mixed case match
        ValidationResult resMixed = rule.validate(UserValidationContext.forCreate("Existing_Operator", "op@domain.com", "secret"));
        assertTrue(resMixed instanceof ValidationResult.Invalid, "Mixed-case duplicate must be rejected");
    }

    @JettraTest
    @DisplayName("UsernameUniquenessRule should allow available username")
    void testUsernameUniquenessRule_AllowsAvailable() {
        UsernameUniquenessRule rule = new UsernameUniquenessRule(mockUserRepo);

        ValidationResult res = rule.validate(UserValidationContext.forCreate("brand_new_user", "new@jettra.io", "secret"));
        assertTrue(res.isValid(), "Available username should be valid");
    }

    @JettraTest
    @DisplayName("UsernameUniquenessRule should allow current user's username when excludeUserId matches")
    void testUsernameUniquenessRule_AllowsSameUserOnUpdate() {
        Optional<JUser> adminOpt = mockUserRepo.findByUsername("admin");
        assertTrue(adminOpt.isPresent(), "Admin must exist");
        UUID adminId = adminOpt.get().id();

        UsernameUniquenessRule rule = new UsernameUniquenessRule(mockUserRepo);

        // Same username with excludeUserId = adminId should pass
        UserValidationContext updateSameCtx = UserValidationContext.forUpdate("admin", "admin@updated.io", null, adminId);
        ValidationResult updateSameRes = rule.validate(updateSameCtx);
        assertTrue(updateSameRes.isValid(), "Updating the same user without changing username should be permitted");

        // Attempting to change to someone else's username should still fail
        UserValidationContext collisionCtx = UserValidationContext.forUpdate("existing_operator", "admin@updated.io", null, adminId);
        ValidationResult collisionRes = rule.validate(collisionCtx);
        assertTrue(collisionRes instanceof ValidationResult.Invalid, "Changing username to an already existing user must fail");
    }

    @JettraTest
    @DisplayName("UserValidationService orchestrates the Chain of Responsibility")
    void testUserValidationService_ChainExecution() {
        // Blank input stops at required rule
        ValidationResult resBlank = validationService.validate(UserValidationContext.forCreate("", "", ""));
        assertTrue(resBlank instanceof ValidationResult.Invalid);
        assertEquals("USERNAME_REQUIRED", ((ValidationResult.Invalid) resBlank).errorCode());

        // Short input stops at format rule
        ValidationResult resShort = validationService.validate(UserValidationContext.forCreate("u", "u@jettra.io", "pass"));
        assertTrue(resShort instanceof ValidationResult.Invalid);
        assertEquals("USERNAME_TOO_SHORT", ((ValidationResult.Invalid) resShort).errorCode());

        // Duplicate input stops at uniqueness rule
        ValidationResult resDuplicate = validationService.validate(UserValidationContext.forCreate("admin", "a@jettra.io", "pass"));
        assertTrue(resDuplicate instanceof ValidationResult.Invalid);
        assertEquals("USERNAME_DUPLICATE", ((ValidationResult.Invalid) resDuplicate).errorCode());

        // Clean unique input passes
        ValidationResult resValid = validationService.validate(UserValidationContext.forCreate("valid_user", "v@jettra.io", "pass"));
        assertTrue(resValid.isValid());
    }

    @JettraTest
    @DisplayName("UserValidationService executes asynchronously via Java 25 Virtual Threads")
    void testUserValidationService_VirtualThreadsAsync() {
        UserValidationContext ctx = UserValidationContext.forCheck("async_user", null);
        CompletableFuture<ValidationResult> future = validationService.validateAsync(ctx);

        assertNotNull(future, "CompletableFuture should not be null");
        ValidationResult res = future.join();
        assertTrue(res.isValid(), "Async validation should return valid result for new user");

        // Test duplicate asynchronously
        UserValidationContext dupCtx = UserValidationContext.forCheck("admin", null);
        ValidationResult dupRes = validationService.validateAsync(dupCtx).join();
        assertTrue(dupRes instanceof ValidationResult.Invalid, "Async check should detect duplicate");
        assertEquals("USERNAME_DUPLICATE", ((ValidationResult.Invalid) dupRes).errorCode());
    }

    /**
     * In-Memory mock implementation of JUserRepository for isolation testing.
     */
    private static class InMemoryUserRepository implements JUserRepository {
        private final List<JUser> users = new ArrayList<>();

        @Override
        public Optional<JUser> findById(UUID id) {
            return users.stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public Optional<JUser> findByUsername(String username) {
            if (username == null) return Optional.empty();
            return users.stream().filter(u -> u.firstName().equalsIgnoreCase(username.trim())).findFirst();
        }

        @Override
        public List<JUser> findAll() {
            return new ArrayList<>(users);
        }

        @Override
        public void save(JUser user) {
            users.removeIf(u -> u.id().equals(user.id()));
            users.add(user);
        }

        @Override
        public void delete(UUID id) {
            users.removeIf(u -> u.id().equals(id));
        }

        @Override
        public List<JUser> search(String query) {
            if (query == null || query.isBlank()) return findAll();
            return users.stream().filter(u -> u.firstName().contains(query) || (u.email() != null && u.email().contains(query))).toList();
        }

        @Override
        public Optional<JUser> updateUser(String username, UserUpdateCommand command) {
            Optional<JUser> existing = findByUsername(username);
            if (existing.isEmpty()) return Optional.empty();
            JUser old = existing.get();
            JUser updated = new JUser(
                old.id(),
                old.firstName(),
                String.join(", ", command.assignedDatabases()),
                command.email(),
                command.phone(),
                command.active(),
                command.roles(),
                command.assignedDatabases()
            );
            save(updated);
            return Optional.of(updated);
        }
    }
}
