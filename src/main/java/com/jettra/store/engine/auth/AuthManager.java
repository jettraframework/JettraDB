package com.jettra.store.engine.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages JettraStoreEngine authentication, users, and tokens.
 */
public class AuthManager {
    
    public static final String SUPER_USER = "super-user";
    private static final String DEFAULT_PASSWORD = "superUserZ"; // Should be hashed in production
    
    private final Map<String, String> userPasswords;
    private final Map<String, String> activeTokens;
    private final Map<String, Boolean> requiresPasswordChange;

    public AuthManager() {
        this.userPasswords = new ConcurrentHashMap<>();
        this.activeTokens = new ConcurrentHashMap<>();
        this.requiresPasswordChange = new ConcurrentHashMap<>();
        
        // Initialize default administrative credentials
        userPasswords.put("admin", "admin");
        userPasswords.put(SUPER_USER, DEFAULT_PASSWORD);
        requiresPasswordChange.put(SUPER_USER, true);
    }

    /**
     * Authenticates a user and returns a token if successful.
     */
    public String login(String username, String password) throws Exception {
        String storedPassword = userPasswords.get(username);
        if (storedPassword != null && storedPassword.equals(password)) {
            if (requiresPasswordChange.getOrDefault(username, false)) {
                // Return a special token or status that requires them to change password.
                // For simplicity in this iteration, we just return a normal token but flag it.
                System.out.println("User " + username + " must change their password!");
            }
            String token = UUID.randomUUID().toString(); // Generate simple token (In reality, JWT)
            activeTokens.put(token, username);
            return token;
        }
        throw new Exception("Invalid credentials");
    }

    /**
     * Changes a user's password.
     */
    public void changePassword(String username, String oldPassword, String newPassword) throws Exception {
        String storedPassword = userPasswords.get(username);
        if (storedPassword != null && storedPassword.equals(oldPassword)) {
            userPasswords.put(username, newPassword);
            requiresPasswordChange.put(username, false);
        } else {
            throw new Exception("Invalid old password");
        }
    }

    /**
     * Validates if a token is active.
     */
    public boolean validateToken(String token) {
        return activeTokens.containsKey(token);
    }
}
