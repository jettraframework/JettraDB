package com.jettra.store.engine.core.storage.compression;

/**
 * Adaptive record compression strategy.
 * Evaluates whether compression actually achieves measurable disk savings (at least 10% reduction).
 * If the payload is incompressible (e.g. already compressed or high entropy), it preserves uncompressed storage.
 */
public class AdaptiveRecordCompressionStrategy implements RecordCompressionStrategy {

    private final RecordCompressionStrategy delegate;
    private final double minimumSavingsRatio;

    public AdaptiveRecordCompressionStrategy() {
        this(new DeflateRecordCompressionStrategy(), 0.10);
    }

    public AdaptiveRecordCompressionStrategy(RecordCompressionStrategy delegate, double minimumSavingsRatio) {
        this.delegate = delegate;
        this.minimumSavingsRatio = minimumSavingsRatio;
    }

    @Override
    public byte[] compress(byte[] raw) {
        if (!shouldCompress(raw)) {
            return raw;
        }
        byte[] compressed = delegate.compress(raw);
        // Check if compression saved at least minimumSavingsRatio (e.g. 10%)
        if (compressed.length < raw.length * (1.0 - minimumSavingsRatio)) {
            return compressed;
        }
        // Not enough savings; return raw uncompressed
        return raw;
    }

    @Override
    public byte[] decompress(byte[] compressed, int originalLength) {
        if (compressed == null || compressed.length == originalLength) {
            return compressed;
        }
        return delegate.decompress(compressed, originalLength);
    }

    @Override
    public byte getAlgorithmId() {
        return ALGO_ADAPTIVE;
    }

    @Override
    public String getAlgorithmName() {
        return "Adaptive (" + delegate.getAlgorithmName() + ")";
    }

    @Override
    public boolean shouldCompress(byte[] raw) {
        return delegate.shouldCompress(raw);
    }
}
