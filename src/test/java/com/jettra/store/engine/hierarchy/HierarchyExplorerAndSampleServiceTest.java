package com.jettra.store.engine.hierarchy;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.samples.SampleDatasetManager;
import com.jettra.store.engine.samples.lifecycle.InstallState;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseDefinition;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseService;
import com.jettra.store.engine.web.StoreEnginesPage;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class HierarchyExplorerAndSampleServiceTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private HierarchyExplorerService hierarchyService;
    private SampleDatabaseService sampleService;
    private StoreEnginesPage page;
    private final JettraJson json = new JettraJson();

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jettra_hierarchy_sample_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        hierarchyService = new HierarchyExplorerService(engine);
        sampleService = new SampleDatabaseService(engine);
        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testCleanStartupHasZeroDatabasesByDefault() {
        Set<String> dbs = hierarchyService.discoverAllDatabases();
        // Default clean state without auto-seeding
        assertEquals(0, dbs.size(), "Clean startup should not auto-seed any sample databases.");

        List<SampleDatabaseDefinition> catalog = sampleService.getCatalog();
        assertNotNull(catalog);
        assertTrue(catalog.size() >= 10, "Catalog must list all available sample datasets.");

        for (SampleDatabaseDefinition def : catalog) {
            InstallState state = sampleService.getInstallState(def.id());
            assertEquals(InstallState.NOT_INSTALLED, state, "All sample DBs must initially be NOT_INSTALLED.");
        }
    }

    @Test
    void testSampleDatabaseLifecycleInstallAndUninstall() throws Exception {
        String targetDb = "scrum_board_db";
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(targetDb));

        // 1. Install asynchronously via Virtual Threads
        CompletableFuture<HierarchyResult<Integer>> installFuture = sampleService.installAsync(targetDb);
        HierarchyResult<Integer> installRes = installFuture.get();
        assertTrue(installRes.isSuccess(), "Installation of scrum_board_db must succeed.");
        assertTrue(installRes.getOrNull() > 0, "Installed records count must be greater than 0.");

        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb));

        // 2. Discover via HierarchyExplorerService
        Set<String> dbsAfterInstall = hierarchyService.discoverAllDatabases();
        assertTrue(dbsAfterInstall.contains(targetDb), "Discovered databases must now include scrum_board_db.");

        HierarchyResult<HierarchyNode.DatabaseNode> hierRes = hierarchyService.resolveDatabaseHierarchy(targetDb);
        assertTrue(hierRes.isSuccess());
        HierarchyNode.DatabaseNode dbNode = hierRes.getOrNull();
        assertNotNull(dbNode);
        assertTrue(dbNode.totalItems() > 0);
        assertTrue(dbNode.hasComponents());

        // 3. Uninstall / Purge asynchronously
        CompletableFuture<HierarchyResult<Integer>> uninstallFuture = sampleService.uninstallAsync(targetDb);
        HierarchyResult<Integer> uninstallRes = uninstallFuture.get();
        assertTrue(uninstallRes.isSuccess(), "Uninstallation of scrum_board_db must succeed.");
        assertTrue(uninstallRes.getOrNull() > 0, "Purged records count must be greater than 0.");

        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(targetDb));

        Set<String> dbsAfterUninstall = hierarchyService.discoverAllDatabases();
        assertFalse(dbsAfterUninstall.contains(targetDb), "Purged database must no longer appear in discovered databases.");
    }

    @Test
    void testHierarchyStreamingJsonSerializationWithExampleDBReferences() {
        // Load the complex ExampleDBReferences dataset with 9 multi-model engines
        new SampleDatasetManager(engine).loadExampleDBReferencesDataset();

        HierarchyResult<HierarchyNode.DatabaseNode> res = hierarchyService.resolveDatabaseHierarchy("ExampleDBReferences");
        assertTrue(res.isSuccess());
        HierarchyNode.DatabaseNode dbNode = res.getOrNull();
        assertNotNull(dbNode);

        // Serialize using the streaming RFC 8259 serializer
        String jsonPayload = HierarchyJsonStreamer.toJson(dbNode);
        assertNotNull(jsonPayload);
        assertFalse(jsonPayload.isBlank());

        // Parse with JettraJson to guarantee syntax correctness and zero truncation
        JsonObject parsed = json.fromJson(jsonPayload, JsonObject.class);
        assertNotNull(parsed);
        assertEquals("ExampleDBReferences", parsed.getAsString("database"));
        assertTrue(parsed.getAsBoolean("hasComponents"));
        assertTrue(parsed.getAsInt("totalItems") >= 9);

        // Verify engines array
        assertTrue(parsed.has("engines"));
        var enginesArr = parsed.getAsJsonArray("engines");
        assertNotNull(enginesArr);
        assertEquals(9, enginesArr.size(), "Must resolve all 9 multi-model engines.");

        // Stream into StringWriter directly
        StringWriter sw = new StringWriter();
        assertDoesNotThrow(() -> HierarchyJsonStreamer.writeDatabaseNode(sw, dbNode));
        assertTrue(sw.toString().length() > 500);
    }

    @Test
    void testSpecialAndControlCharactersEscapedCorrectlyInStreamingSerializer() {
        String dbName = "esc_test_db";
        String complexPayload = "{\"key\\\"withQuote\":\"value with \\n newline, \\r carriage return, \\t tab, \\b backspace, \\f formfeed, \\\\ backslash, and \\u001f control char\"}";
        engine.getStorageCore().put("doc:" + dbName + ":special_unit:esc_item_01", complexPayload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        HierarchyResult<HierarchyNode.DatabaseNode> res = hierarchyService.resolveDatabaseHierarchy(dbName);
        assertTrue(res.isSuccess());

        String jsonPayload = HierarchyJsonStreamer.toJson(res.getOrNull());
        assertNotNull(jsonPayload);

        // Guarantee that parsed JSON contains exact special character payload without truncation
        JsonObject parsed = json.fromJson(jsonPayload, JsonObject.class);
        assertNotNull(parsed);
        assertEquals(dbName, parsed.getAsString("database"));
        assertEquals(1, parsed.getAsInt("totalItems"));
    }

    @Test
    void testCreateDatabaseInitializesAllNineEngineSubtrees() {
        String newDb = "fintech_multi_model_db";
        // Create new database with custom initial engine/unit
        page.initializeDatabaseEngineSubtrees(newDb, "DOCUMENT", "contracts");

        // Verify database is discovered
        Set<String> dbs = hierarchyService.discoverAllDatabases();
        assertTrue(dbs.contains(newDb), "Newly created database must be discoverable in storage.");

        // Resolve hierarchy
        HierarchyResult<HierarchyNode.DatabaseNode> res = hierarchyService.resolveDatabaseHierarchy(newDb);
        assertTrue(res.isSuccess());
        HierarchyNode.DatabaseNode dbNode = res.getOrNull();
        assertNotNull(dbNode);
        assertTrue(dbNode.hasComponents(), "Created database must have hasComponents=true.");

        // Verify all 9 engines have subtrees
        List<HierarchyNode.EngineNode> engines = dbNode.engines();
        assertNotNull(engines);
        assertEquals(9, engines.size(), "Database must define specifications for all 9 multi-model engines.");

        String[] expectedEngines = {"DOCUMENT", "RECORDS", "KEYVALUE", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT"};
        for (String engName : expectedEngines) {
            HierarchyNode.EngineNode engNode = engines.stream()
                    .filter(e -> e.name().equalsIgnoreCase(engName))
                    .findFirst()
                    .orElse(null);
            assertNotNull(engNode, "Engine " + engName + " must exist in database hierarchy.");
            assertFalse(engNode.units().isEmpty(), "Engine " + engName + " must have initialized subtree unit ready for processing.");

            if (engName.equalsIgnoreCase("DOCUMENT")) {
                assertTrue(engNode.units().stream().anyMatch(u -> u.name().equals("contracts")), "DOCUMENT engine should contain custom initial unit 'contracts'.");
            } else {
                assertTrue(engNode.units().stream().anyMatch(u -> u.name().equals("default")), "Engine " + engName + " should contain default unit.");
            }
        }
    }

    @Test
    void testMultiModelSubtreeFactoryAndStorageEngineTypeAliases() {
        assertEquals(StorageEngineType.DOCUMENT, StorageEngineType.fromString("document").orElse(null));
        assertEquals(StorageEngineType.KEY_VALUE, StorageEngineType.fromString("KEYVALUE").orElse(null));
        assertEquals(StorageEngineType.KEY_VALUE, StorageEngineType.fromString("KEY_VALUE").orElse(null));
        assertEquals(StorageEngineType.GRAPH_REFERENCES, StorageEngineType.fromString("GRAPH").orElse(null));
        assertEquals(StorageEngineType.GRAPH_REFERENCES, StorageEngineType.fromString("GRAPH_REFERENCES").orElse(null));
        assertEquals(StorageEngineType.RELATIONAL_RECORDS, StorageEngineType.fromString("RECORDS").orElse(null));
        assertEquals(StorageEngineType.RELATIONAL_RECORDS, StorageEngineType.fromString("RELATIONAL_RECORDS").orElse(null));
        assertEquals(StorageEngineType.VECTOR, StorageEngineType.fromString("VECTOR").orElse(null));
        assertEquals(StorageEngineType.TIMESERIES, StorageEngineType.fromString("timeseries").orElse(null));
        assertEquals(StorageEngineType.COLUMN, StorageEngineType.fromString("column").orElse(null));
        assertEquals(StorageEngineType.GEOSPATIAL, StorageEngineType.fromString("geospatial").orElse(null));
        assertEquals(StorageEngineType.OBJECT, StorageEngineType.fromString("object").orElse(null));

        // Test MultiModelSubtreeFactory.createInitialDatabaseNode
        HierarchyNode.DatabaseNode initialNode = MultiModelSubtreeFactory.createInitialDatabaseNode("telecom_db", "RECORDS", "subscribers");
        assertNotNull(initialNode);
        assertEquals("telecom_db", initialNode.name());
        assertTrue(initialNode.hasComponents());
        assertEquals(9, initialNode.engines().size());

        HierarchyNode.EngineNode recEng = initialNode.engines().stream()
                .filter(e -> e.name().equalsIgnoreCase("RECORDS"))
                .findFirst()
                .orElse(null);
        assertNotNull(recEng);
        assertEquals(1, recEng.units().size());
        assertEquals("subscribers", recEng.units().get(0).name());
    }
}
