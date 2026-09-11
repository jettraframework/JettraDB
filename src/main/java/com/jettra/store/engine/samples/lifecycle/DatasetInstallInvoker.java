package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.hierarchy.HierarchyResult;
import com.jettra.store.engine.samples.SampleDatasetManager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Command Invoker for dataset installation pipelines in JettraDB.
 * Manages synchronous and asynchronous execution using Java 25 Virtual Threads.
 */
public class DatasetInstallInvoker {

    private final SampleDatasetManager datasetManager;
    private final ExecutorService executor;

    public DatasetInstallInvoker(SampleDatasetManager datasetManager) {
        this.datasetManager = Objects.requireNonNull(datasetManager, "SampleDatasetManager must not be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executes the given dataset installation command synchronously.
     *
     * @param command installation command
     * @return result of execution
     */
    public HierarchyResult<Integer> execute(DatasetInstallCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        return command.execute();
    }

    /**
     * Executes installation for a specific target database by constructing and executing the command.
     *
     * @param targetDb target database name
     * @return result of execution
     */
    public HierarchyResult<Integer> executeInstall(String targetDb) {
        InstallSingleDatasetCommand cmd = new InstallSingleDatasetCommand(datasetManager, targetDb);
        return execute(cmd);
    }

    /**
     * Executes installation for a target database asynchronously on Java 25 Virtual Threads.
     *
     * @param targetDb target database name
     * @return CompletableFuture holding the execution result
     */
    public CompletableFuture<HierarchyResult<Integer>> executeInstallAsync(String targetDb) {
        return CompletableFuture.supplyAsync(() -> executeInstall(targetDb), executor);
    }
}
