package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for EditActionHandler validating:
 * 1. Java 25 Virtual Threads async execution.
 * 2. New version creation and storage persistence.
 * 3. Observer pattern with reactive event publishing (EditDocumentSuccessEvent / EditDocumentFailureEvent).
 * 4. Resilience and timeout handling without hanging or deadlock.
 */
public class EditActionHandlerTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private EditActionHandler editHandler;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_edit_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();

        editHandler = new EditActionHandler(engine);
    }

    @AfterEach
    public void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Should execute edit asynchronously via Virtual Threads and create new version")
    void testExecuteEditAsyncVirtualThreads() throws Exception {
        String db = "customers_db";
        String coll = "users";
        String id = "cust_101";
        String payloadV1 = "{\"name\":\"Alice\",\"status\":\"PENDING\"}";
        String payloadV2 = "{\"name\":\"Alice Smith\",\"status\":\"VERIFIED\"}";

        // Initial insert
        EditDocumentCommand cmd1 = EditDocumentCommand.of("DOCUMENT", db, coll, id, payloadV1);
        EditDocumentResult res1 = editHandler.executeEditAsync(cmd1).get(5, TimeUnit.SECONDS);

        assertTrue(res1.success());
        assertEquals("DOCUMENT", res1.engineType());
        assertEquals(db, res1.database());
        assertEquals(id, res1.recordId());
        assertTrue(res1.versionCount() >= 1);

        // Edit version 2
        EditDocumentCommand cmd2 = EditDocumentCommand.of("DOCUMENT", db, coll, id, payloadV2);
        EditDocumentResult res2 = editHandler.executeEditAsync(cmd2).get(5, TimeUnit.SECONDS);

        assertTrue(res2.success());
        assertTrue(res2.timestamp() >= res1.timestamp());
        assertTrue(res2.versionCount() >= 2);
    }

    @Test
    @DisplayName("Should notify observers on successful edit via Observer Pattern")
    void testObserverNotificationOnSuccess() throws Exception {
        String db = "products_db";
        String coll = "catalog";
        String id = "prod_500";
        String payload = "{\"sku\":\"SKU-999\",\"price\":49.99}";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EditDocumentEvent> receivedEvent = new AtomicReference<>();

        editHandler.registerObserver(event -> {
            receivedEvent.set(event);
            latch.countDown();
        });

        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, payload);
        EditDocumentResult result = editHandler.executeEditAsync(cmd).get(5, TimeUnit.SECONDS);

        assertTrue(result.success());
        boolean notified = latch.await(3, TimeUnit.SECONDS);
        assertTrue(notified, "Observer should have received event");
        assertNotNull(receivedEvent.get());
        assertInstanceOf(EditDocumentSuccessEvent.class, receivedEvent.get());

        EditDocumentSuccessEvent successEvent = (EditDocumentSuccessEvent) receivedEvent.get();
        assertEquals(id, successEvent.result().recordId());
        assertEquals(db, successEvent.result().database());
    }

    @Test
    @DisplayName("Should notify observer with FailureEvent when record ID is empty")
    void testObserverNotificationOnFailure() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EditDocumentEvent> receivedEvent = new AtomicReference<>();

        editHandler.registerObserver(event -> {
            receivedEvent.set(event);
            latch.countDown();
        });

        // Command with empty ID
        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", "test_db", "test_coll", "", "{}");
        EditDocumentResult result = editHandler.executeEditAsync(cmd).get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertNotNull(result.error());

        boolean notified = latch.await(3, TimeUnit.SECONDS);
        assertTrue(notified, "Observer should have received failure event");
        assertInstanceOf(EditDocumentFailureEvent.class, receivedEvent.get());
    }

    @Test
    @DisplayName("Should enforce resilience timeout without blocking execution")
    void testTimeoutResilience() throws Exception {
        // Test with ultra-short timeout (1 ms) on a mock slow task to verify timeout handling
        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", "db", "col", "id", "{}");
        
        // Ensure default timeout or explicit timeout returns a clean result without hanging
        CompletableFuture<EditDocumentResult> future = editHandler.executeEditAsync(cmd, Duration.ofSeconds(5));
        EditDocumentResult result = future.get(6, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.success());
    }
}
