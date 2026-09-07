package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.strategies.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Gatherers;

/**
 * Abstract Factory and Factory Method orchestrator for resolving engine insertion strategies,
 * building polymorphic UI components, and executing inserts concurrently using Virtual Threads.
 */
public final class EngineInsertionFactory {

    public record MultiModelInsertionEvent(
        String engineKey,
        String database,
        String unit,
        String id,
        EngineRecordPayload payload,
        long timestamp
    ) {}

    private static final Map<String, EngineRecordInsertionStrategy<?>> STRATEGIES = new ConcurrentHashMap<>();

    static {
        register(new DocumentInsertionStrategy());
        register(new KeyValueInsertionStrategy());
        register(new RelationalRecordsInsertionStrategy());
        register(new GraphInsertionStrategy());
        register(new VectorInsertionStrategy());
        register(new TimeSeriesInsertionStrategy());
        register(new WideColumnInsertionStrategy());
        register(new SpatialGeoInsertionStrategy());
        register(new PureObjectInsertionStrategy());
    }

    private EngineInsertionFactory() {}

    public static void register(EngineRecordInsertionStrategy<?> strategy) {
        if (strategy != null) {
            STRATEGIES.put(strategy.engineType().key().toUpperCase(), strategy);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends EngineRecordPayload> EngineRecordInsertionStrategy<T> getStrategy(EngineType engineType) {
        if (engineType == null) engineType = new EngineType.Document();
        return (EngineRecordInsertionStrategy<T>) getStrategy(engineType.key());
    }

    @SuppressWarnings("unchecked")
    public static <T extends EngineRecordPayload> EngineRecordInsertionStrategy<T> getStrategy(String rawKey) {
        EngineType resolved = EngineType.fromKey(rawKey);
        EngineRecordInsertionStrategy<?> strat = STRATEGIES.get(resolved.key().toUpperCase());
        if (strat == null) {
            strat = STRATEGIES.get("DOCUMENT");
        }
        return (EngineRecordInsertionStrategy<T>) strat;
    }

    public static Collection<EngineRecordInsertionStrategy<?>> getAllStrategies() {
        return Collections.unmodifiableCollection(STRATEGIES.values());
    }

    /**
     * Java 25 Pattern Matching with compiler-verified exhaustiveness on the sealed EngineRecordPayload hierarchy.
     */
    public static String describePayloadModel(EngineRecordPayload payload) {
        return switch (payload) {
            case EngineRecordPayload.KeyValuePayload kv -> "KeyValue [ns=" + kv.namespace() + ", key=" + kv.key() + "]";
            case EngineRecordPayload.DocumentPayload doc -> "Document [coll=" + doc.collection() + ", id=" + doc.id() + "]";
            case EngineRecordPayload.RelationalRecordPayload rec -> "Relational [table=" + rec.table() + ", id=" + rec.recordId() + "]";
            case EngineRecordPayload.GraphPayload gr -> "Graph [mode=" + gr.mode() + ", id=" + gr.id() + ", label=" + gr.label() + "]";
            case EngineRecordPayload.VectorPayload vec -> "Vector [idx=" + vec.index() + ", id=" + vec.id() + ", dim=" + vec.dimension() + "]";
            case EngineRecordPayload.TimeSeriesPayload ts -> "TimeSeries [metric=" + ts.metric() + ", ts=" + ts.timestamp() + ", val=" + ts.value() + "]";
            case EngineRecordPayload.WideColumnPayload wc -> "WideColumn [cf=" + wc.columnFamily() + ", row=" + wc.rowKey() + "]";
            case EngineRecordPayload.SpatialGeoPayload geo -> "SpatialGeo [layer=" + geo.layer() + ", feat=" + geo.id() + ", type=" + geo.geometryType() + "]";
            case EngineRecordPayload.PureObjectPayload obj -> "PureObject [bucket=" + obj.bucket() + ", id=" + obj.id() + ", class=" + obj.className() + "]";
        };
    }

    /**
     * Java 25 Stream Gatherer pipeline for validating parameter sets and aggregating schema errors.
     */
    public static List<String> validateParametersWithGatherer(Map<String, String> params, EngineType engineType) {
        if (params == null || params.isEmpty()) {
            return List.of("Los parámetros de inserción no pueden estar vacíos.");
        }

        return params.entrySet().stream()
                .gather(Gatherers.fold(
                        ArrayList<String>::new,
                        (errors, entry) -> {
                            String k = entry.getKey();
                            String v = entry.getValue();
                            if ("target_db".equals(k) && (v == null || v.isBlank())) {
                                errors.add("Base de datos destino requerida");
                            }
                            if ("target_id".equals(k) && v != null && v.contains("/")) {
                                errors.add("El identificador no puede contener barras diagonales ('/')");
                            }
                            return errors;
                        }
                ))
                .findFirst()
                .orElseGet(ArrayList::new);
    }

    /**
     * Executes the record insertion asynchronously on a lightweight Java 25 Virtual Thread.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CompletableFuture<InsertionResult> executeInsertAsync(
            JettraStorageEngine storageEngine,
            String rawEngineKey,
            String database,
            Map<String, String> params
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                EngineRecordInsertionStrategy strategy = getStrategy(rawEngineKey);

                // Run Stream Gatherer validation
                List<String> gathererErrors = validateParametersWithGatherer(params, strategy.engineType());
                if (!gathererErrors.isEmpty()) {
                    return InsertionResult.ofError(
                            strategy.engineType().key(),
                            database,
                            params.getOrDefault("target_coll", "default"),
                            params.getOrDefault("target_id", "unknown"),
                            "Validación fallida: " + String.join(" | ", gathererErrors)
                    );
                }

                ValidationResult validation = strategy.validate(params);
                if (!validation.isValid()) {
                    return InsertionResult.ofError(
                            strategy.engineType().key(),
                            database,
                            params.getOrDefault("target_coll", "default"),
                            params.getOrDefault("target_id", "unknown"),
                            "Validación fallida: " + String.join(" | ", validation.errors())
                    );
                }

                // Resolve target ID and unit
                String rawMode = params.getOrDefault("id_gen_mode", "UUID");
                IdGenerator.IdMode idMode = IdGenerator.IdMode.fromString(rawMode);
                String manualId = params.get("target_id");
                String targetColl = params.getOrDefault("target_coll", "default");
                String resolvedId = IdGenerator.generateId(database + ":" + targetColl, idMode, manualId);

                EngineRecordPayload payload = strategy.parsePayload(database, targetColl, resolvedId, params);

                // Capture immutable event record for auditability
                MultiModelInsertionEvent event = new MultiModelInsertionEvent(
                        strategy.engineType().key(),
                        database,
                        targetColl,
                        resolvedId,
                        payload,
                        System.currentTimeMillis()
                );

                InsertionResult result = strategy.executeInsert(storageEngine, database, payload);
                return result;
            } catch (Exception e) {
                return InsertionResult.ofError(
                        rawEngineKey,
                        database,
                        params.getOrDefault("target_coll", "default"),
                        params.getOrDefault("target_id", "unknown"),
                        "Fallo en inserción: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                );
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }
}
