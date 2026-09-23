package com.jettra.store.engine.core.storage;

import io.jettra.ee.serialization.CompactBinaryHeader;
import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.ee.serialization.JettraSerializedRecord;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit and Performance Tests verifying JettraSerialization from JettraEE,
 * the Strategy Pattern, and Factory Pattern for individual record persistence.
 * Validated strictly using JettraTest.
 */
@NotRequiresRunningServer
public class JettraSerializationPerformanceAndIntegrityTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_serialization_test_");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @JettraTest
    void testDirectJettraSerializationRecordRoundTrip() {
        String recordId = "doc_usr_9981";
        int version = 3;
        long timestamp = 1774000000000L;
        String jsonPayload = "{\"id\":\"doc_usr_9981\",\"name\":\"John Doe\",\"role\":\"architect\",\"active\":true}";
        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

        byte[] serialized = JettraSerialization.serializeRecord(recordId, version, timestamp, payloadBytes);
        assertNotNull(serialized);
        assertTrue(serialized.length >= CompactBinaryHeader.HEADER_SIZE + payloadBytes.length);
        assertTrue(JettraSerialization.isJettraBinary(serialized));

        // Deserialization
        JettraSerializedRecord deserialized = JettraSerialization.deserializeRecord(recordId, serialized);
        assertNotNull(deserialized);
        assertEquals(recordId, deserialized.recordId());
        assertEquals(version, deserialized.version());
        assertEquals(timestamp, deserialized.timestamp());
        assertEquals(jsonPayload, new String(deserialized.payload(), StandardCharsets.UTF_8));

        // Fast payload extraction without creating record wrapper
        byte[] extracted = JettraSerialization.extractPayload(serialized);
        assertNotNull(extracted);
        assertEquals(jsonPayload, new String(extracted, StandardCharsets.UTF_8));
    }

    @JettraTest
    void testStrategyPatternIntegration() {
        RecordSerializationStrategy strategy = new JettraEESerializationStrategy();
        assertEquals("JettraSerialization (JettraEE Native)", strategy.getStrategyName());

        String recordId = "cust_505";
        byte[] payload = "{\"balance\": 12500.50}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = strategy.serialize(recordId, 1, 123456789L, payload);

        StorageRecordFile file = strategy.deserialize(recordId, bytes);
        assertNotNull(file);
        assertEquals(recordId, file.recordId());
        assertEquals(1, file.version());
        assertEquals(123456789L, file.timestamp());
        assertEquals("{\"balance\": 12500.50}", file.payloadAsString());

        byte[] extracted = strategy.extractPayload(bytes);
        assertEquals("{\"balance\": 12500.50}", new String(extracted, StandardCharsets.UTF_8));
    }

    @JettraTest
    void testFactoryPatternAndRepositoryPersistence() throws IOException {
        StorageEngineFactory.setDefaultStrategy(new JettraEESerializationStrategy());
        StorageRecordRepository repo = StorageEngineFactory.createRepository();
        assertNotNull(repo);

        StorageRecordPath path = StoragePathBuilder.create()
                .withRoot(tempDir)
                .withDatabase("fintech_db")
                .withEngine("RECORDS")
                .withUnit("accounts")
                .withRecordId("acc_771")
                .build();
        String data = "{\"iban\":\"US123456789\",\"currency\":\"USD\"}";
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);

        repo.save(path, payload, System.currentTimeMillis(), 1);

        assertTrue(repo.exists(path));
        assertEquals(StorageRecordFile.HEADER_SIZE + payload.length, Files.size(path.filePath()));

        Optional<StorageRecordFile> retrieved = repo.find(path);
        assertTrue(retrieved.isPresent());
        assertEquals("acc_771", retrieved.get().recordId());
        assertEquals(data, retrieved.get().payloadAsString());

        byte[] extractedPayload = repo.readPayload(path);
        assertNotNull(extractedPayload);
        assertEquals(data, new String(extractedPayload, StandardCharsets.UTF_8));

        boolean deleted = repo.delete(path);
        assertTrue(deleted);
        assertFalse(repo.exists(path));
    }

    @JettraTest
    void testLegacyAndRawPayloadCompatibility() {
        String recordId = "legacy_001";
        byte[] rawPayload = "raw un-headered data stream".getBytes(StandardCharsets.UTF_8);

        StorageRecordFile record = StorageRecordFile.deserialize(recordId, rawPayload);
        assertNotNull(record);
        assertEquals(recordId, record.recordId());
        assertEquals("raw un-headered data stream", record.payloadAsString());

        byte[] extracted = StorageRecordFile.extractPayload(rawPayload);
        assertEquals("raw un-headered data stream", new String(extracted, StandardCharsets.UTF_8));
    }

    @JettraTest
    void testSerializationPerformanceThroughput() {
        int iterations = 50_000;
        String jsonTemplate = "{\"customerId\":\"c_%d\",\"amount\":%d.50,\"status\":\"CONFIRMED\"}";
        byte[][] payloads = new byte[iterations][];
        for (int i = 0; i < iterations; i++) {
            payloads[i] = String.format(jsonTemplate, i, i * 10).getBytes(StandardCharsets.UTF_8);
        }

        RecordSerializationStrategy strategy = StorageEngineFactory.getDefaultStrategy();

        long start = System.nanoTime();
        byte[][] serializedAll = new byte[iterations][];
        for (int i = 0; i < iterations; i++) {
            serializedAll[i] = strategy.serialize("c_" + i, 1, 1000L, payloads[i]);
        }
        long serializeTimeMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] p = strategy.extractPayload(serializedAll[i]);
            assertNotNull(p);
        }
        long extractTimeMs = (System.nanoTime() - start) / 1_000_000;

        // Ensure 50,000 serializations complete in less than 2500ms (typically ~50ms in Java 25)
        assertTrue(serializeTimeMs < 2500, "50,000 serializations took " + serializeTimeMs + "ms, expected < 2500ms");
        assertTrue(extractTimeMs < 2500, "50,000 extractions took " + extractTimeMs + "ms, expected < 2500ms");
    }

    @JettraTest
    void testObjectStorageJettraSerializationRoundTrip() {
        com.jettra.store.storage.ObjectStorage storage = new com.jettra.store.storage.ObjectStorage(tempDir.resolve("objects").toString());
        
        record TestCustomer(String name, int age) implements java.io.Serializable {}
        TestCustomer customer = new TestCustomer("Alice Smith", 32);

        storage.save("cust_100", customer);

        Optional<TestCustomer> found = storage.findById(TestCustomer.class, "cust_100");
        assertTrue(found.isPresent());
        assertEquals("Alice Smith", found.get().name());
        assertEquals(32, found.get().age());

        java.util.List<TestCustomer> all = storage.findAll(TestCustomer.class);
        assertEquals(1, all.size());
        assertEquals("Alice Smith", all.get(0).name());
    }
}
