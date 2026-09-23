package com.jettra.store.engine.samples;

import com.jettra.store.engine.samples.lifecycle.SampleDatabaseDefinition;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseService;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest suite validating the architectural naming standard:
 * All sample databases must strictly start with the prefix "Example<nombre>".
 */
@NotRequiresRunningServer
public class SampleDatabaseNamingPolicyTest {

    @JettraTest
    @DisplayName("1. Naming Policy: All Catalog databases strictly start with the prefix Example<nombre>")
    void testCatalogDatabasesFollowNamingStandard() {
        for (SampleDatabaseDefinition def : SampleDatabaseService.CATALOG) {
            String dbName = def.databaseName();
            assertTrue(SampleDatabaseNamingPolicy.isValidSampleDatabaseName(dbName),
                    "Database '" + dbName + "' must comply with the Example<nombre> naming standard");
            assertTrue(dbName.startsWith("Example"),
                    "Database '" + dbName + "' must strictly start with 'Example'");
            assertFalse(dbName.equals("Example"),
                    "Database name must not be solely 'Example'");
        }
    }

    @JettraTest
    @DisplayName("2. Naming Policy: AVAILABLE_DATASETS whitelist strictly starts with Example<nombre>")
    void testAvailableDatasetsFollowNamingStandard() {
        for (SampleDatasetManager.DatasetInfo info : SampleDatasetManager.AVAILABLE_DATASETS) {
            String dbName = info.databaseName();
            assertTrue(SampleDatabaseNamingPolicy.isValidSampleDatabaseName(dbName),
                    "Dataset database '" + dbName + "' must start with 'Example<nombre>'");
            assertTrue(dbName.startsWith("Example"));
        }
    }

    @JettraTest
    @DisplayName("3. Naming Policy: Validation throws exception for non-conforming names")
    void testValidationRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () ->
            SampleDatabaseNamingPolicy.validateOrThrow("hr_enterprise_db")
        );
        assertThrows(IllegalArgumentException.class, () ->
            SampleDatabaseNamingPolicy.validateOrThrow("meteorology_iot_db")
        );
        assertThrows(IllegalArgumentException.class, () ->
            SampleDatabaseNamingPolicy.validateOrThrow("sample_db")
        );
        assertThrows(IllegalArgumentException.class, () ->
            SampleDatabaseNamingPolicy.validateOrThrow("")
        );
        assertThrows(IllegalArgumentException.class, () ->
            SampleDatabaseNamingPolicy.validateOrThrow(null)
        );
    }

    @JettraTest
    @DisplayName("4. Naming Policy: Canonicalization maps legacy aliases to standard Example<nombre> names")
    void testCanonicalizationWithAliases() {
        assertEquals("ExampleHrEnterpriseDb", SampleDatabaseNamingPolicy.canonicalize("hr_enterprise_db"));
        assertEquals("ExampleMeteorologyIotDb", SampleDatabaseNamingPolicy.canonicalize("meteorology_iot_db"));
        assertEquals("ExampleEcommerceOlapDb", SampleDatabaseNamingPolicy.canonicalize("ecommerce_olap_db"));
        assertEquals("ExampleScrumBoardDb", SampleDatabaseNamingPolicy.canonicalize("scrum_board_db"));
        assertEquals("ExampleSmartCityGisDb", SampleDatabaseNamingPolicy.canonicalize("smart_city_gis_db"));
        assertEquals("ExampleDBReferences", SampleDatabaseNamingPolicy.canonicalize("ExampleDBReferences"));
        assertEquals("ExampleFactura", SampleDatabaseNamingPolicy.canonicalize("factura"));
        assertEquals("ExampleFactura", SampleDatabaseNamingPolicy.canonicalize("example_factura"));
    }

    @JettraTest
    @DisplayName("5. Naming Policy: isSampleDatabase detects both canonical names and legacy aliases")
    void testIsSampleDatabaseDetection() {
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("ExampleDBReferences"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("ExampleHrEnterpriseDb"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("ExampleFactura"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("ExampleCustomDb"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("hr_enterprise_db"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("meteorology_iot_db"));
        assertTrue(SampleDatabaseNamingPolicy.isSampleDatabase("factura"));
        assertFalse(SampleDatabaseNamingPolicy.isSampleDatabase("production_orders_db"));
        assertFalse(SampleDatabaseNamingPolicy.isSampleDatabase(""));
        assertFalse(SampleDatabaseNamingPolicy.isSampleDatabase(null));
    }
}
