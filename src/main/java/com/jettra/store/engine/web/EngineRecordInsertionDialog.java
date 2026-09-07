package com.jettra.store.engine.web;

import com.jettra.store.engine.insertion.EngineInsertionFactory;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineType;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adaptive Multi-Model Record Insertion Dialog for JettraDB in /engines.
 * Built exclusively using reactive JettraFlux components:
 * - JettraFluxModal
 * - JettraFluxDynamicForm
 * - JettraFluxButton
 * - JettraFluxJsonEditor
 * - JettraFluxNotification
 *
 * Dynamically switches between the 9 multi-model storage engine schemas at runtime,
 * validates payloads, and executes asynchronous inserts with Virtual Threads.
 */
public final class EngineRecordInsertionDialog {

    public static final String MODAL_ID = "adaptiveRecordInsertModal";
    public static final String FORM_ID = "adaptiveRecordInsertForm";
    public static final String NOTIFICATION_ID = "adaptiveRecordInsertNotification";

    private EngineRecordInsertionDialog() {}

    public static Widget build(String actionUrl, String defaultEngine, String defaultDb, String defaultColl) {
        String activeEngine = defaultEngine != null ? defaultEngine.toUpperCase() : "DOCUMENT";
        String activeDb = defaultDb != null ? defaultDb : "customers_db";
        String activeColl = defaultColl != null ? defaultColl : "default";

        // 1. Notification feedback banner inside modal
        Widget notification = JettraFluxNotification.of(NOTIFICATION_ID)
                .title("Resultado de Inserción")
                .message("")
                .type(JettraFluxNotification.Type.INFO)
                .visible(false);

        // 2. Engine Selector Tabs Bar (9 Engines)
        Widget engineSelectorBar = buildEngineSelectorBar(activeEngine);

        // 3. Dynamic Form with common fields and 9 polymorphic sections
        JettraFluxDynamicForm form = JettraFluxDynamicForm.of(FORM_ID, actionUrl)
                .onSubmit("submitAdaptiveRecordInsert()")
                .activeSection(activeEngine);

        // Common Fields
        form.addField(InputHidden.of("action", "insert_object_ajax"));
        form.addField(InputHidden.of("is_ajax", "true"));
        form.addField(InputHidden.of("engine", activeEngine).id("adaptive_insert_engine_input"));

        Widget commonRow = Div.of(
            Div.of(
                Label.of("Base de Datos Destino (Target Database):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("adaptive_insert_target_db").binding("target_db").value(activeDb)
                    .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px; font-weight:600;"))
            ).modifier(new Modifier().style("flex:1.2;")),

            Div.of(
                Label.of("Modo de Generación de ID:")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                JettraFluxSelect.of("adaptive_insert_id_mode", "id_gen_mode")
                    .onChange("onAdaptiveIdModeChange(this)")
                    .addOption("UUID", "UUID v4 (Automático)", true)
                    .addOption("SNOWFLAKE", "Snowflake (Temporal / Distribuido)")
                    .addOption("SEQUENTIAL", "Secuencial (Entero Auto-incremental)")
                    .addOption("MANUAL", "Manual (Definir ID específico)")
            ).modifier(new Modifier().style("flex:1.2;")),

            Div.of(
                Label.of("Identificador (ID / Primary Key):")
                    .modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                TextField.of().id("adaptive_insert_target_id").binding("target_id").value("")
                    .modifier(new Modifier().attribute("placeholder", "(Auto-generado si está vacío)")
                        .style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#4ade80; font-family:monospace; font-weight:600; font-size:12.5px;"))
            ).modifier(new Modifier().style("flex:1.6;"))
        ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:10px; background:var(--j-bg-body); padding:10px 14px; border-radius:8px; border:1px solid var(--j-border); flex-wrap:wrap;"));

        form.addField(commonRow);

        // Add 9 polymorphic engine sections
        for (EngineType engineType : EngineType.all()) {
            EngineRecordInsertionStrategy<?> strategy = EngineInsertionFactory.getStrategy(engineType);
            Widget engineFields = strategy.buildEngineFormFields(activeDb, activeColl);
            form.addSection(engineType.key(), engineFields);
        }

        // 4. Modal Footer Actions
        Widget sampleButton = JettraFluxButton.of("Cargar Plantilla de Ejemplo", "fas fa-magic")
                .variant(JettraFluxButton.Variant.SECONDARY)
                .size(JettraFluxButton.Size.SM)
                .onClickJs("loadSampleForActiveEngine()");

        Widget cancelButton = JettraFluxButton.of("Cancelar")
                .variant(JettraFluxButton.Variant.GHOST)
                .size(JettraFluxButton.Size.SM)
                .onClickJs("JettraFluxModal.close('" + MODAL_ID + "')");

        Widget submitButton = JettraFluxButton.of("Insertar Registro", "fas fa-plus-circle")
                .id("btnAdaptiveSubmitInsert")
                .form(FORM_ID)
                .variant(JettraFluxButton.Variant.PRIMARY)
                .size(JettraFluxButton.Size.MD)
                .onClickJs("submitAdaptiveRecordInsert()")
                .submit();

        Widget footer = Div.of(
            Div.of(sampleButton).modifier(new Modifier().style("margin-right:auto;")),
            cancelButton,
            submitButton
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; align-items:center; gap:8px; width:100%;"));

        // 5. Build Modal using JettraFluxModal
        JettraFluxModal modal = JettraFluxModal.of(MODAL_ID)
                .title("Insertar Registro Multi-Modelo")
                .subtitle("Formulario adaptativo con validación polimórfica para los 9 motores heterogéneos de JettraDB")
                .icon("fas fa-database")
                .badge("9 MOTORES", "#38bdf8")
                .maxWidth("880px")
                .maxHeight("92vh")
                .addBody(notification)
                .addBody(engineSelectorBar)
                .addBody(form)
                .footer(footer);

        // 6. Return combined Widget with reactive client script
        return Div.of(
            modal,
            buildClientScript(actionUrl)
        );
    }

    private static Widget buildEngineSelectorBar(String initialEngine) {
        List<Widget> pills = new ArrayList<>();
        for (EngineType eng : EngineType.all()) {
            boolean isActive = eng.key().equalsIgnoreCase(initialEngine);
            String borderStyle = isActive ? "2px solid " + eng.color() : "1px solid var(--j-border)";
            String bgStyle = isActive ? "rgba(255,255,255,0.08)" : "var(--j-bg-body)";
            String fontColor = isActive ? eng.color() : "var(--j-text-secondary)";

            Widget pill = Div.of(
                Icon.of(eng.icon()).modifier(new Modifier().style("color:" + eng.color() + "; font-size:12px; margin-right:6px;")),
                Span.of(eng.displayName()).modifier(new Modifier().style("font-size:11.5px; font-weight:700; color:" + fontColor + ";"))
            ).id("engine_tab_btn_" + eng.key())
             .modifier(new Modifier()
                .attribute("data-engine", eng.key())
                .attribute("data-color", eng.color())
                .attribute("data-label", eng.displayName())
                .attribute("onclick", "switchInsertEngine('" + eng.key() + "')")
                .style("display:inline-flex; align-items:center; padding:6px 11px; border-radius:20px; cursor:pointer; "
                     + "background:" + bgStyle + "; border:" + borderStyle + "; transition:all 0.15s ease; user-select:none; white-space:nowrap;"));

            pills.add(pill);
        }

        return Div.of(
            Div.of(
                Span.of("SELECCIONE EL MOTOR DE ALMACENAMIENTO:")
                    .modifier(new Modifier().style("font-size:10px; font-weight:700; color:var(--j-text-muted); letter-spacing:0.8px; text-transform:uppercase; margin-bottom:6px; display:block;")),
                Div.of(pills.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display:flex; gap:6px; overflow-x:auto; padding-bottom:4px;"))
            ).modifier(new Modifier().style("background:var(--j-bg-subsurface); padding:10px 14px; border-radius:8px; border:1px solid var(--j-border); margin-bottom:10px;"))
        );
    }

    private static Widget buildClientScript(String actionUrl) {
        StringBuilder sb = new StringBuilder();

        // Dictionary of sample values for all 9 engines
        sb.append("window.JettraAdaptiveSamples = {\n");
        for (EngineType eng : EngineType.all()) {
            EngineRecordInsertionStrategy<?> strat = EngineInsertionFactory.getStrategy(eng);
            Map<String, String> sampleMap = strat.generateSampleFormValues("customers_db", "default");
            JsonObject jo = new JsonObject();
            for (Map.Entry<String, String> e : sampleMap.entrySet()) {
                jo.addProperty(e.getKey(), e.getValue());
            }
            sb.append("  '").append(eng.key()).append("': ").append(jo.toString()).append(",\n");
        }
        sb.append("};\n\n");

        sb.append("""
        function openEngineInsertModal(engineKey, unitName, dbName) {
            var eng = (engineKey || 'DOCUMENT').toUpperCase();
            switchInsertEngine(eng);
            if (dbName) {
                var dbInput = document.getElementById('adaptive_insert_target_db');
                if (dbInput) dbInput.value = dbName;
            }
            if (unitName && unitName !== 'default') {
                var activeSec = document.querySelector('#adaptiveRecordInsertForm .jettra-flux-form-section[data-section="' + eng + '"]');
                if (activeSec) {
                    var unitInputs = activeSec.querySelectorAll('input[name="target_coll"], input[name="node_label"]');
                    unitInputs.forEach(function(inp) { inp.value = unitName; });
                }
            }
            if (window.JettraFluxNotification) {
                JettraFluxNotification.hide('adaptiveRecordInsertNotification');
            }
            if (window.JettraFluxModal) {
                JettraFluxModal.open('adaptiveRecordInsertModal');
            }
        }

        function switchInsertEngine(engineKey) {
            var eng = (engineKey || 'DOCUMENT').toUpperCase();
            var engineInput = document.getElementById('adaptive_insert_engine_input');
            if (engineInput) engineInput.value = eng;

            // Switch dynamic form section and toggle control disabled states
            if (window.JettraFluxDynamicForm) {
                JettraFluxDynamicForm.switchSection('adaptiveRecordInsertForm', eng);
            } else {
                var form = document.getElementById('adaptiveRecordInsertForm');
                if (form) {
                    var sections = form.querySelectorAll('.jettra-flux-form-section');
                    sections.forEach(function(sec) {
                        var isMatch = (sec.getAttribute('data-section') === eng);
                        sec.style.display = isMatch ? 'block' : 'none';
                        var controls = sec.querySelectorAll('input, select, textarea');
                        controls.forEach(function(ctrl) { ctrl.disabled = !isMatch; });
                    });
                }
            }

            // Update pills highlight
            document.querySelectorAll('[id^="engine_tab_btn_"]').forEach(function(pill) {
                var pillEngine = pill.getAttribute('data-engine');
                var color = pill.getAttribute('data-color') || '#38bdf8';
                var labelSpan = pill.querySelector('span');
                if (pillEngine === eng) {
                    pill.style.border = '2px solid ' + color;
                    pill.style.background = 'rgba(255,255,255,0.08)';
                    if (labelSpan) labelSpan.style.color = color;
                } else {
                    pill.style.border = '1px solid var(--j-border)';
                    pill.style.background = 'var(--j-bg-body)';
                    if (labelSpan) labelSpan.style.color = 'var(--j-text-secondary)';
                }
            });

            // Update submit button text
            var submitBtn = document.getElementById('btnAdaptiveSubmitInsert');
            if (submitBtn) {
                var labelSpan = submitBtn.querySelector('span');
                if (labelSpan) labelSpan.textContent = 'Insertar en ' + eng;
            }
        }

        function onAdaptiveIdModeChange(selectEl) {
            var idInput = document.getElementById('adaptive_insert_target_id');
            if (!idInput) return;
            if (selectEl.value === 'MANUAL') {
                idInput.placeholder = 'Ingrese ID obligatorio...';
                idInput.focus();
            } else {
                idInput.placeholder = '(Auto-generado ' + selectEl.value + ')';
            }
        }

        function loadSampleForActiveEngine() {
            var engineInput = document.getElementById('adaptive_insert_engine_input');
            var activeEng = engineInput ? engineInput.value : 'DOCUMENT';
            var sample = window.JettraAdaptiveSamples[activeEng];
            if (!sample) return;

            var form = document.getElementById('adaptiveRecordInsertForm');
            if (!form) return;

            for (var key in sample) {
                if (sample.hasOwnProperty(key)) {
                    var input = form.querySelector('[name="' + key + '"]');
                    if (input) {
                        input.value = sample[key];
                        // If it's a JSON editor, trigger validation
                        if (input.tagName && input.tagName.toLowerCase() === 'textarea') {
                            var statusId = input.id.replace('_input', '_status');
                            if (window.JettraFluxJsonEditor && document.getElementById(statusId)) {
                                window.JettraFluxJsonEditor.validate(input.id, statusId);
                            }
                        }
                    }
                }
            }

            if (window.JettraFluxNotification) {
                JettraFluxNotification.show('adaptiveRecordInsertNotification', 'Plantilla Cargada', 'Se han cargado datos de ejemplo válidos para el motor ' + activeEng + '.', 'INFO');
            }
        }

        function submitAdaptiveRecordInsert() {
            var form = document.getElementById('adaptiveRecordInsertForm');
            if (!form) return;

            var engineInput = document.getElementById('adaptive_insert_engine_input');
            var selectedEngine = engineInput ? engineInput.value : 'DOCUMENT';
            var submitBtn = document.getElementById('btnAdaptiveSubmitInsert');

            // 1. Ensure all inactive sections have controls disabled so FormData does not include duplicate fields
            var sections = form.querySelectorAll('.jettra-flux-form-section');
            sections.forEach(function(sec) {
                var isMatch = (sec.getAttribute('data-section') === selectedEngine);
                sec.style.display = isMatch ? 'block' : 'none';
                var controls = sec.querySelectorAll('input, select, textarea');
                controls.forEach(function(ctrl) {
                    ctrl.disabled = !isMatch;
                });
            });

            // 2. Syntax validation for active section textareas
            var activeSection = form.querySelector('.jettra-flux-form-section[data-section="' + selectedEngine + '"]');
            if (activeSection) {
                var textareas = activeSection.querySelectorAll('textarea');
                for (var i = 0; i < textareas.length; i++) {
                    var ta = textareas[i];
                    var statusId = ta.id.replace('_input', '_status');
                    var statusEl = document.getElementById(statusId);
                    if (statusEl && statusEl.textContent === 'SYNTAX ERROR') {
                        if (window.JettraFluxNotification) {
                            JettraFluxNotification.show('adaptiveRecordInsertNotification', 'Error de Sintaxis', 'Corrija los errores de sintaxis en el editor JSON antes de enviar.', 'ERROR');
                        }
                        ta.focus();
                        return;
                    }
                }
            }

            var formData = new FormData(form);
            var params = new URLSearchParams();
            formData.forEach(function(value, key) {
                params.append(key, value);
            });

            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Guardando en ' + selectedEngine + '...';
            }

            var targetPostUrl = form.action || window.location.href;
            fetch(targetPostUrl, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: params.toString()
            })
            .then(function(response) {
                return response.json();
            })
            .then(function(data) {
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.innerHTML = '<i class="fas fa-plus-circle"></i> Insertar en ' + selectedEngine;
                }
                if (data.status === 'SUCCESS') {
                    if (window.JettraFluxNotification) {
                        JettraFluxNotification.show('adaptiveRecordInsertNotification', '¡Inserción Exitosa!', data.message || 'Registro persistido correctamente.', 'SUCCESS');
                    }
                    setTimeout(function() {
                        if (window.JettraFluxModal) {
                            JettraFluxModal.close('adaptiveRecordInsertModal');
                        }
                        var dbVal = data.database || (document.getElementById('adaptive_insert_target_db') ? document.getElementById('adaptive_insert_target_db').value : 'customers_db');
                        var collVal = data.collection || 'default';
                        var redirectUrl = window.location.pathname + '?engine=' + encodeURIComponent(selectedEngine)
                            + '&target_db=' + encodeURIComponent(dbVal)
                            + '&coll=' + encodeURIComponent(collVal);
                        window.location.href = redirectUrl;
                    }, 800);
                } else {
                    if (window.JettraFluxNotification) {
                        JettraFluxNotification.show('adaptiveRecordInsertNotification', 'Error al Insertar', data.message || 'La operación no pudo completarse.', 'ERROR');
                    }
                }
            })
            .catch(function(err) {
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.innerHTML = '<i class="fas fa-plus-circle"></i> Insertar en ' + selectedEngine;
                }
                if (window.JettraFluxNotification) {
                    JettraFluxNotification.show('adaptiveRecordInsertNotification', 'Fallo de Red / Servidor', err.message || 'Error en comunicación con el servidor.', 'ERROR');
                }
            });
        }
        """);

        return RawScript.of(sb.toString());
    }
}
