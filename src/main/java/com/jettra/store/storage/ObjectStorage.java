package com.jettra.store.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Core storage mechanism for JettraStore Engine.
 * Supports concurrent reads and exclusive writes.
 */
public class ObjectStorage {
    
    private final String baseDir;
    private final ConcurrentHashMap<Class<?>, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public ObjectStorage(String baseDir) {
        this.baseDir = baseDir;
        File dir = new File(baseDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private ReentrantReadWriteLock getLock(Class<?> clazz) {
        return locks.computeIfAbsent(clazz, k -> new ReentrantReadWriteLock(true));
    }

    private File getCollectionDir(Class<?> clazz) {
        File dir = new File(baseDir + File.separator + clazz.getSimpleName().toLowerCase());
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public record StoredObjectEntry(CompactRecordHeader header, Serializable entity) implements Serializable {}

    public <T extends Serializable> void save(String id, T entity) {
        Class<?> clazz = entity.getClass();
        ReentrantReadWriteLock.WriteLock writeLock = getLock(clazz).writeLock();
        writeLock.lock();
        
        try {
            File dir = getCollectionDir(clazz);
            File file = new File(dir, id + ".jdb");
            
            byte[] binary = io.jettra.ee.serialization.JettraSerialization.serializeObject(
                new StoredObjectEntry(new CompactRecordHeader(id), entity)
            );

            try (FileOutputStream fos = new FileOutputStream(file);
                 FileChannel channel = fos.getChannel();
                 FileLock fileLock = channel.lock()) { // Process-wide lock
                 
                fos.write(binary);
                fos.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error saving object to JettraStore ObjectStorage", e);
        } finally {
            writeLock.unlock();
        }
    }

    public <T extends Serializable> Optional<T> findById(Class<T> clazz, String id) {
        ReentrantReadWriteLock.ReadLock readLock = getLock(clazz).readLock();
        readLock.lock();
        
        try {
            File dir = getCollectionDir(clazz);
            File file = new File(dir, id + ".jdb");
            if (!file.exists()) {
                return Optional.empty();
            }

            try (FileInputStream fis = new FileInputStream(file);
                 FileChannel channel = fis.getChannel();
                 FileLock fileLock = channel.lock(0L, Long.MAX_VALUE, true)) { // Process-wide shared lock
                 
                byte[] bytes = fis.readAllBytes();
                if (io.jettra.ee.serialization.JettraSerialization.isJettraBinary(bytes)) {
                    StoredObjectEntry entry = io.jettra.ee.serialization.JettraSerialization.deserializeObject(bytes, StoredObjectEntry.class);
                    if (entry == null || entry.header().deleted()) {
                        return Optional.empty();
                    }
                    @SuppressWarnings("unchecked")
                    T entity = (T) entry.entity();
                    return Optional.ofNullable(entity);
                } else {
                    // Legacy fallback
                    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                        CompactRecordHeader header = (CompactRecordHeader) ois.readObject();
                        if (header.deleted()) {
                            return Optional.empty();
                        }
                        @SuppressWarnings("unchecked")
                        T entity = (T) ois.readObject();
                        return Optional.of(entity);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Error reading object from JettraStore ObjectStorage", e);
        } finally {
            readLock.unlock();
        }
    }
    
    public <T extends Serializable> List<T> findAll(Class<T> clazz) {
        ReentrantReadWriteLock.ReadLock readLock = getLock(clazz).readLock();
        readLock.lock();
        try {
            File dir = getCollectionDir(clazz);
            List<T> results = new ArrayList<>();
            
            File[] files = dir.listFiles((d, name) -> name.endsWith(".jdb"));
            if (files != null) {
                for (File file : files) {
                    try (FileInputStream fis = new FileInputStream(file);
                         FileChannel channel = fis.getChannel();
                         FileLock fileLock = channel.lock(0L, Long.MAX_VALUE, true)) {
                         
                        byte[] bytes = fis.readAllBytes();
                        if (io.jettra.ee.serialization.JettraSerialization.isJettraBinary(bytes)) {
                            StoredObjectEntry entry = io.jettra.ee.serialization.JettraSerialization.deserializeObject(bytes, StoredObjectEntry.class);
                            if (entry != null && !entry.header().deleted()) {
                                @SuppressWarnings("unchecked")
                                T entity = (T) entry.entity();
                                results.add(entity);
                            }
                        } else {
                            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                                CompactRecordHeader header = (CompactRecordHeader) ois.readObject();
                                if (!header.deleted()) {
                                    @SuppressWarnings("unchecked")
                                    T entity = (T) ois.readObject();
                                    results.add(entity);
                                }
                            }
                        }
                    } catch (IOException | ClassNotFoundException ignored) {
                        // Ignore concurrently deleted/corrupted files
                    }
                }
            }
            return results;
        } finally {
            readLock.unlock();
        }
    }

    public <T extends Serializable> void delete(Class<T> clazz, String id) {
        ReentrantReadWriteLock.WriteLock writeLock = getLock(clazz).writeLock();
        writeLock.lock();
        try {
            File dir = getCollectionDir(clazz);
            File file = new File(dir, id + ".jdb");
            if (file.exists()) {
                file.delete();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public <T extends Serializable> List<T> search(Class<T> clazz, Predicate<T> predicate) {
        return findAll(clazz).stream().filter(predicate).collect(Collectors.toList());
    }

    public <T extends Serializable> List<T> search(Class<T> clazz, Predicate<T> predicate, int offset, int limit) {
        return findAll(clazz).stream()
                .filter(predicate)
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }
}
