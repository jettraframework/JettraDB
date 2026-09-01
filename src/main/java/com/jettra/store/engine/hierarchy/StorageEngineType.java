package com.jettra.store.engine.hierarchy;

import java.util.Arrays;
import java.util.Optional;

/**
 * Enumeration of supported multi-model storage engine types in JettraStoreEngine.
 * Contains domain metadata, color themes, FontAwesome icons, collection/item labels, and storage prefixes.
 */
public enum StorageEngineType {
    DOCUMENT(
        "DOCUMENT",
        "NoSQL Collections",
        "#38bdf8",
        "fas fa-file-code",
        "Collections",
        "Collection",
        "Document",
        "fas fa-file-alt",
        "doc:"
    ),
    KEY_VALUE(
        "KEYVALUE",
        "Key-Value Cache & Buckets",
        "#f59e0b",
        "fas fa-key",
        "Namespaces",
        "Namespace",
        "Key-Value",
        "fas fa-tag",
        "kv:"
    ),
    GRAPH_REFERENCES(
        "GRAPH",
        "Graph Nodes & Edge Relations",
        "#ec4899",
        "fas fa-project-diagram",
        "Graphs",
        "Graph",
        "Node / Edge",
        "fas fa-circle-nodes",
        "graph:"
    ),
    VECTOR(
        "VECTOR",
        "AI Embeddings & Vector Search",
        "#a855f7",
        "fas fa-brain",
        "Indexes",
        "Index",
        "Vector",
        "fas fa-project-diagram",
        "vec:"
    ),
    RELATIONAL_RECORDS(
        "RECORDS",
        "Java 25 Record & Immutable Schemas",
        "#10b981",
        "fas fa-table",
        "Tables",
        "Table",
        "Record",
        "fas fa-id-card",
        "rec:"
    ),
    TIMESERIES(
        "TIMESERIES",
        "IoT Metrics & Time Series Data",
        "#06b6d4",
        "fas fa-chart-line",
        "Metrics",
        "Metric",
        "Data Point",
        "fas fa-chart-area",
        "ts:"
    ),
    COLUMN(
        "COLUMN",
        "Wide-Column & Analytical Store",
        "#6366f1",
        "fas fa-columns",
        "Column Families",
        "Column Family",
        "Row",
        "fas fa-bars",
        "col:"
    ),
    GEOSPATIAL(
        "GEOSPATIAL",
        "Geospatial Coordinates & Spatial Layers",
        "#84cc16",
        "fas fa-map-marked-alt",
        "Layers",
        "Layer",
        "Geo Feature",
        "fas fa-location-dot",
        "geo:"
    ),
    OBJECT(
        "OBJECT",
        "Binary Large Object (BLOB) Buckets",
        "#f97316",
        "fas fa-cubes",
        "Buckets",
        "Bucket",
        "Object / Blob",
        "fas fa-box",
        "obj:"
    );

    private final String engineName;
    private final String description;
    private final String color;
    private final String icon;
    private final String unitPlural;
    private final String unitSingle;
    private final String itemLabel;
    private final String itemIcon;
    private final String prefix;

    StorageEngineType(
        String engineName,
        String description,
        String color,
        String icon,
        String unitPlural,
        String unitSingle,
        String itemLabel,
        String itemIcon,
        String prefix
    ) {
        this.engineName = engineName;
        this.description = description;
        this.color = color;
        this.icon = icon;
        this.unitPlural = unitPlural;
        this.unitSingle = unitSingle;
        this.itemLabel = itemLabel;
        this.itemIcon = itemIcon;
        this.prefix = prefix;
    }

    public String engineName() { return engineName; }
    public String description() { return description; }
    public String color() { return color; }
    public String icon() { return icon; }
    public String unitPlural() { return unitPlural; }
    public String unitSingle() { return unitSingle; }
    public String itemLabel() { return itemLabel; }
    public String itemIcon() { return itemIcon; }
    public String prefix() { return prefix; }

    public static Optional<StorageEngineType> fromString(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase().replace("-", "_");
        return Arrays.stream(values())
                .filter(t -> t.name().equals(normalized) || t.engineName.equalsIgnoreCase(raw.trim()))
                .findFirst();
    }

    public static StorageEngineType fromStringOrDefault(String raw, StorageEngineType defaultType) {
        return fromString(raw).orElse(defaultType);
    }
}
