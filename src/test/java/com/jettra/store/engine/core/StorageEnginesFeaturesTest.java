package com.jettra.store.engine.core;

import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.DocumentEngine;
import io.jettra.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StorageEnginesFeaturesTest {

    private Path tempDir;
    private JettraStorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_test_db");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();
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
    void testRecordsEnginePersistenceAndStorageCore() {
        RecordsEngine recEngine = (RecordsEngine) engine.getEngine("RECORDS");
        assertNotNull(recEngine);

        JsonObject recordData = new JsonObject();
        recordData.addProperty("firstName", "Linus");
        recordData.addProperty("lastName", "Torvalds");
        recordData.addProperty("role", "Kernel Maintainer");

        recEngine.saveRecord("programmers", "rec_001", "com.jettra.model.PersonRecord", recordData, null);

        // Verify retrieval from engine
        JsonObject retrieved = recEngine.getRecord("programmers", "rec_001");
        assertNotNull(retrieved);
        assertTrue(retrieved.has("components"));

        // Verify retrieval from storage core
        byte[] rawBytes = engine.getStorageCore().get("rec:programmers:rec_001");
        assertNotNull(rawBytes);

        // Delete record
        recEngine.deleteRecord("programmers", "rec_001");

        assertNull(recEngine.getRecord("programmers", "rec_001"));
        assertNull(engine.getStorageCore().get("rec:programmers:rec_001"));
    }

    @Test
    void testKeyValueEnginePersistenceAndStorageCore() {
        KeyValueEngine kvEngine = (KeyValueEngine) engine.getEngine("KEYVALUE");
        assertNotNull(kvEngine);

        kvEngine.put("config_db", "sys.timeout", "5000");

        assertEquals("5000", kvEngine.get("config_db", "sys.timeout"));
        assertNotNull(engine.getStorageCore().get("kv:config_db:sys.timeout"));

        kvEngine.delete("config_db", "sys.timeout");

        assertNull(kvEngine.get("config_db", "sys.timeout"));
        assertNull(engine.getStorageCore().get("kv:config_db:sys.timeout"));
    }

    @Test
    void testDatabaseBackupAndRestore() throws IOException {
        String testDb = "orders_db";
        DocumentEngine docEngine = (DocumentEngine) engine.getEngine("DOCUMENT");
        assertNotNull(docEngine);

        // Populate database with sample records
        for (int i = 1; i <= 5; i++) {
            JsonObject order = new JsonObject();
            order.addProperty("orderId", "ord_" + i);
            order.addProperty("amount", 100.0 * i);
            order.addProperty("status", "COMPLETED");
            docEngine.insert(testDb, "orders", "ord_" + i, order);
        }

        Path backupDir = Files.createTempDirectory("jettra_backup_test");

        // 1. Create Backup
        var backupRes = DatabaseBackupManager.createDatabaseBackup(engine, testDb, backupDir.toString(), "orders_backup.zip");
        assertTrue(backupRes.success(), backupRes.message());
        assertNotNull(backupRes.filePath());
        assertTrue(Files.exists(Path.of(backupRes.filePath())));

        // 2. List Backups
        List<DatabaseBackupManager.BackupFileInfo> backups = DatabaseBackupManager.listBackups(testDb, backupDir.toString());
        assertFalse(backups.isEmpty());
        assertEquals("orders_backup.zip", backups.get(0).fileName());

        // 3. Clear existing database keys
        Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(testDb + ":");
        for (String k : keys.keySet()) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
        }
        assertEquals(0, engine.getStorageCore().scanPrefix(testDb + ":").size());

        // 4. Restore Database from backup .zip
        var restoreRes = DatabaseBackupManager.restoreDatabaseBackup(engine, testDb, backupRes.filePath());
        assertTrue(restoreRes.success(), restoreRes.message());

        // 5. Verify restored items
        Map<String, byte[]> restoredKeys = engine.getStorageCore().scanPrefix(testDb + ":");
        assertTrue(restoredKeys.size() >= 5);

        // Clean up backup directory
        Files.walk(backupDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    void testExampleDBReferencesAndReferenceResolver() {
        com.jettra.store.engine.samples.SampleDatasetManager sampleManager = new com.jettra.store.engine.samples.SampleDatasetManager(engine);
        int loaded = sampleManager.loadExampleDBReferencesDataset();
        assertTrue(loaded > 0, "ExampleDBReferences dataset should load records");

        com.jettra.store.engine.ref.JettraReferenceResolver resolver = new com.jettra.store.engine.ref.JettraReferenceResolver(engine, "primary-node");

        // 1. Resolve Document Customer reference
        var resCust = resolver.resolve("jref://DOCUMENT:ExampleDBReferences/cust_101");
        assertTrue(resCust.exists(), "Customer reference should resolve");
        assertEquals("doc:ExampleDBReferences:cust_101", resCust.primaryStorageAddress());
        assertNotNull(resCust.jsonPayload());
        assertEquals("cust_101", resCust.jsonPayload().getAsString("id"));

        // 2. Resolve Records Employee reference
        var resEmp = resolver.resolve("jref://RECORDS:ExampleDBReferences/emp_201");
        assertTrue(resEmp.exists(), "Employee record should resolve");
        assertEquals("rec:ExampleDBReferences:emp_201", resEmp.primaryStorageAddress());
        assertNotNull(resEmp.jsonPayload());
        assertEquals("emp_201", resEmp.jsonPayload().getAsString("id"));

        // 3. Resolve Geospatial Hub reference
        var resGeo = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_panama");
        assertTrue(resGeo.exists(), "Geospatial hub should resolve");
        assertEquals("geo:ExampleDBReferences:hub_panama", resGeo.primaryStorageAddress());

        // 4. Resolve multi-cluster node reference (remote cluster)
        var resRemote = resolver.resolve("jref://cluster-east-01@DOCUMENT:ExampleDBReferences/cust_101");
        assertTrue(resRemote.exists(), "Remote cluster reference should resolve with primary storage key fallback");
        assertEquals("cluster-east-01", resRemote.clusterNode());
        assertEquals("doc:ExampleDBReferences:cust_101", resRemote.primaryStorageAddress());

        // 5. Resolve Object engine BLOB reference (with .pdf file extension in ID)
        var resObj = resolver.resolve("jref://OBJECT:ExampleDBReferences/contract_enterprise_2026.pdf");
        assertTrue(resObj.exists(), "Object PDF contract reference should resolve");
        assertEquals("obj:ExampleDBReferences:contract_enterprise_2026.pdf", resObj.primaryStorageAddress());

        // 6. Resolve Vector, KeyValue and TimeSeries references
        var resVec = resolver.resolve("jref://node-cloud-west@VECTOR:ExampleDBReferences/vec_prod_embed_01");
        assertTrue(resVec.exists(), "Vector embedding reference should resolve");
        assertEquals("node-cloud-west", resVec.clusterNode());

        var resKv = resolver.resolve("jref://KEYVALUE:ExampleDBReferences/session_token_carlos");
        assertTrue(resKv.exists(), "KeyValue session token should resolve");

        var resTs = resolver.resolve("jref://TIMESERIES:ExampleDBReferences/iot_hub_power_01");
        assertTrue(resTs.exists(), "TimeSeries IoT power reading should resolve");

        // 7. Expand master entity references
        byte[] orderBytes = engine.getStorageCore().get("doc:ExampleDBReferences:order_master_7001");
        assertNotNull(orderBytes);
        io.jettra.json.JettraJson jsonParser = new io.jettra.json.JettraJson();
        JsonObject orderObj = jsonParser.fromJson(new String(orderBytes, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);

        JsonObject expanded = resolver.expandReferences(orderObj, 3);
        assertNotNull(expanded);
        assertTrue(expanded.has("customerRef"));
        assertTrue(expanded.get("customerRef") instanceof JsonObject);
        JsonObject expCust = (JsonObject) expanded.get("customerRef");
        assertTrue(expCust.has("_primaryAddress"));
        assertEquals("doc:ExampleDBReferences:cust_101", expCust.getAsString("_primaryAddress"));
        assertTrue(expCust.has("_resolved"));

        assertTrue(expanded.has("contractDocRef"));
        assertTrue(expanded.get("contractDocRef") instanceof JsonObject);
        JsonObject expContract = (JsonObject) expanded.get("contractDocRef");
        assertEquals("obj:ExampleDBReferences:contract_enterprise_2026.pdf", expContract.getAsString("_primaryAddress"));
    }

    @Test
    void testStoreEnginesPageHtmlRendering() throws Exception {
        com.jettra.store.engine.samples.SampleDatasetManager sampleManager = new com.jettra.store.engine.samples.SampleDatasetManager(engine);
        sampleManager.loadAllDatasets();

        com.jettra.store.engine.web.StoreEnginesPage page = new com.jettra.store.engine.web.StoreEnginesPage(engine);
        // Test with hr_enterprise_db
        io.jettra.flux.core.Widget ui = page.buildContent(null, java.util.Map.of("engine", "DOCUMENT", "target_db", "hr_enterprise_db"), "dark");
        String html = ui.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertNotNull(html);

        java.nio.file.Path scratch = java.nio.file.Path.of("/home/avbravo/.gemini/antigravity-ide/brain/69588fd9-77bc-4b16-9c63-b3fdd69ad9b8/scratch/rendered_page.html");
        java.nio.file.Files.createDirectories(scratch.getParent());
        java.nio.file.Files.writeString(scratch, html);

        String[] lines = html.split("\n");
        System.out.println("TOTAL HTML LINES: " + lines.length);

        // Core Structure Modals
        assertTrue(html.contains("id=\"createDbModal\""), "createDbModal must exist in HTML");
        assertTrue(html.contains("id=\"createUnitModal\""), "createUnitModal must exist in HTML");

        // Add Modals for all 9 Engines
        assertTrue(html.contains("id=\"addDocumentModal\""), "addDocumentModal must exist in HTML");
        assertTrue(html.contains("id=\"addKeyValueModal\""), "addKeyValueModal must exist in HTML");
        assertTrue(html.contains("id=\"addVectorModal\""), "addVectorModal must exist in HTML");
        assertTrue(html.contains("id=\"addGraphModal\""), "addGraphModal must exist in HTML");
        assertTrue(html.contains("id=\"addTimeSeriesModal\""), "addTimeSeriesModal must exist in HTML");
        assertTrue(html.contains("id=\"addColumnModal\""), "addColumnModal must exist in HTML");
        assertTrue(html.contains("id=\"addGeoModal\""), "addGeoModal must exist in HTML");
        assertTrue(html.contains("id=\"addObjectModal\""), "addObjectModal must exist in HTML");
        assertTrue(html.contains("id=\"addRecordsModal\""), "addRecordsModal must exist in HTML");

        // Edit Modals for all 9 Engines
        assertTrue(html.contains("id=\"editDocumentModal\""), "editDocumentModal must exist in HTML");
        assertTrue(html.contains("id=\"editKeyValueModal\""), "editKeyValueModal must exist in HTML");
        assertTrue(html.contains("id=\"editVectorModal\""), "editVectorModal must exist in HTML");
        assertTrue(html.contains("id=\"editGraphModal\""), "editGraphModal must exist in HTML");
        assertTrue(html.contains("id=\"editTimeSeriesModal\""), "editTimeSeriesModal must exist in HTML");
        assertTrue(html.contains("id=\"editColumnModal\""), "editColumnModal must exist in HTML");
        assertTrue(html.contains("id=\"editGeoModal\""), "editGeoModal must exist in HTML");
        assertTrue(html.contains("id=\"editObjectModal\""), "editObjectModal must exist in HTML");
        assertTrue(html.contains("id=\"editRecordsModal\""), "editRecordsModal must exist in HTML");

        // Versioning, Deletion, and Inspect Modals
        assertTrue(html.contains("id=\"universalRestoreModal\""), "universalRestoreModal must exist in HTML");
        assertTrue(html.contains("id=\"confirmRestoreModal\""), "confirmRestoreModal must exist in HTML");
        assertTrue(html.contains("id=\"confirmDeleteModal\""), "confirmDeleteModal must exist in HTML");
        assertTrue(html.contains("id=\"inspectRecordModal\""), "inspectRecordModal must exist in HTML");
        assertTrue(html.contains("id=\"referenceWarningModal\""), "referenceWarningModal must exist in HTML");

        // Advanced Search & Utility Modals
        assertTrue(html.contains("id=\"advancedSearchModal\""), "advancedSearchModal must exist in HTML");
        assertTrue(html.contains("id=\"advSearchHelpModal\""), "advSearchHelpModal must exist in HTML");
        assertTrue(html.contains("id=\"backupDbModal\""), "backupDbModal must exist in HTML");
        assertTrue(html.contains("id=\"restoreDbModal\""), "restoreDbModal must exist in HTML");
        assertTrue(html.contains("id=\"confirmDbRestoreModal\""), "confirmDbRestoreModal must exist in HTML");
        assertTrue(html.contains("id=\"exportDataModal\""), "exportDataModal must exist in HTML");
        assertTrue(html.contains("id=\"createIndexModal\""), "createIndexModal must exist in HTML");
        assertTrue(html.contains("id=\"createSchemaModal\""), "createSchemaModal must exist in HTML");

        // JavaScript Functions
        assertTrue(html.contains("<script>"), "Page must contain JavaScript block");
        assertTrue(html.contains("openUniversalEditModal"), "JS must contain openUniversalEditModal");
        assertTrue(html.contains("openUniversalRestoreModal"), "JS must contain openUniversalRestoreModal");
        assertTrue(html.contains("openUniversalDeleteModal"), "JS must contain openUniversalDeleteModal");
        assertTrue(html.contains("openAddObjectModal"), "JS must contain openAddObjectModal");
        assertTrue(html.contains("openAddUnitModal"), "JS must contain openAddUnitModal");
        assertTrue(html.contains("openAddIndexModal"), "JS must contain openAddIndexModal");
        assertTrue(html.contains("openAddSchemaModal"), "JS must contain openAddSchemaModal");
        assertTrue(html.contains("openInspectRecordModal"), "JS must contain openInspectRecordModal");
        assertTrue(html.contains("showModal"), "JS must contain showModal");
        assertTrue(html.contains("hideModal"), "JS must contain hideModal");
    }
}
