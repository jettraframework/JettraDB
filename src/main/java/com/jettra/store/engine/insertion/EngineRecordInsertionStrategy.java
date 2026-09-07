package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.flux.core.Widget;

import java.util.Map;

/**
 * Strategy pattern interface for schema validation, payload parsing, execution,
 * and form field generation across all 9 JettraDB storage engines.
 *
 * @param <T> specific EngineRecordPayload implementation
 */
public interface EngineRecordInsertionStrategy<T extends EngineRecordPayload> {

    /**
     * The targeted engine type.
     */
    EngineType engineType();

    /**
     * Validates input parameters before parsing or submission.
     */
    ValidationResult validate(Map<String, String> params);

    /**
     * Parses the HTTP/form parameters into an immutable typed record.
     */
    T parsePayload(String database, String unit, String id, Map<String, String> params);

    /**
     * Persists the typed payload into the given JettraStorageEngine backend.
     */
    InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, T payload);

    /**
     * Builds the reactive JettraFlux UI fields specific to this engine.
     */
    Widget buildEngineFormFields(String currentDb, String currentUnit);

    /**
     * Generates a sample payload model for testing and quick seeding.
     */
    T generateSamplePayload(String database, String unit);

    /**
     * Generates realistic form key-value pairs for pre-filling the UI dynamically.
     */
    Map<String, String> generateSampleFormValues(String database, String unit);
}
