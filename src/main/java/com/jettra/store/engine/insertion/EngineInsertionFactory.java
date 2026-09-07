package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.strategies.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Abstract Factory and Factory Method orchestrator for resolving engine insertion strategies,
 * building polymorphic UI components, and executing inserts concurrently using Virtual Threads.
 */
public final class EngineInsertionFactory {

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
                return strategy.executeInsert(storageEngine, database, payload);
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
