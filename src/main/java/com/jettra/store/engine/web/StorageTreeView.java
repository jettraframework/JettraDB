package com.jettra.store.engine.web;

import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.StorageHierarchyNodeData;
import io.jettra.flux.core.FluxEscapers;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.Button;
import io.jettra.flux.widgets.Div;
import io.jettra.flux.widgets.FluxTree;
import io.jettra.flux.widgets.FluxTreeNode;
import io.jettra.flux.widgets.JettraTreeNode;
import io.jettra.flux.widgets.JettraCollapsible;
import io.jettra.flux.widgets.Icon;
import io.jettra.flux.widgets.RawHtml;
import io.jettra.flux.widgets.Span;
import io.jettra.flux.widgets.Text;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

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

    private static final JettraJson JSON_PARSER = new JettraJson();

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

                FluxTreeNode<StorageHierarchyNodeData> engNode = JettraTreeNode.of(
                    "node_eng_" + engName + "_" + targetDb,
                    engName + " (" + totalEngineItems + " items in " + units.size() + " " + pluralUnit + ")",
                    StorageHierarchyNodeData.forEngine(engName)
                ).icon(engIcon)
                 .iconColor(engColor)
                 .badge(engName, "store-badge")
                 .withDetails(buildEngineDetailPanel(engName, engColor, targetDb, units.size(), totalEngineItems, pluralUnit))
                 .action(
                     Button.of(Icon.of("fas fa-plus"))
                         .modifier(new Modifier()
                             .attribute("type", "button")
                             .attribute("title", "Insertar Registro en " + engName)
                             .attribute("onclick", "openEngineInsertModal('" + engName + "', 'default', '" + escapeJs(targetDb) + "')")
                             .style("background:none; border:none; color:" + engColor + "; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                 );

                for (Map.Entry<String, List<String>> unitEntry : units.entrySet()) {
                    String uName = unitEntry.getKey();
                    List<String> items = unitEntry.getValue();

                    FluxTreeNode<StorageHierarchyNodeData> unitNode = JettraTreeNode.of(
                        "node_unit_" + engName + "_" + uName,
                        uName + " (" + items.size() + " " + (items.size() == 1 ? singularUnit : pluralUnit) + ")",
                        StorageHierarchyNodeData.forUnit(engName, targetDb, uName, items.size())
                    ).icon(engIcon)
                     .iconColor(engColor)
                     .badge(String.valueOf(items.size()), "store-badge")
                     .withDetails(buildUnitDetailPanel(engName, engColor, targetDb, uName, items.size(), singularUnit, pluralUnit))
                     .action(
                         Button.of(Icon.of("fas fa-plus"))
                             .modifier(new Modifier()
                                 .attribute("type", "button")
                                 .attribute("title", "Insertar Registro en " + uName)
                                 .attribute("onclick", "openEngineInsertModal('" + engName + "', '" + escapeJs(uName) + "', '" + escapeJs(targetDb) + "')")
                                 .style("background:none; border:none; color:" + engColor + "; font-size:9.5px; cursor:pointer; padding:1px 4px;"))
                     );

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

                        FluxTreeNode<StorageHierarchyNodeData> itemNode = JettraTreeNode.of(
                            "node_item_" + engName + "_" + uName + "_" + itemId,
                            itemId,
                            StorageHierarchyNodeData.forItem(engName, targetDb, uName, itemId, vCount, System.currentTimeMillis(), "", payload, pB64, vB64)
                        ).icon(itemIcon)
                         .iconColor(engColor)
                         .badge("v" + vCount, "store-badge badge-records")
                         .actions(actionButtons)
                         .withDetails(buildItemDetailPanel(engName, engColor, targetDb, uName, itemId, vCount, payload, pB64, vB64));

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

        boolean isExpandedRequested = params != null &&
            ("true".equalsIgnoreCase(params.get("expand")) ||
             "expanded".equalsIgnoreCase(params.get("tree_state")));
        boolean isCollapsedRequested = params != null &&
            ("false".equalsIgnoreCase(params.get("expand")) ||
             "collapsed".equalsIgnoreCase(params.get("tree_state")));

        // Apply state: expandAll or default expand active database
        if (isExpandedRequested) {
            fluxTree.expandAll();
        } else if (isCollapsedRequested) {
            fluxTree.collapseToRoot();
        } else {
            dbNode.expand();
            for (FluxTreeNode<StorageHierarchyNodeData> ch : dbNode.getChildren()) {
                ch.expand();
            }
        }

        boolean isSubtreeOpen = !isCollapsedRequested;
        String subtreeDisplay = isSubtreeOpen ? "block" : "none";
        String subtreeState = isSubtreeOpen ? "expanded" : "collapsed";
        String subtreeAria = String.valueOf(isSubtreeOpen);
        String toggleIconClass = isSubtreeOpen ? "fas fa-chevron-down tree-toggle-icon" : "fas fa-chevron-right tree-toggle-icon";

        // Render card layout preserving test contracts and accessible roles
        Widget dbToggleBtn = Button.of(
            Icon.of(toggleIconClass)
                .id("icon_" + dbContainerId)
                .modifier(new Modifier().style("color:var(--j-primary); font-size:10px; pointer-events:none;"))
        ).id(dbToggleBtnId)
         .modifier(new Modifier()
            .attribute("type", "button")
            .attribute("aria-label", "Toggle " + targetDb + " database subtree")
            .attribute("aria-controls", dbContainerId)
            .attribute("aria-expanded", subtreeAria)
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
         .attribute("data-state", subtreeState)
         .attribute("role", "treeitem")
         .attribute("tabindex", "0")
         .attribute("aria-expanded", subtreeAria)
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
            .attribute("data-loaded", "true")
            .attribute("data-state", subtreeState)
            .attribute("data-db-idx", String.valueOf(dbIdx))
            .attribute("aria-expanded", subtreeAria)
            .modifier(new Modifier().cssClass("tree-collapsible-content db-subtree-container").style("margin-left:8px; border-left: 2px dashed rgba(56,189,248,0.3); padding-left:6px; margin-top:3px; display:" + subtreeDisplay + ";"));

        Widget dbCard = Div.of(dbHeaderRow, dbSubtreeContainer)
            .modifier(new Modifier().style("margin-bottom:6px; padding:4px 8px; border-radius:6px; background:var(--j-primary-light); border:1px solid var(--j-primary);"));

        Widget treeBody = Div.of(dbCard)
            .modifier(new Modifier().style("max-height:600px; overflow-y:auto; padding-right:4px;"));

        Widget treeInitScript = RawHtml.of(
            "<script>\n" +
            "  window.lastActionUrl = '" + escapeJs(actionUrl) + "';\n" +
            "  window.lastSelectedEngine = '" + escapeJs(selectedEngine) + "';\n" +
            "</script>\n"
        );

        return Div.of(treeBody, treeInitScript);
    }

    private static Widget buildItemDetailPanel(
        String engName,
        String engColor,
        String targetDb,
        String uName,
        String itemId,
        int vCount,
        String payload,
        String pB64,
        String vB64
    ) {
        String pfx = switch (engName.toUpperCase()) {
            case "RECORDS" -> "rec:";
            case "KEYVALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };
        String primaryAddr = pfx + targetDb + ":" + (uName.equals("default") ? "" : uName + ":") + itemId;
        byte[] payloadBytes = (payload != null ? payload : "{}").getBytes(StandardCharsets.UTF_8);
        String sizeFormatted = payloadBytes.length >= 1024 ? String.format("%.1f KB", payloadBytes.length / 1024.0) : payloadBytes.length + " B";

        JsonObject parsed;
        try {
            parsed = JSON_PARSER.fromJson(payload, JsonObject.class);
            if (parsed == null) parsed = new JsonObject();
        } catch (Exception e) {
            parsed = new JsonObject();
            parsed.addProperty("raw", payload != null ? payload : "{}");
        }

        List<Widget> detailElements = new ArrayList<>();

        // 1. Technical Meta Header Bar: Address, Engine, Memory Size, Version
        Widget metaHeader = Div.of(
            Span.of("📍 " + primaryAddr).modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:600; font-size:9.5px;")),
            Div.of(
                Span.of(engName).modifier(new Modifier().cssClass("store-badge").style("font-size:8px; padding:1px 5px; color:" + engColor + "; border:1px solid " + engColor + "; margin-right:4px;")),
                Span.of("v" + vCount).modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:8px; padding:1px 5px; margin-right:4px;")),
                Span.of("Mem: " + sizeFormatted).modifier(new Modifier().style("color:#38bdf8; font-size:8.5px; font-weight:500;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:4px; margin-bottom:5px;"));
        detailElements.add(metaHeader);

        // 2. Engine-Specific Technical Inspection Badges
        List<Widget> specBadges = new ArrayList<>();
        switch (engName.toUpperCase()) {
            case "VECTOR" -> {
                specBadges.add(Span.of("🧠 Dimension: 128 (Float32)").modifier(new Modifier().style("color:#a78bfa; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("📐 Metric: Cosine Similarity").modifier(new Modifier().style("color:#c084fc; font-size:8.5px; margin-right:8px; font-weight:500;")));
                specBadges.add(Span.of("⚡ Index: HNSW M=16").modifier(new Modifier().style("color:#e9d5ff; font-size:8.5px; font-weight:500;")));
            }
            case "GRAPH" -> {
                specBadges.add(Span.of("🕸️ Entity: Vertex / Node").modifier(new Modifier().style("color:#f472b6; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("🔗 Adjacency: Directed Graph").modifier(new Modifier().style("color:#f9a8d4; font-size:8.5px; font-weight:500;")));
            }
            case "TIMESERIES" -> {
                specBadges.add(Span.of("⏱️ Resolution: Raw 1ms").modifier(new Modifier().style("color:#22d3ee; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("📊 Storage: Append-Only Columnar Block").modifier(new Modifier().style("color:#67e8f9; font-size:8.5px; font-weight:500;")));
            }
            case "KEYVALUE" -> {
                specBadges.add(Span.of("🔑 Partition Key: Hash Bucket").modifier(new Modifier().style("color:#34d399; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("💾 Format: " + (payload != null && payload.startsWith("{") ? "JSON Document" : "Binary/UTF-8")).modifier(new Modifier().style("color:#6ee7b7; font-size:8.5px; font-weight:500;")));
            }
            case "COLUMN" -> {
                specBadges.add(Span.of("🏛️ Column Family: " + uName).modifier(new Modifier().style("color:#fb923c; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("📑 Sparse Row Encoding").modifier(new Modifier().style("color:#fdba74; font-size:8.5px; font-weight:500;")));
            }
            case "GEOSPATIAL" -> {
                specBadges.add(Span.of("🌍 CRS: EPSG:4326 (WGS 84)").modifier(new Modifier().style("color:#2dd4bf; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("🗺️ Spatial Index: R-Tree").modifier(new Modifier().style("color:#5eead4; font-size:8.5px; font-weight:500;")));
            }
            case "OBJECT" -> {
                specBadges.add(Span.of("📦 Bucket: " + uName).modifier(new Modifier().style("color:#c084fc; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("🗄️ Chunked BLOB Storage").modifier(new Modifier().style("color:#d8b4fe; font-size:8.5px; font-weight:500;")));
            }
            default -> {
                specBadges.add(Span.of("📄 Format: JSON Document").modifier(new Modifier().style("color:#38bdf8; font-size:8.5px; margin-right:8px; font-weight:600;")));
                specBadges.add(Span.of("🔍 Schema: Dynamic BSON").modifier(new Modifier().style("color:#7dd3fc; font-size:8.5px; font-weight:500;")));
            }
        }
        Widget specRow = Div.of(specBadges.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; margin-bottom:5px; background:rgba(0,0,0,0.15); padding:2px 6px; border-radius:3px;"));
        detailElements.add(specRow);

        // 3. Properties Preview Grid
        List<Widget> propRows = new ArrayList<>();
        int propCount = 0;
        for (String key : parsed.keySet()) {
            if (propCount >= 6) {
                propRows.add(Span.of("... and " + (parsed.keySet().size() - propCount) + " more attribute(s)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
                break;
            }
            propCount++;
            Object val = parsed.get(key);
            String valStr = val != null ? val.toString() : "null";
            if (valStr.length() > 65) valStr = valStr.substring(0, 65) + "...";

            boolean isJref = valStr.contains("jref://");
            Widget valWidget = Span.of(valStr).modifier(new Modifier().style("color:" + (isJref ? "#38bdf8" : "#f1f5f9") + "; font-family:monospace; font-size:8px;"));

            Widget propRow = Div.of(
                Span.of(key + ": ").modifier(new Modifier().style("color:#94a3b8; font-weight:600; font-size:8px; margin-right:4px;")),
                valWidget
            ).modifier(new Modifier().style("padding:1px 0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));
            propRows.add(propRow);
        }

        if (propRows.isEmpty()) {
            propRows.add(Span.of("(No parsed JSON fields)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
        }

        Widget propsContainer = Div.of(propRows.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:1px; background:rgba(0,0,0,0.22); padding:4px 6px; border-radius:4px; margin-bottom:5px;"));
        detailElements.add(propsContainer);

        // 4. Action Toolbar inside Detail Panel
        Widget detailActions = Div.of(
            Button.of(Icon.of("fas fa-search-plus"), Text.of(" Inspeccionar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('" + escapeJs(engName) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + pB64 + "', " + vCount + ")").style("background:rgba(56,189,248,0.12); border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:8.5px; cursor:pointer; padding:2px 6px; border-radius:3px; display:inline-flex; align-items:center; gap:3px;")),
            Button.of(Icon.of("fas fa-edit"), Text.of(" Editar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + escapeJs(engName) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + pB64 + "')").style("background:rgba(251,191,36,0.12); border:1px solid rgba(251,191,36,0.3); color:#fbbf24; font-size:8.5px; cursor:pointer; padding:2px 6px; border-radius:3px; display:inline-flex; align-items:center; gap:3px;")),
            Button.of(Icon.of("fas fa-history"), Text.of(" Versiones (v" + vCount + ")"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + escapeJs(engName) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "', '" + vB64 + "')").style("background:rgba(168,85,247,0.12); border:1px solid rgba(168,85,247,0.3); color:#c084fc; font-size:8.5px; cursor:pointer; padding:2px 6px; border-radius:3px; display:inline-flex; align-items:center; gap:3px;")),
            Button.of(Icon.of("fas fa-trash-alt"), Text.of(" Eliminar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalDeleteModal('" + escapeJs(engName) + "', '" + escapeJs(targetDb) + "', '" + escapeJs(uName) + "', '" + escapeJs(itemId) + "')").style("background:rgba(239,68,68,0.12); border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:8.5px; cursor:pointer; padding:2px 6px; border-radius:3px; display:inline-flex; align-items:center; gap:3px;"))
        ).modifier(new Modifier().style("display:flex; gap:6px; align-items:center; flex-wrap:wrap; border-top:1px dashed rgba(255,255,255,0.08); padding-top:4px;"));
        detailElements.add(detailActions);

        return Div.of(detailElements.toArray(new Widget[0]))
            .modifier(new Modifier().style("background:var(--j-bg-subsurface,#1e293b); border:1px solid rgba(56,189,248,0.2); border-left:3px solid " + engColor + "; padding:6px 10px; border-radius:0 0 6px 6px;"));
    }

    private static Widget buildUnitDetailPanel(
        String engName,
        String engColor,
        String targetDb,
        String uName,
        int itemCount,
        String singularUnit,
        String pluralUnit
    ) {
        return Div.of(
            Div.of(
                Span.of("📁 " + uName).modifier(new Modifier().style("font-weight:700; color:var(--j-text-primary); font-size:9.5px;")),
                Span.of(engName).modifier(new Modifier().cssClass("store-badge").style("font-size:8px; padding:1px 5px; color:" + engColor + "; border:1px solid " + engColor + ";"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:3px; border-bottom:1px solid rgba(255,255,255,0.06); padding-bottom:2px;")),
            Div.of(
                Span.of("Items: " + itemCount + " " + (itemCount == 1 ? singularUnit : pluralUnit)).modifier(new Modifier().style("color:#38bdf8; font-size:8.5px; margin-right:8px;")),
                Span.of("Partition: Single-Partition Primary").modifier(new Modifier().style("color:#4ade80; font-size:8.5px; margin-right:8px;")),
                Span.of("Storage Engine: " + engName).modifier(new Modifier().style("color:#94a3b8; font-size:8.5px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;"))
        ).modifier(new Modifier().style("background:var(--j-bg-subsurface,#1e293b); border:1px solid rgba(56,189,248,0.15); border-left:2px solid " + engColor + "; padding:4px 8px; border-radius:4px; margin-top:2px; margin-bottom:2px;"));
    }

    private static Widget buildEngineDetailPanel(
        String engName,
        String engColor,
        String targetDb,
        int unitCount,
        int totalItems,
        String pluralUnit
    ) {
        String archDesc = switch (engName.toUpperCase()) {
            case "KEYVALUE" -> "In-Memory LSM / High-Throughput Hash Index Engine";
            case "VECTOR" -> "HNSW High-Dimensional Vector Search & Annoy Indexing Engine";
            case "GRAPH" -> "Adjacency-List Directed Graph Engine with Traversal";
            case "TIMESERIES" -> "Append-Only Windowed TimeSeries Metrics Engine";
            case "COLUMN" -> "Sparse Wide-Column Family Store Engine";
            case "GEOSPATIAL" -> "R-Tree Spatial Index & GIS Geometry Engine";
            case "OBJECT" -> "BLOB & Object Bucket Storage Engine";
            case "RECORDS" -> "Strict Java 25 Record & Immutable Schema Engine";
            default -> "Hierarchical JSON Document Store with B-Tree Indexes";
        };

        return Div.of(
            Div.of(
                Span.of("⚙️ Engine Architecture: " + archDesc).modifier(new Modifier().style("color:#e2e8f0; font-weight:600; font-size:9px;")),
                Span.of(engName).modifier(new Modifier().cssClass("store-badge").style("font-size:8px; padding:1px 5px; color:" + engColor + "; border:1px solid " + engColor + ";"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:2px; border-bottom:1px solid rgba(255,255,255,0.06); padding-bottom:2px;")),
            Div.of(
                Span.of("Units: " + unitCount + " " + pluralUnit).modifier(new Modifier().style("color:#38bdf8; font-size:8.5px; margin-right:8px;")),
                Span.of("Total Records: " + totalItems).modifier(new Modifier().style("color:#4ade80; font-size:8.5px; margin-right:8px;")),
                Span.of("Target Database: " + targetDb).modifier(new Modifier().style("color:#94a3b8; font-size:8.5px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;"))
        ).modifier(new Modifier().style("background:var(--j-bg-subsurface,#1e293b); border:1px solid rgba(56,189,248,0.15); border-left:2px solid " + engColor + "; padding:4px 8px; border-radius:4px; margin-top:2px; margin-bottom:2px;"));
    }

    private static String escapeJs(String s) {
        return FluxEscapers.escapeJs(s);
    }
}

