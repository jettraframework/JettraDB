package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.LsmBTreeHybrid;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the hierarchical storage architecture and individual record persistence (.dat)
 * following: /databases/{DatabaseName}/{engineName}/{unitName}/<record_id>.dat
 * using Java 25 Virtual Threads and optimized serialization with JettraTest.
 */
@NotRequiresRunningServer
public class HierarchicalStorageEngineTest {

    private Path tempDir;
    private LsmBTreeHybrid storage;

    @BeforeEach
    void setUp() throws IOException {
        StorageEngineFactory.setDefaultStorageType(StorageEngineFactory.StorageType.INDIVIDUAL_FILE);
        tempDir = Files.createTempDirectory("jettra_hierarchical_storage_test_");
        storage = new LsmBTreeHybrid(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            storage.close();
        }
        deleteRecursively(tempDir);
        StorageEngineFactory.setDefaultStorageType(StorageEngineFactory.StorageType.SLOTTED_PAGE);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                      });
            }
        }
    }

    @JettraTest
    @DisplayName("1. Verify Hierarchical Directory Structure per Engine and Unit")
    void testHierarchicalDirectoryStructure() throws IOException {
        long now = System.currentTimeMillis();
        String db = "ExampleFactura";

        // 1. Persist RECORDS engine entity
        String empPayload = "{\"id\":\"emp_201\",\"name\":\"Alice Engineer\",\"role\":\"Staff\"}";
        storage.put("rec:" + db + ":empleado:emp_201", empPayload.getBytes(StandardCharsets.UTF_8), now);

        // 2. Persist DOCUMENT engine entity
        String groupPayload = "{\"id\":\"group_10\",\"name\":\"Cloud Infrastructure\"}";
        storage.put("doc:" + db + ":grupos:group_10", groupPayload.getBytes(StandardCharsets.UTF_8), now);

        // 3. Persist GEOSPATIAL engine entity
        String branchPayload = "{\"id\":\"sucursal_1\",\"name\":\"Plaza Central\",\"lat\":8.98,\"lon\":-79.52}";
        storage.put("geo:" + db + ":sucursales:sucursal_1", branchPayload.getBytes(StandardCharsets.UTF_8), now);

        // Verify physical file paths
        Path empFile = tempDir.resolve("databases").resolve(db).resolve("records").resolve("empleado").resolve("emp_201.dat");
        Path groupFile = tempDir.resolve("databases").resolve(db).resolve("document").resolve("grupos").resolve("group_10.dat");
        Path branchFile = tempDir.resolve("databases").resolve(db).resolve("geospatial").resolve("sucursales").resolve("sucursal_1.dat");

        assertTrue(Files.exists(empFile), "Record file must exist under databases/ExampleFactura/records/empleado/emp_201.dat");
        assertTrue(Files.exists(groupFile), "Record file must exist under databases/ExampleFactura/document/grupos/group_10.dat");
        assertTrue(Files.exists(branchFile), "Record file must exist under databases/ExampleFactura/geospatial/sucursales/sucursal_1.dat");

        // Verify content read
        byte[] readEmp = storage.get("rec:" + db + ":empleado:emp_201");
        assertNotNull(readEmp);
        assertEquals(empPayload, new String(readEmp, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("2. Verify Optimized Binary Serialization with JDAT 21-byte Header")
    void testOptimizedBinaryFileSerialization() throws IOException {
        long now = System.currentTimeMillis();
        String db = "ExampleFactura";
        String payload = "{\"sku\":\"SKU-9900\",\"price\":45.50}";

        storage.put("rec:" + db + ":empleado:emp_305", payload.getBytes(StandardCharsets.UTF_8), now);

        Path empFile = tempDir.resolve("databases").resolve(db).resolve("records").resolve("empleado").resolve("emp_305.dat");
        assertTrue(Files.exists(empFile));

        byte[] rawBytes = Files.readAllBytes(empFile);
        assertTrue(rawBytes.length >= StorageRecordFile.HEADER_SIZE, "File must include 21-byte binary header");

        ByteBuffer buf = ByteBuffer.wrap(rawBytes);
        byte[] magic = new byte[4];
        buf.get(magic);
        assertEquals((byte) 'J', magic[0]);
        assertEquals((byte) 'D', magic[1]);
        assertEquals((byte) 'A', magic[2]);
        assertEquals((byte) 'T', magic[3]);

        byte formatVersion = buf.get();
        assertEquals(StorageRecordFile.FORMAT_VERSION, formatVersion);

        int recordVersion = buf.getInt();
        assertEquals(1, recordVersion);

        long timestamp = buf.getLong();
        assertTrue(timestamp > 0);

        int payloadLength = buf.getInt();
        assertEquals(payload.getBytes(StandardCharsets.UTF_8).length, payloadLength);

        byte[] extractedPayload = new byte[payloadLength];
        buf.get(extractedPayload);
        assertEquals(payload, new String(extractedPayload, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("3. Concurrent Virtual Threads Batch Persistence Across Engines")
    void testConcurrentVirtualThreadsBatchPersistence() throws IOException {
        long now = System.currentTimeMillis();
        String db = "ExampleFactura";

        List<Map.Entry<String, byte[]>> batch = new ArrayList<>();
        int count = 500;
        for (int i = 1; i <= count; i++) {
            String key = (i % 2 == 0)
                ? "rec:" + db + ":empleado:emp_batch_" + i
                : "doc:" + db + ":grupos:grp_batch_" + i;
            String json = "{\"id\":" + i + ",\"batchTest\":true}";
            batch.add(new AbstractMap.SimpleEntry<>(key, json.getBytes(StandardCharsets.UTF_8)));
        }

        storage.putBatch(db, batch, now);

        // Verify samples from both engines exist on disk in individual .dat files
        Path empSample = tempDir.resolve("databases").resolve(db).resolve("records").resolve("empleado").resolve("emp_batch_100.dat");
        Path grpSample = tempDir.resolve("databases").resolve(db).resolve("document").resolve("grupos").resolve("grp_batch_101.dat");

        assertTrue(Files.exists(empSample), "emp_batch_100.dat must exist");
        assertTrue(Files.exists(grpSample), "grp_batch_101.dat must exist");

        // Verify retrieval
        byte[] empData = storage.get("rec:" + db + ":empleado:emp_batch_100");
        assertNotNull(empData);
        assertTrue(new String(empData, StandardCharsets.UTF_8).contains("\"id\":100"));
    }

    @JettraTest
    @DisplayName("4. Individual Record Deletion Removes Disk File")
    void testIndividualRecordDeletion() {
        long now = System.currentTimeMillis();
        String db = "ExampleFactura";
        String key = "rec:" + db + ":empleado:emp_delete_target";

        storage.put(key, "{\"status\":\"to_delete\"}".getBytes(StandardCharsets.UTF_8), now);
        Path targetFile = tempDir.resolve("databases").resolve(db).resolve("records").resolve("empleado").resolve("emp_delete_target.dat");
        assertTrue(Files.exists(targetFile));

        // Delete record
        storage.delete(key, now + 10);
        assertNull(storage.get(key));
        assertFalse(Files.exists(targetFile), "Record file must be deleted from disk upon deletion");
    }
}
