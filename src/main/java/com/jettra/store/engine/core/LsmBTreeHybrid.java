package com.jettra.store.engine.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * LsmBTreeHybrid: A hybrid storage structure combining the write-optimized 
 * nature of Log-Structured Merge-trees (LSM) with the read-optimized 
 * indexing of B-Trees.
 *
 * - MemTable (In-Memory): Uses a ConcurrentSkipListMap (LSM approach) for fast in-memory writes.
 * - SSTables (Disk): Flushed as immutable B-Tree structures within the .jettra files 
 *   to allow fast range scans and pagination.
 * - Versioning: Each key is appended with a timestamp/version to support 
 *   document history and point-in-time restoration.
 */
public class LsmBTreeHybrid {
    
    private final Path storageDirectory;
    private final Path journalFile;
    // In-memory MemTable for fast writes
    private final ConcurrentSkipListMap<String, byte[]> memTable;
    // In-memory index of on-disk records (Key -> Offset)
    private final Map<String, Long> diskIndex;
    private JettraFileManager fileManager;
    private final int FLUSH_THRESHOLD = 1000; // items
    
    public LsmBTreeHybrid(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.journalFile = storageDirectory.resolve("jettra_storage_wal.jettra");
        this.memTable = new ConcurrentSkipListMap<>();
        this.diskIndex = new ConcurrentHashMap<>();
        
        try {
            if (!java.nio.file.Files.exists(storageDirectory)) {
                java.nio.file.Files.createDirectories(storageDirectory);
            }
            this.fileManager = new JettraFileManager(storageDirectory.resolve("data_0.jettra"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        loadFromWal();
    }

    private void loadFromWal() {
        if (!java.nio.file.Files.exists(journalFile)) {
            return;
        }
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(journalFile)))) {
            while (dis.available() > 0) {
                String key = dis.readUTF();
                long ts = dis.readLong();
                int len = dis.readInt();
                byte[] data = new byte[len];
                if (len > 0) {
                    dis.readFully(data);
                    memTable.put(key + "@" + ts, data);
                } else {
                    // Tombstone deletion: purge any prior entries for this key
                    java.util.List<String> toRemove = new java.util.ArrayList<>();
                    for (String k : memTable.keySet()) {
                        if (k.equals(key) || k.startsWith(key + "@")) {
                            toRemove.add(k);
                        }
                    }
                    for (String k : toRemove) {
                        memTable.remove(k);
                    }
                    memTable.put(key + "@" + ts, new byte[0]);
                }
            }
            System.out.println("Restored " + memTable.size() + " versioned records from persistent storage at " + journalFile);
        } catch (java.io.EOFException eof) {
            // End of file reached
        } catch (IOException e) {
            System.err.println("Warning while loading persistent storage WAL: " + e.getMessage());
        }
    }

