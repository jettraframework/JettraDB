package com.jettra.store.engine.operations;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.test.TestDatabaseCleanup;
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

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the core engine operations service and builder pattern for BACKUP, RESTORE, and EXPORT.
 * Tests Java 25 Record builder configuration, snapshot zip archive creation, restoration integrity,
 * and multi-format export (JSON, CSV, Excel/TSV).
 */
@NotRequiresRunningServer
public class EngineOperationsServiceTest {

    private Path tempDir;
    private Path backupDir;
    private JettraStorageEngine engine;
    private EngineOperationService operationService;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_ops_test");
        backupDir = Files.createTempDirectory("jettra_ops_backup");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        // Seed data in test database
        String db = "sales_archive_db";
        engine.getStorageCore().put("doc:" + db + ":invoices:inv_101",
                "{\"number\":\"INV-101\",\"amount\":1500.50,\"customer\":\"Acme Corp\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:" + db + ":invoices:inv_102",
                "{\"number\":\"INV-102\",\"amount\":840.00,\"customer\":\"Globex\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("kv:" + db + ":rate_limit",
                "1000_req_per_min".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        operationService = new EngineOperationService(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
        if (backupDir != null && Files.exists(backupDir)) {
            Files.walk(backupDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @JettraTest
    @DisplayName("1. Builder Pattern: Encapsulates task configuration for BACKUP, RESTORE, and EXPORT")
    void testBuilderPatternConfiguration() {
        EngineOperationTask backupTask = EngineOperationTask.backup("sales_archive_db")
                .destinationDirectory(backupDir.toString())
                .fileName("snapshot_sales.zip")
                .requestedBy("admin_user")
                .build();

        assertEquals(EngineOperationType.BACKUP, backupTask.type());
        assertEquals("sales_archive_db", backupTask.database());
        assertEquals(backupDir.toString(), backupTask.destinationDirectory());
        assertEquals("snapshot_sales.zip", backupTask.fileName());

        EngineOperationTask restoreTask = EngineOperationTask.restore("sales_archive_db", "/tmp/snapshot_sales.zip")
                .requestedBy("admin_user")
                .build();

        assertEquals(EngineOperationType.RESTORE, restoreTask.type());
        assertEquals("/tmp/snapshot_sales.zip", restoreTask.sourceFilePath());

        EngineOperationTask exportTask = EngineOperationTask.export("sales_archive_db", "csv")
                .engineType("DOCUMENT")
                .collection("invoices")
                .requestedBy("analyst")
                .build();

        assertEquals(EngineOperationType.EXPORT, exportTask.type());
        assertEquals("csv", exportTask.exportFormat());
        assertEquals("DOCUMENT", exportTask.engineType());
        assertEquals("invoices", exportTask.collection());
    }

    @JettraTest
    @DisplayName("2. Backup Operation: Generates compressed .zip snapshot containing database records")
    void testBackupOperationCreatesZipSnapshot() {
        String filename = "backup_test_" + System.currentTimeMillis() + ".zip";
        EngineOperationTask backupTask = EngineOperationTask.backup("sales_archive_db")
                .destinationDirectory(backupDir.toString())
                .fileName(filename)
                .build();

        EngineOperationResult result = operationService.execute(backupTask);

        assertTrue(result.success(), "Backup operation must succeed: " + result.message());
        assertTrue(result.recordCount() >= 3, "Backup must include at least 3 records (2 docs + 1 kv)");
        assertTrue(result.sizeBytes() > 0, "Backup file size must be greater than 0");
        assertNotNull(result.outputFilePath(), "Output file path must not be null");

        File zipFile = new File(result.outputFilePath());
        assertTrue(zipFile.exists(), "Snapshot .zip archive file must exist on disk");
        assertTrue(zipFile.length() > 0, "Snapshot archive file must not be empty");
    }

    @JettraTest
    @DisplayName("3. Restore Operation: Restores records from snapshot .zip archive into storage engine")
    void testRestoreOperationRestoresData() {
        // Step 1: Create backup
        String filename = "restore_source_" + System.currentTimeMillis() + ".zip";
        EngineOperationTask backupTask = EngineOperationTask.backup("sales_archive_db")
                .destinationDirectory(backupDir.toString())
                .fileName(filename)
                .build();
        EngineOperationResult backupResult = operationService.execute(backupTask);
        assertTrue(backupResult.success());

        // Step 2: Delete one of the keys from storage
        engine.getStorageCore().delete("doc:sales_archive_db:invoices:inv_101", System.currentTimeMillis());
        assertNull(engine.getStorageCore().get("doc:sales_archive_db:invoices:inv_101"), "Key must be deleted before restore");

        // Step 3: Execute restore
        EngineOperationTask restoreTask = EngineOperationTask.restore("sales_archive_db", backupResult.outputFilePath())
                .build();
        EngineOperationResult restoreResult = operationService.execute(restoreTask);

        assertTrue(restoreResult.success(), "Restore operation must succeed: " + restoreResult.message());
        assertTrue(restoreResult.recordCount() >= 3, "Restored record count must match or exceed 3");

        // Step 4: Verify key is recovered
        byte[] restoredBytes = engine.getStorageCore().get("doc:sales_archive_db:invoices:inv_101");
        assertNotNull(restoredBytes, "Restored record must be recovered in storage");
        String restoredJson = new String(restoredBytes, StandardCharsets.UTF_8);
        assertTrue(restoredJson.contains("INV-101"), "Restored data must contain original payload");
    }

    @JettraTest
    @DisplayName("4. Export Operation: Formats data into JSON, CSV, and Excel (TSV) outputs")
    void testExportOperationAcrossFormats() {
        // Test JSON Export
        EngineOperationTask jsonTask = EngineOperationTask.export("sales_archive_db", "json")
                .engineType("DOCUMENT")
                .collection("invoices")
                .build();
        EngineOperationResult jsonResult = operationService.execute(jsonTask);

        assertTrue(jsonResult.success(), "JSON export must succeed");
        assertTrue(jsonResult.contentType().contains("application/json"), "JSON content type must contain application/json");
        String jsonPayload = new String(jsonResult.outputData(), StandardCharsets.UTF_8);
        assertTrue(jsonPayload.contains("INV-101") && jsonPayload.contains("INV-102"),
                "JSON export must contain exported documents");

        // Test CSV Export
        EngineOperationTask csvTask = EngineOperationTask.export("sales_archive_db", "csv")
                .engineType("DOCUMENT")
                .collection("invoices")
                .build();
        EngineOperationResult csvResult = operationService.execute(csvTask);

        assertTrue(csvResult.success(), "CSV export must succeed");
        assertTrue(csvResult.contentType().contains("text/csv"), "CSV content type must contain text/csv");
        String csvPayload = new String(csvResult.outputData(), StandardCharsets.UTF_8);
        assertTrue(csvPayload.contains("INV-101") || csvPayload.contains("invoices"),
                "CSV export must contain comma-separated invoice data");

        // Test Excel (TSV) Export
        EngineOperationTask excelTask = EngineOperationTask.export("sales_archive_db", "excel")
                .engineType("DOCUMENT")
                .collection("invoices")
                .build();
        EngineOperationResult excelResult = operationService.execute(excelTask);

        assertTrue(excelResult.success(), "Excel export must succeed");
        assertTrue(excelResult.contentType().contains("application/vnd.ms-excel"), "Excel content type must contain application/vnd.ms-excel");
        String excelPayload = new String(excelResult.outputData(), StandardCharsets.UTF_8);
        assertTrue(excelPayload.contains("INV-101") || excelPayload.contains("invoices"),
                "Excel export must contain spreadsheet data");
    }
}
