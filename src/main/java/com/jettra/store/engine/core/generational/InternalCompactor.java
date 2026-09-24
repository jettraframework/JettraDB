package com.jettra.store.engine.core.generational;

import com.jettra.store.engine.core.JettraFileManager;
import com.jettra.store.engine.core.storage.StorageRecordRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Internal Compaction Engine for JettraDB.
 * Implements two-phase generational compaction:
 * <ul>
 *   <li><b>Minor Compaction:</b> Rapidly purges tombstones in {@link YoungArea}, collapses dead versions,
 *       promotes mature records to {@link OldArea}, and recycles off-heap Panama {@link GenerationalArena} blocks.</li>
 *   <li><b>Major Compaction:</b> Defragments physical storage (data_0.jettra), purges tombstoned records
 *       and stale historical versions from disk, rebuilds contiguous disk index offsets, and truncates WAL logs.</li>
 * </ul>
 */
public class InternalCompactor {

    public enum CompactionType {
        MINOR,
        MAJOR
    }

    public record CompactionReport(
        String dbName,
        CompactionType type,
        int tombstonesPurged,
        int promotedCount,
        int activeRecordsRemaining,
        long bytesReclaimed,
        long durationMs
    ) {}

    private final ReentrantLock compactionLock = new ReentrantLock();

    /**
     * Executes a Minor Compaction on the Young Area.
     */
    public CompactionReport performMinorCompaction(YoungArea youngArea,
                                                  OldArea oldArea,
                                                  JettraFileManager fileManager,
                                                  StorageRecordRepository repo,
                                                  Path rootDir) {
        if (youngArea == null || oldArea == null) {
            return new CompactionReport("unknown", CompactionType.MINOR, 0, 0, 0, 0L, 0L);
        }

        long start = System.currentTimeMillis();
        int purged = 0;
        int promoted = 0;
        long bytesReclaimed = 0L;

        compactionLock.lock();
        try {
            List<String> keysToPurge = new ArrayList<>();
            List<GenerationalRecord> keysToPromote = new ArrayList<>();

            for (Map.Entry<String, GenerationalRecord> entry : youngArea.getRecords().entrySet()) {
                GenerationalRecord rec = entry.getValue();
                if (rec.isTombstone()) {
                    keysToPurge.add(entry.getKey());
                } else {
                    int age = rec.incrementTenureAge();
                    if (age >= youngArea.getTenureThreshold() || youngArea.isThresholdReached()) {
                        keysToPromote.add(rec);
                    }
                }
            }

            // 1. Purge tombstones
            for (String key : keysToPurge) {
                youngArea.remove(key);
                oldArea.delete(key, repo, rootDir);
                purged++;
            }

            // 2. Promote mature records to Old Area
            for (GenerationalRecord rec : keysToPromote) {
                try {
                    byte[] data = rec.resolveData(youngArea.getArena());
                    if (data != null && data.length > 0) {
                        rec.setHeapData(data);
                        oldArea.promote(rec, fileManager, repo, rootDir);
                        youngArea.remove(rec.getKey());
                        promoted++;
                    } else {
                        youngArea.remove(rec.getKey());
                        purged++;
                    }
                } catch (Exception e) {
                    System.err.println("[InternalCompactor] Warning promoting key [" + rec.getKey() + "]: " + e.getMessage());
                }
            }

            // 3. If Young Area is now empty or low, recycle the off-heap Arena
            if (youngArea.isEmpty()) {
                bytesReclaimed = youngArea.getArena().getAllocatedBytes();
                youngArea.getArena().reset();
            }

            if (fileManager != null) {
                try {
                    fileManager.force();
                } catch (Exception ignored) {}
            }

            long elapsed = System.currentTimeMillis() - start;
            return new CompactionReport(youngArea.getDbName(), CompactionType.MINOR, purged, promoted,
                    youngArea.size() + oldArea.size(), bytesReclaimed, elapsed);
        } finally {
            compactionLock.unlock();
        }
    }

    /**
     * Executes a Major Compaction on the Old Area and physical disk SSTable (data_0.jettra).
     */
    public CompactionReport performMajorCompaction(YoungArea youngArea,
                                                  OldArea oldArea,
                                                  JettraFileManager[] fileManagerHolder,
                                                  StorageRecordRepository repo,
                                                  Path rootDir,
                                                  Path dbDirectory,
                                                  Path walFile) {
        if (dbDirectory == null || oldArea == null) {
            return new CompactionReport("unknown", CompactionType.MAJOR, 0, 0, 0, 0L, 0L);
        }

        long start = System.currentTimeMillis();
        compactionLock.lock();
        try {
            // First flush young records into old area
            if (youngArea != null && !youngArea.isEmpty()) {
                performMinorCompaction(youngArea, oldArea, fileManagerHolder[0], repo, rootDir);
            }

            Path originalDataFile = dbDirectory.resolve("data_0.jettra");
            Path compactedDataFile = dbDirectory.resolve("data_0.jettra.compacted");
            long originalSize = Files.exists(originalDataFile) ? Files.size(originalDataFile) : 0L;

            Map<String, Long> newDiskIndex = new LinkedHashMap<>();
            int recordsWritten = 0;
            int purged = 0;

            if (Files.exists(originalDataFile)) {
                // Open new temporary compacted file
                JettraFileManager compactedManager = new JettraFileManager(compactedDataFile);

                for (Map.Entry<String, Long> entry : oldArea.getDiskIndex().entrySet()) {
                    String key = entry.getKey();
                    byte[] data = oldArea.get(key, fileManagerHolder[0], repo, rootDir);

                    // Skip tombstones or corrupted empty bytes
                    if (data == null || data.length == 0) {
                        purged++;
                        continue;
                    }

                    String checkStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    if ("__TOMBSTONE__".equals(checkStr.trim())) {
                        purged++;
                        continue;
                    }

                    long newOffset = compactedManager.append(data, false);
                    newDiskIndex.put(key, newOffset);
                    recordsWritten++;
                }

                compactedManager.force();
                compactedManager.close();

                // Close original file manager
                if (fileManagerHolder[0] != null) {
                    fileManagerHolder[0].close();
                }

                // Atomically replace data_0.jettra with compacted version
                try {
                    Files.move(compactedDataFile, originalDataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(compactedDataFile, originalDataFile, StandardCopyOption.REPLACE_EXISTING);
                }

                // Re-open active file manager
                fileManagerHolder[0] = new JettraFileManager(originalDataFile);
                oldArea.rebuildIndex(newDiskIndex);
            }

            long newSize = Files.exists(originalDataFile) ? Files.size(originalDataFile) : 0L;
            long bytesReclaimed = Math.max(0L, originalSize - newSize);

            // Safely rotate / truncate WAL since all active data is safely committed into compacted data_0.jettra
            if (walFile != null && Files.exists(walFile) && Files.size(walFile) > 0) {
                try {
                    bytesReclaimed += Files.size(walFile);
                    Files.write(walFile, new byte[0]); // Truncate WAL
                } catch (Exception ignored) {}
            }

            long elapsed = System.currentTimeMillis() - start;
            return new CompactionReport(oldArea.getDbName(), CompactionType.MAJOR, purged, recordsWritten,
                    recordsWritten, bytesReclaimed, elapsed);
        } catch (Exception e) {
            System.err.println("[InternalCompactor] Error during major compaction for db [" + oldArea.getDbName() + "]: " + e.getMessage());
            return new CompactionReport(oldArea.getDbName(), CompactionType.MAJOR, 0, 0, oldArea.size(), 0L, System.currentTimeMillis() - start);
        } finally {
            compactionLock.unlock();
        }
    }
}
