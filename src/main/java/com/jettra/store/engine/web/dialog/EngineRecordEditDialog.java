package com.jettra.store.engine.web.dialog;

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
 * Exclusively built with JettraFlux components, structurally mirroring
 * the EngineRecordInsertionDialog layout per storage engine:
 * - Document: Target Collection, Java Class, and JettraFluxJsonEditor.
 * - KeyValue: Bucket/Namespace, TTL, and Value.
 * - Vector: Index, Distance Metric Select, Semantic Label, Embeddings Float Array, and JettraFluxJsonEditor for Metadata.
 * - Graph: Entity mode selector (Node vs Edge) with JettraFluxJsonEditor for Properties.
 * - TimeSeries: Metric, Value, Unit, Timestamp with "Ahora" button, and JettraFluxJsonEditor for IoT Tags.
 * - Wide Column: Column Family, Qualifier, and JettraFluxJsonEditor for Dynamic Columns.
 * - Geospatial: Layer, Name, Geometry Type Select, Coordinates (Lat/Lon), and JettraFluxJsonEditor for GeoJSON metadata.
 * - Pure Object: Bucket, Object Class, MIME Type, and Payload.
 * - Relational Records: JettraFluxRecordForm with typed columns table, type selector, and canonical JSON dual view.
 */
public final class EngineRecordEditDialog {

    public static final String MODAL_ID = "universalEditModal";
    public static final String FORM_ID = "universalEditForm";
    public static final String NOTIFICATION_ID = "universalEditNotification";

    private EngineRecordEditDialog() {}

    public static Widget build(String actionUrl) {
        // 1. Notification feedback banner inside modal
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

        // 4. Form with polymorphic engine sections mirroring EngineRecordInsertionDialog
        Widget form = Form.of(
            InputHidden.of("action", "update_object"),
            InputHidden.of("is_ajax", "true"),
            InputHidden.of("engine_type", "DOCUMENT").id("universalEditEngineInput"),
            InputHidden.of("target_db", "default").id("universalEditDbInput"),
            InputHidden.of("target_coll", "default").id("universalEditCollInput"),
            InputHidden.of("target_id", "").id("universalEditIdInput"),

            commonRow,

            // Polymorphic sections for the 9 engines
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
                Div.of(
                    Label.of("Colección de Documentos:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of("target_coll").id("editDocCollInput").binding("target_coll").value("customers")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#38bdf8; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Java Entity Class (_class metadata):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of("doc_class").id("editDocClassInput").binding("doc_class").value("")
                        .modifier(new Modifier().attribute("placeholder", "com.jettra.models.Customer")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px; font-family:monospace;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editDocPayload", "Document JSON Body", "{\n  \n}")
                    .name("doc_payload")
                    .height("220px")
            ),

            // Legacy direct textarea for backwards compatibility
            TextArea.create().name("doc_payload_direct").id("editDocPayloadInput")
                .modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "DOCUMENT").style("display:block;"));
    }

    // 2. KEYVALUE Section
    private static Widget buildKeyValueSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Namespace / Bucket:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editKvCollInput").binding("target_coll").value("session_cache")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#10b981; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("TTL (Segundos - Opcional):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editKvTtlInput").binding("kv_ttl").value("")
                        .modifier(new Modifier().attribute("placeholder", "Ej. 3600 (0 o vacío = Sin expiración)")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Valor / Contenido Almacenado:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.create().name("kv_value").rows(8).id("editKvValueInput")
                    .modifier(new Modifier().attribute("placeholder", "Ingrese cadena, JSON o datos a almacenar...")
                        .style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#10b981; font-family:monospace; font-size:12px; padding:10px; line-height:1.4; resize:vertical;"))
            ).modifier(new Modifier().style("margin-bottom:6px;")),

            Paragraph.of(Text.of("Soporta tipos primitivos, cadenas Base64 y payloads serializados."))
                .modifier(new Modifier().style("font-size:11px; color:var(--j-text-muted); margin:0;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "KEYVALUE").style("display:none;"));
    }

    // 3. VECTOR Section
    private static Widget buildVectorSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Índice Vectorial (Index):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editVecCollInput").binding("target_coll").value("semantic_index")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#8b5cf6; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Métrica de Distancia:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    JettraFluxSelect.of("editVecMetricSelect", "vector_metric")
                        .addOption("COSINE", "Cosine Similarity", true)
                        .addOption("EUCLIDEAN", "Euclidean (L2)")
                        .addOption("DOT_PRODUCT", "Dot Product (Inner Product)")
                        .addOption("MANHATTAN", "Manhattan (L1)")
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Etiqueta / Clase Semántica:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editVecLabelInput").binding("vector_label").value("")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Array de Embeddings (float[] flotantes separados por coma):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextArea.of("0.12, 0.45, 0.88, 0.31").id("editVecCoordsInput").binding("vector_coords")
                    .modifier(new Modifier().attribute("rows", "3")
                        .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#a855f7; font-family:monospace; font-size:12px; resize:vertical;"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editVecMeta", "Metadatos Asociados (JSON)", "{\n  \n}")
                    .name("vector_meta")
                    .height("160px")
            ),

            TextArea.create().name("vector_meta_direct").id("editVecMetaInput")
                .modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "VECTOR").style("display:none;"));
    }

