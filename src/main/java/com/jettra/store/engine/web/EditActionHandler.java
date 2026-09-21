package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.web.workflow.DocumentSaveWorkflow;
import com.jettra.store.engine.web.workflow.StandardDocumentSaveWorkflow;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Controller and Reactive Command Handler for editing multi-model documents and records,
 * creating new immutable versions in JettraDB under Java 25+.
 *
 * Implements:
 * 1. Command Pattern (EditDocumentCommand).
 * 2. Template Method Pattern (DocumentSaveWorkflow).
 * 3. Builder Pattern (DocumentPayloadBuilder).
 * 4. Observer Pattern with thread-safe reactive listeners.
 * 5. Java 25 Virtual Threads (Executors.newVirtualThreadPerTaskExecutor()).
 * 6. Resilience & Explicit Timeouts to prevent indefinite blocking/deadlocks.
 */
public class EditActionHandler {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private final DocumentSaveWorkflow saveWorkflow;
    private final List<Consumer<EditDocumentEvent>> observers = new CopyOnWriteArrayList<>();
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    public EditActionHandler(JettraStorageEngine engine) {
        this(engine, new HierarchyExplorerService(engine));
    }

    public EditActionHandler(JettraStorageEngine engine, HierarchyExplorerService hierarchyService) {
        this.engine = engine;
        this.hierarchyService = hierarchyService != null ? hierarchyService : new HierarchyExplorerService(engine);
        this.saveWorkflow = new StandardDocumentSaveWorkflow(engine, this.hierarchyService, this::notifyObservers);
    }

    public void registerObserver(Consumer<EditDocumentEvent> observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public void removeObserver(Consumer<EditDocumentEvent> observer) {
        if (observer != null) {
            observers.remove(observer);
        }
    }

    private void notifyObservers(EditDocumentEvent event) {
        for (Consumer<EditDocumentEvent> obs : observers) {
            try {
                obs.accept(event);
            } catch (Exception ignored) {
                // Keep event bus resilient
            }
        }
    }

    /**
     * Executes the edit command synchronously using the DocumentSaveWorkflow Template Method.
     */
    public EditDocumentResult executeEdit(EditDocumentCommand cmd) {
        return saveWorkflow.execute(cmd);
    }

    /**
     * Executes the edit command asynchronously using Java 25 Virtual Threads with default timeout.
     */
    public CompletableFuture<EditDocumentResult> executeEditAsync(EditDocumentCommand cmd) {
        return executeEditAsync(cmd, DEFAULT_TIMEOUT);
    }

    /**
     * Executes the edit command asynchronously using Java 25 Virtual Threads with explicit timeout.
     */
    public CompletableFuture<EditDocumentResult> executeEditAsync(EditDocumentCommand cmd, Duration timeout) {
        CompletableFuture<EditDocumentResult> future = CompletableFuture.supplyAsync(() -> executeEdit(cmd), VIRTUAL_EXECUTOR);

        Duration effectiveTimeout = (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? timeout : DEFAULT_TIMEOUT;

        return future.orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                String errorMsg;
                if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                    errorMsg = "Operation timed out after " + effectiveTimeout.toSeconds() + "s. Storage operation suspended.";
                } else {
                    errorMsg = "Async execution failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
                String eng = cmd != null && cmd.engineType() != null ? cmd.engineType() : "DOCUMENT";
                String db = cmd != null && cmd.database() != null ? cmd.database() : "default";
                String coll = cmd != null && cmd.collection() != null ? cmd.collection() : "default";
                String id = cmd != null && cmd.recordId() != null ? cmd.recordId() : "";

                EditDocumentResult timeoutFail = EditDocumentResult.failure(eng, db, coll, id, errorMsg);
                if (cmd != null) {
                    notifyObservers(new EditDocumentFailureEvent(cmd, errorMsg, System.currentTimeMillis()));
                }
                return timeoutFail;
            });
    }

    public JettraStorageEngine getEngine() {
        return engine;
    }

    public HierarchyExplorerService getHierarchyService() {
        return hierarchyService;
    }

    public DocumentSaveWorkflow getSaveWorkflow() {
        return saveWorkflow;
    }
}
