package com.jettra.store.engine.highperformance.graph;

import com.jettra.store.engine.highperformance.paged.PagedStorageEngine;
import com.jettra.store.engine.highperformance.paged.RecordId;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * In-memory Native Object Graph Store inspired by Eclipse Store & ArcadeDB.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li>Native Java object graph traversal and direct references.</li>
 *   <li>CPU-speed microsecond queries via standard {@link java.util.stream.Stream}.</li>
 *   <li>Zero impedance mismatch: direct binary serialization via {@link ObjectGraphSerializer}.</li>
 *   <li>Direct physical indexing via {@link RecordId}.</li>
 *   <li>Lazy loading of nested graph nodes with {@link LazyReference}.</li>
 *   <li>Incremental durability through {@link MicroSnapshotEngine}.</li>
 * </ul>
 */
public class NativeObjectGraphStore implements AutoCloseable {

    public record GraphNode(
            String id,
            RecordId recordId,
            LazyReference<Object> lazyReference,
            Set<RecordId> outgoingEdges,
            String label
    ) {
        public Object getEntity() {
            return lazyReference != null ? lazyReference.get() : null;
        }
    }

    private final int defaultFileId;
    private final PagedStorageEngine pagedStorage;
    private final ObjectGraphSerializer serializer;
    private final MicroSnapshotEngine snapshotEngine;

    // In-memory graph nodes index
    private final Map<String, GraphNode> nodesById;
    private final Map<RecordId, GraphNode> nodesByRid;

    public NativeObjectGraphStore(int defaultFileId, PagedStorageEngine pagedStorage, MicroSnapshotEngine snapshotEngine) {
        this.defaultFileId = defaultFileId;
        this.pagedStorage = pagedStorage;
        this.snapshotEngine = snapshotEngine;
        this.serializer = new ObjectGraphSerializer();
        this.nodesById = new ConcurrentHashMap<>();
        this.nodesByRid = new ConcurrentHashMap<>();
    }

    /**
     * Persists an object entity into the graph store.
     *
     * @param id Unique entity ID in the graph.
     * @param entity Java native object.
     * @param label Optional category/class label.
     * @return Direct physical RecordId.
     */
    public synchronized RecordId store(String id, Object entity, String label) throws IOException {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(entity, "entity cannot be null");

        // Direct binary serialization without ORM or JSON conversion
        byte[] payload = serializer.serialize(entity);

        // Store into physical 64KB page and obtain direct RecordId
        RecordId rid = pagedStorage.writeRecord(defaultFileId, payload);

        // Track for incremental micro-snapshot
        if (snapshotEngine != null) {
            snapshotEngine.trackDirty(id, rid);
        }

        // Create lazy reference with resolver backing
        LazyReference<Object> lazyRef = LazyReference.ofResolved(rid, entity, targetRid -> {
            try {
                byte[] raw = pagedStorage.readRecord(targetRid);
                return serializer.deserialize(raw);
            } catch (Exception e) {
                throw new RuntimeException("Failed to lazily resolve RecordId: " + targetRid, e);
            }
        });

        GraphNode node = new GraphNode(id, rid, lazyRef, ConcurrentHashMap.newKeySet(), label != null ? label : "Entity");
        nodesById.put(id, node);
        nodesByRid.put(rid, node);

        return rid;
    }

    /**
     * Stores an object using its simple class name as the label.
     */
    public RecordId store(String id, Object entity) throws IOException {
        return store(id, entity, entity != null ? entity.getClass().getSimpleName() : "Object");
    }

    /**
     * Retrieves an entity by its string ID.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String id) {
        GraphNode node = nodesById.get(id);
        if (node != null) {
            return (T) node.getEntity();
        }
        return null;
    }

    /**
     * Retrieves an entity directly by its physical {@link RecordId} in O(1) time.
     */
    @SuppressWarnings("unchecked")
    public <T> T getByRid(RecordId rid) {
        GraphNode node = nodesByRid.get(rid);
        if (node != null) {
            return (T) node.getEntity();
        }
        // Direct read from paged storage if not in memory
        try {
            byte[] raw = pagedStorage.readRecord(rid);
            if (raw != null) {
                return (T) serializer.deserialize(raw);
            }
        } catch (Exception e) {
            System.err.println("Error reading direct RID " + rid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Creates a direct physical edge relationship between two graph nodes.
     * Stored as a direct pointer without requiring index lookup during traversal.
     */
    public synchronized void link(String fromId, String toId) {
        GraphNode fromNode = nodesById.get(fromId);
        GraphNode toNode = nodesById.get(toId);
        if (fromNode == null || toNode == null) {
            throw new IllegalArgumentException("Both source (" + fromId + ") and target (" + toId + ") must exist");
        }
        fromNode.outgoingEdges().add(toNode.recordId());
    }

    /**
     * Traverses outgoing neighbors of a node using direct RecordId pointers.
     */
    public List<Object> getNeighbors(String nodeId) {
        GraphNode node = nodesById.get(nodeId);
        if (node == null || node.outgoingEdges().isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> neighbors = new ArrayList<>();
        for (RecordId rid : node.outgoingEdges()) {
            Object entity = getByRid(rid);
            if (entity != null) {
                neighbors.add(entity);
            }
        }
        return neighbors;
    }

    /**
     * Native query using Java Streams at CPU memory speeds.
     *
     * @param type Target entity type class.
     * @param <T> Entity type.
     * @return Stream of entities matching the requested type.
     */
    @SuppressWarnings("unchecked")
    public <T> Stream<T> query(Class<T> type) {
        return nodesById.values().stream()
                .map(GraphNode::getEntity)
                .filter(Objects::nonNull)
                .filter(type::isInstance)
                .map(obj -> (T) obj);
    }

    /**
     * Native query with predicate filtering using Java Streams.
     */
    public <T> Stream<T> query(Class<T> type, Predicate<T> filter) {
        return query(type).filter(filter);
    }

    /**
     * Clears cached in-memory entities for memory eviction while preserving physical RecordIds.
     */
    public void evictEntitiesFromMemory() {
        for (GraphNode node : nodesById.values()) {
            if (node.lazyReference() != null) {
                node.lazyReference().evict();
            }
        }
    }

    public int size() {
        return nodesById.size();
    }

    public GraphNode getNode(String id) {
        return nodesById.get(id);
    }

    public GraphNode getNodeByRid(RecordId rid) {
        return nodesByRid.get(rid);
    }

    public PagedStorageEngine getPagedStorage() {
        return pagedStorage;
    }

    public MicroSnapshotEngine getSnapshotEngine() {
        return snapshotEngine;
    }

    @Override
    public void close() throws IOException {
        if (snapshotEngine != null) {
            snapshotEngine.close();
        }
        pagedStorage.close();
    }
}
