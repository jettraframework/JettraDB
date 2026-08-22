package com.jettra.store.engine.core;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, multi-strategy Identifier Generator for JettraStoreEngine.
 * Supports:
 * 1. MANUAL: Uses caller-specified ID.
 * 2. AUTOINCREMENT: Thread-safe, per-namespace/collection incremental numeric ID (1, 2, 3...).
 * 3. UUID (Composite): Unique ID combining CPU/Host hardware signature, high-precision timestamp,
 *    namespace/collection digest, and cryptographic UUID entropy.
 */
public final class IdGenerator {

    public enum IdMode {
        MANUAL,
        AUTOINCREMENT,
        UUID;

        public static IdMode fromString(String raw) {
            if (raw == null || raw.isBlank()) return MANUAL;
            String normalized = raw.trim().toUpperCase();
            return switch (normalized) {
                case "AUTO", "AUTOINCREMENT", "AUTO_INCREMENT", "INCREMENT" -> AUTOINCREMENT;
                case "UUID", "COMPOSITE_UUID", "COMPOSITE", "GEN" -> UUID;
                default -> MANUAL;
            };
        }
    }

    private static final ConcurrentHashMap<String, AtomicLong> SEQUENCES = new ConcurrentHashMap<>();
    private static final String CPU_HOST_SIGNATURE = computeCpuHostSignature();

    private IdGenerator() {}

    /**
     * Generates or validates an ID based on the specified IdMode.
     *
     * @param collection Database or collection namespace
     * @param mode Selected generation mode
     * @param manualId Provided ID if manual mode
     * @return Resolved unique ID string
     */
    public static String generateId(String collection, IdMode mode, String manualId) {
        if (mode == null) mode = IdMode.MANUAL;

        return switch (mode) {
            case AUTOINCREMENT -> String.valueOf(nextSequenceValue(collection));
            case UUID -> generateCompositeUuid(collection);
            case MANUAL -> {
                if (manualId != null && !manualId.isBlank()) {
                    yield manualId.trim();
                }
                // Fallback if manual was chosen but no ID was provided
                yield generateCompositeUuid(collection);
            }
        };
    }

    /**
     * Generates an auto-incrementing ID for the given collection.
     */
    public static long nextSequenceValue(String collection) {
        String key = (collection == null || collection.isBlank()) ? "default" : collection.trim();
        return SEQUENCES.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Seeds or initializes the sequence counter from existing database records if needed.
     */
    public static void initializeSequence(String collection, long highestExistingId) {
        String key = (collection == null || collection.isBlank()) ? "default" : collection.trim();
        SEQUENCES.compute(key, (k, existing) -> {
            if (existing == null) {
                return new AtomicLong(highestExistingId);
            }
            if (highestExistingId > existing.get()) {
                existing.set(highestExistingId);
            }
            return existing;
        });
    }

    /**
     * Generates a Composite UUID containing:
     * - CPU & Host signature (8 hex chars)
     * - High-resolution timestamp (12 hex chars, sortable)
     * - Collection hash (4 hex chars)
     * - Secure random UUID suffix (12 hex chars)
     *
     * Format: cpuHex-timestampHex-collHex-randomUuid
     */
    public static String generateCompositeUuid(String collection) {
        long now = System.currentTimeMillis();
        String timeHex = Long.toHexString(now);
        String collHex = computeCollectionHash(collection);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        return String.format("%s-%s-%s-%s", CPU_HOST_SIGNATURE, timeHex, collHex, randomSuffix);
    }

    private static String computeCpuHostSignature() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(System.getProperty("os.name", "unknown")).append(";");
            sb.append(System.getProperty("os.arch", "unknown")).append(";");
            sb.append(Runtime.getRuntime().availableProcessors()).append(";");
            sb.append(ManagementFactory.getRuntimeMXBean().getName()).append(";");

            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost != null) {
                sb.append(localHost.getHostName()).append(";");
                NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
                if (ni != null && ni.getHardwareAddress() != null) {
                    sb.append(HexFormat.of().formatHex(ni.getHardwareAddress()));
                }
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static String computeCollectionHash(String collection) {
        if (collection == null || collection.isBlank()) return "0000";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(collection.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 4);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(collection.hashCode() & 0xFFFF);
        }
    }
}
