package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller and Command Handler for transactional record version rollbacks.
 * Executes version restoration with validation, Virtual Threads for asynchronous execution,
 * and state synchronization across JettraStorageEngine partitions.
 */
public class RestoreActionHandler {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    public RestoreActionHandler(JettraStorageEngine engine) {
        this.engine = engine;
        this.hierarchyService = new HierarchyExplorerService(engine);
    }

    public record RestoreResult(
        boolean success,
        String engineType,
        String database,
        String collection,
        String recordId,
        long timestamp,
        String message
    ) {}

    /**
     * Executes transactional rollback of a record to a target timestamp.
     */
    public RestoreResult executeRestore(String engineType, String database, String collection, String recordId, long targetTimestamp) {
        if (targetTimestamp <= 0 || recordId == null || recordId.isBlank()) {
            return new RestoreResult(false, engineType, database, collection, recordId, targetTimestamp, "Invalid timestamp or record ID for rollback.");
        }

        String eng = (engineType != null && !engineType.isBlank()) ? engineType.toUpperCase() : "DOCUMENT";
        String prefix = hierarchyService.getPrefixForEngine(eng);
        String coll = (collection != null && !collection.isBlank()) ? collection : "default";
        String db = (database != null && !database.isBlank()) ? database : "customers_db";

        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + recordId,
            prefix + db + ":" + recordId,
            db + ":" + coll + ":" + recordId,
            db + ":" + recordId
        };

        boolean restored = false;
        for (String k : candidateKeys) {
            if (engine.getStorageCore().restoreVersion(k, targetTimestamp)) {
                restored = true;
                break;
            }
        }

        if (restored) {
            return new RestoreResult(
                true,
                eng,
                db,
                coll,
                recordId,
                targetTimestamp,
                "[" + eng + "] Record '" + recordId + "' successfully restored to version snapshot from timestamp " + targetTimestamp + "!"
            );
        } else {
            return new RestoreResult(
                false,
                eng,
                db,
                coll,
                recordId,
                targetTimestamp,
                "Failed to restore record '" + recordId + "' at timestamp " + targetTimestamp + " (snapshot not found in partition)."
            );
        }
    }

    /**
     * Executes asynchronous rollback using Java 25 Virtual Threads.
     */
    public CompletableFuture<RestoreResult> executeRestoreAsync(String engineType, String database, String collection, String recordId, long targetTimestamp) {
        return CompletableFuture.supplyAsync(() -> executeRestore(engineType, database, collection, recordId, targetTimestamp), VIRTUAL_EXECUTOR);
    }
}
