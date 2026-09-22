package com.jettra.store.engine.migration;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class DatabaseCloneMigrationServiceTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private DatabaseCloneMigrationService migrationService;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_migration_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        userRepo = new JUserRepositoryImpl();
        migrationService = new DatabaseCloneMigrationService(engine, systemUserRepo, userRepo);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    @JettraTest
    @DisplayName("1. Migration plan builder correctly configures source, target, and flags")
    void testMigrationPlanBuilder() {
        DatabaseMigrationPlan plan = DatabaseMigrationPlan.builder()
                .sourceDatabase("orders_db")
                .targetDatabase("orders_prod_2026")
                .migrateMultiModelKeys(true)
                .migrateUserAssignments(true)
                .purgeSourceDatabase(true)
                .build();

        assertEquals("orders_db", plan.sourceDatabase());
        assertEquals("orders_prod_2026", plan.targetDatabase());
        assertEquals("orders_prod_2026", plan.cleanTargetDatabase());
        assertTrue(plan.migrateMultiModelKeys());
        assertTrue(plan.migrateUserAssignments());
        assertTrue(plan.purgeSourceDatabase());
    }

    @JettraTest
    @DisplayName("2. Database cloning migrates all multi-model keys and safely drops source database")
    void testExecuteMigrationClonesKeysAndDropsSource() {
        String sourceDb = "store_catalog";
        String targetDb = "store_catalog_v2";

        // Seed records across 4 different engines in sourceDb
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:" + sourceDb + ":items:item_1", "{\"name\":\"Laptop\",\"price\":1200}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:" + sourceDb + ":items:item_2", "{\"name\":\"Mouse\",\"price\":25}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("rec:" + sourceDb + ":inventory:inv_01", "{\"stock\":150}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:" + sourceDb + ":cache:session_user", "usr_9988".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("vec:" + sourceDb + ":embeddings:vec_01", "[0.12, 0.45, 0.88]".getBytes(StandardCharsets.UTF_8), now);

        DatabaseMigrationPlan plan = DatabaseMigrationPlan.builder()
                .sourceDatabase(sourceDb)
                .targetDatabase(targetDb)
                .build();

        DatabaseMigrationResult result = migrationService.executeMigration(plan);

        assertTrue(result.success(), "Migration must succeed");
        assertEquals(sourceDb, result.sourceDatabase());
        assertEquals(targetDb, result.targetDatabase());
        assertEquals(5, result.keysMigrated(), "Must have migrated all 5 keys");

        // Verify keys exist in target database with correct data
        byte[] doc1 = engine.getStorageCore().get("doc:" + targetDb + ":items:item_1");
        assertNotNull(doc1, "Cloned item_1 must exist in target database");
        assertTrue(new String(doc1, StandardCharsets.UTF_8).contains("Laptop"));

        byte[] rec1 = engine.getStorageCore().get("rec:" + targetDb + ":inventory:inv_01");
        assertNotNull(rec1, "Cloned record must exist in target database");
        assertTrue(new String(rec1, StandardCharsets.UTF_8).contains("150"));

        byte[] kv1 = engine.getStorageCore().get("kv:" + targetDb + ":cache:session_user");
        assertNotNull(kv1, "Cloned KV must exist in target database");
        assertEquals("usr_9988", new String(kv1, StandardCharsets.UTF_8));

        // Verify keys in source database are deleted
        assertNull(engine.getStorageCore().get("doc:" + sourceDb + ":items:item_1"), "Source item_1 must be deleted");
        assertNull(engine.getStorageCore().get("rec:" + sourceDb + ":inventory:inv_01"), "Source record must be deleted");

        // Verify source database dropped from storage core
        Set<String> dbs = engine.getStorageCore().getDatabaseNames();
        assertTrue(dbs.contains(targetDb), "DatabaseNames must contain targetDb");
        assertFalse(dbs.contains(sourceDb), "DatabaseNames must NOT contain dropped sourceDb");
    }

    @JettraTest
    @DisplayName("3. User assignments in SystemUserRepository and JUserRepository are migrated to target database")
    void testUserAssignmentsMigration() {
        String sourceDb = "finance_db";
        String targetDb = "finance_prod_db";

        // Seed users assigned to finance_db
        SystemUser alice = new SystemUser(
                UUID.randomUUID(), "alice_cfo", "hash", "alice@finance.com", "DB_ADMIN",
                true, Set.of(sourceDb, "analytics_db"), java.time.Instant.now(), java.time.Instant.now()
        );
        SystemUser bob = new SystemUser(
                UUID.randomUUID(), "bob_analyst", "hash", "bob@finance.com", "READ_WRITE",
                true, Set.of(sourceDb), java.time.Instant.now(), java.time.Instant.now()
        );
        SystemUser charlie = new SystemUser(
                UUID.randomUUID(), "charlie_hr", "hash", "charlie@hr.com", "READ_ONLY",
                true, Set.of("hr_db"), java.time.Instant.now(), java.time.Instant.now()
        );
        systemUserRepo.save(alice);
        systemUserRepo.save(bob);
        systemUserRepo.save(charlie);

        // Also save alice in legacy repository
        userRepo.save(new JUser(alice.id(), "alice_cfo", sourceDb + ", analytics_db", "alice@finance.com", "+123", true, Set.of(new JRole(UUID.randomUUID(), "DB_ADMIN", true)), Set.of(sourceDb, "analytics_db")));

        // Perform migration
        DatabaseMigrationPlan plan = DatabaseMigrationPlan.builder()
                .sourceDatabase(sourceDb)
                .targetDatabase(targetDb)
                .build();

        DatabaseMigrationResult result = migrationService.executeMigration(plan);

        assertTrue(result.success());
        assertTrue(result.usersMigrated() >= 2, "Must have migrated at least alice and bob");

        // Verify alice
        SystemUser updatedAlice = systemUserRepo.findByUsername("alice_cfo").orElseThrow();
        assertTrue(updatedAlice.assignedDatabases().contains(targetDb), "Alice must now have targetDb");
        assertFalse(updatedAlice.assignedDatabases().contains(sourceDb), "Alice must not have sourceDb");
        assertTrue(updatedAlice.assignedDatabases().contains("analytics_db"), "Alice must still have analytics_db");

        // Verify bob
        SystemUser updatedBob = systemUserRepo.findByUsername("bob_analyst").orElseThrow();
        assertTrue(updatedBob.assignedDatabases().contains(targetDb), "Bob must now have targetDb");
        assertFalse(updatedBob.assignedDatabases().contains(sourceDb), "Bob must not have sourceDb");

        // Verify charlie was unaffected
        SystemUser updatedCharlie = systemUserRepo.findByUsername("charlie_hr").orElseThrow();
        assertTrue(updatedCharlie.assignedDatabases().contains("hr_db"));
        assertFalse(updatedCharlie.assignedDatabases().contains(targetDb));

        // Verify legacy repo updated for alice
        JUser legacyAlice = userRepo.findByUsername("alice_cfo").orElseThrow();
        assertTrue(legacyAlice.assignedDatabases().contains(targetDb), "Legacy repo Alice must have targetDb");
        assertFalse(legacyAlice.assignedDatabases().contains(sourceDb), "Legacy repo Alice must not have sourceDb");
    }

    @JettraTest
    @DisplayName("4. Attempting to rename system_db is safely rejected with protected error message")
    void testSystemDbProtectedFromRename() {
        DatabaseMigrationPlan plan = DatabaseMigrationPlan.builder()
                .sourceDatabase("system_db")
                .targetDatabase("custom_sys_db")
                .build();

        DatabaseMigrationResult result = migrationService.executeMigration(plan);

        assertFalse(result.success(), "Renaming system_db must fail");
        assertTrue(result.message().contains("The system database 'system_db' is protected and cannot be renamed."));
    }

    @JettraTest
    @DisplayName("5. Validates identical source and target names or reserved targets")
    void testValidationEdgeCases() {
        DatabaseMigrationPlan sameDbPlan = DatabaseMigrationPlan.builder()
                .sourceDatabase("my_db")
                .targetDatabase("my_db")
                .build();
        DatabaseMigrationResult sameRes = migrationService.executeMigration(sameDbPlan);
        assertFalse(sameRes.success());
        assertTrue(sameRes.message().contains("same name"));

        DatabaseMigrationPlan reservedPlan = DatabaseMigrationPlan.builder()
                .sourceDatabase("my_db")
                .targetDatabase("system_db")
                .build();
        DatabaseMigrationResult resRes = migrationService.executeMigration(reservedPlan);
        assertFalse(resRes.success());
        assertTrue(resRes.message().contains("reserved"));
    }
}
