package com.jettra.store.engine.web;

import io.jettra.flux.pages.FluxBaseHandler;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import com.jettra.store.engine.web.RouteVisibilityGuard.NavigationRouteConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Base template layout for JettraStoreEngine Web Management Console.
 * Directly styled to resemble modern multi-model database studio consoles:
 * - Slim Left Icon Rail with branding, system modules, and settings
 * - Context-Aware Top Header with connection badge, optional horizontal section tabs, and action buttons
 * - Responsive workspace supporting Schema, Table, Tree, and Query views in Light and Dark modes.
 */
@NoLoginRequired
public abstract class StoreTemplatePage extends FluxBaseHandler {

    protected abstract String getPageTitle();
    protected abstract Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme);

    protected Set<String> getAvailableDatabases() {
        return new TreeSet<>(Set.of("customers_db", "ExampleDBReferences", "ecommerce_db"));
    }

    /**
     * Determines navigation route visibility policy. Can be overridden by subclasses.
     */
    protected NavigationRouteConfig getRouteConfig(HttpExchange exchange, Map<String, String> params) {
        return RouteVisibilityGuard.resolveConfig(exchange, params, getPageTitle());
    }

    @Override
    public Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String loggedUser = "root";
        if (exchange != null) {
            try {
                String u = getLoggedUser(exchange);
                if (u != null && !u.isBlank()) {
                    loggedUser = u;
                }
            } catch (Exception ignored) {}
        }

        String targetDb = params != null && params.containsKey("target_db") ? params.get("target_db") : "customers_db";
        String currentTab = params != null ? params.getOrDefault("tab", "schema").toLowerCase() : "schema";
        String activeModule = params != null ? params.getOrDefault("module", "database").toLowerCase() : "database";
        String selectedEngine = params != null ? params.getOrDefault("engine", "DOCUMENT").toUpperCase() : "DOCUMENT";
        String currentColl = params != null ? params.getOrDefault("coll", "default") : "default";

        NavigationRouteConfig routeConfig = getRouteConfig(exchange, params);

        Set<String> databases = getAvailableDatabases();
        if (databases == null || databases.isEmpty()) {
            databases = new TreeSet<>(Set.of("customers_db", "ExampleDBReferences"));
        }
        if (!databases.contains(targetDb)) {
            databases.add(targetDb);
        }

        // Modern CSS styling system
        Widget customCss = RawHtml.of(
            "<link rel='preconnect' href='https://fonts.googleapis.com'>\n" +
            "<link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>\n" +
            "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=Outfit:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap' rel='stylesheet'>\n" +
            "<style>\n" +
            "  :root {\n" +
            "    --j-bg-body: var(--jf-bg, #f8fafc);\n" +
            "    --j-bg-surface: var(--jf-surface, #ffffff);\n" +
            "    --j-bg-subsurface: var(--jf-surface-hover, #f1f5f9);\n" +
            "    --j-text-primary: var(--jf-text-primary, #0f172a);\n" +
            "    --j-text-secondary: var(--jf-text-secondary, #475569);\n" +
            "    --j-text-muted: var(--jf-text-secondary, #94a3b8);\n" +
            "    --j-border: var(--jf-border, #e2e8f0);\n" +
            "    --j-border-dark: var(--jf-border, #cbd5e1);\n" +
            "    --j-primary: var(--jf-accent, #0284c7);\n" +
            "    --j-primary-hover: var(--jf-accent, #0369a1);\n" +
            "    --j-primary-light: var(--jf-focus-ring, #e0f2fe);\n" +
            "    --j-accent-records: #10b981;\n" +
            "    --j-accent-docs: #38bdf8;\n" +
            "    --j-accent-vectors: #a855f7;\n" +
            "    --j-accent-graph: #ec4899;\n" +
            "    --j-accent-ts: #06b6d4;\n" +
            "  }\n" +
            "  [data-color-mode='dark'], [data-theme-mode='dark'], [data-theme='dark'], html.dark, body.dark {\n" +
            "    --j-bg-body: var(--jf-bg, #0b0f19);\n" +
            "    --j-bg-surface: var(--jf-surface, #111827);\n" +
            "    --j-bg-subsurface: var(--jf-surface-hover, #1f2937);\n" +
            "    --j-text-primary: var(--jf-text-primary, #f8fafc);\n" +
            "    --j-text-secondary: var(--jf-text-secondary, #cbd5e1);\n" +
            "    --j-text-muted: var(--jf-text-secondary, #64748b);\n" +
            "    --j-border: var(--jf-border, rgba(255, 255, 255, 0.08));\n" +
            "    --j-border-dark: var(--jf-border, rgba(255, 255, 255, 0.15));\n" +
            "    --j-primary: var(--jf-accent, #38bdf8);\n" +
            "    --j-primary-hover: var(--jf-accent, #7dd3fc);\n" +
            "    --j-primary-light: var(--jf-focus-ring, rgba(56, 189, 248, 0.12));\n" +
            "  }\n" +
            "  * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "  body, .jettra-studio-layout { min-height: 100vh; background-color: var(--j-bg-body); color: var(--j-text-primary); font-family: 'Inter', -apple-system, sans-serif; display: flex; overflow: hidden; }\n" +
            "  h1, h2, h3, h4, h5, .brand-font { font-family: 'Outfit', sans-serif; }\n" +
            "  code, pre, .mono { font-family: 'JetBrains Mono', monospace; }\n" +
            "  \n" +
            "  /* Left Slim Icon Rail */\n" +
            "  .jettra-icon-rail { width: 54px; min-width: 54px; background: var(--j-bg-surface); border-right: 1px solid var(--j-border); display: flex; flex-direction: column; justify-content: space-between; align-items: center; padding: 12px 0 16px 0; z-index: 40; }\n" +
            "  .rail-top-section { display: flex; flex-direction: column; align-items: center; width: 100%; gap: 6px; }\n" +
            "  .rail-bottom-section { display: flex; flex-direction: column; align-items: center; width: 100%; gap: 6px; }\n" +
            "  .rail-logo-container { margin-bottom: 12px; display: flex; flex-direction: column; align-items: center; cursor: pointer; text-decoration: none; }\n" +
            "  .rail-logo-badge { width: 36px; height: 36px; border-radius: 8px; display: flex; flex-direction: column; align-items: center; justify-content: center; position: relative; }\n" +
            "  .rail-item { display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 8px; color: var(--j-text-muted); text-decoration: none; transition: all 0.15s ease; cursor: pointer; position: relative; }\n" +
            "  .rail-item:hover { color: var(--j-primary); background: var(--j-primary-light); }\n" +
            "  .rail-item.active { color: var(--j-primary); background: var(--j-primary-light); font-weight: 700; }\n" +
            "  .rail-item.active::before { content: ''; position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; background: var(--j-primary); border-radius: 0 4px 4px 0; }\n" +
            "  .rail-item i { font-size: 17px; margin: 0; }\n" +
            "  \n" +
            "  /* Main Studio Canvas */\n" +
            "  .jettra-main-area { flex: 1; display: flex; flex-direction: column; height: 100vh; overflow: hidden; background: var(--j-bg-body); }\n" +
            "  \n" +
            "  /* Top Header Bar */\n" +
            "  .jettra-top-bar { min-height: 50px; background: var(--j-bg-surface); border-bottom: 1px solid var(--j-border); display: flex; align-items: center; justify-content: space-between; padding: 6px 16px; gap: 10px; z-index: 30; flex-wrap: wrap; }\n" +
            "  .top-left-group { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }\n" +
            "  .conn-chip { display: flex; align-items: center; gap: 7px; font-size: 12px; font-weight: 600; color: var(--j-text-primary); padding: 4px 10px; border-radius: 6px; cursor: pointer; user-select: none; }\n" +
            "  .conn-chip:hover { background: var(--j-bg-subsurface); }\n" +
            "  .conn-chip i.fa-database { color: #38bdf8; font-size: 13px; }\n" +
            "  \n" +
            "  /* Top Navigation Tabs */\n" +
            "  .top-tabs-nav { display: flex; align-items: center; gap: 2px; margin-left: 4px; flex-wrap: wrap; }\n" +
            "  .top-tab { padding: 6px 12px; font-size: 12px; font-weight: 500; color: var(--j-text-secondary); text-decoration: none; border-radius: 6px; transition: all 0.15s ease; position: relative; }\n" +
            "  .top-tab:hover { color: var(--j-primary); background: var(--j-bg-subsurface); }\n" +
            "  .top-tab.active { color: var(--j-primary); font-weight: 700; }\n" +
            "  .top-tab.active::after { content: ''; position: absolute; bottom: -6px; left: 12px; right: 12px; height: 2px; background: var(--j-primary); border-radius: 2px; }\n" +
            "  \n" +
            "  /* Top Right Action Buttons */\n" +
            "  .top-right-group { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }\n" +
            "  .btn-studio-primary { display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border-radius: 6px; background: #0284c7; color: white; font-size: 11.5px; font-weight: 600; text-decoration: none; border: none; cursor: pointer; box-shadow: 0 1px 3px rgba(0,0,0,0.1); transition: all 0.15s; }\n" +
            "  .btn-studio-primary:hover { background: #0369a1; transform: translateY(-0.5px); }\n" +
            "  .btn-studio-secondary { display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border-radius: 6px; background: var(--j-bg-subsurface); color: var(--j-text-secondary); font-size: 11.5px; font-weight: 600; text-decoration: none; border: 1px solid var(--j-border); cursor: pointer; transition: all 0.15s; }\n" +
            "  .btn-studio-secondary:hover { color: var(--j-text-primary); border-color: var(--j-border-dark); background: var(--j-border); }\n" +
            "  \n" +
            "  /* Studio Workspace */\n" +
            "  .jettra-workspace-body { flex: 1; display: flex; overflow: hidden; }\n" +
            "  \n" +
            "  /* Badges & Shared UI elements */\n" +
            "  .store-badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 7px; border-radius: 6px; font-size: 10px; font-weight: 600; text-transform: uppercase; }\n" +
            "  .badge-active { background: rgba(34, 197, 94, 0.15); color: #16a34a; border: 1px solid rgba(34, 197, 94, 0.3); }\n" +
            "  .badge-records { background: rgba(244, 63, 94, 0.15); color: #f43f5e; border: 1px solid rgba(244, 63, 94, 0.3); }\n" +
            "  .btn-action { display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border-radius: 6px; font-weight: 600; font-size: 11.5px; text-decoration: none; cursor: pointer; border: none; transition: all 0.2s; }\n" +
            "  .btn-primary { background: #0284c7; color: white; }\n" +
            "  .btn-primary:hover { background: #0369a1; }\n" +
            "  .btn-secondary { background: var(--j-bg-subsurface); color: var(--j-text-primary); border: 1px solid var(--j-border); }\n" +
            "  .btn-secondary:hover { background: var(--j-border); }\n" +
            "  .btn-danger { background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3); }\n" +
            "  .btn-danger:hover { background: #ef4444; color: white; }\n" +
            "</style>\n" +
            "<script>\n" +
            "  function getSelectedTopDatabase() {\n" +
            "    var sel = document.getElementById('topDatabaseSelect');\n" +
            "    return sel ? sel.value : '" + targetDb + "';\n" +
            "  }\n" +
            "</script>\n"
        );

        // Build Left Slim Icon Rail
        Widget iconRail = buildIconRail(targetDb, selectedEngine, currentTab, activeModule);

        // Build Top Bar Left & Right Groups Conditionally
        Widget topLeftGroup = buildTopLeftGroup(routeConfig, loggedUser, targetDb, selectedEngine, currentTab, databases);
        Widget topRightGroup = buildTopRightGroup(routeConfig, selectedEngine, targetDb, currentColl, currentTheme);

        Widget topBar = Div.of(topLeftGroup, topRightGroup)
            .modifier(new Modifier().cssClass("jettra-top-bar"));

        // Content Area
        Widget content = buildContent(exchange, params, currentTheme);

        Widget mainArea = Div.of(
            topBar,
            Div.of(content).modifier(new Modifier().cssClass("jettra-workspace-body"))
        ).modifier(new Modifier().cssClass("jettra-main-area"));

        Widget scaffold = Div.of(
            iconRail,
            mainArea
        ).modifier(new Modifier().cssClass("jettra-studio-layout"));

        return Column.of(
            customCss,
            scaffold
        );
    }

    private Widget buildTopLeftGroup(
        NavigationRouteConfig config,
        String loggedUser,
        String targetDb,
        String selectedEngine,
        String currentTab,
        Set<String> databases
    ) {
        List<Widget> leftItems = new ArrayList<>();

        if (config.showDatabaseSelector()) {
            StringBuilder dbOptionsHtml = new StringBuilder();
            for (String db : databases) {
                String sel = db.equalsIgnoreCase(targetDb) ? " selected" : "";
                dbOptionsHtml.append("<option value='").append(db).append("'").append(sel).append(">")
                    .append(db).append("</option>");
            }

            Widget dbSelector = RawHtml.of(
                "<div style='display:inline-flex; align-items:center; gap:6px; margin-right:6px;'>" +
                "<i class='fas fa-database' style='color:#0284c7; font-size:13px;'></i>" +
                "<span style='font-size:12px; font-weight:500; color:var(--j-text-secondary);'>Connected as <strong style='color:var(--j-text-primary);'>" + loggedUser + "</strong> @</span>" +
                "<div style='position:relative; display:inline-flex; align-items:center;'>" +
                "<select id='topDatabaseSelect' onchange=\"location.href='" + JettraServer.resolvePath("/engines?target_db=") + "' + encodeURIComponent(this.value) + '&engine=" + selectedEngine + "&tab=" + currentTab + "';\" style='background:var(--j-bg-subsurface); color:var(--j-primary); border:1px solid var(--j-border); padding:3px 22px 3px 8px; border-radius:6px; font-size:12px; font-weight:700; cursor:pointer; outline:none; appearance:none; -webkit-appearance:none;'>" +
                dbOptionsHtml +
                "</select>" +
                "<i class='fas fa-caret-down' style='position:absolute; right:7px; pointer-events:none; font-size:10px; color:var(--j-text-muted);'></i>" +
                "</div>" +
                "<span style='font-size:11px; color:var(--j-text-muted); font-weight:500;'>(" + databases.size() + ")</span>" +
                "</div>"
            );
            leftItems.add(dbSelector);
        } else {
            // Clean connection indicator for Global Dashboard route
            Widget cleanConnChip = Div.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#0284c7; font-size:13px; margin-right:6px;")),
                Span.of("Connected as ").modifier(new Modifier().style("font-size:12px; font-weight:500; color:var(--j-text-secondary);")),
                Span.of(loggedUser).modifier(new Modifier().style("color:var(--j-text-primary); font-weight:700; font-size:12px; margin-right:4px;")),
                Span.of("@ JettraDB Cluster").modifier(new Modifier().style("font-size:12px; color:var(--j-text-muted); font-weight:500;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center; padding:4px 0;"));
            leftItems.add(cleanConnChip);
        }

        if (config.showTopNavigationTabs()) {
            Widget topTabs = Div.of(
                Link.of(JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=" + targetDb), "Schema")
                    .modifier(new Modifier().cssClass("top-tab" + ("schema".equals(currentTab) ? " active" : ""))),
                Link.of(JettraServer.resolvePath("/engines?tab=buckets&engine=OBJECT&target_db=" + targetDb), "Buckets")
                    .modifier(new Modifier().cssClass("top-tab" + ("buckets".equals(currentTab) ? " active" : ""))),
                Link.of(JettraServer.resolvePath("/engines?tab=indexes&engine=" + selectedEngine + "&target_db=" + targetDb), "Indexes")
                    .modifier(new Modifier().cssClass("top-tab" + ("indexes".equals(currentTab) ? " active" : ""))),
                Link.of(JettraServer.resolvePath("/engines?tab=dictionary&engine=" + selectedEngine + "&target_db=" + targetDb), "Dictionary")
                    .modifier(new Modifier().cssClass("top-tab" + ("dictionary".equals(currentTab) ? " active" : ""))),
                Link.of(JettraServer.resolvePath("/engines?tab=metrics&target_db=" + targetDb), "Metrics")
                    .modifier(new Modifier().cssClass("top-tab" + ("metrics".equals(currentTab) ? " active" : ""))),
                Link.of(JettraServer.resolvePath("/engines?tab=settings&target_db=" + targetDb), "Settings")
                    .modifier(new Modifier().cssClass("top-tab" + ("settings".equals(currentTab) ? " active" : "")))
            ).modifier(new Modifier().cssClass("top-tabs-nav"));
            leftItems.add(topTabs);
        }

        return Div.of(leftItems.toArray(new Widget[0]))
            .modifier(new Modifier().cssClass("top-left-group"));
    }

    private Widget buildTopRightGroup(
        NavigationRouteConfig config,
        String selectedEngine,
        String targetDb,
        String currentColl,
        String currentTheme
    ) {
        List<Widget> rightItems = new ArrayList<>();

        if (config.showGlobalActionButtons()) {
            rightItems.add(
                Button.of(Icon.of("fas fa-database"), Text.of(" + DB"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Create Database").attribute("onclick", "if(typeof showModal === 'function') showModal('createDbModal'); else location.href='" + JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=" + targetDb) + "';").cssClass("btn-studio-primary"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-folder-plus"), Text.of(" + Unit"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Add Unit / Collection").attribute("onclick", "if(typeof showModal === 'function') showModal('createUnitModal'); else location.href='" + JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=" + targetDb) + "';").cssClass("btn-studio-secondary"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-download"), Text.of(" Backup"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Backup Database").attribute("onclick", "var db=getSelectedTopDatabase(); if(typeof openBackupDbModal === 'function') openBackupDbModal(db); else location.href='" + JettraServer.resolvePath("/engines?tab=backup&target_db=") + "' + encodeURIComponent(db);").cssClass("btn-studio-secondary").style("color:#16a34a; background:rgba(34,197,94,0.12); border-color:rgba(34,197,94,0.3);"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-upload"), Text.of(" Restore"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Restore Database").attribute("onclick", "var db=getSelectedTopDatabase(); if(typeof openRestoreDbModal === 'function') openRestoreDbModal(db); else location.href='" + JettraServer.resolvePath("/engines?tab=backup&target_db=") + "' + encodeURIComponent(db);").cssClass("btn-studio-secondary").style("color:#9333ea; background:rgba(168,85,247,0.12); border-color:rgba(168,85,247,0.3);"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-file-export"), Text.of(" Export"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Export Data").attribute("onclick", "var db=getSelectedTopDatabase(); if(typeof openExportDataModal === 'function') openExportDataModal('" + selectedEngine + "', db, '" + currentColl + "'); else location.href='" + JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=") + "' + encodeURIComponent(db);").cssClass("btn-studio-secondary").style("color:#d97706; background:rgba(234,179,8,0.12); border-color:rgba(234,179,8,0.3);"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-search-plus"), Text.of(" Búsqueda Avanzada"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Búsqueda Avanzada").attribute("onclick", "var db=getSelectedTopDatabase(); if(typeof openAdvancedSearchModal === 'function') openAdvancedSearchModal('" + selectedEngine + "', db, '" + currentColl + "'); else location.href='" + JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=") + "' + encodeURIComponent(db);").cssClass("btn-studio-secondary").style("color:#0284c7; background:rgba(56,189,248,0.12); border-color:rgba(56,189,248,0.3);"))
            );
            rightItems.add(
                Button.of(Icon.of("fas fa-cubes"), Text.of(" Sample DBs"))
                    .modifier(new Modifier().attribute("type", "button").attribute("title", "Sample DBs").attribute("onclick", "if(typeof openSampleDatabasesModal === 'function') openSampleDatabasesModal(); else location.href='" + JettraServer.resolvePath("/engines?target_db=" + targetDb) + "';").cssClass("btn-studio-secondary").style("color:#db2777; background:rgba(236,72,153,0.12); border-color:rgba(236,72,153,0.3);"))
            );
        }

        if (config.showThemeToggle()) {
            rightItems.add(
                Div.of(
                    io.jettra.flux.widgets.ThemeSelectDropdown.of().current(currentTheme).asNativeSelect(true).modifier(new Modifier().style("margin-left: 4px;")),
                    io.jettra.flux.widgets.ThemeModeToggle.of().size(18).modifier(new Modifier().style("margin-left: 4px;"))
                ).modifier(new Modifier().style("display:inline-flex; align-items:center; gap:4px;"))
            );
        }

        return Div.of(rightItems.toArray(new Widget[0]))
            .modifier(new Modifier().cssClass("top-right-group"));
    }

    private Widget buildIconRail(String targetDb, String selectedEngine, String currentTab, String activeModule) {
        Widget railLogo = Link.of(JettraServer.resolvePath("/dashboard"),
            RawHtml.of(
                "<svg width='32' height='32' viewBox='0 0 40 40' fill='none' xmlns='http://www.w3.org/2000/svg' style='filter: drop-shadow(0 2px 4px rgba(0,0,0,0.15));'>" +
                "<path d='M20 4L36 32H4L20 4Z' fill='url(#jdbGrad)'/>" +
                "<path d='M20 14L28 28H12L20 14Z' fill='white' fill-opacity='0.92'/>" +
                "<defs>" +
                "<linearGradient id='jdbGrad' x1='4' y1='4' x2='36' y2='32' gradientUnits='userSpaceOnUse'>" +
                "<stop stop-color='#f97316'/>" +
                "<stop offset='0.45' stop-color='#eab308'/>" +
                "<stop offset='0.75' stop-color='#22c55e'/>" +
                "<stop offset='1' stop-color='#06b6d4'/>" +
                "</linearGradient>" +
                "</defs>" +
                "</svg>"
            )
        ).modifier(new Modifier().cssClass("rail-logo-container").attribute("title", "JettraDB Studio"));

        Widget railTop = Div.of(
            railLogo,
            Link.of(JettraServer.resolvePath("/engines?tab=query&target_db=" + targetDb),
                Icon.of("fas fa-terminal")
            ).modifier(new Modifier().cssClass("rail-item" + ("query".equals(currentTab) ? " active" : "")).attribute("title", "Query")),
            Link.of(JettraServer.resolvePath("/engines?tab=schema&engine=" + selectedEngine + "&target_db=" + targetDb),
                Icon.of("fas fa-database")
            ).modifier(new Modifier().cssClass("rail-item" + ("database".equals(activeModule) && !"query".equals(currentTab) ? " active" : "")).attribute("title", "DATABASE")),
            Link.of(JettraServer.resolvePath("/engines?engine=TIMESERIES&target_db=" + targetDb),
                Icon.of("fas fa-chart-line")
            ).modifier(new Modifier().cssClass("rail-item" + ("TIMESERIES".equalsIgnoreCase(selectedEngine) ? " active" : "")).attribute("title", "MESERIES")),
            Link.of(JettraServer.resolvePath("/components"),
                Icon.of("fas fa-server")
            ).modifier(new Modifier().cssClass("rail-item").attribute("title", "SERVER")),
            Link.of(JettraServer.resolvePath("/engines?tab=metrics&target_db=" + targetDb),
                Icon.of("fas fa-tachometer-alt")
            ).modifier(new Modifier().cssClass("rail-item" + ("metrics".equals(currentTab) ? " active" : "")).attribute("title", "PROFILE")),
            Link.of(JettraServer.resolvePath("/users"),
                Icon.of("fas fa-shield-alt")
            ).modifier(new Modifier().cssClass("rail-item").attribute("title", "SECURITY")),
            Link.of(JettraServer.resolvePath("/swagger-ui"),
                Icon.of("fas fa-plug")
            ).modifier(new Modifier().cssClass("rail-item").attribute("title", "API")),
            Link.of(JettraServer.resolvePath("/information"),
                Icon.of("fas fa-info-circle")
            ).modifier(new Modifier().cssClass("rail-item").attribute("title", "INFO")),
            Link.of(JettraServer.resolvePath("/engines?engine=VECTOR&target_db=" + targetDb),
                Icon.of("fas fa-robot")
            ).modifier(new Modifier().cssClass("rail-item" + ("VECTOR".equalsIgnoreCase(selectedEngine) ? " active" : "")).attribute("title", "AI"))
        ).modifier(new Modifier().cssClass("rail-top-section"));

        Widget railBottom = Div.of(
            Link.of(JettraServer.resolvePath("/engines?tab=settings&target_db=" + targetDb),
                Icon.of("fas fa-cog")
            ).modifier(new Modifier().cssClass("rail-item" + ("settings".equals(currentTab) ? " active" : "")).attribute("title", "Settings")),
            Link.of(JettraServer.resolvePath("/login?logout=true"),
                Icon.of("fas fa-power-off")
            ).modifier(new Modifier().cssClass("rail-item").attribute("title", "Sign Out"))
        ).modifier(new Modifier().cssClass("rail-bottom-section"));

        return Div.of(railTop, railBottom)
            .modifier(new Modifier().cssClass("jettra-icon-rail"));
    }
}
