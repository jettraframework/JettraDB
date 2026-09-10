package com.jettra.store.engine.users;

import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Adapter pattern bridging JUserRepository implementations with SystemUserRepository.
 * Enables mock repositories in unit tests and existing components to seamlessly integrate
 * with the SystemUserRepository contract.
 */
public class JUserRepositoryAdapter implements SystemUserRepository {

    private final JUserRepository delegate;
    private final Path storagePath;

    public JUserRepositoryAdapter(JUserRepository delegate) {
        this(delegate, Paths.get(SystemUserRepositoryImpl.CANONICAL_SYSTEM_DB_PATH));
    }

    public JUserRepositoryAdapter(JUserRepository delegate, Path storagePath) {
        this.delegate = Objects.requireNonNull(delegate, "JUserRepository delegate cannot be null");
        this.storagePath = storagePath != null ? storagePath : Paths.get(SystemUserRepositoryImpl.CANONICAL_SYSTEM_DB_PATH);
    }

    @Override
    public SystemUser save(SystemUser user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }

        // Case-insensitive uniqueness validation
        for (SystemUser existing : findAll()) {
            if (existing.username().equalsIgnoreCase(user.username())) {
                if (!existing.id().equals(user.id())) {
                    throw new IllegalArgumentException("Duplicate username in system_db: '" + user.username() + "'");
                }
            }
        }

        Set<JRole> roles = Set.of(new JRole(UUID.randomUUID(), user.role(), true));
        JUser ju = new JUser(
            user.id(),
            user.username(),
            String.join(", ", user.assignedDatabases()),
            user.email(),
            "+123456",
            user.active(),
            roles,
            user.assignedDatabases()
        );
        delegate.save(ju);
        return user;
    }

    @Override
    public Optional<SystemUser> findById(UUID id) {
        if (id == null) return Optional.empty();
        return delegate.findById(id).map(this::toSystemUser);
    }

    @Override
    public Optional<SystemUser> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return delegate.findByUsername(username.trim()).map(this::toSystemUser);
    }

    @Override
    public List<SystemUser> findAll() {
        List<SystemUser> list = new ArrayList<>();
        for (JUser ju : delegate.findAll()) {
            list.add(toSystemUser(ju));
        }
        return list;
    }

    @Override
    public boolean delete(UUID id) {
        if (id == null) return false;
        Optional<JUser> ju = delegate.findById(id);
        if (ju.isEmpty()) return false;
        delegate.delete(id);
        return true;
    }

    @Override
    public boolean deleteByUsername(String username) {
        Optional<JUser> ju = delegate.findByUsername(username);
        if (ju.isEmpty()) return false;
        delegate.delete(ju.get().id());
        return true;
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public long count() {
        return delegate.findAll().size();
    }

    @Override
    public Path getStoragePath() {
        return storagePath;
    }

    private SystemUser toSystemUser(JUser ju) {
        String role = (ju.jRoles() != null && !ju.jRoles().isEmpty()) ? ju.jRoles().iterator().next().name() : "READ_WRITE";
        Set<String> dbs = ju.assignedDatabases() != null ? ju.assignedDatabases() : Set.of("*");
        return new SystemUser(
            ju.id(),
            ju.firstName(),
            "",
            ju.email(),
            role,
            ju.active() != null ? ju.active() : true,
            dbs,
            Instant.now(),
            Instant.now()
        );
    }
}
