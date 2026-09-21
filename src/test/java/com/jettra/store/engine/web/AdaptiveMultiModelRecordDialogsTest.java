package com.jettra.store.engine.web;

import com.jettra.store.engine.web.dialog.EngineRecordInspectDialog;
import com.jettra.store.engine.web.dialog.EngineRecordEditDialog;
import com.jettra.store.engine.web.page.StoreEnginesPage;
import com.jettra.store.engine.web.page.StoreDatabasesPage;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.models.DocumentEngine;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.jettra.store.engine.insertion.EngineInsertionFactory;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.insertion.strategies.GraphInsertionStrategy;
import com.jettra.store.engine.models.GraphEngine;
import com.jettra.store.engine.web.EditActionHandler;
import com.jettra.store.engine.web.EditDocumentCommand;
import com.jettra.store.engine.web.EditDocumentResult;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.models.RecordVersionSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit & Integration tests for the Adaptive Multi-Model Record Editing and Inspection Dialogs.
 * Validates that both dialogs adapt to the 9 heterogeneous engines supported by JettraDB:
 * DOCUMENT, KEYVALUE, VECTOR, GRAPH, TIMESERIES, COLUMN, GEOSPATIAL, OBJECT, and RECORDS (Java 25).
 */
@NotRequiresRunningServer
public class AdaptiveMultiModelRecordDialogsTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage page;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_adaptive_dialogs_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("GRAPH", new GraphEngine(engine));
        engine.start();

        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    public void tearDown() {
        com.jettra.store.engine.test.TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("Test 1: EngineRecordEditDialog HTML Structure, 9-Engine Tabs & Polymorphic Inputs")
    public void testEngineRecordEditDialogRendering() {
        String actionUrl = "/engines?engine=RECORDS";
        Widget editDialog = EngineRecordEditDialog.build(actionUrl);
        assertNotNull(editDialog);

        String html = editDialog.render(Themes.FlatTheme());

        // 1. Modal container & ID
        assertTrue(html.contains("id=\"universalEditModal\"") || html.contains("id='universalEditModal'"),
                "Must render universalEditModal id");
        assertTrue(html.contains("Editar Registro Multi-Modelo"),
                "Must contain dialog title");

        // 2. Tab selector pills for all 9 engines
        for (EngineType eng : EngineType.all()) {
            assertTrue(html.contains("id=\"edit_engine_tab_btn_" + eng.key() + "\""),
                    "Must contain edit tab pill for engine: " + eng.key());
            assertTrue(html.contains(eng.displayName()),
                    "Must display engine name: " + eng.displayName());
        }

        // 3. Common metadata displays
        assertTrue(html.contains("id=\"universalEditEngineDisplay\""), "Must contain engine display");
        assertTrue(html.contains("id=\"universalEditDbDisplay\""), "Must contain db display");
        assertTrue(html.contains("id=\"universalEditCollDisplay\""), "Must contain unit display");
        assertTrue(html.contains("id=\"universalEditIdDisplay\""), "Must contain record ID display");

        // 4. Engine-specific input fields
        // DOCUMENT
        assertTrue(html.contains("id=\"editDocCollInput\""), "Must contain editDocCollInput");
        assertTrue(html.contains("id=\"editDocClassInput\""), "Must contain editDocClassInput");
        assertTrue(html.contains("id=\"editDocPayloadInput\""), "Must contain editDocPayloadInput");
        assertTrue(html.contains("id=\"universalEditPayloadInput\""), "Must contain universalEditPayloadInput");

        // KEYVALUE
        assertTrue(html.contains("id=\"editKvCollInput\""), "Must contain editKvCollInput");
        assertTrue(html.contains("id=\"editKvValueInput\""), "Must contain editKvValueInput");

        // VECTOR
        assertTrue(html.contains("id=\"editVecCollInput\""), "Must contain editVecCollInput");
        assertTrue(html.contains("id=\"editVecCoordsInput\""), "Must contain editVecCoordsInput");
        assertTrue(html.contains("id=\"editVecMetaInput\""), "Must contain editVecMetaInput");

        // GRAPH
        assertTrue(html.contains("id=\"editGraphCollInput\""), "Must contain editGraphCollInput");
        assertTrue(html.contains("id=\"editGraphPropsInput\""), "Must contain editGraphPropsInput");

        // TIMESERIES
        assertTrue(html.contains("id=\"editTsCollInput\""), "Must contain editTsCollInput");
        assertTrue(html.contains("id=\"editTsValueInput\""), "Must contain editTsValueInput");
        assertTrue(html.contains("id=\"editTsUnitInput\""), "Must contain editTsUnitInput");
        assertTrue(html.contains("id=\"editTsTimestampInput\""), "Must contain editTsTimestampInput");
        assertTrue(html.contains("id=\"editTsTagsInput\""), "Must contain editTsTagsInput");

        // COLUMN
        assertTrue(html.contains("id=\"editColCollInput\""), "Must contain editColCollInput");
        assertTrue(html.contains("id=\"editColDataInput\""), "Must contain editColDataInput");

        // GEOSPATIAL
        assertTrue(html.contains("id=\"editGeoCollInput\""), "Must contain editGeoCollInput");
        assertTrue(html.contains("id=\"editGeoLatInput\""), "Must contain editGeoLatInput");
        assertTrue(html.contains("id=\"editGeoLonInput\""), "Must contain editGeoLonInput");
        assertTrue(html.contains("id=\"editGeoNameInput\""), "Must contain editGeoNameInput");

        // OBJECT
        assertTrue(html.contains("id=\"editObjCollInput\""), "Must contain editObjCollInput");
        assertTrue(html.contains("id=\"editObjMimeInput\""), "Must contain editObjMimeInput");
        assertTrue(html.contains("id=\"editObjPayloadInput\""), "Must contain editObjPayloadInput");

        // RECORDS (Java 25)
        assertTrue(html.contains("id=\"editRecCollInput\""), "Must contain editRecCollInput");
        assertTrue(html.contains("id=\"editRecClassInput\""), "Must contain editRecClassInput");
        assertTrue(html.contains("id=\"editRecPayloadInput\""), "Must contain editRecPayloadInput");

        // 5. Submit action & scripts
        assertTrue(html.contains("id=\"btnUniversalEditSubmit\""), "Must contain submit button");
        assertTrue(html.contains("submitUniversalEditRecord"), "Must attach submitUniversalEditRecord handler to keep user in web interface");
        assertTrue(html.contains("openUniversalEditModal"), "Must declare openUniversalEditModal function");
        assertTrue(html.contains("switchEditEngine"), "Must declare switchEditEngine function");

        // 6. Record Form structured table & controls
        assertTrue(html.contains("edit_rec_record_editor_container"), "Must embed JettraFluxRecordForm container for RECORDS");
        assertTrue(html.contains("edit_rec_record_fields_tbody"), "Must contain record fields tbody");
        assertTrue(html.contains("Nombre de Campo (_schema)"), "Must contain typed schema column header");
        assertTrue(html.contains("Valor Componente (components)"), "Must contain component value column header");
    }

    @JettraTest
    @DisplayName("Test 2: EngineRecordInspectDialog HTML Structure, 9-Engine Tabs & Adaptive Viewer")
    public void testEngineRecordInspectDialogRendering() {
        Widget inspectDialog = EngineRecordInspectDialog.build();
        assertNotNull(inspectDialog);

        String html = inspectDialog.render(Themes.FlatTheme());

        // 1. Modal container & ID
        assertTrue(html.contains("id=\"inspectRecordModal\"") || html.contains("id='inspectRecordModal'"),
                "Must render inspectRecordModal id");
        assertTrue(html.contains("Inspeccionar Registro Multi-Modelo"),
                "Must contain inspect dialog title");

        // 2. Tab selector pills for all 9 engines
        for (EngineType eng : EngineType.all()) {
            assertTrue(html.contains("id=\"inspect_engine_tab_btn_" + eng.key() + "\""),
                    "Must contain inspect tab pill for engine: " + eng.key());
            assertTrue(html.contains(eng.displayName()),
                    "Must display engine name: " + eng.displayName());
        }

        // 3. Common metadata displays
        assertTrue(html.contains("id=\"inspectRecordEngineDisplay\""), "Must contain engine display");
        assertTrue(html.contains("id=\"inspectRecordDbDisplay\""), "Must contain db display");
        assertTrue(html.contains("id=\"inspectRecordCollDisplay\""), "Must contain unit display");
        assertTrue(html.contains("id=\"inspectRecordIdDisplay\""), "Must contain record ID display");
        assertTrue(html.contains("id=\"inspectRecordVersionDisplay\""), "Must contain version display");
        assertTrue(html.contains("id=\"inspectReferencesCountBadge\""), "Must contain references badge");

        // 4. Adaptive model inspection container
        assertTrue(html.contains("id=\"inspectAdaptiveModelView\""),
                "Must contain container for engine-adaptive model inspection");

        // 5. Jref Auto-Resolve elements
        assertTrue(html.contains("id=\"chkInspectResolveRefs\""), "Must contain Jref toggle checkbox");
        assertTrue(html.contains("Auto-Resolve Jref"), "Must mention Auto-Resolve Jref");
        assertTrue(html.contains("id=\"inspectRecordReferencesContainer\""), "Must contain references container");
        assertTrue(html.contains("id=\"inspectRecordReferencesList\""), "Must contain references list");

        // 6. Full Payload Viewer & Actions
        assertTrue(html.contains("id=\"inspectRecordPayloadDisplay\""), "Must contain payload display");
        assertTrue(html.contains("id=\"btnCopyInspect\""), "Must contain copy button");
        if (html.contains("editFromInspectModal()")) {
            assertTrue(html.contains("editFromInspectModal()"), "Must wire editFromInspectModal if edit button is enabled");
            assertTrue(html.contains("historyFromInspectModal()"), "Must wire historyFromInspectModal if history button is enabled");
        }

        // 7. Client scripts & Table View
        assertTrue(html.contains("switchInspectEngine"), "Must declare switchInspectEngine script");
        assertTrue(html.contains("renderAdaptiveInspectModelView"), "Must declare renderAdaptiveInspectModelView script");
        assertTrue(html.contains("Nombre de Campo / Columna"), "Must define structured table header for Record inspection");
        assertTrue(html.contains("Tipo de Dato (_schema)"), "Must define schema type column header for Record inspection");
        assertTrue(html.contains("Valor del Componente (components)"), "Must define component value column header for Record inspection");
    }

    @JettraTest
    @DisplayName("Test 3: StoreEnginesPage Integration with Adaptive Multi-Model Modals")
    public void testStoreEnginesPageIntegration() {
        Map<String, String> params = new HashMap<>();
        params.put("engine", "RECORDS");
        params.put("target_db", "customers_db");

        Widget pageWidget = page.buildContent(null, params, "dark");
        assertNotNull(pageWidget);
        String html = pageWidget.render(Themes.FlatTheme());

        // Both adaptive modals must be embedded in the page
        assertTrue(html.contains("id=\"universalEditModal\""), "Page must contain universalEditModal");
        assertTrue(html.contains("id=\"inspectRecordModal\""), "Page must contain inspectRecordModal");

        // Engine tabs must be present in both modals within the page
        assertTrue(html.contains("edit_engine_tab_btn_RECORDS"), "Page must contain edit tab for RECORDS");
        assertTrue(html.contains("inspect_engine_tab_btn_RECORDS"), "Page must contain inspect tab for RECORDS");

        // Script integrations
        assertTrue(html.contains("openUniversalEditModal"), "Page must contain openUniversalEditModal function");
        assertTrue(html.contains("openInspectRecordModal"), "Page must contain openInspectRecordModal function");
        assertTrue(html.contains("switchEditEngine"), "Page must contain switchEditEngine call");
        assertTrue(html.contains("switchInspectEngine"), "Page must contain switchInspectEngine call");
    }

    @JettraTest
    @DisplayName("Test 4: Edit Dialog Submit Script dispatches JSON and handles update_object without HTTP 400")
    public void testEditDialogSubmitScriptAndActionHandling() throws IOException {
        Widget editDialog = EngineRecordEditDialog.build("/engines");
        String html = editDialog.render(Themes.FlatTheme());

        // Check JSON headers and action
        assertTrue(html.contains("'Content-Type': 'application/json; charset=UTF-8'"), "Must set application/json Content-Type");
        assertTrue(html.contains("'Accept': 'application/json'"), "Must set Accept application/json");
        assertTrue(html.contains("'X-Requested-With': 'XMLHttpRequest'"), "Must set X-Requested-With");
        assertTrue(html.contains("payloadObj['action'] = 'update_object'"), "Must set action to update_object");
        assertTrue(html.contains("payloadObj['is_fetch'] = 'true'"), "Must flag request with is_fetch");

        // Verify DOM clobbering prevention: must use form.getAttribute('action'), not form.action
        assertTrue(html.contains("form.getAttribute('action')"), "Must use form.getAttribute('action') to avoid DOM clobbering by <input name='action'>");
        assertFalse(html.contains("form.action ||"), "Must NOT use form.action which returns child input element in HTML DOM");

        // First pre-insert a document in DOCUMENT engine
        engine.getStorageCore().put("doc:default:default:doc_101", "{\"name\":\"Original Doc\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());

        // Simulate exact AJAX POST dispatch from browser hitting /engines
        TestHttpExchange exchange = new TestHttpExchange("POST", "/engines?engine=DOCUMENT");
        exchange.getRequestHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getRequestHeaders().set("Accept", "application/json");
        exchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        String jsonBody = "{\"action\":\"update_object\",\"is_ajax\":\"true\",\"is_fetch\":\"true\",\"engine_type\":\"DOCUMENT\",\"target_db\":\"default\",\"target_coll\":\"default\",\"target_id\":\"doc_101\",\"record_payload\":\"{\\\"name\\\":\\\"Updated Doc\\\"}\"}";
        exchange.setJsonRequestBody(jsonBody);

        page.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "Response code must be 200 OK, not 400: " + exchange.getResponseBodyAsString());
        assertTrue(exchange.getResponseBodyAsString().contains("\"status\":\"SUCCESS\""), "Must return SUCCESS status");

        // Also test dispatch when hitting /engine (singular alias)
        TestHttpExchange exchangeSingular = new TestHttpExchange("POST", "/engine?engine=DOCUMENT");
        exchangeSingular.getRequestHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchangeSingular.getRequestHeaders().set("Accept", "application/json");
        exchangeSingular.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        exchangeSingular.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchangeSingular.setJsonRequestBody(jsonBody);

        page.handle(exchangeSingular);
        assertEquals(200, exchangeSingular.getResponseCode(), "Singular /engine path must also succeed with 200 OK: " + exchangeSingular.getResponseBodyAsString());
    }

    @JettraTest
    @DisplayName("Test 4B: Multi-Model Record Updates for RECORDS, KEYVALUE, VECTOR without HTTP 400")
    public void testMultiEngineRecordUpdatesWithoutHttp400() throws IOException {
        // Pre-insert records for RECORDS and KEYVALUE
        engine.getStorageCore().put("rec:default:employees:emp_01", "{\"_recordClass\":\"com.jettra.model.EmployeeRecord\",\"_table\":\"employees\",\"components\":{\"name\":\"Alice\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("kv:default:config:app_title", "Old Title".getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());

        // 1. Update RECORDS item
        TestHttpExchange recExchange = new TestHttpExchange("POST", "/engines?engine=RECORDS");
        recExchange.getRequestHeaders().set("Content-Type", "application/json; charset=UTF-8");
        recExchange.getRequestHeaders().set("Accept", "application/json");
        recExchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        recExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        String recBody = "{\"action\":\"update_object\",\"is_ajax\":\"true\",\"is_fetch\":\"true\",\"engine_type\":\"RECORDS\",\"target_db\":\"default\",\"target_coll\":\"employees\",\"target_id\":\"emp_01\",\"record_payload\":\"{\\\"_table\\\":\\\"employees\\\",\\\"_recordClass\\\":\\\"com.jettra.model.EmployeeRecord\\\",\\\"components\\\":{\\\"name\\\":\\\"Alice Updated\\\"}}\"}";
        recExchange.setJsonRequestBody(recBody);

        page.handle(recExchange);
        assertEquals(200, recExchange.getResponseCode(), "Updating RECORDS must return 200 OK: " + recExchange.getResponseBodyAsString());
        assertTrue(recExchange.getResponseBodyAsString().contains("\"status\":\"SUCCESS\""), "RECORDS update must return SUCCESS");

        // 2. Update KEYVALUE item
        TestHttpExchange kvExchange = new TestHttpExchange("POST", "/engine?engine=KEYVALUE");
        kvExchange.getRequestHeaders().set("Content-Type", "application/json; charset=UTF-8");
        kvExchange.getRequestHeaders().set("Accept", "application/json");
        kvExchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        kvExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        String kvBody = "{\"action\":\"update_object\",\"is_ajax\":\"true\",\"is_fetch\":\"true\",\"engine_type\":\"KEYVALUE\",\"target_db\":\"default\",\"target_coll\":\"config\",\"target_id\":\"app_title\",\"record_payload\":\"New Title V2\",\"kv_value\":\"New Title V2\"}";
        kvExchange.setJsonRequestBody(kvBody);

        page.handle(kvExchange);
        assertEquals(200, kvExchange.getResponseCode(), "Updating KEYVALUE must return 200 OK: " + kvExchange.getResponseBodyAsString());
        assertTrue(kvExchange.getResponseBodyAsString().contains("\"status\":\"SUCCESS\""), "KEYVALUE update must return SUCCESS");
    }

    @JettraTest
    @DisplayName("Test 5: Sample Database Uninstall Confirmation Dialog is built with JettraFlux components")
    public void testConfirmUninstallSampleDbModalBuiltWithJettraFlux() {
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "scrum_board_db");

        Widget pageWidget = new StoreDatabasesPage(engine, null).buildContent(null, params, "dark");
        String html = pageWidget.render(Themes.FlatTheme());

        // 1. Confirm Uninstall Modal exists with JettraFlux structure
        assertTrue(html.contains("id=\"confirmUninstallSampleDbModal\""),
                "Must render confirmUninstallSampleDbModal built with JettraFlux ModalDialog");
        assertTrue(html.contains("Confirm Dataset Uninstallation"),
                "Must render header title");
        assertTrue(html.contains("id=\"confirmUninstallDbNameDisplay\""),
                "Must render target database display container");
        assertTrue(html.contains("id=\"confirmUninstallTargetDbInput\""),
                "Must render hidden target database input");

        // 2. Exact message prompt required
        assertTrue(html.contains("Are you sure you want to uninstall and purge sample database"),
                "Must include prompt text for uninstalling database");
        assertTrue(html.contains("All stored records and components will be permanently deleted."),
                "Must state that all stored records and components will be permanently deleted");

        // 3. Action buttons
        assertTrue(html.contains("id=\"btnConfirmUninstallSubmit\""),
                "Must render confirm uninstall submit button");
        assertTrue(html.contains("executeUninstallSampleDb()"),
                "Confirm button must wire to executeUninstallSampleDb()");
        assertTrue(html.contains("openConfirmUninstallSampleDbModal"),
                "Script must declare openConfirmUninstallSampleDbModal");

        // 4. Verify native browser confirm is NOT used in uninstallSampleDb
        assertFalse(html.contains("confirm('Are you sure you want to uninstall"),
                "Must NOT use raw JavaScript window.confirm() dialog");
    }

    @JettraTest
    @DisplayName("Test 6: Sample Databases Content adapts to JettraDB Java 25 Record format and rich types")
    public void testSampleDatabasesContentAdaptedToJettraDBFeatures() {
        com.jettra.store.engine.samples.SampleDatasetManager sampleManager = new com.jettra.store.engine.samples.SampleDatasetManager(engine);

        // 1. Load hr_enterprise_db
        int hrCount = sampleManager.loadHrEnterpriseDataset();
        assertTrue(hrCount > 0, "Must load HR enterprise records");

        byte[] emp100Bytes = engine.getStorageCore().get("rec:hr_enterprise_db:emp_100");
        assertNotNull(emp100Bytes, "Employee 100 record must be stored in rec:hr_enterprise_db:emp_100");
        String emp100Json = new String(emp100Bytes, java.nio.charset.StandardCharsets.UTF_8);

        // Verify Java 25 Record typed schema and components structure
        assertTrue(emp100Json.contains("\"_recordClass\":\"com.jettra.model.EmployeeProfileRecord\""), "Must contain _recordClass");
        assertTrue(emp100Json.contains("\"_table\":\"employees\""), "Must contain _table");
        assertTrue(emp100Json.contains("\"_schema\":"), "Must contain _schema object");
        assertTrue(emp100Json.contains("\"hireDate\":\"LocalDate\""), "Schema must declare LocalDate");
        assertTrue(emp100Json.contains("\"shift\":\"LocalTime\""), "Schema must declare LocalTime");
        assertTrue(emp100Json.contains("\"contractType\":\"Enum\""), "Schema must declare Enum");
        assertTrue(emp100Json.contains("\"skills\":\"List<String>\""), "Schema must declare List<String>");
        assertTrue(emp100Json.contains("\"country\":\"Object\""), "Schema must declare Object");
        assertTrue(emp100Json.contains("\"components\":"), "Must contain components object");
        assertTrue(emp100Json.contains("\"salary\":"), "Components must contain salary");
        assertTrue(emp100Json.contains("\"active\":true"), "Components must contain active boolean");

        // 2. Load ExampleDBReferences
        int refCount = sampleManager.loadExampleDBReferencesDataset();
        assertTrue(refCount > 0, "Must load ExampleDBReferences dataset");

        byte[] emp201Bytes = engine.getStorageCore().get("rec:ExampleDBReferences:emp_201");
        assertNotNull(emp201Bytes, "Employee 201 record must exist");
        String emp201Json = new String(emp201Bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(emp201Json.contains("\"_schema\":"), "emp_201 must contain _schema");
        assertTrue(emp201Json.contains("\"hireDate\":\"LocalDate\""), "emp_201 must contain LocalDate");
        assertTrue(emp201Json.contains("\"components\":"), "emp_201 must contain components");
    }

    @JettraTest
    @DisplayName("Test 7: Graph Insertion Strategy Validation allows auto-generated ID without 400 error")
    public void testGraphInsertionStrategyAutoIdAndValidation() throws Exception {
        GraphInsertionStrategy strategy = new GraphInsertionStrategy();

        // 1. Auto ID with UUID mode: target_id is empty -> Validation must succeed
        Map<String, String> autoParams = new HashMap<>();
        autoParams.put("id_gen_mode", "UUID");
        autoParams.put("node_label", "Person");
        autoParams.put("node_props", "{\"name\":\"Alice Vance\"}");

        ValidationResult autoVal = strategy.validate(autoParams);
        assertTrue(autoVal.isValid(), "Validation must pass when target_id is omitted with UUID auto-generation mode");

        // 2. Auto ID with empty id_gen_mode (defaults to UUID) -> Validation must succeed
        Map<String, String> defaultParams = new HashMap<>();
        defaultParams.put("node_label", "Developer");
        defaultParams.put("node_props", "{\"skill\":\"Java 25\"}");

        ValidationResult defaultVal = strategy.validate(defaultParams);
        assertTrue(defaultVal.isValid(), "Validation must pass when id_gen_mode is defaulted");

        // 3. Manual mode without target_id -> Validation must fail with clear message
        Map<String, String> manualEmptyParams = new HashMap<>();
        manualEmptyParams.put("id_gen_mode", "MANUAL");
        manualEmptyParams.put("node_label", "Person");

        ValidationResult manualVal = strategy.validate(manualEmptyParams);
        assertFalse(manualVal.isValid(), "Validation must fail in MANUAL mode if target_id is blank");
        assertTrue(manualVal.errors().stream().anyMatch(e -> e.contains("Manual")),
                "Error message must specify that target_id is required in Manual mode");

        // 4. End-to-end execution via EngineInsertionFactory
        CompletableFuture<InsertionResult> future = EngineInsertionFactory.executeInsertAsync(
                engine, "GRAPH", "social_db", autoParams);
        InsertionResult result = future.get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "InsertionResult must be successful: " + result.message());
        assertNotNull(result.id(), "Resolved ID must be generated");
        assertFalse(result.id().isBlank(), "Resolved ID must not be blank");
    }

    @JettraTest
    @DisplayName("Test 8: Index Creation specifies explicit engine_type and target_coll")
    public void testCreateIndexModalExplicitEngineAndUnit() throws Exception {
        // 1. Render buildCreateIndexModal
        Widget createIndexModal = page.buildCreateIndexModal("/engines");
        String modalHtml = createIndexModal.render(Themes.FlatTheme());

        assertTrue(modalHtml.contains("name='engine_type'") || modalHtml.contains("name=\"engine_type\""), "createIndexModal must contain engine_type select");
        assertTrue(modalHtml.contains("name='target_coll'") || modalHtml.contains("name=\"target_coll\""), "createIndexModal must contain target_coll input");
        assertTrue(modalHtml.contains("createIndexEngineSelect"), "Must have createIndexEngineSelect id");
        assertTrue(modalHtml.contains("createIndexCollInput"), "Must have createIndexCollInput id");

        // 2. Dispatch create_index request with explicit engine_type and target_coll
        TestHttpExchange exchange = new TestHttpExchange("POST", "/engines?action=create_index");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        Map<String, String> formParams = new HashMap<>();
        formParams.put("action", "create_index");
        formParams.put("target_db", "test_index_db");
        formParams.put("engine_type", "KEYVALUE");
        formParams.put("target_coll", "user_sessions");
        formParams.put("index_name", "idx_session_ttl");
        formParams.put("index_field", "ttl");
        formParams.put("index_type", "HASH");

        // Encode as form urlencoded
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : formParams.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
              .append("=")
              .append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        exchange.setFormRequestBody(sb.toString());

        page.handle(exchange);
        assertEquals(200, exchange.getResponseCode(), "create_index must return 200 OK");

        // Verify index is registered in storage with engineType and collection
        byte[] idxBytes = engine.getStorageCore().get("idx:test_index_db:idx_session_ttl");
        assertNotNull(idxBytes, "Index must be stored in idx:test_index_db:idx_session_ttl");
        String idxJson = new String(idxBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(idxJson.contains("\"engineType\":\"KEYVALUE\""), "Index metadata must contain engineType KEYVALUE");
        assertTrue(idxJson.contains("\"collection\":\"user_sessions\""), "Index metadata must contain collection user_sessions");
        assertTrue(idxJson.contains("\"field\":\"ttl\""), "Index metadata must contain field ttl");
        assertTrue(idxJson.contains("\"type\":\"HASH\""), "Index metadata must contain type HASH");
    }

    @JettraTest
    @DisplayName("Test 9: Universal Edit Modal teleportation and Java 25 Record v+1 versioning")
    public void testUniversalEditModalTeleportedAndRecordVersionIncrement() throws Exception {
        // 1. Verify modalIds array in page HTML includes universalEditModal
        Map<String, String> pageParams = new HashMap<>();
        Widget pageWidget = page.buildContent(null, pageParams, "dark");
        String pageHtml = pageWidget.render(Themes.FlatTheme());

        assertTrue(pageHtml.contains("'universalEditModal'"),
                "StoreEnginesPage modalIds teleport array must include universalEditModal");
        assertTrue(pageHtml.contains("'adaptiveRecordInsertModal'"),
                "StoreEnginesPage modalIds teleport array must include adaptiveRecordInsertModal");

        // 2. Verify Record edit creates v+1 version
        String db = "corp_db";
        String coll = "staff";
        String id = "rec_001";
        String key = "rec:" + db + ":" + coll + ":" + id;

        // Initial version v1
        String v1Payload = "{\"_recordClass\":\"com.jettra.model.StaffRecord\",\"_table\":\"staff\",\"components\":{\"name\":\"Carlos\",\"role\":\"Eng\"}}";
        engine.getStorageCore().put(key, v1Payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis() - 5000);

        // Edit through EditActionHandler
        EditActionHandler editHandler = new EditActionHandler(engine);
        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("rec_class", "com.jettra.model.StaffRecord");
        String v2Payload = "{\"_recordClass\":\"com.jettra.model.StaffRecord\",\"_table\":\"staff\",\"components\":{\"name\":\"Carlos\",\"role\":\"Lead Eng\"}}";

        EditDocumentCommand cmd = EditDocumentCommand.of("RECORDS", db, coll, id, v2Payload, extraParams);
        EditDocumentResult res = editHandler.executeEdit(cmd);
        assertTrue(res.success(), "Edit must succeed: " + res.error());

        // Verify data in storage has the new payload
        byte[] updatedBytes = engine.getStorageCore().get(key);
        assertNotNull(updatedBytes, "Updated record must exist in storage");
        String updatedJson = new String(updatedBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(updatedJson.contains("Lead Eng"), "Updated payload must be persisted");

        // Verify version history has at least 2 versions (v1 and v2)
        HierarchyExplorerService hierarchyService = new HierarchyExplorerService(engine);
        List<RecordVersionSnapshot> snapshots = hierarchyService.getVersionSnapshots("RECORDS", db, coll, id);
        assertTrue(snapshots.size() >= 2, "HierarchyExplorerService must report at least 2 snapshots after edit, confirming v+1: " + snapshots.size());
    }

    @JettraTest
    @DisplayName("Test 10: StoreEnginesPage Tree View script does not reference undefined currentColl")
    public void testTreeViewScriptDoesNotReferenceUndefinedCurrentColl() {
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "test_db");

        Widget pageWidget = page.buildContent(null, params, "dark");
        String html = pageWidget.render(Themes.FlatTheme());

        // Ensure currentColl is NOT referenced as an undefined JS identifier in renderDbHierarchyHtml
        assertFalse(html.contains("escapeJsString(currentColl)"),
                "renderDbHierarchyHtml must not reference undefined variable 'currentColl'");
        assertFalse(html.contains("+ currentColl +"),
                "renderDbHierarchyHtml must not concatenate undefined 'currentColl'");
        assertTrue(html.contains("renderDbHierarchyHtml"),
                "Page must contain renderDbHierarchyHtml function");
    }

    @JettraTest
    @DisplayName("Test 11: EngineRecordEditDialog renders structured JettraFlux components for all 9 engines")
    public void testEngineRecordEditDialogStructuredJettraFluxComponents() {
        Widget editDialog = EngineRecordEditDialog.build("/engines");
        String html = editDialog.render(Themes.FlatTheme());

        // Helper scripts for safe JSON editor setting and decoding
        assertTrue(html.contains("function safeDecodePayload"), "Must declare safeDecodePayload script");
        assertTrue(html.contains("function setJsonEditorVal"), "Must declare setJsonEditorVal script");

        // DOCUMENT: JettraFluxJsonEditor and Class input
        assertTrue(html.contains("id=\"editDocPayload_container\"") || html.contains("name=\"editDocPayload\""), "Must contain editDocPayload JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editDocPayload_input\""), "Must contain editDocPayload textarea");
        assertTrue(html.contains("id=\"editDocClassInput\""), "Must contain editDocClassInput");

        // KEYVALUE: Value and TTL
        assertTrue(html.contains("id=\"editKvValueInput\""), "Must contain editKvValueInput");
        assertTrue(html.contains("id=\"editKvTtlInput\""), "Must contain editKvTtlInput");

        // VECTOR: Coords, Metric select, and JettraFluxJsonEditor
        assertTrue(html.contains("id=\"editVecCoordsInput\""), "Must contain editVecCoordsInput");
        assertTrue(html.contains("id=\"editVecMetricSelect\""), "Must contain editVecMetricSelect");
        assertTrue(html.contains("id=\"editVecMeta_container\"") || html.contains("name=\"editVecMeta\""), "Must contain editVecMeta JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editVecMeta_input\""), "Must contain editVecMeta textarea");

        // GRAPH: Mode radios (Node/Edge), Label/IDs, and JettraFluxJsonEditors
        assertTrue(html.contains("id=\"edit_graph_mode_node\""), "Must contain Node mode radio");
        assertTrue(html.contains("id=\"edit_graph_mode_edge\""), "Must contain Edge mode radio");
        assertTrue(html.contains("id=\"editGraphNodeProps_container\"") || html.contains("name=\"editGraphNodeProps\""), "Must contain editGraphNodeProps JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editGraphEdgeProps_container\"") || html.contains("name=\"editGraphEdgeProps\""), "Must contain editGraphEdgeProps JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editGraphFromId\""), "Must contain editGraphFromId");
        assertTrue(html.contains("id=\"editGraphToId\""), "Must contain editGraphToId");

        // TIMESERIES: Value, Unit, Timestamp with Now button, and Tags JettraFluxJsonEditor
        assertTrue(html.contains("id=\"editTsValueInput\""), "Must contain editTsValueInput");
        assertTrue(html.contains("id=\"editTsUnitInput\""), "Must contain editTsUnitInput");
        assertTrue(html.contains("id=\"editTsTags_container\"") || html.contains("name=\"editTsTags\""), "Must contain editTsTags JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editTsTags_input\""), "Must contain editTsTags textarea");

        // COLUMN: Family, Qualifier, and Data JettraFluxJsonEditor
        assertTrue(html.contains("id=\"editColCollInput\""), "Must contain editColCollInput");
        assertTrue(html.contains("id=\"editColQualifierInput\""), "Must contain editColQualifierInput");
        assertTrue(html.contains("id=\"editColData_container\"") || html.contains("name=\"editColData\""), "Must contain editColData JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editColData_input\""), "Must contain editColData textarea");

        // GEOSPATIAL: Layer, Feature Name, Geo Type Select, Lat, Lon, and Meta JettraFluxJsonEditor
        assertTrue(html.contains("id=\"editGeoCollInput\""), "Must contain editGeoCollInput");
        assertTrue(html.contains("id=\"editGeoNameInput\""), "Must contain editGeoNameInput");
        assertTrue(html.contains("id=\"editGeoTypeSelect\""), "Must contain editGeoTypeSelect");
        assertTrue(html.contains("id=\"editGeoLatInput\""), "Must contain editGeoLatInput");
        assertTrue(html.contains("id=\"editGeoLonInput\""), "Must contain editGeoLonInput");
        assertTrue(html.contains("id=\"editGeoMeta_container\"") || html.contains("name=\"editGeoMeta\""), "Must contain editGeoMeta JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editGeoMeta_input\""), "Must contain editGeoMeta textarea");

        // OBJECT: Bucket, Class, MIME Type, and Payload JettraFluxJsonEditor
        assertTrue(html.contains("id=\"editObjCollInput\""), "Must contain editObjCollInput");
        assertTrue(html.contains("id=\"editObjClassInput\""), "Must contain editObjClassInput");
        assertTrue(html.contains("id=\"editObjMimeInput\""), "Must contain editObjMimeInput");
        assertTrue(html.contains("id=\"editObjPayload_container\"") || html.contains("name=\"editObjPayload\""), "Must contain editObjPayload JettraFluxJsonEditor");
        assertTrue(html.contains("id=\"editObjPayload_input\""), "Must contain editObjPayload textarea");

        // RECORDS: JettraFluxRecordForm with schema, components, and canonical dual view
        assertTrue(html.contains("id=\"edit_rec_record_editor_container\""), "Must contain JettraFluxRecordForm container");
        assertTrue(html.contains("id=\"edit_rec_record_fields_tbody\""), "Must contain record fields tbody");
        assertTrue(html.contains("id=\"edit_rec_record_json_container\""), "Must contain canonical JSON container");
        assertTrue(html.contains("Canonical Record Payload Serialization"), "Must contain canonical record serialization header");
    }

    @JettraTest
    @DisplayName("Test 12: Record payload endpoint and universalRecordEditor auto-population")
    public void testRecordPayloadEndpointAndUniversalRecordEditor() throws Exception {
        // Pre-insert a record in storage
        String docKey = "doc:default:customers:cust_999";
        String originalJson = "{\"name\":\"Super Customer\",\"tier\":\"VIP\",\"points\":1500}";
        engine.getStorageCore().put(docKey, originalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());

        // 1. Test get_record_payload action on /engines
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines?action=get_record_payload&engine=DOCUMENT&target_db=default&coll=customers&id=cust_999");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        page.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "get_record_payload must return 200 OK");
        String responseBody = exchange.getResponseBodyAsString();
        assertTrue(responseBody.contains("\"status\":\"SUCCESS\""), "Response must indicate SUCCESS");
        assertTrue(responseBody.contains("Super Customer"), "Response must return stored payload content");
        assertTrue(responseBody.contains("\"payloadB64\""), "Response must include payloadB64");

        // 2. Verify StoreEnginesPage exports and connects universalRecordEditor
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "default");
        Widget pageWidget = page.buildContent(null, params, "dark");
        String pageHtml = pageWidget.render(Themes.FlatTheme());

        assertTrue(pageHtml.contains("universalRecordEditor"), "Page must reference universalRecordEditor");
        assertTrue(pageHtml.contains("get_record_payload"), "Page must support get_record_payload fallback");

        // 3. Verify EngineRecordEditDialog exports universalRecordEditor and contains dynamic auto-fetch
        Widget dialogWidget = EngineRecordEditDialog.build("/engines");
        String dialogHtml = dialogWidget.render(Themes.FlatTheme());
        assertTrue(dialogHtml.contains("window.universalRecordEditor = openUniversalEditModal"), "Dialog must export universalRecordEditor");
        assertTrue(dialogHtml.contains("action=get_record_payload"), "Dialog must implement dynamic auto-fetch fallback if payload is empty");
    }

    private static class TestHttpExchange extends com.sun.net.httpserver.HttpExchange {
        private final String method;
        private final java.net.URI uri;
        private final com.sun.net.httpserver.Headers requestHeaders = new com.sun.net.httpserver.Headers();
        private final com.sun.net.httpserver.Headers responseHeaders = new com.sun.net.httpserver.Headers();
        private final java.io.ByteArrayOutputStream responseBody = new java.io.ByteArrayOutputStream();
        private java.io.ByteArrayInputStream requestBody = new java.io.ByteArrayInputStream(new byte[0]);
        private int responseCode = -1;

        TestHttpExchange(String method, String path) {
            this.method = method;
            this.uri = java.net.URI.create(path);
        }

        void setJsonRequestBody(String body) {
            this.requestBody = new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.requestHeaders.set("Content-Type", "application/json; charset=UTF-8");
        }

        void setFormRequestBody(String body) {
            this.requestBody = new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
        }

        @Override public com.sun.net.httpserver.Headers getRequestHeaders() { return requestHeaders; }
        @Override public com.sun.net.httpserver.Headers getResponseHeaders() { return responseHeaders; }
        @Override public java.net.URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public java.io.InputStream getRequestBody() { return requestBody; }
        @Override public java.io.OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(java.io.InputStream i, java.io.OutputStream o) {}
        @Override public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }

        public String getResponseBodyAsString() {
            return responseBody.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}

