package com.jettra.store.engine.exception;

/**
 * Domain exception thrown when an operation attempts to register or assign
 * a username that already exists within the system database (system_db).
 * Enforces strict atomic identity uniqueness across all interfaces.
 */
public class UserAlreadyExistsException extends RuntimeException {

    private final String username;

    public UserAlreadyExistsException(String username) {
        super("El nombre de usuario '" + (username != null ? username.trim() : "") + "' ya está registrado en system_db.");
        this.username = username != null ? username.trim() : "";
    }

    public UserAlreadyExistsException(String username, String message) {
        super(message);
        this.username = username != null ? username.trim() : "";
    }

    public String getUsername() {
        return username;
    }
}
