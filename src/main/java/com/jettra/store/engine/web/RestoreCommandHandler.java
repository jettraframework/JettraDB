package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Reactive Command Handler for transactional record version rollbacks in JettraFlux.
 * Encapsulates the Command Pattern, Event Bus state synchronization, Java 25+ immutable records,
 * and Java 25 Virtual Threads for non-blocking asynchronous execution.
 */
public class RestoreCommandHandler {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private final List<Consumer<RestoreEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Immutable Command Payload for Rollback Operations.
     */
    public record RestoreCommand(
        String engine,
        String database,
        String unit,
        String recordId,
        long targetTimestamp,
        int versionNumber
    ) {
        public static RestoreCommand of(String engine, String database, String unit, String recordId, long targetTimestamp) {
            return new RestoreCommand(engine, database, unit, recordId, targetTimestamp, 0);
        }
    }

    /**
     * Immutable Result Payload returned after rollback execution.
     */
    public record RestoreResult(
        boolean success,
        String engineType,
        String database,
        String collection,
        String recordId,
        long timestamp,
        String message,
        byte[] restoredData
    ) {
        public String restoredPayloadString() {
            return (restoredData != null && restoredData.length > 0)
                ? new String(restoredData, StandardCharsets.UTF_8)
                : null;
        }
    }

    /**
     * Sealed hierarchy of Reactive Events published to synchronize UI and application state.
     */
    public sealed interface RestoreEvent permits RestoreSuccessEvent, RestoreFailureEvent {
        RestoreCommand command();
        long eventTimestamp();
    }

    public record RestoreSuccessEvent(
        RestoreCommand command,
        RestoreResult result,
        long eventTimestamp
    ) implements RestoreEvent {}

    public record RestoreFailureEvent(
        RestoreCommand command,
        String failureReason,
        long eventTimestamp
    ) implements RestoreEvent {}

    public RestoreCommandHandler(JettraStorageEngine engine) {
        this.engine = engine;
        this.hierarchyService = new HierarchyExplorerService(engine);
    }

    /**
     * Subscribes a listener to reactive restore events.
     *
     * @param listener reactive consumer
     * @return AutoCloseable subscription to unsubscribe
     */
    public AutoCloseable subscribe(Consumer<RestoreEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
        return () -> {
            if (listener != null) {
                eventListeners.remove(listener);
            }
        };
    }

    /**
     * Executes synchronous transactional rollback with validation and state synchronization.
     */
    public RestoreResult handle(RestoreCommand command) {
        if (command == null) {
            return new RestoreResult(false, "DOCUMENT", "default", "default", "", 0, "Null restore command.", null);
        }

        long targetTimestamp = command.targetTimestamp();
        String recordId = command.recordId();
        String engineType = (command.engine() != null && !command.engine().isBlank()) ? command.engine().toUpperCase() : "DOCUMENT";
        String database = (command.database() != null && !command.database().isBlank()) ? command.database() : "customers_db";
        String unit = (command.unit() != null && !command.unit().isBlank()) ? command.unit() : "default";

        if (targetTimestamp <= 0 || recordId == null || recordId.isBlank()) {
            String errorMsg = "Invalid timestamp or record ID for rollback: ts=" + targetTimestamp + ", recordId=" + recordId;
            notifyListeners(new RestoreFailureEvent(command, errorMsg, System.currentTimeMillis()));
            return new RestoreResult(false, engineType, database, unit, recordId, targetTimestamp, errorMsg, null);
        }

        String prefix = hierarchyService.getPrefixForEngine(engineType);
        String[] candidateKeys = {
            prefix + database + ":" + unit + ":" + recordId,
            prefix + database + ":" + recordId,
            database + ":" + unit + ":" + recordId,
            database + ":" + recordId
        };

        boolean restored = false;
        String matchedKey = null;
        for (String k : candidateKeys) {
            if (engine.getStorageCore().restoreVersion(k, targetTimestamp)) {
                restored = true;
                matchedKey = k;
                break;
            }
        }

        if (restored) {
            byte[] restoredData = (matchedKey != null) ? engine.getStorageCore().get(matchedKey) : null;
            RestoreResult result = new RestoreResult(
                true,
                engineType,
                database,
                unit,
                recordId,
                targetTimestamp,
                "[" + engineType + "] Record '" + recordId + "' successfully restored to version snapshot from timestamp " + targetTimestamp + "!",
                restoredData
            );
            notifyListeners(new RestoreSuccessEvent(command, result, System.currentTimeMillis()));
            return result;
        } else {
            String errorMsg = "Failed to restore record '" + recordId + "' at timestamp " + targetTimestamp + " (snapshot not found in partition).";
            notifyListeners(new RestoreFailureEvent(command, errorMsg, System.currentTimeMillis()));
            return new RestoreResult(
                false,
                engineType,
                database,
                unit,
                recordId,
                targetTimestamp,
                errorMsg,
                null
            );
        }
    }

    /**
     * Executes asynchronous rollback using Java 25 Virtual Threads.
     */
    public CompletableFuture<RestoreResult> handleAsync(RestoreCommand command) {
        return CompletableFuture.supplyAsync(() -> handle(command), VIRTUAL_EXECUTOR);
    }

    private void notifyListeners(RestoreEvent event) {
        VIRTUAL_EXECUTOR.submit(() -> {
            for (Consumer<RestoreEvent> listener : eventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    System.err.println("[RestoreCommandHandler] Error in reactive listener: " + e.getMessage());
                }
            }
        });
    }

    public int getListenerCount() {
        return eventListeners.size();
    }

    public void clearListeners() {
        eventListeners.clear();
    }
}
