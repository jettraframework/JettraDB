package com.jettra.store.engine.core.storage.compression;

/**
 * Strategy interface (Strategy Pattern) for record compression algorithms in JettraDB.
 * Enables interchangeable, high-density compression algorithms compatible with Java 25+.
 */
public interface RecordCompressionStrategy {

    byte ALGO_NONE = 0x00;
    byte ALGO_DEFLATE = 0x01;
    byte ALGO_ADAPTIVE = 0x02;

    int DEFAULT_MIN_COMPRESSION_THRESHOLD = 48; // bytes

    /**
     * Compresses the raw payload bytes.
     *
     * @param raw original raw payload
     * @return compressed payload bytes
     */
    byte[] compress(byte[] raw);

    /**
     * Decompresses the compressed payload bytes back to the original size.
     *
     * @param compressed     compressed bytes
     * @param originalLength uncompressed original byte count
     * @return uncompressed payload bytes
     */
    byte[] decompress(byte[] compressed, int originalLength);

    /**
     * Gets the unique single-byte identifier for this compression algorithm.
     */
    byte getAlgorithmId();

    /**
     * Gets the human-readable name of the compression strategy.
     */
    String getAlgorithmName();

    /**
     * Determines whether the given payload is eligible for compression based on size and entropy.
     */
    boolean shouldCompress(byte[] raw);
}
