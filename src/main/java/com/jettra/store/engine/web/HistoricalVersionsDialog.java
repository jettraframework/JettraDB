package com.jettra.store.engine.web;

import com.jettra.store.engine.models.RecordVersionSnapshot;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Native JettraFlux component for the Historical Versions and Rollback Dialog.
 * Encapsulates:
 * - Version column renderer with badge formatting (e.g., 'v1 (CURRENT)', 'v2').
 * - Formatted Timestamp/Date column (yyyy-MM-dd HH:mm:ss).
 * - Comprehensive Snapshot Preview column powered by FluxObjectViewer for inspecting full payload attributes
 *   via interactive JSON tree, key-value table, and raw JSON tabs.
 * - Restore Action Button triggering confirm restore modal and rollback flow.
 * - Reactive Virtual Thread execution and Toast feedback.
 * - Append-only version rollback strategy preserving audit history without data loss.
 */
public final class HistoricalVersionsDialog {

    private HistoricalVersionsDialog() {}

    /**
     * Builds the main Historical Versions Dialog widget containing both the
     * versions inspection modal, the confirmation modal, and the client event script.
     */
    public static Widget build(String actionUrl) {
        return Div.of(
            buildVersionsModal(actionUrl),
            buildConfirmRestoreModal(actionUrl),
            buildClientScript()
        );
    }

    /**
     * Builds the timeline / table modal for viewing historical snapshots.
     */
    public static Widget buildVersionsModal(String actionUrl) {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-history").modifier(new Modifier().style("color:#a855f7; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Historial de Versiones y Rollback (VERSIONES)"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary,#f8fafc);")),
                Span.of("DOCUMENT").id("restoreEngineLabel").modifier(new Modifier().cssClass("store-badge").style("font-size:10px; margin-left:8px; background:rgba(168,85,247,0.15); color:#a855f7; border:1px solid rgba(168,85,247,0.3);"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('universalRestoreModal')").style("background:none; border:none; color:var(--j-text-muted,#94a3b8); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border,rgba(255,255,255,0.1)); background:var(--j-bg-subsurface,#1e293b); border-radius:10px 10px 0 0;"));

        Widget infoRow = Div.of(
            InputHidden.of("engine_type", "DOCUMENT").id("restoreEngineTypeInput"),
            InputHidden.of("target_db", "default").id("restoreRecordDbInput"),
            InputHidden.of("target_coll", "default").id("restoreRecordCollInput"),
            InputHidden.of("target_id", "").id("restoreRecordIdInput"),

            Div.of(
                Span.of("Item Identifier: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted,#94a3b8); font-size:11.5px;")),
                Span.of("").id("restoreRecordIdLabel").modifier(new Modifier().style("color:#a855f7; font-family:monospace; font-weight:700; font-size:12px;"))
            ),
            Span.of("Zero-loss versioned snapshots (Append-only)").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted,#94a3b8);"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:var(--j-bg-body,#0f172a); border-radius:6px; border:1px solid var(--j-border,rgba(255,255,255,0.08)); margin-bottom:14px;"));

        Widget versionsContainer = Div.of()
            .id("universalVersionsContainer")
            .modifier(new Modifier().style("max-height:420px; overflow-y:auto; border:1px solid var(--j-border,rgba(255,255,255,0.08)); border-radius:6px; margin-bottom:14px; background:var(--j-bg-surface,#1e293b);"));

        Widget body = Div.of(infoRow, versionsContainer)
            .modifier(new Modifier().style("padding:16px 18px;"));

        return createModalOverlay("universalRestoreModal", "840px", "rgba(168,85,247,0.4)", header, body);
    }

    /**
     * Builds the confirmation dialog before applying a rollback.
     */
    public static Widget buildConfirmRestoreModal(String actionUrl) {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-undo-alt").modifier(new Modifier().style("color:#a855f7; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Confirmar Rollback de Versión"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary,#f8fafc);"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmRestoreModal')").style("background:none; border:none; color:var(--j-text-muted,#94a3b8); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border,rgba(255,255,255,0.1)); background:var(--j-bg-subsurface,#1e293b); border-radius:10px 10px 0 0;"));

        Widget form = Form.of(
            InputHidden.of("action", "restore_version"),
            InputHidden.of("is_ajax", "true"),
            InputHidden.of("engine_type", "DOCUMENT").id("confirmRestoreEngineInput"),
            InputHidden.of("target_db", "default").id("confirmRestoreDbInput"),
            InputHidden.of("target_coll", "default").id("confirmRestoreCollInput"),
            InputHidden.of("target_id", "").id("confirmRestoreIdInput"),
            InputHidden.of("version_ts", "0").id("confirmRestoreTsInput"),
            InputHidden.of("version_number", "0").id("confirmRestoreVNumInput"),

            Div.of(
                Icon.of("fas fa-history").modifier(new Modifier().style("color:#a855f7; font-size:32px; margin-bottom:10px; display:block; text-align:center;")),
                Paragraph.of(Text.of("¿Está seguro de que desea restaurar este registro al snapshot de la versión seleccionada?"))
                    .modifier(new Modifier().style("font-weight:600; color:var(--j-text-primary,#f8fafc); font-size:13px; text-align:center; margin:0 0 12px 0;")),
                Div.of(
                    Div.of(
                        Span.of("Engine: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted,#94a3b8); font-size:11px;")),
                        Span.of("DOCUMENT").id("confirmRestoreEngineDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:700; font-size:11px; margin-right:12px;")),
                        Span.of("Database: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted,#94a3b8); font-size:11px;")),
                        Span.of("default").id("confirmRestoreDbDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:700; font-size:11px; margin-right:12px;")),
                        Span.of("Record ID: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted,#94a3b8); font-size:11px;")),
                        Span.of("").id("confirmRestoreIdDisplay").modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:700; font-size:11px;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:center; margin-bottom:6px; flex-wrap:wrap; gap:4px;")),
                    Div.of(
                        Span.of("Snapshot Date / Timestamp: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted,#94a3b8); font-size:11px; margin-right:4px;")),
                        Span.of("").id("confirmRestoreDateDisplay").modifier(new Modifier().style("color:#a855f7; font-weight:700; font-size:12px;"))
                    ).modifier(new Modifier().style("text-align:center;"))
                ).modifier(new Modifier().style("background:var(--j-bg-body,#0f172a); border:1px solid var(--j-border,rgba(255,255,255,0.08)); padding:10px 14px; border-radius:6px; margin-bottom:16px;")),
                Paragraph.of(Text.of("La restauración aplicará el estado histórico como nueva versión activa (auditoría append-only sin pérdida de historial)."))
                    .modifier(new Modifier().style("font-size:11.5px; color:var(--j-text-muted,#94a3b8); text-align:center; margin:0 0 14px 0;"))
            ),

