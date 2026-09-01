package com.jettra.store.engine.web;

import com.jettra.store.engine.models.RecordVersionSnapshot;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Native JettraFlux component for the Historical Versions Dialog.
 * Encapsulates:
 * - Version column renderer with badge formatting (e.g., 'v1 (CURRENT)', 'v2').
 * - Formatted Timestamp/Date column (yyyy-MM-dd HH:mm:ss).
 * - Single-line structured Snapshot Preview column with escaping.
 * - Restore Action Button triggering confirm restore modal and rollback flow.
 */
public final class HistoricalVersionsDialog {

    private HistoricalVersionsDialog() {}

    /**
     * Builds the main Historical Versions Dialog widget.
     */
    public static Widget build(String actionUrl) {
        return Div.of(
            buildVersionsModal(actionUrl),
            buildConfirmRestoreModal(actionUrl)
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
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
                Span.of("DOCUMENT").id("restoreEngineLabel").modifier(new Modifier().cssClass("store-badge").style("font-size:10px; margin-left:8px; background:rgba(168,85,247,0.15); color:#a855f7; border:1px solid rgba(168,85,247,0.3);"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('universalRestoreModal')").style("background:none; border:none; color:var(--j-text-muted); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border); background:var(--j-bg-subsurface); border-radius:10px 10px 0 0;"));

        Widget infoRow = Div.of(
            InputHidden.of("engine_type", "DOCUMENT").id("restoreEngineTypeInput"),
            InputHidden.of("target_db", "default").id("restoreRecordDbInput"),
            InputHidden.of("target_coll", "default").id("restoreRecordCollInput"),
            InputHidden.of("target_id", "").id("restoreRecordIdInput"),

            Div.of(
                Span.of("Item Identifier: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11.5px;")),
                Span.of("").id("restoreRecordIdLabel").modifier(new Modifier().style("color:#a855f7; font-family:monospace; font-weight:700; font-size:12px;"))
            ),
            Span.of("Zero-loss versioned snapshots").modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted);"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:var(--j-bg-body); border-radius:6px; border:1px solid var(--j-border); margin-bottom:14px;"));

        Widget versionsContainer = Div.of()
            .id("universalVersionsContainer")
            .modifier(new Modifier().style("max-height:280px; overflow-y:auto; border:1px solid var(--j-border); border-radius:6px; margin-bottom:14px;"));

        Widget body = Div.of(infoRow, versionsContainer)
            .modifier(new Modifier().style("padding:16px 18px;"));

