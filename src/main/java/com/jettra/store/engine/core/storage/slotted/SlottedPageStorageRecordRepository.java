package com.jettra.store.engine.core.storage.slotted;

import com.jettra.store.engine.core.storage.StorageRecordFile;
import com.jettra.store.engine.core.storage.StorageRecordPath;
import com.jettra.store.engine.core.storage.StorageRecordRepository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.jettra.store.engine.core.storage.slotted.SlottedPageConstants.*;

/**
 * Slotted-Page implementation of {@link StorageRecordRepository}.
 *
 * <p>Consolidates all records of a storage unit into single fixed-size slotted page
 * files strictly ending with {@code .jetrra} or {@code .jettra}, eliminating file system inode exhaustion,
 * reducing directory fragmentation, and accelerating transactional I/O.
 */
public class SlottedPageStorageRecordRepository implements StorageRecordRepository, AutoCloseable {

    private final BufferPool sharedBufferPool;
    private final Map<Path, SlottedPageManager> managers;
    private final int pageSize;
    private final String defaultExtension;

    public SlottedPageStorageRecordRepository(int pageSize, int maxBufferPages, String defaultExtension) {
        this.pageSize = (pageSize >= SlottedPageConstants.PAGE_SIZE_4KB) ? pageSize : SlottedPageConstants.PAGE_SIZE_64KB;
        this.sharedBufferPool = new BufferPool(maxBufferPages, this.pageSize, true, new LruPageEvictionStrategy());
        this.managers = new ConcurrentHashMap<>();
        this.defaultExtension = (defaultExtension != null && !defaultExtension.isBlank()) ? defaultExtension : FILE_EXTENSION;
    }

    public SlottedPageStorageRecordRepository(int pageSize, int maxBufferPages) {
        this(pageSize, maxBufferPages, FILE_EXTENSION);
    }

    public SlottedPageStorageRecordRepository() {
        this(SlottedPageConstants.PAGE_SIZE_64KB, 512, FILE_EXTENSION);
    }

    private Path resolveJetrraFilePath(StorageRecordPath path) {
        Path unitDir = path.unitDirectory();
        Path jetrra = unitDir.resolve("store" + FILE_EXTENSION);
        if (Files.exists(jetrra)) return jetrra;
        Path jettra = unitDir.resolve("store" + ALT_FILE_EXTENSION);
        if (Files.exists(jettra)) return jettra;
        String ext = defaultExtension.startsWith(".") ? defaultExtension : "." + defaultExtension;
        return unitDir.resolve("store" + ext);
    }

    private synchronized SlottedPageManager getOrCreateManager(Path jetrraFile) throws IOException {
        SlottedPageManager mgr = managers.get(jetrraFile);
        if (mgr == null) {
            mgr = new SlottedPageManager(jetrraFile, pageSize, sharedBufferPool, new BestFitAllocationStrategy(), new InPlacePageCompactionStrategy());
            managers.put(jetrraFile, mgr);
        }
        return mgr;
    }

    @Override
    public void save(StorageRecordPath path, byte[] payload, long timestamp, int version) throws IOException {
        if (path == null) return;
        Path jetrraFile = resolveJetrraFilePath(path);
        SlottedPageManager mgr = getOrCreateManager(jetrraFile);
        mgr.insert(path.recordId(), payload, version, timestamp);
    }

