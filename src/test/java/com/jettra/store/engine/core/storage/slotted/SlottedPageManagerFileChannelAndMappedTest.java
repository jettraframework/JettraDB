package com.jettra.store.engine.core.storage.slotted;

import com.jettra.store.engine.core.storage.StoragePathBuilder;
import com.jettra.store.engine.core.storage.StorageRecordFile;
import com.jettra.store.engine.core.storage.StorageRecordPath;
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
import java.util.Optional;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest suite validating SlottedPageManager, .jetrra file extension enforcement,
 * index recovery on reopen, and integration with SlottedPageStorageRecordRepository.
 */
@NotRequiresRunningServer
public class SlottedPageManagerFileChannelAndMappedTest {

    private Path tempDir;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_slotted_mgr_test");
    }

    @AfterEach
    public void tearDown() {
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @JettraTest
    @DisplayName("SlottedPageManager strictly enforces .jetrra file extension")
    public void testStrictJetrraExtensionEnforcement() {
        Path invalidExtensionFile = tempDir.resolve("bad_file.dat");
        boolean rejected = false;
        try {
            new SlottedPageManager(invalidExtensionFile);
        } catch (IllegalArgumentException e) {
            rejected = true;
            assertTrue(e.getMessage().contains(".jetrra"), "Exception must state .jetrra requirement");
        } catch (Exception ignored) {}

        assertTrue(rejected, "Files not ending in .jetrra must be strictly rejected");

        Path validFile = tempDir.resolve("valid_storage.jetrra");
        try (SlottedPageManager mgr = new SlottedPageManager(validFile)) {
            assertNotNull(mgr);
            assertTrue(mgr.getStorageFile().toString().endsWith(".jetrra"));
        } catch (IOException e) {
            fail("Valid .jetrra file must open without error: " + e.getMessage());
        }
    }

    @JettraTest
    @DisplayName("SlottedPageManager writes records, closes, and rebuilds sparse index on reload")
    public void testIndexRecoveryOnReopen() throws IOException {
        Path jetrraFile = tempDir.resolve("catalog.jetrra");

        // 1. Initial write phase
        try (SlottedPageManager mgr = new SlottedPageManager(jetrraFile)) {
            for (int i = 1; i <= 50; i++) {
                String key = "prod_" + i;
                byte[] payload = ("{\"name\":\"Product #" + i + "\",\"price\":" + (i * 10) + "}").getBytes(StandardCharsets.UTF_8);
                mgr.insert(key, payload, 1, System.currentTimeMillis());
            }
            assertEquals(50, mgr.getRecordCount(), "Should have 50 records in index");
            mgr.sync();
        }

        // 2. Reopen file phase - index is rebuilt directly by scanning slotted page headers & slot arrays
        try (SlottedPageManager reopenedMgr = new SlottedPageManager(jetrraFile)) {
            assertEquals(50, reopenedMgr.getRecordCount(), "Reopened manager must recover 50 records in sparse index");

            // Verify random records
            byte[] p1 = reopenedMgr.get("prod_1");
            byte[] p25 = reopenedMgr.get("prod_25");
            byte[] p50 = reopenedMgr.get("prod_50");

            assertNotNull(p1);
            assertNotNull(p25);
            assertNotNull(p50);
            assertTrue(new String(p1, StandardCharsets.UTF_8).contains("Product #1"));
            assertTrue(new String(p25, StandardCharsets.UTF_8).contains("Product #25"));
            assertTrue(new String(p50, StandardCharsets.UTF_8).contains("Product #50"));

            // Test in-place update after reopen
            byte[] updatedP25 = "{\"name\":\"Product #25 Updated In-Place\",\"price\":999}".getBytes(StandardCharsets.UTF_8);
            boolean updated = reopenedMgr.update("prod_25", updatedP25, 2, System.currentTimeMillis());
            assertTrue(updated, "Update on reopened manager must succeed");

            byte[] readUpdated = reopenedMgr.get("prod_25");
            assertNotNull(readUpdated);
            assertTrue(new String(readUpdated, StandardCharsets.UTF_8).contains("Updated In-Place"));

            // Test delete
            boolean deleted = reopenedMgr.delete("prod_1");
            assertTrue(deleted, "Delete must return true");
            assertEquals(49, reopenedMgr.getRecordCount());
            assertNull(reopenedMgr.get("prod_1"));
        }
    }

    @JettraTest
    @DisplayName("SlottedPageStorageRecordRepository integrates with .jetrra files and implements StorageRecordRepository")
    public void testSlottedRepositoryIntegration() throws Exception {
        try (SlottedPageStorageRecordRepository repo = new SlottedPageStorageRecordRepository()) {
            StorageRecordPath path = StoragePathBuilder.create()
                .withRoot(tempDir)
                .withDatabase("inventory_db")
                .withEngine("document")
                .withUnit("items")
                .withRecordId("item_999")
                .build();

            byte[] payload = "{\"sku\":\"SKU-999\",\"stock\":150}".getBytes(StandardCharsets.UTF_8);
            repo.save(path, payload, System.currentTimeMillis(), 1);

            // Verify .jetrra file created on disk
            Path expectedJetrra = tempDir.resolve("databases/inventory_db/document/items/store.jetrra");
            assertTrue(Files.exists(expectedJetrra), "Must persist to store.jetrra fixed page file: " + expectedJetrra);

            // Read payload
            byte[] readPayload = repo.readPayload(path);
            assertNotNull(readPayload);
            assertEquals(new String(payload, StandardCharsets.UTF_8), new String(readPayload, StandardCharsets.UTF_8));

            // Find full record file
            Optional<StorageRecordFile> found = repo.find(path);
            assertTrue(found.isPresent());
            assertEquals("item_999", found.get().recordId());
            assertEquals(1, found.get().version());

            // Count
            long count = repo.countRecords(tempDir, "inventory_db", "document", "items");
            assertEquals(1L, count);

            // Delete
            boolean deleted = repo.delete(path);
            assertTrue(deleted);
            assertFalse(repo.exists(path));
            assertEquals(0L, repo.countRecords(tempDir, "inventory_db", "document", "items"));
        }
    }
}
