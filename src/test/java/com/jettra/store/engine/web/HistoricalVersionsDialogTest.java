package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.models.VersionSnapshotRecord;
import com.jettra.store.engine.web.StorageModalCommands.HierarchyRowCommand;
import com.jettra.store.engine.web.StorageModalCommands.RestoreCommand;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and UI Integration Tests validating:
 * 1. VersionSnapshotRecord DTO serialization & temporal formatting.
 * 2. Data Mapping in HierarchyExplorerService.getVersionsJson.
 * 3. Historical Versions Dialog rendering in JettraFlux.
 * 4. Restore Action Command dispatch and state synchronization.
 */
public class HistoricalVersionsDialogTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private JettraJson jsonParser;
    private HierarchyExplorerService hierarchyService;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_historical_versions_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        jsonParser = new JettraJson();
        hierarchyService = new HierarchyExplorerService(engine);
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

    @Test
    @DisplayName("Test 1: VersionSnapshotRecord DTO attributes, default values, and JSON representation")
    void testVersionSnapshotRecordDTO() {
        long now = 1700000000000L;
        String rawData = "{\"name\":\"Enterprise Plan\",\"price\":499.99,\"currency\":\"USD\"}";
        VersionSnapshotRecord record = VersionSnapshotRecord.of(1, now, rawData.getBytes(StandardCharsets.UTF_8), true);

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
    }

    @Test
    @DisplayName("Test 2: HierarchyExplorerService getVersionsJson returns structured array with mapped columns")
    void testHierarchyExplorerServiceVersionsJsonMapping() {
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
    }

    @Test
    @DisplayName("Test 3: Historical Versions Modal Component rendering and DOM IDs")
    void testHistoricalVersionsModalRendering() {
        String actionUrl = "/engines?engine=DOCUMENT";

        Widget versionsModal = StorageModalCommands.buildVersionsModal(actionUrl);
        String html = versionsModal.render(Themes.FlatTheme());

        assertNotNull(html);
        assertTrue(html.contains("id=\"universalRestoreModal\"") || html.contains("id='universalRestoreModal'"));
        assertTrue(html.contains("universalVersionsContainer"), "Must contain universalVersionsContainer DOM container");
        assertTrue(html.contains("restoreEngineLabel"), "Must contain restoreEngineLabel");
        assertTrue(html.contains("restoreRecordIdLabel"), "Must contain restoreRecordIdLabel");

        Widget confirmModal = StorageModalCommands.buildConfirmRestoreModal(actionUrl);
        String confirmHtml = confirmModal.render(Themes.FlatTheme());

        assertNotNull(confirmHtml);
        assertTrue(confirmHtml.contains("id=\"confirmRestoreModal\"") || confirmHtml.contains("id='confirmRestoreModal'"));
        assertTrue(confirmHtml.contains("confirmRestoreTsInput"), "Must have hidden timestamp input for restore");
        assertTrue(confirmHtml.contains("confirmRestoreDateDisplay"), "Must display snapshot date confirmation");
    }

    @Test
    @DisplayName("Test 4: Restore Command dispatch and StorageCore state rollback")
    void testRestoreCommandExecutionAndRollback() {
        String testDb = "inventory_db";
        String coll = "items";
        String id = "sku_999";
        String key = "doc:" + testDb + ":" + coll + ":" + id;

        long t1 = 1700000000000L;
        long t2 = 1700000050000L;

        String payloadV1 = "{\"name\":\"Widget\",\"stock\":100}";
        String payloadV2 = "{\"name\":\"Widget\",\"stock\":45}";

        engine.getStorageCore().put(key, payloadV1.getBytes(StandardCharsets.UTF_8), t1);
        engine.getStorageCore().put(key, payloadV2.getBytes(StandardCharsets.UTF_8), t2);

        // Verify current active payload is V2
        byte[] current = engine.getStorageCore().get(key);
        assertEquals(payloadV2, new String(current, StandardCharsets.UTF_8));

        // Dispatch RestoreCommand
        HierarchyRowCommand restoreCmd = new RestoreCommand("DOCUMENT", testDb, coll, id, t1, 1);
        String dispatched = StorageModalCommands.dispatchCommand(restoreCmd);
        assertTrue(dispatched.startsWith("RESTORE:DOCUMENT:inventory_db:items:sku_999@"));

        // Execute restore to timestamp t1
        boolean restored = engine.getStorageCore().restoreVersion(key, t1);
        assertTrue(restored, "Storage core restoreVersion must succeed");

        // Verify active payload rolled back to V1
        byte[] rolledBack = engine.getStorageCore().get(key);
        assertEquals(payloadV1, new String(rolledBack, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Test 5: StoreEnginesPage full POST restore_version handler refreshes table view")
    void testStoreEnginesPagePostRestoreHandler() {
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
    }
}
