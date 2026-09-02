package com.jettra.store.engine.web;

import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.StorageHierarchyNodeData;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.FluxTree;
import io.jettra.flux.widgets.FluxTreeNode;
import io.jettra.flux.widgets.Icon;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Span;
import io.jettra.flux.widgets.Text;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Multi-Model Storage Hierarchy Explorer Tree View.
 * Built with native JettraFlux FluxTree and FluxTreeNode components.
 * Implements:
 * - Composite Pattern: Arbitrary multi-model nesting (Database -> Engine -> Units -> Items).
 * - State and Observer Pattern: Deterministic global Expand ALL and Collapse ALL operations.
 * - Concurrency: Virtual Threads (Thread.ofVirtual()) for non-blocking multi-model metadata discovery.
 * - Accessible WAI-ARIA Treeview semantics.
 */
public final class StorageTreeView {

    public static final String[][] ALL_ENGINE_SPECS = {
        {"DOCUMENT", "#38bdf8", "fas fa-file-code", "Collections", "Collection", "Document", "fas fa-file-alt"},
        {"KEYVALUE", "#10b981", "fas fa-key", "Namespaces", "Namespace", "Key-Value Pair", "fas fa-database"},
        {"VECTOR", "#8b5cf6", "fas fa-project-diagram", "Vector Indexes", "Vector Index", "Embedding", "fas fa-braille"},
        {"GRAPH", "#ec4899", "fas fa-share-alt", "Labels", "Label", "Vertex / Edge", "fas fa-circle-nodes"},
        {"TIMESERIES", "#06b6d4", "fas fa-chart-line", "Metrics", "Metric", "Time Point", "fas fa-stopwatch"},
        {"COLUMN", "#f97316", "fas fa-table", "Column Families", "Column Family", "Dynamic Row", "fas fa-bars-staggered"},
        {"GEOSPATIAL", "#14b8a6", "fas fa-globe-americas", "Spatial Layers", "Spatial Layer", "GIS Feature", "fas fa-location-dot"},
        {"OBJECT", "#a855f7", "fas fa-archive", "Buckets", "Bucket", "BLOB Object", "fas fa-box-archive"},
        {"RECORDS", "#f43f5e", "fas fa-id-card", "Record Tables", "Record Table", "Record", "fas fa-address-card"}
    };

