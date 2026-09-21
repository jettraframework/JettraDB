package com.jettra.store.engine.highperformance.graph;

import com.jettra.store.engine.highperformance.paged.PagedStorageEngine;
import com.jettra.store.engine.highperformance.paged.RecordId;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.Test;
import static io.jettra.test.core.JettraAssert.*;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@io.jettra.test.annotation.NotRequiresRunningServer
public class NativeObjectGraphStoreTest {

    public record Customer(String id, String name, int score) implements Serializable {}
    public record Order(String orderId, double amount, String status) implements Serializable {}

    private Path tempDir;
    private PagedStorageEngine pagedStorage;
    private MicroSnapshotEngine snapshotEngine;
    private NativeObjectGraphStore graphStore;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_graph_store_test_");
        Path pagedDir = tempDir.resolve("pages");
        Path snapsDir = tempDir.resolve("snaps");

        pagedStorage = new PagedStorageEngine(pagedDir, 16);
        snapshotEngine = new MicroSnapshotEngine(snapsDir, pagedStorage);
        graphStore = new NativeObjectGraphStore(1, pagedStorage, snapshotEngine);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (graphStore != null) {
            graphStore.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    public void testDirectObjectPersistenceAndRetrieval() throws IOException {
        Customer c1 = new Customer("c100", "Alice", 95);
        RecordId rid = graphStore.store("cust_100", c1);

        assertNotNull(rid);
        assertEquals(1, graphStore.size());

        // Retrieve by key
        Customer fetched = graphStore.get("cust_100");
        assertNotNull(fetched);
        assertEquals("Alice", fetched.name());
        assertEquals(95, fetched.score());

        // Retrieve directly by RecordId in O(1)
        Customer fetchedByRid = graphStore.getByRid(rid);
        assertNotNull(fetchedByRid);
        assertEquals("c100", fetchedByRid.id());
    }

    @Test
    public void testLazyReferenceEvictionAndReload() throws IOException {
        Customer c1 = new Customer("c200", "Bob", 88);
        RecordId rid = graphStore.store("cust_200", c1);

        NativeObjectGraphStore.GraphNode node = graphStore.getNode("cust_200");
        assertNotNull(node);
        assertTrue(node.lazyReference().isLoaded());

        // Evict from in-memory heap
        graphStore.evictEntitiesFromMemory();
        assertFalse(node.lazyReference().isLoaded());

        // Access again: should trigger on-demand lazy reload from physical page
        Customer reloaded = (Customer) node.getEntity();
        assertNotNull(reloaded);
        assertEquals("Bob", reloaded.name());
        assertTrue(node.lazyReference().isLoaded());
    }

    @Test
    public void testGraphRelationshipsAndDirectTraversal() throws IOException {
        Customer alice = new Customer("u1", "Alice", 100);
        Order o1 = new Order("ord_01", 150.50, "COMPLETED");
        Order o2 = new Order("ord_02", 79.99, "PENDING");

        graphStore.store("u1", alice);
        graphStore.store("ord_01", o1);
        graphStore.store("ord_02", o2);

        // Link Customer -> Orders via direct RecordId edge pointers
        graphStore.link("u1", "ord_01");
        graphStore.link("u1", "ord_02");

        // Traverse neighbors directly
        List<Object> neighbors = graphStore.getNeighbors("u1");
        assertEquals(2, neighbors.size());
        assertTrue(neighbors.contains(o1));
        assertTrue(neighbors.contains(o2));
    }

    @Test
    public void testNativeJavaStreamsQuerying() throws IOException {
        graphStore.store("c1", new Customer("c1", "Alice", 90));
        graphStore.store("c2", new Customer("c2", "Bob", 60));
        graphStore.store("c3", new Customer("c3", "Charlie", 85));
        graphStore.store("o1", new Order("o1", 50.0, "COMPLETED"));

        // Query customers using native Java Streams and filters
        List<Customer> topCustomers = graphStore.query(Customer.class, c -> c.score() >= 80)
                .sorted(Comparator.comparingInt(Customer::score).reversed())
                .toList();

        assertEquals(2, topCustomers.size());
        assertEquals("Alice", topCustomers.get(0).name());
        assertEquals("Charlie", topCustomers.get(1).name());

        // Query orders
        List<Order> orders = graphStore.query(Order.class).toList();
        assertEquals(1, orders.size());
        assertEquals(50.0, orders.get(0).amount());
    }

    @Test
    public void testMicroSnapshotsSyncAndAsync() throws Exception {
        graphStore.store("c1", new Customer("c1", "Alice", 90));
        graphStore.store("c2", new Customer("c2", "Bob", 60));

        assertEquals(2, snapshotEngine.getPendingDirtyCount());

        // Synchronous micro-snapshot
        MicroSnapshotEngine.SnapshotMetadata meta1 = snapshotEngine.createMicroSnapshot("Initial snapshot");
        assertEquals(1L, meta1.snapshotId());
        assertEquals(2, meta1.deltaRecordCount());
        assertEquals(0, snapshotEngine.getPendingDirtyCount());

        // Asynchronous micro-snapshot via Java 25 Virtual Thread
        graphStore.store("c3", new Customer("c3", "Charlie", 99));
        CompletableFuture<MicroSnapshotEngine.SnapshotMetadata> asyncFuture =
                snapshotEngine.createMicroSnapshotAsync("Async virtual thread snapshot");

        MicroSnapshotEngine.SnapshotMetadata meta2 = asyncFuture.get();
        assertEquals(2L, meta2.snapshotId());
        assertEquals(1, meta2.deltaRecordCount());
    }
}
