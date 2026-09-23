package com.jettra.store.engine.core.storage.compression;

import io.jettra.ee.serialization.JettraSerialization;

/**
 * High-performance record compression strategy backed by Deflate algorithm (DEFLATE / BEST_COMPRESSION).
 * Ideal for JSON, structured text, and relational record payloads, reducing disk footprints by 70-85%.
 */
public class DeflateRecordCompressionStrategy implements RecordCompressionStrategy {

    private final int minThreshold;

    public DeflateRecordCompressionStrategy() {
        this(DEFAULT_MIN_COMPRESSION_THRESHOLD);
    }

    public DeflateRecordCompressionStrategy(int minThreshold) {
        this.minThreshold = minThreshold;
    }

    @Override
    public byte[] compress(byte[] raw) {
        return JettraSerialization.compressDeflate(raw);
    }

    @Override
    public byte[] decompress(byte[] compressed, int originalLength) {
        return JettraSerialization.decompressDeflate(compressed, originalLength);
    }

    @Override
    public byte getAlgorithmId() {
        return ALGO_DEFLATE;
    }

    @Override
    public String getAlgorithmName() {
        return "Deflate (ZLIB)";
    }

    @Override
    public boolean shouldCompress(byte[] raw) {
        return raw != null && raw.length >= minThreshold;
    }
}
