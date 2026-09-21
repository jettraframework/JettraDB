package com.jettra.store.engine.highperformance.graph;

import com.jettra.store.engine.highperformance.paged.PagedStorageEngine;
import com.jettra.store.engine.highperformance.paged.RecordId;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Micro-Snapshot Engine inspired by Eclipse Store incremental snapshot architecture.
 *
 * <p>Consolidates mutable object graph mutations into incremental micro-snapshots
 * without blocking read operations. Leverages Java 25 Virtual Threads for non-blocking
 * asynchronous I/O and ACID durability.
 */
public class MicroSnapshotEngine implements AutoCloseable {

    public record SnapshotMetadata(
            long snapshotId,
            long timestamp,
            int deltaRecordCount,
            String description
    ) implements Serializable {}

    private final Path snapshotDir;
    private final PagedStorageEngine pagedStorage;
    private final AtomicLong snapshotSequence;
    private final ConcurrentLinkedQueue<Map.Entry<String, RecordId>> dirtyQueue;
    private final ReentrantLock snapshotLock;
    private final ExecutorService asyncFlusher;
    private volatile boolean closed = false;

    public MicroSnapshotEngine(Path snapshotDir, PagedStorageEngine pagedStorage) {
        this.snapshotDir = snapshotDir;
        this.pagedStorage = pagedStorage;
        this.snapshotSequence = new AtomicLong(0);
        this.dirtyQueue = new ConcurrentLinkedQueue<>();
        this.snapshotLock = new ReentrantLock();

        // Use Java 25 Virtual Threads executor for micro-snapshot consolidation
        this.asyncFlusher = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("micro-snapshot-worker-", 1).factory()
        );

        try {
            if (!Files.exists(snapshotDir)) {
                Files.createDirectories(snapshotDir);
            }
            recoverSequence();
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize snapshot directory: " + snapshotDir, e);
        }
    }

    private void recoverSequence() {
        File[] files = snapshotDir.toFile().listFiles((d, name) -> name.startsWith("snap_") && name.endsWith(".meta"));
        long maxSeq = 0;
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                try {
                    String seqStr = name.substring("snap_".length(), name.lastIndexOf(".meta"));
                    long seq = Long.parseLong(seqStr);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (Exception ignored) {}
            }
        }
        this.snapshotSequence.set(maxSeq);
    }

    /**
     * Enqueues a dirty record mutation for the next incremental micro-snapshot.
     */
    public void trackDirty(String objectKey, RecordId rid) {
        dirtyQueue.offer(Map.entry(objectKey, rid));
    }

    /**
     * Creates an incremental micro-snapshot synchronously.
     */
    public SnapshotMetadata createMicroSnapshot(String description) throws IOException {
        snapshotLock.lock();
        try {
            // First flush physical paged storage
            pagedStorage.sync();

            long snapshotId = snapshotSequence.incrementAndGet();
            long timestamp = System.currentTimeMillis();
            int count = 0;

            Path snapFile = snapshotDir.resolve(String.format("snap_%08d.meta", snapshotId));
            Path dataFile = snapshotDir.resolve(String.format("snap_%08d.delta", snapshotId));

            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(dataFile)))) {
                
                Map.Entry<String, RecordId> entry;
                while ((entry = dirtyQueue.poll()) != null) {
                    dos.writeUTF(entry.getKey());
                    dos.writeInt(entry.getValue().fileId());
                    dos.writeLong(entry.getValue().pageIndex());
                    dos.writeInt(entry.getValue().offset());
                    count++;
                }
                dos.flush();
            }

            SnapshotMetadata meta = new SnapshotMetadata(snapshotId, timestamp, count, description);
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapFile)))) {
                oos.writeObject(meta);
                oos.flush();
            }

            return meta;
        } finally {
            snapshotLock.unlock();
        }
    }

    /**
     * Triggers a non-blocking incremental micro-snapshot executed on a Java 25 Virtual Thread.
     */
    public CompletableFuture<SnapshotMetadata> createMicroSnapshotAsync(String description) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return createMicroSnapshot(description);
            } catch (IOException e) {
                throw new CompletionException("Failed asynchronous micro-snapshot", e);
            }
        }, asyncFlusher);
    }

    public long getCurrentSnapshotSequence() {
        return snapshotSequence.get();
    }

    public int getPendingDirtyCount() {
        return dirtyQueue.size();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (!dirtyQueue.isEmpty()) {
            createMicroSnapshot("Final consolidation on close");
        }
        asyncFlusher.shutdown();
        try {
            if (!asyncFlusher.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncFlusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
