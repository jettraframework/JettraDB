package com.jettra.store.engine.web;

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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

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
        engine.start();

        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    public void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
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
        assertTrue(html.contains("editFromInspectModal()"), "Must wire editFromInspectModal");
        assertTrue(html.contains("historyFromInspectModal()"), "Must wire historyFromInspectModal");

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
    public void testEditDialogSubmitScriptAndActionHandling() {
        Widget editDialog = EngineRecordEditDialog.build("/engines");
        String html = editDialog.render(Themes.FlatTheme());

        // Check JSON headers and action
        assertTrue(html.contains("'Content-Type': 'application/json; charset=UTF-8'"), "Must set application/json Content-Type");
        assertTrue(html.contains("'Accept': 'application/json'"), "Must set Accept application/json");
        assertTrue(html.contains("'X-Requested-With': 'XMLHttpRequest'"), "Must set X-Requested-With");
        assertTrue(html.contains("payloadObj['action'] = 'update_object'"), "Must set action to update_object");
        assertTrue(html.contains("payloadObj['is_fetch'] = 'true'"), "Must flag request with is_fetch");
    }

    @JettraTest
    @DisplayName("Test 5: Sample Database Uninstall Confirmation Dialog is built with JettraFlux components")
    public void testConfirmUninstallSampleDbModalBuiltWithJettraFlux() {
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "scrum_board_db");

        Widget pageWidget = page.buildContent(null, params, "dark");
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
}

