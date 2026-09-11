package com.jettra.store.engine.security;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise Database Security Filter implementing the Filter and Strategy patterns (Java 25+).
 * Cross-references discovered physical databases from disk (/data/node1/databases)
 * and storage core partitions with multi-model security policies, ensuring that authenticated
 * users only visualize and access authorized database namespaces.
 */
public class DatabaseSecurityFilter {

    private final SystemUserRepository systemUserRepo;
    private final JUserRepository userRepo;

    public DatabaseSecurityFilter() {
        this(new SystemUserRepositoryImpl(), new JUserRepositoryImpl());
    }

    public DatabaseSecurityFilter(SystemUserRepository systemUserRepo, JUserRepository userRepo) {
        this.systemUserRepo = systemUserRepo != null ? systemUserRepo : new SystemUserRepositoryImpl();
        this.userRepo = userRepo != null ? userRepo : new JUserRepositoryImpl();
    }

    public SystemUserRepository getSystemUserRepository() {
        return systemUserRepo;
    }

    public JUserRepository getUserRepository() {
        return userRepo;
    }

    /**
     * Discovers all physical databases by combining:
     * 1. Physical directories under storageDirectory/databases/ (/data/node1/databases) and in-memory partitions.
     * 2. Key prefix scans in StorageCore across all multi-model engines.
     * 3. Default core databases (system_db).
     */
    public Set<String> discoverAllPhysicalDatabases(JettraStorageEngine engine) {
        Set<String> databases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        databases.add("system_db");

        if (engine == null || engine.getStorageCore() == null) {
            return databases;
        }

        // 1. Discover physical directories on disk (/data/node1/databases) and active partitions
        try {
            Set<String> physicalNames = engine.getStorageCore().getDatabaseNames();
            if (physicalNames != null) {
                for (String name : physicalNames) {
                    if (name != null && !name.isBlank() && !"_system".equalsIgnoreCase(name)) {
                        databases.add(name.trim());
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Discover databases through storage core multi-model key prefixes
        try {
            String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
            for (String p : prefixes) {
                Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
                if (keys != null) {
                    for (String k : keys.keySet()) {
                        String rest = k.substring(p.length());
                        int colonIdx = rest.indexOf(':');
                        String dbName = colonIdx > 0 ? rest.substring(0, colonIdx) : "";
                        if (!dbName.isBlank() && !dbName.contains("/") && !"_system".equalsIgnoreCase(dbName)) {
                            databases.add(dbName.trim());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return databases;
    }

    /**
     * Resolves and enriches the active SecurityPrincipal from the HttpExchange, SecurityContextHolder,
     * or fallback identity parameters, cross-referencing repositories to obtain complete assigned databases.
     */
    public SecurityPrincipal resolvePrincipal(HttpExchange exchange, String fallbackUser, String fallbackRole, String fallbackDept) {
        SecurityContext secContext = SecurityContextHolder.getContext();
        if (secContext != null && secContext.isAuthenticated() && secContext.principal() != null) {
            SecurityPrincipal p = secContext.principal();
            return enrichPrincipal(p);
        }

        String username = null;
        String role = fallbackRole != null && !fallbackRole.isBlank() ? fallbackRole : "USER";
        String department = fallbackDept != null ? fallbackDept : "";

        if (exchange != null) {
            String cookies = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookies != null) {
                for (String c : cookies.split(";")) {
                    c = c.trim();
                    if (c.startsWith("username=")) {
                        username = c.substring("username=".length());
                    } else if (c.startsWith("jettra_user=")) {
                        username = c.substring("jettra_user=".length());
                    } else if (c.startsWith("role=")) {
                        role = c.substring("role=".length());
                    } else if (c.startsWith("department=")) {
                        department = c.substring("department=".length());
                    }
                }
            }
        }

        if (username == null || username.isBlank()) {
            username = (fallbackUser != null && !fallbackUser.isBlank()) ? fallbackUser : "anonymous";
        }

        SecurityPrincipal basicPrincipal = SecurityPrincipal.of(username, role, department);
        return enrichPrincipal(basicPrincipal);
    }

    /**
     * Enriches a SecurityPrincipal with database assignments from SystemUserRepository and JUserRepository.
     */
    public SecurityPrincipal enrichPrincipal(SecurityPrincipal principal) {
        if (principal == null) {
            return null;
        }

        String username = principal.username();
        Set<String> assignedDbs = new TreeSet<>(principal.assignedDatabases());
        Set<String> roles = new TreeSet<>(principal.roles());

        boolean isAdminIdentity = "admin".equalsIgnoreCase(username) || "root".equalsIgnoreCase(username) ||
                                  roles.stream().anyMatch(r -> r.equalsIgnoreCase("ADMIN") || r.equalsIgnoreCase("ROLE_ADMIN") || r.equalsIgnoreCase("SUPERADMIN") || r.equalsIgnoreCase("SUPER_USER"));

        if (isAdminIdentity) {
            assignedDbs.add("*");
            roles.add("ADMIN");
        }

        // Check SystemUserRepository (single source of truth for modern multi-model users)
        if (systemUserRepo != null && username != null && !username.isBlank()) {
            try {
                Optional<SystemUser> sysOpt = systemUserRepo.findByUsername(username);
                if (sysOpt.isPresent()) {
                    SystemUser sysUser = sysOpt.get();
                    assignedDbs.addAll(sysUser.assignedDatabases());
                    if (sysUser.role() != null) {
                        roles.add(sysUser.role());
                    }
                    if (sysUser.isAdmin()) {
                        assignedDbs.add("*");
                        roles.add("ADMIN");
                    }
                }
            } catch (Exception ignored) {}
        }

        // Check JUserRepository (legacy/embedded authentication store)
        if (userRepo != null && username != null && !username.isBlank()) {
            try {
                Optional<JUser> legOpt = userRepo.findAll().stream()
                    .filter(u -> u.firstName().equalsIgnoreCase(username) || (u.email() != null && u.email().equalsIgnoreCase(username)))
                    .findFirst();
                if (legOpt.isPresent()) {
                    JUser lu = legOpt.get();
                    assignedDbs.addAll(lu.assignedDatabases());
                    if (lu.jRoles() != null) {
                        for (JRole r : lu.jRoles()) {
                            roles.add(r.name());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return new SecurityPrincipal(username, roles, principal.department(), assignedDbs);
    }

    /**
     * Evaluates whether a security principal has access privileges (READ or ADMIN)
     * over a target database namespace.
     */
    public boolean isAuthorized(SecurityPrincipal principal, String dbName) {
        return isAuthorized(principal, dbName, null);
    }

    /**
     * Evaluates whether a security principal has access privileges over a target database namespace,
     * optionally using an in-memory or external list of JUser entities.
     */
    public boolean isAuthorized(SecurityPrincipal principal, String dbName, List<JUser> externalUsers) {
        if (principal == null || dbName == null || dbName.isBlank()) {
            return false;
        }

        String cleanDb = dbName.trim();

        // 1. Global Admin, SuperUser, or Manager roles have unrestricted cluster-wide access
        if (principal.hasRole("ADMIN") || principal.hasRole("ROLE_ADMIN") || principal.hasRole("SUPERADMIN") ||
            principal.hasRole("SUPER_USER") || principal.hasRole("MANAGER") ||
            "admin".equalsIgnoreCase(principal.username()) || "root".equalsIgnoreCase(principal.username())) {
            return true;
        }

        // 2. Direct SecurityPrincipal assignedDatabases validation (including '*' wildcard)
        if (principal.isAuthorizedForDatabase(cleanDb)) {
            return true;
        }

        // 3. Department wildcard or department matching database name
        if (principal.department() != null && !principal.department().isBlank()) {
            if ("*".equals(principal.department()) || principal.department().equalsIgnoreCase(cleanDb)) {
                return true;
            }
        }

        // 4. Database-specific roles (e.g. DB_SALES, READ_SALES, ADMIN_SALES)
        String upperDb = cleanDb.toUpperCase();
        if (principal.hasRole("DB_" + upperDb) ||
            principal.hasRole("READ_" + upperDb) ||
            principal.hasRole("ADMIN_" + upperDb)) {
            return true;
        }

        // 5. Explicit check against SystemUserRepository
        if (systemUserRepo != null && principal.username() != null) {
            try {
                Optional<SystemUser> sysOpt = systemUserRepo.findByUsername(principal.username());
                if (sysOpt.isPresent() && sysOpt.get().hasDatabaseAccess(cleanDb)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // 6. Explicit check against provided externalUsers or JUserRepository
        List<JUser> usersToCheck = (externalUsers != null) ? externalUsers : 
            (userRepo != null ? userRepo.findAll() : Collections.emptyList());
        for (JUser u : usersToCheck) {
            boolean match = u.firstName().equalsIgnoreCase(principal.username()) ||
                            (u.email() != null && u.email().equalsIgnoreCase(principal.username()));
            if (match) {
                if (u.isAuthorizedForDatabase(cleanDb)) {
                    return true;
                }
                String dbScope = u.lastName();
                boolean scopeMatch = "*".equals(dbScope) || (dbScope != null && dbScope.equalsIgnoreCase(cleanDb));
                if (scopeMatch) {
                    if (u.jRoles() != null && !u.jRoles().isEmpty()) {
                        for (JRole r : u.jRoles()) {
                            String rName = r.name().toUpperCase();
                            if (rName.contains("READ") || rName.contains("ADMIN") ||
                                rName.contains("USER") || rName.contains("MANAGER")) {
                                return true;
                            }
                        }
                    } else {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Filters candidate physical databases down to only those authorized for the authenticated user.
     * Uses Java 25 Stream and Set operations.
     */
    public Set<String> filterDatabases(SecurityPrincipal principal, Set<String> candidateDatabases) {
        if (candidateDatabases == null || candidateDatabases.isEmpty()) {
            return Collections.emptySet();
        }
        if (principal == null) {
            return Collections.emptySet();
        }

        return candidateDatabases.stream()
            .filter(db -> isAuthorized(principal, db))
            .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }
}
