package com.jettra.store.engine.migration;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise-grade Database Cloning and Migration Service for JettraDB.
 * Implements:
 * <ul>
 *   <li>Full Multi-Model Cloning across all 9 Jettra storage engines.</li>
 *   <li>User Authorization and Scope Migration across SystemUserRepository and legacy JUserRepository.</li>
 *   <li>Safe Source Deletion &amp; Physical Partition Drop.</li>
 *   <li>System Database Protection (system_db / _system).</li>
 * </ul>
 */
public class DatabaseCloneMigrationService {

    private static final String[] MULTI_MODEL_PREFIXES = {
        "rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""
    };

    private final JettraStorageEngine engine;
    private final SystemUserRepository systemUserRepo;
    private final JUserRepository userRepo;

    public DatabaseCloneMigrationService(
        JettraStorageEngine engine,
        SystemUserRepository systemUserRepo,
        JUserRepository userRepo
    ) {
        this.engine = Objects.requireNonNull(engine, "JettraStorageEngine cannot be null");
        this.systemUserRepo = systemUserRepo;
        this.userRepo = userRepo;
    }

    public DatabaseCloneMigrationService(
        JettraStorageEngine engine,
        SystemUserRepository systemUserRepo
    ) {
        this(engine, systemUserRepo, null);
    }

    public DatabaseCloneMigrationService(JettraStorageEngine engine) {
        this(engine, null, null);
    }

    /**
     * Executes the end-to-end database cloning and migration plan.
     *
     * @param plan the migration plan built via {@link DatabaseMigrationPlan.Builder}
     * @return {@link DatabaseMigrationResult} with detailed execution metrics
     */
    public synchronized DatabaseMigrationResult executeMigration(DatabaseMigrationPlan plan) {
        long startTime = System.currentTimeMillis();
        if (plan == null) {
            throw new IllegalArgumentException("DatabaseMigrationPlan cannot be null.");
        }

        String sourceDb = plan.cleanSourceDatabase();
        String targetDb = plan.cleanTargetDatabase();

        // 1. Protection Checks
        if ("system_db".equalsIgnoreCase(sourceDb) || "_system".equalsIgnoreCase(sourceDb)) {
            return DatabaseMigrationResult.failure(
                sourceDb, targetDb,
                "The system database 'system_db' is protected and cannot be renamed.",
                System.currentTimeMillis() - startTime
            );
        }

        if (targetDb.isBlank()) {
            return DatabaseMigrationResult.failure(
                sourceDb, targetDb,
                "Target database name cannot be empty or blank.",
                System.currentTimeMillis() - startTime
            );
        }

        if (sourceDb.equalsIgnoreCase(targetDb)) {
            return DatabaseMigrationResult.failure(
                sourceDb, targetDb,
                "Source database and target database cannot have the same name.",
                System.currentTimeMillis() - startTime
            );
        }

        if ("system_db".equalsIgnoreCase(targetDb) || "_system".equalsIgnoreCase(targetDb) || LsmBTreeHybrid.isReservedDatabaseName(targetDb)) {
            return DatabaseMigrationResult.failure(
                sourceDb, targetDb,
                "Target database name '" + targetDb + "' is reserved and cannot be created.",
                System.currentTimeMillis() - startTime
            );
        }

        // 2. Multi-Model Cloning to Target Partition
        int keysMigrated = 0;
        if (plan.migrateMultiModelKeys() && engine != null && engine.getStorageCore() != null) {
            // Guarantee partition allocation for new database
            engine.getStorageCore().getPartition(targetDb);

            for (String pfx : MULTI_MODEL_PREFIXES) {
                String sourcePrefix = pfx + sourceDb + ":";
                Map<String, byte[]> entries = engine.getStorageCore().scanPrefix(sourcePrefix);

                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    String oldKey = entry.getKey();
                    String keySuffix = oldKey.substring(sourcePrefix.length());
                    String newKey = pfx + targetDb + ":" + keySuffix;

                    engine.getStorageCore().put(newKey, entry.getValue(), System.currentTimeMillis());
                    keysMigrated++;
                }
            }
        }

        // 3. Migrate User Assignments
        int usersMigrated = 0;
        if (plan.migrateUserAssignments()) {
            usersMigrated = migrateUserAssignments(sourceDb, targetDb);
        }

        // 4. Safe Source Deletion & Physical Drop
        if (plan.purgeSourceDatabase() && engine != null && engine.getStorageCore() != null) {
            for (String pfx : MULTI_MODEL_PREFIXES) {
                String sourcePrefix = pfx + sourceDb + ":";
                Map<String, byte[]> entries = engine.getStorageCore().scanPrefix(sourcePrefix);
                for (String oldKey : entries.keySet()) {
                    engine.getStorageCore().delete(oldKey, System.currentTimeMillis());
                }
            }
            // Safely drop memory partition and remove physical disk directory
            engine.getStorageCore().dropDatabase(sourceDb);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return DatabaseMigrationResult.success(sourceDb, targetDb, keysMigrated, usersMigrated, elapsed);
    }

    private int migrateUserAssignments(String sourceDb, String targetDb) {
        AtomicInteger updatedUsers = new AtomicInteger(0);

        // A. Primary SystemUserRepository
        if (systemUserRepo != null) {
            List<SystemUser> allUsers = systemUserRepo.findAll();
            for (SystemUser u : allUsers) {
                Set<String> assigned = new TreeSet<>(u.assignedDatabases());
                boolean hasSource = assigned.contains(sourceDb) || assigned.stream().anyMatch(d -> d.equalsIgnoreCase(sourceDb));
                if (hasSource) {
                    assigned.removeIf(d -> d.equalsIgnoreCase(sourceDb));
                    assigned.add(targetDb);

                    SystemUser updatedUser = u.withUpdatedProfile(u.email(), u.role(), u.active(), assigned);
                    systemUserRepo.save(updatedUser);
                    updatedUsers.incrementAndGet();

                    // Synchronize with legacy repository if corresponding account exists
                    if (userRepo != null) {
                        try {
                            Optional<JUser> legOpt = userRepo.findByUsername(u.username());
                            if (legOpt.isPresent()) {
                                JUser leg = legOpt.get();
                                Set<String> legDbs = new TreeSet<>(leg.assignedDatabases());
                                legDbs.removeIf(d -> d.equalsIgnoreCase(sourceDb));
                                legDbs.add(targetDb);
                                userRepo.save(new JUser(
                                    leg.id(),
                                    leg.firstName(),
                                    String.join(", ", legDbs),
                                    leg.email(),
                                    leg.phone(),
                                    leg.active(),
                                    leg.jRoles(),
                                    legDbs
                                ));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // B. Secondary / Standalone Legacy JUserRepository
        if (userRepo != null) {
            try {
                for (JUser ju : userRepo.findAll()) {
                    Set<String> legDbs = ju.assignedDatabases() != null ? new TreeSet<>(ju.assignedDatabases()) : new TreeSet<>();
                    boolean hasSource = legDbs.contains(sourceDb) || legDbs.stream().anyMatch(d -> d.equalsIgnoreCase(sourceDb));
                    if (hasSource) {
                        legDbs.removeIf(d -> d.equalsIgnoreCase(sourceDb));
                        legDbs.add(targetDb);
                        userRepo.save(new JUser(
                            ju.id(),
                            ju.firstName(),
                            String.join(", ", legDbs),
                            ju.email(),
                            ju.phone(),
                            ju.active(),
                            ju.jRoles(),
                            legDbs
                        ));
                        updatedUsers.incrementAndGet();
                    }
                }
            } catch (Exception ignored) {}
        }

        return updatedUsers.get();
    }
}
