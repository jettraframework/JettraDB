package com.jettra.store.engine.users;

import com.jettra.store.engine.exception.ImmutableAccountException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Enterprise-grade implementation of SystemUserRepository storing users
 * exclusively in system_db under /data/system_db/.
 * Supports thread-safe reads/writes via ReentrantReadWriteLock and file-level isolation.
 */
public class SystemUserRepositoryImpl implements SystemUserRepository {

    public static final String CANONICAL_SYSTEM_PATH = "/data/node1/system";
    public static final String CANONICAL_SYSTEM_DB_PATH = "/data/system_db";
    private static final String USERS_SUBDIR = "users";

    private final Path storagePath;
    private final Path usersDirectory;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    public SystemUserRepositoryImpl() {
        this(resolveDefaultStoragePath());
    }

    public SystemUserRepositoryImpl(Path customPath) {
        if (customPath == null) {
            this.storagePath = resolveDefaultStoragePath();
        } else {
            this.storagePath = customPath;
        }
        this.usersDirectory = this.storagePath.resolve(USERS_SUBDIR);
        initStorage();
    }

    private static Path resolveDefaultStoragePath() {
        String configured = System.getProperty("jettra.system_db.path");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }

        Path canonicalNode1 = Paths.get(CANONICAL_SYSTEM_PATH);
        try {
            if (!Files.exists(canonicalNode1)) {
                Files.createDirectories(canonicalNode1);
            }
            Path testFile = canonicalNode1.resolve(".perm_test_" + UUID.randomUUID());
            Files.writeString(testFile, "test");
            Files.deleteIfExists(testFile);
            return canonicalNode1;
        } catch (Exception ignored) {}

        Path canonicalSysDb = Paths.get(CANONICAL_SYSTEM_DB_PATH);
        try {
            if (!Files.exists(canonicalSysDb)) {
                Files.createDirectories(canonicalSysDb);
            }
            Path testFile = canonicalSysDb.resolve(".perm_test_" + UUID.randomUUID());
            Files.writeString(testFile, "test");
            Files.deleteIfExists(testFile);
            return canonicalSysDb;
        } catch (Exception ignored) {}

        // Non-root or sandbox environment fallback: prefer ./data/node1/system or existing ./data/system_db
        String userDir = System.getProperty("user.dir", ".");
        Path localNode1 = Paths.get(userDir, "data", "node1", "system");
        Path localSysDb = Paths.get(userDir, "data", "system_db");
        if (Files.exists(localSysDb) && !Files.exists(localNode1)) {
            return localSysDb;
        }
        return localNode1;
    }

    private void initStorage() {
        rwLock.writeLock().lock();
        try {
            if (!Files.exists(usersDirectory)) {
                Files.createDirectories(usersDirectory);
            }
            bootstrapAdminIfEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize system_db storage at " + usersDirectory, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void bootstrapAdminIfEmpty() {
        if (countUsersInternal() == 0) {
            SystemUser admin = SystemUser.create(
                "admin",
                hashPassword("admin"),
                "admin@jettra.io",
                "DB_ADMIN",
                Set.of("*")
            );
            persistUserInternal(admin);
        }
    }

    @Override
    public SystemUser save(SystemUser user) {
        if (user == null) {
            throw new IllegalArgumentException("SystemUser cannot be null");
        }
        rwLock.writeLock().lock();
        try {
            // Case-insensitive uniqueness check for duplicate identity
            for (SystemUser existing : readAllInternal()) {
                if (existing.username().equalsIgnoreCase(user.username())) {
                    if (!existing.id().equals(user.id())) {
                        throw new IllegalArgumentException(
                            "Duplicate username detected in system_db: '" + user.username() + "' is already assigned to user ID " + existing.id()
                        );
                    }
                }
            }
            persistUserInternal(user);
            return user;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<SystemUser> findById(UUID id) {
        if (id == null) return Optional.empty();
        rwLock.readLock().lock();
        try {
            Path file = usersDirectory.resolve(id.toString() + ".jdb");
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.ofNullable(readUserFromFile(file));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<SystemUser> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String clean = username.trim();
        rwLock.readLock().lock();
        try {
            return readAllInternal().stream()
                .filter(u -> u.username().equalsIgnoreCase(clean))
                .findFirst();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public List<SystemUser> findAll() {
        rwLock.readLock().lock();
        try {
            List<SystemUser> list = new ArrayList<>(readAllInternal());
            list.sort(Comparator.comparing(SystemUser::username, String.CASE_INSENSITIVE_ORDER));
            return Collections.unmodifiableList(list);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(UUID id) {
        if (id == null) return false;
        rwLock.writeLock().lock();
        try {
            Optional<SystemUser> userOpt = findById(id);
            if (userOpt.isEmpty()) {
                return false;
            }
            SystemUser user = userOpt.get();
            if ("admin".equalsIgnoreCase(user.username())) {
                throw new ImmutableAccountException("El usuario admin no puede ser revocado.");
            }
            Path file = usersDirectory.resolve(id.toString() + ".jdb");
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Error deleting system user with ID " + id, e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean deleteByUsername(String username) {
        if (username == null || username.isBlank()) return false;
        rwLock.writeLock().lock();
        try {
            Optional<SystemUser> userOpt = findByUsername(username);
            if (userOpt.isEmpty()) {
                return false;
            }
            return delete(userOpt.get().id());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public long count() {
        rwLock.readLock().lock();
        try {
            return countUsersInternal();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Path getStoragePath() {
        return storagePath;
    }

    public Path getUsersDirectory() {
        return usersDirectory;
    }

    private long countUsersInternal() {
        if (!Files.exists(usersDirectory)) return 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(usersDirectory, "*.jdb")) {
            long count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private List<SystemUser> readAllInternal() {
        List<SystemUser> users = new ArrayList<>();
        if (!Files.exists(usersDirectory)) return users;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(usersDirectory, "*.jdb")) {
            for (Path file : stream) {
                SystemUser u = readUserFromFile(file);
                if (u != null) {
                    users.add(u);
                }
            }
        } catch (IOException ignored) {}
        return users;
    }

    private void persistUserInternal(SystemUser user) {
        Path file = usersDirectory.resolve(user.id().toString() + ".jdb");
        Path tempFile = usersDirectory.resolve(user.id().toString() + ".tmp");
        try {
            try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
                oos.writeObject(user);
                oos.flush();
            }
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
            throw new UncheckedIOException("Failed to persist user " + user.username() + " to system_db at " + file, e);
        }
    }

    private SystemUser readUserFromFile(Path file) {
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return (SystemUser) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static String hashPassword(String plainPassword) {
        if (plainPassword == null) plainPassword = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plainPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
