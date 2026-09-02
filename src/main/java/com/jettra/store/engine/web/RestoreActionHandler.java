package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.web.RestoreCommandHandler.RestoreCommand;

import java.util.concurrent.CompletableFuture;

/**
 * Controller and Command Handler adapter for transactional record version rollbacks.
 * Delegates to RestoreCommandHandler for decoupled command pattern execution,
 * Virtual Threads (Thread.ofVirtual()) async execution, and reactive event distribution.
 */
public class RestoreActionHandler {

    private final RestoreCommandHandler commandHandler;

    public RestoreActionHandler(JettraStorageEngine engine) {
        this.commandHandler = new RestoreCommandHandler(engine);
    }

    public RestoreActionHandler(RestoreCommandHandler commandHandler) {
        this.commandHandler = commandHandler;
    }

    public RestoreCommandHandler getCommandHandler() {
        return commandHandler;
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
        RestoreCommand cmd = RestoreCommand.of(engineType, database, collection, recordId, targetTimestamp);
        RestoreCommandHandler.RestoreResult res = commandHandler.handle(cmd);
        return new RestoreResult(
            res.success(),
            res.engineType(),
            res.database(),
            res.collection(),
            res.recordId(),
            res.timestamp(),
            res.message()
        );
    }

    public RestoreResult executeRollback(RollbackCommand rollbackCommand) {
        RestoreCommandHandler.RestoreResult res = commandHandler.handle(rollbackCommand);
        return new RestoreResult(
            res.success(),
            res.engineType(),
            res.database(),
            res.collection(),
            res.recordId(),
            res.timestamp(),
            res.message()
        );
    }

    /**
     * Executes asynchronous rollback using Java 25 Virtual Threads.
     */
    public CompletableFuture<RestoreResult> executeRestoreAsync(String engineType, String database, String collection, String recordId, long targetTimestamp) {
        RestoreCommand cmd = RestoreCommand.of(engineType, database, collection, recordId, targetTimestamp);
        return commandHandler.handleAsync(cmd).thenApply(res -> new RestoreResult(
            res.success(),
            res.engineType(),
            res.database(),
            res.collection(),
            res.recordId(),
            res.timestamp(),
            res.message()
        ));
    }

    public CompletableFuture<RestoreResult> executeRollbackAsync(RollbackCommand rollbackCommand) {
        return commandHandler.handleAsync(rollbackCommand).thenApply(res -> new RestoreResult(
            res.success(),
            res.engineType(),
            res.database(),
            res.collection(),
            res.recordId(),
            res.timestamp(),
            res.message()
        ));
    }
}
