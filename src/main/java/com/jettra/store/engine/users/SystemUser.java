package com.jettra.store.engine.users;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Modern Java 25 record representing a system user entity in JettraDB.
 * Stored and managed exclusively within system_db (/data/system_db/).
 */
public record SystemUser(
    UUID id,
    String username,
    String passwordHash,
    String email,
    String role,
    boolean active,
    Set<String> assignedDatabases,
    Instant createdAt,
    Instant updatedAt
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SystemUser {
        id = id != null ? id : UUID.randomUUID();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or blank");
        }
        username = username.trim();
        passwordHash = passwordHash != null ? passwordHash : "";
        email = email != null ? email.trim() : username + "@jettra.io";
        role = (role != null && !role.isBlank()) ? role.trim() : "READ_WRITE";
        
        Set<String> dbs = new TreeSet<>();
        if (assignedDatabases != null && !assignedDatabases.isEmpty()) {
            for (String db : assignedDatabases) {
                if (db != null && !db.isBlank()) {
                    dbs.add(db.trim());
                }
            }
        }
        if (dbs.isEmpty()) {
            dbs.add("*");
        }
        assignedDatabases = Collections.unmodifiableSet(dbs);

        createdAt = createdAt != null ? createdAt : Instant.now();
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static SystemUser create(String username, String passwordHash, String email, String role, Set<String> assignedDatabases) {
        return new SystemUser(
            UUID.randomUUID(),
            username,
            passwordHash,
            email,
            role,
            true,
            assignedDatabases,
            Instant.now(),
            Instant.now()
        );
    }

    public SystemUser withUpdatedProfile(String email, String role, Boolean active, Set<String> assignedDatabases) {
        return new SystemUser(
            this.id,
            this.username, // Username remains primary identity
            this.passwordHash,
            email != null ? email : this.email,
            role != null ? role : this.role,
            active != null ? active : this.active,
            assignedDatabases != null ? assignedDatabases : this.assignedDatabases,
            this.createdAt,
            Instant.now()
        );
    }

    public SystemUser withPasswordHash(String newHash) {
        return new SystemUser(
            this.id,
            this.username,
            newHash,
            this.email,
            this.role,
            this.active,
            this.assignedDatabases,
            this.createdAt,
            Instant.now()
        );
    }

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(username) || "DB_ADMIN".equalsIgnoreCase(role) || "SUPERADMIN".equalsIgnoreCase(role);
    }

    public boolean hasDatabaseAccess(String targetDb) {
        if (targetDb == null || targetDb.isBlank()) return false;
        if (assignedDatabases.contains("*")) return true;
        return assignedDatabases.contains(targetDb.trim());
    }
}
