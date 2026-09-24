package com.jettra.store.engine.cluster;

import com.jettra.store.engine.cluster.ClusterNodeRegistry.ClusterNodeInfo;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.generational.GenerationalArena;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import com.jettra.store.engine.dashboard.DashboardMetricsCollector;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates Cloud-Native 3-Node Cluster Architecture (Primary - Secondary - Secondary)
 * and Generational Storage Telemetry integration.
 */
@NotRequiresRunningServer
public class ClusterThreeNodeTopologyTest {

    private Path tempDir;
    private JettraStorageEngine storageEngine;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_cluster_3node_test_");
        storageEngine = new JettraStorageEngine(tempDir.toString());
        storageEngine.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storageEngine != null) {
            storageEngine.stop();
        }
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                      });
            }
        }
    }

    @JettraTest
    void testThreeNodeClusterTopologyConfiguration() {
        ClusterNodeRegistry registry = ClusterNodeRegistry.getInstance();
        assertNotNull(registry, "ClusterNodeRegistry must be initialized");

        // 1. Verify Primary Node (node1)
        ClusterNodeInfo node1 = registry.getNode("node1");
        assertNotNull(node1, "node1 must be registered");
        assertEquals("primary", node1.metadata().get("role"), "node1 must be designated as PRIMARY");

        // 2. Verify Secondary Nodes (node2, node3)
        ClusterNodeInfo node2 = registry.getNode("node2");
        assertNotNull(node2, "node2 must be registered");
        assertEquals("secondary", node2.metadata().get("role"), "node2 must be designated as SECONDARY");

        ClusterNodeInfo node3 = registry.getNode("node3");
        assertNotNull(node3, "node3 must be registered");
        assertEquals("secondary", node3.metadata().get("role"), "node3 must be designated as SECONDARY");

        // 3. Verify Failover Routes between the 3 nodes
        List<String> node1Failovers = registry.getFailovers("node1");
        assertTrue(node1Failovers.contains("node2"), "node1 failovers must include node2");
        assertTrue(node1Failovers.contains("node3"), "node1 failovers must include node3");

        List<String> node2Failovers = registry.getFailovers("node2");
        assertTrue(node2Failovers.contains("node1"), "node2 failovers must include node1");
    }

    @JettraTest
    void testGenerationalArenaOffHeapAllocationAndRecycling() {
        try (GenerationalArena arena = new GenerationalArena(1024 * 1024)) { // 1 MB
            byte[] payload1 = "Sample off-heap payload alpha".getBytes(StandardCharsets.UTF_8);
            byte[] payload2 = "Sample off-heap payload beta with more characters".getBytes(StandardCharsets.UTF_8);

            // 1. Allocate off-heap slices
            long offset1 = arena.allocateRecord(payload1);
            long offset2 = arena.allocateRecord(payload2);

            assertTrue(offset1 >= 0, "Offset1 must be non-negative");
            assertTrue(offset2 > offset1, "Offset2 must be greater than offset1");
            assertEquals(0, offset1 % 8, "Allocation offset1 must be 8-byte aligned");
            assertEquals(0, offset2 % 8, "Allocation offset2 must be 8-byte aligned");

            // 2. Read back from off-heap memory
            byte[] readBack1 = arena.readRecord(offset1);
            assertNotNull(readBack1);
            assertEquals(new String(payload1, StandardCharsets.UTF_8), new String(readBack1, StandardCharsets.UTF_8));

            byte[] readBack2 = arena.readRecord(offset2);
            assertNotNull(readBack2);
            assertEquals(new String(payload2, StandardCharsets.UTF_8), new String(readBack2, StandardCharsets.UTF_8));

            // 3. Reset arena (Minor Compaction recycling)
            arena.reset();
            assertEquals(0, arena.getAllocatedBytes(), "Arena allocated bytes must reset to 0 without GC pressure");
        }
    }

    @JettraTest
    void testDashboardTelemetryReflectsGenerationalAndClusterMetrics() {
        storageEngine.getStorageCore().put("doc:ClusterDb:doc_1", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        DashboardMetricsCollector collector = new DashboardMetricsCollector(storageEngine);
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();

        assertNotNull(snapshot, "Snapshot must not be null");
        assertNotNull(snapshot.generational(), "Generational metrics must be present in snapshot");
        assertTrue(snapshot.generational().youngRecordsCount() >= 1, "Young records count must be >= 1");
        assertNotNull(snapshot.generational().clusterNodeRole(), "Cluster node role must be defined");
        assertEquals("3 Nodes (1 Primary, 2 Secondaries)", snapshot.generational().clusterTopology());
    }
}
