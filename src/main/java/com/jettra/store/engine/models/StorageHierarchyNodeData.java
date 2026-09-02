package com.jettra.store.engine.models;

/**
 * Immutable Java 25 Record encapsulating multi-model storage hierarchy node metadata.
 * Serves as strongly-typed payload data for FluxTree and FluxTreeNode.
 */
public record StorageHierarchyNodeData(
    String engineType,
    String database,
    String unit,
    String recordId,
    String category, // "ENGINE", "DATABASE", "UNIT", "ITEM"
    int versionCount,
    long timestamp,
    String formattedDate,
    String payloadSnippet,
    String payloadB64,
    String versionsB64
) {
    public static StorageHierarchyNodeData forEngine(String engineType) {
        return new StorageHierarchyNodeData(engineType, "", "", "", "ENGINE", 0, 0, "", "", "", "");
    }

    public static StorageHierarchyNodeData forDatabase(String engineType, String database) {
        return new StorageHierarchyNodeData(engineType, database, "", "", "DATABASE", 0, 0, "", "", "", "");
    }

    public static StorageHierarchyNodeData forUnit(String engineType, String database, String unit, int itemCount) {
        return new StorageHierarchyNodeData(engineType, database, unit, "", "UNIT", itemCount, 0, "", "", "", "");
    }

    public static StorageHierarchyNodeData forItem(String engineType, String database, String unit, String recordId,
                                                  int versionCount, long timestamp, String formattedDate,
                                                  String payloadSnippet, String payloadB64, String versionsB64) {
        return new StorageHierarchyNodeData(engineType, database, unit, recordId, "ITEM", versionCount, timestamp,
                                            formattedDate, payloadSnippet, payloadB64, versionsB64);
    }
}
