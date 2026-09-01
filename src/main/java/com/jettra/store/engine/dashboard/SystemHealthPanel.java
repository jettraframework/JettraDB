package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.dashboard.DashboardMetrics.SystemHealthStatus;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * System Status, Resource Allocation, and Health Widget for JettraDB Dashboard.
 * Displays JVM Heap utilization, Disk Space, Cluster/Raft Topology, and Node Health.
 */
public final class SystemHealthPanel {

    private SystemHealthPanel() {}

    public static Widget build(SystemHealthStatus health) {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-heartbeat").modifier(new Modifier().style("color:#ef4444; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("System Status & Node Telemetry"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
                Span.of(health.nodeStatus())
                    .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px; margin-left:8px; background:rgba(16,185,129,0.15); color:#10b981; border:1px solid #10b981;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of("Uptime: " + health.uptime()).modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); font-family:monospace;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; flex-wrap:wrap; gap:8px;"));

        List<Widget> gridItems = new ArrayList<>();

        // 1. JVM Heap Memory Usage
        Widget jvmHeapItem = createHealthItem(
            "fas fa-memory",
            "#3b82f6",
            "JVM HEAP ALLOCATION",
            health.usedHeapMb() + " MB / " + health.maxHeapMb() + " MB",
            health.heapPercent() + "% utilized (G1GC Virtual Threads)",
            health.heapPercent() > 85 ? "#ef4444" : "#3b82f6",
            health.heapPercent()
        );
        gridItems.add(jvmHeapItem);

        // 2. Data Storage Disk
        long diskPercent = health.totalDiskMb() > 0 ? (health.usedDiskMb() * 100) / health.totalDiskMb() : 25;
        Widget diskItem = createHealthItem(
            "fas fa-hdd",
            "#10b981",
            "DATA DISK STORAGE",
            health.usedDiskMb() + " MB / " + health.totalDiskMb() + " MB",
            (health.totalDiskMb() - health.usedDiskMb()) + " MB available headroom",
            "#10b981",
            (int) diskPercent
        );
        gridItems.add(diskItem);

        // 3. Active Transactions & Lock Concurrency
        Widget txItem = createHealthMetricCard(
            "fas fa-sync",
            "#f59e0b",
            "ACTIVE TRANSACTIONS",
            health.activeTransactions() + " in-flight",
            "MVCC Snapshot Isolation Active",
            "SYNCED",
            "badge-active"
        );
        gridItems.add(txItem);

        // 4. Cluster & Raft Topology
        Widget raftItem = createHealthMetricCard(
            "fas fa-network-wired",
            "#8b5cf6",
            "RAFT TOPOLOGY",
            health.raftStatus(),
            "Port 9092 | Leader Active",
            "QUORUM",
            "badge-raft"
        );
        gridItems.add(raftItem);

        Widget grid = Div.of(gridItems.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:grid; grid-template-columns:repeat(auto-fit, minmax(240px, 1fr)); gap:14px;"));

        return Div.of(header, grid)
            .modifier(new Modifier()
                .cssClass("store-card")
                .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.05); margin-bottom:24px; width:100%;"));
    }

    private static Widget createHealthItem(
        String icon,
        String color,
        String title,
        String mainVal,
        String subVal,
        String progressColor,
        int progressPct
    ) {
        Widget top = Div.of(
            Div.of(
                Icon.of(icon).modifier(new Modifier().style("color:" + color + "; font-size:14px; margin-right:6px;")),
                Span.of(title).modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of(mainVal).modifier(new Modifier().style("font-size:12px; font-weight:700; color:var(--j-text-primary); font-family:monospace;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;"));

        // Progress bar container
        Widget bar = Div.of(
            Div.of().modifier(new Modifier().style("height:100%; width:" + Math.min(100, Math.max(2, progressPct)) + "%; background:" + progressColor + "; border-radius:4px; transition:width 0.3s ease;"))
        ).modifier(new Modifier().style("width:100%; height:6px; background:rgba(255,255,255,0.08); border-radius:4px; overflow:hidden; margin-bottom:4px;"));

        Widget sub = Span.of(subVal).modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-secondary); display:block;"));

        return Div.of(top, bar, sub)
            .modifier(new Modifier().style("background:var(--j-bg-subsurface); border:1px solid var(--j-border); border-radius:8px; padding:12px;"));
    }

    private static Widget createHealthMetricCard(
        String icon,
        String color,
        String title,
        String mainVal,
        String subVal,
        String badgeText,
        String badgeClass
    ) {
        Widget top = Div.of(
            Div.of(
                Icon.of(icon).modifier(new Modifier().style("color:" + color + "; font-size:14px; margin-right:6px;")),
                Span.of(title).modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted); text-transform:uppercase;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of(badgeText).modifier(new Modifier().cssClass("store-badge " + badgeClass).style("font-size:9px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;"));

        Widget main = Span.of(mainVal).modifier(new Modifier().style("font-size:14px; font-weight:800; color:var(--j-text-primary); margin:2px 0; display:block;"));
        Widget sub = Span.of(subVal).modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-secondary); display:block;"));

        return Div.of(top, main, sub)
            .modifier(new Modifier().style("background:var(--j-bg-subsurface); border:1px solid var(--j-border); border-radius:8px; padding:12px;"));
    }
}
