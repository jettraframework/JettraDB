package com.jettra.store.engine.web;

import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dynamic Tree View component for JettraDB Multi-Model Storage Hierarchy Explorer.
 * Scoped strictly to the active database with contextual hierarchical expansion based on the active TYPE selector.
 * Completely eliminates redundant [Explore DB] buttons and provides accessible keyboard and toggle handlers.
 */
public final class StorageTreeView {

    private StorageTreeView() {}

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String currentColl,
        String actionUrl,
        Map<String, String> params
    ) {
        int dbIdx = 1;
        String dbContainerId = "db_content_" + dbIdx;
        String dbHeaderId = "db_header_" + dbIdx;
        String dbToggleBtnId = "btn_toggle_" + dbIdx;

        Widget dbToggleBtn = Button.of(
            Icon.of("fas fa-chevron-right tree-toggle-icon")
                .id("icon_" + dbContainerId)
                .modifier(new Modifier().style("color:var(--j-primary); font-size:10px; pointer-events:none;"))
        ).id(dbToggleBtnId)
         .modifier(new Modifier()
            .attribute("type", "button")
            .attribute("aria-label", "Toggle " + targetDb + " database subtree")
            .attribute("aria-controls", dbContainerId)
            .attribute("aria-expanded", "false")
            .attribute("data-db", targetDb)
            .attribute("data-container-id", dbContainerId)
            .attribute("onclick", "toggleLazyDbSubtree(event, '" + dbContainerId + "', '" + escapeJs(targetDb) + "', '" + escapeJs(selectedEngine) + "', '" + escapeJs(actionUrl) + "', " + dbIdx + ")")
            .style("background:none; border:none; padding:2px 5px; margin-right:3px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;"));

        Widget dbLeft = Div.of(
            dbToggleBtn,
            Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:4px; color:var(--j-primary); font-size:11px; pointer-events:none;")),
            Span.of(targetDb).modifier(new Modifier().style("color:var(--j-primary); font-weight:700; font-size:11px; cursor:pointer;"))
        ).id(dbHeaderId)
         .attribute("data-db", targetDb)
         .attribute("data-state", "collapsed")
         .attribute("role", "treeitem")
         .attribute("tabindex", "0")
         .attribute("aria-expanded", "false")
         .attribute("aria-controls", dbContainerId)
         .modifier(new Modifier()
            .attribute("onclick", "toggleLazyDbSubtree(event, '" + dbContainerId + "', '" + escapeJs(targetDb) + "', '" + escapeJs(selectedEngine) + "', '" + escapeJs(actionUrl) + "', " + dbIdx + ")")
            .attribute("onkeydown", "handleLazyTreeKeyDown(event, '" + dbContainerId + "', '" + escapeJs(targetDb) + "', '" + escapeJs(selectedEngine) + "', '" + escapeJs(actionUrl) + "', " + dbIdx + ")")
            .style("display:inline-flex; align-items:center; cursor:pointer; outline:none; user-select:none;"));

        List<Widget> dbRightWidgets = new ArrayList<>();
        dbRightWidgets.add(Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:8px; padding:1px 5px; margin-left:4px;")));
        dbRightWidgets.add(
            Button.of(Icon.of("fas fa-sync-alt"))
                .modifier(new Modifier().attribute("type", "button").attribute("title", "Refresh database hierarchy").attribute("onclick", "event.stopPropagation(); refreshLazyDbSubtree(event, '" + dbContainerId + "', '" + escapeJs(targetDb) + "', '" + escapeJs(selectedEngine) + "', '" + escapeJs(actionUrl) + "', " + dbIdx + ")").style("background:none; border:none; color:var(--j-text-muted); font-size:9px; cursor:pointer; padding:1px 4px; margin-right:2px;"))
        );
        Widget dbRight = Div.of(dbRightWidgets.toArray(new Widget[0]));

        Widget dbHeaderRow = Div.of(dbLeft, dbRight)
            .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:3px 4px;"));

        Widget dbSubtreeContainer = Div.of()
            .id(dbContainerId)
            .attribute("data-db", targetDb)
            .attribute("data-loaded", "false")
            .attribute("data-state", "collapsed")
            .attribute("data-db-idx", String.valueOf(dbIdx))
            .attribute("aria-expanded", "false")
            .modifier(new Modifier().cssClass("tree-collapsible-content db-subtree-container").style("margin-left:8px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:6px; margin-top:3px; display:none;"));

        Widget dbCard = Div.of(dbHeaderRow, dbSubtreeContainer)
            .modifier(new Modifier().style("margin-bottom:6px; padding:4px 8px; border-radius:6px; background:var(--j-primary-light); border:1px solid var(--j-primary);"));

        Widget treeBody = Div.of(dbCard)
            .modifier(new Modifier().style("max-height:600px; overflow-y:auto; padding-right:4px;"));

        Widget treeInitScript = RawHtml.of(
            "<script>\n" +
            "  window.lastActionUrl = '" + escapeJs(actionUrl) + "';\n" +
            "  window.lastSelectedEngine = '" + escapeJs(selectedEngine) + "';\n" +
            "  (function() {\n" +
            "    function autoExpand() {\n" +
            "      var activeContainer = document.getElementById('" + dbContainerId + "');\n" +
            "      if (activeContainer && activeContainer.getAttribute('data-loaded') !== 'true') {\n" +
            "        toggleLazyDbSubtree(null, '" + dbContainerId + "', '" + escapeJs(targetDb) + "', '" + escapeJs(selectedEngine) + "', '" + escapeJs(actionUrl) + "', " + dbIdx + ");\n" +
            "      }\n" +
            "    }\n" +
            "    if (document.readyState === 'loading') {\n" +
            "      document.addEventListener('DOMContentLoaded', function() { setTimeout(autoExpand, 50); });\n" +
            "    } else {\n" +
            "      setTimeout(autoExpand, 50);\n" +
            "    }\n" +
            "  })();\n" +
            "</script>\n"
        );

        return Div.of(treeBody, treeInitScript);
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }
}
