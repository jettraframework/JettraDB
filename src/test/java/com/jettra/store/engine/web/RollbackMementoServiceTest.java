package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.MementoService;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordMemento;
import com.jettra.store.engine.models.SnapshotPayload;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.core.JettraAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests validating:
 * 1. Java 25 Sealed SnapshotPayload hierarchy with Pattern Matching in switch expressions.
 * 2. Memento Pattern implementation (RecordMemento and MementoService).
 * 3. Atomic rollback restoration and append-only version history maintenance.
 * 4. Asynchronous Rollback execution via Java 25 Virtual Threads (Thread.ofVirtual()).
 */
public class RollbackMementoServiceTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private JettraJson jsonParser;
    private MementoService mementoService;
    private RestoreCommandHandler restoreCommandHandler;

    @BeforeEach
    public void setUp() throws IOException {
        initIfNeeded();
    }

    private synchronized void initIfNeeded() {
        if (engine == null) {
            try {
                tempDir = Files.createTempDirectory("jettra_memento_test");
                engine = new JettraStorageEngine(tempDir.toString());
                engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
                engine.start();

                jsonParser = new JettraJson();
                mementoService = new MementoService(engine);
                restoreCommandHandler = new RestoreCommandHandler(engine);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize test engine", e);
            }
        }
    }

    @AfterEach
    public void tearDown() throws IOException {
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

    @Test
    @JettraTest
    @DisplayName("Test 1: SnapshotPayload Sealed Hierarchy and Pattern Matching")
    public void testSnapshotPayloadPatternMatching() {
        initIfNeeded();
        // Structured JSON
        byte[] jsonBytes = "{\"sku\":\"PROD-99\",\"stock\":150,\"active\":true}".getBytes(StandardCharsets.UTF_8);
        SnapshotPayload payloadJson = SnapshotPayload.fromBytes(jsonBytes, jsonParser);
        assertInstanceOf(SnapshotPayload.StructuredJsonPayload.class, payloadJson);

        String jsonSummary = SnapshotPayload.formatPayload(payloadJson);
        assertTrue(jsonSummary.startsWith("JSON (3 fields):"));
        assertTrue(jsonSummary.contains("PROD-99"));

        // Key-Value
        byte[] kvBytes = "session_token=xyz_secret_token_123".getBytes(StandardCharsets.UTF_8);
        SnapshotPayload payloadKv = SnapshotPayload.fromBytes(kvBytes, jsonParser);
        assertInstanceOf(SnapshotPayload.KeyValuePayload.class, payloadKv);

        String kvSummary = SnapshotPayload.formatPayload(payloadKv);
        assertTrue(kvSummary.startsWith("KV: session_token"));

        // Raw Text
        byte[] textBytes = "Plain text log entry without json structure.".getBytes(StandardCharsets.UTF_8);
        SnapshotPayload payloadText = SnapshotPayload.fromBytes(textBytes, jsonParser);
        assertInstanceOf(SnapshotPayload.RawTextPayload.class, payloadText);

        String textSummary = SnapshotPayload.formatPayload(payloadText);
        assertTrue(textSummary.startsWith("TEXT (UTF-8):"));

        JettraAssert.assertNotNull(jsonSummary, "JSON summary should not be null");
        JettraAssert.assertTrue(jsonSummary.contains("PROD-99"), "Should contain SKU");
    }

    @Test
    @JettraTest
    @DisplayName("Test 2: MementoService atomic rollback restores aggregate and creates append-only version")
    public void testMementoServiceAtomicRollbackAndAppendOnlyHistory() {
        initIfNeeded();
        String db = "warehouse_db";
        String coll = "inventory";
        String id = "item_404";
        String key = "doc:" + db + ":" + coll + ":" + id;

        long t1 = 1700000000000L;
        long t2 = 1700000050000L;

        String v1Data = "{\"status\":\"IN_STOCK\",\"quantity\":100}";
        String v2Data = "{\"status\":\"OUT_OF_STOCK\",\"quantity\":0}";

        // Write version 1 and version 2
        engine.getStorageCore().put(key, v1Data.getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, v2Data.getBytes(StandardCharsets.UTF_8), t2);

        assertEquals(2, engine.getStorageCore().getVersionCount(key), "Initial version count should be 2");
        assertEquals(v2Data, new String(engine.getStorageCore().get(key), StandardCharsets.UTF_8), "Current state should be v2");

        // Find Memento for v1 (timestamp t1)
        RecordMemento mementoV1 = mementoService.findMemento("DOCUMENT", db, coll, id, t1);
        assertNotNull(mementoV1, "Memento for v1 should be found");
        assertEquals(t1, mementoV1.timestamp());
        assertTrue(mementoV1.getPayloadString().contains("IN_STOCK"));

        // Apply Rollback to v1
        MementoService.MementoRestoreResult res = mementoService.applyRollback(mementoV1);
        assertTrue(res.success(), "Rollback application should succeed");
        assertEquals(3, res.newVersionNumber(), "New active version number must be 3 (append-only strategy)");

        // Verify active record adopts exact v1 snapshot data
        byte[] activeData = engine.getStorageCore().get(key);
        assertNotNull(activeData);
        assertEquals(v1Data, new String(activeData, StandardCharsets.UTF_8), "Active record must have adopted exact v1 snapshot values");

        // Verify version history length increased to 3 without truncating v2
        assertEquals(3, engine.getStorageCore().getVersionCount(key), "Version history must contain 3 versions");

        JettraAssert.assertEquals(3, res.newVersionNumber(), "Active version should be 3");
        JettraAssert.assertEquals(v1Data, new String(activeData, StandardCharsets.UTF_8), "Active state should match v1 data");
    }

    @Test
    @JettraTest
    @DisplayName("Test 3: RollbackCommand execution with Java 25 Virtual Threads and Reactive Events")
    public void testRollbackCommandVirtualThreadsExecution() {
        initIfNeeded();
        String db = "finance_db";
        String coll = "invoices";
        String id = "inv_900";
        String key = "doc:" + db + ":" + coll + ":" + id;

        long t1 = 1700000010000L;
        long t2 = 1700000090000L;

        String originalInvoice = "{\"invoiceId\":\"INV-900\",\"amount\":1500.00,\"paid\":false}";
        String corruptedInvoice = "{\"invoiceId\":\"INV-900\",\"amount\":0.00,\"paid\":true}";

        engine.getStorageCore().put(key, originalInvoice.getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, corruptedInvoice.getBytes(StandardCharsets.UTF_8), t2);

        RollbackCommand cmd = new RollbackCommand("DOCUMENT", db, coll, id, t1, 1, "audit_admin", "Correction of corrupted invoice");

        CompletableFuture<RestoreCommandHandler.RestoreResult> asyncResult = restoreCommandHandler.handleAsync(cmd);
        RestoreCommandHandler.RestoreResult res = asyncResult.join();

        assertNotNull(res);
        assertTrue(res.success(), "Async rollback execution must succeed");
        assertEquals(id, res.recordId());
        assertNotNull(res.restoredPayloadString());
        assertTrue(res.restoredPayloadString().contains("INV-900"), "Must contain restored invoice ID");
        assertTrue(res.restoredPayloadString().contains("\"paid\":false"), "Must contain restored paid status");

        // Verify aggregate state
        String activeState = new String(engine.getStorageCore().get(key), StandardCharsets.UTF_8);
        assertTrue(activeState.contains("INV-900"));
        assertTrue(activeState.contains("\"paid\":false"));
        assertEquals(3, engine.getStorageCore().getVersionCount(key));

        JettraAssert.assertTrue(res.success(), "Rollback must succeed asynchronously via Virtual Threads");
    }
}
