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

        // 2. Resolve Records Employee reference (emp_201 & emp_202)
        var resEmp = resolver.resolve("jref://RECORDS:ExampleDBReferences/emp_201");
        assertTrue(resEmp.exists(), "Employee record 201 should resolve");
        assertEquals("rec:ExampleDBReferences:emp_201", resEmp.primaryStorageAddress());
        assertNotNull(resEmp.jsonPayload());
        assertEquals("emp_201", resEmp.jsonPayload().getAsString("id"));

        var resEmp202 = resolver.resolve("jref://RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resEmp202.exists(), "Employee record 202 should resolve without failure");
        assertEquals("rec:ExampleDBReferences:emp_202", resEmp202.primaryStorageAddress());
        assertNotNull(resEmp202.jsonPayload());
        assertEquals("emp_202", resEmp202.jsonPayload().getAsString("id"));

        // 3. Resolve Geospatial Hub reference (hub_panama & hub_colon)
        var resGeo = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_panama");
        assertTrue(resGeo.exists(), "Geospatial hub panama should resolve");
        assertEquals("geo:ExampleDBReferences:hub_panama", resGeo.primaryStorageAddress());

        var resGeoColon = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(resGeoColon.exists(), "Geospatial hub colon should resolve without failure");
        assertEquals("geo:ExampleDBReferences:hub_colon", resGeoColon.primaryStorageAddress());
        assertNotNull(resGeoColon.jsonPayload());
        assertEquals("hub_colon", resGeoColon.jsonPayload().getAsString("id"));

        // 4. Resolve multi-cluster node reference (remote cluster)
        var resRemote = resolver.resolve("jref://cluster-east-01@DOCUMENT:ExampleDBReferences/cust_101");
        assertTrue(resRemote.exists(), "Remote cluster reference should resolve with primary storage key fallback");
        assertEquals("cluster-east-01", resRemote.clusterNode());
        assertEquals("doc:ExampleDBReferences:cust_101", resRemote.primaryStorageAddress());

        var resRemoteSec = resolver.resolve("jref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resRemoteSec.exists(), "Remote secondary cluster RECORDS reference should resolve with fallback");
        assertEquals("cluster-secondary-02", resRemoteSec.clusterNode());
        assertEquals("rec:ExampleDBReferences:emp_202", resRemoteSec.primaryStorageAddress());

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

        // 7. Expand master entity references (order_master_7001 & order_master_7002)
        byte[] orderBytes = engine.getStorageCore().get("doc:ExampleDBReferences:order_master_7001");
        assertNotNull(orderBytes);
        io.jettra.json.JettraJson jsonParser = new io.jettra.json.JettraJson();
        JsonObject orderObj = jsonParser.fromJson(new String(orderBytes, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);

        JsonObject expanded = resolver.expandReferences(orderObj, 3);
        assertNotNull(expanded);
        assertTrue(expanded.has("customerRef"));
        assertTrue(expanded.get("customerRef") instanceof JsonObject);
        JsonObject expCust = (JsonObject) expanded.get("customerRef");
        assertTrue(expCust.has("$jref"), "Must contain canonical $jref");
        assertFalse(expCust.has("$ref"), "Must not contain redundant $ref");
        assertFalse(expCust.has("_primaryAddress"), "Must not leak internal _primaryAddress");
        assertTrue(expCust.has("_resolved"), "Must contain _resolved");
        assertTrue(((JsonObject) expCust.get("_resolved")).has("companyName"));

        assertTrue(expanded.has("contractDocRef"));
        assertTrue(expanded.get("contractDocRef") instanceof JsonObject);
        JsonObject expContract = (JsonObject) expanded.get("contractDocRef");
        assertTrue(expContract.has("$jref"));
        assertFalse(expContract.has("$ref"));
        assertFalse(expContract.has("_primaryAddress"));
        assertTrue(expContract.has("_resolved"));

        // Verify order_master_7002 expanded references
        byte[] order7002Bytes = engine.getStorageCore().get("doc:ExampleDBReferences:order_master_7002");
        assertNotNull(order7002Bytes, "order_master_7002 must exist in storage");
        JsonObject order7002Obj = jsonParser.fromJson(new String(order7002Bytes, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
        JsonObject exp7002 = resolver.expandReferences(order7002Obj, 3);
        assertNotNull(exp7002);
        assertTrue(exp7002.has("leadArchitectRef"));
        assertTrue(exp7002.has("fulfillmentHubRef"));
        assertTrue(exp7002.has("remoteSecondaryClusterRef"));

        // Explicit individual resolution of all order_master_7002 references
        var resLeadArch = resolver.resolve("jref://RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resLeadArch.exists(), "leadArchitectRef emp_202 must resolve");
        assertEquals("rec:ExampleDBReferences:emp_202", resLeadArch.primaryStorageAddress());
        assertNotNull(resLeadArch.jsonPayload());
        assertEquals("emp_202", resLeadArch.jsonPayload().getAsString("id"));

        var resHubColon = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(resHubColon.exists(), "fulfillmentHubRef hub_colon must resolve");
        assertEquals("geo:ExampleDBReferences:hub_colon", resHubColon.primaryStorageAddress());
        assertNotNull(resHubColon.jsonPayload());
        assertEquals("hub_colon", resHubColon.jsonPayload().getAsString("id"));

        var resClusterSec = resolver.resolve("jref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resClusterSec.exists(), "remoteSecondaryClusterRef emp_202 must resolve with cluster node routing");
        assertEquals("cluster-secondary-02", resClusterSec.clusterNode());
        assertEquals("rec:ExampleDBReferences:emp_202", resClusterSec.primaryStorageAddress());

        var resCust102 = resolver.resolve("jref://DOCUMENT:ExampleDBReferences/cust_102");
        assertTrue(resCust102.exists(), "cust_102 must resolve");
        assertEquals("doc:ExampleDBReferences:cust_102", resCust102.primaryStorageAddress());

        var resInvPdf = resolver.resolve("jref://OBJECT:ExampleDBReferences/invoice_ORD-7001.pdf");
        assertTrue(resInvPdf.exists(), "invoice_ORD-7001.pdf must resolve");
        assertEquals("obj:ExampleDBReferences:invoice_ORD-7001.pdf", resInvPdf.primaryStorageAddress());

        // 8. Test flexible scheme (jettra://, JSON, slash paths, casing)
        var resJettraScheme = resolver.resolve("jettra://DOCUMENT:exampledbreferences/cust_101");
        assertTrue(resJettraScheme.exists(), "jettra:// scheme with lowercase DB should resolve");

        var resJsonWrap = resolver.resolve("{\"$jref\": \"jref://DOCUMENT:ExampleDBReferences/cust_101\"}");
        assertTrue(resJsonWrap.exists(), "JSON wrapped $jref should resolve");

        var resQueryWrap = resolver.resolve("/engines?action=resolve_ref&uri=jref%3A%2F%2FDOCUMENT%3AExampleDBReferences%2Fcust_101");
        assertTrue(resQueryWrap.exists(), "URL query wrapped uri should resolve");

        // 9. Test auto-loading of other sample datasets (e.g. scrum_board_db, hr_enterprise_db)
        var resScrum = resolver.resolve("jref://DOCUMENT:scrum_board_db/tasks/TASK-0101");
        assertTrue(resScrum.exists(), "Scrum board task with sub-collection slash path should resolve and auto-load");

        var resHr = resolver.resolve("jref://RECORDS:hr_enterprise_db/employees/emp_201");
        assertTrue(resHr.exists(), "HR employee with table slash path should resolve and auto-load");
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
        assertTrue(html.contains("item_detail_"), "HTML must contain level 5 item detail subtrees");
    }

    @Test
    void testRecordEditVersionIncrementAndHistory() {
        String testKey = "doc:test_v_db:record_01";
        long t1 = 1000000L;
        long t2 = 2000000L;
        long t3 = 3000000L;

        engine.getStorageCore().put(testKey, "{\"v\":1,\"name\":\"alpha\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), t1);
        assertEquals(1, engine.getStorageCore().getVersionCount(testKey));

        // Simulate Edit 1
        engine.getStorageCore().put(testKey, "{\"v\":2,\"name\":\"beta\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), t2);
        assertEquals(2, engine.getStorageCore().getVersionCount(testKey));

        // Simulate Edit 2
        engine.getStorageCore().put(testKey, "{\"v\":3,\"name\":\"gamma\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), t3);
        assertEquals(3, engine.getStorageCore().getVersionCount(testKey));

        var history = engine.getStorageCore().getVersionHistory(testKey);
        assertEquals(3, history.size());
        assertEquals("v3", "v" + history.get(0).versionNumber());
        assertTrue(history.get(0).isCurrent());
        assertEquals("v2", "v" + history.get(1).versionNumber());
        assertFalse(history.get(1).isCurrent());
        assertEquals("v1", "v" + history.get(2).versionNumber());
        assertFalse(history.get(2).isCurrent());
    }

    @Test
    void testGeospatialReferenceResolution() {
        new com.jettra.store.engine.samples.SampleDatasetManager(engine).loadExampleDBReferencesDataset();
        com.jettra.store.engine.ref.JettraReferenceResolver resolver = new com.jettra.store.engine.ref.JettraReferenceResolver(engine);

        // Test 1: Standard lowercase URI
        var res1 = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(res1.exists(), "hub_colon must resolve");
        assertEquals("GEOSPATIAL", res1.reference().engine());
        assertEquals("ExampleDBReferences", res1.reference().database());
        assertNotNull(res1.jsonPayload());
        assertEquals("hub_colon", res1.jsonPayload().getAsString("id"));

        // Test 2: Mixed case URI (hub_coloN)
        var res2 = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_coloN");
        assertTrue(res2.exists(), "hub_coloN must resolve case-insensitively");
        assertNotNull(res2.jsonPayload());
        assertEquals("hub_colon", res2.jsonPayload().getAsString("id"));

        // Test 3: Alias 202
        var res3 = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/202");
        assertTrue(res3.exists(), "202 alias must resolve");

        // Test 4: Expansion in order_master_7002
        var orderEntity = resolver.resolve("jref://DOCUMENT:ExampleDBReferences/order_master_7002");
        assertTrue(orderEntity.exists(), "order_master_7002 must resolve");
        var expanded = resolver.expandReferences(orderEntity.jsonPayload(), 2);
        assertNotNull(expanded);
        assertTrue(expanded.has("fulfillmentHubRef"));
    }

    @Test
    void testEditOrderMasterVersionIncrement() {
        new com.jettra.store.engine.samples.SampleDatasetManager(engine).loadExampleDBReferencesDataset();
        com.jettra.store.engine.web.StoreEnginesPage page = new com.jettra.store.engine.web.StoreEnginesPage(engine);

        String db = "ExampleDBReferences";
        String id = "order_master_7002";
        String key = "doc:" + db + ":" + id;

        assertEquals(1, engine.getStorageCore().getVersionCount(key), "Initial version must be 1");

        // Edit 1
        java.util.Map<String, String> p1 = new java.util.HashMap<>();
        p1.put("doc_payload", "{\"orderId\":\"ORD-2026-7002\",\"totalAmount\":19500,\"status\":\"IN_TRANSIT\"}");
        engine.getStorageCore().put(key, p1.get("doc_payload").getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());
        assertEquals(2, engine.getStorageCore().getVersionCount(key), "Version after edit 1 must be exactly 2");

        // Edit 2
        java.util.Map<String, String> p2 = new java.util.HashMap<>();
        p2.put("doc_payload", "{\"orderId\":\"ORD-2026-7002\",\"totalAmount\":21000,\"status\":\"DELIVERED\"}");
        engine.getStorageCore().put(key, p2.get("doc_payload").getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());
        assertEquals(3, engine.getStorageCore().getVersionCount(key), "Version after edit 2 must be exactly 3");

        // Edit 3
        java.util.Map<String, String> p3 = new java.util.HashMap<>();
        p3.put("doc_payload", "{\"orderId\":\"ORD-2026-7002\",\"totalAmount\":21000,\"status\":\"ARCHIVED\"}");
        engine.getStorageCore().put(key, p3.get("doc_payload").getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());
        assertEquals(4, engine.getStorageCore().getVersionCount(key), "Version after edit 3 must be exactly 4");

        var history = engine.getStorageCore().getVersionHistory(key);
        assertEquals(4, history.size(), "History must contain exactly 4 versions");
        assertEquals("v4", "v" + history.get(0).versionNumber());
        assertTrue(history.get(0).isCurrent());

        // Verify other records in ExampleDBReferences were NOT affected and remain at version 1
        assertEquals(1, engine.getStorageCore().getVersionCount("doc:" + db + ":order_master_7001"), "order_master_7001 must stay at version 1");
        assertEquals(1, engine.getStorageCore().getVersionCount("doc:" + db + ":cust_101"), "cust_101 must stay at version 1");
        assertEquals(1, engine.getStorageCore().getVersionCount("doc:" + db + ":cust_102"), "cust_102 must stay at version 1");
        assertEquals(1, engine.getStorageCore().getVersionCount("rec:" + db + ":emp_201"), "emp_201 must stay at version 1");
        assertEquals(1, engine.getStorageCore().getVersionCount("rec:" + db + ":emp_202"), "emp_202 must stay at version 1");
        assertEquals(1, engine.getStorageCore().getVersionCount("geo:" + db + ":hub_colon"), "hub_colon must stay at version 1");
    }

    @Test
    void testReferenceResolutionAndDynamicClusterRouting() {
        new com.jettra.store.engine.samples.SampleDatasetManager(engine).loadExampleDBReferencesDataset();
        com.jettra.store.engine.cluster.ClusterNodeRegistry registry = com.jettra.store.engine.cluster.ClusterNodeRegistry.getInstance();
        com.jettra.store.engine.ref.JettraReferenceResolver resolver = new com.jettra.store.engine.ref.JettraReferenceResolver(engine, "primary-node", registry);

        // 1. Resolve local RECORDS employee 202 via both jref:// and ref://
        var resJrefEmp202 = resolver.resolve("jref://RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resJrefEmp202.exists(), "jref://RECORDS:ExampleDBReferences/emp_202 must resolve");
        assertEquals("rec:ExampleDBReferences:emp_202", resJrefEmp202.primaryStorageAddress());
        assertEquals("RESOLVED", resJrefEmp202.status());
        assertNotNull(resJrefEmp202.jsonPayload());
        assertEquals("emp_202", resJrefEmp202.jsonPayload().getAsString("id"));

        var resRefEmp202 = resolver.resolve("ref://RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resRefEmp202.exists(), "ref://RECORDS:ExampleDBReferences/emp_202 must resolve");
        assertEquals("rec:ExampleDBReferences:emp_202", resRefEmp202.primaryStorageAddress());
        assertEquals("RESOLVED", resRefEmp202.status());
        assertNotNull(resRefEmp202.jsonPayload());
        assertEquals("emp_202", resRefEmp202.jsonPayload().getAsString("id"));

        // 2. Resolve remote cluster references with cluster-secondary-02 via both ref:// and jref://
        var resClusterRef = resolver.resolve("ref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resClusterRef.exists(), "ref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202 must resolve");
        assertEquals("cluster-secondary-02", resClusterRef.clusterNode());
        assertEquals("rec:ExampleDBReferences:emp_202", resClusterRef.primaryStorageAddress());
        assertEquals("RESOLVED", resClusterRef.status());
        assertEquals("emp_202", resClusterRef.jsonPayload().getAsString("id"));

        var resClusterJref = resolver.resolve("jref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resClusterJref.exists(), "jref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202 must resolve");
        assertEquals("cluster-secondary-02", resClusterJref.clusterNode());
        assertEquals("rec:ExampleDBReferences:emp_202", resClusterJref.primaryStorageAddress());
        assertEquals("RESOLVED", resClusterJref.status());

        // 3. Test dynamic cluster node registration & re-targeting
        registry.registerNode(new com.jettra.store.engine.cluster.ClusterNodeRegistry.ClusterNodeInfo(
            "cluster-dynamic-cloud-99", "cluster-dynamic-cloud-99", "127.0.0.1", 50059, 50058,
            com.jettra.store.engine.cluster.ClusterNodeRegistry.NodeStatus.ACTIVE, System.currentTimeMillis(), java.util.Map.of("region", "cloud-edge")
        ));
        assertTrue(registry.isNodeRegistered("cluster-dynamic-cloud-99"));
        var resDynamicNode = resolver.resolve("ref://cluster-dynamic-cloud-99@DOCUMENT:ExampleDBReferences/cust_101");
        assertTrue(resDynamicNode.exists(), "Dynamic cluster node reference must resolve");
        assertEquals("cluster-dynamic-cloud-99", resDynamicNode.clusterNode());
        assertEquals("doc:ExampleDBReferences:cust_101", resDynamicNode.primaryStorageAddress());

        // 4. Test error handling & node unreachable status without failovers
        registry.registerNode(new com.jettra.store.engine.cluster.ClusterNodeRegistry.ClusterNodeInfo(
            "cluster-offline-isolated", "cluster-offline-isolated", "10.255.255.1", 50051, 50050,
            com.jettra.store.engine.cluster.ClusterNodeRegistry.NodeStatus.UNREACHABLE, System.currentTimeMillis(), java.util.Map.of()
        ));
        var resOffline = resolver.resolve("ref://cluster-offline-isolated@RECORDS:ExampleDBReferences/missing_record_999");
        assertFalse(resOffline.exists(), "Unreachable isolated cluster node without failover must not resolve");
        assertEquals("NODE_UNREACHABLE", resOffline.status());
        assertTrue(resOffline.diagnosticMessage().contains("unreachable"));

        // 5. Test Transparent Cluster Failover to Replica Nodes
        registry.registerNode(new com.jettra.store.engine.cluster.ClusterNodeRegistry.ClusterNodeInfo(
            "cluster-primary-down", "cluster-primary-down", "10.255.255.2", 50051, 50050,
            com.jettra.store.engine.cluster.ClusterNodeRegistry.NodeStatus.UNREACHABLE, System.currentTimeMillis(), java.util.Map.of()
        ));
        registry.registerFailover("cluster-primary-down", "node-local");
        var resFailover = resolver.resolve("ref://cluster-primary-down@RECORDS:ExampleDBReferences/emp_202");
        assertTrue(resFailover.exists(), "Reference with failing primary must resolve transparently via failover replica");
        assertEquals("RESOLVED", resFailover.status());
        assertTrue(resFailover.diagnosticMessage().toLowerCase().contains("failover"), "Diagnostic must indicate failover resolution");

        // 6. Test non-existent record status (NOT_FOUND)
        var resNotFound = resolver.resolve("ref://DOCUMENT:ExampleDBReferences/non_existent_record_xyz");
        assertFalse(resNotFound.exists(), "Missing record must not resolve");
        assertEquals("NOT_FOUND", resNotFound.status());

        // 7. Test JSON reference parsing and expansion with clean canonical $jref output
        io.jettra.json.JsonObject rootObj = new io.jettra.json.JsonObject();
        rootObj.addProperty("orderId", "ORD-TEST-999");
        rootObj.addProperty("leadEmpRef", "ref://RECORDS:ExampleDBReferences/emp_202");
        rootObj.addProperty("clusterSecRef", "ref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202");
        io.jettra.json.JsonObject nestedRefObj = new io.jettra.json.JsonObject();
        nestedRefObj.addProperty("$ref", "ref://DOCUMENT:ExampleDBReferences/cust_102");
        rootObj.add("customerDoc", nestedRefObj);

        io.jettra.json.JsonObject expandedObj = resolver.expandReferences(rootObj, 2);
        assertNotNull(expandedObj);
        assertTrue(expandedObj.get("leadEmpRef") instanceof io.jettra.json.JsonObject);
        io.jettra.json.JsonObject expLead = (io.jettra.json.JsonObject) expandedObj.get("leadEmpRef");
        assertTrue(expLead.has("$jref"), "Must have canonical $jref");
        assertFalse(expLead.has("$ref"), "Must not have redundant $ref");
        assertFalse(expLead.has("_primaryAddress"), "Must not have _primaryAddress");
        assertTrue(expLead.has("_resolved"), "Must have _resolved payload");

        assertTrue(expandedObj.get("clusterSecRef") instanceof io.jettra.json.JsonObject);
        io.jettra.json.JsonObject expCluster = (io.jettra.json.JsonObject) expandedObj.get("clusterSecRef");
        assertTrue(expCluster.has("$jref"));
        assertFalse(expCluster.has("$ref"));
        assertFalse(expCluster.has("_primaryAddress"));
        assertTrue(expCluster.has("_resolved"));

        assertTrue(expandedObj.get("customerDoc") instanceof io.jettra.json.JsonObject);
        io.jettra.json.JsonObject expCustDoc = (io.jettra.json.JsonObject) expandedObj.get("customerDoc");
        assertTrue(expCustDoc.has("$jref"));
        assertFalse(expCustDoc.has("$ref"));
        assertFalse(expCustDoc.has("_primaryAddress"));
        assertTrue(expCustDoc.has("_resolved"));

        // 7. Test GEOSPATIAL reference resolution across local and remote cluster nodes
        var resGeoColon = resolver.resolve("jref://GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(resGeoColon.exists(), "jref://GEOSPATIAL:ExampleDBReferences/hub_colon must resolve");
        assertEquals("geo:ExampleDBReferences:hub_colon", resGeoColon.primaryStorageAddress());
        assertEquals("RESOLVED", resGeoColon.status());
        assertNotNull(resGeoColon.jsonPayload());
        assertEquals("hub_colon", resGeoColon.jsonPayload().getAsString("id"));

        var resGeoColonLocal = resolver.resolve("jref://Local Cluster (Primary)@GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(resGeoColonLocal.exists(), "Local Cluster (Primary) alias must resolve without network timeout");
        assertEquals("Local Cluster (Primary)", resGeoColonLocal.clusterNode());
        assertEquals("RESOLVED", resGeoColonLocal.status());

        var resGeoRemote = resolver.resolve("ref://cluster-secondary-02@GEOSPATIAL:ExampleDBReferences/hub_colon");
        assertTrue(resGeoRemote.exists(), "Remote cluster GEOSPATIAL reference must resolve");
        assertEquals("cluster-secondary-02", resGeoRemote.clusterNode());
        assertEquals("geo:ExampleDBReferences:hub_colon", resGeoRemote.primaryStorageAddress());
        assertEquals("RESOLVED", resGeoRemote.status());

        // 8. Verify Tree View HTML rendering (default collapsed, Explore DB icon, accessibility and direct expansion)
        com.jettra.store.engine.web.StoreEnginesPage page = new com.jettra.store.engine.web.StoreEnginesPage(engine);
        io.jettra.flux.core.Widget ui = page.buildContent(null, java.util.Map.of("engine", "DOCUMENT", "target_db", "ExampleDBReferences"), "dark");
        String html = ui.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertTrue(html.contains("[Explore DB]"), "HTML must contain Explore DB link");
        assertTrue(html.contains("fa-compass"), "HTML must contain compass icon next to Explore DB");
        assertTrue(html.contains("display:none"), "Tree content must be collapsed by default with display:none");
        assertTrue(html.contains("role=\"treeitem\""), "Tree nodes must have treeitem role for direct accessible selection");
        assertTrue(html.contains("handleTreeKeyDown"), "HTML script must contain keyboard navigation handler");
        assertTrue(html.contains("renderManualReferenceCards"), "HTML script must contain manual reference list renderer");
        assertTrue(html.contains("chkInspectResolveRefs"), "Inspect modal must contain auto-resolve toggle checkbox");
        assertTrue(html.contains("id=\"db_content_1\""), "Database tree must render container for first db");
        assertTrue(html.contains("id=\"db_content_2\""), "Database tree must render direct subtree for second db");

        // 9. Verify Table View HTML rendering with Multi-Engine Aggregation, Database SelectOne and Filter Chips
        io.jettra.flux.core.Widget uiTable = page.buildContent(null, java.util.Map.of("engine", "DOCUMENT", "target_db", "ExampleDBReferences", "view_mode", "table", "table_size", "50"), "dark");
        String htmlTable = uiTable.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertTrue(htmlTable.contains("table_db_selector"), "Table view must render Database SelectOne component");
        assertTrue(htmlTable.contains("toggleTableRowDetail"), "Table view must contain row expansion toggle function");
        assertTrue(htmlTable.contains("explorer-table-detail-row"), "Table view must render expandable detail panels for rows");
        assertTrue(htmlTable.contains("tbl_row_detail_"), "Table rows must have unique detail identifiers");
        assertTrue(htmlTable.contains("engine-filter-chip"), "Table view must render multi-engine filter chips");
        assertTrue(htmlTable.contains("filterByEngineType"), "Table view must contain filterByEngineType JavaScript function");
        assertTrue(htmlTable.contains("data-engine-type=\"DOCUMENT\""), "Table rows must contain DOCUMENT engine type tag");
        assertTrue(htmlTable.contains("data-engine-type=\"RECORDS\""), "Table rows must aggregate and contain RECORDS engine type tag");
        assertTrue(htmlTable.contains("data-engine-type=\"GEOSPATIAL\""), "Table rows must aggregate and contain GEOSPATIAL engine type tag");
        assertTrue(htmlTable.contains("data-engine-target=\"RECORDS\""), "Filter chips must contain RECORDS target");
        assertTrue(htmlTable.contains("data-engine-target=\"GEOSPATIAL\""), "Filter chips must contain GEOSPATIAL target");
        assertTrue(htmlTable.contains("data-db-name=\"ExampleDBReferences\""), "Table rows must be strictly tagged with the active target database");
        assertFalse(htmlTable.contains("data-db-name=\"test_isolated_empty_db\""), "Table rows must not contain foreign database tags");
        assertTrue(htmlTable.contains("onTableDatabaseChange"), "Table view must include onTableDatabaseChange function for state reset and loading overlay");
        assertTrue(htmlTable.contains("id=\"tableExplorerContainer\""), "Table container must have tableExplorerContainer ID");

        // 10. Verify Strict Database Isolation on switching to an Empty Database
        io.jettra.flux.core.Widget uiEmpty = page.buildContent(null, java.util.Map.of("engine", "DOCUMENT", "target_db", "test_isolated_empty_db", "view_mode", "table"), "dark");
        String htmlEmpty = uiEmpty.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertTrue(htmlEmpty.contains("No engines or components found for [test_isolated_empty_db]"), "Empty state must be displayed for empty target database");
        assertTrue(htmlEmpty.contains("0 Total Records (0 Active Models)"), "Empty database must report 0 total records");
        assertFalse(htmlEmpty.contains("order_master_7001"), "Empty database must not leak entities from ExampleDBReferences");
    }
}
