package com.jettra.store.engine.lifecycle;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates that stopping and restarting the server does NOT delete created users.
 * Users stored in system_db must persist permanently on disk across restarts.
 */
@NotRequiresRunningServer
public class ServerStopUserPersistenceTest {

    private Path tempDir;
    private Path systemDbDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_server_restart_test_");
        systemDbDir = tempDir.resolve("system_db");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("Created users in system_db remain intact on disk when server stops and restarts")
    void testUsersPersistAcrossServerStopAndRestart() throws Exception {
        // --- 1. FIRST RUN: Start server storage and create users ---
        JettraStorageEngine engine1 = new JettraStorageEngine(tempDir.toString());
        engine1.registerEngine("DOCUMENT", new DocumentEngine(engine1));
        engine1.registerEngine("RECORDS", new RecordsEngine(engine1));
        engine1.start();

        SystemUserRepository repo1 = new SystemUserRepositoryImpl(systemDbDir);
        AuthManager auth1 = new AuthManager(repo1);

        // Verify baseline admin exists
        assertTrue(repo1.existsByUsername("admin"), "Admin must exist on initial start");

        // Create a MANAGER user
        SystemUser managerUser = SystemUser.create(
            "operator_mgr",
            SystemUserRepositoryImpl.hashPassword("mgrPass123"),
            "mgr@jettra.io",
            "MANAGER",
            Set.of("sales_db", "inventory_db")
        );
        repo1.save(managerUser);
        auth1.register("operator_mgr", "mgrPass123");

        // Create a regular user
        SystemUser devUser = SystemUser.create(
            "dev_user",
            SystemUserRepositoryImpl.hashPassword("devPass456"),
            "dev@jettra.io",
            "READ_WRITE",
            Set.of("dev_db")
        );
        repo1.save(devUser);
        auth1.register("dev_user", "devPass456");

        assertEquals(3, repo1.count(), "Must have 3 users (admin, operator_mgr, dev_user)");

        // --- 2. STOP SERVER ---
        // Simulates serverOrchestrator.stop() and storageEngine.stop()
        repo1.close();
        engine1.stop();

        // --- 3. VERIFY DISK INTEGRITY AFTER SERVER STOP ---
        Path usersDir = systemDbDir.resolve("users");
        assertTrue(Files.exists(usersDir), "Users directory must persist on disk after server stop");

        long userFilesCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(usersDir, "*.jdb")) {
            for (Path ignored : stream) {
                userFilesCount++;
            }
        }
        assertEquals(3, userFilesCount, "All 3 .jdb user files must remain present on disk after stopping");

        // --- 4. RESTART SERVER: Simulates starting App.java again on existing data ---
        JettraStorageEngine engine2 = new JettraStorageEngine(tempDir.toString());
        engine2.registerEngine("DOCUMENT", new DocumentEngine(engine2));
        engine2.registerEngine("RECORDS", new RecordsEngine(engine2));
        engine2.start();

        SystemUserRepository repo2 = new SystemUserRepositoryImpl(systemDbDir);
        AuthManager auth2 = new AuthManager(repo2);

        // Verify all 3 users exist without data loss
        assertEquals(3, repo2.count(), "All 3 users must be available after server restart");

        Optional<SystemUser> loadedMgr = repo2.findByUsername("operator_mgr");
        assertTrue(loadedMgr.isPresent(), "operator_mgr must be found after restart");
        assertEquals("MANAGER", loadedMgr.get().role(), "Role must be preserved");
        assertTrue(loadedMgr.get().assignedDatabases().contains("sales_db"), "Assigned databases preserved");

        Optional<SystemUser> loadedDev = repo2.findByUsername("dev_user");
        assertTrue(loadedDev.isPresent(), "dev_user must be found after restart");
        assertEquals("READ_WRITE", loadedDev.get().role(), "Role must be preserved");

        // Verify authentication against persisted credentials works after restart
        String mgrToken = auth2.login("operator_mgr", "mgrPass123");
        assertNotNull(mgrToken, "operator_mgr must authenticate successfully after restart");

        String devToken = auth2.login("dev_user", "devPass456");
        assertNotNull(devToken, "dev_user must authenticate successfully after restart");

        // Stop second engine instance
        repo2.close();
        engine2.stop();
    }
}
