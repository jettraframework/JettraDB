package com.jettra.store.engine.operations.export;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Strategy implementation for high-performance Structured Binary export (.bin).
 * Serializes multi-model keys and payloads with magic header, timestamp,
 * length-prefixed records, and CRC32 verification checksums.
 */
public final class BinaryStructuredExportStrategy implements ExportStrategy {

    private static final byte[] MAGIC = "JETTRA_BIN_V1\n".getBytes(StandardCharsets.US_ASCII);

    @Override
    public String format() {
        return "binary";
    }

    @Override
    public String displayName() {
        return "Binary Structured (.bin) - Native High-Speed Stream";
    }

    @Override
    public String mimeType() {
        return "application/octet-stream";
    }

    @Override
    public String fileExtension() {
        return "bin";
    }

    @Override
    public byte[] export(String database, String engineFilter, String collectionFilter, Map<String, String> recordsMap) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // Header
            dos.write(MAGIC);
            dos.writeUTF(database != null ? database : "default");
            dos.writeUTF(engineFilter != null ? engineFilter : "ALL");
            dos.writeUTF(collectionFilter != null ? collectionFilter : "*");
            dos.writeLong(System.currentTimeMillis());
            dos.writeInt(recordsMap != null ? recordsMap.size() : 0);

            if (recordsMap != null) {
                CRC32 crc = new CRC32();
                for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                    byte[] keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                    byte[] valBytes = entry.getValue() != null ? entry.getValue().getBytes(StandardCharsets.UTF_8) : new byte[0];

                    crc.reset();
                    crc.update(keyBytes);
                    crc.update(valBytes);

                    dos.writeInt(keyBytes.length);
                    dos.write(keyBytes);
                    dos.writeInt(valBytes.length);
                    dos.write(valBytes);
                    dos.writeLong(crc.getValue());
                }
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate binary structured export: " + e.getMessage(), e);
        }
    }
}
