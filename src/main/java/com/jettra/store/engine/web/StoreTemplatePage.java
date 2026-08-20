package com.jettra.store.engine.web;

import io.jettra.flux.pages.FluxBaseHandler;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.util.Map;

/**
 * Base template layout for JettraStoreEngine Web Management Console.
 */
@NoLoginRequired
public abstract class StoreTemplatePage extends FluxBaseHandler {

    protected abstract String getPageTitle();
    protected abstract Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme);

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String loggedUser = getLoggedUser(exchange);
        if (loggedUser == null || loggedUser.isBlank()) {
            // Default to 'admin' session if running locally, or redirect if desired
            loggedUser = "admin";
        }

        String userInitial = loggedUser.substring(0, 1).toUpperCase();

        // Custom CSS styling for JettraStoreEngine modern theme
        Widget customCss = Paragraph.of(
            "<style>\n" +
            "  .jettra-store-layout { min-height: 100vh; background-color: var(--j-bg-primary, #0f172a); color: var(--j-text-primary, #f8fafc); font-family: system-ui, -apple-system, sans-serif; }\n" +
            "  .store-sidebar { width: 260px; background: rgba(15, 23, 42, 0.95); border-right: 1px solid rgba(255, 255, 255, 0.08); padding: 16px; backdrop-filter: blur(12px); }\n" +
            "  .store-header { height: 64px; background: rgba(15, 23, 42, 0.8); border-bottom: 1px solid rgba(255, 255, 255, 0.08); display: flex; align-items: center; justify-content: space-between; padding: 0 24px; backdrop-filter: blur(12px); }\n" +
            "  .store-content-container { padding: 24px; max-width: 1400px; margin: 0 auto; width: 100%; box-sizing: border-box; }\n" +
            "  .store-stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin-bottom: 24px; }\n" +
            "  .store-card { background: rgba(30, 41, 59, 0.7); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 12px; padding: 20px; box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25); transition: transform 0.2s ease, border-color 0.2s ease; }\n" +
            "  .store-card:hover { transform: translateY(-2px); border-color: rgba(59, 130, 246, 0.5); }\n" +
            "  .store-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: 600; text-transform: uppercase; }\n" +
            "  .badge-active { background: rgba(34, 197, 94, 0.15); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.3); }\n" +
            "  .badge-raft { background: rgba(168, 85, 247, 0.15); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.3); }\n" +
            "  .badge-engine { background: rgba(59, 130, 246, 0.15); color: #60a5fa; border: 1px solid rgba(59, 130, 246, 0.3); }\n" +
            "  .engine-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }\n" +
            "  .engine-item { display: flex; flex-direction: column; justify-content: space-between; height: 100%; }\n" +
            "  .engine-icon-box { width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 20px; margin-bottom: 12px; }\n" +
            "  .table-responsive { overflow-x: auto; width: 100%; border-radius: 8px; border: 1px solid rgba(255, 255, 255, 0.08); }\n" +
            "  .jettra-table { width: 100%; border-collapse: collapse; text-align: left; }\n" +
            "  .jettra-table th { background: rgba(15, 23, 42, 0.6); padding: 12px 16px; font-size: 13px; font-weight: 600; color: #94a3b8; border-bottom: 1px solid rgba(255, 255, 255, 0.08); }\n" +
            "  .jettra-table td { padding: 14px 16px; font-size: 14px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); }\n" +
            "  .jettra-table tr:hover td { background: rgba(59, 130, 246, 0.05); }\n" +
            "  .btn-action { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; border-radius: 8px; font-weight: 500; font-size: 14px; text-decoration: none; cursor: pointer; border: none; transition: all 0.2s; }\n" +
            "  .btn-primary { background: #3b82f6; color: white; }\n" +
            "  .btn-primary:hover { background: #2563eb; box-shadow: 0 0 15px rgba(59, 130, 246, 0.5); }\n" +
            "  .btn-secondary { background: rgba(255, 255, 255, 0.08); color: #f8fafc; border: 1px solid rgba(255, 255, 255, 0.15); }\n" +
            "  .btn-secondary:hover { background: rgba(255, 255, 255, 0.15); }\n" +
            "  .btn-danger { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.4); }\n" +
            "  .btn-danger:hover { background: #ef4444; color: white; }\n" +
            "</style>\n"
        );

        // Sidebar Navigation
        WidgetLet overviewMenu = WidgetLet.of("Overview").icon("fas fa-tachometer-alt");
        overviewMenu.add(WidgetLet.of("Dashboard").icon("fas fa-chart-pie").url(JettraServer.resolvePath("/dashboard")));

        WidgetLet storageMenu = WidgetLet.of("Storage Engines").icon("fas fa-database");
        storageMenu.add(WidgetLet.of("All 8 Multi-Models").icon("fas fa-cubes").url(JettraServer.resolvePath("/engines")));

        WidgetLet securityMenu = WidgetLet.of("Security & Access").icon("fas fa-shield-alt");
        securityMenu.add(WidgetLet.of("Users & Credentials").icon("fas fa-users-cog").url(JettraServer.resolvePath("/users")));

        WidgetLet systemMenu = WidgetLet.of("Internals").icon("fas fa-microchip");
        systemMenu.add(WidgetLet.of("Cluster & Components").icon("fas fa-server").url(JettraServer.resolvePath("/components")));

        Widget leftSidebar = Left.of(
            SidebarLogo.of("fas fa-layer-group", "JettraStore"),
            SidebarCategory.of("Navigation"),
            overviewMenu,
            SidebarCategory.of("Database Core"),
            storageMenu,
            SidebarCategory.of("Administration"),
            securityMenu,
            systemMenu
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-sidebar"));

        // Header
        Widget avatar = Avatar.label(userInitial).shape("circle")
            .modifier(new io.jettra.flux.core.Modifier().style("background: linear-gradient(135deg, #3b82f6, #8b5cf6); color: white; font-weight: bold; width: 36px; height: 36px; display: inline-flex; align-items: center; justify-content: center; margin-right: 8px;"));

        Widget profileTrigger = Row.of(
            avatar,
            Span.of(loggedUser).modifier(new io.jettra.flux.core.Modifier().style("font-weight: 600; font-size: 14px;")),
            Icon.of("fas fa-chevron-down").modifier(new io.jettra.flux.core.Modifier().style("margin-left: 6px; font-size: 12px; color: #94a3b8;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center; cursor: pointer; padding: 4px 8px; border-radius: 8px; background: rgba(255,255,255,0.05);"));

        Widget profileMenu = ((OverlayMenu) OverlayMenu.of(
            WidgetLet.of("Security DB Console").icon("fas fa-user-lock").url(JettraServer.resolvePath("/securitydb/admin")),
            WidgetLet.of("API Documentation").icon("fas fa-book").url(JettraServer.resolvePath("/swagger-ui")),
            WidgetLet.of("Sign Out").icon("fas fa-sign-out-alt").url(JettraServer.resolvePath("/login?logout=true"))
        ).trigger(profileTrigger)).alignRight();

        Widget topHeader = Top.of(
            Row.of(
                Icon.of("fas fa-bolt").modifier(new io.jettra.flux.core.Modifier().style("color: #38bdf8; margin-right: 8px; font-size: 18px;")),
                Span.of("JettraStoreEngine").modifier(new io.jettra.flux.core.Modifier().style("font-weight: 700; font-size: 18px; letter-spacing: 0.5px;")),
                Span.of("v1.0-SNAPSHOT").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-active").style("margin-left: 12px; font-size: 11px;")),
                Span.of("RAFT ACTIVE").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-raft").style("margin-left: 6px; font-size: 11px;"))
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;")),
            Row.of(
                ThemeChanged.of().modifier(new io.jettra.flux.core.Modifier().style("margin-right: 16px;")),
                profileMenu
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-header"));

        // Content
        Widget content = buildContent(exchange, params, currentTheme);

        Widget mainContainer = Column.of(
            topHeader,
            Div.of(content).modifier(new io.jettra.flux.core.Modifier().cssClass("store-content-container"))
        ).modifier(new io.jettra.flux.core.Modifier().style("flex: 1; overflow-y: auto;"));

        Widget scaffold = Row.of(
            leftSidebar,
            mainContainer
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("jettra-store-layout"));

        return Column.of(
            customCss,
            scaffold
        );
    }
}
