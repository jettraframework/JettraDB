package com.jettra.store.engine.web;

import com.jettra.store.engine.insertion.EngineType;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive Multi-Model Record Editing Dialog for JettraDB in /engines under
 * the Multi-Model Storage Hierarchy Explorer section.
 *
 * Tailors its editing interface identically to EngineRecordInsertionDialog:
 * - When editing a Record, displays the JettraFluxRecordForm typed table of columns,
 *   field types (primitives, temporals, collections, objects), and values.
 * - Displays and enables strictly the engine corresponding to the edited record.
 * - Submits asynchronously via AJAX (fetch) with Virtual Thread persistence,
 *   preventing navigation away to raw JSON and keeping the user seamlessly in the UI.
 */
public final class EngineRecordEditDialog {

    public static final String MODAL_ID = "universalEditModal";
    public static final String FORM_ID = "universalEditForm";
    public static final String NOTIFICATION_ID = "universalEditNotification";

    private EngineRecordEditDialog() {}

    public static Widget build(String actionUrl) {
        // 1. Notification banner
        Widget notification = JettraFluxNotification.of(NOTIFICATION_ID)
                .title("Resultado de Edición")
                .message("")
                .type(JettraFluxNotification.Type.INFO)
                .visible(false);

        // 2. Engine Selector Tabs Bar (9 Engines - dynamically filtered to the active record)
        Widget engineSelectorBar = buildEngineSelectorBar("DOCUMENT");

        // 3. Common Metadata Info Row
        Widget commonRow = Div.of(
            Div.of(
                Span.of("Engine: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("DOCUMENT").id("universalEditEngineDisplay")
                    .modifier(new Modifier().cssClass("store-badge")
                        .style("font-size:10.5px; font-weight:700; background:rgba(56,189,248,0.15); color:#38bdf8; border:1px solid rgba(56,189,248,0.3); margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Database: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("default").id("universalEditDbDisplay")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-primary); margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Unit / Coll: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("default").id("universalEditCollDisplay")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-primary); margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Record ID: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("").id("universalEditIdDisplay")
                    .modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:700; font-size:12px; margin-right:8px;")),
                Icon.of("fas fa-fingerprint").modifier(new Modifier().style("color:#4ade80; font-size:11px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Span.of("Zero-loss versioned update (v+1)").modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-muted); margin-left:auto;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:6px; background:var(--j-bg-body); padding:9px 14px; border-radius:8px; border:1px solid var(--j-border); margin-bottom:12px;"));

        // 4. Form with polymorphic engine sections
        Widget form = Form.of(
            InputHidden.of("action", "update_object"),
            InputHidden.of("is_ajax", "true"),
            InputHidden.of("engine_type", "DOCUMENT").id("universalEditEngineInput"),
            InputHidden.of("target_db", "default").id("universalEditDbInput"),
            InputHidden.of("target_coll", "default").id("universalEditCollInput"),
            InputHidden.of("target_id", "").id("universalEditIdInput"),

            commonRow,

            // Polymorphic sections for each engine
            buildDocumentSection(),
            buildKeyValueSection(),
            buildVectorSection(),
            buildGraphSection(),
            buildTimeSeriesSection(),
            buildColumnSection(),
            buildGeoSection(),
            buildObjectSection(),
            buildRecordsSection(),

            // Hidden mirror for universal edit compatibility
            TextArea.create().name("record_payload").rows(1).id("universalEditPayloadInput")
                .modifier(new Modifier().style("display:none;")),

            // Footer Actions
            Div.of(
                Button.of(Icon.of("fas fa-times"), Text.of(" Cancelar"))
                    .modifier(new Modifier().attribute("type", "button")
                        .attribute("onclick", "if (window.JettraFluxModal) JettraFluxModal.close('universalEditModal'); else if (typeof hideModal==='function') hideModal('universalEditModal');")
                        .cssClass("btn-action btn-secondary")
                        .style("padding:7px 16px; font-size:12px; margin-right:8px; cursor:pointer;")),
                Button.of(Icon.of("fas fa-save"), Text.of(" Guardar Cambios (v+1)"))
                    .id("btnUniversalEditSubmit")
                    .modifier(new Modifier().attribute("type", "button")
                        .attribute("onclick", "submitUniversalEditRecord(event);")
                        .cssClass("btn-action btn-primary")
                        .style("padding:7px 20px; font-size:12px; font-weight:700; background:#0284c7; border-color:#0284c7; cursor:pointer;"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; margin-top:14px; padding-top:10px; border-top:1px solid var(--j-border);"))
        ).action(actionUrl).method("POST").id(FORM_ID)
         .modifier(new Modifier().attribute("onsubmit", "if(event && event.preventDefault) event.preventDefault(); return submitUniversalEditRecord(event);").style("width:100%; box-sizing:border-box;"));

        // 5. Build Modal using JettraFluxModal
        JettraFluxModal modal = JettraFluxModal.of(MODAL_ID)
                .title("Editar Registro Multi-Modelo")
                .subtitle("Formulario adaptativo con persistencia polimórfica para los 9 motores heterogéneos de JettraDB")
                .icon("fas fa-edit")
                .badge("9 MOTORES", "#fbbf24")
                .maxWidth("900px")
                .maxHeight("92vh")
                .addBody(notification)
                .addBody(engineSelectorBar)
                .addBody(form);

        return Div.of(
            modal,
            buildClientScript()
        );
    }

    private static Widget buildEngineSelectorBar(String initialEngine) {
        List<Widget> pills = new ArrayList<>();
        for (EngineType eng : EngineType.all()) {
            boolean isActive = eng.key().equalsIgnoreCase(initialEngine)
                    || (eng instanceof EngineType.RelationalRecords && "RECORD".equalsIgnoreCase(initialEngine));
            String borderStyle = isActive ? "2px solid " + eng.color() : "1px solid var(--j-border)";
            String bgStyle = isActive ? "rgba(255,255,255,0.08)" : "var(--j-bg-body)";
            String fontColor = isActive ? eng.color() : "var(--j-text-secondary)";

            List<Widget> pillChildren = new ArrayList<>();
            pillChildren.add(Icon.of(eng.icon()).modifier(new Modifier().style("color:" + eng.color() + "; font-size:12px; margin-right:6px;")));
            pillChildren.add(Span.of(eng.displayName()).modifier(new Modifier().style("font-size:11.5px; font-weight:700; color:" + fontColor + ";")));

            if (eng.badge() != null && !eng.badge().isBlank()) {
                pillChildren.add(Span.of(eng.badge()).modifier(new Modifier().style(
                    "font-size:9px; font-weight:800; padding:1px 6px; border-radius:10px; " +
                    "background:rgba(16,185,129,0.2); color:#10b981; border:1px solid rgba(16,185,129,0.35); " +
                    "margin-left:6px; letter-spacing:0.5px; vertical-align:middle;"
                )));
            }

            Widget pill = Div.of(pillChildren.toArray(new Widget[0]))
                .id("edit_engine_tab_btn_" + eng.key())
                .modifier(new Modifier()
                    .attribute("data-engine", eng.key())
                    .attribute("data-color", eng.color())
                    .attribute("data-label", eng.displayName())
                    .attribute("onclick", "switchEditEngine('" + eng.key() + "')")
                    .style("display:inline-flex; align-items:center; padding:6px 11px; border-radius:20px; cursor:pointer; "
                         + "background:" + bgStyle + "; border:" + borderStyle + "; transition:all 0.15s ease; user-select:none; white-space:nowrap;"));

            pills.add(pill);
        }

        return Div.of(
            Div.of(
                Span.of("MOTOR DE ALMACENAMIENTO (ADAPTIVE MODEL SCHEMA):")
                    .modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); letter-spacing:0.8px; text-transform:uppercase; margin-bottom:6px; display:block;")),
                Div.of(pills.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display:flex; gap:6px; overflow-x:auto; padding-bottom:4px;"))
            ).modifier(new Modifier().style("background:var(--j-bg-subsurface); padding:10px 14px; border-radius:8px; border:1px solid var(--j-border); margin-bottom:12px;"))
        );
    }

    // 1. DOCUMENT Section
    private static Widget buildDocumentSection() {
        return Div.of(
            Div.of(
                Label.of("Colección de Destino (Collection):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editDocCollInput").binding("target_coll").value("default")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#38bdf8; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Clase / Esquema Tipado (Class / Schema Optional):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editDocClassInput").binding("doc_class").value("")
                    .modifier(new Modifier().attribute("placeholder", "com.jettra.model.Customer")
                        .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px; font-family:monospace;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Payload JSON del Documento:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("doc_payload").rows(10).id("editDocPayloadInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#38bdf8; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "DOCUMENT").style("display:block;"));
    }

    // 2. KEYVALUE Section
    private static Widget buildKeyValueSection() {
        return Div.of(
            Div.of(
                Label.of("Bucket / Namespace:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editKvCollInput").binding("target_coll").value("default")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#10b981; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Valor Almacenado / Payload:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("kv_value").rows(9).id("editKvValueInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#10b981; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "KEYVALUE").style("display:none;"));
    }

    // 3. VECTOR Section
    private static Widget buildVectorSection() {
        return Div.of(
            Div.of(
                Label.of("Índice Vectorial / Colección:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editVecCollInput").binding("target_coll").value("default")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#8b5cf6; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Coordenadas Vectoriales (Float Array / Embedding):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editVecCoordsInput").binding("vector_coords").value("0.12, 0.45, 0.88, 0.31")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#a855f7; font-family:monospace; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Metadatos Asociados (JSON):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("vector_meta").rows(7).id("editVecMetaInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "VECTOR").style("display:none;"));
    }

    // 4. GRAPH Section
    private static Widget buildGraphSection() {
        return Div.of(
            Div.of(
                Label.of("Etiqueta de Vértice / Grupo (Node Label):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editGraphCollInput").binding("target_coll").value("Vertex")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#ec4899; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Propiedades del Grafo (JSON):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("node_props").rows(9).id("editGraphPropsInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#ec4899; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "GRAPH").style("display:none;"));
    }

    // 5. TIMESERIES Section
    private static Widget buildTimeSeriesSection() {
        return Div.of(
            Div.of(
                Label.of("Nombre de la Métrica / Serie:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editTsCollInput").binding("target_coll").value("telemetry")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#06b6d4; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Div.of(
                    Label.of("Valor Numérico (Double):").modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsValueInput").binding("ts_value").value("25.4")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ),
                Div.of(
                    Label.of("Unidad / Escala:").modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsUnitInput").binding("ts_unit").value("celsius")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ),
                Div.of(
                    Label.of("Timestamp (Epoch ms):").modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsTimestampInput").binding("ts_timestamp").value("")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:12px;"))
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr 1.2fr; gap:10px; margin-bottom:10px;")),
            Div.of(
                Label.of("Tags / Dimensiones JSON:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("ts_tags").rows(6).id("editTsTagsInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#06b6d4; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "TIMESERIES").style("display:none;"));
    }

    // 6. COLUMN Section
    private static Widget buildColumnSection() {
        return Div.of(
            Div.of(
                Label.of("Familia de Columnas (Column Family):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editColCollInput").binding("target_coll").value("analytics")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#f97316; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Columnas Dinámicas / Row Data (JSON):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("col_data").rows(9).id("editColDataInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#f97316; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "COLUMN").style("display:none;"));
    }

    // 7. GEOSPATIAL Section
    private static Widget buildGeoSection() {
        return Div.of(
            Div.of(
                Label.of("Capa Espacial (Spatial Layer):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editGeoCollInput").binding("target_coll").value("stores_layer")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Div.of(
                    Label.of("Latitud (-90..90):").modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoLatInput").binding("geo_lat").value("8.9824")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ),
                Div.of(
                    Label.of("Longitud (-180..180):").modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoLonInput").binding("geo_lon").value("-79.5199")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                )
            ).modifier(new Modifier().style("display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:10px;")),
            Div.of(
                Label.of("Nombre del Lugar / Metadata:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editGeoNameInput").binding("geo_name").value("")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "GEOSPATIAL").style("display:none;"));
    }

    // 8. OBJECT Section
    private static Widget buildObjectSection() {
        return Div.of(
            Div.of(
                Label.of("Bucket de Almacenamiento:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editObjCollInput").binding("target_coll").value("media_bucket")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#a855f7; font-weight:600; font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("MIME Content-Type:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("editObjMimeInput").binding("obj_mime").value("application/json")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
            ).modifier(new Modifier().style("margin-bottom:10px;")),
            Div.of(
                Label.of("Contenido / Raw Payload:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("obj_payload").rows(7).id("editObjPayloadInput")
                    .modifier(new Modifier().style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#a855f7; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            )
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "OBJECT").style("display:none;"));
    }

    // 9. RECORDS (Java 25) Section: Identical to EngineRecordInsertionDialog with JettraFluxRecordForm
    private static Widget buildRecordsSection() {
        return Div.of(
            // Visual, reactive record builder with fields table, typed dropdowns, and canonical sync
            JettraFluxRecordForm.of("edit_rec", "com.jettra.model.EmployeeRecord", "employees")
                .sampleEmployeeRecord(),

            // Legacy compatibility inputs
            TextField.of().id("editRecCollInput").binding("rec_coll_compat").value("")
                .modifier(new Modifier().style("display:none;")),
            TextField.of().id("editRecClassInput").binding("rec_class_compat").value("")
                .modifier(new Modifier().style("display:none;")),
            TextArea.create().name("rec_payload_compat").id("editRecPayloadInput")
                .modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "RECORDS").style("display:none;"));
    }

    private static Widget buildClientScript() {
        return RawHtml.of("""
        <script>
        function submitUniversalEditRecord(e) {
            if (e) {
                if (e.preventDefault) e.preventDefault();
                if (e.stopPropagation) e.stopPropagation();
            }
            var form = document.getElementById('universalEditForm');
            if (!form) return false;

            var btn = document.getElementById('btnUniversalEditSubmit');
            var origHtml = btn ? btn.innerHTML : '';
            if (btn) {
                btn.disabled = true;
                btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Guardando cambios...';
            }

            // Sync Record Form if active
            var engInp = document.getElementById('universalEditEngineInput');
            var eng = engInp ? engInp.value : 'DOCUMENT';
            if ((eng === 'RECORDS' || eng === 'RECORD') && window.JettraFluxRecordForm) {
                try {
                    window.JettraFluxRecordForm.updatePayload('edit_rec');
                } catch(err) {
                    console.warn('RecordForm payload sync warning', err);
                }
                var recPayloadEl = document.getElementById('edit_rec_payload');
                var uniPayload = document.getElementById('universalEditPayloadInput');
                if (recPayloadEl && uniPayload) {
                    uniPayload.value = recPayloadEl.value;
                }
            }

            var payloadObj = {};
            var formData = new FormData(form);
            formData.forEach(function(v, k) {
                payloadObj[k] = v;
            });
            payloadObj['action'] = 'update_object';
            payloadObj['is_ajax'] = 'true';
            payloadObj['is_fetch'] = 'true';

            payloadObj['engine_type'] = eng;

            var dbInp = document.getElementById('universalEditDbInput');
            if (dbInp && dbInp.value) payloadObj['target_db'] = dbInp.value;

            var collInp = document.getElementById('universalEditCollInput');
            if (collInp && collInp.value) payloadObj['target_coll'] = collInp.value;

            var idInp = document.getElementById('universalEditIdInput');
            if (idInp && idInp.value) payloadObj['target_id'] = idInp.value;

            var uniPayloadEl = document.getElementById('universalEditPayloadInput');
            if (uniPayloadEl && uniPayloadEl.value) {
                payloadObj['record_payload'] = uniPayloadEl.value;
            }

            if (eng === 'RECORDS' || eng === 'RECORD') {
                var recEl = document.getElementById('edit_rec_payload');
                if (recEl && recEl.value) {
                    payloadObj['record_payload'] = recEl.value;
                    payloadObj['rec_payload'] = recEl.value;
                }
            } else if (eng === 'DOCUMENT') {
                var docEl = document.getElementById('editDocPayloadInput');
                if (docEl && docEl.value) {
                    payloadObj['record_payload'] = docEl.value;
                    payloadObj['doc_payload'] = docEl.value;
                }
                var docCls = document.getElementById('editDocClassInput');
                if (docCls && docCls.value) payloadObj['doc_class'] = docCls.value;
                var docColl = document.getElementById('editDocCollInput');
                if (docColl && docColl.value) payloadObj['target_coll'] = docColl.value;
            } else if (eng === 'KEYVALUE') {
                var kvVal = document.getElementById('editKvValueInput');
                if (kvVal && kvVal.value) {
                    payloadObj['record_payload'] = kvVal.value;
                    payloadObj['kv_value'] = kvVal.value;
                }
                var kvColl = document.getElementById('editKvCollInput');
                if (kvColl && kvColl.value) payloadObj['target_coll'] = kvColl.value;
            } else if (eng === 'VECTOR') {
                var vecCoords = document.getElementById('editVecCoordsInput');
                if (vecCoords && vecCoords.value) payloadObj['vector_coords'] = vecCoords.value;
                var vecMeta = document.getElementById('editVecMetaInput');
                if (vecMeta && vecMeta.value) payloadObj['vector_meta'] = vecMeta.value;
            } else if (eng === 'GRAPH') {
                var graphProps = document.getElementById('editGraphPropsInput');
                if (graphProps && graphProps.value) payloadObj['node_props'] = graphProps.value;
                var graphColl = document.getElementById('editGraphCollInput');
                if (graphColl && graphColl.value) payloadObj['target_coll'] = graphColl.value;
            } else if (eng === 'TIMESERIES') {
                var tsVal = document.getElementById('editTsValueInput');
                if (tsVal && tsVal.value) payloadObj['ts_value'] = tsVal.value;
                var tsUnit = document.getElementById('editTsUnitInput');
                if (tsUnit && tsUnit.value) payloadObj['ts_unit'] = tsUnit.value;
                var tsTags = document.getElementById('editTsTagsInput');
                if (tsTags && tsTags.value) payloadObj['ts_tags'] = tsTags.value;
                var tsColl = document.getElementById('editTsCollInput');
                if (tsColl && tsColl.value) payloadObj['target_coll'] = tsColl.value;
            } else if (eng === 'COLUMN') {
                var colData = document.getElementById('editColDataInput');
                if (colData && colData.value) payloadObj['col_data'] = colData.value;
                var colColl = document.getElementById('editColCollInput');
                if (colColl && colColl.value) payloadObj['target_coll'] = colColl.value;
            } else if (eng === 'GEOSPATIAL') {
                var geoLat = document.getElementById('editGeoLatInput');
                if (geoLat && geoLat.value) payloadObj['geo_lat'] = geoLat.value;
                var geoLon = document.getElementById('editGeoLonInput');
                if (geoLon && geoLon.value) payloadObj['geo_lon'] = geoLon.value;
                var geoName = document.getElementById('editGeoNameInput');
                if (geoName && geoName.value) payloadObj['geo_name'] = geoName.value;
                var geoProps = document.getElementById('editGeoPropsInput');
                if (geoProps && geoProps.value) payloadObj['geo_props'] = geoProps.value;
            } else if (eng === 'OBJECT') {
                var objPayload = document.getElementById('editObjPayloadInput');
                if (objPayload && objPayload.value) payloadObj['obj_payload'] = objPayload.value;
                var objMime = document.getElementById('editObjMimeInput');
                if (objMime && objMime.value) payloadObj['obj_mime'] = objMime.value;
                var objColl = document.getElementById('editObjCollInput');
                if (objColl && objColl.value) payloadObj['target_coll'] = objColl.value;
            }

            // Send via AJAX fetch to prevent raw JSON navigation and keep user in web interface
            var actionUrl = form.action || window.location.href;
            fetch(actionUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json; charset=UTF-8',
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: JSON.stringify(payloadObj)
            })
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function(data) {
                if (btn) {
                    btn.disabled = false;
                    btn.innerHTML = origHtml;
                }
                if (data.status === 'SUCCESS' || data.success) {
                    if (window.JettraFluxNotification) {
                        JettraFluxNotification.show('universalEditNotification', 'Registro Guardado', data.message || 'Registro actualizado exitosamente.', 'SUCCESS');
                    }
                    setTimeout(function() {
                        if (window.JettraFluxModal) {
                            JettraFluxModal.close('universalEditModal');
                        } else if (typeof hideModal === 'function') {
                            hideModal('universalEditModal');
                        }
                        // Refresh page without leaving the graphical interface
                        window.location.reload();
                    }, 650);
                } else {
                    if (window.JettraFluxNotification) {
                        JettraFluxNotification.show('universalEditNotification', 'Error al Guardar', data.message || 'No se pudo guardar el registro.', 'ERROR');
                    }
                }
            })
            .catch(function(err) {
                if (btn) {
                    btn.disabled = false;
                    btn.innerHTML = origHtml;
                }
                if (window.JettraFluxNotification) {
                    JettraFluxNotification.show('universalEditNotification', 'Error de Comunicación', (err && err.message) ? err.message : 'Error al contactar el servidor.', 'ERROR');
                }
            });

            return false;
        }

        function populateRecordFieldsFromPayload(formId, p, prettyPayload) {
            var recClass = p._recordClass || p._class || 'com.jettra.model.Record';
            var classInp = document.getElementById(formId + '_class');
            if (classInp) classInp.value = recClass;

            var table = p._table || '';
            var tableInp = document.getElementById(formId + '_table');
            if (tableInp && table) tableInp.value = table;

            var ta = document.getElementById(formId + '_payload');
            if (ta) ta.value = prettyPayload;

            var tbody = document.getElementById(formId + '_record_fields_tbody');
            if (!tbody) return;
            tbody.innerHTML = '';

            var schema = p._schema || {};
            var comps = p.components || p._components || p;

            var fieldNames = [];
            var seen = {};
            if (schema && typeof schema === 'object') {
                for (var sk in schema) {
                    if (schema.hasOwnProperty(sk) && !seen[sk]) {
                        seen[sk] = true;
                        fieldNames.push(sk);
                    }
                }
            }
            if (comps && typeof comps === 'object') {
                for (var ck in comps) {
                    if (comps.hasOwnProperty(ck) && !seen[ck]) {
                        seen[ck] = true;
                        fieldNames.push(ck);
                    }
                }
            }

            for (var i = 0; i < fieldNames.length; i++) {
                var k = fieldNames[i];
                if (k === '_table' || k.startsWith('_record') || k === '_timestamp' || k === '_version' || k === '_schema' || k === 'components' || k === '_components' || k === '_class') continue;
                var val = comps[k];
                var type = schema[k] || (typeof val === 'number' ? (Number.isInteger(val) ? 'Integer' : 'Double') : (typeof val === 'boolean' ? 'Boolean' : 'String'));
                var strVal = (val !== undefined && val !== null) ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : '';
                if (window.JettraFluxRecordForm && window.JettraFluxRecordForm.addField) {
                    window.JettraFluxRecordForm.addField(formId, k, type, strVal);
                }
            }
            if (window.JettraFluxRecordForm && window.JettraFluxRecordForm.updatePayload) {
                window.JettraFluxRecordForm.updatePayload(formId);
            }
        }

        function openUniversalEditModal(engine, db, unit, id, payloadB64) {
            var raw = (engine || 'DOCUMENT').toUpperCase();
            var eng = (raw === 'RECORD') ? 'RECORDS' : raw;
            if (eng === 'KEY_VALUE' || eng === 'KEY-VALUE') eng = 'KEYVALUE';
            if (eng === 'TIME_SERIES' || eng === 'TIMESERIE') eng = 'TIMESERIES';
            if (eng === 'GEO') eng = 'GEOSPATIAL';

            var payload = '';
            if (typeof decodeUtf8Base64 === 'function') {
                payload = decodeUtf8Base64(payloadB64);
            } else {
                try { payload = atob(payloadB64); } catch(e) { payload = payloadB64 || ''; }
            }

            var parsed = null;
            try {
                if (typeof payload === 'string' && (payload.trim().startsWith('{') || payload.trim().startsWith('['))) {
                    parsed = JSON.parse(payload);
                }
            } catch(e) {}

            var pretty = parsed ? JSON.stringify(parsed, null, 2) : (payload || '{}');
            var p = parsed || {};

            // Common display fields
            var engDisplay = document.getElementById('universalEditEngineDisplay');
            if (engDisplay) engDisplay.innerText = eng;
            var dbDisplay = document.getElementById('universalEditDbDisplay');
            if (dbDisplay) dbDisplay.innerText = db || 'default';
            var collDisplay = document.getElementById('universalEditCollDisplay');
            if (collDisplay) collDisplay.innerText = unit || 'default';
            var idDisplay = document.getElementById('universalEditIdDisplay');
            if (idDisplay) idDisplay.innerText = id || '';

            // Common hidden inputs
            var engInp = document.getElementById('universalEditEngineInput');
            if (engInp) engInp.value = eng;
            var dbInp = document.getElementById('universalEditDbInput');
            if (dbInp) dbInp.value = db || 'default';
            var collInp = document.getElementById('universalEditCollInput');
            if (collInp) collInp.value = unit || 'default';
            var idInp = document.getElementById('universalEditIdInput');
            if (idInp) idInp.value = id || '';

            // Enable and show ONLY the engine corresponding to this record
            switchEditEngine(eng);

            // Populate engine-specific inputs
            if (eng === 'RECORDS') {
                populateRecordFieldsFromPayload('edit_rec', p, pretty);
                var recClassInp = document.getElementById('editRecClassInput');
                if (recClassInp) recClassInp.value = p._recordClass || p._class || 'com.jettra.model.Record';
                var recCollInp = document.getElementById('editRecCollInput');
                if (recCollInp) recCollInp.value = unit || 'default';
                var recPayloadInp = document.getElementById('editRecPayloadInput');
                if (recPayloadInp) recPayloadInp.value = pretty;
            } else if (eng === 'DOCUMENT') {
                var docColl = document.getElementById('editDocCollInput');
                if (docColl) docColl.value = unit || 'default';
                var docClass = document.getElementById('editDocClassInput');
                if (docClass) docClass.value = p._class || '';
                var docPayload = document.getElementById('editDocPayloadInput');
                if (docPayload) docPayload.value = pretty;
            } else if (eng === 'KEYVALUE') {
                var kvColl = document.getElementById('editKvCollInput');
                if (kvColl) kvColl.value = unit || 'default';
                var kvVal = document.getElementById('editKvValueInput');
                if (kvVal) kvVal.value = (typeof payload === 'string') ? payload : pretty;
            } else if (eng === 'VECTOR') {
                var vecColl = document.getElementById('editVecCollInput');
                if (vecColl) vecColl.value = unit || 'default';
                var vecCoords = '0.12, 0.45, 0.88, 0.31';
                if (Array.isArray(p.coordinates)) vecCoords = p.coordinates.join(', ');
                else if (Array.isArray(p.embedding)) vecCoords = p.embedding.join(', ');
                else if (Array.isArray(p.vector)) vecCoords = p.vector.join(', ');
                else if (p.coordinates || p.embedding || p.vector) vecCoords = String(p.coordinates || p.embedding || p.vector);
                var vecCoordsInp = document.getElementById('editVecCoordsInput');
                if (vecCoordsInp) vecCoordsInp.value = vecCoords;
                var vecMeta = document.getElementById('editVecMetaInput');
                if (vecMeta) vecMeta.value = pretty;
            } else if (eng === 'GRAPH') {
                var graphColl = document.getElementById('editGraphCollInput');
                if (graphColl) graphColl.value = p.label || unit || 'Vertex';
                var graphProps = document.getElementById('editGraphPropsInput');
                if (graphProps) graphProps.value = pretty;
            } else if (eng === 'TIMESERIES') {
                var tsColl = document.getElementById('editTsCollInput');
                if (tsColl) tsColl.value = p.metric || unit || 'telemetry';
                var tsTs = document.getElementById('editTsTimestampInput');
                if (tsTs) tsTs.value = p.timestamp || id;
                var tsVal = document.getElementById('editTsValueInput');
                if (tsVal) tsVal.value = (p.value !== undefined) ? p.value : '25.4';
                var tsUnit = document.getElementById('editTsUnitInput');
                if (tsUnit) tsUnit.value = p.unit || 'celsius';
                var tsTags = document.getElementById('editTsTagsInput');
                if (tsTags) tsTags.value = pretty;
            } else if (eng === 'COLUMN') {
                var colColl = document.getElementById('editColCollInput');
                if (colColl) colColl.value = p._family || unit || 'analytics';
                var colData = document.getElementById('editColDataInput');
                if (colData) colData.value = pretty;
            } else if (eng === 'GEOSPATIAL') {
                var geoColl = document.getElementById('editGeoCollInput');
                if (geoColl) geoColl.value = p._layer || unit || 'stores_layer';
                var geoLat = document.getElementById('editGeoLatInput');
                if (geoLat) geoLat.value = (p.lat !== undefined ? p.lat : (p.latitude !== undefined ? p.latitude : '8.9824'));
                var geoLon = document.getElementById('editGeoLonInput');
                if (geoLon) geoLon.value = (p.lon !== undefined ? p.lon : (p.longitude !== undefined ? p.longitude : '-79.5199'));
                var geoName = document.getElementById('editGeoNameInput');
                if (geoName) geoName.value = p.name || id;
            } else if (eng === 'OBJECT') {
                var objColl = document.getElementById('editObjCollInput');
                if (objColl) objColl.value = p.bucket || unit || 'media_bucket';
                var objMime = document.getElementById('editObjMimeInput');
                if (objMime) objMime.value = p.mimeType || 'application/json';
                var objPayload = document.getElementById('editObjPayloadInput');
                if (objPayload) objPayload.value = p.content || payload || pretty;
            }

            // Sync with universalEditPayloadInput for backward compatibility
            var uniPayload = document.getElementById('universalEditPayloadInput');
            if (uniPayload) uniPayload.value = pretty;

            if (window.JettraFluxNotification) {
                JettraFluxNotification.hide('universalEditNotification');
            }

            if (window.JettraFluxModal) {
                JettraFluxModal.open('universalEditModal');
            } else if (typeof showModal === 'function') {
                showModal('universalEditModal');
            }
        }

        function switchEditEngine(engineKey) {
            var raw = (engineKey || 'DOCUMENT').toUpperCase();
            var eng = (raw === 'RECORD') ? 'RECORDS' : raw;
            var engineInput = document.getElementById('universalEditEngineInput');
            if (engineInput) engineInput.value = eng;

            // Show ONLY the active engine's section, hide and disable all others
            var sections = document.querySelectorAll('.universal-edit-engine-section');
            sections.forEach(function(sec) {
                var isMatch = (sec.getAttribute('data-engine') === eng);
                sec.style.display = isMatch ? 'block' : 'none';
                var controls = sec.querySelectorAll('input, select, textarea');
                controls.forEach(function(ctrl) { ctrl.disabled = !isMatch; });
            });

            // Show ONLY the active engine's pill tab in edit mode
            document.querySelectorAll('[id^="edit_engine_tab_btn_"]').forEach(function(pill) {
                var pillEng = pill.getAttribute('data-engine');
                var color = pill.getAttribute('data-color') || '#fbbf24';
                var label = pill.querySelector('span');
                var isSelected = (pillEng === eng || (pillEng === 'RECORDS' && raw === 'RECORD'));
                if (isSelected) {
                    pill.style.display = 'inline-flex';
                    pill.style.border = '2px solid ' + color;
                    pill.style.background = 'rgba(255,255,255,0.08)';
                    if (label) label.style.color = color;
                } else {
                    pill.style.display = 'none'; // Only show the matching engine for the selected record!
                }
            });

            // Update submit button text and color
            var submitBtn = document.getElementById('btnUniversalEditSubmit');
            if (submitBtn) {
                var labelSpan = submitBtn.querySelector('span');
                var displayTitle = (eng === 'RECORDS' || raw === 'RECORD') ? 'RECORD' : eng;
                if (labelSpan) labelSpan.textContent = 'Guardar Cambios en ' + displayTitle + ' (v+1)';
            }
        }

        window.submitUniversalEditRecord = submitUniversalEditRecord;
        window.openUniversalEditModal = openUniversalEditModal;
        window.switchEditEngine = switchEditEngine;
        </script>
        """);
    }
}
