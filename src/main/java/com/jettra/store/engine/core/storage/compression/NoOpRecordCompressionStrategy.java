package com.jettra.store.engine.core.storage.compression;

/**
 * Passthrough / No-Op compression strategy for scenarios where raw uncompressed persistence is desired.
 */
public class NoOpRecordCompressionStrategy implements RecordCompressionStrategy {

    @Override
    public byte[] compress(byte[] raw) {
        return raw != null ? raw : new byte[0];
    }

    @Override
    public byte[] decompress(byte[] compressed, int originalLength) {
        return compressed != null ? compressed : new byte[0];
    }

    @Override
    public byte getAlgorithmId() {
        return ALGO_NONE;
    }

    @Override
    public String getAlgorithmName() {
        return "None (Uncompressed)";
    }

    @Override
    public boolean shouldCompress(byte[] raw) {
        return false;
    }
}
