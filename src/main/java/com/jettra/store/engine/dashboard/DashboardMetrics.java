package com.jettra.store.engine.dashboard;

import java.util.List;
import java.util.Map;

/**
 * Immutable Java 25 Metric Models and DTOs for JettraDB Dashboard.
 * Encapsulates multi-model distributions, telemetry streams, storage hierarchy, and system health.
 */
public final class DashboardMetrics {

    private DashboardMetrics() {}

    /**
     * High-level KPI summary metrics displayed in the top dashboard row.
     */
    public record KpiSummary(
        int totalDatabases,
        int activeEngines,
        int totalRecords,
        long totalStorageBytes,
        long opsPerSec,
        double avgLatencyMs,
        int heapPercent
    ) {}

    /**
     * Multi-model data volume distribution across all 9 engines.
     */
    public record MultiModelDistribution(
        Map<String, Integer> engineCounts,
        int totalItems
    ) {
        public int getCount(String engineKey) {
            return engineCounts != null ? engineCounts.getOrDefault(engineKey, 0) : 0;
        }

        public double getPercentage(String engineKey) {
            if (totalItems <= 0) return 0.0;
            return (getCount(engineKey) * 100.0) / totalItems;
        }
    }

    /**
     * Single time-series telemetry sample for throughput and latency.
     */
    public record ThroughputLatencyPoint(
        long timestamp,
        String label,
        double readIops,
        double writeIops,
        double latencyMs
    ) {}

    /**
     * Time-series telemetry dataset for real-time line charts.
     */
    public record ThroughputTelemetry(
        List<ThroughputLatencyPoint> points
    ) {}

    /**
     * Storage hierarchy breakdown per database and active engine models.
     */
    public record DatabaseStorageHierarchy(
        Map<String, Map<String, Integer>> dbModelCounts
    ) {
        public int getTotalKeysForDb(String db) {
            if (dbModelCounts == null || !dbModelCounts.containsKey(db)) return 0;
            return dbModelCounts.get(db).values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * System status, JVM memory usage, active transactions, and Raft consensus health.
     */
    public record SystemHealthStatus(
        String nodeStatus,
        long usedHeapMb,
        long maxHeapMb,
        int heapPercent,
        long usedDiskMb,
        long totalDiskMb,
        String uptime,
        int activeTransactions,
        String raftStatus
    ) {}

    /**
     * Comprehensive immutable snapshot of all dashboard analytics.
     */
    public record ComprehensiveDashboardSnapshot(
        KpiSummary kpi,
        MultiModelDistribution distribution,
        ThroughputTelemetry telemetry,
        DatabaseStorageHierarchy hierarchy,
        SystemHealthStatus health
    ) {}
}
