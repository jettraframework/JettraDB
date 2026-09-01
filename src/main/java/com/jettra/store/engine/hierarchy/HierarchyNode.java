package com.jettra.store.engine.hierarchy;

import java.util.List;
import java.util.Map;

/**
 * Spec-Driven immutable hierarchy models for Multi-Model Storage Hierarchy Explorer.
 * Utilizes Java 25 sealed interfaces, records, and pattern matching.
 */
public sealed interface HierarchyNode permits 
        HierarchyNode.DatabaseNode, 
        HierarchyNode.EngineNode, 
        HierarchyNode.UnitNode, 
        HierarchyNode.RecordNode, 
        HierarchyNode.IndexNode, 
        HierarchyNode.SchemaNode {

    String id();
    String name();
    String nodeType();

    record DatabaseNode(
        String id,
        String name,
        int totalItems,
        boolean hasComponents,
        List<EngineNode> engines,
        List<IndexNode> indexes,
        List<SchemaNode> schemas
    ) implements HierarchyNode {
        @Override public String nodeType() { return "DATABASE"; }
    }

    record EngineNode(
        String id,
        String name,
        String color,
        String icon,
        String unitPlural,
        String unitSingle,
        String itemLabel,
        String itemIcon,
        int totalItems,
        List<UnitNode> units
    ) implements HierarchyNode {
        @Override public String nodeType() { return "ENGINE"; }
    }

    record UnitNode(
        String id,
        String name,
        int totalItems,
        List<RecordNode> items
    ) implements HierarchyNode {
        @Override public String nodeType() { return "UNIT"; }
    }

    record RecordNode(
        String id,
        String name,
        String engine,
        String unit,
        int versionCount,
        String rawPayload,
        String payloadB64,
        String versionsB64,
        Map<String, Object> summaryProps
    ) implements HierarchyNode {
        @Override public String nodeType() { return "RECORD"; }
    }

    record IndexNode(
        String id,
        String name,
        String type,
        String targetUnit,
        String fieldPath,
        int entryCount
    ) implements HierarchyNode {
        @Override public String nodeType() { return "INDEX"; }
    }

    record SchemaNode(
        String id,
        String name,
        String schemaJson,
        String schemaB64
    ) implements HierarchyNode {
        @Override public String nodeType() { return "SCHEMA"; }
    }
}
