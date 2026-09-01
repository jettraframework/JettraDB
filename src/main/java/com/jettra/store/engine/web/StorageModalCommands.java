package com.jettra.store.engine.web;

import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

import java.util.ArrayList;
import java.util.List;

/**
 * Command Pattern and Modal Dialog definitions for Multi-Model Storage Hierarchy row actions.
 * Leverages Java 25+ sealed interfaces, pattern matching, and immutable records for event dispatching.
 */
public final class StorageModalCommands {

    private StorageModalCommands() {}

    /**
     * Java 25 Sealed Interface for all row actions.
     */
    public sealed interface HierarchyRowCommand permits ViewCommand, EditCommand, RestoreCommand, DeleteCommand {
        String engine();
        String database();
        String unit();
        String recordId();
    }

    /**
     * VER Command: Read-only inspection command payload.
     */
    public record ViewCommand(
        String engine,
        String database,
        String unit,
        String recordId,
        String payloadJson,
        int versionCount
    ) implements HierarchyRowCommand {}

    /**
     * EDITAR Command: Update record command payload.
     */
    public record EditCommand(
        String engine,
        String database,
        String unit,
        String recordId,
        String rawPayload,
        JsonObject attributes
    ) implements HierarchyRowCommand {}

    /**
     * VERSIONES Command: Version history and restore command payload.
     */
    public record RestoreCommand(
        String engine,
        String database,
        String unit,
        String recordId,
        long targetTimestamp,
        int versionNumber
    ) implements HierarchyRowCommand {}

    /**
     * ELIMINAR Command: Delete record command payload.
     */
    public record DeleteCommand(
        String engine,
        String database,
        String unit,
        String recordId
    ) implements HierarchyRowCommand {}

    /**
     * Dispatches command using Java 25 Pattern Matching in switch expression.
     */
    public static String dispatchCommand(HierarchyRowCommand cmd) {
        return switch (cmd) {
            case ViewCommand v -> "VIEW:" + v.engine() + ":" + v.database() + ":" + v.unit() + ":" + v.recordId() + ":v" + v.versionCount();
            case EditCommand e -> "EDIT:" + e.engine() + ":" + e.database() + ":" + e.unit() + ":" + e.recordId();
            case RestoreCommand r -> "RESTORE:" + r.engine() + ":" + r.database() + ":" + r.unit() + ":" + r.recordId() + "@" + r.targetTimestamp();
            case DeleteCommand d -> "DELETE:" + d.engine() + ":" + d.database() + ":" + d.unit() + ":" + d.recordId();
        };
    }

