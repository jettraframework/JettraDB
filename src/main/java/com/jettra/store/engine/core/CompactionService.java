package com.jettra.store.engine.core;

import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.generational.InternalCompactor.CompactionReport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service responsible for orchestrating background and on-demand Generational LSM Tree Compaction.
 * <p>
 * Performs two tiers of internal optimization:
 * <ol>
 *   <li><b>Minor Compaction:</b> Rapidly purges tombstones from Young Areas, promotes mature records
 *       to Old Area, and recycles off-heap Panama Arena blocks.</li>
 *   <li><b>Major Compaction:</b> Defragments physical data_0.jettra storage files, strips deleted records,
 *       rebuilds contiguous disk indexes, and truncates WAL logs.</li>
 * </ol>
 * In a distributed 3-node cluster, the Primary node coordinates compaction and propagates
 * compaction sync signals to the Secondary nodes.
 */
public class CompactionService implements Runnable {

    private final LsmBTreeHybrid storage;
    private final JettraConsensusClient consensusClient;
    private volatile boolean running = false;
    private Thread workerThread;
    private final long minorCompactionIntervalMs;
    private final long majorCompactionIntervalMs;

    private final AtomicInteger totalCompactionRuns = new AtomicInteger(0);
    private final AtomicLong totalBytesReclaimed = new AtomicLong(0);
    private final AtomicInteger totalTombstonesPurged = new AtomicInteger(0);
    private final List<CompactionReport> recentReports = new CopyOnWriteArrayList<>();

    public CompactionService(LsmBTreeHybrid storage) {
        this(storage, 15000, 3600000); // 15 sec minor, 1 hour major
    }

    public CompactionService(LsmBTreeHybrid storage, long minorIntervalMs, long majorIntervalMs) {
        this.storage = storage;
        this.consensusClient = new JettraConsensusClient();
        this.minorCompactionIntervalMs = minorIntervalMs > 0 ? minorIntervalMs : 15000;
        this.majorCompactionIntervalMs = majorIntervalMs > 0 ? majorIntervalMs : 3600000;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            consensusClient.init();
        } catch (Exception ignored) {}

        workerThread = Thread.ofVirtual().name("Jettra-CompactionWorker").start(this);
        System.out.println("[CompactionService] Generational Compaction Service started (Minor: "
                + (minorCompactionIntervalMs / 1000) + "s, Major: " + (majorCompactionIntervalMs / 1000) + "s).");
    }

    @Override
    public void run() {
        long lastMajorRun = System.currentTimeMillis();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(minorCompactionIntervalMs);
                if (!running) break;

                // Run minor compaction across all database partitions
                performMinorCompaction();

                // Run major compaction if threshold interval reached
                long now = System.currentTimeMillis();
                if (now - lastMajorRun >= majorCompactionIntervalMs) {
                    performMajorCompaction();
                    lastMajorRun = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[CompactionService] Background compaction cycle warning: " + e.getMessage());
            }
        }
    }

    /**
     * Executes Minor Compaction on all active database partitions.
     */
    public List<CompactionReport> performMinorCompaction() {
        if (storage == null) return List.of();
        List<CompactionReport> reports = storage.compactAll(false);
        recordReports(reports);
        return reports;
    }

    /**
     * Executes Major Compaction on all active database partitions and defragments storage files.
     */
    public List<CompactionReport> performMajorCompaction() {
        if (storage == null) return List.of();
        System.out.println("[CompactionService] Starting Major Compaction across all database partitions...");
        List<CompactionReport> reports = storage.compactAll(true);
        recordReports(reports);

        // In 3-node cluster, broadcast compaction event to secondaries
        if (consensusClient != null) {
            try {
                for (CompactionReport r : reports) {
                    consensusClient.sendCommand("COMPACT " + r.dbName());
                }
            } catch (Exception ignored) {}
        }

        System.out.println("[CompactionService] Major Compaction complete across " + reports.size() + " partitions.");
        return reports;
    }

    /**
     * Programmatic alias to perform a full compaction cycle.
     */
    public void performCompaction() {
        performMajorCompaction();
    }

    /**
     * Performs compaction on a specific database.
     */
    public CompactionReport compactDatabase(String dbName, boolean major) {
        if (storage == null || dbName == null) return null;
        CompactionReport report = storage.compactDatabase(dbName, major);
        if (report != null) {
            recordReports(List.of(report));
            if (major && consensusClient != null) {
                try {
                    consensusClient.sendCommand("COMPACT " + dbName);
                } catch (Exception ignored) {}
            }
        }
        return report;
    }

    private void recordReports(List<CompactionReport> reports) {
        if (reports == null) return;
        for (CompactionReport r : reports) {
            if (r != null) {
                totalCompactionRuns.incrementAndGet();
                totalBytesReclaimed.addAndGet(r.bytesReclaimed());
                totalTombstonesPurged.addAndGet(r.tombstonesPurged());
                recentReports.add(r);
                while (recentReports.size() > 50) {
                    recentReports.remove(0);
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (consensusClient != null) {
            try {
                consensusClient.close();
            } catch (Exception ignored) {}
        }
        System.out.println("[CompactionService] Compaction Service stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getTotalCompactionRuns() {
        return totalCompactionRuns.get();
    }

    public long getTotalBytesReclaimed() {
        return totalBytesReclaimed.get();
    }

    public int getTotalTombstonesPurged() {
        return totalTombstonesPurged.get();
    }

    public List<CompactionReport> getRecentReports() {
        return List.copyOf(recentReports);
    }
}
