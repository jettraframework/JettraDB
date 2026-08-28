package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.ThemeData;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and Integration Test Suite verifying strict lazy loading, on-demand
 * hierarchy resolution, and reliable expansion toggle handling for the JettraStoreEngine Web Explorer.
 */
public class HierarchyExplorerLazyLoadTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage page;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_lazy_hierarchy_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();
        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
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
    void testEmptyDatabaseReturnsNoCollectionsOrEnginesRegisteredState() {
        String dbName = "clean_empty_db";
        JsonObject hierarchy = page.buildDatabaseHierarchyJson(dbName);

        assertNotNull(hierarchy);
        assertEquals(dbName, hierarchy.getAsString("database"));
        assertEquals("SUCCESS", hierarchy.getAsString("status"));
        assertEquals(0, hierarchy.getAsInt("totalItems"));
        assertFalse(hierarchy.getAsBoolean("hasComponents"),
                "Empty database without records/schemas/custom indexes must have hasComponents=false to render empty state.");

        JsonArray engines = hierarchy.getAsJsonArray("engines");
        assertNotNull(engines);
        assertEquals(9, engines.size(), "Should define specifications for all 9 multi-model engines.");

        for (int i = 0; i < engines.size(); i++) {
            JsonObject eng = engines.getAsJsonObject(i);
            assertEquals(0, eng.getAsInt("totalItems"));
        }
    }

    @Test
    void testIsolatedOnDemandChildMetadataResolutionForTargetDatabase() {
        String dbAlpha = "db_alpha";
        String dbBeta = "db_beta";

        // Insert items into dbAlpha
        engine.getStorageCore().put("doc:" + dbAlpha + ":orders:ord_1001",
                "{\"orderId\":\"ord_1001\",\"total\":150.50,\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());
        engine.getStorageCore().put("doc:" + dbAlpha + ":orders:ord_1002",
                "{\"orderId\":\"ord_1002\",\"total\":89.00,\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        // Insert items into dbBeta
        engine.getStorageCore().put("rec:" + dbBeta + ":employees:emp_9001",
                "{\"_recordClass\":\"com.enterprise.Employee\",\"id\":\"emp_9001\",\"name\":\"Alice Smith\"}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        // 1. Resolve hierarchy strictly for dbAlpha
        JsonObject alphaHierarchy = page.buildDatabaseHierarchyJson(dbAlpha);
        assertNotNull(alphaHierarchy);
        assertEquals(dbAlpha, alphaHierarchy.getAsString("database"));
        assertTrue(alphaHierarchy.getAsBoolean("hasComponents"));
        assertEquals(2, alphaHierarchy.getAsInt("totalItems"));

        JsonArray alphaEngines = alphaHierarchy.getAsJsonArray("engines");
        JsonObject alphaDocEngine = null;
        JsonObject alphaRecEngine = null;
        for (int i = 0; i < alphaEngines.size(); i++) {
            JsonObject eng = alphaEngines.getAsJsonObject(i);
            if ("DOCUMENT".equals(eng.getAsString("name"))) {
                alphaDocEngine = eng;
            } else if ("RECORDS".equals(eng.getAsString("name"))) {
                alphaRecEngine = eng;
            }
        }

        assertNotNull(alphaDocEngine);
        assertEquals(2, alphaDocEngine.getAsInt("totalItems"));
        JsonArray alphaDocUnits = alphaDocEngine.getAsJsonArray("units");
        assertEquals(1, alphaDocUnits.size());
        JsonObject ordersUnit = alphaDocUnits.getAsJsonObject(0);
        assertEquals("orders", ordersUnit.getAsString("name"));
        assertEquals(2, ordersUnit.getAsInt("totalItems"));

        // Verify isolation: dbAlpha MUST NOT contain emp_9001 from dbBeta
        assertNotNull(alphaRecEngine);
        assertEquals(0, alphaRecEngine.getAsInt("totalItems"));

        // 2. Resolve hierarchy strictly for dbBeta
        JsonObject betaHierarchy = page.buildDatabaseHierarchyJson(dbBeta);
        assertNotNull(betaHierarchy);
        assertEquals(dbBeta, betaHierarchy.getAsString("database"));
        assertTrue(betaHierarchy.getAsBoolean("hasComponents"));
        assertEquals(1, betaHierarchy.getAsInt("totalItems"));

        JsonArray betaEngines = betaHierarchy.getAsJsonArray("engines");
        JsonObject betaRecEngine = null;
        JsonObject betaDocEngine = null;
        for (int i = 0; i < betaEngines.size(); i++) {
            JsonObject eng = betaEngines.getAsJsonObject(i);
            if ("RECORDS".equals(eng.getAsString("name"))) {
                betaRecEngine = eng;
            } else if ("DOCUMENT".equals(eng.getAsString("name"))) {
                betaDocEngine = eng;
            }
        }

        assertNotNull(betaRecEngine);
        assertEquals(1, betaRecEngine.getAsInt("totalItems"));
        JsonArray betaRecUnits = betaRecEngine.getAsJsonArray("units");
        assertEquals(1, betaRecUnits.size());
        assertEquals("employees", betaRecUnits.getAsJsonObject(0).getAsString("name"));

        // Verify isolation: dbBeta MUST NOT contain ord_1001 or ord_1002 from dbAlpha
        assertNotNull(betaDocEngine);
        assertEquals(0, betaDocEngine.getAsInt("totalItems"));
    }

    @Test
    void testMultiModelResolutionWithVersionCountsAndBase64Payloads() {
        String dbName = "multimodel_prod_db";

        // Version 1 insert
        String itemKey = "rec:" + dbName + ":users:usr_01";
        String payloadV1 = "{\"id\":\"usr_01\",\"username\":\"john_doe\",\"active\":true}";
        engine.getStorageCore().put(itemKey, payloadV1.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // Version 2 update
        String payloadV2 = "{\"id\":\"usr_01\",\"username\":\"john_doe\",\"active\":false,\"role\":\"ADMIN\"}";
        engine.getStorageCore().put(itemKey, payloadV2.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis() + 1000);

        JsonObject hierarchy = page.buildDatabaseHierarchyJson(dbName);
        assertTrue(hierarchy.getAsBoolean("hasComponents"));

        JsonArray engines = hierarchy.getAsJsonArray("engines");
        JsonObject recordsEngine = null;
        for (int i = 0; i < engines.size(); i++) {
            if ("RECORDS".equals(engines.getAsJsonObject(i).getAsString("name"))) {
                recordsEngine = engines.getAsJsonObject(i);
                break;
            }
        }

        assertNotNull(recordsEngine);
        JsonArray units = recordsEngine.getAsJsonArray("units");
        assertEquals(1, units.size());
        JsonObject usersUnit = units.getAsJsonObject(0);
        JsonArray items = usersUnit.getAsJsonArray("items");
        assertEquals(1, items.size());

        JsonObject item = items.getAsJsonObject(0);
        assertEquals("usr_01", item.getAsString("id"));
        assertTrue(item.getAsInt("versionCount") >= 2, "Version count should reflect historical snapshots.");

        // Validate payload base64 encoding
        String payloadB64 = item.getAsString("payloadB64");
        assertNotNull(payloadB64);
        String decodedPayload = new String(Base64.getDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        assertTrue(decodedPayload.contains("usr_01"));
        assertTrue(decodedPayload.contains("john_doe"));

        // Validate versions base64 encoding
        String versionsB64 = item.getAsString("versionsB64");
        assertNotNull(versionsB64);
        String decodedVersions = new String(Base64.getDecoder().decode(versionsB64), StandardCharsets.UTF_8);
        assertTrue(decodedVersions.contains("usr_01") || decodedVersions.startsWith("["));
    }

    @Test
    void testIndexesAndSchemasScopedResolution() {
        String dbName = "indexed_schema_db";

        // Insert schema
        String schemaJson = "{\"type\":\"object\",\"required\":[\"sku\",\"price\"]}";
        engine.getStorageCore().put("schema:" + dbName + ":ProductSchema",
                schemaJson.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        // Insert custom index
        String indexMeta = "{\"name\":\"idx_product_sku\",\"field\":\"sku\",\"type\":\"BTREE\",\"collection\":\"products\"}";
        engine.getStorageCore().put("idx:" + dbName + ":idx_product_sku",
                indexMeta.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        JsonObject hierarchy = page.buildDatabaseHierarchyJson(dbName);
        assertTrue(hierarchy.getAsBoolean("hasComponents"));

        // Verify schemas
        JsonArray schemas = hierarchy.getAsJsonArray("schemas");
        assertNotNull(schemas);
        assertEquals(1, schemas.size());
        JsonObject scObj = schemas.getAsJsonObject(0);
        assertEquals("ProductSchema", scObj.getAsString("name"));
        assertNotNull(scObj.getAsString("schemaB64"));

        // Verify indexes
        JsonArray indexes = hierarchy.getAsJsonArray("indexes");
        assertNotNull(indexes);
        boolean foundCustomIndex = false;
        for (int i = 0; i < indexes.size(); i++) {
            JsonObject idx = indexes.getAsJsonObject(i);
            if ("idx_product_sku".equals(idx.getAsString("name"))) {
                foundCustomIndex = true;
                assertEquals("sku", idx.getAsString("field"));
                assertEquals("products", idx.getAsString("collection"));
            }
        }
        assertTrue(foundCustomIndex, "Custom index idx_product_sku should be resolved in database hierarchy.");
    }

    @Test
    void testTreeCardRenderIncludesProperExpansionButtonAttributesAndHandlers() {
        String dbName = "interactive_db";
        engine.getStorageCore().put("doc:" + dbName + ":orders:ord_1",
                "{\"item\":\"laptop\"}".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", dbName);

        Widget contentWidget = page.buildContent(null, params, "dark");
        assertNotNull(contentWidget);

        String html = contentWidget.render(io.jettra.flux.theme.Themes.FlatTheme());
        assertNotNull(html);

        // 1. Verify toggle button attributes
        assertTrue(html.contains("btn_toggle_"), "Rendered tree must contain explicit toggle button elements.");
        assertTrue(html.contains("toggleLazyDbSubtree(event,"), "Expansion button must bind toggleLazyDbSubtree with event.");
        assertTrue(html.contains("aria-expanded=\"false\""), "Initial collapsed state must have aria-expanded=false.");
        assertTrue(html.contains("role=\"treeitem\""), "Tree node header must declare role=treeitem for accessibility.");
        assertTrue(html.contains("data-state=\"collapsed\""), "Initial node state must be collapsed.");
        assertTrue(html.contains("data-loaded=\"false\""), "Initial node must mark data-loaded=false.");

        // 2. Verify subtree container existence and collapsed display
        assertTrue(html.contains("db-subtree-container"), "Must render lazy subtree container.");
        assertTrue(html.contains("display:none"), "Lazy subtree container must initially be hidden.");

        // 3. Verify client script definitions
        assertTrue(html.contains("dbHierarchyCache"), "Page script must declare dbHierarchyCache.");
        assertTrue(html.contains("buildHierarchyFetchUrl"), "Page script must define buildHierarchyFetchUrl.");
        assertTrue(html.contains("loadDbHierarchy"), "Page script must define loadDbHierarchy.");
    }

    @Test
    void testExampleDBReferencesJsonSerializationIsValidAndParsable() {
        new com.jettra.store.engine.samples.SampleDatasetManager(engine).loadExampleDBReferencesDataset();

        for (String dbName : new String[]{"ExampleDBReferences", "ExampleDBReference"}) {
            JsonObject hierarchy = page.buildDatabaseHierarchyJson(dbName);
            assertNotNull(hierarchy);
            assertTrue(hierarchy.getAsBoolean("hasComponents"));

            io.jettra.json.JettraJson serializer = new io.jettra.json.JettraJson();
            String jsonOutput = serializer.toJson(hierarchy);
            assertNotNull(jsonOutput);
            assertFalse(jsonOutput.isBlank());

            // Validate that JettraJson itself parses it cleanly without syntax errors
            JsonObject parsedObj = serializer.fromJson(jsonOutput, JsonObject.class);
            assertNotNull(parsedObj, "Parsed object must not be null for " + dbName);
            assertEquals(dbName, parsedObj.getAsString("database"));
            assertTrue(parsedObj.getAsBoolean("hasComponents"));
            assertTrue(parsedObj.getAsInt("totalItems") > 0);
        }
    }

    @Test
    void testSpecialCharactersAndControlCharactersInMetadataKeysAndValues() {
        String dbName = "special_char_db";

        // Insert record with special characters, quotes in keys and values, backslashes, and control characters
        String payload = "{\"complex\\\"Key\":\"value with \\\"quotes\\\" and \\\\backslashes\\\\ and \\nnewlines and \\u0007bell\"," +
                "\"nested\":{\"quoted\\\"sub\":\"subVal\\n123\"}," +
                "\"arr\":[\"elem1\\\"withQuote\", 123.45, true]}";

        engine.getStorageCore().put("doc:" + dbName + ":special_unit:item_spec_01",
                payload.getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());

        JsonObject hierarchy = page.buildDatabaseHierarchyJson(dbName);
        assertNotNull(hierarchy);
        assertTrue(hierarchy.getAsBoolean("hasComponents"));

        io.jettra.json.JettraJson serializer = new io.jettra.json.JettraJson();
        String jsonOutput = serializer.toJson(hierarchy);
        assertNotNull(jsonOutput);

        // Verify that JSON parsing succeeds without "Expected ':' after property name"
        JsonObject parsed = serializer.fromJson(jsonOutput, JsonObject.class);
        assertNotNull(parsed);
        assertEquals(dbName, parsed.getAsString("database"));
        assertEquals("SUCCESS", parsed.getAsString("status"));
    }
}


