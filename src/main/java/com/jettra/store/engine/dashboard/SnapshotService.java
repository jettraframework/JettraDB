package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.dashboard.DashboardMetrics.*;
import io.jettra.flux.theme.ColorMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * Service in Java 25+ responsible for capturing dashboard telemetry,
 * formatting comprehensive Markdown reports, and persisting atomic snapshots
 * into the standardized storage directory.
 */
public final class SnapshotService {

    private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SnapshotService() {}

    /**
     * Resolves the target directory for snapshots based on the database storage directory.
     * The snapshot subdirectory is created inside the database storage directory (e.g. /data/snapshot or storageDir/snapshot).
     *
     * @param storageDir database storage directory (e.g. from engine.getStorageDir()), or null to auto-discover
     * @return Path to the snapshot directory
     */
    public static Path resolveSnapshotDirectory(Path storageDir) {
        if (storageDir != null) {
            Path snapshotDir = storageDir.resolve("snapshot");
            try {
                if (!Files.exists(snapshotDir)) {
                    Files.createDirectories(snapshotDir);
                }
                return snapshotDir;
            } catch (IOException ignored) {
                // If the specified storageDir cannot be created or written to, try fallback
            }
        }

        // Check system property or environment variable for database storage directory
        String configuredDir = System.getProperty("jettra.data.dir", System.getenv("JETTRA_DATA_DIR"));
        if (configuredDir != null && !configuredDir.isBlank()) {
            if (configuredDir.startsWith("~/")) {
                configuredDir = System.getProperty("user.home") + configuredDir.substring(1);
            }
            Path dir = Path.of(configuredDir).resolve("snapshot");
            try {
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
                return dir;
            } catch (Exception ignored) {}
        }

        // Try /data/snapshot (standard root database storage directory)
        Path rootTarget = Path.of("/data/snapshot");
        try {
            if (!Files.exists(rootTarget)) {
                Files.createDirectories(rootTarget);
            }
            if (Files.isWritable(rootTarget)) {
                return rootTarget;
            }
        } catch (Exception ignored) {
            // Root path not accessible without elevated system privileges
        }

        // Fallback to local working directory data/snapshot (./data/snapshot)
        Path localTarget = Path.of(System.getProperty("user.dir"), "data", "snapshot");
        try {
            if (!Files.exists(localTarget)) {
                Files.createDirectories(localTarget);
            }
            return localTarget;
        } catch (IOException e) {
            throw new RuntimeException("Could not create snapshot directory in data path: " + localTarget, e);
        }
    }

    /**
     * Resolves the default snapshot directory.
     */
    public static Path resolveSnapshotDirectory() {
        return resolveSnapshotDirectory(null);
    }

