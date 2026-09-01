package com.jettra.store.engine.samples.lifecycle;

import java.util.List;

/**
 * Immutable definition and metadata contract for an on-demand sample database.
 */
public record SampleDatabaseDefinition(
    String id,
    String engineType,
    String databaseName,
    String displayName,
    String description,
    int estimatedRecords,
    String icon,
    List<String> tags
) {}