    private StorageTreeView() {}

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String currentColl,
        String actionUrl,
        java.util.Map<String, String> params
    ) {
        return build(selectedEngine, targetDb, currentColl, actionUrl, params, null);
    }

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String currentColl,
        String actionUrl,
        java.util.Map<String, String> params,
        HierarchyExplorerService hierarchyService
    ) {
        int dbIdx = 1;
        String dbContainerId = "db_content_" + dbIdx;
        String dbHeaderId = "db_header_" + dbIdx;
        String dbToggleBtnId = "btn_toggle_" + dbIdx;

        boolean isExpandedRequested = params != null &&
            ("true".equalsIgnoreCase(params.get("expand")) ||
             "expanded".equalsIgnoreCase(params.get("tree_state")));

        // Build native FluxTree component
        FluxTree<StorageHierarchyNodeData> fluxTree = FluxTree.of("storage-hierarchy-tree");
        fluxTree.ariaLabel("Multi-Model Storage Hierarchy for " + targetDb);

        FluxTreeNode<StorageHierarchyNodeData> dbNode = FluxTreeNode.of(
            "node_db_" + targetDb,
            targetDb,
            StorageHierarchyNodeData.forDatabase(selectedEngine, targetDb)
        ).icon("fas fa-database")
         .iconColor("var(--j-primary,#38bdf8)")
         .badge("ACTIVE", "store-badge badge-active");

        // Concurrent multi-model discovery using Virtual Threads
        Map<String, Map<String, List<String>>> engineUnitsMap = new ConcurrentHashMap<>();
        if (hierarchyService != null) {
            List<Thread> vThreads = new ArrayList<>();
            for (String[] spec : ALL_ENGINE_SPECS) {
                String eng = spec[0];
                Thread vt = Thread.ofVirtual().name("tree-discovery-" + eng).start(() -> {
                    try {
                        Map<String, List<String>> units = hierarchyService.discoverUnitsAndItems(eng, targetDb);
                        if (units != null && !units.isEmpty()) {
                            engineUnitsMap.put(eng, units);
                        }
                    } catch (Exception ignored) {}
                });
                vThreads.add(vt);
            }
            for (Thread vt : vThreads) {
                try {
                    vt.join();
                } catch (InterruptedException ignored) {}
            }
        }

        boolean hasAnyItems = false;
        for (String[] spec : ALL_ENGINE_SPECS) {
            String engName = spec[0];
            String engColor = spec[1];
            String engIcon = spec[2];
            String pluralUnit = spec[3];
            String singularUnit = spec[4];
            String itemIcon = spec[6];

            Map<String, List<String>> units = engineUnitsMap.get(engName);
            if (units != null && !units.isEmpty()) {
                hasAnyItems = true;
                int totalEngineItems = 0;
                for (List<String> list : units.values()) {
                    totalEngineItems += list.size();
                }

                FluxTreeNode<StorageHierarchyNodeData> engNode = FluxTreeNode.of(
                    "node_eng_" + engName + "_" + targetDb,
                    engName + " (" + totalEngineItems + " items in " + units.size() + " " + pluralUnit + ")",
                    StorageHierarchyNodeData.forEngine(engName)
                ).icon(engIcon)
                 .iconColor(engColor)
                 .badge(engName, "store-badge");

                for (Map.Entry<String, List<String>> unitEntry : units.entrySet()) {
                    String uName = unitEntry.getKey();
                    List<String> items = unitEntry.getValue();

                    FluxTreeNode<StorageHierarchyNodeData> unitNode = FluxTreeNode.of(
                        "node_unit_" + engName + "_" + uName,
                        uName + " (" + items.size() + " " + (items.size() == 1 ? singularUnit : pluralUnit) + ")",
                        StorageHierarchyNodeData.forUnit(engName, targetDb, uName, items.size())
                    ).icon(engIcon)
                     .iconColor(engColor)
                     .badge(String.valueOf(items.size()), "store-badge");

                    for (String itemId : items) {
                        int vCount = 1;
                        String payload = "{}";
                        String versionsJson = "[]";
                        if (hierarchyService != null) {
                            try {
                                vCount = hierarchyService.getItemVersionCount(engName, targetDb, uName, itemId);
                                payload = hierarchyService.getItemPayload(engName, targetDb, uName, itemId);
                                versionsJson = hierarchyService.getVersionsJson(engName, targetDb, uName, itemId);
                            } catch (Exception ignored) {}
                        }
                        if (payload == null) payload = "{}";
                        if (versionsJson == null) versionsJson = "[]";

                        String pB64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
                        String vB64 = Base64.getEncoder().encodeToString(versionsJson.getBytes(StandardCharsets.UTF_8));

                        // Build Action buttons for item node
                        List<Widget> actionButtons = new ArrayList<>();
                        actionButtons.add(
                            Button.of(Icon.of("fas fa-eye"))
                                .modifier(new Modifier()
                                    .attribute("type", "button")
                                    .attribute("title", "Inspect Record")
                                    .attribute("onclick", "openInspectRecordModal('" + engName + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + pB64 + "', " + vCount + ")")
                                    .style("background:none; border:none; color:#38bdf8; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                        );
                        actionButtons.add(
                            Button.of(Icon.of("fas fa-history"))
                                .modifier(new Modifier()
                                    .attribute("type", "button")
                                    .attribute("title", "Historical Versions")
                                    .attribute("onclick", "openUniversalRestoreModal('" + engName + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + vB64 + "')")
                                    .style("background:none; border:none; color:#a855f7; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                        );
                        actionButtons.add(
                            Button.of(Icon.of("fas fa-edit"))
                                .modifier(new Modifier()
                                    .attribute("type", "button")
                                    .attribute("title", "Edit Record")
                                    .attribute("onclick", "openUniversalEditModal('" + engName + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + pB64 + "')")
                                    .style("background:none; border:none; color:#10b981; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                        );
                        actionButtons.add(
                            Button.of(Icon.of("fas fa-trash-alt"))
                                .modifier(new Modifier()
                                    .attribute("type", "button")
                                    .attribute("title", "Delete Record")
                                    .attribute("onclick", "openUniversalDeleteModal('" + engName + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "')")
                                    .style("background:none; border:none; color:#ef4444; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                        );

                        FluxTreeNode<StorageHierarchyNodeData> itemNode = FluxTreeNode.of(
                            "node_item_" + engName + "_" + uName + "_" + itemId,
                            itemId,
                            StorageHierarchyNodeData.forItem(engName, targetDb, uName, itemId, vCount, System.currentTimeMillis(), "", payload, pB64, vB64)
                        ).icon(itemIcon)
                         .iconColor(engColor)
                         .badge("v" + vCount, "store-badge badge-records")
                         .actions(actionButtons);

                        unitNode.child(itemNode);
                    }
                    engNode.child(unitNode);
                }
                dbNode.child(engNode);
            }
        }

        if (!hasAnyItems) {
            dbNode.child(
                FluxTreeNode.<StorageHierarchyNodeData>of(
                    "node_empty_" + targetDb,
                    "No items recorded in '" + targetDb + "' yet. Use [+ Document] or [+ KeyValue] to add data.",
                    null
                ).icon("fas fa-info-circle")
                 .iconColor("var(--j-text-muted,#94a3b8)")
            );
        }

        fluxTree.root(dbNode);

        // Also add other discovered databases as roots to allow comprehensive multi-database exploration
        if (hierarchyService != null) {
            try {
                Set<String> allDbs = hierarchyService.discoverAllDatabases();
                for (String otherDb : allDbs) {
                    if (!otherDb.equalsIgnoreCase(targetDb)) {
                        FluxTreeNode<StorageHierarchyNodeData> otherDbNode = FluxTreeNode.of(
                            "node_other_db_" + otherDb,
                            otherDb,
                            StorageHierarchyNodeData.forDatabase(selectedEngine, otherDb)
                        ).icon("fas fa-database")
                         .iconColor("var(--j-text-muted,#94a3b8)")
                         .badge("DATABASE", "store-badge")
                         .action(
                             Button.of(Icon.of("fas fa-arrow-right"), Text.of(" Open"))
                                 .modifier(new Modifier()
                                     .attribute("type", "button")
                                     .attribute("onclick", "location.href='" + actionUrl + "&target_db=" + escapeJs(otherDb) + "'")
                                     .style("background:none; border:none; color:var(--j-primary,#38bdf8); font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                         );
                        fluxTree.root(otherDbNode);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Apply state: expandAll or default expand active database
        if (isExpandedRequested) {
            fluxTree.expandAll();
        } else {
            dbNode.expand();
            for (FluxTreeNode<StorageHierarchyNodeData> ch : dbNode.getChildren()) {
                ch.expand();
            }
        }

        // Render card layout preserving test contracts and accessible roles
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

        Widget dbSubtreeContainer = Div.of(fluxTree)
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
            "      if (activeContainer) {\n" +
            "        activeContainer.style.display = 'block';\n" +
            "        activeContainer.setAttribute('aria-expanded', 'true');\n" +
            "        activeContainer.setAttribute('data-state', 'expanded');\n" +
            "        activeContainer.setAttribute('data-loaded', 'true');\n" +
            "        var header = document.getElementById('" + dbHeaderId + "');\n" +
            "        if (header) { header.setAttribute('aria-expanded', 'true'); header.setAttribute('data-state', 'expanded'); }\n" +
            "        var btn = document.getElementById('" + dbToggleBtnId + "');\n" +
            "        if (btn) btn.setAttribute('aria-expanded', 'true');\n" +
            "        var icon = document.getElementById('icon_" + dbContainerId + "');\n" +
            "        if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';\n" +
            "      }\n" +
            "    }\n" +
            "    if (document.readyState === 'loading') {\n" +
            "      document.addEventListener('DOMContentLoaded', function() { setTimeout(autoExpand, 30); });\n" +
            "    } else {\n" +
            "      setTimeout(autoExpand, 30);\n" +
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
