package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.insertion.EngineInsertionFactory;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.strategies.GraphInsertionStrategy;
import com.jettra.store.engine.insertion.strategies.TimeSeriesInsertionStrategy;
import com.jettra.store.engine.insertion.strategies.VectorInsertionStrategy;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.GraphEngine;
import com.jettra.store.engine.models.TimeSeriesEngine;
import com.jettra.store.engine.models.VectorEngine;
import com.jettra.store.engine.test.TestDatabaseCleanup;
import com.jettra.store.engine.web.dialog.EngineRecordInsertionDialog;
import com.jettra.store.engine.web.page.StoreEnginesPage;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JsonObject;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class MultiModelExtensionAndVersioningTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private DocumentEngine docEngine;
    private TimeSeriesEngine tsEngine;
    private GraphEngine graphEngine;
    private VectorEngine vecEngine;
    private HierarchyExplorerService hierarchyService;
    private StoreEnginesPage page;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_multimodel_test");
        engine = new JettraStorageEngine(tempDir.toString());

        docEngine = new DocumentEngine(engine);
        tsEngine = new TimeSeriesEngine(engine);
        graphEngine = new GraphEngine(engine);
        vecEngine = new VectorEngine(engine);

        engine.registerEngine("DOCUMENT", docEngine);
        engine.registerEngine("TIMESERIES", tsEngine);
        engine.registerEngine("GRAPH", graphEngine);
        engine.registerEngine("VECTOR", vecEngine);

        engine.start();

        hierarchyService = new HierarchyExplorerService(engine);
        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    public void tearDown() {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. Document insertion should result strictly in Version 1 (V1), without spurious V2")
    public void testDocumentInitialVersioningIsStrictlyV1() {
        String db = "customers_db";
        String coll = "clients";
        String docId = "client_v1_test";

        Map<String, String> params = new HashMap<>();
        params.put("target_coll", coll);
        params.put("target_id", docId);
        params.put("id_gen_mode", "MANUAL");
        params.put("doc_payload", "{\"name\":\"Initial Client\",\"active\":true}");

        InsertionResult res = EngineInsertionFactory.executeInsertAsync(engine, "DOCUMENT", db, params).join();
        assertTrue(res.success(), "Document insertion should succeed: " + res.message());

        String key = db + ":" + coll + ":" + docId;
        int coreVersions = engine.getStorageCore().getVersionCount(key);
        assertEquals(1, coreVersions, "Initial document insert must register strictly 1 version in storage core");

        int explorerVersions = hierarchyService.getItemVersionCount("DOCUMENT", db, coll, docId);
        assertEquals(1, explorerVersions, "Explorer service must report strictly version 1 for newly inserted document");

        String payload = hierarchyService.getItemPayload("DOCUMENT", db, coll, docId);
        assertTrue(payload.contains("Initial Client"), "Item payload must reflect the inserted content");
    }

    @JettraTest
    @DisplayName("2. Vector sample template values should load and insert without JSON syntax errors")
    public void testVectorSampleTemplateLoadingAndInsertion() {
        VectorInsertionStrategy strategy = new VectorInsertionStrategy();
        Map<String, String> sampleValues = strategy.generateSampleFormValues("ai_db", "embeddings");

        assertNotNull(sampleValues);
        assertEquals("COSINE", sampleValues.get("vector_metric"));
        assertNotNull(sampleValues.get("vector_coords"));
        assertNotNull(sampleValues.get("vector_meta"));

        // Validate that vector_meta is valid JSON syntax
        try {
            new io.jettra.json.JettraJson().fromJson(sampleValues.get("vector_meta"), JsonObject.class);
        } catch (Exception e) {
            fail("Vector sample template vector_meta must be valid JSON: " + e.getMessage());
        }

        // Validate insertion with sample values
        InsertionResult res = EngineInsertionFactory.executeInsertAsync(engine, "VECTOR", "ai_db", sampleValues).join();
        assertTrue(res.success(), "Vector insertion using sample values should succeed: " + res.message());

        // Verify that coords textarea is not treated as a JSON editor in rendered HTML
        Widget formFields = strategy.buildEngineFormFields("ai_db", "embeddings");
        String html = formFields.render(Themes.DarkTheme());
        assertTrue(html.contains("id=\"insert_vec_coords\""), "Coords textarea must have id insert_vec_coords");
        assertTrue(html.contains("name=\"vector_coords\""), "Coords textarea must have name vector_coords");
        assertTrue(!html.contains("id=\"insert_vec_coords_input\""), "Plain coords textarea must not have _input suffix");
    }

    @JettraTest
    @DisplayName("3. TimeSeries multi-model full support: insertion, hierarchy discovery, versioning and editing")
    public void testTimeSeriesMultiModelSupport() {
        String db = "iot_db";
        String metric = "server_temperature";
        long timestamp = 1726915200000L;

        Map<String, String> params = new HashMap<>();
        params.put("target_coll", metric);
        params.put("ts_value", "72.4");
        params.put("ts_unit", "°C");
        params.put("ts_timestamp", String.valueOf(timestamp));
        params.put("ts_tags", "{\"datacenter\":\"us-east-1\",\"rack\":4}");

        InsertionResult res = EngineInsertionFactory.executeInsertAsync(engine, "TIMESERIES", db, params).join();
        assertTrue(res.success(), "TimeSeries point insertion should succeed: " + res.message());

        String idStr = String.valueOf(timestamp);
        int vCount = hierarchyService.getItemVersionCount("TIMESERIES", db, metric, idStr);
        assertEquals(1, vCount, "Initial timeseries point should be Version 1");

        String payload = hierarchyService.getItemPayload("TIMESERIES", db, metric, idStr);
        assertTrue(payload.contains("72.4"), "Payload must contain metric value 72.4");
        assertTrue(payload.contains("us-east-1"), "Payload must contain tag datacenter");

        // Hierarchy discovery
        Map<String, List<String>> units = hierarchyService.discoverUnitsAndItems("TIMESERIES", db);
        assertTrue(units.containsKey(metric), "Hierarchy must discover the metric series");
        assertTrue(units.get(metric).contains(idStr), "Series must contain the timestamp data point");

        // Edit timeseries point -> should create Version 2
        Map<String, String> editParams = new HashMap<>();
        editParams.put("ts_value", "75.1");
        editParams.put("ts_unit", "°C");
        editParams.put("ts_tags", "{\"datacenter\":\"us-east-1\",\"rack\":4,\"status\":\"ALERT\"}");

        EditDocumentCommand cmd = EditDocumentCommand.of("TIMESERIES", db, metric, idStr, editParams.get("ts_tags"), editParams);
        EditActionHandler editHandler = new EditActionHandler(engine, hierarchyService);
        EditDocumentResult editRes = editHandler.executeEdit(cmd);

        assertTrue(editRes.success(), "Editing timeseries record should succeed: " + editRes.message());
        assertEquals(2, editRes.versionCount(), "Editing timeseries record must increment to Version 2");

        int afterCount = hierarchyService.getItemVersionCount("TIMESERIES", db, metric, idStr);
        assertEquals(2, afterCount, "Hierarchy explorer must report Version 2 after edit");
    }

    @JettraTest
    @DisplayName("4. Graph multi-model full support: vertices, edges, hierarchy discovery, versioning and editing")
    public void testGraphMultiModelSupport() {
        String db = "social_db";
        String label = "Person";
        String nodeId = "vertex_bob_01";

        Map<String, String> nodeParams = new HashMap<>();
        nodeParams.put("graph_mode", "node");
        nodeParams.put("id_gen_mode", "MANUAL");
        nodeParams.put("target_coll", label);
        nodeParams.put("node_label", label);
        nodeParams.put("target_id", nodeId);
        nodeParams.put("node_props", "{\"name\":\"Bob Builder\",\"age\":38}");

        InsertionResult nodeRes = EngineInsertionFactory.executeInsertAsync(engine, "GRAPH", db, nodeParams).join();
        assertTrue(nodeRes.success(), "Graph node insertion should succeed: " + nodeRes.message());

        int vCount = hierarchyService.getItemVersionCount("GRAPH", db, label, nodeId);
        assertEquals(1, vCount, "Initial graph vertex should be Version 1");

        String nodePayload = hierarchyService.getItemPayload("GRAPH", db, label, nodeId);
        assertTrue(nodePayload.contains("Bob Builder"), "Node payload must contain properties");

        // Insert edge
        Map<String, String> edgeParams = new HashMap<>();
        edgeParams.put("graph_mode", "edge");
        edgeParams.put("edge_from", "vertex_alice_01");
        edgeParams.put("edge_to", nodeId);
        edgeParams.put("edge_label", "FRIENDS_WITH");
        edgeParams.put("edge_props", "{\"since\":2022,\"strength\":0.9}");

        InsertionResult edgeRes = EngineInsertionFactory.executeInsertAsync(engine, "GRAPH", db, edgeParams).join();
        assertTrue(edgeRes.success(), "Graph edge insertion should succeed: " + edgeRes.message());

        // Edit vertex -> should create Version 2
        Map<String, String> editParams = new HashMap<>();
        editParams.put("node_label", label);
        editParams.put("node_props", "{\"name\":\"Bob Builder\",\"age\":39,\"promoted\":true}");

        EditDocumentCommand cmd = EditDocumentCommand.of("GRAPH", db, label, nodeId, editParams.get("node_props"), editParams);
        EditActionHandler editHandler = new EditActionHandler(engine, hierarchyService);
        EditDocumentResult editRes = editHandler.executeEdit(cmd);

        assertTrue(editRes.success(), "Editing graph vertex should succeed: " + editRes.message());
        assertEquals(2, editRes.versionCount(), "Editing graph vertex must increment to Version 2");

        int afterCount = hierarchyService.getItemVersionCount("GRAPH", db, label, nodeId);
        assertEquals(2, afterCount, "Hierarchy explorer must report Version 2 for vertex after edit");
    }

    @JettraTest
    @DisplayName("5. JettraFlux UI Dialog rendering includes all multi-model engines without syntax errors")
    public void testJettraFluxDialogRendersAllEngines() {
        Widget dialog = EngineRecordInsertionDialog.build("/engine", "DOCUMENT", "customers_db", "default");
        assertNotNull(dialog, "EngineRecordInsertionDialog must be non-null");

        String html = dialog.render(Themes.DarkTheme());
        assertTrue(html.contains("adaptiveRecordInsertModal"), "Modal must contain adaptiveRecordInsertModal");
        assertTrue(html.contains("DOCUMENT"), "Dialog must contain DOCUMENT engine tab");
        assertTrue(html.contains("VECTOR"), "Dialog must contain VECTOR engine tab");
        assertTrue(html.contains("TIMESERIES"), "Dialog must contain TIMESERIES engine tab");
        assertTrue(html.contains("GRAPH"), "Dialog must contain GRAPH engine tab");
        assertTrue(html.contains("loadSampleForActiveEngine"), "Dialog must contain sample loading logic");

        // Verify that only true JSON editors (_input) trigger syntax validation
        assertTrue(html.contains("input.id.endsWith('_input')"), "Dialog JS must check for _input suffix on JSON editors");
    }
}
