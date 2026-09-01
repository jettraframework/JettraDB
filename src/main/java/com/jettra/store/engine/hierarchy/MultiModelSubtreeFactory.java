package com.jettra.store.engine.hierarchy;

import com.jettra.store.engine.core.LsmBTreeHybrid;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composite & Factory provider for constructing and initializing standardized Multi-Model Storage Subtrees.
 * Guarantees that every database in JettraStoreEngine has its 9 storage engine subtrees instantiated
 * without empty or orphaned branches.
 */
public final class MultiModelSubtreeFactory {

    private MultiModelSubtreeFactory() {}

    /**
     * Instantiates the complete in-memory DatabaseNode composite containing all 9 engine subtrees
     * with ready-to-use collection/unit nodes.
     */
    public static HierarchyNode.DatabaseNode createInitialDatabaseNode(
            String dbName,
            String primaryEngineName,
            String initialUnitName
    ) {
        String cleanDb = dbName != null ? dbName.trim() : "default_db";
        List<HierarchyNode.EngineNode> engineNodes = new ArrayList<>();

        for (StorageEngineType engineType : StorageEngineType.values()) {
            HierarchyNode.EngineNode engNode = createEngineSubtreeNode(cleanDb, engineType, primaryEngineName, initialUnitName);
            engineNodes.add(engNode);
        }

        List<HierarchyNode.IndexNode> defaultIndexes = List.of(
            new HierarchyNode.IndexNode("idx_primary_id", "idx_primary_id", "BTREE", "default", "_id", 0)
        );

        return new HierarchyNode.DatabaseNode(
            "db_" + cleanDb,
            cleanDb,
            0,
            true,
            engineNodes,
            defaultIndexes,
            Collections.emptyList()
        );
    }

    /**
     * Builds a specific EngineNode subtree using pattern matching over StorageEngineType.
     */
    public static HierarchyNode.EngineNode createEngineSubtreeNode(
            String dbName,
            StorageEngineType engineType,
            String primaryEngineName,
            String initialUnitName
    ) {
        String effectiveUnit = resolveInitialUnitForEngine(engineType, primaryEngineName, initialUnitName);
        HierarchyNode.UnitNode initialUnit = new HierarchyNode.UnitNode(
            "unit_" + engineType.engineName() + "_" + dbName + "_" + effectiveUnit,
            effectiveUnit,
            0,
            Collections.emptyList()
        );

        return switch (engineType) {
            case DOCUMENT -> new HierarchyNode.EngineNode(
                "eng_DOCUMENT_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case KEY_VALUE -> new HierarchyNode.EngineNode(
                "eng_KEYVALUE_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case GRAPH_REFERENCES -> new HierarchyNode.EngineNode(
                "eng_GRAPH_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case VECTOR -> new HierarchyNode.EngineNode(
                "eng_VECTOR_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case RELATIONAL_RECORDS -> new HierarchyNode.EngineNode(
                "eng_RECORDS_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case TIMESERIES -> new HierarchyNode.EngineNode(
                "eng_TIMESERIES_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case COLUMN -> new HierarchyNode.EngineNode(
                "eng_COLUMN_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case GEOSPATIAL -> new HierarchyNode.EngineNode(
                "eng_GEOSPATIAL_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
            case OBJECT -> new HierarchyNode.EngineNode(
                "eng_OBJECT_" + dbName,
                engineType.engineName(),
                engineType.color(),
                engineType.icon(),
                engineType.unitPlural(),
                engineType.unitSingle(),
                engineType.itemLabel(),
                engineType.itemIcon(),
                0,
                List.of(initialUnit)
            );
        };
    }

    /**
     * Persists the topology of all 9 multi-model engines into the underlying LSM storage core.
     */
    public static void initializeDatabaseStorage(
            LsmBTreeHybrid storageCore,
            String dbName,
            String primaryEngineName,
            String initialUnitName
    ) {
        if (storageCore == null || dbName == null || dbName.isBlank()) return;

        String cleanDb = dbName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        long now = System.currentTimeMillis();

        for (StorageEngineType engineType : StorageEngineType.values()) {
            String unit = resolveInitialUnitForEngine(engineType, primaryEngineName, initialUnitName);
            String initKey = engineType.prefix() + cleanDb + ":" + unit + ":init_01";

            JsonObject initDoc = new JsonObject();
            initDoc.addProperty("_database", cleanDb);
            initDoc.addProperty("_engine", engineType.engineName());
            initDoc.addProperty("_unit", unit);
            initDoc.addProperty("status", "ACTIVE");
            initDoc.addProperty("createdAt", now);

            storageCore.put(initKey, initDoc.toString().getBytes(StandardCharsets.UTF_8), now);
        }
    }

    private static String resolveInitialUnitForEngine(
            StorageEngineType engineType,
            String primaryEngineName,
            String initialUnitName
    ) {
        if (primaryEngineName != null && !primaryEngineName.isBlank()
                && initialUnitName != null && !initialUnitName.isBlank()) {
            if (engineType.engineName().equalsIgnoreCase(primaryEngineName.trim())
                    || engineType.name().equalsIgnoreCase(primaryEngineName.trim())) {
                return initialUnitName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            }
        }
        return "default";
    }
}
