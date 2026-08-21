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
    // In-memory MemTable for fast writes
    private final ConcurrentSkipListMap<String, byte[]> memTable;
    // In-memory index of on-disk records (Key -> Offset)
    private final Map<String, Long> diskIndex;
    private JettraFileManager fileManager;
    private final int FLUSH_THRESHOLD = 1000; // items
    
    public LsmBTreeHybrid(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.memTable = new ConcurrentSkipListMap<>();
        this.diskIndex = new ConcurrentHashMap<>();
        
        try {
            this.fileManager = new JettraFileManager(storageDirectory.resolve("data_0.jettra"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Inserts or updates a record.
     * Appends a new version rather than overwriting, allowing historical restorations.
     */
    public void put(String key, byte[] data, long timestamp) {
        // Construct a versioned key: "key@timestamp"
        String versionedKey = key + "@" + timestamp;
        memTable.put(versionedKey, data);
        
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
     * Deletes a record by writing a tombstone.
     */
    public void delete(String key, long timestamp) {
        put(key, new byte[0], timestamp);
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
    
    /**
     * Retrieves a specific historical version of a document.
     */
    public byte[] getVersion(String key, long timestamp) {
        String versionedKey = key + "@" + timestamp;
        if (memTable.containsKey(versionedKey)) {
            return memTable.get(versionedKey);
        }
        // TODO: Retrieve specific version from LSM levels / B-Tree blocks.
        return null;
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
