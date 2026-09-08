package com.jettra.store.engine.web;

import com.jettra.store.engine.insertion.EngineType;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive Multi-Model Record Inspection Dialog (VER) for JettraDB in /engines under
 * the Multi-Model Storage Hierarchy Explorer section.
 *
 * Built with native JettraFlux components matching the design, aesthetic, and
 * polymorphic behavior of EngineRecordInsertionDialog:
 * - JettraFluxModal
 * - 9-Engine dynamic model indicator tabs bar
 * - Common metadata summary bar (Database, Unit, Monospace Record ID, Version badge, Jref badge)
 * - Model-adaptive inspection views tailored for DOCUMENT, KEYVALUE, VECTOR, GRAPH,
 *   TIMESERIES, COLUMN, GEOSPATIAL, OBJECT, and RECORDS (Java 25 Immutable Schemas).
 * - Integrated reactive reference resolution ($jref), full JSON payload viewer, and action shortcuts.
 */
public final class EngineRecordInspectDialog {

    public static final String MODAL_ID = "inspectRecordModal";

    private EngineRecordInspectDialog() {}

    public static Widget build() {
        // 1. Engine Selector / Model Tabs Bar (9 Engines)
        Widget engineSelectorBar = buildEngineSelectorBar("DOCUMENT");

        // 2. Common Metadata Info Row
        Widget commonRow = Div.of(
            Div.of(
                Span.of("Engine: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("DOCUMENT").id("inspectRecordEngineDisplay")
                    .modifier(new Modifier().cssClass("store-badge badge-active")
                        .style("font-size:10.5px; font-weight:700; margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Database: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("default").id("inspectRecordDbDisplay")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-primary); margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Unit / Coll: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("default").id("inspectRecordCollDisplay")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-primary); margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Record ID: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("").id("inspectRecordIdDisplay")
                    .modifier(new Modifier().style("color:#4ade80; font-family:monospace; font-weight:700; font-size:12px; margin-right:8px;")),
                Icon.of("fas fa-fingerprint").modifier(new Modifier().style("color:#4ade80; font-size:11px; margin-right:12px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Div.of(
                Span.of("Version: ").modifier(new Modifier().style("font-size:11px; font-weight:700; color:var(--j-text-muted);")),
                Span.of("v1").id("inspectRecordVersionDisplay")
                    .modifier(new Modifier().cssClass("store-badge badge-records")
                        .style("font-size:10px; font-weight:700; margin-right:8px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center;")),

            Span.of("0 Ref(s)").id("inspectReferencesCountBadge")
                .modifier(new Modifier().cssClass("store-badge badge-active")
                    .style("font-size:10.5px; display:none; margin-left:auto;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; flex-wrap:wrap; gap:6px; background:var(--j-bg-body); padding:9px 14px; border-radius:8px; border:1px solid var(--j-border); margin-bottom:12px;"));

        // 3. Model-Adaptive Inspection Section (dynamic per engine)
        Widget adaptiveModelView = Div.of()
            .id("inspectAdaptiveModelView")
            .modifier(new Modifier().style("margin-bottom:12px;"));

        // 4. Jref Auto-Resolve Toolbar
        Widget jrefToolbar = Div.of(
            Label.of(
                RawHtml.of("<input type=\"checkbox\" id=\"chkInspectResolveRefs\" checked onchange=\"toggleInspectReferenceResolution(this.checked)\" style=\"accent-color:#38bdf8; width:14px; height:14px; cursor:pointer; margin-right:6px;\" />"),
                Icon.of("fas fa-link").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:11.5px;")),
                Span.of("Cargar Objetos Referenciados (Auto-Resolve Jref)").modifier(new Modifier().style("color:#38bdf8; font-weight:600; font-size:11.5px;"))
            ).modifier(new Modifier().style("display:inline-flex; align-items:center; cursor:pointer;")),
            Span.of("Resolución reactiva de punteros de memoria y nodos de almacenamiento distribuidos")
                .modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-muted);"))
        ).modifier(new Modifier().style("display:flex; align-items:center; justify-content:space-between; background:rgba(56,189,248,0.08); border:1px solid rgba(56,189,248,0.25); padding:6px 12px; border-radius:6px; margin-bottom:10px;"));

        // 5. Full Payload TextArea Display
        Widget payloadLabel = Div.of(
            Label.of("Representación Completa del Registro (JSON Payload):")
                .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin:0;")),
            Span.of("Estructura serializada inmutable")
                .modifier(new Modifier().style("font-size:10.5px; color:var(--j-text-muted);"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;"));

        Widget payloadArea = TextArea.create()
            .name("inspect_payload")
            .rows(9)
            .id("inspectRecordPayloadDisplay")
            .modifier(new Modifier()
                .attribute("readonly", "readonly")
                .style("width:100%; box-sizing:border-box; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#38bdf8; font-family:monospace; font-size:11.5px; padding:10px; line-height:1.4; resize:vertical;"));

        // 6. Referenced Objects Container
        Widget refObjectsTitle = Div.of(
            Icon.of("fas fa-project-diagram").modifier(new Modifier().style("color:#38bdf8; margin-right:6px; font-size:12px;")),
            Span.of("Objetos Referenciados Detectados (Jref Operator):").modifier(new Modifier().style("color:var(--j-text-secondary); font-size:11.5px; font-weight:700;"))
        ).modifier(new Modifier().style("display:flex; align-items:center; margin-bottom:8px;"));

        Widget refObjectsList = Div.of()
            .id("inspectRecordReferencesList")
            .modifier(new Modifier().style("display:flex; flex-direction:column; gap:6px;"));

        Widget refObjectsContainer = Div.of(refObjectsTitle, refObjectsList)
            .id("inspectRecordReferencesContainer")
            .modifier(new Modifier().style("display:none; margin-top:10px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:8px; padding:12px; max-height:180px; overflow-y:auto;"));

        // 7. Footer Action Buttons
        Widget footer = Div.of(
            Button.of(Icon.of("fas fa-copy"), Text.of(" Copiar Payload"))
                .id("btnCopyInspect")
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "copyInspectRecordPayload()").cssClass("btn-action btn-secondary").style("font-size:11.5px; padding:6px 13px; margin-right:6px; cursor:pointer;")),
            Button.of(Icon.of("fas fa-edit"), Text.of(" Editar"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "editFromInspectModal()").cssClass("btn-action btn-primary").style("font-size:11.5px; padding:6px 14px; background:#fbbf24; border-color:#fbbf24; color:#0f172a; font-weight:700; margin-right:6px; cursor:pointer;")),
            Button.of(Icon.of("fas fa-history"), Text.of(" Historial"))
                .modifier(new Modifier().attribute("type", "button").attribute("onclick", "historyFromInspectModal()").cssClass("btn-action btn-secondary").style("font-size:11.5px; padding:6px 13px; color:#c084fc; border-color:#c084fc; margin-right:6px; cursor:pointer;")),
            Button.of(Icon.of("fas fa-times"), Text.of(" Cerrar"))
                .modifier(new Modifier().attribute("type", "button")
                    .attribute("onclick", "if (window.JettraFluxModal) JettraFluxModal.close('inspectRecordModal'); else if (typeof hideModal==='function') hideModal('inspectRecordModal');")
                    .cssClass("btn-action btn-secondary").style("font-size:11.5px; padding:6px 13px; cursor:pointer;"))
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; width:100%; gap:6px; margin-top:10px; padding-top:10px; border-top:1px solid var(--j-border);"));

        // 8. Assemble Modal using JettraFluxModal
        JettraFluxModal modal = JettraFluxModal.of(MODAL_ID)
                .title("Inspeccionar Registro Multi-Modelo (VER)")
                .subtitle("Visor adaptativo estructurado según el modelo de datos y resolución de referencias $jref")
                .icon("fas fa-search-plus")
                .badge("9 MOTORES", "#38bdf8")
                .maxWidth("880px")
                .maxHeight("92vh")
                .addBody(engineSelectorBar)
                .addBody(commonRow)
                .addBody(adaptiveModelView)
                .addBody(jrefToolbar)
                .addBody(payloadLabel)
                .addBody(payloadArea)
                .addBody(refObjectsContainer)
                .footer(footer);

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
                .id("inspect_engine_tab_btn_" + eng.key())
                .modifier(new Modifier()
                    .attribute("data-engine", eng.key())
                    .attribute("data-color", eng.color())
                    .attribute("data-label", eng.displayName())
                    .attribute("onclick", "switchInspectEngine('" + eng.key() + "')")
                    .style("display:inline-flex; align-items:center; padding:6px 11px; border-radius:20px; cursor:pointer; "
                         + "background:" + bgStyle + "; border:" + borderStyle + "; transition:all 0.15s ease; user-select:none; white-space:nowrap;"));

            pills.add(pill);
        }

        return Div.of(
            Div.of(
                Span.of("MODELO DE ALMACENAMIENTO (ADAPTIVE INSPECT VIEW):")
                    .modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); letter-spacing:0.8px; text-transform:uppercase; margin-bottom:6px; display:block;")),
                Div.of(pills.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display:flex; gap:6px; overflow-x:auto; padding-bottom:4px;"))
            ).modifier(new Modifier().style("background:var(--j-bg-subsurface); padding:10px 14px; border-radius:8px; border:1px solid var(--j-border); margin-bottom:12px;"))
        );
    }

    private static Widget buildClientScript() {
        return RawHtml.of("""
        <script>
        function switchInspectEngine(engineKey) {
            var raw = (engineKey || 'DOCUMENT').toUpperCase();
            var eng = (raw === 'RECORD') ? 'RECORDS' : raw;

            // Show ONLY the active engine pill, hide all others
            document.querySelectorAll('[id^="inspect_engine_tab_btn_"]').forEach(function(pill) {
                var pillEng = pill.getAttribute('data-engine');
                var color = pill.getAttribute('data-color') || '#38bdf8';
                var label = pill.querySelector('span');
                var isSelected = (pillEng === eng || (pillEng === 'RECORDS' && raw === 'RECORD'));
                if (isSelected) {
                    pill.style.display = 'inline-flex';
                    pill.style.border = '2px solid ' + color;
                    pill.style.background = 'rgba(255,255,255,0.08)';
                    if (label) label.style.color = color;
                } else {
                    pill.style.display = 'none';
                }
            });

            // Re-render adaptive model view if record is present
            if (window.currentInspectRecord) {
                renderAdaptiveInspectModelView(eng, window.currentInspectRecord.db, window.currentInspectRecord.unit, window.currentInspectRecord.id, window.currentInspectRecord.parsed, window.currentInspectRecord.rawPayload);
            }
        }

        function renderAdaptiveInspectModelView(engine, db, unit, id, parsed, rawPayload) {
            var container = document.getElementById('inspectAdaptiveModelView');
            if (!container) return;
            var p = parsed || {};
            var raw = (engine || 'DOCUMENT').toUpperCase();
            var eng = (raw === 'RECORD') ? 'RECORDS' : raw;

            var html = '';
            switch(eng) {
                case 'RECORDS':
                    var recClass = p._recordClass || p._class || 'Java 25 Record';
                    var schema = p._schema || {};
                    var comps = p.components || p._components || p;
                    var tableUnit = unit || p._table || 'default';

                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(244,63,94,0.35); border-radius:8px; padding:12px 16px; margin-bottom:12px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:8px;">';
                    html += '    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">';
                    html += '      <span style="font-size:11.5px; font-weight:700; color:#f43f5e;"><i class="fas fa-microchip"></i> Record Class:</span>';
                    html += '      <code style="background:rgba(244,63,94,0.12); color:#fb7185; padding:2px 8px; border-radius:4px; font-size:12px; font-weight:700;">' + recClass + '</code>';
                    html += '      <span style="font-size:11px; font-weight:600; color:var(--j-text-muted); margin-left:8px;"><i class="fas fa-table" style="color:#10b981;"></i> Tabla:</span>';
                    html += '      <span style="color:#10b981; font-weight:700; font-size:11.5px;">' + tableUnit + '</span>';
                    html += '    </div>';
                    html += '    <span style="font-size:9.5px; font-weight:800; background:rgba(16,185,129,0.2); color:#10b981; border:1px solid rgba(16,185,129,0.4); padding:2px 8px; border-radius:12px;"><i class="fas fa-shield-halved"></i> IMMUTABLE TYPED SCHEMA</span>';
                    html += '  </div>';

                    // Structured Table of Record Fields, Schema Types, and Component Values
                    html += '  <div style="overflow-x:auto; margin-top:8px;">';
                    html += '    <table style="width:100%; border-collapse:collapse; font-size:11.5px;">';
                    html += '      <thead>';
                    html += '        <tr style="background:var(--j-bg-body); border-bottom:1px solid var(--j-border); text-align:left; color:var(--j-text-secondary);">';
                    html += '          <th style="padding:7px 10px; width:30%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-cube" style="color:#f43f5e; margin-right:5px;"></i> Nombre de Campo / Columna</th>';
                    html += '          <th style="padding:7px 10px; width:32%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-tag" style="color:#38bdf8; margin-right:5px;"></i> Tipo de Dato (_schema)</th>';
                    html += '          <th style="padding:7px 10px; width:38%; font-weight:700; text-transform:uppercase; letter-spacing:0.5px;"><i class="fas fa-database" style="color:#4ade80; margin-right:5px;"></i> Valor del Componente (components)</th>';
                    html += '        </tr>';
                    html += '      </thead>';
                    html += '      <tbody>';

                    var fieldNames = [];
                    var seen = {};
                    if (schema && typeof schema === 'object') {
                        for (var sk in schema) { if (schema.hasOwnProperty(sk) && !seen[sk]) { seen[sk] = true; fieldNames.push(sk); } }
                    }
                    if (comps && typeof comps === 'object') {
                        for (var ck in comps) { if (comps.hasOwnProperty(ck) && !seen[ck]) { seen[ck] = true; fieldNames.push(ck); } }
                    }

                    var renderedRowCount = 0;
                    for (var fi = 0; fi < fieldNames.length; fi++) {
                        var fn = fieldNames[fi];
                        if (fn === '_table' || fn.startsWith('_record') || fn === '_timestamp' || fn === '_version' || fn === '_schema' || fn === 'components' || fn === '_components' || fn === '_class') continue;
                        renderedRowCount++;
                        var fType = (schema && schema[fn]) ? schema[fn] : 'String';
                        var fVal = (comps && comps[fn] !== undefined) ? comps[fn] : '';
                        var fValStr = (typeof fVal === 'object' && fVal !== null) ? JSON.stringify(fVal) : String(fVal);

                        var typeBadgeColor = '#38bdf8';
                        var typeBadgeBg = 'rgba(56,189,248,0.12)';
                        var typeBadgeBorder = 'rgba(56,189,248,0.3)';
                        if (['LocalDate','LocalTime','LocalDateTime','Instant','ZonedDateTime','OffsetDateTime','Date'].indexOf(fType) !== -1) {
                            typeBadgeColor = '#10b981';
                            typeBadgeBg = 'rgba(16,185,129,0.12)';
                            typeBadgeBorder = 'rgba(16,185,129,0.3)';
                        } else if (fType.indexOf('<') !== -1 || fType.indexOf('[]') !== -1) {
                            typeBadgeColor = '#06b6d4';
                            typeBadgeBg = 'rgba(6,182,212,0.12)';
                            typeBadgeBorder = 'rgba(6,182,212,0.3)';
                        } else if (['Enum','Object','Record','Pais','Persona'].indexOf(fType) !== -1) {
                            typeBadgeColor = '#c084fc';
                            typeBadgeBg = 'rgba(168,85,247,0.12)';
                            typeBadgeBorder = 'rgba(168,85,247,0.3)';
                        }

                        html += '        <tr style="border-bottom:1px solid rgba(255,255,255,0.05); background: ' + (renderedRowCount % 2 === 0 ? 'rgba(255,255,255,0.02)' : 'transparent') + ';">';
                        html += '          <td style="padding:7px 10px; font-weight:700; color:var(--j-text-primary); font-family:monospace;">' + fn + '</td>';
                        html += '          <td style="padding:7px 10px;"><span style="font-family:monospace; font-size:10.5px; font-weight:700; color:' + typeBadgeColor + '; background:' + typeBadgeBg + '; border:1px solid ' + typeBadgeBorder + '; padding:2px 7px; border-radius:4px;">' + fType + '</span></td>';
                        html += '          <td style="padding:7px 10px; color:#4ade80; font-family:monospace; word-break:break-all; font-weight:600;">' + fValStr + '</td>';
                        html += '        </tr>';
                    }

                    if (renderedRowCount === 0) {
                        html += '        <tr><td colspan="3" style="padding:10px; text-align:center; color:var(--j-text-muted);">Sin componentes registrados</td></tr>';
                    }

                    html += '      </tbody>';
                    html += '    </table>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'VECTOR':
                    var coords = p.coordinates || p.embedding || p.vector || [];
                    var dimCount = Array.isArray(coords) ? coords.length : 0;
                    var preview = Array.isArray(coords) ? coords.slice(0, 8).join(', ') + (coords.length > 8 ? ' ...' : '') : String(coords);

                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(139,92,246,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#8b5cf6;"><i class="fas fa-brain"></i> Vector Embedding & Cosine Space</span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(139,92,246,0.2); color:#c084fc; border:1px solid rgba(139,92,246,0.4); padding:2px 8px; border-radius:12px;">' + dimCount + ' DIMENSIONES</span>';
                    html += '  </div>';
                    html += '  <div style="background:var(--j-bg-body); border:1px solid var(--j-border); padding:8px 12px; border-radius:6px; font-family:monospace; font-size:11.5px; color:#c084fc; word-break:break-all;">';
                    html += '    [' + preview + ']';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'TIMESERIES':
                    var metric = p.metric || unit || 'telemetry';
                    var val = p.value !== undefined ? p.value : '--';
                    var unitStr = p.unit || '';
                    var ts = p.timestamp || p.time || '--';

                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(6,182,212,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#06b6d4;"><i class="fas fa-chart-line"></i> Serie Temporal / Métrica: <code>' + metric + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(6,182,212,0.2); color:#22d3ee; border:1px solid rgba(6,182,212,0.4); padding:2px 8px; border-radius:12px;">TELEMETRÍA</span>';
                    html += '  </div>';
                    html += '  <div style="display:flex; gap:12px; flex-wrap:wrap;">';
                    html += '    <div style="background:var(--j-bg-body); border:1px solid var(--j-border); padding:6px 12px; border-radius:6px; flex:1;">';
                    html += '      <span style="font-size:10px; color:var(--j-text-muted); display:block;">Valor Registrado:</span>';
                    html += '      <span style="font-size:14px; font-weight:700; color:#22d3ee;">' + val + ' <small style="font-size:10px; color:var(--j-text-muted);">' + unitStr + '</small></span>';
                    html += '    </div>';
                    html += '    <div style="background:var(--j-bg-body); border:1px solid var(--j-border); padding:6px 12px; border-radius:6px; flex:1.5;">';
                    html += '      <span style="font-size:10px; color:var(--j-text-muted); display:block;">Timestamp (Epoch / Fecha):</span>';
                    html += '      <span style="font-size:12px; font-weight:600; color:var(--j-text-primary); font-family:monospace;">' + ts + '</span>';
                    html += '    </div>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'GEOSPATIAL':
                    var lat = p.lat !== undefined ? p.lat : (p.latitude !== undefined ? p.latitude : '--');
                    var lon = p.lon !== undefined ? p.lon : (p.longitude !== undefined ? p.longitude : '--');
                    var place = p.name || id;

                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(20,184,166,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#14b8a6;"><i class="fas fa-map-marked-alt"></i> GIS Feature: <code>' + place + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(20,184,166,0.2); color:#2dd4bf; border:1px solid rgba(20,184,166,0.4); padding:2px 8px; border-radius:12px;">WGS84 COORDS</span>';
                    html += '  </div>';
                    html += '  <div style="display:flex; gap:12px;">';
                    html += '    <div style="background:var(--j-bg-body); border:1px solid var(--j-border); padding:6px 12px; border-radius:6px; flex:1;">';
                    html += '      <span style="font-size:10px; color:var(--j-text-muted); display:block;">Latitud:</span>';
                    html += '      <span style="font-size:13px; font-weight:700; color:#2dd4bf; font-family:monospace;">' + lat + '</span>';
                    html += '    </div>';
                    html += '    <div style="background:var(--j-bg-body); border:1px solid var(--j-border); padding:6px 12px; border-radius:6px; flex:1;">';
                    html += '      <span style="font-size:10px; color:var(--j-text-muted); display:block;">Longitud:</span>';
                    html += '      <span style="font-size:13px; font-weight:700; color:#2dd4bf; font-family:monospace;">' + lon + '</span>';
                    html += '    </div>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'GRAPH':
                    var label = p.label || unit || 'Vertex';
                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(236,72,153,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#ec4899;"><i class="fas fa-project-diagram"></i> Graph Node Label: <code>' + label + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(236,72,153,0.2); color:#f472b6; border:1px solid rgba(236,72,153,0.4); padding:2px 8px; border-radius:12px;">PROPERTY GRAPH</span>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'COLUMN':
                    var family = p._family || unit || 'analytics';
                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(249,115,22,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#f97316;"><i class="fas fa-columns"></i> Column Family: <code>' + family + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(249,115,22,0.2); color:#fb923c; border:1px solid rgba(249,115,22,0.4); padding:2px 8px; border-radius:12px;">WIDE-COLUMN</span>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'OBJECT':
                    var bucket = p.bucket || unit || 'media_bucket';
                    var mime = p.mimeType || 'application/octet-stream';
                    var size = p.sizeBytes || (rawPayload ? rawPayload.length : 0);
                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(168,85,247,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#a855f7;"><i class="fas fa-cubes"></i> Bucket: <code>' + bucket + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(168,85,247,0.2); color:#c084fc; border:1px solid rgba(168,85,247,0.4); padding:2px 8px; border-radius:12px;">' + mime + ' (' + size + ' bytes)</span>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'KEYVALUE':
                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(16,185,129,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#10b981;"><i class="fas fa-key"></i> Key-Value Namespace: <code>' + (unit || 'default') + '</code></span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(16,185,129,0.2); color:#34d399; border:1px solid rgba(16,185,129,0.4); padding:2px 8px; border-radius:12px;">FAST KV CACHE</span>';
                    html += '  </div>';
                    html += '</div>';
                    break;

                case 'DOCUMENT':
                default:
                    var docCls = p._class || '';
                    var fieldCount = (typeof p === 'object' && p !== null) ? Object.keys(p).length : 0;
                    html += '<div style="background:var(--j-bg-subsurface); border:1px solid rgba(56,189,248,0.35); border-radius:8px; padding:12px 16px;">';
                    html += '  <div style="display:flex; justify-content:space-between; align-items:center;">';
                    html += '    <span style="font-size:11.5px; font-weight:700; color:#38bdf8;"><i class="fas fa-file-code"></i> Colección: <code>' + (unit || 'default') + '</code>' + (docCls ? ' (' + docCls + ')' : '') + '</span>';
                    html += '    <span style="font-size:10px; font-weight:800; background:rgba(56,189,248,0.2); color:#38bdf8; border:1px solid rgba(56,189,248,0.4); padding:2px 8px; border-radius:12px;">' + fieldCount + ' CAMPOS JSON</span>';
                    html += '  </div>';
                    html += '</div>';
                    break;
            }

            container.innerHTML = html;
        }

        window.switchInspectEngine = switchInspectEngine;
        window.renderAdaptiveInspectModelView = renderAdaptiveInspectModelView;
        </script>
        """);
    }
}
