package com.jettra.store.engine.dashboard;

import io.jettra.server.JettraServer;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

/**
 * Main Composite Dashboard View for JettraDB built strictly with JettraFlux components.
 * Assembles modular panels, reactive metrics, and native chart widgets
 * (CharsDoughnut, ChartsLine, CharsBar) into a responsive, cohesive layout.
 */
public final class MainDashboardView {

    private MainDashboardView() {}

    /**
     * Builds the complete modular dashboard UI using the provided snapshot.
     */
    public static Widget build(ComprehensiveDashboardSnapshot snapshot) {
        // 1. Header Row (Title, Subtitle, Global Action Buttons)
        Widget titleBlock = Div.of(
            Div.of(
                Header.of(1, Text.of("Storage Engine Dashboard"))
                    .modifier(new Modifier().style("margin:0; font-size:24px; font-weight:800; color:var(--j-text-primary); letter-spacing:-0.5px;")),
                Paragraph.of(Text.of("Real-time operational monitoring, multi-model storage hierarchy, and cluster telemetry."))
                    .modifier(new Modifier().style("margin:4px 0 0 0; color:var(--j-text-muted); font-size:13px;"))
            ),
            Div.of(
                Button.of(
                    Icon.of("fas fa-save").modifier(new Modifier().style("margin-right:6px;")),
                    Text.of("Create Backup Snapshot")
                ).attribute("id", "btnCreateBackupSnapshot")
                 .attribute("type", "button")
                 .attribute("onclick", "triggerBackup(this)")
                 .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:8px 16px; font-size:12px; font-weight:600; cursor:pointer;")),
                Link.of(JettraServer.resolvePath("/engines"),
                    Icon.of("fas fa-cubes").modifier(new Modifier().style("margin-right:6px;")),
                    Text.of("Hierarchy Explorer")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:8px 16px; font-size:12px; font-weight:600; margin-left:10px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:8px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; flex-wrap:wrap; gap:12px;"));

        // 2. Top KPI Summary Cards Row
        Widget kpiRow = KpiSummaryCardsPanel.build(snapshot.kpi());

        // 3. Middle Charts Row: Multi-Model Distribution (Doughnut) + Throughput & Latency (Line)
        Widget chartsRow = Div.of(
            MultiModelDistributionPanel.build(snapshot.distribution()),
            ThroughputLatencyPanel.build(snapshot.telemetry())
        ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:16px; margin-bottom:24px; width:100%;"));

        // 4. Storage Hierarchy Panel: Comparative Bar Chart + Namespaces Datatable
        Widget hierarchyPanel = EngineHierarchyChartPanel.build(snapshot.hierarchy());

        // 5. System Health & Resource Allocation Panel
        Widget healthPanel = SystemHealthPanel.build(snapshot.health());

        // 6. Quick Operations & Network Interfaces Panel
        Widget bottomPanel = QuickActionsAndEndpointsPanel.build();

        // 7. Client-side backup trigger script with non-invasive toast feedback and button state management
        Widget backupScript = RawScript.of(
            "async function triggerBackup(btn) {\n" +
            "  if (!btn) btn = document.getElementById('btnCreateBackupSnapshot');\n" +
            "  var origHtml = btn ? btn.innerHTML : '';\n" +
            "  if (btn) {\n" +
            "    btn.disabled = true;\n" +
            "    btn.style.opacity = '0.7';\n" +
            "    btn.style.cursor = 'not-allowed';\n" +
            "    btn.innerHTML = '<i class=\"fas fa-spinner fa-spin\" style=\"margin-right:6px;\"></i> Generating Snapshot...';\n" +
            "  }\n" +
            "\n" +
            "  function showToast(msg, isSuccess) {\n" +
            "    var toast = document.getElementById('jettra-snapshot-toast');\n" +
            "    if (!toast) {\n" +
            "      toast = document.createElement('div');\n" +
            "      toast.id = 'jettra-snapshot-toast';\n" +
            "      toast.style.position = 'fixed';\n" +
            "      toast.style.bottom = '24px';\n" +
            "      toast.style.right = '24px';\n" +
            "      toast.style.zIndex = '999999';\n" +
            "      toast.style.padding = '14px 20px';\n" +
            "      toast.style.borderRadius = '8px';\n" +
            "      toast.style.fontFamily = 'Inter, -apple-system, BlinkMacSystemFont, sans-serif';\n" +
            "      toast.style.fontSize = '13px';\n" +
            "      toast.style.fontWeight = '600';\n" +
            "      toast.style.boxShadow = '0 10px 25px rgba(0,0,0,0.3)';\n" +
            "      toast.style.display = 'flex';\n" +
            "      toast.style.alignItems = 'center';\n" +
            "      toast.style.gap = '10px';\n" +
            "      toast.style.transition = 'all 0.3s cubic-bezier(0.16, 1, 0.3, 1)';\n" +
            "      document.body.appendChild(toast);\n" +
            "    }\n" +
            "    if (isSuccess) {\n" +
            "      toast.style.background = 'var(--jf-surface, var(--j-bg-surface, #0f172a))';\n" +
            "      toast.style.border = '1px solid var(--jf-accent, var(--j-primary, #10b981))';\n" +
            "      toast.style.color = 'var(--jf-text-primary, var(--j-text-primary, #f8fafc))';\n" +
            "      toast.innerHTML = '<i class=\"fas fa-check-circle\" style=\"color:var(--jf-accent, var(--j-primary, #10b981)); font-size:16px;\"></i> <span>' + msg + '</span>';\n" +
            "    } else {\n" +
            "      toast.style.background = 'var(--jf-surface, var(--j-bg-surface, #0f172a))';\n" +
            "      toast.style.border = '1px solid #ef4444';\n" +
            "      toast.style.color = 'var(--jf-text-primary, var(--j-text-primary, #f8fafc))';\n" +
            "      toast.innerHTML = '<i class=\"fas fa-exclamation-triangle\" style=\"color:#ef4444; font-size:16px;\"></i> <span>' + msg + '</span>';\n" +
            "    }\n" +
            "    toast.style.opacity = '1';\n" +
            "    toast.style.transform = 'translateY(0)';\n" +
            "    setTimeout(function() {\n" +
            "      toast.style.opacity = '0';\n" +
            "      toast.style.transform = 'translateY(10px)';\n" +
            "    }, 4500);\n" +
            "  }\n" +
            "\n" +
            "  try {\n" +
            "    var curMode = document.documentElement.getAttribute('data-color-mode') || 'dark';\n" +
            "    var curTheme = document.documentElement.getAttribute('data-theme') || 'Matrix';\n" +
            "    var res = await fetch('" + JettraServer.resolvePath("/dashboard?action=backup") + "', {\n" +
            "      method: 'POST',\n" +
            "      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },\n" +
            "      body: 'action=backup&_jettra_theme=' + encodeURIComponent(curTheme) + '&_jettra_color_mode=' + encodeURIComponent(curMode)\n" +
            "    });\n" +
            "    if (!res.ok) {\n" +
            "      res = await fetch('" + JettraServer.resolvePath("/api/backup") + "', { method: 'POST' });\n" +
            "    }\n" +
            "    if (res.ok) {\n" +
            "      var data = await res.json();\n" +
            "      var fname = data.fileName || data.snapshot || 'snapshot.md';\n" +
            "      var p = data.path || '/data/snapshot/' + fname;\n" +
            "      showToast('Markdown Snapshot created: ' + fname + ' in ' + p, true);\n" +
            "    } else {\n" +
            "      showToast('Snapshot generation failed (HTTP ' + res.status + ')', false);\n" +
            "    }\n" +
            "  } catch(e) {\n" +
            "    showToast('Error creating snapshot: ' + e.message, false);\n" +
            "  } finally {\n" +
            "    if (btn) {\n" +
            "      btn.disabled = false;\n" +
            "      btn.style.opacity = '1';\n" +
            "      btn.style.cursor = 'pointer';\n" +
            "      btn.innerHTML = origHtml;\n" +
            "    }\n" +
            "  }\n" +
            "}"
        );

        return Column.of(
            titleBlock,
            kpiRow,
            chartsRow,
            hierarchyPanel,
            healthPanel,
            bottomPanel,
            backupScript
        ).modifier(new Modifier().style("width:100%; max-width:1440px; margin:0 auto; padding:4px; box-sizing:border-box;"));
    }
}
