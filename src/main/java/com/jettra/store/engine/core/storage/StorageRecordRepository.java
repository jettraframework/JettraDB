package com.jettra.store.engine.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing individual persistent record files (Repository Pattern).
 */
public interface StorageRecordRepository {

    /**
     * Immutable task definition for batch writes using Java 25 Virtual Threads.
     */
    record RecordWriteTask(
        StorageRecordPath path,
        byte[] payload,
        long timestamp,
        int version
    ) {}

    /**
     * Persists an individual record into its designated .dat file.
     */
    void save(StorageRecordPath path, byte[] payload, long timestamp, int version) throws IOException;

    /**
     * Persists a batch of records concurrently utilizing Java 25 Virtual Threads.
     */
    void saveBatch(List<RecordWriteTask> tasks) throws IOException;

    /**
     * Reads and deserializes a record file if present.
     */
    Optional<StorageRecordFile> find(StorageRecordPath path);

    /**
     * Reads only the payload bytes directly for maximum memory efficiency.
     */
    byte[] readPayload(StorageRecordPath path);

    /**
     * Deletes an individual record file from disk.
     */
    boolean delete(StorageRecordPath path);

    /**
     * Checks if a record file exists on disk.
     */
    boolean exists(StorageRecordPath path);

    /**
     * Lists record paths within a given database, engine, and unit.
     */
    List<StorageRecordPath> listRecords(Path rootDir, String database, String engine, String unit);

    /**
     * Returns total records count for a unit without loading file contents.
     */
    long countRecords(Path rootDir, String database, String engine, String unit);

    /**
     * Purges database engine directory hierarchy from disk.
     */
    void dropDatabase(Path rootDir, String database);
}
