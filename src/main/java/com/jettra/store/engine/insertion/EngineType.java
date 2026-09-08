package com.jettra.store.engine.insertion;

import com.jettra.store.engine.hierarchy.StorageEngineType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Sealed hierarchy and strongly-typed models representing the 9 heterogeneous storage engines of JettraDB.
 * Leverages Java 25 Sealed Interfaces and exhaustive switch pattern matching.
 */
public sealed interface EngineType permits
        EngineType.KeyValue,
        EngineType.Document,
        EngineType.RelationalRecords,
        EngineType.Graph,
        EngineType.Vector,
        EngineType.TimeSeries,
        EngineType.WideColumn,
        EngineType.SpatialGeo,
        EngineType.PureObject {

    String key();
    String displayName();
    String color();
    String icon();
    String unitName();
    String itemLabel();
    String description();
    StorageEngineType toStorageEngineType();
    default String badge() { return null; }

    record KeyValue() implements EngineType {
        @Override public String key() { return "KEYVALUE"; }
        @Override public String displayName() { return "Key-Value"; }
        @Override public String color() { return "#10b981"; }
        @Override public String icon() { return "fas fa-key"; }
        @Override public String unitName() { return "Namespace"; }
        @Override public String itemLabel() { return "Key-Value Pair"; }
        @Override public String description() { return "High-throughput key-value cache and bucket store with optional TTL"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.KEY_VALUE; }
    }

    record Document() implements EngineType {
        @Override public String key() { return "DOCUMENT"; }
        @Override public String displayName() { return "Document / JSON"; }
        @Override public String color() { return "#38bdf8"; }
        @Override public String icon() { return "fas fa-file-code"; }
        @Override public String unitName() { return "Collection"; }
        @Override public String itemLabel() { return "Document"; }
        @Override public String description() { return "Schema-flexible JSON document engine with structured validation and indexing"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.DOCUMENT; }
    }

    record RelationalRecords() implements EngineType {
        @Override public String key() { return "RECORDS"; }
        @Override public String displayName() { return "Record (Java 25)"; }
        @Override public String color() { return "#10b981"; }
        @Override public String icon() { return "fas fa-microchip"; }
        @Override public String unitName() { return "Table"; }
        @Override public String itemLabel() { return "Record"; }
        @Override public String badge() { return "ULTRA-FAST"; }
        @Override public String description() { return "Java 25 Record & Immutable Schemas with high speed and low memory footprint"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.RELATIONAL_RECORDS; }
    }

    record Graph() implements EngineType {
        @Override public String key() { return "GRAPH"; }
        @Override public String displayName() { return "Graph"; }
        @Override public String color() { return "#ec4899"; }
        @Override public String icon() { return "fas fa-project-diagram"; }
        @Override public String unitName() { return "Graph / Label"; }
        @Override public String itemLabel() { return "Vertex / Edge"; }
        @Override public String description() { return "Property graph database supporting vertices, edges, and relationship traversal"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.GRAPH_REFERENCES; }
    }

    record Vector() implements EngineType {
        @Override public String key() { return "VECTOR"; }
        @Override public String displayName() { return "Vector / Embeddings"; }
        @Override public String color() { return "#8b5cf6"; }
        @Override public String icon() { return "fas fa-brain"; }
        @Override public String unitName() { return "Vector Index"; }
        @Override public String itemLabel() { return "Embedding Vector"; }
        @Override public String description() { return "AI embeddings store supporting similarity search and vector metrics"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.VECTOR; }
    }

    record TimeSeries() implements EngineType {
        @Override public String key() { return "TIMESERIES"; }
        @Override public String displayName() { return "Time-Series"; }
        @Override public String color() { return "#06b6d4"; }
        @Override public String icon() { return "fas fa-chart-line"; }
        @Override public String unitName() { return "Metric"; }
        @Override public String itemLabel() { return "Data Point"; }
        @Override public String description() { return "High-frequency IoT metrics and telemetry with nanosecond precision"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.TIMESERIES; }
    }

    record WideColumn() implements EngineType {
        @Override public String key() { return "COLUMN"; }
        @Override public String displayName() { return "Wide-Column"; }
        @Override public String color() { return "#f97316"; }
        @Override public String icon() { return "fas fa-columns"; }
        @Override public String unitName() { return "Column Family"; }
        @Override public String itemLabel() { return "Dynamic Row"; }
        @Override public String description() { return "Sparse wide-column analytical store organized by column families"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.COLUMN; }
    }

    record SpatialGeo() implements EngineType {
        @Override public String key() { return "GEOSPATIAL"; }
        @Override public String displayName() { return "Spatial / Geo"; }
        @Override public String color() { return "#14b8a6"; }
        @Override public String icon() { return "fas fa-map-marked-alt"; }
        @Override public String unitName() { return "Spatial Layer"; }
        @Override public String itemLabel() { return "Geo Feature"; }
        @Override public String description() { return "GIS layers, GeoJSON coordinates, geometries, and spatial indexing"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.GEOSPATIAL; }
    }

    record PureObject() implements EngineType {
        @Override public String key() { return "OBJECT"; }
        @Override public String displayName() { return "Pure Object / BLOB"; }
        @Override public String color() { return "#a855f7"; }
        @Override public String icon() { return "fas fa-cubes"; }
        @Override public String unitName() { return "Bucket"; }
        @Override public String itemLabel() { return "Binary Object"; }
        @Override public String description() { return "Pure Java objects, BLOB payloads, and binary asset buckets"; }
        @Override public StorageEngineType toStorageEngineType() { return StorageEngineType.OBJECT; }
    }

    static List<EngineType> all() {
        return List.of(
            new Document(),
            new KeyValue(),
            new RelationalRecords(),
            new Graph(),
            new Vector(),
            new TimeSeries(),
            new WideColumn(),
            new SpatialGeo(),
            new PureObject()
        );
    }

    static EngineType fromKey(String key) {
        if (key == null || key.isBlank()) return new Document();
        String normalized = key.trim().toUpperCase().replace("-", "_");
        return switch (normalized) {
            case "KEYVALUE", "KEY_VALUE", "KV" -> new KeyValue();
            case "DOCUMENT", "DOC", "JSON" -> new Document();
            case "RECORD", "RECORDS", "RELATIONAL", "TABULAR", "REC" -> new RelationalRecords();
            case "GRAPH", "GRAPH_REFERENCES" -> new Graph();
            case "VECTOR", "EMBEDDINGS", "VEC" -> new Vector();
            case "TIMESERIES", "TIME_SERIES", "TS" -> new TimeSeries();
            case "COLUMN", "WIDE_COLUMN", "WIDECOLUMN", "COL" -> new WideColumn();
            case "GEOSPATIAL", "SPATIAL", "GEO" -> new SpatialGeo();
            case "OBJECT", "BLOB", "OBJ" -> new PureObject();
            default -> new Document();
        };
    }
}
