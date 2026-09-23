package com.jettra.store.engine.core.storage;

import com.jettra.store.engine.core.storage.compression.AdaptiveRecordCompressionStrategy;
import com.jettra.store.engine.core.storage.compression.DeflateRecordCompressionStrategy;
import com.jettra.store.engine.core.storage.compression.NoOpRecordCompressionStrategy;
import com.jettra.store.engine.core.storage.compression.RecordCompressionStrategy;
import io.jettra.ee.serialization.CompactBinaryHeader;
import io.jettra.ee.serialization.JettraSerialization;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the Strategy Pattern implementations for Record Compression in JettraDB:
 * DeflateRecordCompressionStrategy, AdaptiveRecordCompressionStrategy, and NoOpRecordCompressionStrategy,
 * testing disk space reduction, payload recovery, and formatVersion 2 binary handling.
 */
@NotRequiresRunningServer
public class RecordCompressionStrategyTest {

    @JettraTest
    @DisplayName("1. DeflateRecordCompressionStrategy reduces JSON size by > 60% with lossless recovery")
    void testDeflateCompressionAndLosslessDecompression() {
        DeflateRecordCompressionStrategy deflateStrategy = new DeflateRecordCompressionStrategy();
        assertEquals(RecordCompressionStrategy.ALGO_DEFLATE, deflateStrategy.getAlgorithmId());
        assertEquals("Deflate (ZLIB)", deflateStrategy.getAlgorithmName());

        // Realistic database JSON record with nested fields (like an invoice from ExampleFactura)
        StringBuilder sb = new StringBuilder();
        sb.append("{\"facturaId\":\"FAC-2026-990812\",\"cliente\":{\"id\":\"cli_9012\",\"nombre\":\"Corporacion Logistica SA\",");
        sb.append("\"ruc\":\"155209-1-98711\",\"direccion\":\"Parque Industrial, Edif 4, Piso 3\"},\"items\":[");
        for (int i = 1; i <= 20; i++) {
            if (i > 1) sb.append(",");
            sb.append("{\"linea\":").append(i).append(",\"sku\":\"PROD-").append(i)
              .append("\",\"descripcion\":\"Item de prueba para almacenamiento comprimido \").append(i)")
              .append(",\"cantidad\":").append(i * 2).append(",\"precioUnitario\":").append(19.99 * i).append("}");
        }
        sb.append("],\"total\":4980.50,\"estado\":\"EMITIDA\"}");

        String rawJson = sb.toString();
        byte[] rawBytes = rawJson.getBytes(StandardCharsets.UTF_8);
        assertTrue(rawBytes.length > 500, "Raw payload should be > 500 bytes for realistic test");

        byte[] compressed = deflateStrategy.compress(rawBytes);
        assertNotNull(compressed);
        assertTrue(compressed.length < rawBytes.length * 0.40, 
            "Compressed size (" + compressed.length + ") should be less than 40% of original (" + rawBytes.length + ")");

        // Decompress and verify lossless integrity
        byte[] restored = deflateStrategy.decompress(compressed, rawBytes.length);
        assertNotNull(restored);
        assertEquals(rawBytes.length, restored.length);
        assertEquals(rawJson, new String(restored, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("2. AdaptiveRecordCompressionStrategy evaluates threshold and skips incompressible data")
    void testAdaptiveCompressionThreshold() {
        AdaptiveRecordCompressionStrategy adaptive = new AdaptiveRecordCompressionStrategy();
        assertEquals(RecordCompressionStrategy.ALGO_ADAPTIVE, adaptive.getAlgorithmId());

        // Small payload below threshold (e.g. 20 bytes)
        byte[] smallPayload = "{\"id\":\"small_1\"}".getBytes(StandardCharsets.UTF_8);
        assertFalse(adaptive.shouldCompress(smallPayload), "Small payload should not be compressed");

        byte[] resultSmall = adaptive.compress(smallPayload);
        assertEquals(smallPayload.length, resultSmall.length);
        assertEquals(new String(smallPayload, StandardCharsets.UTF_8), new String(resultSmall, StandardCharsets.UTF_8));

        // Structured multi-field JSON exceeding threshold with high compressibility
        String json = "{\"sensor\":\"IOT-992\",\"metric\":\"temperature_reading_celsius\",\"value\":24.85,\"unit\":\"C\",\"status\":\"NOMINAL\",\"alert\":false,\"history\":[{\"t\":1,\"val\":24.8},{\"t\":2,\"val\":24.9},{\"t\":3,\"val\":24.85}],\"location\":{\"datacenter\":\"DC-PANAMA-EAST\",\"rack\":\"RACK-42\",\"slot\":\"A1\"}}";
        byte[] largePayload = json.getBytes(StandardCharsets.UTF_8);
        assertTrue(adaptive.shouldCompress(largePayload));

        byte[] resultLarge = adaptive.compress(largePayload);
        assertTrue(resultLarge.length < largePayload.length, "Adaptive compression should reduce disk footprint");
        byte[] restoredLarge = adaptive.decompress(resultLarge, largePayload.length);
        assertEquals(json, new String(restoredLarge, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("3. NoOpRecordCompressionStrategy provides neutral pass-through")
    void testNoOpCompressionStrategy() {
        NoOpRecordCompressionStrategy noop = new NoOpRecordCompressionStrategy();
        assertEquals(RecordCompressionStrategy.ALGO_NONE, noop.getAlgorithmId());
        assertEquals("None (Uncompressed)", noop.getAlgorithmName());
        assertFalse(noop.shouldCompress(new byte[100]));

        byte[] data = "Sample Data String".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = noop.compress(data);
        assertEquals(data.length, compressed.length);

        byte[] restored = noop.decompress(compressed, data.length);
        assertEquals(new String(data, StandardCharsets.UTF_8), new String(restored, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("4. Binary Header and formatVersion 2 roundtrip with StorageRecordFile")
    void testFormatVersion2BinaryRoundtrip() {
        String recordId = "fac_78901";
        int version = 2;
        long timestamp = 1774500000000L;
        String jsonInvoice = "{\"factura\":\"FAC-78901\",\"items\":[{\"sku\":\"ITEM-1\",\"qty\":10},{\"sku\":\"ITEM-2\",\"qty\":20}],\"total\":1500.00}";
        byte[] payload = jsonInvoice.getBytes(StandardCharsets.UTF_8);

        // Explicitly compress via JettraSerialization format 2
        byte[] serializedCompressed = JettraSerialization.serializeRecordCompressed(recordId, version, timestamp, payload);
        assertNotNull(serializedCompressed);
        assertTrue(serializedCompressed.length < CompactBinaryHeader.HEADER_SIZE + payload.length, 
            "Serialized compressed record must be smaller than header + raw payload");

        ByteBuffer buf = ByteBuffer.wrap(serializedCompressed);
        byte[] magic = new byte[4];
        buf.get(magic);
        assertEquals((byte) 'J', magic[0]);
        assertEquals((byte) 'D', magic[1]);
        assertEquals((byte) 'A', magic[2]);
        assertEquals((byte) 'T', magic[3]);

        byte fmt = buf.get();
        assertEquals(CompactBinaryHeader.FORMAT_VERSION_COMPRESSED, fmt);

        // Deserialization check
        StorageRecordFile record = StorageRecordFile.deserialize(recordId, serializedCompressed);
        assertNotNull(record);
        assertEquals(recordId, record.recordId());
        assertEquals(version, record.version());
        assertEquals(timestamp, record.timestamp());
        assertEquals(jsonInvoice, record.payloadAsString());

        // Fast payload extraction check
        byte[] extracted = StorageRecordFile.extractPayload(serializedCompressed);
        assertNotNull(extracted);
        assertEquals(jsonInvoice, new String(extracted, StandardCharsets.UTF_8));
    }

    @JettraTest
    @DisplayName("5. StorageEngineFactory interchangeable compression configuration")
    void testStorageEngineFactoryCustomCompression() {
        DeflateRecordCompressionStrategy deflate = new DeflateRecordCompressionStrategy();
        StorageEngineFactory.setDefaultCompressionStrategy(deflate);

        RecordSerializationStrategy activeStrategy = StorageEngineFactory.getDefaultStrategy();
        assertNotNull(activeStrategy);
        assertTrue(activeStrategy.getStrategyName().contains("Deflate"));

        // Reset to default
        StorageEngineFactory.setDefaultCompressionStrategy(new AdaptiveRecordCompressionStrategy());
    }
}
