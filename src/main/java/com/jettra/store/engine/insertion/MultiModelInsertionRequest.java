package com.jettra.store.engine.insertion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable Java 25 Record modeling a multi-model record insertion request.
 * Encapsulates common metadata, engine type, target database and collections,
 * polymorphic parameters and raw JSON payloads.
 */
public record MultiModelInsertionRequest(
    String action,
    String engine,
    String targetDb,
    String targetColl,
    String targetId,
    String idGenMode,
    Map<String, String> properties,
    String rawJsonPayload,
    long timestamp
) {
    public MultiModelInsertionRequest {
        properties = properties != null ? Collections.unmodifiableMap(new HashMap<>(properties)) : Map.of();
    }

    public static MultiModelInsertionRequest fromMap(Map<String, String> params) {
        String action = params != null ? params.getOrDefault("action", "insert_object_ajax") : "insert_object_ajax";
        String engine = params != null ? params.getOrDefault("engine", "DOCUMENT").toUpperCase() : "DOCUMENT";
        String targetDb = params != null ? params.getOrDefault("target_db", "customers_db") : "customers_db";
        String targetColl = params != null ? params.getOrDefault("target_coll", "default") : "default";
        String targetId = params != null ? params.getOrDefault("target_id", "") : "";
        String idGenMode = params != null ? params.getOrDefault("id_gen_mode", "UUID") : "UUID";
        String rawJson = params != null ? params.getOrDefault("_raw_body", "") : "";

        return new MultiModelInsertionRequest(
            action,
            engine,
            targetDb,
            targetColl,
            targetId,
            idGenMode,
            params != null ? params : Map.of(),
            rawJson,
            System.currentTimeMillis()
        );
    }
}
