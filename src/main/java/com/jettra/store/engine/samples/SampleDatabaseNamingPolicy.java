package com.jettra.store.engine.samples;

import java.util.*;

/**
 * Architectural naming policy and standardization engine for all sample databases in JettraDB.
 * Enforces the mandatory naming convention rule: every sample database name must strictly
 * begin with the acronym/prefix "Example<nombre>" (e.g. ExampleDBReferences, ExampleHrEnterpriseDb,
 * ExampleMeteorologyIotDb, ExampleEcommerceOlapDb).
 * Provides bi-directional alias resolution to guarantee backwards compatibility with legacy identifiers.
 */
public final class SampleDatabaseNamingPolicy {

    public static final String PREFIX = "Example";

    // 5 Primary On-Demand Catalog Databases
    public static final String EXAMPLE_DB_REFERENCES = "ExampleDBReferences";
    public static final String EXAMPLE_HR_ENTERPRISE_DB = "ExampleHrEnterpriseDb";
    public static final String EXAMPLE_METEOROLOGY_IOT_DB = "ExampleMeteorologyIotDb";
    public static final String EXAMPLE_ECOMMERCE_OLAP_DB = "ExampleEcommerceOlapDb";
    public static final String EXAMPLE_FACTURA = "ExampleFactura";

    // Additional Multi-Engine Sample Databases
    public static final String EXAMPLE_SCRUM_BOARD_DB = "ExampleScrumBoardDb";
    public static final String EXAMPLE_SMART_CITY_GIS_DB = "ExampleSmartCityGisDb";
    public static final String EXAMPLE_AI_KNOWLEDGE_DB = "ExampleAiKnowledgeDb";
    public static final String EXAMPLE_SOCIAL_NETWORK_DB = "ExampleSocialNetworkDb";
    public static final String EXAMPLE_DISTRIBUTED_CACHE_DB = "ExampleDistributedCacheDb";
    public static final String EXAMPLE_DIGITAL_ASSETS_DB = "ExampleDigitalAssetsDb";

    private static final Set<String> PRIMARY_CATALOG = Set.of(
        EXAMPLE_DB_REFERENCES,
        EXAMPLE_HR_ENTERPRISE_DB,
        EXAMPLE_METEOROLOGY_IOT_DB,
        EXAMPLE_ECOMMERCE_OLAP_DB,
        EXAMPLE_FACTURA
    );

    private static final Set<String> ALL_CANONICAL = Set.of(
        EXAMPLE_DB_REFERENCES,
        EXAMPLE_HR_ENTERPRISE_DB,
        EXAMPLE_METEOROLOGY_IOT_DB,
        EXAMPLE_ECOMMERCE_OLAP_DB,
        EXAMPLE_FACTURA,
        EXAMPLE_SCRUM_BOARD_DB,
        EXAMPLE_SMART_CITY_GIS_DB,
        EXAMPLE_AI_KNOWLEDGE_DB,
        EXAMPLE_SOCIAL_NETWORK_DB,
        EXAMPLE_DISTRIBUTED_CACHE_DB,
        EXAMPLE_DIGITAL_ASSETS_DB
    );

    private static final Map<String, String> ALIAS_MAP = new HashMap<>();

