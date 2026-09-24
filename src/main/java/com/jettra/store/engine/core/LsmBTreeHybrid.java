package com.jettra.store.engine.core;

import com.jettra.store.engine.core.generational.*;
import com.jettra.store.engine.core.storage.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LsmBTreeHybrid: A multi-partition hybrid storage structure combining the write-optimized 
 * nature of Log-Structured Merge-trees (LSM) with the read-optimized indexing of B-Trees.
 *
 * <p>Architecture (Per-Database Dedicated Storage Files):</p>
 * <ul>
 *   <li>Each database maintains its own dedicated on-disk directory and files under 
 *       {@code storageDirectory/databases/<dbName>/} containing its isolated {@code wal.jettra} 
 *       and {@code data_0.jettra} SSTables.</li>
 *   <li>Each database partition operates its own independent in-memory {@code MemTable}, 
 *       {@code diskIndex}, {@code versionHistory}, and {@code JettraFileManager}.</li>
 *   <li>Operations on database A are fully isolated from database B, eliminating I/O contention, 
 *       minimizing WAL overhead, enabling independent database compaction and hot drops.</li>
 * </ul>
 */
public class LsmBTreeHybrid {
    
    public record RecordVersion(
        int versionNumber,
        long timestamp,
        String formattedDate,
        byte[] data,
        String payload,
        boolean isCurrent
    ) {}

    /**
     * Dedicated storage partition for an individual database.
     */
    public static class DatabasePartition {
        private final String dbName;
        private final Path rootStorageDir;
        private final Path dbDirectory;
        private final Path journalFile;
        private final ConcurrentSkipListMap<String, byte[]> memTable;
        private final Map<String, Long> diskIndex;
        private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, byte[]>> versionHistory;
        private JettraFileManager fileManager;
        private final StorageRecordRepository recordRepository;
        private final int FLUSH_THRESHOLD = 1000;
        private final YoungArea youngArea;
        private final OldArea oldArea;
        private final InternalCompactor internalCompactor;
        private final Map<String, AtomicLong> engineRecordCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> cachedEngineCounts = new ConcurrentHashMap<>();
        private volatile long lastEngineCountCalc = 0L;
        private static final long ENGINE_COUNT_CACHE_TTL_MS = 1500L;

        public DatabasePartition(Path rootStorageDir, String dbName) {
            this.rootStorageDir = rootStorageDir;
            this.dbName = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "_system";
            if ("_system".equalsIgnoreCase(this.dbName)) {
                this.dbDirectory = rootStorageDir.resolve("system");
            } else if ("system_db".equalsIgnoreCase(this.dbName)) {
                Path directSysDb = rootStorageDir.resolve("system_db");
                if (Files.exists(directSysDb) && Files.isDirectory(directSysDb)) {
                    this.dbDirectory = directSysDb;
                } else {
                    this.dbDirectory = rootStorageDir.resolve("databases").resolve(this.dbName);
                }
            } else {
                this.dbDirectory = rootStorageDir.resolve("databases").resolve(this.dbName);
            }
            this.journalFile = this.dbDirectory.resolve("wal.jettra");
            this.memTable = new ConcurrentSkipListMap<>();
            this.diskIndex = new ConcurrentHashMap<>();
            this.versionHistory = new ConcurrentHashMap<>();
            this.recordRepository = com.jettra.store.engine.core.storage.StorageEngineFactory.createRepository();
            this.youngArea = new YoungArea(this.dbName);
            this.oldArea = new OldArea(this.dbName, this.diskIndex);
            this.internalCompactor = new InternalCompactor();

            try {
                if (!Files.exists(this.dbDirectory)) {
                    Files.createDirectories(this.dbDirectory);
                }
                this.fileManager = new JettraFileManager(this.dbDirectory.resolve("data_0.jettra"));
            } catch (IOException e) {
                System.err.println("Error initializing file manager for database partition [" + this.dbName + "]: " + e.getMessage());
            }

            loadFromWal();
            loadFromDiskHierarchy();
        }

        private void loadFromDiskHierarchy() {
            if (!Files.exists(this.dbDirectory)) return;

            // 1. Fast path: load persisted engine counts metadata if present
            Path countsFile = this.dbDirectory.resolve(".engine_counts");
            if (Files.exists(countsFile)) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(countsFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("=");
                        if (parts.length == 2) {
                            try {
                                long c = Long.parseLong(parts[1].trim());
                                engineRecordCounts.put(parts[0].trim().toUpperCase(), new AtomicLong(c));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } catch (IOException ignored) {}
            }

            // 2. Count on-disk records and warm up index up to MAX_STARTUP_INDEX_KEYS (50,000)
            // This prevents OutOfMemoryError: Java heap space on databases with millions of objects.
            int indexedCount = 0;
            final int MAX_STARTUP_INDEX_KEYS = 50_000;
            boolean countNeedsPersist = engineRecordCounts.isEmpty();
            long totalKnown = 0;
            for (AtomicLong val : engineRecordCounts.values()) {
                totalKnown += val.get();
            }

            // If millions of records are already known, warm up index with a lightweight sample without exhaustive directory walking
            final int effectiveLimit = (totalKnown > MAX_STARTUP_INDEX_KEYS) ? 10_000 : MAX_STARTUP_INDEX_KEYS;

            try (DirectoryStream<Path> engineDirs = Files.newDirectoryStream(this.dbDirectory)) {
                for (Path engineDir : engineDirs) {
                    if (!Files.isDirectory(engineDir)) continue;
                    if (totalKnown > MAX_STARTUP_INDEX_KEYS && indexedCount >= effectiveLimit) break;
                    String engineName = engineDir.getFileName().toString();
                    if (engineName.equals("data_0.jettra") || engineName.equals("wal.jettra") || engineName.startsWith(".")) continue;
                    StorageEngineStrategy strategy = EngineStorageStrategyRegistry.resolve(engineName);
                    String prefix = strategy.getPrefix();

                    long count = 0;
                    try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                        for (Path unitDir : unitDirs) {
                            if (!Files.isDirectory(unitDir)) continue;
                            if (totalKnown > MAX_STARTUP_INDEX_KEYS && indexedCount >= effectiveLimit) break;
                            String unitName = unitDir.getFileName().toString();
                            List<com.jettra.store.engine.core.storage.StorageRecordPath> recPaths = recordRepository.listRecords(rootStorageDir, dbName, engineName, unitName);
                            for (com.jettra.store.engine.core.storage.StorageRecordPath srp : recPaths) {
                                count++;
                                if (indexedCount < effectiveLimit) {
                                    String recId = srp.recordId();
                                    String primaryKey = "default".equalsIgnoreCase(unitName)
                                        ? prefix + dbName + ":" + recId
                                        : prefix + dbName + ":" + unitName + ":" + recId;
                                    diskIndex.put(primaryKey, 0L);
                                    if ("records".equalsIgnoreCase(engineName)) {
                                        diskIndex.put(dbName + ":" + recId, 0L);
                                    }
                                    indexedCount++;
                                }
                            }
                        }
                    }
                    if (countNeedsPersist && count > 0) {
                        engineRecordCounts.put(engineName.toUpperCase(), new AtomicLong(count));
                    }
                }
            } catch (IOException ignored) {}

            if (countNeedsPersist) {
                persistEngineCounts();
            }
        }