    // 4. GRAPH Section
    private static Widget buildGraphSection() {
        return Div.of(
            Div.of(
                Label.of("Tipo de Entidad Grafo:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                Div.of(
                    RadioButton.of("edit_graph_mode_node", "Vértice / Nodo (Vertex)")
                        .id("edit_graph_mode_node")
                        .name("graph_mode")
                        .value("node")
                        .checked(true)
                        .onChange("document.getElementById('edit_graph_node_fields').style.display='block';document.getElementById('edit_graph_edge_fields').style.display='none';")
                        .modifier(new Modifier().style("margin-right:16px; font-size:12px; color:var(--j-text-primary); cursor:pointer;")),
                    RadioButton.of("edit_graph_mode_edge", "Arista / Relación (Edge)")
                        .id("edit_graph_mode_edge")
                        .name("graph_mode")
                        .value("edge")
                        .checked(false)
                        .onChange("document.getElementById('edit_graph_node_fields').style.display='none';document.getElementById('edit_graph_edge_fields').style.display='block';")
                        .modifier(new Modifier().style("font-size:12px; color:var(--j-text-primary); cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px;"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),

            // Node specific fields
            Div.of(
                Div.of(
                    Label.of("Etiqueta de Nodo (Node Label / Type):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGraphNodeLabel").binding("node_label").value("Person")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#ec4899; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("margin-bottom:12px;")),
                JettraFluxJsonEditor.of("editGraphNodeProps", "Propiedades del Vértice (JSON)", "{\n  \n}")
                    .name("node_props")
                    .height("170px")
            ).id("edit_graph_node_fields"),

            // Edge specific fields
            Div.of(
                Div.of(
                    Div.of(
                        Label.of("Nodo Origen (From ID):")
                            .modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("editGraphFromId").binding("from_id").value("")
                            .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                    ).modifier(new Modifier().style("flex:1;")),
                    Div.of(
                        Label.of("Tipo de Relación (Edge Label):")
                            .modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("editGraphEdgeLabel").binding("edge_label").value("RELATES_TO")
                            .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                    ).modifier(new Modifier().style("flex:1;")),
                    Div.of(
                        Label.of("Nodo Destino (To ID):")
                            .modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("editGraphToId").binding("to_id").value("")
                            .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                    ).modifier(new Modifier().style("flex:1;"))
                ).modifier(new Modifier().style("display:flex; gap:10px; margin-bottom:12px;")),
                JettraFluxJsonEditor.of("editGraphEdgeProps", "Propiedades de la Arista (JSON)", "{\n  \n}")
                    .name("edge_props")
                    .height("170px")
            ).id("edit_graph_edge_fields").modifier(new Modifier().style("display:none;")),

            // Legacy mirrors for backwards compatibility
            TextField.of().id("editGraphCollInput").binding("target_coll").value("Vertex").modifier(new Modifier().style("display:none;")),
            TextArea.create().name("node_props_direct").id("editGraphPropsInput").modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "GRAPH").style("display:none;"));
    }

    // 5. TIMESERIES Section
    private static Widget buildTimeSeriesSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Nombre de Serie / Métrica:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsCollInput").binding("target_coll").value("server_temperature")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#06b6d4; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1.2;")),
                Div.of(
                    Label.of("Valor Numérico (Double):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsValueInput").binding("ts_value").value("25.4")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Unidad de Medida:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editTsUnitInput").binding("ts_unit").value("°C")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:0.8;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Label.of("Timestamp (Milisegundos Epoch o ISO-8601):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                Div.of(
                    TextField.of().id("editTsTimestampInput").binding("ts_timestamp").value("")
                        .modifier(new Modifier().style("flex:1; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-family:monospace; font-size:12px;")),
                    Button.of(Icon.of("fas fa-clock"), Text.of(" Ahora"))
                        .modifier(new Modifier().attribute("type", "button").attribute("onclick", "document.getElementById('editTsTimestampInput').value = Date.now();")
                            .style("padding:8px 14px; font-size:11px; background:var(--j-bg-subsurface); color:var(--j-text-primary); border:1px solid var(--j-border); border-radius:6px; cursor:pointer;"))
                ).modifier(new Modifier().style("display:flex; gap:8px;"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editTsTags", "Tags / Dimensiones IoT (JSON)", "{\n  \n}")
                    .name("ts_tags")
                    .height("160px")
            ),

            TextArea.create().name("ts_tags_direct").id("editTsTagsInput").modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "TIMESERIES").style("display:none;"));
    }