    static {
        // Register canonical variants
        for (String canonical : ALL_CANONICAL) {
            ALIAS_MAP.put(canonical.toLowerCase(Locale.ROOT), canonical);
        }

        // Register legacy snake_case and short aliases
        registerAlias("hr_enterprise_db", EXAMPLE_HR_ENTERPRISE_DB);
        registerAlias("meteorology_iot_db", EXAMPLE_METEOROLOGY_IOT_DB);
        registerAlias("ecommerce_olap_db", EXAMPLE_ECOMMERCE_OLAP_DB);
        registerAlias("factura", EXAMPLE_FACTURA);
        registerAlias("example_factura", EXAMPLE_FACTURA);
        registerAlias("factura_db", EXAMPLE_FACTURA);
        registerAlias("billing", EXAMPLE_FACTURA);
        registerAlias("scrum_board_db", EXAMPLE_SCRUM_BOARD_DB);
        registerAlias("smart_city_gis_db", EXAMPLE_SMART_CITY_GIS_DB);
        registerAlias("ai_knowledge_db", EXAMPLE_AI_KNOWLEDGE_DB);
        registerAlias("social_network_db", EXAMPLE_SOCIAL_NETWORK_DB);
        registerAlias("distributed_cache_db", EXAMPLE_DISTRIBUTED_CACHE_DB);
        registerAlias("digital_assets_db", EXAMPLE_DIGITAL_ASSETS_DB);

        // Variations without trailing 'db'
        registerAlias("examplehrenterprise", EXAMPLE_HR_ENTERPRISE_DB);
        registerAlias("examplemeteorologyiot", EXAMPLE_METEOROLOGY_IOT_DB);
        registerAlias("exampleecommerceolap", EXAMPLE_ECOMMERCE_OLAP_DB);
        registerAlias("examplescrumboard", EXAMPLE_SCRUM_BOARD_DB);
        registerAlias("examplesmartcitygis", EXAMPLE_SMART_CITY_GIS_DB);
        registerAlias("exampleaiknowledge", EXAMPLE_AI_KNOWLEDGE_DB);
        registerAlias("examplesocialnetwork", EXAMPLE_SOCIAL_NETWORK_DB);
        registerAlias("exampledistributedcache", EXAMPLE_DISTRIBUTED_CACHE_DB);
        registerAlias("exampledigitalassets", EXAMPLE_DIGITAL_ASSETS_DB);

        // Uppercase engine tokens
        registerAlias("records", EXAMPLE_HR_ENTERPRISE_DB);
        registerAlias("timeseries", EXAMPLE_METEOROLOGY_IOT_DB);
        registerAlias("column", EXAMPLE_ECOMMERCE_OLAP_DB);
        registerAlias("document", EXAMPLE_SCRUM_BOARD_DB);
        registerAlias("geospatial", EXAMPLE_SMART_CITY_GIS_DB);
        registerAlias("vector", EXAMPLE_AI_KNOWLEDGE_DB);
        registerAlias("graph", EXAMPLE_SOCIAL_NETWORK_DB);
        registerAlias("keyvalue", EXAMPLE_DISTRIBUTED_CACHE_DB);
        registerAlias("object", EXAMPLE_DIGITAL_ASSETS_DB);
        registerAlias("references", EXAMPLE_DB_REFERENCES);
    }

    private static void registerAlias(String alias, String targetCanonical) {
        ALIAS_MAP.put(alias.toLowerCase(Locale.ROOT), targetCanonical);
    }

    private SampleDatabaseNamingPolicy() {}

    /**
     * Checks whether a name strictly complies with the Example<nombre> naming standard.
     */
    public static boolean isValidSampleDatabaseName(String dbName) {
        if (dbName == null) return false;
        String trimmed = dbName.trim();
        return trimmed.startsWith(PREFIX) && trimmed.length() > PREFIX.length();
    }

    /**
     * Validates that the database name conforms to Example<nombre>, throwing an IllegalArgumentException if invalid.
     */
    public static void validateOrThrow(String dbName) {
        if (!isValidSampleDatabaseName(dbName)) {
            throw new IllegalArgumentException(
                "Invalid sample database name: '" + dbName + "'. All sample databases must start with the prefix '" + PREFIX + "<nombre>'."
            );
        }
    }

    /**
     * Determines whether the given database name corresponds to a known sample database (either by canonical Example* name or recognized alias).
     */
    public static boolean isSampleDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) return false;
        String trimmed = dbName.trim();
        if (isValidSampleDatabaseName(trimmed)) return true;
        return ALIAS_MAP.containsKey(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the canonical Example<nombre> name for any sample database or legacy alias.
     */
    public static String canonicalize(String dbName) {
        if (dbName == null || dbName.isBlank()) return dbName;
        String trimmed = dbName.trim();
        String lookup = trimmed.toLowerCase(Locale.ROOT);
        return ALIAS_MAP.getOrDefault(lookup, trimmed);
    }

    /**
     * Returns the 4 authorized primary on-demand sample databases.
     */
    public static Set<String> primaryCatalogDatabases() {
        return PRIMARY_CATALOG;
    }

    /**
     * Returns all 10 canonical sample databases.
     */
    public static Set<String> allCanonicalDatabases() {
        return ALL_CANONICAL;
    }
}
