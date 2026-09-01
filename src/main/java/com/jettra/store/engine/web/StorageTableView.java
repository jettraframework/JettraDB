package com.jettra.store.engine.web;

import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Storage Table View for Multi-Model Storage Hierarchy Explorer.
 * Features:
 * - Direct row-level action buttons (VER, EDITAR, VERSIONES, ELIMINAR).
 * - Expandable child detail panel with primary address, engine badge, preview fields, and action shortcuts.
 * - Quick text search & filter across all attributes and payloads.
 * - Jref ($jref) reference auto-resolution toggle.
 * - Responsive pagination.
 */
public final class StorageTableView {

    private StorageTableView() {}

    public record FlatRecordItem(
        String engine,
        String color,
        String icon,
        String db,
        String unit,
        String id,
        int vCount,
        String payload,
        String payloadB64,
        String versionsB64
    ) {}

    public static Widget build(
        String selectedEngine,
        String targetDb,
        String currentColl,
        String actionUrl,
        List<FlatRecordItem> flatItems,
        Map<String, String> params,
        JettraJson jsonParser
    ) {
        int totalItems = flatItems.size();
        int pageSize = 15;
        try {
            if (params != null && params.containsKey("table_size")) {
                pageSize = Math.max(5, Integer.parseInt(params.get("table_size")));
            }
        } catch (Exception ignored) {}

        int currentPage = 1;
        try {
            if (params != null && params.containsKey("table_page")) {
                currentPage = Math.max(1, Integer.parseInt(params.get("table_page")));
            }
        } catch (Exception ignored) {}

        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        if (currentPage > totalPages) currentPage = totalPages;

        int startIndex = (currentPage - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);
        List<FlatRecordItem> pageItems = totalItems > 0 ? flatItems.subList(startIndex, endIndex) : Collections.emptyList();

        // 1. Filter Bar
        Widget quickFilterInput = TextField.of("table_quick_filter", "Quick filter by Record ID, unit, engine, or payload content...")
            .id("tableExplorerQuickFilter")
            .modifier(new Modifier()
                .attribute("onkeyup", "filterExplorerTable()")
                .style("flex:1; min-width:220px; padding:6px 12px; background:var(--j-bg-surface); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"));

        Widget resolveRefCheckbox = Label.of(
            RawHtml.of("<input type=\"checkbox\" id=\"chkAutoResolveRefsGlobal\" checked onchange=\"toggleGlobalReferenceResolution(this.checked)\" style=\"accent-color:var(--j-primary); width:14px; height:14px; cursor:pointer; margin-right:4px;\" />"),
            Icon.of("fas fa-link").modifier(new Modifier().style("color:var(--j-primary); margin-right:4px; font-size:11px;")),
            Span.of("Cargar Objetos Referenciados (Auto-Resolve Jref)").modifier(new Modifier().style("color:var(--j-text-secondary); font-size:11px; font-weight:600;"))
        ).modifier(new Modifier().style("display:inline-flex; align-items:cursor:pointer; background:var(--j-primary-light); border:1px solid var(--j-border); padding:4px 8px; border-radius:6px;"));

        Widget totalCountBadge = Span.of(totalItems + " Total Records").id("tableFilterVisibleCount")
            .modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:11px; padding:4px 8px;"));

        Widget tableFilterBar = Div.of(
            resolveRefCheckbox,
            quickFilterInput,
            totalCountBadge
        ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-bottom:12px; background:var(--j-bg-subsurface); padding:8px 12px; border-radius:6px; border:1px solid var(--j-border);"));

        // 2. Table Rows
        List<Widget> tableRows = new ArrayList<>();

        // Header Row
        Widget tableHeaderRow = Div.of(
            Span.of("").modifier(new Modifier().style("width:28px; text-align:center;")),
            Span.of("ENGINE").modifier(new Modifier().style("width:125px; font-weight:700; color:var(--j-text-secondary); font-size:11px;")),
            Span.of("UNIT / COLLECTION").modifier(new Modifier().style("width:150px; font-weight:700; color:var(--j-text-secondary); font-size:11px;")),
            Span.of("RECORD ID").modifier(new Modifier().style("width:160px; font-weight:700; color:var(--j-text-secondary); font-size:11px;")),
            Span.of("VERSION").modifier(new Modifier().style("width:70px; font-weight:700; color:var(--j-text-secondary); font-size:11px;")),
            Span.of("PAYLOAD PREVIEW").modifier(new Modifier().style("flex:1; min-width:180px; font-weight:700; color:var(--j-text-secondary); font-size:11px;")),
            Span.of("ACTIONS").modifier(new Modifier().style("width:130px; text-align:right; font-weight:700; color:var(--j-text-secondary); font-size:11px;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:var(--j-bg-subsurface); border-bottom:2px solid var(--j-border); border-radius:6px 6px 0 0; gap:8px;"));

        tableRows.add(tableHeaderRow);

        if (pageItems.isEmpty()) {
            tableRows.add(
                Div.of(
                    Icon.of("fas fa-database").modifier(new Modifier().style("color:var(--j-text-muted); font-size:28px; margin-bottom:8px; display:block;")),
                    Header.of(4, Text.of("No engines or components found for [" + targetDb + "]"))
                        .modifier(new Modifier().style("color:var(--j-text-muted); font-size:13px; font-weight:600; margin:0 0 6px 0;")),
                    Paragraph.of(Text.of("Select another database or use the '+ Unit' / '+ Object' action to initialize multi-model entities."))
                        .modifier(new Modifier().style("color:var(--j-text-muted); font-size:11.5px; margin:0;"))
                ).modifier(new Modifier().style("padding:32px 16px; text-align:center; background:var(--j-bg-surface);"))
            );
        } else {
            for (int i = 0; i < pageItems.size(); i++) {
                FlatRecordItem item = pageItems.get(i);
                String rowDetailId = "tbl_row_detail_" + (i + 1);

                Widget expandBtn = Button.of(
                    Icon.of("fas fa-chevron-right").id("icon_" + rowDetailId).modifier(new Modifier().cssClass("tree-toggle-icon"))
                ).modifier(new Modifier()
                    .attribute("type", "button")
                    .attribute("title", "Desplegar/Ocultar detalles del registro")
                    .attribute("onclick", "toggleTableRowDetail('" + rowDetailId + "')")
                    .style("background:none; border:none; color:var(--j-text-muted); width:28px; height:28px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center; padding:0;"));

                Widget engCell = Span.of(item.engine())
                    .modifier(new Modifier().cssClass("store-badge").style("width:125px; background:rgba(0,0,0,0.06); color:" + item.color() + "; border:1px solid " + item.color() + "; font-size:10px; padding:2px 6px; font-weight:700;"));

                Widget unitCell = Span.of(item.unit())
                    .modifier(new Modifier().style("width:150px; font-weight:600; color:var(--j-text-primary); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

                Widget idCell = Span.of(item.id())
                    .modifier(new Modifier().style("width:160px; font-family:monospace; color:#4ade80; font-size:11.5px; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; cursor:pointer;"))
                    .attribute("onclick", "toggleTableRowDetail('" + rowDetailId + "')");

                Widget versionCell = Span.of("v" + item.vCount())
                    .modifier(new Modifier().cssClass("store-badge").style("width:70px; background:var(--j-primary-light); color:var(--j-primary); font-size:10px; padding:2px 6px; text-align:center;"));

                String preview = item.payload().length() > 75 ? item.payload().substring(0, 75) + "..." : item.payload();
                Widget previewCell = Span.of(preview)
                    .modifier(new Modifier().style("flex:1; min-width:180px; color:var(--j-text-muted); font-family:monospace; font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; cursor:pointer;"))
                    .attribute("onclick", "toggleTableRowDetail('" + rowDetailId + "')");

                List<Widget> actionBtns = new ArrayList<>();
                // 1. VER Action Button
                actionBtns.add(
                    Button.of(Icon.of("fas fa-eye"))
                        .modifier(new Modifier()
                            .attribute("type", "button")
                            .attribute("title", "Ver detalles del registro")
                            .attribute("onclick", "openInspectRecordModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "', " + item.vCount() + ")")
                            .style("background:rgba(56,189,248,0.1); border:1px solid rgba(56,189,248,0.3); color:#38bdf8; font-size:11px; padding:4px 8px; border-radius:4px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;"))
                );
                // 2. EDITAR Action Button
                actionBtns.add(
                    Button.of(Icon.of("fas fa-edit"))
                        .modifier(new Modifier()
                            .attribute("type", "button")
                            .attribute("title", "Editar registro")
                            .attribute("onclick", "openUniversalEditModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "')")
                            .style("background:rgba(251,191,36,0.1); border:1px solid rgba(251,191,36,0.3); color:#fbbf24; font-size:11px; padding:4px 8px; border-radius:4px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;"))
                );
                // 3. VERSIONES Action Button
                actionBtns.add(
                    Button.of(Icon.of("fas fa-history"))
                        .modifier(new Modifier()
                            .attribute("type", "button")
                            .attribute("title", "Historial de versiones (v" + item.vCount() + ")")
                            .attribute("onclick", "openUniversalRestoreModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.versionsB64() + "')")
                            .style("background:rgba(168,85,247,0.1); border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:11px; padding:4px 8px; border-radius:4px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;"))
                );
                // 4. ELIMINAR Action Button
                actionBtns.add(
                    Button.of(Icon.of("fas fa-trash-alt"))
                        .modifier(new Modifier()
                            .attribute("type", "button")
                            .attribute("title", "Eliminar registro")
                            .attribute("onclick", "openUniversalDeleteModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "')")
                            .style("background:rgba(239,68,68,0.1); border:1px solid rgba(239,68,68,0.3); color:#ef4444; font-size:11px; padding:4px 8px; border-radius:4px; cursor:pointer; display:inline-flex; align-items:center; justify-content:center;"))
                );

                Widget actionsCell = Div.of(actionBtns.toArray(new Widget[0]))
                    .modifier(new Modifier().style("width:130px; display:flex; justify-content:flex-end; align-items:center; gap:4px;"));

                Widget row = Div.of(expandBtn, engCell, unitCell, idCell, versionCell, previewCell, actionsCell)
                    .modifier(new Modifier()
                        .cssClass("explorer-table-row")
                        .attribute("data-detail-id", rowDetailId)
                        .attribute("data-db-name", item.db())
                        .attribute("data-engine-type", item.engine())
                        .style("display:flex; align-items:center; padding:8px 12px; border-bottom:1px solid var(--j-border); background:var(--j-bg-surface); gap:8px;"));

                Widget detailContent = renderItemDetailSummary(item, jsonParser);

                Widget detailRow = Div.of(detailContent)
                    .id(rowDetailId)
                    .modifier(new Modifier().cssClass("explorer-table-detail-row").style("display:none; padding:10px 16px; background:var(--j-bg-subsurface); border-bottom:1px solid var(--j-border); border-left:3px solid " + item.color() + "; margin-left:32px; border-radius:0 0 6px 6px; box-shadow:inset 0 2px 8px rgba(0,0,0,0.05); margin-bottom:4px;"));

                tableRows.add(row);
                tableRows.add(detailRow);
            }
        }

        Widget tableContainer = Div.of(tableRows.toArray(new Widget[0]))
            .id("tableExplorerContainer")
            .modifier(new Modifier().style("border:1px solid var(--j-border); border-radius:6px; overflow-x:auto; margin-bottom:12px; position:relative;"));

        // 3. Pagination Controls
        String baseTableUrl = actionUrl + selectedEngine + "&target_db=" + targetDb + "&coll=" + currentColl + "&view_mode=table&table_size=" + pageSize;

        List<Widget> pageButtons = new ArrayList<>();
        if (currentPage > 1) {
            pageButtons.add(Link.of(baseTableUrl + "&table_page=1", "« First").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-right:3px;")));
            pageButtons.add(Link.of(baseTableUrl + "&table_page=" + (currentPage - 1), "‹ Prev").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-right:3px;")));
        }
        pageButtons.add(
            Span.of("Page " + currentPage + " / " + totalPages).modifier(new Modifier().style("color:var(--j-primary); font-weight:bold; font-size:11.5px; padding:3px 8px;"))
        );
        if (currentPage < totalPages) {
            pageButtons.add(Link.of(baseTableUrl + "&table_page=" + (currentPage + 1), "Next ›").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-left:3px;")));
            pageButtons.add(Link.of(baseTableUrl + "&table_page=" + totalPages, "Last »").modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:11px; margin-left:3px;")));
        }

        Widget paginationFooter = Div.of(
            Span.of("Showing " + (totalItems == 0 ? 0 : startIndex + 1) + " - " + endIndex + " of " + totalItems + " records (Page " + currentPage + " of " + totalPages + ")")
                .modifier(new Modifier().style("font-size:12px; color:var(--j-text-muted); font-weight:500;")),
            Div.of(pageButtons.toArray(new Widget[0])).modifier(new Modifier().style("display:flex; align-items:center; gap:2px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; padding:6px 4px;"));

        Widget tableInitScript = RawHtml.of(
            "<script>\n" +
            "  if (typeof window.toggleTableRowDetail !== 'function') {\n" +
            "    window.toggleTableRowDetail = function(detailId) {\n" +
            "      var el = document.getElementById(detailId);\n" +
            "      var icon = document.getElementById('icon_' + detailId);\n" +
            "      if (!el) return;\n" +
            "      var isHidden = (el.style.display === 'none' || el.style.display === '');\n" +
            "      if (isHidden) {\n" +
            "        el.style.display = 'block';\n" +
            "        if (icon) icon.className = 'fas fa-chevron-down tree-toggle-icon';\n" +
            "      } else {\n" +
            "        el.style.display = 'none';\n" +
            "        if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';\n" +
            "      }\n" +
            "    };\n" +
            "  }\n" +
            "  if (typeof window.filterExplorerTable !== 'function') {\n" +
            "    window.filterExplorerTable = function() {\n" +
            "      var input = document.getElementById('tableExplorerQuickFilter');\n" +
            "      var filter = input ? input.value.toLowerCase().trim() : '';\n" +
            "      var rows = document.querySelectorAll('.explorer-table-row');\n" +
            "      var visibleCount = 0;\n" +
            "      for (var i = 0; i < rows.length; i++) {\n" +
            "        var text = rows[i].innerText.toLowerCase();\n" +
            "        var textMatch = (!filter || text.indexOf(filter) > -1);\n" +
            "        var detailId = rows[i].getAttribute('data-detail-id');\n" +
            "        var detailEl = detailId ? document.getElementById(detailId) : null;\n" +
            "        if (textMatch) {\n" +
            "          rows[i].style.display = 'flex';\n" +
            "          visibleCount++;\n" +
            "        } else {\n" +
            "          rows[i].style.display = 'none';\n" +
            "          if (detailEl) {\n" +
            "            detailEl.style.display = 'none';\n" +
            "            var icon = document.getElementById('icon_' + detailId);\n" +
            "            if (icon) icon.className = 'fas fa-chevron-right tree-toggle-icon';\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      var counter = document.getElementById('tableFilterVisibleCount');\n" +
            "      if (counter) counter.innerText = visibleCount + ' Total Records';\n" +
            "    };\n" +
            "  }\n" +
            "</script>\n"
        );

        return Div.of(
            tableFilterBar,
            tableContainer,
            paginationFooter,
            tableInitScript,
            StorageModalCommands.buildModalActionHandlersScript()
        );
    }

    private static Widget renderItemDetailSummary(FlatRecordItem item, JettraJson jsonParser) {
        String pfxMap = switch (item.engine().toUpperCase()) {
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
        String primaryAddr = pfxMap + item.db() + ":" + (item.unit().equals("default") ? "" : item.unit() + ":") + item.id();

        JsonObject parsed;
        try {
            parsed = jsonParser.fromJson(item.payload(), JsonObject.class);
            if (parsed == null) parsed = new JsonObject();
        } catch (Exception e) {
            parsed = new JsonObject();
            parsed.addProperty("raw", item.payload());
        }

        List<Widget> detailElements = new ArrayList<>();

        // 1. Meta Header Bar
        Widget metaHeader = Div.of(
            Span.of("📍 " + primaryAddr).modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:600; font-size:8.5px;")),
            Span.of("Engine: " + item.engine() + " | v" + item.vCount()).modifier(new Modifier().style("color:#38bdf8; font-size:8px; font-weight:500;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid rgba(255,255,255,0.06); padding-bottom:3px; margin-bottom:4px;"));
        detailElements.add(metaHeader);

        // 2. Field Attributes Preview (Max 8 attributes displayed cleanly)
        List<Widget> propRows = new ArrayList<>();
        int propCount = 0;
        for (String key : parsed.keySet()) {
            if (propCount >= 8) {
                propRows.add(Span.of("... and " + (parsed.keySet().size() - propCount) + " more field(s)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
                break;
            }
            propCount++;
            Object val = parsed.get(key);
            String valStr = val != null ? val.toString() : "null";
            if (valStr.length() > 60) valStr = valStr.substring(0, 60) + "...";

            boolean isJref = valStr.contains("jref://");
            Widget valWidget = Span.of(valStr).modifier(new Modifier().style("color:" + (isJref ? "#38bdf8" : "#f1f5f9") + "; font-family:monospace; font-size:8px;"));

            Widget propRow = Div.of(
                Span.of(key + ": ").modifier(new Modifier().style("color:#94a3b8; font-weight:600; font-size:8px; margin-right:4px;")),
                valWidget
            ).modifier(new Modifier().style("padding:1px 0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));
            propRows.add(propRow);
        }

        if (propRows.isEmpty()) {
            propRows.add(Span.of("(Empty or unparsed binary payload)").modifier(new Modifier().style("color:#64748b; font-style:italic; font-size:8px;")));
        }

        Widget propsContainer = Div.of(propRows.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:1px; background:rgba(0,0,0,0.25); padding:4px 6px; border-radius:3px; margin-bottom:4px;"));
        detailElements.add(propsContainer);

        // 3. Action Buttons Row inside Detail Panel
        Widget detailActions = Div.of(
            Button.of(Icon.of("fas fa-search-plus"), Text.of(" Inspeccionar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openInspectRecordModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "', " + item.vCount() + ")").style("background:none; border:none; color:#38bdf8; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;")),
            Button.of(Icon.of("fas fa-edit"), Text.of(" Editar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalEditModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.payloadB64() + "')").style("background:none; border:none; color:#fbbf24; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;")),
            Button.of(Icon.of("fas fa-history"), Text.of(" Historial (v" + item.vCount() + ")"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "openUniversalRestoreModal('" + escapeJs(item.engine()) + "', '" + escapeJs(item.db()) + "', '" + escapeJs(item.unit()) + "', '" + escapeJs(item.id()) + "', '" + item.versionsB64() + "')").style("background:none; border:none; color:#c084fc; font-size:8px; cursor:pointer; padding:1px 4px; display:inline-flex; align-items:center; gap:2px;"))
        ).modifier(new Modifier().style("display:flex; gap:8px; align-items:center; border-top:1px dashed rgba(255,255,255,0.06); padding-top:3px;"));
        detailElements.add(detailActions);

        return Div.of(detailElements.toArray(new Widget[0]));
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }
}