        return createModalOverlay("universalRestoreModal", "680px", "rgba(168,85,247,0.4)", header, body);
    }

    /**
     * Builds the confirmation dialog before applying a rollback.
     */
    public static Widget buildConfirmRestoreModal(String actionUrl) {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-undo-alt").modifier(new Modifier().style("color:#a855f7; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Confirmar Rollback de Versión"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmRestoreModal')").style("background:none; border:none; color:var(--j-text-muted); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border); background:var(--j-bg-subsurface); border-radius:10px 10px 0 0;"));

        Widget form = Form.of(
            InputHidden.of("action", "restore_version"),
            InputHidden.of("engine_type", "DOCUMENT").id("confirmRestoreEngineInput"),
            InputHidden.of("target_db", "default").id("confirmRestoreDbInput"),
            InputHidden.of("target_coll", "default").id("confirmRestoreCollInput"),
            InputHidden.of("target_id", "").id("confirmRestoreIdInput"),
            InputHidden.of("version_ts", "0").id("confirmRestoreTsInput"),

            Div.of(
                Icon.of("fas fa-history").modifier(new Modifier().style("color:#a855f7; font-size:32px; margin-bottom:10px; display:block; text-align:center;")),
                Paragraph.of(Text.of("¿Está seguro de que desea restaurar este registro al snapshot de la versión seleccionada?"))
                    .modifier(new Modifier().style("font-weight:600; color:var(--j-text-primary); font-size:13px; text-align:center; margin:0 0 12px 0;")),
                Div.of(
                    Span.of("Snapshot Timestamp: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px; margin-right:4px;")),
                    Span.of("").id("confirmRestoreDateDisplay").modifier(new Modifier().style("color:#a855f7; font-weight:700; font-size:12px;"))
                ).modifier(new Modifier().style("background:var(--j-bg-body); border:1px solid var(--j-border); padding:10px 14px; border-radius:6px; margin-bottom:16px; text-align:center;"))
            ),

            Div.of(
                Button.of(Icon.of("fas fa-times"), Text.of(" Cancelar"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmRestoreModal')").cssClass("btn-action btn-secondary").style("padding:6px 14px; font-size:12px; margin-right:8px;")),
                Button.of(Icon.of("fas fa-undo"), Text.of(" Sí, Restaurar Versión"))
                    .modifier(new Modifier().attribute("type", "submit").cssClass("btn-action btn-primary").style("padding:6px 16px; font-size:12px; font-weight:700; background:#a855f7; border-color:#a855f7;"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:8px;"))
        ).action(actionUrl).method("POST").modifier(new Modifier().style("padding:18px 20px;"));

        return createModalOverlay("confirmRestoreModal", "480px", "rgba(168,85,247,0.4)", header, form);
    }

    /**
     * Builds static HTML table rows for a list of RecordVersionSnapshot DTOs.
     */
    public static Widget renderVersionTable(List<RecordVersionSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Div.of(
                Paragraph.of("No historical snapshot versions recorded for this item yet. Edit the item to create new versions.")
                    .modifier(new Modifier().style("padding:16px; color:var(--j-text-muted); text-align:center; font-size:12px; margin:0;"))
            );
        }

        List<Widget> rows = new ArrayList<>();
        // Header row
        rows.add(Div.of(
            Span.of("Version").modifier(new Modifier().style("width:90px; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Timestamp / Date").modifier(new Modifier().style("width:160px; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Snapshot Preview").modifier(new Modifier().style("flex:1; font-weight:700; font-size:11px; color:var(--j-text-secondary);")),
            Span.of("Action").modifier(new Modifier().style("width:100px; text-align:right; font-weight:700; font-size:11px; color:var(--j-text-secondary);"))
        ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:var(--j-bg-subsurface); border-bottom:1px solid var(--j-border); gap:8px;")));

        for (RecordVersionSnapshot snap : snapshots) {
            String badgeText = snap.isCurrent() ? (snap.versionId() + " (CURRENT)") : snap.versionId();
            Widget versionCell = Span.of(badgeText)
                .modifier(new Modifier().cssClass(snap.isCurrent() ? "store-badge badge-active" : "store-badge badge-records").style("font-size:10px; font-weight:700; width:90px;"));

            Widget dateCell = Span.of(snap.formattedDate())
                .modifier(new Modifier().style("width:160px; color:var(--j-text-secondary); font-size:11px;"));

            Widget previewCell = Span.of(snap.snapshotPreview())
                .modifier(new Modifier().style("flex:1; font-family:monospace; color:var(--j-text-primary); font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"));

            Widget actionCell;
            if (!snap.isCurrent()) {
                actionCell = Button.of(Icon.of("fas fa-undo"), Text.of(" Restaurar"))
                    .modifier(new Modifier()
                        .attribute("type", "button")
                        .attribute("onclick", "openConfirmRestoreModal('" + snap.timestamp() + "', '" + snap.formattedDate() + "')")
                        .style("background:rgba(168,85,247,0.15); border:1px solid rgba(168,85,247,0.3); color:#a855f7; font-size:10.5px; padding:3px 10px; border-radius:4px; cursor:pointer; font-weight:600; width:100px; display:inline-flex; align-items:center; justify-content:center; gap:4px;"));
            } else {
                actionCell = Span.of(Icon.of("fas fa-check-circle"), Text.of(" Activo"))
                    .modifier(new Modifier().style("color:#10b981; font-size:11px; font-weight:600; width:100px; text-align:right; display:inline-flex; align-items:center; justify-content:flex-end; gap:4px;"));
            }

            rows.add(Div.of(versionCell, dateCell, previewCell, actionCell)
                .modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; border-bottom:1px solid var(--j-border); gap:8px;")));
        }

        return Div.of(rows.toArray(new Widget[0])).modifier(new Modifier().style("width:100%; font-size:12px;"));
    }

    private static Widget createModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(
                header,
                content
            ).modifier(new Modifier().style("background:var(--j-bg-surface); border:1px solid " + borderColor + "; border-radius:10px; width:100%; max-width:" + width + "; max-height:90vh; overflow-y:auto; box-shadow:0 12px 36px rgba(0,0,0,0.5); z-index:999999; margin:20px; animation:modalFadeIn 0.15s ease-out;"))
        ).id(modalId).modifier(new Modifier().style("position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(15,23,42,0.75); backdrop-filter:blur(4px); display:none; justify-content:center; align-items:center; z-index:999998;"));
    }
}