    private synchronized void appendWal(String key, long ts, byte[] data) {
        try {
            if (!java.nio.file.Files.exists(storageDirectory)) {
                java.nio.file.Files.createDirectories(storageDirectory);
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
            System.err.println("Error writing to persistent storage WAL: " + e.getMessage());
        }
    }

    /**
     * Inserts or updates a record.
     * Appends a new version only when the content has actually changed or is new,
     * avoiding duplicate versions on identical concurrent writes or Raft loopbacks.
     */
    public void put(String key, byte[] data, long timestamp) {
        if (key == null || data == null) return;

        // Prevent duplicate version if latest version already has identical data
        byte[] latest = get(key);
        if (latest != null && java.util.Arrays.equals(latest, data)) {
            return;
        }

        // Construct a versioned key: "key@timestamp"
        String versionedKey = key + "@" + timestamp;
        memTable.put(versionedKey, data);
        appendWal(key, timestamp, data);
        
        // If exceeds threshold, flush to Disk as a B-Tree SSTable.
        if (memTable.size() >= FLUSH_THRESHOLD) {
            flushToBTree();
        }
    }

    /**
     * Retrieves the latest version of a record by key.
     */
    public byte[] get(String key) {
        // Search MemTable first (highest timestamp).
        String latestKey = memTable.floorKey(key + "@" + Long.MAX_VALUE);
        if (latestKey != null && latestKey.startsWith(key + "@")) {
            byte[] val = memTable.get(latestKey);
            return (val != null && val.length > 0) ? val : null;
        }
        
        // If not found, search the hierarchical B-Tree SSTables on disk.
        Long offset = diskIndex.get(key);
        if (offset != null) {
            try {
                byte[] val = fileManager.read(offset);
                return (val != null && val.length > 0) ? val : null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Deletes a record by writing a tombstone and removing prior versions.
     */
    public void delete(String key, long timestamp) {
        if (key == null) return;
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (String k : memTable.keySet()) {
            if (k.equals(key) || k.startsWith(key + "@")) {
                toRemove.add(k);
            }
        }
        for (String k : toRemove) {
            memTable.remove(k);
        }
        String versionedKey = key + "@" + timestamp;
        memTable.put(versionedKey, new byte[0]);
        appendWal(key, timestamp, new byte[0]);
        diskIndex.remove(key);
    }

    /**
     * Scans keys matching a given prefix from both MemTable and DiskIndex.
     */
    public Map<String, byte[]> scanPrefix(String prefix) {
        Map<String, byte[]> results = new java.util.LinkedHashMap<>();
        // 1. Scan memTable
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
        // 2. Scan diskIndex
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
    
    public record RecordVersion(
        int versionNumber,
        long timestamp,
        String formattedDate,
        byte[] data,
        String payload,
        boolean isCurrent
    ) {}

    /**
     * Retrieves all historical versions of a record by key, ordered in descending order (latest version first).
     * Version numbers start at v1 for the oldest record and increase incrementally up to vN for the latest.
     */
    public java.util.List<RecordVersion> getVersionHistory(String key) {
        java.util.List<RecordVersion> versions = new java.util.ArrayList<>();
        String prefix = key + "@";
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        // Scan memTable for all timestamped versions of this key (sorted ascending by timestamp)
        java.util.Map<Long, byte[]> timeMap = new java.util.TreeMap<>();
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

        // Also check if diskIndex has a record not in memTable
        if (timeMap.isEmpty() && diskIndex.containsKey(key)) {
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

        // 1. Build chronological list: v1 (oldest), v2, ..., vN (current)
        java.util.List<RecordVersion> chronological = new java.util.ArrayList<>();
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

        // 2. Return list in strictly DESCENDING order: [vN (current), ..., v2, v1]
        for (int i = chronological.size() - 1; i >= 0; i--) {
            versions.add(chronological.get(i));
        }

        return versions;
    }

    /**
     * Returns total version count for a key.
     */
    public int getVersionCount(String key) {
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

    /**
     * Retrieves a specific historical version of a document.
     */
    public byte[] getVersion(String key, long timestamp) {
        String versionedKey = key + "@" + timestamp;
        if (memTable.containsKey(versionedKey)) {
            return memTable.get(versionedKey);
        }
        return null;
    }

    /**
     * Restores a record to a specific historical version by appending a new current version.
     */
    public boolean restoreVersion(String key, long timestamp) {
        byte[] historicalData = getVersion(key, timestamp);
        if (historicalData != null && historicalData.length > 0) {
            put(key, historicalData, System.currentTimeMillis());
            return true;
        }
        return false;
    }

    /**
     * Flushes the current MemTable to an on-disk B-Tree structured SSTable (.jettra format).
     */
    private synchronized void flushToBTree() {
        System.out.println("Flushing MemTable to B-Tree SSTable on disk...");
        try {
            for (Map.Entry<String, byte[]> entry : memTable.entrySet()) {
                long offset = fileManager.append(entry.getValue());
                // Simplified Index mapping base key to offset
                String baseKey = entry.getKey().substring(0, entry.getKey().lastIndexOf('@'));
                diskIndex.put(baseKey, offset);
            }
            memTable.clear();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void close() {
        try {
            flushToBTree();
            if (fileManager != null) {
                fileManager.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
