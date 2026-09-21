package com.jettra.store.engine.highperformance.integration;

import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.highperformance.graph.MicroSnapshotEngine;
import com.jettra.store.engine.highperformance.graph.NativeObjectGraphStore;
import com.jettra.store.engine.highperformance.paged.OffHeapPageCache;
import com.jettra.store.engine.highperformance.paged.PagedStorageEngine;
import com.jettra.store.engine.highperformance.paged.RecordId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * High-Performance Engine Bridge integrating ArcadeDB & Eclipse Store patterns
 * directly into JettraDB's orchestrator ecosystem.
 *
 * <p>Exposes:
 * <ul>
 *   <li>Physical Page Management & Off-Heap caching (Project Panama).</li>
 *   <li>Direct physical {@link RecordId} addressing.</li>
 *   <li>Zero-impedance native Object Graph persistence.</li>
 *   <li>Ultra-low latency Java Streams querying.</li>
 *   <li>Incremental Micro-Snapshots with Java 25 Virtual Threads.</li>
 * </ul>
 */
public class HighPerformanceEngineBridge implements EngineFamily, AutoCloseable {

    public static final String ENGINE_NAME = "HIGH_PERFORMANCE";
    public static final int DEFAULT_MAX_OFF_HEAP_PAGES = 1024; // 64 MB of off-heap cache

    private final JettraStorageEngine orchestrator;
    private final Path highPerformanceDir;
    private final int maxOffHeapPages;
    private PagedStorageEngine pagedStorage;
    private MicroSnapshotEngine snapshotEngine;
    private final ConcurrentHashMap<String, NativeObjectGraphStore> graphStores;
    private volatile boolean initialized = false;

    public HighPerformanceEngineBridge(JettraStorageEngine orchestrator) {
        this(orchestrator, DEFAULT_MAX_OFF_HEAP_PAGES);
    }

    public HighPerformanceEngineBridge(JettraStorageEngine orchestrator, int maxOffHeapPages) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator cannot be null");
        this.highPerformanceDir = orchestrator.getStorageDir().resolve("high_performance");
        this.maxOffHeapPages = maxOffHeapPages;
        this.graphStores = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return ENGINE_NAME;
    }

    @Override
    public synchronized void init() {
        if (initialized) return;
        System.out.println("Initializing High-Performance Engine Bridge (Eclipse Store & ArcadeDB patterns)...");

        Path pagedDir = highPerformanceDir.resolve("pages");
        Path snapsDir = highPerformanceDir.resolve("snapshots");

        this.pagedStorage = new PagedStorageEngine(pagedDir, maxOffHeapPages);
        this.snapshotEngine = new MicroSnapshotEngine(snapsDir, pagedStorage);
        this.initialized = true;

        System.out.println("High-Performance Engine initialized successfully with " 
                + maxOffHeapPages + " off-heap pages (" + (maxOffHeapPages * 64 / 1024) + " MB).");
    }

    /**
     * Obtains or creates a named native object graph store.
     */
    public NativeObjectGraphStore getGraphStore(String namespace) {
        ensureInitialized();
        return graphStores.computeIfAbsent(namespace, ns -> {
            int fileId = Math.abs(ns.hashCode() % 1000) + 1;
            return new NativeObjectGraphStore(fileId, pagedStorage, snapshotEngine);
        });
    }

    /**
     * Directly persists an object into the default graph store and returns its RecordId.
     */
    public RecordId persist(String namespace, String id, Object entity) throws IOException {
        return getGraphStore(namespace).store(id, entity);
    }

    /**
     * Resolves an entity by ID from the specified namespace.
     */
    public <T> T fetch(String namespace, String id) {
        return getGraphStore(namespace).get(id);
    }

    /**
     * Directly fetches an entity via its physical {@link RecordId}.
     */
    public <T> T fetchByRid(String namespace, RecordId rid) {
        return getGraphStore(namespace).getByRid(rid);
    }

    /**
     * Executes a native Java Stream query against in-memory objects in the given namespace.
     */
    public <T> Stream<T> stream(String namespace, Class<T> type) {
        return getGraphStore(namespace).query(type);
    }

    /**
     * Triggers an incremental micro-snapshot.
     */
    public MicroSnapshotEngine.SnapshotMetadata triggerSnapshot(String description) throws IOException {
        ensureInitialized();
        return snapshotEngine.createMicroSnapshot(description);
    }

    public PagedStorageEngine getPagedStorage() {
        ensureInitialized();
        return pagedStorage;
    }

    public OffHeapPageCache getPageCache() {
        ensureInitialized();
        return pagedStorage.getPageCache();
    }

    public MicroSnapshotEngine getSnapshotEngine() {
        ensureInitialized();
        return snapshotEngine;
    }

    private void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    @Override
    public synchronized void close() {
        if (!initialized) return;
        System.out.println("Closing High-Performance Engine Bridge...");
        for (NativeObjectGraphStore store : graphStores.values()) {
            try {
                // Stores share the pagedStorage and snapshotEngine
            } catch (Exception ignored) {}
        }
        graphStores.clear();
        try {
            if (snapshotEngine != null) {
                snapshotEngine.close();
            }
            if (pagedStorage != null) {
                pagedStorage.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing High-Performance Engine: " + e.getMessage());
        }
        initialized = false;
    }
}
