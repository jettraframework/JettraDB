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
 * Features modern design aesthetics, glassmorphism, Google Fonts, and intuitive navigation.
 */
@NoLoginRequired
public abstract class StoreTemplatePage extends FluxBaseHandler {

    protected abstract String getPageTitle();
    protected abstract Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme);

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String loggedUser = getLoggedUser(exchange);
        if (loggedUser == null || loggedUser.isBlank()) {
            loggedUser = "admin";
        }

        String userInitial = loggedUser.substring(0, 1).toUpperCase();

        // Custom CSS styling with Google Fonts, glassmorphism, rich color palette, and micro-animations
        Widget customCss = Paragraph.of(
            "<link rel='preconnect' href='https://fonts.googleapis.com'>\n" +
            "<link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>\n" +
            "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap' rel='stylesheet'>\n" +
            "<style>\n" +
            "  :root {\n" +
            "    --j-bg-main: #0b0f19;\n" +
            "    --j-bg-card: rgba(18, 24, 38, 0.85);\n" +
            "    --j-bg-card-hover: rgba(28, 36, 56, 0.95);\n" +
            "    --j-border: rgba(255, 255, 255, 0.08);\n" +
            "    --j-border-focus: rgba(56, 189, 248, 0.5);\n" +
            "    --j-accent-records: #f43f5e;\n" +
            "    --j-accent-docs: #38bdf8;\n" +
            "    --j-accent-vectors: #a855f7;\n" +
            "    --j-accent-graph: #10b981;\n" +
            "  }\n" +
            "  * { box-sizing: border-box; }\n" +
            "  body, .jettra-store-layout { min-height: 100vh; background-color: var(--j-bg-main); color: #f8fafc; font-family: 'Inter', -apple-system, sans-serif; }\n" +
            "  h1, h2, h3, h4, h5 { font-family: 'Outfit', sans-serif; letter-spacing: -0.02em; }\n" +
            "  code, pre, .mono { font-family: 'JetBrains Mono', monospace; }\n" +
            "  .store-sidebar { width: 260px; background: rgba(11, 15, 25, 0.95); border-right: 1px solid var(--j-border); padding: 18px; backdrop-filter: blur(16px); }\n" +
            "  .store-header { height: 68px; background: rgba(11, 15, 25, 0.85); border-bottom: 1px solid var(--j-border); display: flex; align-items: center; justify-content: space-between; padding: 0 28px; backdrop-filter: blur(16px); }\n" +
            "  .store-content-container { padding: 28px; max-width: 1440px; margin: 0 auto; width: 100%; }\n" +
            "  .store-stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 18px; margin-bottom: 24px; }\n" +
            "  .store-card { background: var(--j-bg-card); border: 1px solid var(--j-border); border-radius: 14px; padding: 22px; box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.35); transition: all 0.25s ease; backdrop-filter: blur(12px); }\n" +
            "  .store-card:hover { border-color: rgba(56, 189, 248, 0.4); box-shadow: 0 12px 40px 0 rgba(0, 0, 0, 0.45); }\n" +
            "  .store-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 8px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }\n" +
            "  .badge-active { background: rgba(34, 197, 94, 0.15); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.3); }\n" +
            "  .badge-raft { background: rgba(168, 85, 247, 0.15); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.3); }\n" +
            "  .badge-engine { background: rgba(56, 189, 248, 0.15); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.3); }\n" +
            "  .badge-records { background: rgba(244, 63, 94, 0.15); color: #f43f5e; border: 1px solid rgba(244, 63, 94, 0.3); }\n" +
            "  .engine-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }\n" +
            "  .table-responsive { overflow-x: auto; width: 100%; border-radius: 10px; border: 1px solid var(--j-border); background: rgba(15, 23, 42, 0.4); }\n" +
            "  .jettra-table { width: 100%; border-collapse: collapse; text-align: left; }\n" +
            "  .jettra-table th { background: rgba(15, 23, 42, 0.8); padding: 14px 18px; font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid var(--j-border); }\n" +
            "  .jettra-table td { padding: 14px 18px; font-size: 13px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); }\n" +
            "  .jettra-table tr:hover td { background: rgba(56, 189, 248, 0.04); }\n" +
            "  .btn-action { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; border-radius: 8px; font-weight: 600; font-size: 13px; text-decoration: none; cursor: pointer; border: none; transition: all 0.2s; }\n" +
            "  .btn-primary { background: linear-gradient(135deg, #0284c7, #2563eb); color: white; box-shadow: 0 4px 14px rgba(37,99,235,0.35); }\n" +
            "  .btn-primary:hover { background: linear-gradient(135deg, #0369a1, #1d4ed8); transform: translateY(-1px); box-shadow: 0 6px 20px rgba(37,99,235,0.5); }\n" +
            "  .btn-secondary { background: rgba(255, 255, 255, 0.06); color: #f8fafc; border: 1px solid var(--j-border); }\n" +
            "  .btn-secondary:hover { background: rgba(255, 255, 255, 0.12); border-color: rgba(255, 255, 255, 0.2); }\n" +
            "  .btn-danger { background: rgba(239, 68, 68, 0.15); color: #f87171; border: 1px solid rgba(239, 68, 68, 0.3); }\n" +
            "  .btn-danger:hover { background: #ef4444; color: white; }\n" +
            "  .pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: #4ade80; display: inline-block; box-shadow: 0 0 8px #4ade80; }\n" +
            "</style>\n"
        );

        // Sidebar Navigation
        WidgetLet overviewMenu = WidgetLet.of("Overview").icon("fas fa-tachometer-alt");
        overviewMenu.add(WidgetLet.of("Dashboard").icon("fas fa-chart-pie").url(JettraServer.resolvePath("/dashboard")));

        WidgetLet storageMenu = WidgetLet.of("Database Core").icon("fas fa-database");
        storageMenu.add(WidgetLet.of("Databases & Components").icon("fas fa-server").url(JettraServer.resolvePath("/databases")));
        storageMenu.add(WidgetLet.of("All 9 Multi-Models").icon("fas fa-cubes").url(JettraServer.resolvePath("/engines")));

        WidgetLet securityMenu = WidgetLet.of("Security & Access").icon("fas fa-shield-alt");
        securityMenu.add(WidgetLet.of("Users & Per-DB Roles").icon("fas fa-users-cog").url(JettraServer.resolvePath("/users")));

        WidgetLet systemMenu = WidgetLet.of("Internals").icon("fas fa-microchip");
        systemMenu.add(WidgetLet.of("Cluster & Components").icon("fas fa-microchip").url(JettraServer.resolvePath("/components")));

        Widget leftSidebar = Left.of(
            SidebarLogo.of("fas fa-layer-group", "JettraStore"),
            SidebarCategory.of("Navigation"),
            overviewMenu,
            SidebarCategory.of("Storage Layer"),
            storageMenu,
            SidebarCategory.of("Administration"),
            securityMenu,
            systemMenu
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-sidebar"));

        // Header
        Widget avatar = Avatar.label(userInitial).shape("circle")
            .modifier(new io.jettra.flux.core.Modifier().style("background: linear-gradient(135deg, #38bdf8, #818cf8); color: white; font-weight: bold; width: 36px; height: 36px; display: inline-flex; align-items: center; justify-content: center; margin-right: 8px;"));

        Widget profileTrigger = Row.of(
            avatar,
            Span.of(loggedUser).modifier(new io.jettra.flux.core.Modifier().style("font-weight: 600; font-size: 14px;")),
            Icon.of("fas fa-chevron-down").modifier(new io.jettra.flux.core.Modifier().style("margin-left: 6px; font-size: 12px; color: #94a3b8;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center; cursor: pointer; padding: 4px 10px; border-radius: 8px; background: rgba(255,255,255,0.05);"));

        Widget profileMenu = ((OverlayMenu) OverlayMenu.of(
            WidgetLet.of("Databases Console").icon("fas fa-server").url(JettraServer.resolvePath("/databases")),
            WidgetLet.of("Security DB Console").icon("fas fa-user-lock").url(JettraServer.resolvePath("/securitydb/admin")),
            WidgetLet.of("API Documentation").icon("fas fa-book").url(JettraServer.resolvePath("/swagger-ui")),
            WidgetLet.of("Sign Out").icon("fas fa-sign-out-alt").url(JettraServer.resolvePath("/login?logout=true"))
        ).trigger(profileTrigger)).alignRight();

        Widget topHeader = Top.of(
            Row.of(
                Icon.of("fas fa-bolt").modifier(new io.jettra.flux.core.Modifier().style("color: #38bdf8; margin-right: 8px; font-size: 20px;")),
                Span.of("JettraStoreEngine").modifier(new io.jettra.flux.core.Modifier().style("font-weight: 700; font-size: 18px; font-family: 'Outfit', sans-serif; letter-spacing: 0.5px;")),
                Span.of("v1.0 (Java 25)").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-active").style("margin-left: 12px; font-size: 11px;")),
                Span.of("<span class='pulse-dot' style='margin-right:6px;'></span> 9 ENGINES ACTIVE").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-records").style("margin-left: 6px; font-size: 11px;"))
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