    @Override
    public void saveBatch(List<RecordWriteTask> tasks) throws IOException {
        if (tasks == null || tasks.isEmpty()) return;

        // Group tasks by unit/jetrra file
        Map<Path, List<RecordWriteTask>> grouped = new HashMap<>();
        for (RecordWriteTask task : tasks) {
            Path jFile = resolveJetrraFilePath(task.path());
            grouped.computeIfAbsent(jFile, k -> new ArrayList<>()).add(task);
        }

        try (ExecutorService vThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> callables = new ArrayList<>();
            for (Map.Entry<Path, List<RecordWriteTask>> entry : grouped.entrySet()) {
                callables.add(() -> {
                    SlottedPageManager mgr = getOrCreateManager(entry.getKey());
                    for (RecordWriteTask t : entry.getValue()) {
                        mgr.insert(t.path().recordId(), t.payload(), t.version(), t.timestamp());
                    }
                    mgr.sync();
                    return null;
                });
            }
            List<Future<Void>> futures = vThreads.invokeAll(callables);
            for (Future<Void> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Batch write interrupted during virtual thread execution", e);
        } catch (Exception e) {
            throw new IOException("Error during slotted page batch write: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StorageRecordFile> find(StorageRecordPath path) {
        if (path == null) return Optional.empty();
        Path jetrraFile = resolveJetrraFilePath(path);
        try {
            if (Files.exists(jetrraFile)) {
                SlottedPageManager mgr = getOrCreateManager(jetrraFile);
                Page.RecordEntry entry = mgr.getRecordEntry(path.recordId());
                if (entry != null) {
                    return Optional.of(new StorageRecordFile(entry.key(), entry.version(), entry.timestamp(), entry.payload()));
                }
            }
        } catch (IOException e) {
            System.err.println("Warning reading slotted record [" + path.recordId() + "]: " + e.getMessage());
        }

        // Fallback: check sibling units under the same engine directory
        Path engineDir = path.engineDirectory();
        if (engineDir != null && Files.exists(engineDir) && Files.isDirectory(engineDir)) {
            try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                for (Path uDir : unitDirs) {
                    if (Files.isDirectory(uDir) && !uDir.equals(path.unitDirectory())) {
                        try (DirectoryStream<Path> slottedFiles = Files.newDirectoryStream(uDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
                            for (Path sf : slottedFiles) {
                                SlottedPageManager m = getOrCreateManager(sf);
                                Page.RecordEntry e = m.getRecordEntry(path.recordId());
                                if (e != null) {
                                    return Optional.of(new StorageRecordFile(e.key(), e.version(), e.timestamp(), e.payload()));
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback: check legacy .dat file if present
        if (Files.exists(path.filePath())) {
            try {
                byte[] bytes = Files.readAllBytes(path.filePath());
                return Optional.ofNullable(StorageRecordFile.deserialize(path.recordId(), bytes));
            } catch (Exception ignored) {}
        }

        return Optional.empty();
    }

    @Override
    public byte[] readPayload(StorageRecordPath path) {
        if (path == null) return null;
        Path jetrraFile = resolveJetrraFilePath(path);
        try {
            if (Files.exists(jetrraFile)) {
                SlottedPageManager mgr = getOrCreateManager(jetrraFile);
                byte[] payload = mgr.get(path.recordId());
                if (payload != null) {
                    return payload;
                }
            }
        } catch (IOException e) {
            System.err.println("Warning reading slotted payload [" + path.recordId() + "]: " + e.getMessage());
        }

        // Fallback: check sibling units under the same engine directory
        Path engineDir = path.engineDirectory();
        if (engineDir != null && Files.exists(engineDir) && Files.isDirectory(engineDir)) {
            try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                for (Path uDir : unitDirs) {
                    if (Files.isDirectory(uDir) && !uDir.equals(path.unitDirectory())) {
                        try (DirectoryStream<Path> slottedFiles = Files.newDirectoryStream(uDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
                            for (Path sf : slottedFiles) {
                                SlottedPageManager m = getOrCreateManager(sf);
                                byte[] p = m.get(path.recordId());
                                if (p != null) return p;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback to legacy .dat
        if (Files.exists(path.filePath())) {
            try {
                byte[] bytes = Files.readAllBytes(path.filePath());
                return StorageRecordFile.extractPayload(bytes);
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public boolean delete(StorageRecordPath path) {
        if (path == null) return false;
        Path jetrraFile = resolveJetrraFilePath(path);
        boolean deleted = false;
        try {
            if (Files.exists(jetrraFile)) {
                SlottedPageManager mgr = getOrCreateManager(jetrraFile);
                deleted = mgr.delete(path.recordId());
            }
        } catch (IOException ignored) {}

        if (!deleted) {
            // Check sibling units
            Path engineDir = path.engineDirectory();
            if (engineDir != null && Files.exists(engineDir) && Files.isDirectory(engineDir)) {
                try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                    for (Path uDir : unitDirs) {
                        if (Files.isDirectory(uDir) && !uDir.equals(path.unitDirectory())) {
                            try (DirectoryStream<Path> slottedFiles = Files.newDirectoryStream(uDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
                                for (Path sf : slottedFiles) {
                                    SlottedPageManager m = getOrCreateManager(sf);
                                    if (m.exists(path.recordId())) {
                                        deleted |= m.delete(path.recordId());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        if (Files.exists(path.filePath())) {
            try {
                deleted |= Files.deleteIfExists(path.filePath());
            } catch (IOException ignored) {}
        }
        return deleted;
    }

    @Override
    public boolean exists(StorageRecordPath path) {
        if (path == null) return false;
        Path jetrraFile = resolveJetrraFilePath(path);
        if (Files.exists(jetrraFile)) {
            try {
                SlottedPageManager mgr = getOrCreateManager(jetrraFile);
                if (mgr.exists(path.recordId())) {
                    return true;
                }
            } catch (IOException ignored) {}
        }

        // Fallback to sibling units
        Path engineDir = path.engineDirectory();
        if (engineDir != null && Files.exists(engineDir) && Files.isDirectory(engineDir)) {
            try (DirectoryStream<Path> unitDirs = Files.newDirectoryStream(engineDir)) {
                for (Path uDir : unitDirs) {
                    if (Files.isDirectory(uDir) && !uDir.equals(path.unitDirectory())) {
                        try (DirectoryStream<Path> slottedFiles = Files.newDirectoryStream(uDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
                            for (Path sf : slottedFiles) {
                                SlottedPageManager m = getOrCreateManager(sf);
                                if (m.exists(path.recordId())) return true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return Files.exists(path.filePath());
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
        Set<String> seenIds = new HashSet<>();

        // 1. Scan all slotted-page files (.jetrra and .jettra)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
            for (Path slottedFile : stream) {
                try {
                    SlottedPageManager mgr = getOrCreateManager(slottedFile);
                    for (String recId : mgr.listKeys()) {
                        if (seenIds.add(recId)) {
                            results.add(new StorageRecordPath(
                                rootDir, database,
                                engine != null ? engine : "document",
                                unit != null ? unit : "default",
                                recId,
                                unitDir.resolve(recId + ".dat")
                            ));
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}

        // 2. Also add legacy .dat if any
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, "*.dat")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                String recId = fileName.substring(0, fileName.length() - 4);
                if (seenIds.add(recId)) {
                    results.add(new StorageRecordPath(
                        rootDir, database,
                        engine != null ? engine : "document",
                        unit != null ? unit : "default",
                        recId, p
                    ));
                }
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
        Set<String> slottedKeys = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, p -> SlottedPageConstants.isSlottedFile(p.getFileName().toString()))) {
            for (Path slottedFile : stream) {
                try {
                    SlottedPageManager mgr = getOrCreateManager(slottedFile);
                    for (String k : mgr.listKeys()) {
                        if (slottedKeys.add(k)) {
                            count++;
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}

        // Fallback to legacy count for files not already counted
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unitDir, "*.dat")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                String recId = fileName.substring(0, fileName.length() - 4);
                if (!slottedKeys.contains(recId)) {
                    count++;
                }
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

        // Close any managers under dbBase
        for (Iterator<Map.Entry<Path, SlottedPageManager>> it = managers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, SlottedPageManager> entry = it.next();
            if (entry.getKey().startsWith(dbBase)) {
                try {
                    entry.getValue().close();
                } catch (Exception ignored) {}
                it.remove();
            }
        }

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

    @Override
    public void close() throws Exception {
        for (SlottedPageManager mgr : managers.values()) {
            try {
                mgr.close();
            } catch (Exception ignored) {}
        }
        managers.clear();
        sharedBufferPool.close();
    }
}
