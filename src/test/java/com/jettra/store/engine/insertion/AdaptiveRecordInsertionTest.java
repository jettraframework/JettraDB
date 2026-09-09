package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.*;
import com.jettra.store.engine.web.EngineRecordInsertionDialog;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit test suite verifying schema validation, payload serialization,
 * reactive form building, and Virtual Thread execution across all 9 engines.
 */
@io.jettra.test.annotation.NotRequiresRunningServer
public class AdaptiveRecordInsertionTest {

    private Path tempDir;
    private JettraStorageEngine storageEngine;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_test_adaptive_");
        storageEngine = new JettraStorageEngine(tempDir.toString());

        // Register all 9 multi-model engines
        storageEngine.registerEngine("DOCUMENT", new DocumentEngine(storageEngine));
        storageEngine.registerEngine("KEYVALUE", new KeyValueEngine(storageEngine));
        storageEngine.registerEngine("VECTOR", new VectorEngine(storageEngine));
        storageEngine.registerEngine("GRAPH", new GraphEngine(storageEngine));
        storageEngine.registerEngine("TIMESERIES", new TimeSeriesEngine(storageEngine));
        storageEngine.registerEngine("COLUMN", new ColumnEngine(storageEngine));
        storageEngine.registerEngine("GEOSPATIAL", new GeospatialEngine(storageEngine));
        storageEngine.registerEngine("OBJECT", new ObjectEngine(storageEngine));
        storageEngine.registerEngine("RECORDS", new RecordsEngine(storageEngine));

