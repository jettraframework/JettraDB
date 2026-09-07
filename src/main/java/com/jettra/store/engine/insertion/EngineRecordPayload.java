package com.jettra.store.engine.insertion;

import io.jettra.json.JsonObject;

/**
 * Sealed hierarchy of immutable data records for the 9 multi-model storage engines in JettraDB.
 * Built with Java 25 records and sealed interfaces for strict compiler-checked pattern matching.
 */
public sealed interface EngineRecordPayload permits
        EngineRecordPayload.KeyValuePayload,
        EngineRecordPayload.DocumentPayload,
        EngineRecordPayload.RelationalRecordPayload,
        EngineRecordPayload.GraphPayload,
        EngineRecordPayload.VectorPayload,
        EngineRecordPayload.TimeSeriesPayload,
        EngineRecordPayload.WideColumnPayload,
        EngineRecordPayload.SpatialGeoPayload,
        EngineRecordPayload.PureObjectPayload {

    String entityId();
    String unitName();
    EngineType engineType();

    record KeyValuePayload(
            String namespace,
            String key,
            String value,
            Long ttlSeconds
    ) implements EngineRecordPayload {
        @Override public String entityId() { return key; }
        @Override public String unitName() { return namespace; }
        @Override public EngineType engineType() { return new EngineType.KeyValue(); }
    }

    record DocumentPayload(
            String id,
            String collection,
            String documentClass,
            JsonObject jsonContent,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return id; }
        @Override public String unitName() { return collection; }
        @Override public EngineType engineType() { return new EngineType.Document(); }
    }

    record RelationalRecordPayload(
            String table,
            String recordId,
            String recordClass,
            JsonObject columns,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return recordId; }
        @Override public String unitName() { return table; }
        @Override public EngineType engineType() { return new EngineType.RelationalRecords(); }
    }

    record GraphPayload(
            String mode, // "node" or "edge"
            String id,
            String label,
            String fromId,
            String toId,
            JsonObject properties,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return id; }
        @Override public String unitName() { return label; }
        @Override public EngineType engineType() { return new EngineType.Graph(); }
    }

    record VectorPayload(
            String id,
            String index,
            int dimension,
            float[] embeddings,
            String distanceMetric,
            String label,
            JsonObject metadata,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return id; }
        @Override public String unitName() { return index; }
        @Override public EngineType engineType() { return new EngineType.Vector(); }
    }

    record TimeSeriesPayload(
            String metric,
            long timestamp,
            double value,
            String unit,
            JsonObject tags,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return metric + "@" + timestamp; }
        @Override public String unitName() { return metric; }
        @Override public EngineType engineType() { return new EngineType.TimeSeries(); }
    }

    record WideColumnPayload(
            String rowKey,
            String columnFamily,
            String columnQualifier,
            Long timestamp,
            String cellValue,
            JsonObject columns,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return rowKey; }
        @Override public String unitName() { return columnFamily; }
        @Override public EngineType engineType() { return new EngineType.WideColumn(); }
    }

    record SpatialGeoPayload(
            String id,
            String layer,
            String featureName,
            String geometryType,
            double latitude,
            double longitude,
            JsonObject properties,
            String rawJson
    ) implements EngineRecordPayload {
        @Override public String entityId() { return id; }
        @Override public String unitName() { return layer; }
        @Override public EngineType engineType() { return new EngineType.SpatialGeo(); }
    }

    record PureObjectPayload(
            String id,
            String bucket,
            String className,
            String mimeType,
            String contentText,
            byte[] contentBytes
    ) implements EngineRecordPayload {
        @Override public String entityId() { return id; }
        @Override public String unitName() { return bucket; }
        @Override public EngineType engineType() { return new EngineType.PureObject(); }
    }
}
