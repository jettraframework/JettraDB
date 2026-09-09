package com.jettra.store.engine.exception;

/**
 * Thrown when an administrative operation violates immutability safeguards,
 * such as attempting to revoke or delete the root 'admin' user,
 * or attempting to modify the 'admin' user from an unauthorized session.
 */
public class ImmutableAccountException extends io.jettra.server.autentification.exception.ImmutableAccountException {

    public ImmutableAccountException(String message) {
        super(message);
    }

    public ImmutableAccountException(String message, Throwable cause) {
        super(message, cause);
    }
}
