package com.jettra.store.engine.security;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit Test Suite validating DatabaseSecurityFilter logic (Filter & Strategy Pattern).
 * Verifies that physical databases discovered from disk are strictly filtered
 * according to user permissions, roles, entity scopes, and wildcards.
 */
@NotRequiresRunningServer
public class DatabaseSecurityFilterTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private DatabaseSecurityFilter securityFilter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_db_sec_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        userRepo = new JUserRepositoryImpl();
        securityFilter = new DatabaseSecurityFilter(systemUserRepo, userRepo);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @JettraTest
    @DisplayName("1. Physical Discovery: Discovers databases from disk directories and storage prefixes")
    void testDiscoverAllPhysicalDatabases() throws IOException {
        // Create physical database folders on disk under storageDirectory/databases/
        Path dbRoot = tempDir.resolve("databases");
        Files.createDirectories(dbRoot.resolve("sales_db"));
        Files.createDirectories(dbRoot.resolve("inventory_db"));

        // Insert keys into storage core with engine prefix
        engine.getStorageCore().put("doc:analytics_db:dashboards:1", "{}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        Set<String> discovered = securityFilter.discoverAllPhysicalDatabases(engine);

        assertTrue(discovered.contains("sales_db"), "Must discover physical folder sales_db on disk");
        assertTrue(discovered.contains("inventory_db"), "Must discover physical folder inventory_db on disk");
        assertTrue(discovered.contains("analytics_db"), "Must discover prefix-based analytics_db");
        assertTrue(discovered.contains("system_db"), "Must contain default system_db");
    }

    @JettraTest
    @DisplayName("2. Global Administrator: Admin identity possesses unrestricted cluster-wide access")
    void testAdminWildcardAccess() {
        SecurityPrincipal adminPrincipal = SecurityPrincipal.of("admin", "ADMIN", "");
        Set<String> allDbs = Set.of("sales_db", "finance_db", "hr_db", "system_db", "custom_db");

        for (String db : allDbs) {
            assertTrue(securityFilter.isAuthorized(adminPrincipal, db), "Admin must be authorized for " + db);
        }

        Set<String> filtered = securityFilter.filterDatabases(adminPrincipal, allDbs);
        assertEquals(allDbs.size(), filtered.size(), "Admin must retain all databases after filtering");
    }

    @JettraTest
    @DisplayName("3. Explicit Database Scope: Scoped user is authorized only for assigned databases")
    void testScopedUserAuthorization() {
        String testUser = "analyst_scoped_" + System.currentTimeMillis();
        SystemUser sysUser = SystemUser.create(testUser, "hash", testUser + "@jettra.io", "USER", Set.of("sales_db", "marketing_db"));
        systemUserRepo.save(sysUser);

        SecurityPrincipal principal = securityFilter.resolvePrincipal(null, testUser, "USER", "");

        assertTrue(securityFilter.isAuthorized(principal, "sales_db"), "User must be authorized for sales_db");
        assertTrue(securityFilter.isAuthorized(principal, "marketing_db"), "User must be authorized for marketing_db");

        assertFalse(securityFilter.isAuthorized(principal, "finance_db"), "User must NOT be authorized for finance_db");
        assertFalse(securityFilter.isAuthorized(principal, "system_db"), "User must NOT be authorized for system_db");
        assertFalse(securityFilter.isAuthorized(principal, "hr_db"), "User must NOT be authorized for hr_db");

        Set<String> allCandidates = Set.of("sales_db", "marketing_db", "finance_db", "system_db", "hr_db");
        Set<String> filtered = securityFilter.filterDatabases(principal, allCandidates);

        assertEquals(2, filtered.size());
        assertTrue(filtered.contains("sales_db"));
        assertTrue(filtered.contains("marketing_db"));
        assertFalse(filtered.contains("finance_db"));
        assertFalse(filtered.contains("system_db"));
    }

    @JettraTest
    @DisplayName("4. Database-Specific Roles: DB_<NAME> grant access to specific database")
    void testDatabaseRoleAccess() {
        SecurityPrincipal dbScopedPrincipal = SecurityPrincipal.of("developer", "DB_TELEMETRY", "");

        assertTrue(securityFilter.isAuthorized(dbScopedPrincipal, "telemetry"), "Must authorize telemetry via DB_TELEMETRY role");
        assertFalse(securityFilter.isAuthorized(dbScopedPrincipal, "sales_db"), "Must NOT authorize sales_db without role or assignment");
    }

    @JettraTest
    @DisplayName("5. Department Matching: Department name matches database namespace")
    void testDepartmentScopeAccess() {
        SecurityPrincipal deptPrincipal = SecurityPrincipal.of("accountant", "USER", "finance_db");

        assertTrue(securityFilter.isAuthorized(deptPrincipal, "finance_db"), "Must authorize finance_db matching department");
        assertFalse(securityFilter.isAuthorized(deptPrincipal, "engineering_db"), "Must NOT authorize engineering_db");
    }
}
