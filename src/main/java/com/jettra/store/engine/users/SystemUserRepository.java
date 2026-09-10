package com.jettra.store.engine.users;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing SystemUser entities strictly and exclusively
 * stored in system_db (/data/system_db/).
 */
public interface SystemUserRepository extends AutoCloseable {

    /**
     * Persists or updates a SystemUser into system_db.
     *
     * @param user the user to persist
     * @return the saved user entity
     */
    SystemUser save(SystemUser user);

    /**
     * Finds a user by their UUID primary key.
     *
     * @param id the user id
     * @return an Optional containing the user if found
     */
    Optional<SystemUser> findById(UUID id);

    /**
     * Finds a user by username using case-insensitive comparison.
     *
     * @param username the username to find
     * @return an Optional containing the user if found
     */
    Optional<SystemUser> findByUsername(String username);

    /**
     * Retrieves all system users.
     *
     * @return list of all system users
     */
    List<SystemUser> findAll();

    /**
     * Deletes a user by UUID.
     * In accordance with JettraDB Identity Preservation Policy, physical deletion is prohibited.
     *
     * @param id the user id
     * @return true if deleted, false otherwise
     * @throws com.jettra.store.engine.exception.UnsupportedUserDeletionException always
     */
    boolean delete(UUID id);

    /**
     * Deletes a user by username.
     * In accordance with JettraDB Identity Preservation Policy, physical deletion is prohibited.
     *
     * @param username the username
     * @return true if deleted, false otherwise
     * @throws com.jettra.store.engine.exception.UnsupportedUserDeletionException always
     */
    boolean deleteByUsername(String username);

    /**
     * Internal test harness purge method strictly for testing lifecycle teardowns.
     *
     * @param id the user id
     * @return true if test file purged
     */
    default boolean purgeTestUserForTestingOnly(UUID id) {
        return false;
    }

    /**
     * Checks if a user exists with the given username (case-insensitive).
     *
     * @param username the username to check
     * @return true if exists, false otherwise
     */
    boolean existsByUsername(String username);

    /**
     * Returns the total count of system users.
     *
     * @return total user count
     */
    long count();

    /**
     * Returns the canonical disk storage path for system_db users.
     *
     * @return path to storage directory
     */
    Path getStoragePath();

    @Override
    default void close() {}
}