    /**
     * Builds the VER (Inspect Record) Modal with structured JSON / $jref inspection and auto-resolution.
     */
    public static Widget buildInspectModal() {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-search-plus").modifier(new Modifier().style("color:#38bdf8; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Visor Estructurado de Registro (VER)"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);")),
                Span.of("").id("inspectRecordVersionDisplay").modifier(new Modifier().cssClass("store-badge badge-active").style("font-size:10px; margin-left:8px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('inspectRecordModal')").style("background:none; border:none; color:var(--j-text-muted); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border); background:var(--j-bg-subsurface); border-radius:10px 10px 0 0;"));

        Widget metaRow = Div.of(
            Div.of(
                Span.of("Engine: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                Span.of("DOCUMENT").id("inspectRecordEngineDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:700; font-size:11px; margin-right:12px;")),
                Span.of("Database: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                Span.of("default").id("inspectRecordDbDisplay").modifier(new Modifier().style("color:var(--j-text-primary); font-weight:600; font-size:11px; margin-right:12px;")),
                Span.of("Unit: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                Span.of("default").id("inspectRecordCollDisplay").modifier(new Modifier().style("color:var(--j-text-primary); font-weight:600; font-size:11px; margin-right:12px;")),
                Span.of("ID: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                Span.of("").id("inspectRecordIdDisplay").modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:700; font-size:11px;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:4px;")),
            Span.of("0 Ref(s)").id("inspectReferencesCountBadge").modifier(new Modifier().cssClass("store-badge").style("display:none; font-size:10px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:8px 12px; background:var(--j-bg-body); border-radius:6px; border:1px solid var(--j-border); margin-bottom:12px;"));

        Widget toolbar = Div.of(
            Label.of(
                RawHtml.of("<input type=\"checkbox\" id=\"chkInspectResolveRefs\" checked onchange=\"toggleInspectReferenceResolution(this.checked)\" style=\"accent-color:var(--j-primary); width:13px; height:13px; cursor:pointer; margin-right:4px;\" />"),
                Icon.of("fas fa-link").modifier(new Modifier().style("color:var(--j-primary); margin-right:4px; font-size:11px;")),
                Span.of("Auto-Resolve Jref ($jref)").modifier(new Modifier().style("color:var(--j-text-secondary); font-size:11px; font-weight:600;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center; cursor:pointer; background:var(--j-bg-subsurface); border:1px solid var(--j-border); padding:3px 8px; border-radius:4px;")),
            Div.of(
                Button.of(Icon.of("fas fa-copy"), Text.of(" Copy Payload"))
                    .id("btnCopyInspect")
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "copyInspectRecordPayload()").cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:10.5px; margin-right:4px;")),
                Button.of(Icon.of("fas fa-edit"), Text.of(" Editar"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "editFromInspectModal()").cssClass("btn-action btn-primary").style("padding:3px 8px; font-size:10.5px; background:#fbbf24; border-color:#fbbf24; color:#0f172a; margin-right:4px; font-weight:700;")),
                Button.of(Icon.of("fas fa-history"), Text.of(" Historial"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "historyFromInspectModal()").cssClass("btn-action btn-secondary").style("padding:3px 8px; font-size:10.5px; color:#c084fc; border-color:#c084fc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;"));

        Widget payloadArea = TextArea.create()
            .name("inspect_payload")
            .rows(14)
            .id("inspectRecordPayloadDisplay")
            .modifier(new Modifier()
                .attribute("readonly", "true")
                .style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:11.5px; padding:10px; line-height:1.4; resize:vertical;"));

        Widget refContainer = Div.of(
            Header.of(4, Text.of("Manual Exploration of Referenced Objects ($jref)"))
                .modifier(new Modifier().style("margin:12px 0 6px 0; font-size:12px; font-weight:700; color:var(--j-text-secondary);")),
            Div.of().id("inspectRecordReferencesList").modifier(new Modifier().style("display:flex; flex-direction:column; gap:6px; max-height:160px; overflow-y:auto;"))
        ).id("inspectRecordReferencesContainer").modifier(new Modifier().style("display:none; margin-top:8px;"));

        Widget body = Div.of(metaRow, toolbar, payloadArea, refContainer)
            .modifier(new Modifier().style("padding:16px 18px;"));

        return createModalOverlay("inspectRecordModal", "720px", "rgba(56,189,248,0.4)", header, body);
    }

    /**
     * Builds the ELIMINAR (Confirm Delete) Modal with confirmation prompt and destructive action feedback.
     */
    public static Widget buildDeleteModal(String actionUrl) {
        Widget header = Div.of(
            Div.of(
                Icon.of("fas fa-trash-alt").modifier(new Modifier().style("color:#ef4444; font-size:16px; margin-right:8px;")),
                Header.of(3, Text.of("Confirmar Eliminación de Registro (ELIMINAR)"))
                    .modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:var(--j-text-primary);"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmDeleteModal')").style("background:none; border:none; color:var(--j-text-muted); font-size:16px; cursor:pointer; padding:4px;"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; padding:14px 18px; border-bottom:1px solid var(--j-border); background:var(--j-bg-subsurface); border-radius:10px 10px 0 0;"));

        Widget form = Form.of(
            InputHidden.of("action", "delete_object"),
            InputHidden.of("engine_type", "DOCUMENT").id("confirmDeleteEngineInput"),
            InputHidden.of("target_db", "default").id("confirmDeleteDbInput"),
            InputHidden.of("target_coll", "default").id("confirmDeleteCollInput"),
            InputHidden.of("target_id", "").id("confirmDeleteIdInput"),

            Div.of(
                Icon.of("fas fa-exclamation-triangle").modifier(new Modifier().style("color:#ef4444; font-size:32px; margin-bottom:10px; display:block; text-align:center;")),
                Paragraph.of(Text.of("¿Está seguro de que desea eliminar permanentemente este registro del motor de almacenamiento?"))
                    .modifier(new Modifier().style("font-weight:600; color:var(--j-text-primary); font-size:13px; text-align:center; margin:0 0 12px 0;")),
                Div.of(
                    Div.of(
                        Span.of("Engine: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                        Span.of("DOCUMENT").id("confirmDeleteEngineDisplay").modifier(new Modifier().style("color:#38bdf8; font-weight:700; font-size:11px; margin-right:12px;")),
                        Span.of("Database: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                        Span.of("default").id("confirmDeleteDbDisplay").modifier(new Modifier().style("color:var(--j-text-primary); font-weight:600; font-size:11px; margin-right:12px;")),
                        Span.of("Unit: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                        Span.of("default").id("confirmDeleteCollDisplay").modifier(new Modifier().style("color:var(--j-text-primary); font-weight:600; font-size:11px;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:center; flex-wrap:wrap; margin-bottom:6px;")),
                    Div.of(
                        Span.of("Record ID: ").modifier(new Modifier().style("font-weight:bold; color:var(--j-text-muted); font-size:11px;")),
                        Span.of("").id("confirmDeleteIdDisplay").modifier(new Modifier().style("color:#ef4444; font-family:monospace; font-weight:700; font-size:12px;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:center;"))
                ).modifier(new Modifier().style("background:var(--j-bg-body); border:1px solid var(--j-border); padding:10px 14px; border-radius:6px; margin-bottom:16px;"))
            ),

            Div.of(
                Button.of(Icon.of("fas fa-times"), Text.of(" Cancelar"))
                    .modifier(new Modifier().attribute("type", "button").attribute("onclick", "hideModal('confirmDeleteModal')").cssClass("btn-action btn-secondary").style("padding:6px 14px; font-size:12px; margin-right:8px;")),
                Button.of(Icon.of("fas fa-trash-alt"), Text.of(" Sí, Eliminar Registro"))
                    .modifier(new Modifier().attribute("type", "submit").cssClass("btn-action btn-danger").style("padding:6px 16px; font-size:12px; font-weight:700;"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:8px;"))
        ).action(actionUrl).method("POST").modifier(new Modifier().style("padding:18px 20px;"));

        return createModalOverlay("confirmDeleteModal", "500px", "rgba(239,68,68,0.4)", header, form);
    }

    /**
     * Builds the VERSIONES (Version History & Snapshot Restore) Modal.
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

    private static Widget createModalOverlay(String modalId, String width, String borderColor, Widget header, Widget content) {
        return Div.of(
            Div.of(
                header,
                content
            ).modifier(new Modifier().style("background:var(--j-bg-surface); border:1px solid " + borderColor + "; border-radius:10px; width:100%; max-width:" + width + "; max-height:90vh; overflow-y:auto; box-shadow:0 12px 36px rgba(0,0,0,0.5); z-index:999999; margin:20px; animation:modalFadeIn 0.15s ease-out;"))
        ).id(modalId).modifier(new Modifier().style("position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(15,23,42,0.75); backdrop-filter:blur(4px); display:none; justify-content:center; align-items:center; z-index:999998;"));
    }
}
