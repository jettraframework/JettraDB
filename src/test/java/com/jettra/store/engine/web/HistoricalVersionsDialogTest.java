package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.models.RecordVersionSnapshot;
import com.jettra.store.engine.web.RestoreCommandHandler.RestoreCommand;
import com.jettra.store.engine.web.RestoreCommandHandler.RestoreEvent;
import com.jettra.store.engine.web.RestoreCommandHandler.RestoreSuccessEvent;
import com.jettra.store.engine.web.RestoreCommandHandler.RestoreFailureEvent;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JsonArray;
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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and UI Integration Tests validating:
 * 1. RecordVersionSnapshot DTO serialization & temporal formatting.
 * 2. Data Mapping in HierarchyExplorerService.getVersionsJson.
 * 3. Historical Versions Dialog rendering in JettraFlux with standardized typography contrast.
 * 4. RestoreCommandHandler reactive command execution, validation, Virtual Threads & Event Bus.
 * 5. RestoreActionHandler delegating and StoreEnginesPage integration.
 */
public class HistoricalVersionsDialogTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private JettraJson jsonParser;
    private HierarchyExplorerService hierarchyService;

    @BeforeEach
    public void setUp() throws IOException {
        initIfNeeded();
    }

    private synchronized void initIfNeeded() {
        if (engine == null) {
            try {
                tempDir = Files.createTempDirectory("jettra_historical_versions_test");
                engine = new JettraStorageEngine(tempDir.toString());
                engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
                engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
                engine.registerEngine("RECORDS", new RecordsEngine(engine));
                engine.start();

                jsonParser = new JettraJson();
                hierarchyService = new HierarchyExplorerService(engine);
            } catch (Exception e) {
                throw new RuntimeException("Failed to init test engine", e);
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
    @DisplayName("Test 1: RecordVersionSnapshot DTO attributes, default values, and JSON representation")
    public void testRecordVersionSnapshotDTO() {
        long now = 1700000000000L;
        String rawData = "{\"name\":\"Enterprise Plan\",\"price\":499.99,\"currency\":\"USD\"}";
        RecordVersionSnapshot record = RecordVersionSnapshot.of(1, now, rawData.getBytes(StandardCharsets.UTF_8), true);

        assertEquals(1, record.versionNumber());
        assertEquals("v1", record.versionId());
        assertEquals(now, record.timestamp());
        assertTrue(record.isCurrent());
        assertNotNull(record.formattedDate());
        assertFalse(record.formattedDate().isBlank());
        assertEquals(rawData, record.snapshotData());
        assertTrue(record.snapshotPreview().contains("Enterprise Plan"));

        JsonObject json = record.toJsonObject(jsonParser);
        assertEquals(1, json.get("versionNumber"));
        assertEquals("v1", json.get("versionId"));
        assertEquals(now, json.get("timestamp"));
        assertTrue(json.has("formattedDate"));
        assertTrue(json.has("snapshotPreview"));
        assertTrue(json.has("preview"));
        assertTrue((Boolean) json.get("isCurrent"));

        JettraAssert.assertEquals("v1", record.versionId(), "Version ID should be v1");
        JettraAssert.assertTrue(record.isCurrent(), "Record should be current");
    }

    @Test
    @JettraTest
    @DisplayName("Test 2: HierarchyExplorerService getVersionsJson returns structured array with mapped columns")
    public void testHierarchyExplorerServiceVersionsJsonMapping() {
        initIfNeeded();
        String testDb = "crm_db";
        String coll = "accounts";
        String id = "acc_100";
        String key = "doc:" + testDb + ":" + coll + ":" + id;

        long t1 = System.currentTimeMillis() - 60000;
        long t2 = System.currentTimeMillis();

        // Create version 1 and version 2
        engine.getStorageCore().put(key, "{\"name\":\"Initial Corp\",\"status\":\"PROSPECT\"}".getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, "{\"name\":\"Initial Corp\",\"status\":\"ACTIVE_CLIENT\"}".getBytes(StandardCharsets.UTF_8), t2);

        String versionsJson = hierarchyService.getVersionsJson("DOCUMENT", testDb, coll, id);
        assertNotNull(versionsJson);
        assertFalse(versionsJson.isBlank());
        assertTrue(versionsJson.startsWith("[") && versionsJson.endsWith("]"));

        JsonArray arr = jsonParser.fromJson(versionsJson, JsonArray.class);
        assertNotNull(arr);
        assertTrue(arr.size() >= 1, "Must contain at least 1 version snapshot");

        JsonObject vFirst = (JsonObject) arr.get(0);
        assertTrue(vFirst.has("versionNumber") || vFirst.has("versionId"), "Must contain version identification");
        assertTrue(vFirst.has("timestamp"), "Must contain timestamp");
        assertTrue(vFirst.has("formattedDate"), "Must contain formatted date string");
        assertTrue(vFirst.has("snapshotPreview") || vFirst.has("preview"), "Must contain snapshot preview");

        JettraAssert.assertNotNull(versionsJson, "Versions JSON should not be null");
    }

    @Test
    @JettraTest
    @DisplayName("Test 3: Historical Versions Modal Component rendering and DOM IDs")
    public void testHistoricalVersionsModalRendering() {
        String actionUrl = "/engines?engine=DOCUMENT";

        Widget versionsModal = HistoricalVersionsDialog.buildVersionsModal(actionUrl);
        String html = versionsModal.render(Themes.FlatTheme());

        assertNotNull(html);
        assertTrue(html.contains("id=\"universalRestoreModal\"") || html.contains("id='universalRestoreModal'"));
        assertTrue(html.contains("universalVersionsContainer"), "Must contain universalVersionsContainer DOM container");
        assertTrue(html.contains("restoreEngineLabel"), "Must contain restoreEngineLabel");
        assertTrue(html.contains("restoreRecordIdLabel"), "Must contain restoreRecordIdLabel");

        Widget confirmModal = HistoricalVersionsDialog.buildConfirmRestoreModal(actionUrl);
        String confirmHtml = confirmModal.render(Themes.FlatTheme());

        assertNotNull(confirmHtml);
        assertTrue(confirmHtml.contains("id=\"confirmRestoreModal\"") || confirmHtml.contains("id='confirmRestoreModal'"));
        assertTrue(confirmHtml.contains("confirmRestoreTsInput"), "Must have hidden timestamp input for restore");
        assertTrue(confirmHtml.contains("confirmRestoreDateDisplay"), "Must display snapshot date confirmation");

        JettraAssert.assertTrue(html.contains("universalVersionsContainer"), "Must contain versions container");
    }

    @Test
    @JettraTest
    @DisplayName("Test 4: HistoricalVersionsDialog.renderVersionTable generates table with standardized typography contrast")
    public void testHistoricalVersionsDialogRenderVersionTable() {
        RecordVersionSnapshot v1 = RecordVersionSnapshot.of(1, 1700000000000L, "{\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8), false);
        RecordVersionSnapshot v2 = RecordVersionSnapshot.of(2, 1700000050000L, "{\"role\":\"superadmin\"}".getBytes(StandardCharsets.UTF_8), true);

        Widget tableWidget = HistoricalVersionsDialog.renderVersionTable(List.of(v2, v1));
        String html = tableWidget.render(Themes.FlatTheme());

        assertNotNull(html);
        assertFalse(html.contains("UNDEFINED"), "Table must not contain UNDEFINED text");
        assertTrue(html.contains("v2 (CURRENT)") || html.contains("v2"), "Must render v2 badge");
        assertTrue(html.contains("v1"), "Must render v1 badge");
        assertTrue(html.contains("superadmin"), "Must render preview for active version");
        assertTrue(html.contains("admin"), "Must render preview for historical version");
        assertTrue(html.contains("Restaurar"), "Historical row must have Restaurar button");
        assertTrue(html.contains("Activo"), "Current row must have Activo badge");

        // Verify Snapshot Preview typography contrast matches Date column (var(--j-text-secondary))
        assertTrue(html.contains("color:var(--j-text-secondary)"), "Snapshot preview cell must use standard text-secondary token for contrast");

        JettraAssert.assertTrue(html.contains("Restaurar"), "Must contain restore button");
        JettraAssert.assertTrue(html.contains("color:var(--j-text-secondary)"), "Must use secondary text color token");
    }

    @Test
    @JettraTest
    @DisplayName("Test 5: RestoreCommandHandler executes transactional rollback, validation, and Virtual Thread async")
    public void testRestoreCommandHandlerExecution() throws InterruptedException {
        initIfNeeded();
        RestoreCommandHandler handler = new RestoreCommandHandler(engine);
        String testDb = "tenant_db";
        String coll = "configs";
        String id = "cfg_01";
        String key = "doc:" + testDb + ":" + coll + ":" + id;

        long t1 = 1700000000000L;
        long t2 = 1700000080000L;

        engine.getStorageCore().put(key, "{\"theme\":\"light\"}".getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8), t2);

        // 1. Pre-validation failure test with failure event listener
        CountDownLatch failLatch = new CountDownLatch(1);
        AtomicReference<RestoreEvent> failEventRef = new AtomicReference<>();
        AutoCloseable failSub = handler.subscribe(event -> {
            if (event instanceof RestoreFailureEvent) {
                failEventRef.set(event);
                failLatch.countDown();
            }
        });

        RestoreCommandHandler.RestoreResult invalidRes = handler.handle(new RestoreCommand("DOCUMENT", testDb, coll, "", -1, 0));
        assertFalse(invalidRes.success(), "Invalid command must fail validation");
        boolean failReceived = failLatch.await(2, TimeUnit.SECONDS);
        assertTrue(failReceived, "Reactive listener should receive failure event");
        assertInstanceOf(RestoreFailureEvent.class, failEventRef.get());
        try {
            failSub.close();
        } catch (Exception ignored) {}

        // 2. Setup Reactive Event Bus listener for success event
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RestoreEvent> eventRef = new AtomicReference<>();
        AutoCloseable subscription = handler.subscribe(event -> {
            if (event instanceof RestoreSuccessEvent) {
                eventRef.set(event);
                latch.countDown();
            }
        });

        // Execute synchronous restore
        RestoreCommand cmd = RestoreCommand.of("DOCUMENT", testDb, coll, id, t1);
        RestoreCommandHandler.RestoreResult syncRes = handler.handle(cmd);
        assertTrue(syncRes.success(), "Sync restore must succeed");
        assertEquals("{\"theme\":\"light\"}", new String(engine.getStorageCore().get(key), StandardCharsets.UTF_8));
        assertEquals("{\"theme\":\"light\"}", syncRes.restoredPayloadString());

        // Wait for event notification
        boolean eventReceived = latch.await(2, TimeUnit.SECONDS);
        assertTrue(eventReceived, "Reactive listener should receive success restore event");
        assertInstanceOf(RestoreSuccessEvent.class, eventRef.get());
        RestoreSuccessEvent successEvent = (RestoreSuccessEvent) eventRef.get();
        assertEquals(id, successEvent.command().recordId());

        // Re-modify and test async restore with Virtual Threads
        engine.getStorageCore().put(key, "{\"theme\":\"solarized\"}".getBytes(StandardCharsets.UTF_8), t2 + 1000);
        RestoreCommandHandler.RestoreResult asyncRes = handler.handleAsync(cmd).join();
        assertTrue(asyncRes.success(), "Async restore must succeed");
        assertEquals("{\"theme\":\"light\"}", new String(engine.getStorageCore().get(key), StandardCharsets.UTF_8));

        try {
            subscription.close();
        } catch (Exception ignored) {}
        assertEquals(0, handler.getListenerCount());

        JettraAssert.assertTrue(syncRes.success(), "Synchronous restore must succeed");
        JettraAssert.assertTrue(asyncRes.success(), "Asynchronous restore must succeed");
    }

    @Test
    @JettraTest
    @DisplayName("Test 6: RestoreActionHandler delegates to RestoreCommandHandler")
    public void testRestoreActionHandlerDelegation() {
        initIfNeeded();
        RestoreActionHandler handler = new RestoreActionHandler(engine);
        assertNotNull(handler.getCommandHandler(), "Command handler must be initialized");

        String testDb = "tenant_db";
        String coll = "configs";
        String id = "cfg_02";
        String key = "doc:" + testDb + ":" + coll + ":" + id;

        long t1 = 1700000000000L;
        long t2 = 1700000080000L;

        engine.getStorageCore().put(key, "{\"status\":\"pending\"}".getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, "{\"status\":\"approved\"}".getBytes(StandardCharsets.UTF_8), t2);

        RestoreActionHandler.RestoreResult syncRes = handler.executeRestore("DOCUMENT", testDb, coll, id, t1);
        assertTrue(syncRes.success(), "Sync restore through adapter must succeed");
        assertEquals("{\"status\":\"pending\"}", new String(engine.getStorageCore().get(key), StandardCharsets.UTF_8));

        JettraAssert.assertTrue(syncRes.success(), "Restore action must succeed");
    }

    @Test
    @JettraTest
    @DisplayName("Test 7: StoreEnginesPage full POST restore_version handler refreshes table view")
    public void testStoreEnginesPagePostRestoreHandler() {
        initIfNeeded();
        StoreEnginesPage page = new StoreEnginesPage(engine);
        String testDb = "ecommerce_db";
        String coll = "products";
        String id = "prod_alpha";
        String key = "doc:" + testDb + ":" + coll + ":" + id;

        long t1 = System.currentTimeMillis() - 10000;
        long t2 = System.currentTimeMillis();

        String payloadOriginal = "{\"title\":\"Original Camera\",\"price\":299}";
        String payloadModified = "{\"title\":\"Original Camera\",\"price\":399}";

        engine.getStorageCore().put(key, payloadOriginal.getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, payloadModified.getBytes(StandardCharsets.UTF_8), t2);

        Map<String, String> restoreParams = new HashMap<>();
        restoreParams.put("action", "restore_version");
        restoreParams.put("engine_type", "DOCUMENT");
        restoreParams.put("target_db", testDb);
        restoreParams.put("target_coll", coll);
        restoreParams.put("target_id", id);
        restoreParams.put("version_ts", String.valueOf(t1));
        restoreParams.put("view_mode", "table");

        Widget responseWidget = page.buildContent(null, restoreParams, "dark");
        assertNotNull(responseWidget);

        String html = responseWidget.render(Themes.FlatTheme());
        assertTrue(html.contains("successfully restored") || html.contains("Restored"), "Flash message must indicate restore success");
        assertTrue(html.contains("prod_alpha"), "Table view must render restored record ID");

        JettraAssert.assertTrue(html.contains("prod_alpha"), "Table must display restored item ID");
    }
}