        private void persistEngineCounts() {
            if (!Files.exists(this.dbDirectory)) return;
            Path countsFile = this.dbDirectory.resolve(".engine_counts");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(countsFile, java.nio.charset.StandardCharsets.UTF_8)) {
                for (Map.Entry<String, AtomicLong> entry : engineRecordCounts.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue().get() + "\n");
                }
            } catch (IOException ignored) {}
        }

        public String getDbName() {
            return dbName;
        }

        public Path getDbDirectory() {
            return dbDirectory;
        }

        public void loadInitialRecord(String key, byte[] data, long timestamp) {
            if (key == null || data == null) return;
            ConcurrentSkipListMap<Long, byte[]> history = versionHistory.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
            long effectiveTs = timestamp;
            if (!history.isEmpty() && history.lastKey() >= effectiveTs) {
                effectiveTs = history.lastKey() + 1;
            }
            history.put(effectiveTs, data);
            String versionedKey = key + "@" + effectiveTs;
            memTable.put(versionedKey, data);
            youngArea.put(key, data, effectiveTs, 1);
            oldArea.getDiskIndex().put(key, 0L);
        }

        private void loadFromWal() {
            if (!Files.exists(journalFile)) {
                return;
            }
            try (java.io.DataInputStream dis = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(journalFile)))) {
                while (true) {
                    String key;
                    try {
                        key = dis.readUTF();
                    } catch (java.io.EOFException eof) {
                        break;
                    }
                    long ts = dis.readLong();
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    if (len > 0) {
                        dis.readFully(data);
                        memTable.put(key + "@" + ts, data);
                        versionHistory.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>()).put(ts, data);
                        youngArea.put(key, data, ts, 1);
                        oldArea.getDiskIndex().put(key, 0L);
                    } else {
                        // Tombstone deletion: purge prior entries for this key
                        List<String> toRemove = new java.util.ArrayList<>();
                        for (String k : memTable.keySet()) {
                            if (k.equals(key) || k.startsWith(key + "@")) {
                                toRemove.add(k);
                            }
                        }
                        for (String k : toRemove) {
                            memTable.remove(k);
                        }
                        versionHistory.remove(key);
                        memTable.put(key + "@" + ts, new byte[0]);
                        youngArea.delete(key, ts);
                        oldArea.delete(key, recordRepository, rootStorageDir);
                    }
                }
            } catch (IOException e) {
                System.err.println("Warning while loading WAL for database [" + dbName + "]: " + e.getMessage());
            }
        }

        private synchronized void appendWal(String key, long ts, byte[] data) {
            try {
                if (!Files.exists(dbDirectory)) {
                    Files.createDirectories(dbDirectory);
                }
                try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(new java.io.FileOutputStream(journalFile.toFile(), true)))) {
                    dos.writeUTF(key);
                    dos.writeLong(ts);
                    dos.writeInt(data != null ? data.length : 0);
                    if (data != null && data.length > 0) {
                        dos.write(data);
                    }
                    dos.flush();
                }
            } catch (IOException e) {
                System.err.println("Error writing to WAL for database [" + dbName + "]: " + e.getMessage());
            }
        }

        public void put(String key, byte[] data, long timestamp) {
            if (key == null || data == null) return;

            ConcurrentSkipListMap<Long, byte[]> history = versionHistory.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
            if (history.isEmpty()) {
                byte[] existing = get(key);
                if (existing != null && existing.length > 0 && !Arrays.equals(existing, data)) {
                    long baselineTs = timestamp > 1 ? timestamp - 1 : System.currentTimeMillis() - 1;
                    history.put(baselineTs, existing);
                    memTable.put(key + "@" + baselineTs, existing);
                }
            }
            long effectiveTs = timestamp;
            if (!history.isEmpty() && history.lastKey() >= effectiveTs) {
                effectiveTs = history.lastKey() + 1;
            }
            history.put(effectiveTs, data);

            String versionedKey = key + "@" + effectiveTs;
            memTable.put(versionedKey, data);
            appendWal(key, effectiveTs, data);

            // Generational Young Area write with off-heap Panama Arena
            youngArea.put(key, data, effectiveTs, 1);

            String eng = resolveEngineFromKey(key);
            if (eng != null) {
                engineRecordCounts.computeIfAbsent(eng, k -> new AtomicLong(0)).incrementAndGet();
            }

            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, data);
                recordRepository.save(path, data, effectiveTs, 1);
            } catch (IOException e) {
                System.err.println("Warning saving individual record for key [" + key + "]: " + e.getMessage());
            }

            if (youngArea.isThresholdReached() || memTable.size() >= FLUSH_THRESHOLD) {
                performMinorCompaction();
            }
        }

        public byte[] get(String key) {
            // 1. Fast-path check in Young Area (Off-Heap Panama Arena)
            if (youngArea.isTombstone(key)) {
                return null;
            }
            byte[] youngVal = youngArea.get(key);
            if (youngVal != null && youngVal.length > 0) {
                return youngVal;
            }

            ConcurrentSkipListMap<Long, byte[]> hist = versionHistory.get(key);
            if (hist != null && !hist.isEmpty()) {
                byte[] val = hist.lastEntry().getValue();
                return (val != null && val.length > 0) ? val : null;
            }

            String latestKey = memTable.floorKey(key + "@" + Long.MAX_VALUE);
            if (latestKey != null && latestKey.startsWith(key + "@")) {
                byte[] val = memTable.get(latestKey);
                return (val != null && val.length > 0) ? val : null;
            }

            // 2. Fast-path check in Old Generation Area (warm cache, data_0.jettra, repository)
            byte[] oldVal = oldArea.get(key, fileManager, recordRepository, rootStorageDir);
            if (oldVal != null && oldVal.length > 0) {
                return oldVal;
            }

            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, null);
                byte[] payload = recordRepository.readPayload(path);
                if (payload != null && payload.length > 0) {
                    return payload;
                }
            } catch (Exception ignored) {}

            Long offset = diskIndex.get(key);
            if (offset != null && offset > 0 && fileManager != null) {
                try {
                    byte[] val = fileManager.read(offset);
                    return (val != null && val.length > 0) ? val : null;
                } catch (IOException e) {
                    System.err.println("Error reading record [" + key + "] from database [" + dbName + "]: " + e.getMessage());
                }
            }
            return null;
        }

        public void delete(String key, long timestamp) {
            if (key == null) return;
            youngArea.delete(key, timestamp);
            oldArea.delete(key, recordRepository, rootStorageDir);

            List<String> toRemove = new java.util.ArrayList<>();
            for (String k : memTable.keySet()) {
                if (k.equals(key) || k.startsWith(key + "@")) {
                    toRemove.add(k);
                }
            }
            for (String k : toRemove) {
                memTable.remove(k);
            }
            versionHistory.remove(key);
            String versionedKey = key + "@" + timestamp;
            memTable.put(versionedKey, new byte[0]);
            appendWal(key, timestamp, new byte[0]);
            diskIndex.remove(key);

            String eng = resolveEngineFromKey(key);
            if (eng != null) {
                AtomicLong c = engineRecordCounts.get(eng);
                if (c != null && c.get() > 0) {
                    c.decrementAndGet();
                }
            }

            try {
                StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, null);
                recordRepository.delete(path);
            } catch (Exception ignored) {}
        }

        public Map<String, byte[]> scanPrefix(String prefix) {
            Map<String, byte[]> results = new LinkedHashMap<>();
            if (prefix == null || prefix.isEmpty()) {
                for (String k : memTable.keySet()) {
                    String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                    if (!results.containsKey(baseKey)) {
                        byte[] val = get(baseKey);
                        if (val != null && val.length > 0) results.put(baseKey, val);
                    }
                }
                for (String baseKey : diskIndex.keySet()) {
                    if (!results.containsKey(baseKey)) {
                        byte[] val = get(baseKey);
                        if (val != null && val.length > 0) results.put(baseKey, val);
                    }
                }
                return results;
            }

            for (String k : memTable.tailMap(prefix).keySet()) {
                if (!k.startsWith(prefix)) break;
                String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                if (!results.containsKey(baseKey)) {
                    byte[] val = get(baseKey);
                    if (val != null && val.length > 0) {
                        results.put(baseKey, val);
                    }
                }
            }
            for (String baseKey : diskIndex.keySet()) {
                if (baseKey.startsWith(prefix) && !results.containsKey(baseKey)) {
                    byte[] val = get(baseKey);
                    if (val != null && val.length > 0) {
                        results.put(baseKey, val);
                    }
                }
            }
            return results;
        }

        public synchronized void putBatch(List<Map.Entry<String, byte[]>> entries, long timestamp) {
            if (entries == null || entries.isEmpty()) return;
            try {
                if (!Files.exists(dbDirectory)) {
                    Files.createDirectories(dbDirectory);
                }
                try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(new java.io.FileOutputStream(journalFile.toFile(), true), 131072))) {
                    for (Map.Entry<String, byte[]> entry : entries) {
                        String key = entry.getKey();
                        byte[] data = entry.getValue();
                        if (key == null || data == null) continue;

                        dos.writeUTF(key);
                        dos.writeLong(timestamp);
                        dos.writeInt(data.length);
                        if (data.length > 0) {
                            dos.write(data);
                        }

                        if (fileManager != null) {
                            long offset = fileManager.append(data, false);
                            if (offset > 0) {
                                diskIndex.put(key, offset);
                            }
                        }
                        youngArea.put(key, data, timestamp, 1);
                    }
                    dos.flush();
                }

                // Batch update engine counts in memory
                Map<String, Integer> deltas = new HashMap<>();
                for (Map.Entry<String, byte[]> entry : entries) {
                    String key = entry.getKey();
                    if (key != null) {
                        String eng = resolveEngineFromKey(key);
                        if (eng != null) {
                            deltas.merge(eng, 1, Integer::sum);
                        }
                    }
                }
                for (Map.Entry<String, Integer> d : deltas.entrySet()) {
                    engineRecordCounts.computeIfAbsent(d.getKey(), k -> new AtomicLong(0)).addAndGet(d.getValue());
                }

                // Concurrent virtual thread persistence of individual record files (.dat)
                List<StorageRecordRepository.RecordWriteTask> tasks = new java.util.ArrayList<>(entries.size());
                for (Map.Entry<String, byte[]> entry : entries) {
                    String key = entry.getKey();
                    byte[] data = entry.getValue();
                    if (key == null || data == null) continue;
                    StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, key, data);
                    tasks.add(new StorageRecordRepository.RecordWriteTask(path, data, timestamp, 1));
                }
                recordRepository.saveBatch(tasks);

                if (fileManager != null) {
                    fileManager.force();
                }
            } catch (IOException e) {
                System.err.println("Error in putBatch for database [" + dbName + "]: " + e.getMessage());
            }
        }

        public Set<String> scanPrefixKeys(String prefix) {
            Set<String> results = new java.util.LinkedHashSet<>();
            if (prefix == null || prefix.isEmpty()) {
                for (String k : memTable.keySet()) {
                    String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                    results.add(baseKey);
                }
                for (String baseKey : diskIndex.keySet()) {
                    results.add(baseKey);
                }
                return results;
            }

            for (String k : memTable.tailMap(prefix).keySet()) {
                if (!k.startsWith(prefix)) break;
                String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                results.add(baseKey);
            }
            for (String baseKey : diskIndex.keySet()) {
                if (baseKey.startsWith(prefix)) {
                    results.add(baseKey);
                }
            }
            return results;
        }

        public List<String> scanPrefixKeysPaged(String prefix, int offset, int limit) {
            List<String> results = new java.util.ArrayList<>(Math.max(1, limit));
            if (prefix == null) prefix = "";
            int skipped = 0;
            Set<String> seen = new java.util.HashSet<>();

            for (String k : memTable.tailMap(prefix).keySet()) {
                if (!prefix.isEmpty() && !k.startsWith(prefix)) break;
                if (k.contains("@")) continue;
                String baseKey = k;
                if (seen.add(baseKey)) {
                    if (skipped < offset) {
                        skipped++;
                    } else {
                        results.add(baseKey);
                        if (results.size() >= limit) {
                            return results;
                        }
                    }
                }
            }

            for (String baseKey : diskIndex.keySet()) {
                if (prefix.isEmpty() || baseKey.startsWith(prefix)) {
                    if (seen.add(baseKey)) {
                        if (skipped < offset) {
                            skipped++;
                        } else {
                            results.add(baseKey);
                            if (results.size() >= limit) {
                                return results;
                            }
                        }
                    }
                }
            }
            return results;
        }

        public int getTotalRecordCount() {
            long total = 0;
            for (AtomicLong count : engineRecordCounts.values()) {
                total += count.get();
            }
            if (total == 0) {
                total = diskIndex.size();
            }
            return (int) Math.min(total, Integer.MAX_VALUE);
        }

        public Map<String, Integer> getEngineCounts() {
            long now = System.currentTimeMillis();
            if (now - lastEngineCountCalc < ENGINE_COUNT_CACHE_TTL_MS && !cachedEngineCounts.isEmpty()) {
                return new LinkedHashMap<>(cachedEngineCounts);
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<String, AtomicLong> entry : engineRecordCounts.entrySet()) {
                long c = entry.getValue().get();
                if (c > 0) {
                    counts.put(entry.getKey(), (int) Math.min(c, Integer.MAX_VALUE));
                }
            }

            if (counts.isEmpty() && !diskIndex.isEmpty()) {
                for (String baseKey : diskIndex.keySet()) {
                    String eng = resolveEngineFromKey(baseKey);
                    if (eng != null) {
                        counts.merge(eng, 1, Integer::sum);
                    }
                }
            }

            cachedEngineCounts.clear();
            cachedEngineCounts.putAll(counts);
            lastEngineCountCalc = now;
            return counts;
        }

        private String resolveEngineFromKey(String key) {
            if (key == null || key.isBlank() || key.startsWith("meta:") || key.startsWith("schema:") || key.startsWith("rule:") || key.startsWith("idx:")) {
                return null;
            }
            if (key.startsWith("rec:")) return "RECORDS";
            if (key.startsWith("doc:")) return "DOCUMENT";
            if (key.startsWith("vec:")) return "VECTOR";
            if (key.startsWith("graph:")) return "GRAPH";
            if (key.startsWith("ts:")) return "TIMESERIES";
            if (key.startsWith("col:")) return "COLUMN";
            if (key.startsWith("kv:")) return "KEYVALUE";
            if (key.startsWith("geo:")) return "GEOSPATIAL";
            if (key.startsWith("obj:")) return "OBJECT";
            if (key.startsWith("user:") || key.startsWith("role:")) return "RECORDS";
            return "DOCUMENT";
        }

        public List<RecordVersion> getVersionHistory(String key) {
            List<RecordVersion> versions = new java.util.ArrayList<>();
            String prefix = key + "@";
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            Map<Long, byte[]> timeMap = new java.util.TreeMap<>();

            ConcurrentSkipListMap<Long, byte[]> hist = versionHistory.get(key);
            if (hist != null && !hist.isEmpty()) {
                timeMap.putAll(hist);
            } else {
                for (Map.Entry<String, byte[]> entry : memTable.entrySet()) {
                    String k = entry.getKey();
                    if (k.startsWith(prefix)) {
                        try {
                            long ts = Long.parseLong(k.substring(prefix.length()));
                            byte[] data = entry.getValue();
                            if (data != null && data.length > 0) {
                                timeMap.put(ts, data);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (timeMap.isEmpty() && (diskIndex.containsKey(key) || get(key) != null)) {
                byte[] diskVal = get(key);
                if (diskVal != null && diskVal.length > 0) {
                    timeMap.put(System.currentTimeMillis(), diskVal);
                }
            }

            if (timeMap.isEmpty()) {
                return versions;
            }

            int versionCounter = 1;
            long latestTs = ((java.util.TreeMap<Long, byte[]>) timeMap).lastKey();
            List<RecordVersion> chronological = new java.util.ArrayList<>();
            for (Map.Entry<Long, byte[]> entry : timeMap.entrySet()) {
                long ts = entry.getKey();
                byte[] data = entry.getValue();
                String payloadStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                String formattedDate = java.time.Instant.ofEpochMilli(ts)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(dtf);
                boolean isCurrent = (ts == latestTs);
                chronological.add(new RecordVersion(versionCounter++, ts, formattedDate, data, payloadStr, isCurrent));
            }

            for (int i = chronological.size() - 1; i >= 0; i--) {
                versions.add(chronological.get(i));
            }

            return versions;
        }

        public int getVersionCount(String key) {
            ConcurrentSkipListMap<Long, byte[]> hist = versionHistory.get(key);
            if (hist != null && !hist.isEmpty()) {
                return hist.size();
            }
            String prefix = key + "@";
            int count = 0;
            for (String k : memTable.tailMap(prefix).keySet()) {
                if (!k.startsWith(prefix)) break;
                byte[] v = memTable.get(k);
                if (v != null && v.length > 0) count++;
            }
            if (count == 0 && get(key) != null) count = 1;
            return count;
        }

        public byte[] getVersion(String key, long timestamp) {
            ConcurrentSkipListMap<Long, byte[]> hist = versionHistory.get(key);
            if (hist != null && hist.containsKey(timestamp)) {
                return hist.get(timestamp);
            }
            String versionedKey = key + "@" + timestamp;
            if (memTable.containsKey(versionedKey)) {
                return memTable.get(versionedKey);
            }
            return null;
        }

        public boolean restoreVersion(String key, long timestamp) {
            byte[] historicalData = getVersion(key, timestamp);
            if (historicalData != null && historicalData.length > 0) {
                put(key, historicalData, System.currentTimeMillis());
                return true;
            }
            return false;
        }

        public synchronized void flushToBTree() {
            if (memTable.isEmpty()) return;
            try {
                List<StorageRecordRepository.RecordWriteTask> flushTasks = new java.util.ArrayList<>(memTable.size());
                for (Map.Entry<String, byte[]> entry : memTable.entrySet()) {
                    long offset = (fileManager != null) ? fileManager.append(entry.getValue(), false) : 0L;
                    String k = entry.getKey();
                    String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                    diskIndex.put(baseKey, offset);
                    byte[] val = entry.getValue();
                    if (val != null && val.length > 0) {
                        StorageRecordPath path = StoragePathBuilder.fromKey(rootStorageDir, baseKey, val);
                        flushTasks.add(new StorageRecordRepository.RecordWriteTask(path, val, System.currentTimeMillis(), 1));
                    }
                }
                recordRepository.saveBatch(flushTasks);
                if (fileManager != null) {
                    fileManager.force();
                }
                memTable.clear();
            } catch (IOException e) {
                System.err.println("Error flushing MemTable for database [" + dbName + "]: " + e.getMessage());
            }
        }

        public StorageRecordRepository getRecordRepository() {
            return recordRepository;
        }

        public Path getRootStorageDir() {
            return rootStorageDir;
        }

        public void drop() {
            try {
                if (fileManager != null) {
                    fileManager.close();
                }
            } catch (IOException ignored) {}
            youngArea.close();
            oldArea.close();
            memTable.clear();
            diskIndex.clear();
            versionHistory.clear();
            if (recordRepository instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception ignored) {}
            }
            recordRepository.dropDatabase(rootStorageDir, dbName);
            deleteDirectoryRecursively(dbDirectory);
        }

        public void close() {
            try {
                flushToBTree();
                youngArea.close();
                oldArea.close();
                if (fileManager != null) {
                    fileManager.close();
                }
                if (recordRepository instanceof AutoCloseable ac) {
                    ac.close();
                }
            } catch (Exception e) {
                System.err.println("Error closing database partition [" + dbName + "]: " + e.getMessage());
            }
        }

        public InternalCompactor.CompactionReport performMinorCompaction() {
            flushToBTree();
            return internalCompactor.performMinorCompaction(youngArea, oldArea, fileManager, recordRepository, rootStorageDir);
        }

        public InternalCompactor.CompactionReport performMajorCompaction() {
            flushToBTree();
            JettraFileManager[] holder = new JettraFileManager[]{fileManager};
            InternalCompactor.CompactionReport report = internalCompactor.performMajorCompaction(
                    youngArea, oldArea, holder, recordRepository, rootStorageDir, dbDirectory, journalFile);
            this.fileManager = holder[0];
            this.diskIndex.clear();
            this.diskIndex.putAll(oldArea.getDiskIndex());
            return report;
        }

        public YoungArea getYoungArea() {
            return youngArea;
        }

        public OldArea getOldArea() {
            return oldArea;
        }

        public InternalCompactor getInternalCompactor() {
            return internalCompactor;
        }

        private static void deleteDirectoryRecursively(Path dir) {
            if (dir == null || !Files.exists(dir)) return;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        deleteDirectoryRecursively(entry);
                    } else {
                        Files.deleteIfExists(entry);
                    }
                }
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                System.err.println("Warning deleting database directory " + dir + ": " + e.getMessage());
            }
        }
    }

    private final Path storageDirectory;
    private final ConcurrentHashMap<String, DatabasePartition> partitions;

    public LsmBTreeHybrid(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.partitions = new ConcurrentHashMap<>();

        try {
            if (!Files.exists(storageDirectory)) {
                Files.createDirectories(storageDirectory);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Initialize system partitions
        getPartition("_system");
        getPartition("system_db");

        // Scan existing databases on disk under databases/
        Path dbRootDir = storageDirectory.resolve("databases");
        if (Files.exists(dbRootDir) && Files.isDirectory(dbRootDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbRootDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        String dbName = entry.getFileName().toString();
                        getPartition(dbName);
                    }
                }
            } catch (IOException e) {
                System.err.println("Warning scanning databases directory: " + e.getMessage());
            }
        }

        // Legacy compatibility: check if legacy root WAL exists and load it into _system partition
        Path legacyWal = storageDirectory.resolve("jettra_storage_wal.jettra");
        if (Files.exists(legacyWal)) {
            migrateLegacyWal(legacyWal);
        }
    }

    private void migrateLegacyWal(Path legacyWal) {
        System.out.println("[LsmBTreeHybrid] Migrating legacy root WAL (" + legacyWal.getFileName() + ")...");
        long start = System.currentTimeMillis();
        int migratedCount = 0;
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(legacyWal)))) {
            while (true) {
                String key;
                try {
                    key = dis.readUTF();
                } catch (java.io.EOFException eof) {
                    break;
                }
                long ts = dis.readLong();
                int len = dis.readInt();
                byte[] data = new byte[len];
                if (len > 0) {
                    dis.readFully(data);
                    String db = extractDatabaseFromKey(key);
                    getPartition(db).loadInitialRecord(key, data, ts);
                    migratedCount++;
                }
            }
        } catch (Exception e) {
            System.err.println("[LsmBTreeHybrid] Warning during legacy WAL migration: " + e.getMessage());
        }

        // Flush partitions that received legacy records
        for (DatabasePartition partition : partitions.values()) {
            partition.flushToBTree();
        }

        // Once migration is complete, rename the legacy WAL so it never runs again
        try {
            Path migratedPath = legacyWal.resolveSibling(legacyWal.getFileName() + ".migrated");
            Files.move(legacyWal, migratedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[LsmBTreeHybrid] Legacy WAL migration completed (" + migratedCount + " records in " + elapsed + "ms) and archived to " + migratedPath.getFileName());
        } catch (IOException e) {
            System.err.println("[LsmBTreeHybrid] Warning renaming migrated legacy WAL: " + e.getMessage());
        }
    }

    /**
     * Extracts the target database name from any engine key structure.
     * Supports standard prefixes (rec:, doc:, geo:, vec:, obj:, kv:, ts:, graph:, col:, schema:, idx:),
     * direct database keys (e.g. ExampleDBReferences:id), and system keys.
    /**
     * Checks whether a database name candidate matches system namespaces, multi-model engine prefixes,
     * or metadata prefixes that should never be created or recognized as independent user databases.
     */
    public static boolean isReservedDatabaseName(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.trim().toLowerCase();
        return n.equals("_system") || n.equals("system") || n.equals("sys")
                || n.equals("col") || n.equals("doc") || n.equals("geo")
                || n.equals("graph") || n.equals("kb") || n.equals("kv")
                || n.equals("obj") || n.equals("rec") || n.equals("record_store")
                || n.equals("recordstore") || n.equals("ts") || n.equals("vec")
                || n.equals("meta") || n.equals("schema") || n.equals("idx")
                || n.equals("rule") || n.equals("column") || n.equals("document")
                || n.equals("geospatial") || n.equals("keyvalue") || n.equals("object")
                || n.equals("record") || n.equals("records") || n.equals("timeseries")
                || n.equals("vector");
    }

    /**
     * Extracts database name from storage key using multi-model hierarchy patterns.
     */
    public static String extractDatabaseFromKey(String key) {
        if (key == null || key.isBlank()) return "_system";
        if (key.startsWith("sys:") || key.startsWith("_system:") || key.startsWith("system:")) {
            return "_system";
        }
        int firstColon = key.indexOf(':');
        if (firstColon > 0) {
            String pfx = key.substring(0, firstColon).toLowerCase();
            if (pfx.equals("rec") || pfx.equals("doc") || pfx.equals("geo") || pfx.equals("vec")
                    || pfx.equals("obj") || pfx.equals("kv") || pfx.equals("kb") || pfx.equals("ts")
                    || pfx.equals("graph") || pfx.equals("col") || pfx.equals("record_store")
                    || pfx.equals("recordstore") || pfx.equals("schema") || pfx.equals("idx")
                    || pfx.equals("rule") || pfx.equals("meta") || isReservedDatabaseName(pfx)) {
                String rest = key.substring(firstColon + 1);
                if (rest.isBlank()) {
                    return "_system"; // generic prefix like "doc:"
                }
                int nextColon = rest.indexOf(':');
                if (nextColon > 0) {
                    String dbCandidate = rest.substring(0, nextColon).trim();
                    return (dbCandidate.isBlank() || isReservedDatabaseName(dbCandidate)) ? "_system" : dbCandidate;
                } else {
                    // Key format is prefix:id without a database segment (e.g. geo:hub_1, graph:node_1).
                    // This is an entity ID under the default/system namespace, NOT a database name.
                    return "_system";
                }
            } else {
                String candidate = key.substring(0, firstColon).trim();
                return isReservedDatabaseName(candidate) ? "_system" : candidate;
            }
        }
        return "_system";
    }

    /**
     * Finds an existing database partition if loaded in memory, or loads it from disk
     * strictly if its physical directory exists under databases/.
     * Read-only operations use this method to avoid inadvertently creating physical
     * directories on disk for non-existent or uninstalled databases.
     */
    public DatabasePartition findPartition(String dbName) {
        String cleanDb = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "_system";
        DatabasePartition exact = partitions.get(cleanDb);
        if (exact != null) return exact;

        for (Map.Entry<String, DatabasePartition> entry : partitions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(cleanDb)) {
                return entry.getValue();
            }
        }

        for (Map.Entry<String, DatabasePartition> entry : partitions.entrySet()) {
            String k = entry.getKey();
            if (k.equalsIgnoreCase(cleanDb + "s") || (cleanDb.endsWith("s") && k.equalsIgnoreCase(cleanDb.substring(0, cleanDb.length() - 1)))) {
                return entry.getValue();
            }
        }

        // Check if directory physically exists on disk
        Path targetDir;
        if ("_system".equalsIgnoreCase(cleanDb)) {
            targetDir = storageDirectory.resolve("system");
        } else if ("system_db".equalsIgnoreCase(cleanDb)) {
            Path directSysDb = storageDirectory.resolve("system_db");
            targetDir = (Files.exists(directSysDb) && Files.isDirectory(directSysDb))
                ? directSysDb
                : storageDirectory.resolve("databases").resolve(cleanDb);
        } else {
            targetDir = storageDirectory.resolve("databases").resolve(cleanDb);
        }
        if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
            return getPartition(cleanDb);
        }

        return null;
    }

    /**
     * Returns the dedicated database partition, instantiating it if not yet loaded.
     */
    public DatabasePartition getPartition(String dbName) {
        String cleanDb = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "_system";
        DatabasePartition exact = partitions.get(cleanDb);
        if (exact != null) return exact;

        for (Map.Entry<String, DatabasePartition> entry : partitions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(cleanDb)) {
                return entry.getValue();
            }
        }

        for (Map.Entry<String, DatabasePartition> entry : partitions.entrySet()) {
            String k = entry.getKey();
            if (k.equalsIgnoreCase(cleanDb + "s") || (cleanDb.endsWith("s") && k.equalsIgnoreCase(cleanDb.substring(0, cleanDb.length() - 1)))) {
                return entry.getValue();
            }
        }

        return partitions.computeIfAbsent(cleanDb, name -> new DatabasePartition(storageDirectory, name));
    }

    public Set<String> getDatabaseNames() {
        Set<String> dbs = new LinkedHashSet<>();
        dbs.add("system_db");
        for (String p : partitions.keySet()) {
            if (!"_system".equalsIgnoreCase(p) && !isReservedDatabaseName(p)) {
                dbs.add(p);
            }
        }
        Path dbRootDir = storageDirectory.resolve("databases");
        if (Files.exists(dbRootDir) && Files.isDirectory(dbRootDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbRootDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry) && !"_system".equalsIgnoreCase(name) && !isReservedDatabaseName(name)) {
                        dbs.add(name);
                    }
                }
            } catch (IOException ignored) {}
        }
        Path directSysDb = storageDirectory.resolve("system_db");
        if (Files.exists(directSysDb) && Files.isDirectory(directSysDb)) {
            dbs.add("system_db");
        }
        return dbs;
    }

    public void dropDatabase(String dbName) {
        if (dbName == null || dbName.isBlank() || "_system".equalsIgnoreCase(dbName) || "system_db".equalsIgnoreCase(dbName)) return;
        DatabasePartition partition = partitions.remove(dbName.trim());
        if (partition != null) {
            partition.drop();
        } else {
            Path dbDir = storageDirectory.resolve("databases").resolve(dbName.trim());
            DatabasePartition.deleteDirectoryRecursively(dbDir);
        }
    }

    /**
     * Drops all non-system databases and purges their physical directories from disk.
     * Useful for test teardowns and lifecycle reset to eliminate overhead and lingering disk/memory states.
     */
    public void dropAllDatabases() {
        for (String db : new LinkedHashSet<>(partitions.keySet())) {
            if (!"_system".equalsIgnoreCase(db) && !"system_db".equalsIgnoreCase(db)) {
                dropDatabase(db);
            }
        }
        Path dbRootDir = storageDirectory.resolve("databases");
        if (Files.exists(dbRootDir) && Files.isDirectory(dbRootDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbRootDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && !"system_db".equalsIgnoreCase(entry.getFileName().toString()) && !"_system".equalsIgnoreCase(entry.getFileName().toString())) {
                        DatabasePartition.deleteDirectoryRecursively(entry);
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    public void put(String key, byte[] data, long timestamp) {
        if (key == null || data == null) return;
        String db = extractDatabaseFromKey(key);
        getPartition(db).put(key, data, timestamp);
    }

    public byte[] get(String key) {
        if (key == null) return null;
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        byte[] val = partition != null ? partition.get(key) : null;
        if (val == null && !"_system".equals(db)) {
            DatabasePartition sys = findPartition("_system");
            if (sys != null) {
                val = sys.get(key);
            }
        }
        return val;
    }

    public void delete(String key, long timestamp) {
        if (key == null) return;
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        if (partition != null) {
            partition.delete(key, timestamp);
        }
    }

    public Map<String, byte[]> scanPrefix(String prefix) {
        Map<String, byte[]> results = new LinkedHashMap<>();
        if (prefix == null || prefix.isEmpty()) {
            for (DatabasePartition partition : partitions.values()) {
                results.putAll(partition.scanPrefix(""));
            }
            return results;
        }

        String db = extractDatabaseFromKey(prefix);
        if (!"_system".equals(db)) {
            // Specific database prefix
            DatabasePartition partition = findPartition(db);
            if (partition != null) {
                results.putAll(partition.scanPrefix(prefix));
            }
        } else {
            // Generic prefix (e.g. "doc:", "rec:") spanning across all database partitions
            for (DatabasePartition partition : partitions.values()) {
                results.putAll(partition.scanPrefix(prefix));
            }
        }
        return results;
    }

    public void putBatch(String dbName, List<Map.Entry<String, byte[]>> entries, long timestamp) {
        if (dbName == null || entries == null || entries.isEmpty()) return;
        getPartition(dbName).putBatch(entries, timestamp);
    }

    public void putBatch(List<Map.Entry<String, byte[]>> entries, long timestamp) {
        if (entries == null || entries.isEmpty()) return;
        Map<String, List<Map.Entry<String, byte[]>>> byDb = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> entry : entries) {
            String db = extractDatabaseFromKey(entry.getKey());
            byDb.computeIfAbsent(db, k -> new java.util.ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<Map.Entry<String, byte[]>>> dbEntries : byDb.entrySet()) {
            getPartition(dbEntries.getKey()).putBatch(dbEntries.getValue(), timestamp);
        }
    }

    public Set<String> scanPrefixKeys(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            Set<String> allKeys = new LinkedHashSet<>();
            for (DatabasePartition partition : partitions.values()) {
                allKeys.addAll(partition.scanPrefixKeys(""));
            }
            return allKeys;
        }

        String db = extractDatabaseFromKey(prefix);
        if (!"_system".equals(db)) {
            DatabasePartition partition = findPartition(db);
            if (partition != null) {
                return partition.scanPrefixKeys(prefix);
            }
        } else {
            Set<String> allKeys = new LinkedHashSet<>();
            for (DatabasePartition partition : partitions.values()) {
                allKeys.addAll(partition.scanPrefixKeys(prefix));
            }
            return allKeys;
        }
        return Collections.emptySet();
    }

    public List<String> scanPrefixKeysPaged(String prefix, int offset, int limit) {
        if (prefix == null) prefix = "";
        String db = extractDatabaseFromKey(prefix);
        if (db != null && !"_system".equals(db)) {
            DatabasePartition partition = findPartition(db);
            if (partition != null) {
                return partition.scanPrefixKeysPaged(prefix, offset, limit);
            }
        }
        List<String> allKeys = new ArrayList<>(Math.max(1, limit));
        int currentOffset = offset;
        for (DatabasePartition partition : partitions.values()) {
            List<String> partKeys = partition.scanPrefixKeysPaged(prefix, currentOffset, limit - allKeys.size());
            allKeys.addAll(partKeys);
            if (allKeys.size() >= limit) break;
            currentOffset = Math.max(0, currentOffset - partition.getTotalRecordCount());
        }
        return allKeys;
    }

    public int getEngineRecordCount(String dbName, String engineName) {
        if (dbName == null || dbName.isBlank()) return 0;
        DatabasePartition partition = findPartition(dbName);
        if (partition != null) {
            Map<String, Integer> counts = partition.getEngineCounts();
            if (counts != null && engineName != null) {
                Integer c = counts.get(engineName.toUpperCase());
                if (c != null && c > 0) return c;
            }
            if ("ALL".equalsIgnoreCase(engineName) || engineName == null) {
                return partition.getTotalRecordCount();
            }
        }
        return 0;
    }

    public int getDatabaseRecordCount(String dbName) {
        if (dbName == null || dbName.isBlank()) return 0;
        DatabasePartition partition = findPartition(dbName);
        return partition != null ? partition.getTotalRecordCount() : 0;
    }

    public Map<String, Integer> getDatabaseEngineCounts(String dbName) {
        if (dbName == null || dbName.isBlank()) return Collections.emptyMap();
        DatabasePartition partition = findPartition(dbName);
        if (partition == null) {
            partition = getPartition(dbName);
        }
        return partition != null ? partition.getEngineCounts() : Collections.emptyMap();
    }

    public List<RecordVersion> getVersionHistory(String key) {
        if (key == null) return Collections.emptyList();
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        return partition != null ? partition.getVersionHistory(key) : Collections.emptyList();
    }

    public int getVersionCount(String key) {
        if (key == null) return 0;
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        return partition != null ? partition.getVersionCount(key) : 0;
    }

    public byte[] getVersion(String key, long timestamp) {
        if (key == null) return null;
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        return partition != null ? partition.getVersion(key, timestamp) : null;
    }

    public boolean restoreVersion(String key, long timestamp) {
        if (key == null) return false;
        String db = extractDatabaseFromKey(key);
        DatabasePartition partition = findPartition(db);
        return partition != null && partition.restoreVersion(key, timestamp);
    }

    public InternalCompactor.CompactionReport compactDatabase(String dbName, boolean major) {
        DatabasePartition partition = findPartition(dbName);
        if (partition != null) {
            return major ? partition.performMajorCompaction() : partition.performMinorCompaction();
        }
        return null;
    }

    public List<InternalCompactor.CompactionReport> compactAll(boolean major) {
        List<InternalCompactor.CompactionReport> reports = new java.util.ArrayList<>();
        for (DatabasePartition partition : partitions.values()) {
            reports.add(major ? partition.performMajorCompaction() : partition.performMinorCompaction());
        }
        return reports;
    }

    public Map<String, DatabasePartition> getPartitions() {
        return Collections.unmodifiableMap(partitions);
    }

    public void close() {
        for (DatabasePartition partition : partitions.values()) {
            partition.close();
        }
    }
}