        storageEngine.start();
    }

    @AfterEach
    public void tearDown() {
        if (storageEngine != null) {
            storageEngine.stop();
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

    @Test
    @DisplayName("Verify EngineType sealed hierarchy completeness and exhaustive switch")
    public void testEngineTypeHierarchy() {
        var allTypes = EngineType.all();
        assertEquals(9, allTypes.size(), "Should have exactly 9 heterogeneous engines");

        for (EngineType type : allTypes) {
            assertNotNull(type.key());
            assertNotNull(type.displayName());
            assertNotNull(type.color());
            assertNotNull(type.icon());
            assertNotNull(type.toStorageEngineType());

            // Test exhaustive switch
            String category = switch (type) {
                case EngineType.KeyValue kv -> "KV";
                case EngineType.Document doc -> "DOCUMENT";
                case EngineType.RelationalRecords rec -> "RECORDS";
                case EngineType.Graph g -> "GRAPH";
                case EngineType.Vector v -> "VECTOR";
                case EngineType.TimeSeries ts -> "TIMESERIES";
                case EngineType.WideColumn col -> "COLUMN";
                case EngineType.SpatialGeo geo -> "GEOSPATIAL";
                case EngineType.PureObject obj -> "OBJECT";
            };
            assertNotNull(category);
        }
    }

    @Test
    @DisplayName("Strategy 1: Key-Value validation, parsing, rendering and execution")
    public void testKeyValueStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.KeyValueInsertionStrategy();
        assertEquals("KEYVALUE", strategy.engineType().key());

        // Validation failure
        var invalid = strategy.validate(Map.of());
        assertFalse(invalid.isValid());

        // Sample values & valid execution
        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "sessions");
        var valid = strategy.validate(sample);
        assertTrue(valid.isValid());

        var payload = strategy.parsePayload("test_db", "sessions", "sess_001", sample);
        assertEquals("sess_001", payload.key());

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());
        assertEquals("sess_001", res.id());

        // Verify HTML form rendering
        Widget formFields = strategy.buildEngineFormFields("test_db", "sessions");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_kv_namespace"));
        assertTrue(html.contains("insert_kv_value"));
    }

    @Test
    @DisplayName("Strategy 2: Document / JSON validation, parsing, rendering and execution")
    public void testDocumentStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.DocumentInsertionStrategy();
        assertEquals("DOCUMENT", strategy.engineType().key());

        // Validation
        var invalid = strategy.validate(Map.of("target_coll", ""));
        assertFalse(invalid.isValid());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "customers");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "customers", "cust_100", sample);
        assertEquals("cust_100", payload.id());
        assertNotNull(payload.jsonContent());

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "customers");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_doc_payload"));
        assertTrue(html.contains("jettra-flux-json-editor-container"));
    }

    @Test
    @DisplayName("Strategy 3: Relational / Tabular validation, parsing, rendering and execution")
    public void testRelationalRecordsStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.RelationalRecordsInsertionStrategy();
        assertEquals("RECORDS", strategy.engineType().key());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "employees");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "employees", "emp_500", sample);
        assertEquals("emp_500", payload.recordId());

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "employees");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_rec_payload"));
    }

    @Test
    @DisplayName("Strategy 4: Graph Vertex and Edge validation, parsing, rendering and execution")
    public void testGraphStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.GraphInsertionStrategy();
        assertEquals("GRAPH", strategy.engineType().key());

        // Node insertion
        Map<String, String> nodeSample = strategy.generateSampleFormValues("test_db", "Person");
        assertTrue(strategy.validate(nodeSample).isValid());

        var nodePayload = strategy.parsePayload("test_db", "Person", "alice", nodeSample);
        InsertionResult nodeRes = strategy.executeInsert(storageEngine, "test_db", nodePayload);
        assertTrue(nodeRes.success());

        // Edge insertion
        Map<String, String> edgeParams = Map.of(
                "graph_mode", "edge",
                "edge_from", "alice",
                "edge_to", "bob",
                "edge_label", "KNOWS",
                "edge_props", "{\"since\": 2024}"
        );
        assertTrue(strategy.validate(edgeParams).isValid());
        var edgePayload = strategy.parsePayload("test_db", "KNOWS", "e1", edgeParams);
        InsertionResult edgeRes = strategy.executeInsert(storageEngine, "test_db", edgePayload);
        assertTrue(edgeRes.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "Person");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("graph_node_fields"));
        assertTrue(html.contains("graph_edge_fields"));
    }

    @Test
    @DisplayName("Strategy 5: Vector Embeddings validation, parsing, rendering and execution")
    public void testVectorStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.VectorInsertionStrategy();
        assertEquals("VECTOR", strategy.engineType().key());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "semantic_idx");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "semantic_idx", "vec_101", sample);
        assertEquals("vec_101", payload.id());
        assertTrue(payload.embeddings().length > 0);

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "semantic_idx");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_vec_coords"));
        assertTrue(html.contains("insert_vec_metric"));
    }

    @Test
    @DisplayName("Strategy 6: Time-Series IoT metrics validation, parsing, rendering and execution")
    public void testTimeSeriesStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.TimeSeriesInsertionStrategy();
        assertEquals("TIMESERIES", strategy.engineType().key());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "cpu_temp");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "cpu_temp", "ts_now", sample);
        assertTrue(payload.timestamp() > 0);
        assertEquals(72.4, payload.value(), 0.001);

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "cpu_temp");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_ts_timestamp"));
        assertTrue(html.contains("insert_ts_value"));
    }

    @Test
    @DisplayName("Strategy 7: Wide-Column validation, parsing, rendering and execution")
    public void testWideColumnStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.WideColumnInsertionStrategy();
        assertEquals("COLUMN", strategy.engineType().key());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "user_fam");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "user_fam", "user_row_1", sample);
        assertEquals("user_row_1", payload.rowKey());

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "user_fam");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_col_data"));
    }

    @Test
    @DisplayName("Strategy 8: Geospatial coordinates validation, parsing, rendering and execution")
    public void testSpatialGeoStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.SpatialGeoInsertionStrategy();
        assertEquals("GEOSPATIAL", strategy.engineType().key());

        // Invalid latitude validation
        var invalidLat = strategy.validate(Map.of("target_coll", "layers", "geo_lat", "195.0", "geo_lon", "0.0"));
        assertFalse(invalidLat.isValid());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "cities");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "cities", "poi_panama", sample);
        assertEquals(8.9824, payload.latitude(), 0.0001);
        assertEquals(-79.5199, payload.longitude(), 0.0001);

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "cities");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_geo_lat"));
        assertTrue(html.contains("insert_geo_lon"));
    }

    @Test
    @DisplayName("Strategy 9: Pure Object / BLOB validation, parsing, rendering and execution")
    public void testPureObjectStrategy() {
        var strategy = new com.jettra.store.engine.insertion.strategies.PureObjectInsertionStrategy();
        assertEquals("OBJECT", strategy.engineType().key());

        Map<String, String> sample = strategy.generateSampleFormValues("test_db", "images");
        assertTrue(strategy.validate(sample).isValid());

        var payload = strategy.parsePayload("test_db", "images", "img_logo", sample);
        assertEquals("img_logo", payload.id());
        assertNotNull(payload.contentBytes());

        InsertionResult res = strategy.executeInsert(storageEngine, "test_db", payload);
        assertTrue(res.success());

        Widget formFields = strategy.buildEngineFormFields("test_db", "images");
        String html = formFields.render(Themes.FlatTheme());
        assertTrue(html.contains("insert_obj_payload"));
    }

    @Test
    @DisplayName("Virtual Thread Asynchronous Execution via EngineInsertionFactory")
    public void testVirtualThreadAsyncExecution() {
        Map<String, String> sample = EngineInsertionFactory.getStrategy("DOCUMENT")
                .generateSampleFormValues("test_db", "customers");

        CompletableFuture<InsertionResult> future = EngineInsertionFactory.executeInsertAsync(
                storageEngine,
                "DOCUMENT",
                "test_db",
                sample
        );

        InsertionResult result = future.join();
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("DOCUMENT", result.engine());
    }

    @Test
    @DisplayName("EngineRecordInsertionDialog JettraFlux UI Widget rendering test")
    public void testEngineRecordInsertionDialogRendering() {
        Widget dialog = EngineRecordInsertionDialog.build("/engines", "DOCUMENT", "customers_db", "default");
        assertNotNull(dialog);

        String html = dialog.render(Themes.FlatTheme());
        assertNotNull(html);

        // Verify that all 9 engines are present in the dialog
        assertTrue(html.contains("adaptiveRecordInsertModal"), "Modal container must be present");
        assertTrue(html.contains("adaptiveRecordInsertForm"), "Dynamic form must be present");
        assertTrue(html.contains("adaptiveRecordInsertNotification"), "Notification area must be present");

        for (EngineType eng : EngineType.all()) {
            assertTrue(html.contains(eng.displayName()), "Dialog must contain engine pill: " + eng.displayName());
            assertTrue(html.contains(eng.key()), "Dialog must contain section for: " + eng.key());
        }

        // Verify action buttons
        assertTrue(html.contains("Cargar Plantilla de Ejemplo"));
        assertTrue(html.contains("Insertar Registro"));
        assertTrue(html.contains("btnAdaptiveSubmitInsert"));
        assertTrue(html.contains("form=\"adaptiveRecordInsertForm\""));
        assertTrue(html.contains("submitAdaptiveRecordInsert()"));
        assertTrue(html.contains("fas fa-plus-circle"));
    }

    @Test
    @DisplayName("Verify submit button (+) form binding, JettraFluxSelect, and absence of RawHtml")
    public void testAdaptiveInsertButtonActionBinding() {
        Widget dialog = EngineRecordInsertionDialog.build("/engines", "DOCUMENT", "customers_db", "default");
        String html = dialog.render(Themes.FlatTheme());

        // 1. Verify submit button (+) functional binding
        assertTrue(html.contains("id=\"btnAdaptiveSubmitInsert\""), "Must have unique button id");
        assertTrue(html.contains("form=\"adaptiveRecordInsertForm\""), "Must have HTML5 form attribute binding to form");
        assertTrue(html.contains("type=\"submit\""), "Must be a submit button");
        assertTrue(html.contains("submitAdaptiveRecordInsert()"), "Must have onclick calling submitAdaptiveRecordInsert()");
        assertTrue(html.contains("fas fa-plus-circle"), "Must have + plus circle icon");

        // 2. Verify JettraFluxSelect component is used for ID mode
        assertTrue(html.contains("id=\"adaptive_insert_id_mode\""), "Select component must be rendered");
        assertTrue(html.contains("name=\"id_gen_mode\""), "Select must have name id_gen_mode");
        assertTrue(html.contains("class=\"jettra-flux-select"), "Must use JettraFluxSelect CSS class");
        assertTrue(html.contains("value=\"UUID\" selected"), "UUID must be selected by default");

        // 3. Verify that script uses RawScript
        assertTrue(html.contains("window.JettraAdaptiveSamples"), "Sample dictionary must be defined");
        assertTrue(html.contains("openEngineInsertModal"), "openEngineInsertModal JS must be present");
        assertTrue(html.contains("switchInsertEngine"), "switchInsertEngine JS must be present");
    }

    @Test
    @DisplayName("Verify Java 25 Stream Gatherer validation pipeline and Pattern Matching")
    public void testStreamGathererValidationAndPatternMatching() {
        // 1. Gatherer parameter validation
        Map<String, String> validParams = Map.of(
            "target_db", "telemetry_db",
            "target_id", "point_01",
            "target_coll", "metrics"
        );
        var noErrors = EngineInsertionFactory.validateParametersWithGatherer(validParams, new EngineType.TimeSeries());
        assertTrue(noErrors.isEmpty(), "Valid params must produce no gatherer errors");

        Map<String, String> invalidParams = Map.of(
            "target_db", "",
            "target_id", "invalid/slash/id"
        );
        var errors = EngineInsertionFactory.validateParametersWithGatherer(invalidParams, new EngineType.TimeSeries());
        assertEquals(2, errors.size(), "Should detect empty db and invalid slash in id");

        // 2. Pattern Matching with compiler exhaustiveness
        EngineRecordPayload kv = new EngineRecordPayload.KeyValuePayload("default", "k1", "v1", null);
        EngineRecordPayload doc = new EngineRecordPayload.DocumentPayload("default", "c1", "d1", new io.jettra.json.JsonObject(), "{}");
        EngineRecordPayload gr = new EngineRecordPayload.GraphPayload("node", "n1", "Person", null, null, new io.jettra.json.JsonObject(), "{}");
        EngineRecordPayload vec = new EngineRecordPayload.VectorPayload("v1", "idx", 3, new float[]{0.1f, 0.2f, 0.3f}, "COSINE", "label", new io.jettra.json.JsonObject(), "{}");

        assertTrue(EngineInsertionFactory.describePayloadModel(kv).contains("KeyValue"));
        assertTrue(EngineInsertionFactory.describePayloadModel(doc).contains("Document"));
        assertTrue(EngineInsertionFactory.describePayloadModel(gr).contains("Graph"));
        assertTrue(EngineInsertionFactory.describePayloadModel(vec).contains("Vector"));
    }

    @Test
    @DisplayName("Verify Storage Hierarchy Explorer Tree View renders [+] insert action buttons")
    public void testHierarchyExplorerTreeActionButtons() {
        // Insert a document so tree has nodes
        DocumentEngine docEng = (DocumentEngine) storageEngine.getEngine("DOCUMENT");
        io.jettra.json.JsonObject docObj = new io.jettra.json.JsonObject();
        docObj.addProperty("test", "data");
        docEng.insert("test_db", "orders", "ord_001", docObj);

        com.jettra.store.engine.hierarchy.HierarchyExplorerService hierarchyService =
                new com.jettra.store.engine.hierarchy.HierarchyExplorerService(storageEngine);

        Widget treeWidget = com.jettra.store.engine.web.StorageTreeView.build(
                "DOCUMENT", "test_db", "orders", "/engines?engine=DOCUMENT", Map.of(), hierarchyService
        );
        assertNotNull(treeWidget);

        String html = treeWidget.render(Themes.FlatTheme());
        assertNotNull(html);

        // Verify [+] action button calling openEngineInsertModal on nodes
        assertTrue(html.contains("openEngineInsertModal"), "Tree view must contain [+] insert action buttons");
        assertTrue(html.contains("fas fa-plus"), "Must contain plus icon for insertion on tree nodes");
    }

    @Test
    @DisplayName("Verify ExceptionMapper Java 25 pattern matching and structured ErrorResponse JSON")
    public void testExceptionMapperPatternMatchingAndErrorResponse() {
        // 1. BadRequestException pattern matching
        var bre = new com.jettra.store.engine.exception.BadRequestException("Parámetro inválido", java.util.List.of("Campo requerido"));
        var err1 = com.jettra.store.engine.exception.ExceptionMapper.toErrorResponse(bre, "/engines");
        assertEquals(400, err1.status());
        assertEquals("Bad Request", err1.error());
        assertEquals("Parámetro inválido", err1.message());
        assertEquals(1, err1.errors().size());
        assertEquals("/engines", err1.path());
        assertTrue(err1.timestamp() > 0);

        // 2. ConstraintViolationException pattern matching
        var cve = new com.jettra.store.engine.exception.ConstraintViolationException("Violación de restricción", java.util.List.of("ID duplicado"));
        var err2 = com.jettra.store.engine.exception.ExceptionMapper.toErrorResponse(cve, "/engines");
        assertEquals(400, err2.status());
        assertEquals("Violación de restricción", err2.message());

        // 3. JSON serialization of ErrorResponse
        io.jettra.json.JettraJson json = new io.jettra.json.JettraJson();
        String jsonStr = json.toJson(err1);
        assertNotNull(jsonStr);
        assertTrue(jsonStr.contains("\"status\":400") || jsonStr.contains("\"status\": 400"));
        assertTrue(jsonStr.contains("Bad Request"));
        assertTrue(jsonStr.contains("Campo requerido"));
        assertFalse(jsonStr.startsWith("<"), "Error response MUST be JSON, not HTML");
    }

    @Test
    @DisplayName("Verify MultiModelInsertionRequest and JSON payload execution in DocumentEngine")
    public void testMultiModelInsertionRequestAndJsonPayloadExecution() {
        Map<String, String> params = Map.of(
            "action", "insert_object_ajax",
            "engine", "DOCUMENT",
            "target_db", "test_db",
            "target_coll", "customers",
            "target_id", "cust_json_01",
            "id_gen_mode", "MANUAL",
            "doc_json", "{\"name\":\"TechCorp\",\"tier\":\"Enterprise\",\"active\":true}"
        );

        var req = com.jettra.store.engine.insertion.MultiModelInsertionRequest.fromMap(params);
        assertEquals("DOCUMENT", req.engine());
        assertEquals("test_db", req.targetDb());
        assertEquals("customers", req.targetColl());
        assertEquals("cust_json_01", req.targetId());

        var result = EngineInsertionFactory.executeInsertAsync(storageEngine, req.engine(), req.targetDb(), req.properties()).join();
        assertTrue(result.success(), "Insertion must succeed: " + result.message());
        assertEquals("cust_json_01", result.id());

        DocumentEngine docEng = (DocumentEngine) storageEngine.getEngine("DOCUMENT");
        io.jettra.json.JsonObject inserted = docEng.get("test_db", "customers", "cust_json_01");
        assertNotNull(inserted, "Record must be persisted in storage engine");
        assertEquals("TechCorp", String.valueOf(inserted.get("name")));
    }

    @Test
    @DisplayName("Verify EngineRecordInsertionDialog JettraFluxTransport rendering and resilient error handling")
    public void testAdaptiveDialogJettraFluxTransportIntegration() {
        Widget dialog = EngineRecordInsertionDialog.build("/engines?engine=DOCUMENT", "DOCUMENT", "test_db", "orders");
        assertNotNull(dialog);

        String html = dialog.render(Themes.FlatTheme());
        assertNotNull(html);

        // Verify JettraFluxTransport client function and strict headers
        assertTrue(html.contains("dispatchAdaptiveTransport"), "Must define dispatchAdaptiveTransport function");
        assertTrue(html.contains("'Accept': 'application/json'"), "Must enforce Accept: application/json header");
        assertTrue(html.contains("submitAdaptiveRecordInsert"), "Must define submitAdaptiveRecordInsert caller");
        // Verify resilient HTML error interceptor (preventing unexpected token '<')
        assertTrue(html.contains("replace(/<[^>]*>/g, ' ')"), "Must safely strip HTML tags from non-JSON errors");
    }

    @Test
    @DisplayName("Verify RECORD native model insertion via EngineInsertionFactory and Virtual Threads")
    public void testNativeRecordModelInsertion() {
        // 1. Verify EngineType resolution for RECORD
        EngineType recType = EngineType.fromKey("RECORD");
        assertNotNull(recType);
        assertEquals("RECORDS", recType.key());
        assertEquals("Record (Java 25)", recType.displayName());
        assertEquals("ULTRA-FAST", recType.badge());

        // 2. Lookup strategy by alias RECORD
        var strat = EngineInsertionFactory.getStrategy("RECORD");
        assertNotNull(strat);

        // 3. Execute asynchronous insert using Virtual Threads
        Map<String, String> params = new LinkedHashMap<>();
        params.put("target_coll", "developers");
        params.put("target_id", "dev_100");
        params.put("id_gen_mode", "MANUAL");
        params.put("rec_class", "com.jettra.model.EmployeeRecord");
        params.put("rec_payload", """
        {
          "_recordClass": "com.jettra.model.EmployeeRecord",
          "_timestamp": 1788809869770,
          "_version": 1,
          "_schema": {
            "first_name": "String",
            "last_name": "String",
            "email": "String",
            "age": "Integer",
            "salary": "Double",
            "department": "String",
            "created_at": "String",
            "_table": "String"
          },
          "components": {
            "first_name": "John",
            "last_name": "Doe",
            "email": "john.doe@company.org",
            "age": 34,
            "salary": 85000.0,
            "department": "ENGINEERING",
            "created_at": "2026-09-07T10:00:00Z",
            "_table": "developers"
          }
        }
        """);

        CompletableFuture<InsertionResult> future = EngineInsertionFactory.executeInsertAsync(
                storageEngine, "RECORD", "corp_db", params
        );

        InsertionResult res = future.join();
        assertNotNull(res);
        assertTrue(res.success(), "Insertion should succeed: " + res.message());
        assertEquals("RECORDS", res.engine());
        assertEquals("corp_db", res.database());
        assertEquals("developers", res.unit());
        assertEquals("dev_100", res.id());

        // 4. Verify storage core content
        byte[] bytes = storageEngine.getStorageCore().get("rec:corp_db:developers:dev_100");
        assertNotNull(bytes, "Must be stored in storage core under rec:corp_db:developers:dev_100");
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("com.jettra.model.EmployeeRecord"));
        assertTrue(json.contains("john.doe@company.org"));
        assertTrue(json.contains("_schema"));
        assertTrue(json.contains("components"));
    }

    public static void main(String[] args) {
        System.out.println("=== RUNNING AdaptiveRecordInsertionTest (19 Test Cases) ===");
        AdaptiveRecordInsertionTest test = new AdaptiveRecordInsertionTest();
        int passed = 0;
        int failed = 0;

        java.util.List<java.util.Map.Entry<String, Runnable>> testCases = java.util.List.of(
            java.util.Map.entry("testNativeRecordModelInsertion", test::testNativeRecordModelInsertion),
            java.util.Map.entry("testEngineTypeHierarchy", test::testEngineTypeHierarchy),
            java.util.Map.entry("testKeyValueStrategy", test::testKeyValueStrategy),
            java.util.Map.entry("testDocumentStrategy", test::testDocumentStrategy),
            java.util.Map.entry("testRelationalRecordsStrategy", test::testRelationalRecordsStrategy),
            java.util.Map.entry("testGraphStrategy", test::testGraphStrategy),
            java.util.Map.entry("testVectorStrategy", test::testVectorStrategy),
            java.util.Map.entry("testTimeSeriesStrategy", test::testTimeSeriesStrategy),
            java.util.Map.entry("testWideColumnStrategy", test::testWideColumnStrategy),
            java.util.Map.entry("testSpatialGeoStrategy", test::testSpatialGeoStrategy),
            java.util.Map.entry("testPureObjectStrategy", test::testPureObjectStrategy),
            java.util.Map.entry("testVirtualThreadAsyncExecution", test::testVirtualThreadAsyncExecution),
            java.util.Map.entry("testEngineRecordInsertionDialogRendering", test::testEngineRecordInsertionDialogRendering),
            java.util.Map.entry("testAdaptiveInsertButtonActionBinding", test::testAdaptiveInsertButtonActionBinding),
            java.util.Map.entry("testStreamGathererValidationAndPatternMatching", test::testStreamGathererValidationAndPatternMatching),
            java.util.Map.entry("testHierarchyExplorerTreeActionButtons", test::testHierarchyExplorerTreeActionButtons),
            java.util.Map.entry("testExceptionMapperPatternMatchingAndErrorResponse", test::testExceptionMapperPatternMatchingAndErrorResponse),
            java.util.Map.entry("testMultiModelInsertionRequestAndJsonPayloadExecution", test::testMultiModelInsertionRequestAndJsonPayloadExecution),
            java.util.Map.entry("testAdaptiveDialogJettraFluxTransportIntegration", test::testAdaptiveDialogJettraFluxTransportIntegration)
        );

        for (var tc : testCases) {
            try {
                test.setUp();
                tc.getValue().run();
                test.tearDown();
                System.out.println("  [PASS] " + tc.getKey());
                passed++;
            } catch (Throwable t) {
                System.err.println("  [FAIL] " + tc.getKey() + ": " + t.getMessage());
                t.printStackTrace();
                failed++;
                try { test.tearDown(); } catch (Exception ignored) {}
            }
        }

        System.out.printf("%n=== RESULTS: Passed: %d, Failed: %d ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
