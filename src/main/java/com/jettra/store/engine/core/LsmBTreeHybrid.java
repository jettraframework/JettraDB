package com.jettra.store.engine.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

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
        private final Path dbDirectory;
        private final Path journalFile;
        private final ConcurrentSkipListMap<String, byte[]> memTable;
        private final Map<String, Long> diskIndex;
        private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, byte[]>> versionHistory;
        private JettraFileManager fileManager;
        private final int FLUSH_THRESHOLD = 1000;

        public DatabasePartition(Path rootStorageDir, String dbName) {
            this.dbName = (dbName != null && !dbName.isBlank()) ? dbName.trim() : "_system";
            if ("_system".equalsIgnoreCase(this.dbName)) {
                this.dbDirectory = rootStorageDir.resolve("system");
            } else {
                this.dbDirectory = rootStorageDir.resolve("databases").resolve(this.dbName);
            }
            this.journalFile = this.dbDirectory.resolve("wal.jettra");
            this.memTable = new ConcurrentSkipListMap<>();
            this.diskIndex = new ConcurrentHashMap<>();
            this.versionHistory = new ConcurrentHashMap<>();

            try {
                if (!Files.exists(this.dbDirectory)) {
                    Files.createDirectories(this.dbDirectory);
                }
                this.fileManager = new JettraFileManager(this.dbDirectory.resolve("data_0.jettra"));
            } catch (IOException e) {
                System.err.println("Error initializing file manager for database partition [" + this.dbName + "]: " + e.getMessage());
            }

            loadFromWal();
        }

        public String getDbName() {
            return dbName;
        }

        public Path getDbDirectory() {
            return dbDirectory;
        }

        private void loadFromWal() {
            if (!Files.exists(journalFile)) {
                return;
            }
            try (java.io.DataInputStream dis = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(journalFile)))) {
                while (dis.available() > 0) {
                    String key = dis.readUTF();
                    long ts = dis.readLong();
                    int len = dis.readInt();
                    byte[] data = new byte[len];
                    if (len > 0) {
                        dis.readFully(data);
                        memTable.put(key + "@" + ts, data);
                        versionHistory.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>()).put(ts, data);
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
                    }
                }
            } catch (java.io.EOFException ignored) {
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
            long effectiveTs = timestamp;
            if (!history.isEmpty() && history.lastKey() >= effectiveTs) {
                effectiveTs = history.lastKey() + 1;
            }
            history.put(effectiveTs, data);

            String versionedKey = key + "@" + effectiveTs;
            memTable.put(versionedKey, data);
            appendWal(key, effectiveTs, data);

            if (memTable.size() >= FLUSH_THRESHOLD) {
                flushToBTree();
            }
        }

        public byte[] get(String key) {
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

            Long offset = diskIndex.get(key);
            if (offset != null && fileManager != null) {
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
        }

        public Map<String, byte[]> scanPrefix(String prefix) {
            Map<String, byte[]> results = new LinkedHashMap<>();
            for (String k : memTable.keySet()) {
                if (k.startsWith(prefix)) {
                    String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                    if (!results.containsKey(baseKey)) {
                        byte[] val = get(baseKey);
                        if (val != null && val.length > 0) {
                            results.put(baseKey, val);
                        }
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
            for (String k : memTable.keySet()) {
                if (k.startsWith(prefix)) {
                    byte[] v = memTable.get(k);
                    if (v != null && v.length > 0) count++;
                }
            }
            if (count == 0 && get(key) != null) count = 1;
            return Math.max(1, count);
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
            if (fileManager == null || memTable.isEmpty()) return;
            try {
                for (Map.Entry<String, byte[]> entry : memTable.entrySet()) {
                    long offset = fileManager.append(entry.getValue());
                    String k = entry.getKey();
                    String baseKey = k.contains("@") ? k.substring(0, k.lastIndexOf('@')) : k;
                    diskIndex.put(baseKey, offset);
                }
                memTable.clear();
            } catch (IOException e) {
                System.err.println("Error flushing MemTable for database [" + dbName + "]: " + e.getMessage());
            }
        }

        public void drop() {
            try {
                if (fileManager != null) {
                    fileManager.close();
                }
            } catch (IOException ignored) {}
            memTable.clear();
            diskIndex.clear();
            versionHistory.clear();
            deleteDirectoryRecursively(dbDirectory);
        }

        public void close() {
            try {
                flushToBTree();
                if (fileManager != null) {
                    fileManager.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing database partition [" + dbName + "]: " + e.getMessage());
            }
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

        // Initialize system partition
        getPartition("_system");

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
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(legacyWal)))) {
            while (dis.available() > 0) {
                String key = dis.readUTF();
                long ts = dis.readLong();
                int len = dis.readInt();
                byte[] data = new byte[len];
                if (len > 0) {
                    dis.readFully(data);
                    String db = extractDatabaseFromKey(key);
                    getPartition(db).put(key, data, ts);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Extracts the target database name from any engine key structure.
     * Supports standard prefixes (rec:, doc:, geo:, vec:, obj:, kv:, ts:, graph:, col:, schema:, idx:),
     * direct database keys (e.g. ExampleDBReferences:id), and system keys.
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
                    || pfx.equals("obj") || pfx.equals("kv") || pfx.equals("ts") || pfx.equals("graph")
                    || pfx.equals("col") || pfx.equals("schema") || pfx.equals("idx")) {
                String rest = key.substring(firstColon + 1);
                if (rest.isBlank()) {
                    return "_system"; // generic prefix like "doc:"
                }
                int nextColon = rest.indexOf(':');
                String dbCandidate = (nextColon > 0) ? rest.substring(0, nextColon) : rest;
                return dbCandidate.isBlank() ? "_system" : dbCandidate;
            } else {
                return key.substring(0, firstColon);
            }
        }
        return "_system";
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
        Set<String> dbs = new LinkedHashSet<>(partitions.keySet());
        dbs.remove("_system");
        Path dbRootDir = storageDirectory.resolve("databases");
        if (Files.exists(dbRootDir) && Files.isDirectory(dbRootDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbRootDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        dbs.add(entry.getFileName().toString());
                    }
                }
            } catch (IOException ignored) {}
        }
        return dbs;
    }

    public void dropDatabase(String dbName) {
        if (dbName == null || dbName.isBlank() || "_system".equalsIgnoreCase(dbName)) return;
        DatabasePartition partition = partitions.remove(dbName.trim());
        if (partition != null) {
            partition.drop();
        } else {
            Path dbDir = storageDirectory.resolve("databases").resolve(dbName.trim());
            DatabasePartition.deleteDirectoryRecursively(dbDir);
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
        byte[] val = getPartition(db).get(key);
        if (val == null && !"_system".equals(db)) {
            val = getPartition("_system").get(key);
        }
        return val;
    }

    public void delete(String key, long timestamp) {
        if (key == null) return;
        String db = extractDatabaseFromKey(key);
        getPartition(db).delete(key, timestamp);
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
            results.putAll(getPartition(db).scanPrefix(prefix));
        } else {
            // Generic prefix (e.g. "doc:", "rec:") spanning across all database partitions
            for (DatabasePartition partition : partitions.values()) {
                results.putAll(partition.scanPrefix(prefix));
            }
        }
        return results;
    }

    public List<RecordVersion> getVersionHistory(String key) {
        if (key == null) return Collections.emptyList();
        String db = extractDatabaseFromKey(key);
        return getPartition(db).getVersionHistory(key);
    }

    public int getVersionCount(String key) {
        if (key == null) return 1;
        String db = extractDatabaseFromKey(key);
        return getPartition(db).getVersionCount(key);
    }

    public byte[] getVersion(String key, long timestamp) {
        if (key == null) return null;
        String db = extractDatabaseFromKey(key);
        return getPartition(db).getVersion(key, timestamp);
    }

    public boolean restoreVersion(String key, long timestamp) {
        if (key == null) return false;
        String db = extractDatabaseFromKey(key);
        return getPartition(db).restoreVersion(key, timestamp);
    }

    public void close() {
        for (DatabasePartition partition : partitions.values()) {
            partition.close();
        }
    }
}
