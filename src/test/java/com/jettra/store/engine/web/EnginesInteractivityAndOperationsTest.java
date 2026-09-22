package com.jettra.store.engine.web;

import com.jettra.store.engine.core.DatabaseBackupManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.operations.export.*;
import com.jettra.store.engine.test.MockHttpExchange;
import com.jettra.store.engine.test.TestDatabaseCleanup;
import com.jettra.store.engine.web.page.StoreEnginesPage;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the three core refactoring requirements on /engines:
 * 1. Tree View Interactivity: Consistent recursive Expand All and Collapse All controlling both FluxTree composite nodes and TableView rows.
 * 2. External Database Restore: Local disk file selection with FileUpload component and server staging.
 * 3. Dynamic Export Formatting: Strategy Pattern implementation for dynamic format selection (JSON, CSV, Excel).
 */
@NotRequiresRunningServer
public class EnginesInteractivityAndOperationsTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage enginesPage;
    private final JettraJson jsonParser = new JettraJson();

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_engines_operations_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        // Seed test records
        String db = "sales_db";
        engine.getStorageCore().put("doc:" + db + ":orders:ord_1",
                "{\"item\":\"Laptop\",\"amount\":1200}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:" + db + ":orders:ord_2",
                "{\"item\":\"Mouse\",\"amount\":25}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("kv:" + db + ":active_token",
                "auth_token_xyz_123".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        enginesPage = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. Tree View Interactivity: Expand All and Collapse All control FluxTree composite groups, icons and panels")
    void testTreeViewExpandAndCollapseComprehensiveInteractivity() {
        Widget scriptWidget = enginesPage.buildModalsScript();
        String script = scriptWidget.render(Themes.FlatTheme());

        // Function definitions
        assertTrue(script.contains("function expandAllExplorerView()"),
                "Script must define expandAllExplorerView()");
        assertTrue(script.contains("function collapseAllExplorerView()"),
                "Script must define collapseAllExplorerView()");

        // FluxTree composite invocations
        assertTrue(script.contains("window.FluxTree.expandAll('storage-hierarchy-tree')"),
                "expandAllExplorerView must invoke window.FluxTree.expandAll");
        assertTrue(script.contains("window.FluxTree.collapseAll('storage-hierarchy-tree', false)"),
                "collapseAllExplorerView must invoke window.FluxTree.collapseAll");

        // Comprehensive selector targeting
        assertTrue(script.contains(".flux-tree-group"),
                "Script must target .flux-tree-group for composite branches");
        assertTrue(script.contains(".flux-tree-details-panel"),
                "Script must target .flux-tree-details-panel for metadata panels");
        assertTrue(script.contains(".flux-tree-toggle-icon"),
                "Script must toggle .flux-tree-toggle-icon classes");
        assertTrue(script.contains("expandAllTableRows"),
                "expandAllExplorerView must synchronize with Table View rows");
        assertTrue(script.contains("collapseAllTableRows"),
                "collapseAllExplorerView must synchronize with Table View rows");
    }

    @JettraTest
    @DisplayName("2. Database Restore: UI renders FileUpload component for external local disk backup archive")
    void testRestoreModalRendersLocalFileUploadComponent() {
        Widget content = enginesPage.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", "sales_db"
        ), "dark");

        String html = content.render(Themes.FlatTheme());

        // Restore modal presence
        assertTrue(html.contains("restoreDbModal"), "Engines page must contain restoreDbModal");
        assertTrue(html.contains("externalBackupFileInput"),
                "Restore modal must contain FileUpload component with id 'externalBackupFileInput'");
        assertTrue(html.contains("accept=\".zip\"") || html.contains("accept=\".zip,application/zip\""),
                "FileUpload must restrict selection to .zip archives");
        assertTrue(html.contains("onExternalBackupFileSelected"),
                "FileUpload must wire onExternalBackupFileSelected listener");
        assertTrue(html.contains("externalFileStatusBadge"),
                "Restore modal must include status badge for external upload feedback");
    }

    @JettraTest
    @DisplayName("3. Database Restore: AJAX upload_restore_file endpoint stages external backup archive onto disk")
    void testUploadRestoreFileEndpointStagesArchive() throws IOException {
        String dbName = "sales_db";

        // Create a dummy valid zip archive in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("database_dump.json");
            zos.putNextEntry(entry);
            String manifest = "{\"database\":\"sales_db\",\"keys\":{}}";
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] zipBytes = baos.toByteArray();
        String zipBase64 = Base64.getEncoder().encodeToString(zipBytes);

        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestMethod("POST");
        exchange.setRequestURI(URI.create("/engines"));
        Map<String, String> params = new HashMap<>();
        params.put("action", "upload_restore_file");
        params.put("target_db", dbName);
        params.put("file_name", "external_sales_backup.zip");
        params.put("file_base64", zipBase64);

        enginesPage.handleAjaxPost(exchange, params);

        assertEquals(200, exchange.getResponseCode(), "upload_restore_file must return HTTP 200");
        String respJson = exchange.getResponseBodyAsString();
        JsonObject resp = jsonParser.fromJson(respJson, JsonObject.class);

        assertEquals("SUCCESS", resp.getAsString("status"), "Response status must be SUCCESS");
        assertTrue(resp.has("filePath"), "Response must contain filePath of staged archive");
        String filePath = resp.getAsString("filePath");

        File stagedFile = new File(filePath);
        assertTrue(stagedFile.exists(), "Staged backup file must physically exist on disk");
        assertEquals(zipBytes.length, stagedFile.length(), "Staged file length must match uploaded bytes");

        // Clean up staged file
        stagedFile.delete();
    }

    @JettraTest
    @DisplayName("4. Dynamic Export Formatting: Strategy Pattern exports JSON, CSV and Excel formats")
    void testExportStrategyPatternImplementations() {
        Map<String, String> records = new LinkedHashMap<>();
        records.put("doc:sales_db:orders:ord_1", "{\"item\":\"Laptop\",\"amount\":1200}");
        records.put("doc:sales_db:orders:ord_2", "{\"item\":\"Mouse\",\"amount\":25}");

        // 1. JSON Strategy
        ExportStrategy jsonStrategy = ExportStrategyRegistry.getStrategy("json");
        assertNotNull(jsonStrategy, "JSON Strategy must be registered");
        assertEquals("application/json; charset=UTF-8", jsonStrategy.mimeType());
        assertEquals("json", jsonStrategy.fileExtension());
        byte[] jsonBytes = jsonStrategy.export("sales_db", "DOCUMENT", "orders", records);
        String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
        assertTrue(jsonStr.contains("_database"), "JSON export must contain _database header");
        assertTrue(jsonStr.contains("Laptop"), "JSON export must contain record payload");

        // 2. CSV Strategy
        ExportStrategy csvStrategy = ExportStrategyRegistry.getStrategy("csv");
        assertNotNull(csvStrategy, "CSV Strategy must be registered");
        assertEquals("text/csv; charset=UTF-8", csvStrategy.mimeType());
        assertEquals("csv", csvStrategy.fileExtension());
        byte[] csvBytes = csvStrategy.export("sales_db", "DOCUMENT", "orders", records);
        String csvStr = new String(csvBytes, StandardCharsets.UTF_8);
        assertTrue(csvStr.contains("Key,Database,Collection_Unit,ID,Payload"), "CSV must include standard header");
        assertTrue(csvStr.contains("\"sales_db\""), "CSV must include database column");
        assertTrue(csvStr.contains("Laptop"), "CSV must include record data");

        // 3. Excel Strategy
        ExportStrategy excelStrategy = ExportStrategyRegistry.getStrategy("excel");
        assertNotNull(excelStrategy, "Excel Strategy must be registered");
        assertEquals("application/vnd.ms-excel; charset=UTF-8", excelStrategy.mimeType());
        assertEquals("xls", excelStrategy.fileExtension());
        byte[] excelBytes = excelStrategy.export("sales_db", "DOCUMENT", "orders", records);
        String excelStr = new String(excelBytes, StandardCharsets.UTF_8);
        assertTrue(excelStr.contains("xmlns:x=\"urn:schemas-microsoft-com:office:excel\""),
                "Excel export must contain Excel XML namespace");
        assertTrue(excelStr.contains("<th>Storage Key</th>"), "Excel export must contain table headers");
        assertTrue(excelStr.contains("Laptop"), "Excel export must contain record payload");
    }

    @JettraTest
    @DisplayName("5. Dynamic Export Formatting: Modal renders dynamic strategy formats and downloadExportFile script")
    void testExportModalRendersDynamicFormatsAndDownloadHandler() {
        Widget content = enginesPage.buildContent(null, Map.of(
                "engine", "DOCUMENT",
                "target_db", "sales_db"
        ), "dark");

        String html = content.render(Themes.FlatTheme());

        // Check format dropdown options from registry
        assertTrue(html.contains("exportFormatSelect"), "Export modal must contain exportFormatSelect dropdown");
        assertTrue(html.contains("value='json'") || html.contains("value=\"json\""), "Export dropdown must contain json option");
        assertTrue(html.contains("value='csv'") || html.contains("value=\"csv\""), "Export dropdown must contain csv option");
        assertTrue(html.contains("value='excel'") || html.contains("value=\"excel\""), "Export dropdown must contain excel option");

        // Check download action
        assertTrue(html.contains("downloadExportFile()"),
                "Download Export File button must invoke dynamic downloadExportFile() function");

        Widget scriptWidget = enginesPage.buildModalsScript();
        String script = scriptWidget.render(Themes.FlatTheme());
        assertTrue(script.contains("function downloadExportFile()"),
                "Client script must define downloadExportFile() handler");
        assertTrue(script.contains("action=export_data"),
                "downloadExportFile must dispatch action=export_data with selected format");
    }

    @JettraTest
    @DisplayName("6. Database Restore: Error handling returns structured JSON with HTTP 200 (preventing HTTP 500)")
    void testDatabaseRestoreErrorReturnsStructuredJsonWithout500() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestMethod("POST");
        exchange.setRequestURI(URI.create("/engines"));
        Map<String, String> params = new HashMap<>();
        params.put("action", "restore_database");
        params.put("target_db", "sales_db");
        params.put("restore_file_path", "/non/existent/path/backup.zip");

        enginesPage.handleAjaxPost(exchange, params);

        assertEquals(200, exchange.getResponseCode(),
                "restore_database must return HTTP 200 with structured JSON even on failure");
        String respJson = exchange.getResponseBodyAsString();
        JsonObject resp = jsonParser.fromJson(respJson, JsonObject.class);
        assertEquals("FAILURE", resp.getAsString("status"),
                "Failed restore must return status 'FAILURE'");
        assertTrue(resp.has("message"), "Response must contain explicit failure message");
        assertTrue(resp.getAsString("message").contains("not found"),
                "Failure message should indicate file not found");
    }

    @JettraTest
    @DisplayName("7. Database Restore: Full End-to-End backup and restore flow")
    void testDatabaseRestoreSuccessFlow() throws IOException {
        String dbName = "restore_test_db";
        engine.getStorageCore().put("doc:" + dbName + ":users:u1",
                "{\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // 1. Create backup
        DatabaseBackupManager.BackupOperationResult backupRes = DatabaseBackupManager.createDatabaseBackup(
                engine, dbName, tempDir.toString(), "test_restore_archive.zip"
        );
        assertTrue(backupRes.success(), "Backup creation must succeed");

        // 2. Clear record
        engine.getStorageCore().delete("doc:" + dbName + ":users:u1", System.currentTimeMillis());
        assertNull(engine.getStorageCore().get("doc:" + dbName + ":users:u1"), "Record must be deleted before restore");

        // 3. Restore via AJAX endpoint
        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestMethod("POST");
        exchange.setRequestURI(URI.create("/engines"));
        Map<String, String> params = new HashMap<>();
        params.put("action", "restore_database");
        params.put("target_db", dbName);
        params.put("restore_file_path", backupRes.filePath());

        enginesPage.handleAjaxPost(exchange, params);

        assertEquals(200, exchange.getResponseCode(), "Restore endpoint must return HTTP 200");
        JsonObject resp = jsonParser.fromJson(exchange.getResponseBodyAsString(), JsonObject.class);
        assertEquals("SUCCESS", resp.getAsString("status"), "Restore must succeed");

        // 4. Verify record was restored
        byte[] restoredBytes = engine.getStorageCore().get("doc:" + dbName + ":users:u1");
        assertNotNull(restoredBytes, "Record must exist in storage core after restore");
        assertTrue(new String(restoredBytes, StandardCharsets.UTF_8).contains("Alice"), "Restored record payload must match");
    }

    @JettraTest
    @DisplayName("8. Dynamic Export: Selective filtering strictly respects engine_type and target_coll")
    void testSelectiveExportFilteringByEngineAndCollection() throws IOException {
        String db = "sales_db";
        // sales_db has:
        // doc:sales_db:orders:ord_1
        // doc:sales_db:orders:ord_2
        // kv:sales_db:active_token

        // 1. Export strictly DOCUMENT engine
        MockHttpExchange docExchange = new MockHttpExchange();
        docExchange.setRequestMethod("POST");
        docExchange.setRequestURI(URI.create("/engines"));
        Map<String, String> docParams = new HashMap<>();
        docParams.put("action", "export_data");
        docParams.put("target_db", db);
        docParams.put("engine_type", "DOCUMENT");
        docParams.put("target_coll", "orders");
        docParams.put("format", "json");

        enginesPage.handleAjaxPost(docExchange, docParams);

        assertEquals(200, docExchange.getResponseCode());
        JsonObject docResp = jsonParser.fromJson(docExchange.getResponseBodyAsString(), JsonObject.class);
        assertEquals("SUCCESS", docResp.getAsString("status"));
        assertEquals(2, docResp.getAsInt("recordCount"),
                "DOCUMENT orders export must contain exactly the 2 document orders");

        // 2. Export strictly KEYVALUE engine
        MockHttpExchange kvExchange = new MockHttpExchange();
        kvExchange.setRequestMethod("POST");
        kvExchange.setRequestURI(URI.create("/engines"));
        Map<String, String> kvParams = new HashMap<>();
        kvParams.put("action", "export_data");
        kvParams.put("target_db", db);
        kvParams.put("engine_type", "KEYVALUE");
        kvParams.put("target_coll", "");
        kvParams.put("format", "json");

        enginesPage.handleAjaxPost(kvExchange, kvParams);

        assertEquals(200, kvExchange.getResponseCode());
        JsonObject kvResp = jsonParser.fromJson(kvExchange.getResponseBodyAsString(), JsonObject.class);
        assertEquals("SUCCESS", kvResp.getAsString("status"));
        assertEquals(1, kvResp.getAsInt("recordCount"),
                "KEYVALUE export must contain exactly the 1 key-value token, excluding document orders");
    }

    @JettraTest
    @DisplayName("9. Tree View Interactivity: Window bridging and performDirectDbRestore defined")
    void testTreeViewWindowBridgingAndDirectRestore() {
        Widget scriptWidget = enginesPage.buildModalsScript();
        String script = scriptWidget.render(Themes.FlatTheme());

        assertTrue(script.contains("window.expandAllExplorerView = expandAllExplorerView"),
                "Script must bridge expandAllExplorerView to window");
        assertTrue(script.contains("window.collapseAllExplorerView = collapseAllExplorerView"),
                "Script must bridge collapseAllExplorerView to window");
        assertTrue(script.contains("window.performDirectDbRestore = performDirectDbRestore"),
                "Script must bridge performDirectDbRestore to window");
        assertTrue(script.contains("btnPerformRestore"),
                "Restore modal button must have id 'btnPerformRestore'");
    }
}