    // 6. COLUMN Section
    private static Widget buildColumnSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Familia de Columnas (Column Family):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editColCollInput").binding("target_coll").value("user_analytics")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#f97316; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Column Qualifier (Opcional):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editColQualifierInput").binding("col_qualifier").value("profile:full")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editColData", "Dynamic Columns & Qualifier Map (JSON)", "{\n  \n}")
                    .name("col_data")
                    .height("180px")
            ),

            TextArea.create().name("col_data_direct").id("editColDataInput").modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "COLUMN").style("display:none;"));
    }

    // 7. GEOSPATIAL Section
    private static Widget buildGeoSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Capa Espacial (Spatial Layer):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoCollInput").binding("target_coll").value("facilities_layer")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Nombre de Feature / Lugar:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoNameInput").binding("geo_name").value("")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Tipo de Geometría:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    JettraFluxSelect.of("editGeoTypeSelect", "geo_type")
                        .addOption("Point", "Point (Coordenada)", true)
                        .addOption("Polygon", "Polygon (Polígono)")
                        .addOption("MultiPolygon", "MultiPolygon")
                        .addOption("LineString", "LineString (Trayectoria)")
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Div.of(
                    Label.of("Latitud (-90.0 a +90.0):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoLatInput").binding("geo_lat").value("8.9824")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Longitud (-180.0 a +180.0):")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editGeoLonInput").binding("geo_lon").value("-79.5199")
                        .modifier(new Modifier().attribute("type", "number").attribute("step", "any")
                            .style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editGeoMeta", "Propiedades y Coordenadas GeoJSON (JSON)", "{\n  \n}")
                    .name("geo_props")
                    .height("160px")
            ),

            TextArea.create().name("geo_props_direct").id("editGeoPropsInput").modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "GEOSPATIAL").style("display:none;"));
    }

    // 8. OBJECT Section
    private static Widget buildObjectSection() {
        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Bucket de Almacenamiento:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editObjCollInput").binding("target_coll").value("media_bucket")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#a855f7; font-weight:600; font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Canonical Java Object Class:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editObjClassInput").binding("obj_class").value("com.jettra.storage.MediaFile")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("MIME / Content Type:")
                        .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("editObjMimeInput").binding("obj_mime").value("application/json")
                        .modifier(new Modifier().style("width:100%; box-sizing:border-box; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("editObjPayload", "Contenido del Objeto / Payload Serializado (JSON o Raw)", "{\n  \n}")
                    .name("obj_payload")
                    .height("160px")
            ),

            TextArea.create().name("obj_payload_direct").id("editObjPayloadInput").modifier(new Modifier().style("display:none;"))
        ).modifier(new Modifier().cssClass("universal-edit-engine-section").attribute("data-engine", "OBJECT").style("display:none;"));
    }

    // 9. RECORDS (Java 25) Section: Adaptive viewer similar to Inspect view, but fully editable
    private static Widget buildRecordsSection() {
        return Div.of(
            Div.of(
                // Header row styled like Inspect dialog
                Div.of(
                    Div.of(
                        Span.of(Icon.of("fas fa-microchip"), Text.of(" Record Class:")).modifier(new Modifier().style("font-size:11.5px; font-weight:700; color:#f43f5e; margin-right:6px; display:inline-flex; align-items:center; gap:4px;")),
                        TextField.of("rec_class").id("edit_rec_class").value("com.jettra.model.EmployeeProfileRecord")
                            .modifier(new Modifier().attribute("oninput", "syncRecordEditPayload()").style("background:rgba(244,63,94,0.12); color:#fb7185; border:1px solid rgba(244,63,94,0.3); padding:3px 8px; border-radius:4px; font-size:12px; font-weight:700; font-family:monospace; min-width:240px;")),
                        Span.of(Icon.of("fas fa-table"), Text.of(" Tabla:")).modifier(new Modifier().style("font-size:11px; font-weight:600; color:var(--j-text-muted); margin-left:10px; margin-right:6px; display:inline-flex; align-items:center; gap:4px; color:#10b981;")),
                        TextField.of("target_coll").id("edit_rec_table").value("default")
                            .modifier(new Modifier().attribute("oninput", "syncRecordEditPayload()").style("background:rgba(16,185,129,0.1); color:#10b981; border:1px solid rgba(16,185,129,0.3); padding:3px 8px; border-radius:4px; font-size:11.5px; font-weight:700; min-width:130px;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:6px; flex-wrap:wrap;")),
                    Div.of(
                        Span.of(Icon.of("fas fa-shield-halved"), Text.of(" IMMUTABLE TYPED SCHEMA"))
                            .modifier(new Modifier().style("font-size:9.5px; font-weight:800; background:rgba(16,185,129,0.2); color:#10b981; border:1px solid rgba(16,185,129,0.4); padding:2px 8px; border-radius:12px; margin-right:8px; display:inline-flex; align-items:center; gap:4px;")),
                        Button.of(Icon.of("fas fa-code"), Text.of(" JSON Canónico"))
                            .id("edit_rec_btn_mode_toggle")
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "toggleEditRecJsonMode()").style("background:rgba(255,255,255,0.08); color:var(--j-text-primary); border:1px solid var(--j-border); padding:4px 8px; border-radius:5px; font-size:11px; font-weight:600; cursor:pointer; margin-right:6px; display:inline-flex; align-items:center; gap:4px;")),
                        Button.of(Icon.of("fas fa-plus"), Text.of(" Agregar Campo"))
                            .modifier(new Modifier().attribute("type", "button").attribute("onclick", "addRecordEditField('', 'String', '')").style("background:#f43f5e; color:#fff; border:none; padding:4px 10px; border-radius:5px; font-size:11px; font-weight:700; cursor:pointer; display:inline-flex; align-items:center; gap:4px;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:6px; flex-wrap:wrap;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:8px;")),

                // Structured table of fields, schema types, and editable component values
                Div.of(
                    RawHtml.of("""
                    <table style="width:100%; border-collapse:collapse; font-size:11.5px;">
                      <thead>
                        <tr style="background:var(--j-bg-body); border-bottom:1px solid var(--j-border); text-align:left; color:var(--j-text-secondary);">
                          <th style="padding:7px 10px; width:28%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-cube" style="color:#f43f5e; margin-right:5px;"></i> Nombre de Campo (_schema)</th>
                          <th style="padding:7px 10px; width:28%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-tag" style="color:#38bdf8; margin-right:5px;"></i> Tipo de Dato (_schema)</th>
                          <th style="padding:7px 10px; width:38%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-database" style="color:#4ade80; margin-right:5px;"></i> Valor Componente (components)</th>
                          <th style="padding:7px 10px; width:6%; text-align:center;"></th>
                        </tr>
                      </thead>
                      <tbody id="edit_rec_record_fields_tbody">
                      </tbody>
                    </table>
                    <div id="edit_rec_record_json_container" style="display:none; flex-direction:column; gap:6px; margin-top:12px;">
                      <div style="display:flex; justify-content:space-between; align-items:center; background:var(--j-bg-body); padding:6px 10px; border-radius:6px 6px 0 0; border:1px solid var(--j-border); border-bottom:none;">
                        <div style="display:flex; align-items:center; gap:6px;">
                          <i class="fas fa-file-invoice" style="color:#10b981; font-size:12px;"></i>
                          <span style="font-size:11px; font-weight:700; color:var(--j-text-secondary); text-transform:uppercase;">Canonical Record Payload Serialization</span>
                          <span id="edit_rec_json_status" style="font-size:9.5px; font-weight:700; padding:1px 6px; border-radius:10px; background:rgba(16,185,129,0.15); color:#10b981; border:1px solid rgba(16,185,129,0.3);">SYNCED</span>
                        </div>
                      </div>
                      <textarea id="edit_rec_json_raw" oninput="syncRecordJsonToFields()" style="width:100%; min-height:160px; font-family:monospace; font-size:11.5px; background:var(--j-bg-body, #0a0f1d); border:1px solid var(--j-border, rgba(255,255,255,0.1)); border-radius:0 0 6px 6px; color:#38bdf8; padding:8px 10px; box-sizing:border-box; resize:vertical;"></textarea>
                    </div>
                    """)
                ).modifier(new Modifier().style("overflow-x:auto; margin-top:8px;"))
            ).id("edit_rec_record_editor_container").modifier(new Modifier().style("background:var(--j-bg-subsurface); border:1px solid rgba(244,63,94,0.35); border-radius:8px; padding:14px 16px; margin-bottom:12px;")),

            // Legacy compatibility inputs and serialized payload textarea
            TextArea.create().name("rec_payload").id("edit_rec_payload")
                .modifier(new Modifier().style("display:none;")),
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
        function safeDecodePayload(b64) {
            if (!b64 || b64 === 'undefined' || b64 === 'null') return '';
            if (typeof b64 === 'object' && b64 !== null) {
                try { return JSON.stringify(b64); } catch(e) { return '{}'; }
            }
            var str = String(b64).trim();
            if (str.startsWith('{') || str.startsWith('[') || str.startsWith('<')) {
                return str;
            }
            if (str.indexOf('%7B') >= 0 || str.indexOf('%22') >= 0 || str.indexOf('%20') >= 0) {
                try { str = decodeURIComponent(str); } catch(e) {}
                if (str.startsWith('{') || str.startsWith('[') || str.startsWith('<')) return str;
            }
            try {
                var bin = atob(str);
                var bytes = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                return new TextDecoder('utf-8').decode(bytes);
            } catch (e) {
                try { return atob(str); } catch (e2) { return str; }
            }
        }

        function setJsonEditorVal(editorId, jsonVal) {
            var str = '';
            if (typeof jsonVal === 'object' && jsonVal !== null) {
                str = JSON.stringify(jsonVal, null, 2);
            } else if (typeof jsonVal === 'string') {
                var trimmed = jsonVal.trim();
                if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                    try { str = JSON.stringify(JSON.parse(trimmed), null, 2); } catch(e) { str = jsonVal; }
                } else {
                    str = jsonVal;
                }
            } else {
                str = String(jsonVal || '');
            }

            var inp = document.getElementById(editorId + '_input');
            if (inp) {
                inp.value = str;
                if (window.JettraFluxJsonEditor && window.JettraFluxJsonEditor.validate) {
                    window.JettraFluxJsonEditor.validate(editorId + '_input', editorId + '_status');
                }
            }
            var direct = document.getElementById(editorId + 'Input');
            if (direct) direct.value = str;
        }

        function normalizeEditEngine(engineKey) {
            if (!engineKey) return 'DOCUMENT';
            var raw = String(engineKey).trim().toUpperCase();
            if (raw === 'RECORD' || raw.indexOf('RECORD') !== -1 || raw === 'REC') {
                return 'RECORDS';
            }
            if (raw === 'KEY_VALUE' || raw === 'KEY-VALUE' || raw === 'KV') return 'KEYVALUE';
            if (raw === 'TIME_SERIES' || raw === 'TIMESERIE' || raw === 'TS') return 'TIMESERIES';
            if (raw === 'GEO' || raw === 'SPATIAL') return 'GEOSPATIAL';
            if (raw === 'WIDE_COLUMN' || raw === 'WIDECOLUMN' || raw === 'COL') return 'COLUMN';
            if (raw === 'BLOB' || raw === 'OBJ') return 'OBJECT';
            if (raw === 'VEC' || raw === 'EMBEDDINGS') return 'VECTOR';
            if (raw === 'DOC' || raw === 'JSON') return 'DOCUMENT';
            return raw;
        }

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

            var engInp = document.getElementById('universalEditEngineInput');
            var eng = normalizeEditEngine(engInp ? engInp.value : 'DOCUMENT');

            // Sync Record Form if active
            if (eng === 'RECORDS') {
                try {
                    syncRecordEditPayload();
                } catch(err) {
                    console.warn('RecordForm payload sync warning', err);
                }
                var recPayloadEl = document.getElementById('edit_rec_payload') || document.getElementById('editRecPayloadInput');
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

            if (eng === 'RECORDS') {
                var recEl = document.getElementById('edit_rec_payload') || document.getElementById('editRecPayloadInput');
                if (recEl && recEl.value) {
                    payloadObj['record_payload'] = recEl.value;
                    payloadObj['rec_payload'] = recEl.value;
                }
                var recTable = document.getElementById('edit_rec_table') || document.getElementById('editRecCollInput');
                if (recTable && recTable.value) payloadObj['target_coll'] = recTable.value;
                var recCls = document.getElementById('edit_rec_class') || document.getElementById('editRecClassInput');
                if (recCls && recCls.value) payloadObj['rec_class'] = recCls.value;
            } else if (eng === 'DOCUMENT') {
                var docEl = document.getElementById('editDocPayload_input') || document.getElementById('editDocPayloadInput');
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
                var kvTtl = document.getElementById('editKvTtlInput');
                if (kvTtl && kvTtl.value) payloadObj['kv_ttl'] = kvTtl.value;
            } else if (eng === 'VECTOR') {
                var vecCoords = document.getElementById('editVecCoordsInput');
                if (vecCoords && vecCoords.value) payloadObj['vector_coords'] = vecCoords.value;
                var vecMeta = document.getElementById('editVecMeta_input') || document.getElementById('editVecMetaInput');
                if (vecMeta && vecMeta.value) {
                    payloadObj['vector_meta'] = vecMeta.value;
                    payloadObj['record_payload'] = vecMeta.value;
                }
                var vecColl = document.getElementById('editVecCollInput');
                if (vecColl && vecColl.value) payloadObj['target_coll'] = vecColl.value;
                var vecMetric = document.getElementById('editVecMetricSelect');
                if (vecMetric && vecMetric.value) payloadObj['vector_metric'] = vecMetric.value;
                var vecLabel = document.getElementById('editVecLabelInput');
                if (vecLabel && vecLabel.value) payloadObj['vector_label'] = vecLabel.value;
            } else if (eng === 'GRAPH') {
                var isEdge = document.getElementById('edit_graph_mode_edge') && document.getElementById('edit_graph_mode_edge').checked;
                payloadObj['graph_mode'] = isEdge ? 'edge' : 'node';
                if (isEdge) {
                    var fromId = document.getElementById('editGraphFromId');
                    if (fromId && fromId.value) payloadObj['from_id'] = fromId.value;
                    var edgeLbl = document.getElementById('editGraphEdgeLabel');
                    if (edgeLbl && edgeLbl.value) payloadObj['edge_label'] = edgeLbl.value;
                    var toId = document.getElementById('editGraphToId');
                    if (toId && toId.value) payloadObj['to_id'] = toId.value;
                    var edgeProps = document.getElementById('editGraphEdgeProps_input');
                    if (edgeProps && edgeProps.value) {
                        payloadObj['node_props'] = edgeProps.value;
                        payloadObj['edge_props'] = edgeProps.value;
                        payloadObj['record_payload'] = edgeProps.value;
                    }
                } else {
                    var nodeLbl = document.getElementById('editGraphNodeLabel');
                    if (nodeLbl && nodeLbl.value) {
                        payloadObj['node_label'] = nodeLbl.value;
                        payloadObj['target_coll'] = nodeLbl.value;
                    }
                    var nodeProps = document.getElementById('editGraphNodeProps_input') || document.getElementById('editGraphPropsInput');
                    if (nodeProps && nodeProps.value) {
                        payloadObj['node_props'] = nodeProps.value;
                        payloadObj['record_payload'] = nodeProps.value;
                    }
                }
            } else if (eng === 'TIMESERIES') {
                var tsVal = document.getElementById('editTsValueInput');
                if (tsVal && tsVal.value) payloadObj['ts_value'] = tsVal.value;
                var tsUnit = document.getElementById('editTsUnitInput');
                if (tsUnit && tsUnit.value) payloadObj['ts_unit'] = tsUnit.value;
                var tsTs = document.getElementById('editTsTimestampInput');
                if (tsTs && tsTs.value) payloadObj['ts_timestamp'] = tsTs.value;
                var tsTags = document.getElementById('editTsTags_input') || document.getElementById('editTsTagsInput');
                if (tsTags && tsTags.value) {
                    payloadObj['ts_tags'] = tsTags.value;
                    payloadObj['record_payload'] = tsTags.value;
                }
                var tsColl = document.getElementById('editTsCollInput');
                if (tsColl && tsColl.value) payloadObj['target_coll'] = tsColl.value;
            } else if (eng === 'COLUMN') {
                var colData = document.getElementById('editColData_input') || document.getElementById('editColDataInput');
                if (colData && colData.value) {
                    payloadObj['col_data'] = colData.value;
                    payloadObj['record_payload'] = colData.value;
                }
                var colColl = document.getElementById('editColCollInput');
                if (colColl && colColl.value) payloadObj['target_coll'] = colColl.value;
                var colQual = document.getElementById('editColQualifierInput');
                if (colQual && colQual.value) payloadObj['col_qualifier'] = colQual.value;
            } else if (eng === 'GEOSPATIAL') {
                var geoLat = document.getElementById('editGeoLatInput');
                if (geoLat && geoLat.value) payloadObj['geo_lat'] = geoLat.value;
                var geoLon = document.getElementById('editGeoLonInput');
                if (geoLon && geoLon.value) payloadObj['geo_lon'] = geoLon.value;
                var geoName = document.getElementById('editGeoNameInput');
                if (geoName && geoName.value) payloadObj['geo_name'] = geoName.value;
                var geoType = document.getElementById('editGeoTypeSelect');
                if (geoType && geoType.value) payloadObj['geo_type'] = geoType.value;
                var geoProps = document.getElementById('editGeoMeta_input') || document.getElementById('editGeoPropsInput');
                if (geoProps && geoProps.value) {
                    payloadObj['geo_props'] = geoProps.value;
                    payloadObj['record_payload'] = geoProps.value;
                }
                var geoColl = document.getElementById('editGeoCollInput');
                if (geoColl && geoColl.value) payloadObj['target_coll'] = geoColl.value;
            } else if (eng === 'OBJECT') {
                var objPayload = document.getElementById('editObjPayload_input') || document.getElementById('editObjPayloadInput');
                if (objPayload && objPayload.value) {
                    payloadObj['obj_payload'] = objPayload.value;
                    payloadObj['record_payload'] = objPayload.value;
                }
                var objMime = document.getElementById('editObjMimeInput');
                if (objMime && objMime.value) payloadObj['obj_mime'] = objMime.value;
                var objClass = document.getElementById('editObjClassInput');
                if (objClass && objClass.value) payloadObj['obj_class'] = objClass.value;
                var objColl = document.getElementById('editObjCollInput');
                if (objColl && objColl.value) payloadObj['target_coll'] = objColl.value;
            }

            var uniPayloadEl = document.getElementById('universalEditPayloadInput');
            if (uniPayloadEl && payloadObj['record_payload']) {
                uniPayloadEl.value = payloadObj['record_payload'];
            }

            // Resolve target endpoint safely without HTML DOM clobbering from <input name="action">
            var actionAttr = (typeof form.getAttribute === 'function') ? form.getAttribute('action') : null;
            var actionUrl = (actionAttr && typeof actionAttr === 'string' && actionAttr.indexOf('[object') === -1 && actionAttr.trim().length > 0)
                ? actionAttr
                : (window.lastActionUrl || '/engines');
            if (eng && actionUrl.indexOf('engine=') === -1) {
                actionUrl += (actionUrl.indexOf('?') === -1 ? '?' : '&') + 'engine=' + encodeURIComponent(eng);
            }

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
                if (!res.ok) {
                    return res.text().then(function(errText) {
                        var errMsg = 'HTTP ' + res.status;
                        try {
                            var jsonErr = JSON.parse(errText);
                            if (jsonErr && (jsonErr.message || jsonErr.error)) {
                                errMsg += ': ' + (jsonErr.message || jsonErr.error);
                            }
                        } catch(e) {}
                        throw new Error(errMsg);
                    });
                }
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

        function toggleEditRecJsonMode() {
            var tbl = document.querySelector('#edit_rec_record_editor_container table');
            var jsonCont = document.getElementById('edit_rec_record_json_container');
            var btn = document.getElementById('edit_rec_btn_mode_toggle');
            if (!jsonCont) return;
            var isJsonVisible = (jsonCont.style.display === 'flex' || jsonCont.style.display === 'block');
            if (!isJsonVisible) {
                if (tbl) tbl.style.display = 'none';
                jsonCont.style.display = 'flex';
                if (btn) {
                    btn.innerHTML = '<i class="fas fa-table"></i> Vista Estructurada';
                    btn.style.color = '#38bdf8';
                }
                var rawTa = document.getElementById('edit_rec_json_raw');
                var pInp = document.getElementById('edit_rec_payload');
                if (rawTa && pInp) rawTa.value = pInp.value;
            } else {
                if (tbl) tbl.style.display = 'table';
                jsonCont.style.display = 'none';
                if (btn) {
                    btn.innerHTML = '<i class="fas fa-code"></i> JSON Canónico';
                    btn.style.color = 'var(--j-text-primary)';
                }
            }
        }

        function syncRecordJsonToFields() {
            var rawTa = document.getElementById('edit_rec_json_raw');
            if (!rawTa) return;
            var text = rawTa.value.trim();
            var statusEl = document.getElementById('edit_rec_json_status');
            try {
                var parsed = JSON.parse(text);
                if (statusEl) {
                    statusEl.innerText = 'VALID JSON';
                    statusEl.style.color = '#10b981';
                    statusEl.style.borderColor = 'rgba(16,185,129,0.3)';
                }
                var p1 = document.getElementById('edit_rec_payload');
                if (p1) p1.value = text;
                var p2 = document.getElementById('editRecPayloadInput');
                if (p2) p2.value = text;
                var p3 = document.getElementById('universalEditPayloadInput');
                if (p3) p3.value = text;

                if (parsed._recordClass) {
                    var clsInp = document.getElementById('edit_rec_class');
                    if (clsInp) clsInp.value = parsed._recordClass;
                }
                if (parsed._table) {
                    var tblInp = document.getElementById('edit_rec_table');
                    if (tblInp) tblInp.value = parsed._table;
                }
            } catch (e) {
                if (statusEl) {
                    statusEl.innerText = 'INVALID JSON';
                    statusEl.style.color = '#ef4444';
                    statusEl.style.borderColor = 'rgba(239,68,68,0.3)';
                }
            }
        }

        function addRecordEditField(name, type, val, skipSync) {
            var tbody = document.getElementById('edit_rec_record_fields_tbody');
            if (!tbody) return;
            var fName = name || '';
            var fType = type || 'String';
            var fVal = (val !== undefined && val !== null) ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : '';

            var tr = document.createElement('tr');
            tr.style.borderBottom = '1px solid var(--j-border, rgba(255,255,255,0.08))';
            tr.style.background = 'transparent';
            tr.className = 'rec-edit-field-row';

            var types = ['String', 'Integer', 'Long', 'Double', 'Boolean', 'BigDecimal', 'LocalDate', 'LocalDateTime', 'Instant', 'UUID', 'List', 'Map', 'Object'];
            var optionsHtml = '';
            for (var i = 0; i < types.length; i++) {
                var sel = (types[i].toLowerCase() === fType.toLowerCase()) ? ' selected' : '';
                optionsHtml += '<option value="' + types[i] + '"' + sel + '>' + types[i] + '</option>';
            }

            tr.innerHTML = 
                '<td style="padding:6px 10px;">' +
                '  <input type="text" class="rec-edit-name" value="' + fName.replace(/"/g, '&quot;') + '" oninput="syncRecordEditPayload()" placeholder="nombre_campo" style="width:100%; padding:5px 8px; background:var(--j-bg-body, #0a0f1d); border:1px solid var(--j-border, rgba(255,255,255,0.1)); border-radius:4px; color:var(--j-text-primary, #f8fafc); font-size:12px; font-weight:600; box-sizing:border-box;" />' +
                '</td>' +
                '<td style="padding:6px 10px;">' +
                '  <select class="rec-edit-type" onchange="syncRecordEditPayload()" style="width:100%; padding:5px 8px; background:var(--j-bg-body, #0a0f1d); border:1px solid var(--j-border, rgba(255,255,255,0.1)); border-radius:4px; color:#38bdf8; font-size:12px; font-weight:600; box-sizing:border-box;">' +
                     optionsHtml +
                '  </select>' +
                '</td>' +
                '<td style="padding:6px 10px;">' +
                '  <input type="text" class="rec-edit-value" value="' + fVal.replace(/"/g, '&quot;') + '" oninput="syncRecordEditPayload()" placeholder="valor" style="width:100%; padding:5px 8px; background:var(--j-bg-body, #0a0f1d); border:1px solid var(--j-border, rgba(255,255,255,0.1)); border-radius:4px; color:#10b981; font-size:12px; font-weight:600; box-sizing:border-box;" />' +
                '</td>' +
                '<td style="padding:6px 10px; text-align:center;">' +
                '  <button type="button" onclick="this.closest(\\'tr\\').remove(); syncRecordEditPayload();" style="background:none; border:none; color:#ef4444; cursor:pointer; font-size:12px;" title="Eliminar Campo"><i class="fas fa-trash-alt"></i></button>' +
                '</td>';

            tbody.appendChild(tr);
            if (!skipSync) {
                syncRecordEditPayload();
            }
        }

        function syncRecordEditPayload() {
            var recClassInp = document.getElementById('edit_rec_class');
            var recClass = recClassInp ? recClassInp.value.trim() : 'com.jettra.model.Record';
            var recTableInp = document.getElementById('edit_rec_table');
            var recTable = recTableInp ? recTableInp.value.trim() : 'default';

            var legacyClass = document.getElementById('editRecClassInput');
            if (legacyClass) legacyClass.value = recClass;
            var legacyColl = document.getElementById('editRecCollInput');
            if (legacyColl) legacyColl.value = recTable;

            var schema = {};
            var components = {};

            var rows = document.querySelectorAll('#edit_rec_record_fields_tbody tr.rec-edit-field-row');
            rows.forEach(function(row) {
                var nameInp = row.querySelector('.rec-edit-name');
                var typeInp = row.querySelector('.rec-edit-type');
                var valInp = row.querySelector('.rec-edit-value');
                if (nameInp) {
                    var k = nameInp.value.trim();
                    if (k) {
                        var t = typeInp ? typeInp.value : 'String';
                        var v = valInp ? valInp.value : '';
                        schema[k] = t;
                        var parsedVal = v;
                        if (t === 'Integer' || t === 'Long') {
                            var n = parseInt(v, 10);
                            if (!isNaN(n)) parsedVal = n;
                        } else if (t === 'Double' || t === 'BigDecimal') {
                            var d = parseFloat(v);
                            if (!isNaN(d)) parsedVal = d;
                        } else if (t === 'Boolean') {
                            parsedVal = (v.toLowerCase() === 'true' || v === '1');
                        } else if (t === 'List' || t === 'Map' || t === 'Object') {
                            try { parsedVal = JSON.parse(v); } catch(e) { parsedVal = v; }
                        }
                        components[k] = parsedVal;
                    }
                }
            });

            var payload = {
                _recordClass: recClass,
                _table: recTable,
                _schema: schema,
                components: components
            };

            for (var prop in components) {
                if (components.hasOwnProperty(prop)) {
                    payload[prop] = components[prop];
                }
            }

            if (window.currentEditRecordParsed) {
                if (window.currentEditRecordParsed._id) payload._id = window.currentEditRecordParsed._id;
                if (window.currentEditRecordParsed.id) payload.id = window.currentEditRecordParsed.id;
                if (window.currentEditRecordParsed._version !== undefined) payload._version = window.currentEditRecordParsed._version;
                if (window.currentEditRecordParsed._timestamp !== undefined) payload._timestamp = window.currentEditRecordParsed._timestamp;
            }

            var jsonStr = JSON.stringify(payload, null, 2);
            var p1 = document.getElementById('edit_rec_payload');
            if (p1) p1.value = jsonStr;
            var p2 = document.getElementById('editRecPayloadInput');
            if (p2) p2.value = jsonStr;
            var p3 = document.getElementById('universalEditPayloadInput');
            if (p3) p3.value = jsonStr;
            var rawTa = document.getElementById('edit_rec_json_raw');
            if (rawTa && document.activeElement !== rawTa) rawTa.value = jsonStr;
        }

        function populateRecordFieldsFromPayload(formId, p, prettyPayload) {
            var pObj = p || {};
            if (typeof pObj === 'string') {
                try { pObj = JSON.parse(pObj); } catch(e) { pObj = {}; }
            }
            window.currentEditRecordParsed = pObj;

            var recClass = pObj._recordClass || pObj._class || 'com.jettra.model.Record';
            var classInp = document.getElementById('edit_rec_class');
            if (classInp) classInp.value = recClass;
            var legacyClass = document.getElementById('editRecClassInput');
            if (legacyClass) legacyClass.value = recClass;

            var table = pObj._table || '';
            var tableInp = document.getElementById('edit_rec_table');
            if (tableInp && table) tableInp.value = table;
            var legacyColl = document.getElementById('editRecCollInput');
            if (legacyColl && table) legacyColl.value = table;

            var ta = document.getElementById('edit_rec_payload');
            if (ta) ta.value = prettyPayload;
            var rawTa = document.getElementById('edit_rec_json_raw');
            if (rawTa) rawTa.value = prettyPayload;

            var tbody = document.getElementById('edit_rec_record_fields_tbody');
            if (!tbody) return;
            tbody.innerHTML = '';

            var schema = pObj._schema || {};
            if (typeof schema === 'string') {
                try { schema = JSON.parse(schema); } catch(e) { schema = {}; }
            }
            var comps = pObj.components || pObj._components || null;
            if (typeof comps === 'string') {
                try { comps = JSON.parse(comps); } catch(e) { comps = null; }
            }

            var sourceProps = (comps && typeof comps === 'object') ? comps : pObj;

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
            if (sourceProps && typeof sourceProps === 'object') {
                for (var ck in sourceProps) {
                    if (sourceProps.hasOwnProperty(ck) && !seen[ck]) {
                        seen[ck] = true;
                        fieldNames.push(ck);
                    }
                }
            }

            var addedCount = 0;
            for (var i = 0; i < fieldNames.length; i++) {
                var k = fieldNames[i];
                if (k === '_table' || k.startsWith('_record') || k === '_timestamp' || k === '_version' || k === '_schema' || k === 'components' || k === '_components' || k === '_class') continue;
                var val = sourceProps[k];
                var type = (schema && schema[k]) ? schema[k] : (typeof val === 'number' ? (Number.isInteger(val) ? 'Integer' : 'Double') : (typeof val === 'boolean' ? 'Boolean' : 'String'));
                var strVal = (val !== undefined && val !== null) ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : '';
                addRecordEditField(k, type, strVal, true);
                addedCount++;
            }

            if (addedCount === 0) {
                addRecordEditField('field1', 'String', '', true);
            }

            syncRecordEditPayload();
        }

        function openUniversalEditModal(engine, db, unit, id, payloadB64) {
            var eng = normalizeEditEngine(engine);

            var payload = safeDecodePayload(payloadB64);
            var parsed = null;
            try {
                if (typeof payload === 'string' && (payload.trim().startsWith('{') || payload.trim().startsWith('['))) {
                    parsed = JSON.parse(payload);
                }
            } catch(e) {}

            var pretty = parsed ? JSON.stringify(parsed, null, 2) : (payload || '{}');
            var p = (parsed && typeof parsed === 'object') ? parsed : {};

            // Dynamic auto-fetch if payload is empty but an ID is provided
            if (id && (!payload || payload === '{}' || payload.trim() === '')) {
                var fetchUrl = '/engines?action=get_record_payload&engine=' + encodeURIComponent(eng) +
                               '&target_db=' + encodeURIComponent(db || 'default') +
                               '&coll=' + encodeURIComponent(unit || 'default') +
                               '&id=' + encodeURIComponent(id);
                fetch(fetchUrl)
                    .then(function(res) { if (res.ok) return res.json(); return null; })
                    .then(function(data) {
                        if (data && data.payload && data.payload !== '{}') {
                            openUniversalEditModal(eng, db, unit, id, data.payloadB64 || data.payload);
                        }
                    })
                    .catch(function(e) {});
            }

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

            // Populate engine-specific inputs with structured values
            if (eng === 'RECORDS') {
                populateRecordFieldsFromPayload('edit_rec', p, pretty);
                var recClassInp = document.getElementById('editRecClassInput');
                if (recClassInp) recClassInp.value = p._recordClass || p._class || 'com.jettra.model.EmployeeProfileRecord';
                var recCollInp = document.getElementById('editRecCollInput');
                if (recCollInp) recCollInp.value = unit || p._table || 'default';
                var recTableInp = document.getElementById('edit_rec_table');
                if (recTableInp && (!recTableInp.value || recTableInp.value === 'employees')) {
                    recTableInp.value = p._table || unit || 'default';
                }
                var recPayloadInp = document.getElementById('editRecPayloadInput');
                if (recPayloadInp) recPayloadInp.value = pretty;
            } else if (eng === 'DOCUMENT') {
                var docColl = document.getElementById('editDocCollInput');
                if (docColl) docColl.value = unit || 'default';
                var docClass = document.getElementById('editDocClassInput');
                if (docClass) docClass.value = p._class || '';
                setJsonEditorVal('editDocPayload', pretty);
            } else if (eng === 'KEYVALUE') {
                var kvColl = document.getElementById('editKvCollInput');
                if (kvColl) kvColl.value = unit || 'default';
                var kvTtl = document.getElementById('editKvTtlInput');
                if (kvTtl) kvTtl.value = p.ttl || '';
                var kvVal = document.getElementById('editKvValueInput');
                if (kvVal) kvVal.value = (typeof payload === 'string' && payload.length > 0) ? payload : pretty;
            } else if (eng === 'VECTOR') {
                var vecColl = document.getElementById('editVecCollInput');
                if (vecColl) vecColl.value = unit || 'default';
                var vecMetric = document.getElementById('editVecMetricSelect');
                if (vecMetric) vecMetric.value = p.metric || p.distanceMetric || 'COSINE';
                var vecLabel = document.getElementById('editVecLabelInput');
                if (vecLabel) vecLabel.value = p.label || p.semanticClass || '';

                var vecCoords = '0.12, 0.45, 0.88, 0.31';
                if (Array.isArray(p.coordinates)) vecCoords = p.coordinates.join(', ');
                else if (Array.isArray(p.embedding)) vecCoords = p.embedding.join(', ');
                else if (Array.isArray(p.vector)) vecCoords = p.vector.join(', ');
                else if (p.coordinates || p.embedding || p.vector) vecCoords = String(p.coordinates || p.embedding || p.vector);
                var vecCoordsInp = document.getElementById('editVecCoordsInput');
                if (vecCoordsInp) vecCoordsInp.value = vecCoords;

                var metaPayload = p.metadata || p.meta || p;
                setJsonEditorVal('editVecMeta', metaPayload);
            } else if (eng === 'GRAPH') {
                var isEdge = (p.mode === 'edge' || p.fromId || p.from || p.toId || p.to);
                var rNode = document.getElementById('edit_graph_mode_node');
                var rEdge = document.getElementById('edit_graph_mode_edge');
                var fNode = document.getElementById('edit_graph_node_fields');
                var fEdge = document.getElementById('edit_graph_edge_fields');

                if (isEdge) {
                    if (rEdge) rEdge.checked = true;
                    if (rNode) rNode.checked = false;
                    if (fNode) fNode.style.display = 'none';
                    if (fEdge) fEdge.style.display = 'block';

                    var fromInp = document.getElementById('editGraphFromId');
                    if (fromInp) fromInp.value = p.fromId || p.from || '';
                    var edgeLbl = document.getElementById('editGraphEdgeLabel');
                    if (edgeLbl) edgeLbl.value = p.label || unit || 'RELATES_TO';
                    var toInp = document.getElementById('editGraphToId');
                    if (toInp) toInp.value = p.toId || p.to || '';

                    setJsonEditorVal('editGraphEdgeProps', p.properties || p.props || p);
                } else {
                    if (rNode) rNode.checked = true;
                    if (rEdge) rEdge.checked = false;
                    if (fNode) fNode.style.display = 'block';
                    if (fEdge) fEdge.style.display = 'none';

                    var nodeLbl = document.getElementById('editGraphNodeLabel');
                    if (nodeLbl) nodeLbl.value = p.label || unit || 'Person';

                    setJsonEditorVal('editGraphNodeProps', p.properties || p.props || p);
                }

                var graphColl = document.getElementById('editGraphCollInput');
                if (graphColl) graphColl.value = p.label || unit || 'Vertex';
            } else if (eng === 'TIMESERIES') {
                var tsColl = document.getElementById('editTsCollInput');
                if (tsColl) tsColl.value = p.metric || unit || 'server_temperature';
                var tsTs = document.getElementById('editTsTimestampInput');
                if (tsTs) tsTs.value = p.timestamp || id;
                var tsVal = document.getElementById('editTsValueInput');
                if (tsVal) tsVal.value = (p.value !== undefined) ? p.value : '25.4';
                var tsUnit = document.getElementById('editTsUnitInput');
                if (tsUnit) tsUnit.value = p.unit || '°C';

                var tagsPayload = p.tags || p.dimensions || p;
                setJsonEditorVal('editTsTags', tagsPayload);
            } else if (eng === 'COLUMN') {
                var colColl = document.getElementById('editColCollInput');
                if (colColl) colColl.value = p._family || unit || 'user_analytics';
                var colQual = document.getElementById('editColQualifierInput');
                if (colQual) colQual.value = p.qualifier || p.col_qualifier || 'profile:full';

                var colsPayload = p.columns || p.col_data || p;
                setJsonEditorVal('editColData', colsPayload);
            } else if (eng === 'GEOSPATIAL') {
                var geoColl = document.getElementById('editGeoCollInput');
                if (geoColl) geoColl.value = p._layer || unit || 'facilities_layer';
                var geoName = document.getElementById('editGeoNameInput');
                if (geoName) geoName.value = p.name || id;
                var geoType = document.getElementById('editGeoTypeSelect');
                if (geoType) geoType.value = p.geomType || p.type || 'Point';
                var geoLat = document.getElementById('editGeoLatInput');
                if (geoLat) geoLat.value = (p.latitude !== undefined ? p.latitude : (p.lat !== undefined ? p.lat : '8.9824'));
                var geoLon = document.getElementById('editGeoLonInput');
                if (geoLon) geoLon.value = (p.longitude !== undefined ? p.longitude : (p.lon !== undefined ? p.lon : '-79.5199'));

                var geoProps = p.properties || p.meta || p;
                setJsonEditorVal('editGeoMeta', geoProps);
            } else if (eng === 'OBJECT') {
                var objColl = document.getElementById('editObjCollInput');
                if (objColl) objColl.value = p.bucket || unit || 'media_bucket';
                var objClass = document.getElementById('editObjClassInput');
                if (objClass) objClass.value = p.className || p.obj_class || 'com.jettra.storage.MediaFile';
                var objMime = document.getElementById('editObjMimeInput');
                if (objMime) objMime.value = p.mimeType || p.obj_mime || 'application/json';

                var objContent = p.content || payload || pretty;
                setJsonEditorVal('editObjPayload', objContent);
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
            var eng = normalizeEditEngine(engineKey);
            var engineInput = document.getElementById('universalEditEngineInput');
            if (engineInput) engineInput.value = eng;

            // Show ONLY the active engine's section, hide and disable all others
            var sections = document.querySelectorAll('.universal-edit-engine-section');
            sections.forEach(function(sec) {
                var secEng = normalizeEditEngine(sec.getAttribute('data-engine'));
                var isMatch = (secEng === eng);
                sec.style.display = isMatch ? 'block' : 'none';
                var controls = sec.querySelectorAll('input, select, textarea');
                controls.forEach(function(ctrl) { ctrl.disabled = !isMatch; });
            });

            // Show ONLY the active engine's pill tab in edit mode
            document.querySelectorAll('[id^="edit_engine_tab_btn_"]').forEach(function(pill) {
                var pillEng = normalizeEditEngine(pill.getAttribute('data-engine'));
                var color = pill.getAttribute('data-color') || '#fbbf24';
                var label = pill.querySelector('span');
                var isSelected = (pillEng === eng);
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
                var displayTitle = (eng === 'RECORDS') ? 'RECORD' : eng;
                if (labelSpan) labelSpan.textContent = 'Guardar Cambios en ' + displayTitle + ' (v+1)';
            }
        }

        window.submitUniversalEditRecord = submitUniversalEditRecord;
        window.universalRecordEditor = openUniversalEditModal;
        window.openUniversalEditModal = openUniversalEditModal;
        window.switchEditEngine = switchEditEngine;
        window.populateRecordFieldsFromPayload = populateRecordFieldsFromPayload;
        window.safeDecodePayload = safeDecodePayload;
        window.setJsonEditorVal = setJsonEditorVal;
        window.toggleEditRecJsonMode = toggleEditRecJsonMode;
        window.syncRecordJsonToFields = syncRecordJsonToFields;
        </script>
        """);
    }
}