    /**
     * Captures and persists a comprehensive Markdown snapshot of the dashboard metrics
     * inside the snapshot subdirectory of the database storage directory.
     *
     * @param baseStorageDir path to database storage directory (engine.getStorageDir())
     * @param snapshot       immutable snapshot DTO
     * @param user           authenticated user or session identifier
     * @param themeName      active visual theme (e.g. "Matrix")
     * @param colorMode      active color mode (WHITE / DARK)
     * @return Path of the persisted Markdown snapshot file
     * @throws IOException if writing to disk fails
     */
    public static Path createSnapshot(
        Path baseStorageDir,
        ComprehensiveDashboardSnapshot snapshot,
        String user,
        String themeName,
        ColorMode colorMode
    ) throws IOException {
        Objects.requireNonNull(snapshot, "ComprehensiveDashboardSnapshot must not be null");
        LocalDateTime now = LocalDateTime.now();

        String effectiveUser = (user != null && !user.isBlank()) ? user.trim() : "root";
        String effectiveTheme = (themeName != null && !themeName.isBlank()) ? themeName.trim() : "Matrix";
        ColorMode effectiveMode = (colorMode != null) ? colorMode : ColorMode.DARK;

        String markdownContent = generateMarkdown(snapshot, effectiveUser, effectiveTheme, effectiveMode, now);

        Path snapshotDir = resolveSnapshotDirectory(baseStorageDir);
        String fileName = "snapshot-" + now.format(FILE_NAME_FORMATTER) + ".md";
        Path targetPath = snapshotDir.resolve(fileName);
        Path tempPath = snapshotDir.resolve(fileName + ".tmp." + System.nanoTime());

        // Thread-safe and atomic file write
        Files.writeString(tempPath, markdownContent, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // Fallback to standard replace if atomic move is not supported across filesystem boundaries
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return targetPath;
    }

    /**
     * Captures and persists a comprehensive Markdown snapshot of the dashboard metrics.
     *
     * @param snapshot   immutable snapshot DTO
     * @param user       authenticated user or session identifier
     * @param themeName  active visual theme (e.g. "Matrix")
     * @param colorMode  active color mode (WHITE / DARK)
     * @return Path of the persisted Markdown snapshot file
     * @throws IOException if writing to disk fails
     */
    public static Path createSnapshot(
        ComprehensiveDashboardSnapshot snapshot,
        String user,
        String themeName,
        ColorMode colorMode
    ) throws IOException {
        return createSnapshot(null, snapshot, user, themeName, colorMode);
    }

    /**
     * Overload using baseStorageDir and default user session and active theme.
     */
    public static Path createSnapshot(Path baseStorageDir, ComprehensiveDashboardSnapshot snapshot) throws IOException {
        return createSnapshot(baseStorageDir, snapshot, "root", "Matrix", ColorMode.DARK);
    }

    /**
     * Overload using defaults for user session and active theme.
     */
    public static Path createSnapshot(ComprehensiveDashboardSnapshot snapshot) throws IOException {
        return createSnapshot(null, snapshot, "root", "Matrix", ColorMode.DARK);
    }

    /**
     * Formats the comprehensive dashboard state into structured GitHub Flavored Markdown.
     */
    public static String generateMarkdown(
        ComprehensiveDashboardSnapshot snapshot,
        String user,
        String themeName,
        ColorMode colorMode,
        LocalDateTime timestamp
    ) {
        StringBuilder sb = new StringBuilder(4096);

        // 1. Header & Metadata
        sb.append("# JettraDB System & Storage Dashboard Snapshot\n\n");
        sb.append("> **Snapshot Timestamp:** `").append(timestamp.format(DISPLAY_FORMATTER)).append("`  \n");
        sb.append("> **User Session:** `").append(user).append("`  \n");
        sb.append("> **Visual Environment:** `").append(themeName).append("` (Mode: `").append(colorMode).append("`)  \n");
        sb.append("> **Node Status:** `").append(snapshot.health().nodeStatus()).append("` (Consensus: `")
          .append(snapshot.health().raftStatus()).append("`)  \n\n");
        sb.append("---\n\n");

        // 2. High-Level KPI Summary
        KpiSummary kpi = snapshot.kpi();
        sb.append("## 1. High-Level KPI Summary\n\n");
        sb.append("| Metric Name | Value | Operational State |\n");
        sb.append("| :--- | :--- | :--- |\n");
        sb.append("| **Total Databases** | `").append(kpi.totalDatabases()).append("` | Active Namespaces |\n");
        sb.append("| **Active Engines** | `").append(kpi.activeEngines()).append("` / 9 | Unified Multi-Model Runtime |\n");
        sb.append("| **Total Records / Entities** | `").append(String.format("%,d", kpi.totalRecords())).append("` | Indexed Storage |\n");
        sb.append("| **Total Storage Allocated** | `").append(formatBytes(kpi.totalStorageBytes())).append("` | Disk Allocation |\n");
        sb.append("| **Throughput (Ops/sec)** | `").append(String.format("%,d ops/s", kpi.opsPerSec())).append("` | Real-Time IOPS |\n");
        sb.append("| **Average Latency** | `").append(String.format("%.2f ms", kpi.avgLatencyMs())).append("` | SLA Benchmark |\n");
        sb.append("| **JVM Heap Utilization** | `").append(kpi.heapPercent()).append("%` | Memory Headroom |\n\n");
        sb.append("---\n\n");

        // 3. Multi-Model Data Volume Distribution
        MultiModelDistribution dist = snapshot.distribution();
        sb.append("## 2. Multi-Model Data Volume Distribution\n\n");
        sb.append("Total Items across all engines: **").append(String.format("%,d", dist.totalItems())).append("**\n\n");
        sb.append("| Storage Engine | Stored Entities | Volume Share | Category |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");

        String[] engineKeys = {
            "DOCUMENT", "KEYVALUE", "VECTOR", "RECORDS", "TIMESERIES",
            "OBJECT", "GRAPH", "COLUMN", "GEOSPATIAL"
        };

        for (String eng : engineKeys) {
            int count = dist.getCount(eng);
            double pct = dist.getPercentage(eng);
            String desc = switch (eng) {
                case "DOCUMENT" -> "JSON Document Hierarchy";
                case "KEYVALUE" -> "High-Speed Key-Value Cache";
                case "VECTOR" -> "HNSW Vector Embeddings";
                case "RECORDS" -> "Java 25 Record Schemas";
                case "TIMESERIES" -> "Metrics & Telemetry Buckets";
                case "OBJECT" -> "Binary Object Store";
                case "GRAPH" -> "Property Graph Nodes & Edges";
                case "COLUMN" -> "Columnar Analytics Engine";
                case "GEOSPATIAL" -> "Spatial & GIS Coordinates";
                default -> "Storage Engine";
            };
            sb.append("| **").append(eng).append("** | `").append(String.format("%,d", count))
              .append("` | `").append(String.format("%.2f%%", pct)).append("` | ").append(desc).append(" |\n");
        }
        sb.append("\n---\n\n");

        // 4. Storage Hierarchy & Namespace Breakdown
        DatabaseStorageHierarchy hierarchy = snapshot.hierarchy();
        sb.append("## 3. Storage Hierarchy & Namespace Breakdown\n\n");
        sb.append("| Database Namespace | Active Storage Engine | Stored Entities | Namespace Total |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");

        if (hierarchy.dbModelCounts() != null && !hierarchy.dbModelCounts().isEmpty()) {
            for (Map.Entry<String, Map<String, Integer>> dbEntry : hierarchy.dbModelCounts().entrySet()) {
                String dbName = dbEntry.getKey();
                Map<String, Integer> models = dbEntry.getValue();
                int dbTotal = models.values().stream().mapToInt(Integer::intValue).sum();

                boolean first = true;
                for (Map.Entry<String, Integer> m : models.entrySet()) {
                    sb.append("| ");
                    if (first) {
                        sb.append("**").append(dbName).append("**");
                        first = false;
                    } else {
                        sb.append("↳ *").append(dbName).append("*");
                    }
                    sb.append(" | `").append(m.getKey()).append("` | `").append(String.format("%,d", m.getValue()))
                      .append("` | `").append(String.format("%,d", dbTotal)).append("` |\n");
                }
            }
        } else {
            sb.append("| *(None)* | *(None)* | `0` | `0` |\n");
        }
        sb.append("\n---\n\n");

        // 5. Real-Time Telemetry & Throughput
        ThroughputTelemetry telemetry = snapshot.telemetry();
        sb.append("## 4. Telemetry Stream & Performance Profiling\n\n");
        sb.append("| Timestamp Offset | Time Label | Read Throughput | Write Throughput | Latency |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n");

        if (telemetry.points() != null && !telemetry.points().isEmpty()) {
            for (ThroughputLatencyPoint pt : telemetry.points()) {
                sb.append("| `").append(pt.timestamp()).append("` | `").append(pt.label()).append("` | `")
                  .append(String.format("%.1f IOPS", pt.readIops())).append("` | `")
                  .append(String.format("%.1f IOPS", pt.writeIops())).append("` | `")
                  .append(String.format("%.2f ms", pt.latencyMs())).append("` |\n");
            }
        } else {
            sb.append("| *(Live Stream)* | `Now` | `0.0 IOPS` | `0.0 IOPS` | `0.00 ms` |\n");
        }
        sb.append("\n---\n\n");

        // 6. System Health, Memory & Resource Allocation
        SystemHealthStatus health = snapshot.health();
        sb.append("## 5. System Health, Memory & Resource Allocation\n\n");
        sb.append("| Component | Metric / Allocation | Operational State |\n");
        sb.append("| :--- | :--- | :--- |\n");
        sb.append("| **Node Status** | `").append(health.nodeStatus()).append("` | Cluster Health |\n");
        sb.append("| **JVM Heap Memory** | `").append(health.usedHeapMb()).append(" MB` / `").append(health.maxHeapMb())
          .append(" MB` (").append(health.heapPercent()).append("%) | Headroom OK |\n");
        sb.append("| **Disk Volume** | `").append(health.usedDiskMb()).append(" MB` / `").append(health.totalDiskMb())
          .append(" MB` | Persistent Disk |\n");
        sb.append("| **Active Transactions** | `").append(health.activeTransactions()).append(" in-flight` | ACID Concurrency |\n");
        sb.append("| **Raft Consensus** | `").append(health.raftStatus()).append("` | Quorum Replication |\n");
        sb.append("| **System Uptime** | `").append(health.uptime()).append("` | Continuous Operation |\n\n");
        sb.append("---\n\n");

        sb.append("*Generated automatically by JettraDB Dashboard Snapshot Service.*\n");
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), unit);
    }
}
