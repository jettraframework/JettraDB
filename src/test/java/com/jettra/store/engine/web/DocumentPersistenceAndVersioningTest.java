package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordVersionSnapshot;
import com.jettra.store.engine.web.builder.DocumentPayloadBuilder;
import com.jettra.store.engine.web.workflow.StandardDocumentSaveWorkflow;
import io.jettra.json.JsonObject;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Exclusively JettraTest-powered integration and unit test suite verifying:
 * 1. Immediate persistence on the VERY FIRST save attempt for documents inserted via DocumentEngine.
 * 2. Mandatory incrementation of the document version counter (v1 -> v2) on the first edit.
 * 3. Monotonic sequential version increments (v1 -> v2 -> v3 -> v4 -> v5).
 * 4. Immediate visibility of updated payloads in DocumentEngine.get() and HierarchyExplorerService.
 * 5. Full adherence to Template Method, Builder, and Observer design patterns.
 */
@NotRequiresRunningServer
public class DocumentPersistenceAndVersioningTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private DocumentEngine docEngine;
    private HierarchyExplorerService hierarchyService;
    private EditActionHandler editHandler;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_persistence_ver_test");
        engine = new JettraStorageEngine(tempDir.toString());
        docEngine = new DocumentEngine(engine);
        engine.registerEngine("DOCUMENT", docEngine);
        engine.start();

        hierarchyService = new HierarchyExplorerService(engine);
        editHandler = new EditActionHandler(engine, hierarchyService);
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

    @JettraTest
    @DisplayName("First save attempt on collection document must persist changes and increment version from v1 to v2")
    public void testFirstAttemptSavePersistsAndIncrementsVersion() throws Exception {
        String db = "sales_db";
        String coll = "customers";
        String id = "cust_101";

        // Step 1: Initial document insertion via native DocumentEngine
        JsonObject initDoc = new JsonObject();
        initDoc.addProperty("name", "Acme Corp");
        initDoc.addProperty("status", "ACTIVE");
        initDoc.addProperty("revenue", 100000);
        docEngine.insert(db, coll, id, initDoc);

        // Verify initial state
        JsonObject retrievedV1 = docEngine.get(db, coll, id);
        assertNotNull(retrievedV1, "Initial document must be present in DocumentEngine");
        assertEquals("Acme Corp", retrievedV1.getAsString("name"));
        assertEquals(1, hierarchyService.getItemVersionCount("DOCUMENT", db, coll, id), "Initial version count must be 1");

        // Step 2: First save attempt through EditActionHandler (simulating 'GUARDAR CAMBIOS EN DOCUMENT')
        String updatedJson = "{\"name\":\"Acme Corporation International\",\"status\":\"ACTIVE\",\"revenue\":250000}";
        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, updatedJson, Map.of(
            "doc_payload", updatedJson,
            "target_coll", coll,
            "doc_class", "com.jettra.models.Customer"
        ));

        EditDocumentResult res = editHandler.executeEditAsync(cmd).get(5, TimeUnit.SECONDS);

        // Assertions on the Result of FIRST SAVE
        assertTrue(res.success(), "First save attempt must succeed without error");
        assertEquals("DOCUMENT", res.engineType());
        assertEquals(db, res.database());
        assertEquals(coll, res.collection());
        assertEquals(id, res.recordId());
        assertEquals(2, res.versionCount(), "CRITICAL: First save attempt MUST increment version count to 2!");

        // Step 3: Underlying storage verification via DocumentEngine.get()
        JsonObject retrievedV2 = docEngine.get(db, coll, id);
        assertNotNull(retrievedV2, "Document must still exist in DocumentEngine");
        assertEquals("Acme Corporation International", retrievedV2.getAsString("name"),
                "DocumentEngine must reflect updated payload immediately");
        assertEquals("com.jettra.models.Customer", retrievedV2.getAsString("_class"),
                "_class metadata must be injected via Builder");

        // Step 4: HierarchyExplorerService verification
        assertEquals(2, hierarchyService.getItemVersionCount("DOCUMENT", db, coll, id),
                "HierarchyExplorerService version count must return 2");
        String payloadFromExplorer = hierarchyService.getItemPayload("DOCUMENT", db, coll, id);
        assertTrue(payloadFromExplorer.contains("Acme Corporation International"),
                "HierarchyExplorerService must return updated payload");

        // Step 5: Version history snapshots verification (reverse chronological: latest version first)
        List<RecordVersionSnapshot> snapshots = hierarchyService.getVersionSnapshots("DOCUMENT", db, coll, id);
        assertNotNull(snapshots);
        assertEquals(2, snapshots.size(), "Must have exactly 2 version snapshots");
        assertEquals(2, snapshots.get(0).versionNumber(), "Latest snapshot must be version 2");
        assertTrue(snapshots.get(0).isCurrent(), "Version 2 must be marked as current");
        assertTrue(snapshots.get(0).snapshotData().contains("Acme Corporation International"));

        assertEquals(1, snapshots.get(1).versionNumber(), "Earlier snapshot must be version 1");
        assertFalse(snapshots.get(1).isCurrent(), "Version 1 must not be marked as current");
        assertTrue(snapshots.get(1).snapshotData().contains("Acme Corp"));
    }

    @JettraTest
    @DisplayName("Sequential saves must monotonically increment versions v1 -> v2 -> v3 -> v4 -> v5")
    public void testSequentialSavesIncrementVersionsMonotonically() throws Exception {
        String db = "enterprise_db";
        String coll = "partners";
        String id = "partner_99";

        // Seed v1
        JsonObject seed = new JsonObject();
        seed.addProperty("level", 1);
        docEngine.insert(db, coll, id, seed);

        // Perform sequential edits: v2, v3, v4, v5
        for (int v = 2; v <= 5; v++) {
            String payload = String.format("{\"level\":%d,\"description\":\"Partner tier update %d\"}", v, v);
            EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, payload, Map.of(
                "doc_payload", payload,
                "target_coll", coll
            ));

            EditDocumentResult res = editHandler.executeEditAsync(cmd).get(5, TimeUnit.SECONDS);
            assertTrue(res.success());
            assertEquals(v, res.versionCount(), "Version count must match sequential step " + v);

            // Verify storage
            JsonObject currentDoc = docEngine.get(db, coll, id);
            assertNotNull(currentDoc);
            assertEquals(v, currentDoc.getAsInt("level"));
        }

        // Verify total snapshot history (reverse chronological: latest v5 at index 0 down to v1 at index 4)
        List<RecordVersionSnapshot> snapshots = hierarchyService.getVersionSnapshots("DOCUMENT", db, coll, id);
        assertEquals(5, snapshots.size(), "Must have exactly 5 version snapshots in history");
        for (int i = 0; i < 5; i++) {
            int expectedVersion = 5 - i;
            assertEquals(expectedVersion, snapshots.get(i).versionNumber(), "Snapshot at index " + i + " must be version " + expectedVersion);
        }
    }

    @JettraTest
    @DisplayName("Should persist and increment version for document in default collection")
    public void testDefaultCollectionSaveAndVersioning() throws Exception {
        String db = "config_db";
        String coll = "default";
        String id = "system_settings";

        JsonObject init = new JsonObject();
        init.addProperty("clusterMode", "standalone");
        docEngine.insert(db, coll, id, init);

        String updated = "{\"clusterMode\":\"distributed\",\"nodes\":5}";
        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, updated);

        EditDocumentResult res = editHandler.executeEdit(cmd);
        assertTrue(res.success());
        assertEquals(2, res.versionCount(), "Version must increment to 2 in default collection");

        JsonObject docAfter = docEngine.get(db, coll, id);
        assertNotNull(docAfter);
        assertEquals("distributed", docAfter.getAsString("clusterMode"));
        assertEquals(5, docAfter.getAsInt("nodes"));
    }

    @JettraTest
    @DisplayName("DocumentPayloadBuilder must correctly structure payload and inject class metadata")
    public void testDocumentPayloadBuilder() {
        String raw = "{\"company\":\"Jettra Systems\"}";
        DocumentPayloadBuilder.BuiltPayload built = DocumentPayloadBuilder.builder()
                .engineType("DOCUMENT")
                .collection("clients")
                .recordId("client_01")
                .rawPayload(raw)
                .params(Map.of("doc_class", "io.jettra.models.Client"))
                .build();

        assertNotNull(built);
        assertNotNull(built.payloadBytes());
        assertTrue(built.formattedJson().contains("Jettra Systems"));
        assertTrue(built.formattedJson().contains("\"_class\":\"io.jettra.models.Client\""));
    }

    @JettraTest
    @DisplayName("Observer Pattern must receive success and failure reactive events")
    public void testObserverReactiveEvents() throws Exception {
        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicReference<EditDocumentEvent> successEventRef = new AtomicReference<>();
        AtomicReference<EditDocumentEvent> failureEventRef = new AtomicReference<>();

        editHandler.registerObserver(event -> {
            if (event instanceof EditDocumentSuccessEvent succ) {
                successEventRef.set(succ);
                successLatch.countDown();
            } else if (event instanceof EditDocumentFailureEvent fail) {
                failureEventRef.set(fail);
                failureLatch.countDown();
            }
        });

        // 1. Trigger successful event
        String db = "metrics_db";
        String coll = "servers";
        String id = "srv_alpha";
        docEngine.insert(db, coll, id, new JsonObject());

        EditDocumentCommand okCmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, "{\"ip\":\"10.0.0.1\"}");
        editHandler.executeEdit(okCmd);

        assertTrue(successLatch.await(3, TimeUnit.SECONDS), "Success observer must be invoked");
        assertNotNull(successEventRef.get());
        assertTrue(successEventRef.get() instanceof EditDocumentSuccessEvent);

        // 2. Trigger failure event (empty record ID)
        EditDocumentCommand badCmd = EditDocumentCommand.of("DOCUMENT", db, coll, "", "{}");
        EditDocumentResult failRes = editHandler.executeEdit(badCmd);

        assertFalse(failRes.success());
        assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "Failure observer must be invoked");
        assertNotNull(failureEventRef.get());
        assertTrue(failureEventRef.get() instanceof EditDocumentFailureEvent);
    }
}
