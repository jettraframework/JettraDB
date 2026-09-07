package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.*;
import com.jettra.store.engine.web.EngineRecordInsertionDialog;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

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
    }

    public static void main(String[] args) {
        System.out.println("=== RUNNING AdaptiveRecordInsertionTest (12 Test Cases) ===");
        AdaptiveRecordInsertionTest test = new AdaptiveRecordInsertionTest();
        int passed = 0;
        int failed = 0;

        java.util.List<java.util.Map.Entry<String, Runnable>> testCases = java.util.List.of(
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
            java.util.Map.entry("testEngineRecordInsertionDialogRendering", test::testEngineRecordInsertionDialogRendering)
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
