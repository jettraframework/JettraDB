package com.jettra.store.engine.highperformance.integration;

import com.jettra.store.engine.core.JettraStorageEngine;
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

@io.jettra.test.annotation.NotRequiresRunningServer
public class HighPerformanceEngineBridgeTest {

    public record DeviceTelemetry(String deviceId, double cpuUsage, long timestamp) implements Serializable {}

    private Path tempDir;
    private JettraStorageEngine storageEngine;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_bridge_test_");
        storageEngine = new JettraStorageEngine(tempDir.toString());
        storageEngine.start();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (storageEngine != null) {
            storageEngine.stop();
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
    public void testHighPerformanceBridgeLifecycleAndStorage() throws IOException {
        HighPerformanceEngineBridge bridge = storageEngine.getHighPerformanceBridge();
        assertNotNull(bridge);
        assertEquals("HIGH_PERFORMANCE", bridge.getName());

        DeviceTelemetry t1 = new DeviceTelemetry("dev_01", 42.5, System.currentTimeMillis());
        RecordId rid = bridge.persist("iot_devices", "dev_01", t1);
        assertNotNull(rid);

        // Fetch by key
        DeviceTelemetry fetched = bridge.fetch("iot_devices", "dev_01");
        assertNotNull(fetched);
        assertEquals("dev_01", fetched.deviceId());
        assertEquals(42.5, fetched.cpuUsage());

        // Fetch directly by RecordId (ArcadeDB pattern)
        DeviceTelemetry fetchedByRid = bridge.fetchByRid("iot_devices", rid);
        assertNotNull(fetchedByRid);
        assertEquals("dev_01", fetchedByRid.deviceId());

        // Native Stream Query (Eclipse Store pattern)
        List<DeviceTelemetry> streamList = bridge.stream("iot_devices", DeviceTelemetry.class).toList();
        assertEquals(1, streamList.size());
        assertEquals("dev_01", streamList.get(0).deviceId());

        // Micro-snapshot durability
        var snapshot = bridge.triggerSnapshot("Periodic bridge snapshot");
        assertNotNull(snapshot);
        assertEquals(1L, snapshot.snapshotId());
    }
}
