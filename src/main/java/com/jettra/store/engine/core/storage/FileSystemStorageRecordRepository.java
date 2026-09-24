package com.jettra.store.engine.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * High-performance file system implementation of StorageRecordRepository (Repository Pattern).
 * Employs Java 25 Virtual Threads for non-blocking concurrent disk I/O operations and
 * persists each record in an individual optimized .dat file under:
 * {rootPath}/databases/{DatabaseName}/{engineName}/{unitName}/<record_id>.dat
 */
public class FileSystemStorageRecordRepository implements StorageRecordRepository {

    private final RecordSerializationStrategy strategy;

    public FileSystemStorageRecordRepository() {
        this(StorageEngineFactory.getDefaultStrategy());
    }

    public FileSystemStorageRecordRepository(RecordSerializationStrategy strategy) {
        this.strategy = (strategy != null) ? strategy : StorageEngineFactory.getDefaultStrategy();
    }

    public RecordSerializationStrategy getSerializationStrategy() {
        return strategy;
    }

    @Override
    public void save(StorageRecordPath path, byte[] payload, long timestamp, int version) throws IOException {
        if (path == null) return;
        Path targetFile = path.filePath();
        Path parent = targetFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        byte[] serialized = strategy.serialize(path.recordId(), version, timestamp, payload);
        Files.write(targetFile, serialized,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
    }

    @Override
    public void saveBatch(List<RecordWriteTask> tasks) throws IOException {
        if (tasks == null || tasks.isEmpty()) return;

        final int chunkSize = 2000;
        int total = tasks.size();
        for (int i = 0; i < total; i += chunkSize) {
            List<RecordWriteTask> subList = tasks.subList(i, Math.min(total, i + chunkSize));
            try (ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Callable<Void>> callables = new ArrayList<>(subList.size());
                for (RecordWriteTask task : subList) {
                    callables.add(() -> {
                        save(task.path(), task.payload(), task.timestamp(), task.version());
                        return null;
                    });
                }
                List<Future<Void>> futures = vThreadExecutor.invokeAll(callables);
                for (Future<Void> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Batch write interrupted during virtual thread execution", e);
            } catch (Exception e) {
                throw new IOException("Error during concurrent virtual thread batch write: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Optional<StorageRecordFile> find(StorageRecordPath path) {
        if (path == null) return Optional.empty();
        Path target = resolveExistingFile(path);
        if (target == null || !Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            StorageRecordFile record = strategy.deserialize(path.recordId(), bytes);
            return Optional.ofNullable(record);
        } catch (IOException e) {
            System.err.println("Warning reading record file [" + target + "]: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public byte[] readPayload(StorageRecordPath path) {
        if (path == null) return null;
        Path target = resolveExistingFile(path);
        if (target == null || !Files.exists(target)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return strategy.extractPayload(bytes);
        } catch (IOException e) {
            System.err.println("Warning reading record payload [" + target + "]: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean delete(StorageRecordPath path) {
        if (path == null) return false;
        Path target = resolveExistingFile(path);
        if (target == null || !Files.exists(target)) return false;
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            System.err.println("Warning deleting record file [" + target + "]: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(StorageRecordPath path) {
        if (path == null) return false;
        Path target = resolveExistingFile(path);
        return target != null && Files.exists(target);
    }

    private Path resolveExistingFile(StorageRecordPath path) {
        if (path == null) return null;
        if (Files.exists(path.filePath())) {
            return path.filePath();
        }
        // Fallback: check other units under the same engine directory
        Path engineDir = path.engineDirectory();
        if (engineDir != null && Files.exists(engineDir) && Files.isDirectory(engineDir)) {
            String targetFileName = path.recordId() + ".dat";
            try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                for (Path uDir : unitDirs) {
                    if (Files.isDirectory(uDir) && !uDir.equals(path.unitDirectory())) {
                        Path candidate = uDir.resolve(targetFileName);
                        if (Files.exists(candidate)) {
                            return candidate;
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    @Override
    public List<StorageRecordPath> listRecords(Path rootDir, String database, String engine, String unit) {
        if (rootDir == null || database == null) return Collections.emptyList();
        Path dbBase = "_system".equalsIgnoreCase(database)
            ? rootDir.resolve("system")
            : rootDir.resolve("databases").resolve(database);

        Path unitDir = dbBase;
        if (engine != null && !engine.isBlank()) {
            unitDir = unitDir.resolve(engine.toLowerCase());
            if (unit != null && !unit.isBlank()) {
                unitDir = unitDir.resolve(unit.toLowerCase());
            }
        }

        if (!Files.exists(unitDir) || !Files.isDirectory(unitDir)) {
            return Collections.emptyList();
        }

        List<StorageRecordPath> results = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, "*.dat")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                String recId = fileName.substring(0, fileName.length() - 4);
                results.add(new StorageRecordPath(
                    rootDir,
                    database,
                    engine != null ? engine : "document",
                    unit != null ? unit : "default",
                    recId,
                    p
                ));
            }
        } catch (IOException ignored) {}

        return results;
    }

    @Override
    public long countRecords(Path rootDir, String database, String engine, String unit) {
        if (rootDir == null || database == null) return 0L;
        Path dbBase = "_system".equalsIgnoreCase(database)
            ? rootDir.resolve("system")
            : rootDir.resolve("databases").resolve(database);

        Path unitDir = dbBase;
        if (engine != null && !engine.isBlank()) {
            unitDir = unitDir.resolve(engine.toLowerCase());
            if (unit != null && !unit.isBlank()) {
                unitDir = unitDir.resolve(unit.toLowerCase());
            }
        }

        if (!Files.exists(unitDir) || !Files.isDirectory(unitDir)) {
            return 0L;
        }

        long count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, "*.dat")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (IOException ignored) {}
        return count;
    }

    @Override
    public void dropDatabase(Path rootDir, String database) {
        if (rootDir == null || database == null || database.isBlank()) return;
        Path dbBase = "_system".equalsIgnoreCase(database)
            ? rootDir.resolve("system")
            : rootDir.resolve("databases").resolve(database);

        deleteDirectoryRecursively(dbBase);
    }

    private static void deleteDirectoryRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    deleteDirectoryRecursively(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {}
    }
}
