package com.jettra.store.engine.dashboard;

import io.jettra.server.JettraServer;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

/**
 * Quick Operations & Network Interfaces Panel for JettraDB Dashboard.
 * Assembles admin action buttons and endpoint directory using JettraFlux widgets.
 */
public final class QuickActionsAndEndpointsPanel {

    private QuickActionsAndEndpointsPanel() {}

    public static Widget build() {
        // Left: Quick Operations Card
        Widget quickOpsCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-tools").modifier(new Modifier().style("color:#60a5fa; margin-right:8px; font-size:15px;")),
                Text.of("Quick Operations")
            ).modifier(new Modifier().style("margin:0 0 8px 0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
            Paragraph.of(Text.of("Perform direct administrative operations, storage core inspections, or snapshot backups."))
                .modifier(new Modifier().style("font-size:12px; color:var(--j-text-muted); margin-bottom:16px; line-height:1.4;")),
            Div.of(
                Link.of(JettraServer.resolvePath("/users"),
                    Icon.of("fas fa-user-shield"),
                    Text.of(" Manage Users")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 12px;")),
                Link.of(JettraServer.resolvePath("/components"),
                    Icon.of("fas fa-layer-group"),
                    Text.of(" Storage Core")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("font-size:12px; padding:6px 12px;")),
                Button.of(
                    Icon.of("fas fa-hdd"),
                    Text.of(" Snapshot Backup")
                ).attribute("onclick", "triggerBackup()")
                 .modifier(new Modifier().cssClass("btn-action btn-primary").style("font-size:12px; padding:6px 14px;"))
            ).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:8px; align-items:center;"))
        ).modifier(new Modifier()
            .cssClass("store-card")
            .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.05); flex:1; min-width:280px;"));

        // Right: Network Endpoints Card
        Widget endpointsCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-network-wired").modifier(new Modifier().style("color:#34d399; margin-right:8px; font-size:15px;")),
                Text.of("Network Endpoints & APIs")
            ).modifier(new Modifier().style("margin:0 0 10px 0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
            Div.of(
                createEndpointRow("HTTP REST API:", "http://localhost:8080/api/"),
                createEndpointRow("Multi-Model API:", "http://localhost:8080/api/model/{engine}"),
                createEndpointRow("Document API:", "http://localhost:8080/api/document/{coll}"),
                createSwaggerRow("Swagger OpenAPI:", "/swagger-ui")
            ).modifier(new Modifier().style("display:flex; flex-direction:column; gap:4px;"))
        ).modifier(new Modifier()
            .cssClass("store-card")
            .style("background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:10px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.05); flex:1; min-width:280px;"));

        return Div.of(quickOpsCard, endpointsCard)
            .modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:16px; margin-bottom:24px; width:100%;"));
    }

    private static Widget createEndpointRow(String label, String endpoint) {
        return Div.of(
            Span.of(label).modifier(new Modifier().style("font-size:12px; color:var(--j-text-muted); font-weight:500;")),
            Span.of(endpoint).modifier(new Modifier().style("color:#38bdf8; font-family:monospace; font-size:11.5px; background:rgba(56,189,248,0.1); padding:2px 6px; border-radius:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:5px 0; border-bottom:1px solid var(--j-border);"));
    }

    private static Widget createSwaggerRow(String label, String path) {
        return Div.of(
            Span.of(label).modifier(new Modifier().style("font-size:12px; color:var(--j-text-muted); font-weight:500;")),
            Link.of(JettraServer.resolvePath(path), Text.of(path))
                .modifier(new Modifier().style("color:#a78bfa; font-weight:600; font-size:12px; text-decoration:none;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:5px 0;"));
    }
}