            Div.of(
                Button.of(Icon.of("fas fa-times"), Text.of(" Cancelar"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmRestoreModal')").cssClass("btn-action btn-secondary").style("padding:6px 14px; font-size:12px; margin-right:8px;")),
                Button.of(Icon.of("fas fa-undo"), Text.of(" Restore Version"))
                    .id("btnConfirmRestoreSubmit")
                    .modifier(new Modifier()
                        .attribute("type", "button")
                        .attribute("onclick", "executeVersionRollback(event, 'confirmRestoreModal')")
                        .cssClass("btn-action btn-primary")
                        .style("padding:6px 16px; font-size:12px; font-weight:700; background:#a855f7; border-color:#a855f7; cursor:pointer;"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:8px;"))
        ).action(actionUrl).method("POST").modifier(new Modifier().style("padding:18px 20px;"));

        return createModalOverlay("confirmRestoreModal", "520px", "rgba(168,85,247,0.4)", header, form);
    }

    /**
     * Builds static HTML table rows for a list of RecordVersionSnapshot DTOs using FluxObjectViewer.
     */
    public static Widget renderVersionTable(List<RecordVersionSnapshot> snapshots) {
        return renderVersionTable("DOCUMENT", "default", "default", "", snapshots);
    }

    /**
     * Builds static HTML table rows for a list of RecordVersionSnapshot DTOs using FluxObjectViewer with context.
     */
    public static Widget renderVersionTable(String engine, String db, String coll, String id, List<RecordVersionSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Div.of(
                Paragraph.of("No historical snapshot versions recorded for this item yet. Edit the item to create new versions.")
                    .modifier(new Modifier().style("padding:16px; color:var(--j-text-muted,#94a3b8); text-align:center; font-size:12px; margin:0;"))
            );
        }

        List<Widget> rows = new ArrayList<>();
        // Header row
        rows.add(Div.of(
            Span.of("Version").modifier(new Modifier().style("width:90px; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Timestamp / Date").modifier(new Modifier().style("width:150px; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Snapshot Preview").modifier(new Modifier().style("flex:1; min-width:200px; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Action").modifier(new Modifier().style("width:100px; text-align:right; font-weight:700; font-size:11px; color:var(--j-text-secondary);"))
        ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:var(--j-bg-subsurface,#1e293b); border-bottom:1px solid var(--j-border,rgba(255,255,255,0.08)); gap:8px;")));

        for (RecordVersionSnapshot snap : snapshots) {
            String badgeText = snap.isCurrent() ? (snap.versionId() + " (CURRENT)") : snap.versionId();
            Widget versionCell = Span.of(badgeText)
                .modifier(new Modifier().cssClass(snap.isCurrent() ? "store-badge badge-active" : "store-badge badge-records").style("font-size:10px; font-weight:700; width:90px;"));

            Widget dateCell = Span.of(snap.formattedDate())
                .modifier(new Modifier().style("width:150px; color:var(--j-text-secondary); font-size:11px;"));

            // Full structured preview widget using FluxObjectViewer with multi-view tabs (Tree, Table, Raw)
            Widget previewCell = FluxObjectViewer.of(snap.snapshotData())
                .title("Snapshot " + badgeText + " (" + snap.formattedDate() + ")")
                .version(snap.versionId())
                .timestamp(snap.formattedDate())
                .author(snap.author())
                .expandable(true)
                .defaultExpanded(false)
                .maxPreviewLength(65)
                .modifier(new Modifier().style("flex:1; min-width:200px; color:var(--j-text-secondary); font-size:11px;"));

            Widget actionCell;
            if (!snap.isCurrent()) {
                String safeEng = escapeJs(engine);
                String safeDb = escapeJs(db);
                String safeColl = escapeJs(coll != null ? coll : "default");
                String safeId = escapeJs(id != null ? id : "");
                String safeDate = escapeJs(snap.formattedDate());
                String clickHandler = "openConfirmRestoreModal('" + snap.timestamp() + "', '" + safeDate + "', '" + safeEng + "', '" + safeDb + "', '" + safeColl + "', '" + safeId + "', '" + snap.versionNumber() + "')";

                actionCell = Button.of(Icon.of("fas fa-undo"), Text.of(" Restaurar"))
                    .modifier(new Modifier()
                        .attribute("type", "button")
                        .attribute("onclick", clickHandler)
                        .style("background:rgba(168,85,247,0.15); border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:10.5px; padding:3px 10px; border-radius:4px; cursor:pointer; font-weight:600; width:100px; display:inline-flex; align-items:center; justify-content:center; gap:4px;"));
            } else {
                actionCell = Span.of(Icon.of("fas fa-check-circle"), Text.of(" Activo"))
                    .modifier(new Modifier().style("color:#10b981; font-size:11px; font-weight:600; width:100px; text-align:right; display:inline-flex; align-items:center; justify-content:flex-end; gap:4px;"));
            }

            rows.add(Div.of(versionCell, dateCell, previewCell, actionCell)
                .modifier(new Modifier().style("display:flex; align-items:flex-start; padding:8px 12px; border-bottom:1px solid var(--j-border,rgba(255,255,255,0.06)); gap:8px;")));
        }

        return Div.of(rows.toArray(new Widget[0])).modifier(new Modifier().style("width:100%; font-size:12px;"));
    }

    private static String escapeJs(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    private static Widget createModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(
                header,
                content
            ).modifier(new Modifier().style("background:var(--j-bg-surface,#1e293b); border:1px solid " + borderColor + "; border-radius:10px; width:100%; max-width:" + width + "; max-height:90vh; overflow-y:auto; box-shadow:0 12px 36px rgba(0,0,0,0.5); z-index:999999; margin:20px; animation:modalFadeIn 0.15s ease-out;"))
        ).id(modalId).modifier(new Modifier().style("position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(15,23,42,0.75); backdrop-filter:blur(4px); display:none; justify-content:center; align-items:center; z-index:999998;"));
    }

    /**
     * Builds client-side script registering openUniversalRestoreModal, openConfirmRestoreModal,
     * and executeVersionRollback.
     */
    public static Widget buildClientScript() {
        return RawHtml.of(
            "<script>\n" +
            "  window.currentRestoreContext = window.currentRestoreContext || {};\n" +
            "\n" +
            "  window.openUniversalRestoreModal = function(engine, db, unit, id, versionsJsonB64) {\n" +
            "    var normEngine = (engine || 'DOCUMENT').toUpperCase();\n" +
            "    if (normEngine === 'RECORD') normEngine = 'RECORDS';\n" +
            "    if (normEngine === 'KEY_VALUE' || normEngine === 'KEY-VALUE') normEngine = 'KEYVALUE';\n" +
            "    if (normEngine === 'TIME_SERIES' || normEngine === 'TIMESERIE') normEngine = 'TIMESERIES';\n" +
            "    if (normEngine === 'GEO') normEngine = 'GEOSPATIAL';\n" +
            "\n" +
            "    window.currentRestoreContext = {\n" +
            "      engine: normEngine,\n" +
            "      db: db,\n" +
            "      unit: unit || 'default',\n" +
            "      id: id\n" +
            "    };\n" +
            "\n" +
            "    var setVal = function(elemId, val) {\n" +
            "      var el = document.getElementById(elemId);\n" +
            "      if (el) {\n" +
            "        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {\n" +
            "          el.value = val;\n" +
            "        } else {\n" +
            "          el.innerText = val;\n" +
            "        }\n" +
            "      }\n" +
            "    };\n" +
            "\n" +
            "    setVal('restoreEngineLabel', normEngine);\n" +
            "    setVal('restoreEngineTypeInput', normEngine);\n" +
            "    setVal('restoreRecordDbInput', db);\n" +
            "    setVal('restoreRecordCollInput', unit || 'default');\n" +
            "    setVal('restoreRecordIdInput', id);\n" +
            "    setVal('restoreRecordIdLabel', id);\n" +
            "\n" +
            "    var container = document.getElementById('universalVersionsContainer');\n" +
            "    if (container) {\n" +
            "      container.innerHTML = '<div style=\"padding:16px; text-align:center; color:var(--j-text-muted,#94a3b8);\"><i class=\"fas fa-circle-notch fa-spin\"></i> Cargando historial de versiones...</div>';\n" +
            "\n" +
            "      var actionUrl = window.lastActionUrl || '/engines';\n" +
            "      var qUrl = actionUrl + (actionUrl.indexOf('?') >= 0 ? '&' : '?') + 'action=load_version_history_table&engine=' + encodeURIComponent(normEngine) + '&target_db=' + encodeURIComponent(db) + '&target_coll=' + encodeURIComponent(unit || 'default') + '&target_id=' + encodeURIComponent(id);\n" +
            "\n" +
            "      fetch(qUrl, {\n" +
            "        headers: { 'X-Requested-With': 'XMLHttpRequest' }\n" +
            "      })\n" +
            "      .then(function(res) { return res.ok ? res.json() : null; })\n" +
            "      .then(function(data) {\n" +
            "        if (data && data.status === 'SUCCESS' && data.html) {\n" +
            "          container.innerHTML = data.html;\n" +
            "        } else if (versionsJsonB64) {\n" +
            "          window.renderClientVersionsFallback(container, versionsJsonB64, normEngine, db, unit, id);\n" +
            "        } else {\n" +
            "          container.innerHTML = '<div style=\"padding:16px; color:var(--j-text-muted,#94a3b8); text-align:center;\">No historical snapshot versions recorded for this item yet.</div>';\n" +
            "        }\n" +
            "      })\n" +
            "      .catch(function(e) {\n" +
            "        if (versionsJsonB64) {\n" +
            "          window.renderClientVersionsFallback(container, versionsJsonB64, normEngine, db, unit, id);\n" +
            "        } else {\n" +
            "          container.innerHTML = '<div style=\"padding:16px; color:var(--j-text-muted,#94a3b8); text-align:center;\">No historical snapshot versions recorded for this item yet.</div>';\n" +
            "        }\n" +
            "      });\n" +
            "    }\n" +
            "\n" +
            "    if (typeof showModal === 'function') {\n" +
            "      showModal('universalRestoreModal');\n" +
            "    } else {\n" +
            "      var m = document.getElementById('universalRestoreModal');\n" +
            "      if (m) m.style.display = 'flex';\n" +
            "    }\n" +
            "  };\n" +
            "\n" +
            "  window.openConfirmRestoreModal = function(ts, formattedDate, engine, db, coll, id, vNum) {\n" +
            "    var ctx = window.currentRestoreContext || {};\n" +
            "    var engVal = engine || ctx.engine || (document.getElementById('restoreEngineTypeInput') ? document.getElementById('restoreEngineTypeInput').value : 'DOCUMENT');\n" +
            "    var dbVal = db || ctx.db || (document.getElementById('restoreRecordDbInput') ? document.getElementById('restoreRecordDbInput').value : 'default');\n" +
            "    var collVal = coll || ctx.unit || (document.getElementById('restoreRecordCollInput') ? document.getElementById('restoreRecordCollInput').value : 'default');\n" +
            "    var idVal = id || ctx.id || (document.getElementById('restoreRecordIdInput') ? document.getElementById('restoreRecordIdInput').value : '');\n" +
            "\n" +
            "    var setVal = function(elemId, val) {\n" +
            "      var el = document.getElementById(elemId);\n" +
            "      if (el) {\n" +
            "        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {\n" +
            "          el.value = val;\n" +
            "        } else {\n" +
            "          el.innerText = val;\n" +
            "        }\n" +
            "      }\n" +
            "    };\n" +
            "\n" +
            "    setVal('confirmRestoreEngineInput', engVal);\n" +
            "    setVal('confirmRestoreEngineDisplay', engVal);\n" +
            "    setVal('confirmRestoreDbInput', dbVal);\n" +
            "    setVal('confirmRestoreDbDisplay', dbVal);\n" +
            "    setVal('confirmRestoreCollInput', collVal);\n" +
            "    setVal('confirmRestoreCollDisplay', collVal);\n" +
            "    setVal('confirmRestoreIdInput', idVal);\n" +
            "    setVal('confirmRestoreIdDisplay', idVal);\n" +
            "    setVal('confirmRestoreTsInput', ts || '0');\n" +
            "    setVal('confirmRestoreTsDisplay', ts || '0');\n" +
            "    setVal('confirmRestoreDateDisplay', formattedDate || ts || '');\n" +
            "    if (vNum) setVal('confirmRestoreVNumInput', vNum);\n" +
            "\n" +
            "    if (typeof showModal === 'function') {\n" +
            "      showModal('confirmRestoreModal');\n" +
            "    } else {\n" +
            "      var m = document.getElementById('confirmRestoreModal');\n" +
            "      if (m) m.style.display = 'flex';\n" +
            "    }\n" +
            "  };\n" +
            "\n" +
            "  window.executeVersionRollback = function(e, modalId) {\n" +
            "    if (e) {\n" +
            "      if (typeof e.preventDefault === 'function') e.preventDefault();\n" +
            "      if (typeof e.stopPropagation === 'function') e.stopPropagation();\n" +
            "    }\n" +
            "    var modal = document.getElementById(modalId || 'confirmRestoreModal');\n" +
            "    if (!modal) return false;\n" +
            "    var form = modal.querySelector('form');\n" +
            "    if (!form && e && e.target) form = e.target.closest('form');\n" +
            "    if (!form) return false;\n" +
            "\n" +
            "    var btn = document.getElementById('btnConfirmRestoreSubmit');\n" +
            "    var origHtml = btn ? btn.innerHTML : '';\n" +
            "    if (btn) {\n" +
            "      btn.disabled = true;\n" +
            "      btn.innerHTML = '<i class=\"fas fa-circle-notch fa-spin\"></i> Restaurando...';\n" +
            "    }\n" +
            "\n" +
            "    var formData = new FormData(form);\n" +
            "    var params = new URLSearchParams();\n" +
            "    formData.forEach(function(v, k) { params.append(k, v); });\n" +
            "    if (!params.has('is_ajax')) params.append('is_ajax', 'true');\n" +
            "\n" +
            "    var actionUrl = form.getAttribute('action') || window.lastActionUrl || '/engines';\n" +
            "\n" +
            "    fetch(actionUrl, {\n" +
            "      method: 'POST',\n" +
            "      headers: {\n" +
            "        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',\n" +
            "        'X-Requested-With': 'XMLHttpRequest'\n" +
            "      },\n" +
            "      body: params.toString()\n" +
            "    })\n" +
            "    .then(function(res) {\n" +
            "      if (!res.ok) throw new Error('HTTP ' + res.status + ' ' + res.statusText);\n" +
            "      return res.json();\n" +
            "    })\n" +
            "    .then(function(data) {\n" +
            "      if (btn) {\n" +
            "        btn.disabled = false;\n" +
            "        btn.innerHTML = origHtml;\n" +
            "      }\n" +
            "      if (data.status === 'ERROR') {\n" +
            "        if (typeof showTreeToast === 'function') {\n" +
            "          showTreeToast(data.message || 'Error al revertir versión', 'error');\n" +
            "        } else {\n" +
            "          alert(data.message || 'Error al revertir versión');\n" +
            "        }\n" +
            "        return;\n" +
            "      }\n" +
            "      // Success: Automatically close BOTH modals\n" +
            "      if (typeof hideModal === 'function') {\n" +
            "        hideModal('confirmRestoreModal');\n" +
            "        hideModal('universalRestoreModal');\n" +
            "      } else {\n" +
            "        var m1 = document.getElementById('confirmRestoreModal'); if (m1) m1.style.display = 'none';\n" +
            "        var m2 = document.getElementById('universalRestoreModal'); if (m2) m2.style.display = 'none';\n" +
            "      }\n" +
            "      var msg = data.message || 'Registro restaurado con éxito a la versión seleccionada!';\n" +
            "      if (typeof showTreeToast === 'function') {\n" +
            "        showTreeToast(msg, 'success');\n" +
            "      }\n" +
            "\n" +
            "      // Refresh view after short delay\n" +
            "      setTimeout(function() {\n" +
            "        if (typeof refreshLazyDbSubtree === 'function' && data.database) {\n" +
            "          var dc = document.querySelector('.db-subtree-container[data-db=\"' + data.database + '\"]');\n" +
            "          if (dc) {\n" +
            "            var cId = dc.id;\n" +
            "            var dbIdx = dc.getAttribute('data-db-idx') || 1;\n" +
            "            refreshLazyDbSubtree(null, cId, data.database, data.engine || 'DOCUMENT', actionUrl, dbIdx);\n" +
            "          } else {\n" +
            "            location.reload();\n" +
            "          }\n" +
            "        } else {\n" +
            "          location.reload();\n" +
            "        }\n" +
            "      }, 400);\n" +
            "    })\n" +
            "    .catch(function(err) {\n" +
            "      if (btn) {\n" +
            "        btn.disabled = false;\n" +
            "        btn.innerHTML = origHtml;\n" +
            "      }\n" +
            "      if (typeof showTreeToast === 'function') {\n" +
            "        showTreeToast('Fallo en la reversión: ' + err.message, 'error');\n" +
            "      } else {\n" +
            "        alert('Fallo en la reversión: ' + err.message);\n" +
            "      }\n" +
            "    });\n" +
            "    return false;\n" +
            "  };\n" +
            "\n" +
            "  window.renderClientVersionsFallback = function(container, versionsJsonB64, engine, db, unit, id) {\n" +
            "    var versionsJsonStr = '';\n" +
            "    try { versionsJsonStr = decodeURIComponent(escape(atob(versionsJsonB64))); } catch(e) { try { versionsJsonStr = atob(versionsJsonB64); } catch(e2) { versionsJsonStr = versionsJsonB64; } }\n" +
            "    var versions = [];\n" +
            "    try { versions = JSON.parse(versionsJsonStr); } catch(e) { versions = []; }\n" +
            "    if (!versions || versions.length === 0) {\n" +
            "      container.innerHTML = '<div style=\"padding:16px; color:var(--j-text-muted,#94a3b8); text-align:center;\">No historical snapshot versions recorded for this item yet.</div>';\n" +
            "      return;\n" +
            "    }\n" +
            "    var html = '<table style=\"width:100%; border-collapse:collapse; font-size:12px;\">';\n" +
            "    html += '<tr style=\"background:var(--j-bg-subsurface,#1e293b); color:var(--j-text-secondary,#cbd5e1); text-align:left;\"><th style=\"padding:8px 12px; width:90px;\">Version</th><th style=\"padding:8px 12px; width:150px;\">Timestamp / Date</th><th style=\"padding:8px 12px;\">Snapshot Preview</th><th style=\"padding:8px 12px; text-align:right; width:100px;\">Action</th></tr>';\n" +
            "    for (var i = 0; i < versions.length; i++) {\n" +
            "      var v = versions[i];\n" +
            "      var vNum = (v.versionNumber !== undefined && v.versionNumber !== null) ? ('v' + v.versionNumber) : (v.versionId || ('v' + (i + 1)));\n" +
            "      var badge = v.isCurrent ? '<span class=\"store-badge badge-active\" style=\"font-size:10px;\">' + vNum + ' (CURRENT)</span>' : '<span class=\"store-badge badge-records\" style=\"font-size:10px;\">' + vNum + '</span>';\n" +
            "      var safeDate = (v.formattedDate || (v.timestamp ? new Date(v.timestamp).toLocaleString() : '') || 'N/A').toString().replace(/[\\\'\\\"\\\\\\\\]/g, ' ');\n" +
            "      var fullPayload = (typeof v.payload === 'object' ? JSON.stringify(v.payload, null, 2) : (v.snapshotData || v.payload || '{}')).toString();\n" +
            "      var preview = (v.snapshotPreview || v.payloadPreview || v.preview || fullPayload).toString();\n" +
            "      if (preview.length > 65) preview = preview.substring(0, 65) + '...';\n" +
            "      var safeTs = (v.timestamp || 0).toString();\n" +
            "      var rowDetailId = 'snap_detail_' + i;\n" +
            "      html += '<tr style=\"border-bottom:1px solid var(--j-border,rgba(255,255,255,0.06)); vertical-align:top;\">';\n" +
            "      html += '<td style=\"padding:8px 12px; font-weight:700;\">' + badge + '</td>';\n" +
            "      html += '<td style=\"padding:8px 12px; color:var(--j-text-secondary,#cbd5e1); font-size:11px;\">' + safeDate + '</td>';\n" +
            "      html += '<td style=\"padding:8px 12px; color:var(--j-text-secondary,#cbd5e1); font-size:11px;\">';\n" +
            "      html += '<div style=\"display:flex; align-items:center; gap:6px;\">';\n" +
            "      html += '<button type=\"button\" onclick=\"var el=document.getElementById(\\'' + rowDetailId + '\\');if(el){el.style.display=(el.style.display===\\'none\\'?\\'block\\':\\'none\\');}\" style=\"background:none; border:none; color:var(--j-text-muted,#94a3b8); cursor:pointer; font-size:10px; padding:2px;\"><i class=\"fas fa-chevron-right\"></i></button>';\n" +
            "      html += '<span style=\"font-family:monospace; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1;\">' + preview.replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</span>';\n" +
            "      html += '</div>';\n" +
            "      html += '<div id=\"' + rowDetailId + '\" style=\"display:none; margin-top:6px; padding:8px 10px; background:var(--j-bg-body,#0f172a); border-radius:6px; border:1px solid var(--j-border,rgba(255,255,255,0.08)); font-family:monospace; font-size:11px; white-space:pre-wrap; max-height:220px; overflow-y:auto; color:#e2e8f0;\">' + fullPayload.replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</div>';\n" +
            "      html += '</td>';\n" +
            "      html += '<td style=\"padding:8px 12px; text-align:right;\">';\n" +
            "      if (!v.isCurrent) {\n" +
            "        html += '<button type=\"button\" onclick=\"openConfirmRestoreModal(\\'' + safeTs + '\\', \\'' + safeDate + '\\', \\'' + engine + '\\', \\'' + db + '\\', \\'' + unit + '\\', \\'' + id + '\\', \\'' + v.versionNumber + '\\')\" style=\"background:rgba(168,85,247,0.15); border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:10.5px; padding:3px 10px; border-radius:4px; cursor:pointer; font-weight:600;\"><i class=\"fas fa-undo\" style=\"margin-right:3px;\"></i> Restaurar</button>';\n" +
            "      } else {\n" +
            "        html += '<span style=\"color:#10b981; font-size:11px; font-weight:600;\"><i class=\"fas fa-check-circle\" style=\"margin-right:3px;\"></i> Activo</span>';\n" +
            "      }\n" +
            "      html += '</td></tr>';\n" +
            "    }\n" +
            "    html += '</table>';\n" +
            "    container.innerHTML = html;\n" +
            "  };\n" +
            "</script>\n"
        );
    }
}
