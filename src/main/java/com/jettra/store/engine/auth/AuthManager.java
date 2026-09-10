package com.jettra.store.engine.auth;

import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages JettraStoreEngine authentication, users, and tokens.
 * Centralized against system_db (/data/system_db/) via SystemUserRepository
 * as the single source of truth for Web UI, REST endpoints, drivers, and interactive shell.
 */
public class AuthManager {
    
    public static final String SUPER_USER = "super-user";
    private static final String DEFAULT_PASSWORD = "superUserZ"; // Should be hashed in production
    
    private final SystemUserRepository systemUserRepository;
    private final Map<String, String> userPasswords;
    private final Map<String, String> activeTokens;
    private final Map<String, Boolean> requiresPasswordChange;

    public AuthManager() {
        this(new SystemUserRepositoryImpl());
    }

    public AuthManager(SystemUserRepository systemUserRepository) {
        this.systemUserRepository = systemUserRepository != null ? systemUserRepository : new SystemUserRepositoryImpl();
        this.userPasswords = new ConcurrentHashMap<>();
        this.activeTokens = new ConcurrentHashMap<>();
        this.requiresPasswordChange = new ConcurrentHashMap<>();
        
        // Ensure baseline credentials in memory cache and system_db
        userPasswords.put("admin", "admin");

        bootstrapSystemDbCredentials();
    }

    private void bootstrapSystemDbCredentials() {
        try {
            if (!systemUserRepository.existsByUsername("admin")) {
                systemUserRepository.save(SystemUser.create(
                    "admin",
                    SystemUserRepositoryImpl.hashPassword("admin"),
                    "admin@jettra.io",
                    "DB_ADMIN",
                    Set.of("*")
                ));
            }
        } catch (Exception ignored) {}
    }

    public SystemUserRepository getSystemUserRepository() {
        return systemUserRepository;
    }

    /**
     * Authenticates a user and returns a token if successful.
     * Validates primarily against system_db (/data/system_db/).
     */
    public String login(String username, String password) throws Exception {
        if (username == null || password == null) {
            throw new Exception("Invalid credentials");
        }

        String cleanUsername = username.trim();
        Optional<SystemUser> systemUserOpt = systemUserRepository.findByUsername(cleanUsername);

        boolean authenticated = false;
        if (systemUserOpt.isPresent()) {
            SystemUser su = systemUserOpt.get();
            if (!su.active()) {
                throw new Exception("Account is deactivated");
            }
            String hashedInput = SystemUserRepositoryImpl.hashPassword(password);
            if (hashedInput.equalsIgnoreCase(su.passwordHash()) || password.equals(su.passwordHash()) || password.equals(userPasswords.get(cleanUsername))) {
                authenticated = true;
            }
        } else {
            // Fallback for in-memory registered credentials
            String storedPassword = userPasswords.get(cleanUsername);
            if (storedPassword != null && storedPassword.equals(password)) {
                authenticated = true;
            }
        }

        if (authenticated) {
            if (requiresPasswordChange.getOrDefault(cleanUsername, false)) {
                System.out.println("User " + cleanUsername + " must change their password!");
            }
            String token = UUID.randomUUID().toString();
            activeTokens.put(token, cleanUsername);
            return token;
        }

        throw new Exception("Invalid credentials");
    }

    /**
     * Changes a user's password in both system_db and cache.
     */
    public void changePassword(String username, String oldPassword, String newPassword) throws Exception {
        if (username == null || oldPassword == null || newPassword == null) {
            throw new Exception("Invalid parameters");
        }
        String cleanUsername = username.trim();
        if (!authenticate(cleanUsername, oldPassword)) {
            throw new Exception("Invalid old password");
        }

        userPasswords.put(cleanUsername, newPassword);
        requiresPasswordChange.put(cleanUsername, false);

        Optional<SystemUser> userOpt = systemUserRepository.findByUsername(cleanUsername);
        if (userOpt.isPresent()) {
            SystemUser updated = userOpt.get().withPasswordHash(SystemUserRepositoryImpl.hashPassword(newPassword));
            systemUserRepository.save(updated);
        }
    }

    /**
     * Validates if a token is active.
     */
    public boolean validateToken(String token) {
        return token != null && activeTokens.containsKey(token);
    }

    /**
     * Registers a new user with password into active system_db and cache.
     */
    public void register(String username, String password) {
        if (username != null && password != null) {
            String clean = username.trim();
            userPasswords.put(clean, password);

            Optional<SystemUser> existingOpt = systemUserRepository.findByUsername(clean);
            String hash = SystemUserRepositoryImpl.hashPassword(password);
            if (existingOpt.isPresent()) {
                systemUserRepository.save(existingOpt.get().withPasswordHash(hash));
            } else {
                systemUserRepository.save(SystemUser.create(
                    clean,
                    hash,
                    clean + "@jettra.io",
                    "READ_WRITE",
                    Set.of("*")
                ));
            }
        }
    }

    /**
     * Unregisters a user from system_db and authentication cache.
     * In accordance with JettraDB Identity Preservation Policy, physical deletion is prohibited:
     * this deactivates the user record and clears memory caches.
     */
    public void unregister(String username) {
        if (username != null) {
            String clean = username.trim();
            userPasswords.remove(clean);
            requiresPasswordChange.remove(clean);
            systemUserRepository.findByUsername(clean).ifPresent(user -> {
                systemUserRepository.save(user.withActive(false));
            });
        }
    }

    /**
     * Checks if credentials are valid against system_db without creating a session token.
     */
    public boolean authenticate(String username, String password) {
        if (username == null || password == null) return false;
        String clean = username.trim();

        Optional<SystemUser> suOpt = systemUserRepository.findByUsername(clean);
        if (suOpt.isPresent()) {
            SystemUser su = suOpt.get();
            if (!su.active()) return false;
            String hashed = SystemUserRepositoryImpl.hashPassword(password);
            if (hashed.equalsIgnoreCase(su.passwordHash()) || password.equals(su.passwordHash())) {
                return true;
            }
        }

        String stored = userPasswords.get(clean);
        return stored != null && stored.equals(password);
    }
}
