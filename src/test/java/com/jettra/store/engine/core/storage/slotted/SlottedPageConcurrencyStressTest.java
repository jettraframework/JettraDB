package com.jettra.store.engine.core.storage.slotted;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Concurrency and stress test suite powered by Java 25 Virtual Threads and JettraTest.
 * Validates thread safety, atomic in-place updates, and absence of race conditions.
 */
@NotRequiresRunningServer
public class SlottedPageConcurrencyStressTest {

    private Path tempDir;
    private Path jetrraFile;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_slotted_stress_test");
        jetrraFile = tempDir.resolve("stress_storage" + SlottedPageConstants.FILE_EXTENSION);
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
    @DisplayName("High-concurrency stress test with Virtual Threads executing concurrent inserts and in-place updates")
    public void testConcurrentInsertsAndInPlaceUpdates() throws Exception {
        int totalRecords = 200;
        int threads = 16;

        try (SlottedPageManager mgr = new SlottedPageManager(jetrraFile, SlottedPageConstants.PAGE_SIZE_8KB, null, null, null)) {
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicInteger successInserts = new AtomicInteger(0);
            AtomicInteger successUpdates = new AtomicInteger(0);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // Phase 1: Concurrent Inserts
                for (int i = 0; i < totalRecords; i++) {
                    final int idx = i;
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            String key = "stress_entity_" + idx;
                            byte[] payload = ("{\"id\":" + idx + ",\"state\":\"INITIAL\",\"counter\":0}").getBytes(StandardCharsets.UTF_8);
                            mgr.insert(key, payload, 1, System.currentTimeMillis());
                            successInserts.incrementAndGet();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                startGate.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Inserts should finish within 15 seconds");
            }

            assertEquals(totalRecords, successInserts.get(), "All records must insert successfully");
            assertEquals(totalRecords, mgr.getRecordCount(), "Record count in manager must match totalRecords");

            // Phase 2: Concurrent In-Place Updates
            CountDownLatch updateGate = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < totalRecords; i++) {
                    final int idx = i;
                    executor.submit(() -> {
                        try {
                            updateGate.await();
                            String key = "stress_entity_" + idx;
                            byte[] updatedPayload = ("{\"id\":" + idx + ",\"state\":\"UPDATED_CONCURRENTLY\",\"counter\":100}").getBytes(StandardCharsets.UTF_8);
                            boolean ok = mgr.update(key, updatedPayload, 2, System.currentTimeMillis());
                            if (ok) successUpdates.incrementAndGet();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                updateGate.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Updates should finish within 15 seconds");
            }

            assertEquals(totalRecords, successUpdates.get(), "All records must update in-place successfully");
            mgr.sync();

            // Phase 3: Integrity verification
            for (int i = 0; i < totalRecords; i++) {
                String key = "stress_entity_" + i;
                byte[] payload = mgr.get(key);
                assertNotNull(payload, "Record " + key + " must exist");
                String str = new String(payload, StandardCharsets.UTF_8);
                assertTrue(str.contains("UPDATED_CONCURRENTLY"), "Payload must reflect updated state: " + str);
            }
        }
    }
}
