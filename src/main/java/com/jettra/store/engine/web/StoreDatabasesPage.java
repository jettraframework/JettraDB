package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.samples.SampleDatasetManager;
import com.sun.net.httpserver.HttpExchange;
import jcf.annotation.PageWidgetAllow;
import jcf.AppRole;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.security.SecurityPrincipal;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.server.autentification.repository.JettraSecurityDBInitializer;

import com.jettra.store.engine.exception.UserAlreadyExistsException;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.web.validation.UserValidationChain;
import com.jettra.store.engine.web.validation.UserValidationContext;
import com.jettra.store.engine.web.validation.UserValidationService;
import com.jettra.store.engine.web.validation.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Visual Database and Component Management Console for JettraStoreEngine.
 * Features:
 * - Unified JettraFlux panel integrating Multi-Model Components overview and Authorized Active Databases
 * - Fine-grained access control and permission filtering (READ / ADMIN) based on active SecurityContext
 * - Native JettraConfirmDialog for destructive database drop confirmations
 * - Full support for Java 25 RECORDS engine and 9 multi-model storage backends
 * - Granular User & Role Administration per database backed strictly by system_db
 * Built with 100% pure JettraFlux native components.
 */
@PageWidgetAllow(role = { jcf.AppRole.ADMIN, jcf.AppRole.MANAGER, jcf.AppRole.USER })
public class StoreDatabasesPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final AuthManager authManager;
    private final SystemUserRepository systemUserRepo;
    private final JUserRepository userRepo;
    private final JCredentialRepository credRepo;
    private final SampleDatasetManager sampleDatasetManager;
    private final UserValidationChain validationChain;
    private final UserValidationService validationService;
    private boolean showActionButtons = false;

    public StoreDatabasesPage(JettraStorageEngine engine, AuthManager authManager) {
        this(engine, authManager, (authManager != null && authManager.getSystemUserRepository() != null)
            ? authManager.getSystemUserRepository()
            : new SystemUserRepositoryImpl(), new JUserRepositoryImpl(), new JCredentialRepositoryImpl());
    }

    public StoreDatabasesPage(JettraStorageEngine engine, AuthManager authManager, SystemUserRepository systemUserRepo) {
        this(engine, authManager, systemUserRepo, new JUserRepositoryImpl(), new JCredentialRepositoryImpl());
    }

    public StoreDatabasesPage(JettraStorageEngine engine, AuthManager authManager, SystemUserRepository systemUserRepo, JUserRepository userRepo, JCredentialRepository credRepo) {
        this.engine = engine;
        this.authManager = authManager;
        this.systemUserRepo = systemUserRepo != null ? systemUserRepo : new SystemUserRepositoryImpl();
        this.userRepo = userRepo != null ? userRepo : new JUserRepositoryImpl();
        this.credRepo = credRepo != null ? credRepo : new JCredentialRepositoryImpl();
        this.sampleDatasetManager = new SampleDatasetManager(engine);
        this.validationChain = UserValidationChain.defaultChain(this.systemUserRepo);
        this.validationService = new UserValidationService(this.systemUserRepo);
    }

    public SystemUserRepository getSystemUserRepository() {
        return this.systemUserRepo;
    }

    public SystemUser assignExistingUser(String targetDb, String username, String roleName) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Debe seleccionar un usuario existente para asignar.");
        }
        String cleanUsername = username.trim();
        Optional<SystemUser> existingOpt = systemUserRepo.findByUsername(cleanUsername);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("El usuario '" + cleanUsername + "' no fue encontrado en system_db.");
        }

        SystemUser existing = existingOpt.get();
        Set<String> updatedDbs = new TreeSet<>(existing.assignedDatabases());
        if (targetDb != null && !targetDb.isBlank()) {
            updatedDbs.add(targetDb.trim());
        }
        String effectiveRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : existing.role();
        SystemUser updatedUser = existing.withUpdatedProfile(existing.email(), effectiveRole, existing.active(), updatedDbs);
        systemUserRepo.save(updatedUser);

        if (userRepo != null) {
            Optional<JUser> legOpt = userRepo.findAll().stream()
                .filter(u -> u.firstName().equalsIgnoreCase(cleanUsername))
                .findFirst();
            JRole role = new JRole(UUID.randomUUID(), effectiveRole, true);
            if (legOpt.isPresent()) {
                JUser lu = legOpt.get();
                Set<String> legDbs = new TreeSet<>(lu.assignedDatabases());
                if (targetDb != null && !targetDb.isBlank()) legDbs.add(targetDb.trim());
                userRepo.save(new JUser(lu.id(), lu.firstName(), String.join(", ", legDbs), lu.email(), lu.phone(), lu.active(), Set.of(role), legDbs));
            } else {
                userRepo.save(new JUser(existing.id(), existing.username(), String.join(", ", updatedDbs), existing.email(), "+123456", true, Set.of(role), updatedDbs));
            }
        }
        return updatedUser;
    }

    public synchronized int syncDatabaseUsers(String targetDb, Set<String> selectedUsernames) {
        if (targetDb == null || targetDb.isBlank()) {
            throw new IllegalArgumentException("Target database name cannot be null or blank.");
        }
        String cleanDb = targetDb.trim();
        List<SystemUser> allUsers = systemUserRepo.findAll();
        int updatedCount = 0;

        Set<String> cleanSelected = (selectedUsernames != null)
            ? selectedUsernames.stream().map(String::trim).collect(Collectors.toSet())
            : Collections.emptySet();

        for (SystemUser user : allUsers) {
            boolean shouldBeAuthorized = cleanSelected.contains(user.username());
            Set<String> currentDbs = new TreeSet<>(user.assignedDatabases());
            boolean hasWildcard = currentDbs.contains("*");
            boolean hasTargetDb = currentDbs.contains(cleanDb);
            boolean isAdmin = "admin".equalsIgnoreCase(user.username());

            boolean modified = false;
            if (shouldBeAuthorized) {
                if (!hasTargetDb && !hasWildcard) {
                    currentDbs.add(cleanDb);
                    modified = true;
                }
            } else {
                // Uncheck / Deselect: revoke targetDb access unless user is protected (admin or cluster wildcard '*')
                if (!isAdmin && !hasWildcard && hasTargetDb) {
                    currentDbs.remove(cleanDb);
                    modified = true;
                }
            }

            if (modified) {
                SystemUser updated = user.withUpdatedProfile(user.email(), user.role(), user.active(), currentDbs);
                systemUserRepo.save(updated);

                if (userRepo != null) {
                    Optional<JUser> legacyOpt = userRepo.findByUsername(user.username());
                    if (legacyOpt.isPresent()) {
                        JUser legacy = legacyOpt.get();
                        JUser updatedLegacy = new JUser(
                            legacy.id(),
                            legacy.firstName(),
                            String.join(", ", currentDbs),
                            legacy.email(),
                            legacy.phone(),
                            legacy.active(),
                            legacy.jRoles(),
                            currentDbs
                        );
                        userRepo.save(updatedLegacy);
                    }
                }
                updatedCount++;
            }
        }
        return updatedCount;
    }

    public SystemUser createAndAssignNewUser(String targetDb, String username, String email, String password, String roleName) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("El nombre de usuario es obligatorio.");
        }
        String cleanUsername = username.trim();

        // 1. Uniqueness pre-existence verification against system_db
        if (systemUserRepo.findByUsername(cleanUsername).isPresent()) {
            throw new UserAlreadyExistsException(cleanUsername);
        }

        // 2. Validation chain checking format, length, rules
        String effectiveRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : "READ_WRITE";
        UserValidationContext vCtx = UserValidationContext.forCreate(cleanUsername, email, effectiveRole);
        ValidationResult vResult = validationService.validate(vCtx);
        if (vResult.isInvalid()) {
            throw new IllegalArgumentException(vResult.message());
        }

        // 3. Password hashing & persistence
        UUID newUserId = UUID.randomUUID();
        String rawPassword = (password != null && !password.isBlank()) ? password : "password123";
        String hashedPassword = SystemUserRepositoryImpl.hashPassword(rawPassword);
        String cleanEmail = (email != null && !email.isBlank()) ? email.trim() : cleanUsername + "@jettra.io";

        Set<String> dbs = new TreeSet<>();
        if (targetDb != null && !targetDb.isBlank()) {
            dbs.add(targetDb.trim());
        } else {
            dbs.add("*");
        }

        SystemUser newSysUser = new SystemUser(
            newUserId,
            cleanUsername,
            hashedPassword,
            cleanEmail,
            effectiveRole,
            true,
            dbs,
            Instant.now(),
            Instant.now()
        );
        systemUserRepo.save(newSysUser);

        if (userRepo != null) {
            JRole role = new JRole(UUID.randomUUID(), effectiveRole, true);
            JUser newUser = new JUser(newUserId, cleanUsername, String.join(", ", dbs), cleanEmail, "+123456", true, Set.of(role), dbs);
            userRepo.save(newUser);
        }

        if (credRepo != null) {
            JUser refUser = new JUser(newUserId, cleanUsername, String.join(", ", dbs), cleanEmail, "+123456", true, Set.of(), dbs);
            JCredential cred = new JCredential(UUID.randomUUID(), refUser, cleanUsername, hashedPassword, true, Instant.now());
            credRepo.save(cred);
        }

        if (authManager != null) {
            authManager.register(cleanUsername, rawPassword);
        }

        return newSysUser;
    }

    public StoreDatabasesPage withGlobalActionButtons(boolean show) {
        this.showActionButtons = show;
        return this;
    }

    @Override
    protected String getPageTitle() {
        return "Databases & Components Console - JettraStoreEngine";
    }

    @Override
    protected boolean showGlobalActionButtons() {
        return this.showActionButtons;
    }

    @Override
    protected RouteVisibilityGuard.NavigationRouteConfig getRouteConfig(HttpExchange exchange, Map<String, String> params) {
        String path = (exchange != null && exchange.getRequestURI() != null)
            ? exchange.getRequestURI().getPath()
            : "/databases";
        RouteVisibilityGuard.NavigationRouteConfig config = RouteVisibilityGuard.NavigationRouteConfig.databasesConfig(path);
        if (this.showActionButtons) {
            return config.withGlobalActionButtons(true);
        }
        return config;
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String alertMessage = "";
        String alertType = "badge-active";

        // Handle Actions: create_db, drop_db, rename_db, assign_user, delete_entity
        if (exchange != null && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                String action = params != null ? params.get("action") : null;
                if ("create_db".equalsIgnoreCase(action)) {
                    String dbName = params.get("db_name");
                    String initialEngine = params.get("initial_engine");
                    String initialKey = params.get("initial_key");
                    String payload = params.get("payload");

                    if (dbName != null && !dbName.isBlank()) {
                        String cleanDb = dbName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        String keyId = (initialKey != null && !initialKey.isBlank()) ? initialKey.trim() : "init_01";
                        String eng = (initialEngine != null && !initialEngine.isBlank()) ? initialEngine.toUpperCase() : "RECORDS";

                        String prefix = getPrefixForEngine(eng);
                        String internalKey = prefix + cleanDb + ":" + keyId;
                        String data = (payload != null && !payload.isBlank()) ? payload : "{\"status\":\"ACTIVE\",\"createdAt\":" + System.currentTimeMillis() + "}";

                        engine.getStorageCore().put(internalKey, data.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Database '" + cleanDb + "' successfully initialized with component [" + eng + "]!";
                        alertType = "badge-active";
                    }
                } else if ("rename_db".equalsIgnoreCase(action)) {
                    String oldDb = params.get("old_db");
                    String newDb = params.get("new_db");
                    if (oldDb != null && newDb != null && !newDb.isBlank()) {
                        if ("system_db".equalsIgnoreCase(oldDb.trim())) {
                            alertMessage = "The system database 'system_db' is protected and cannot be renamed.";
                            alertType = "badge-raft";
                        } else {
                            int migrated = renameDatabase(oldDb.trim(), newDb.trim());
                            alertMessage = "Database '" + oldDb + "' renamed to '" + newDb + "' (" + migrated + " keys migrated).";
                            alertType = "badge-active";
                        }
                    }
                } else if ("drop_db".equalsIgnoreCase(action)) {
                    String targetDb = params.get("target_db");
                    if (targetDb != null && !targetDb.isBlank()) {
                        if ("system_db".equalsIgnoreCase(targetDb.trim())) {
                            alertMessage = "The system database 'system_db' is protected and cannot be deleted.";
                            alertType = "badge-raft";
                        } else {
                            int purged = purgeDatabase(targetDb.trim());
                            alertMessage = "Database '" + targetDb + "' dropped (" + purged + " components purged).";
                            alertType = "badge-raft";
                        }
                    }
                } else if ("delete_entity".equalsIgnoreCase(action)) {
                    String rawKey = params.get("raw_key");
                    if (rawKey != null && !rawKey.isBlank()) {
                        engine.getStorageCore().delete(rawKey, System.currentTimeMillis());
                        alertMessage = "Entity '" + rawKey + "' deleted from storage core.";
                        alertType = "badge-raft";
                    }
                } else if ("load_sample_dataset".equalsIgnoreCase(action)) {
                    String datasetKey = params.get("dataset_key");
                    int loaded = sampleDatasetManager.loadDataset(datasetKey);
                    alertMessage = "Sample Dataset [" + datasetKey + "] loaded successfully (" + loaded + " records populated across multi-model engines with cross-references)!";
                    alertType = "badge-active";
                } else if ("assign_user".equalsIgnoreCase(action)) {
                    String assignMode = params.get("assign_mode");
                    String targetDb = params.get("target_db");
                    String roleName = params.get("role");
                    String existingUsername = params.get("existing_username");
                    String assignedUsersParam = params.get("assigned_users");
                    String newUsername = params.get("username");
                    String email = params.get("email");
                    String password = params.get("password");

                    boolean isNewMode = "new".equalsIgnoreCase(assignMode);

                    if (isNewMode) {
                        String cleanUsername = newUsername != null ? newUsername.trim() : "";
                        if (cleanUsername.isBlank()) {
                            alertMessage = "El nombre de usuario es obligatorio.";
                            alertType = "badge-raft";
                        } else {
                            SystemUser created = createAndAssignNewUser(targetDb, cleanUsername, email, password, roleName);
                            alertMessage = "Nuevo usuario '" + created.username() + "' registrado con unicidad garantizada y asignado a la base de datos '" + targetDb + "' con rol [" + created.role() + "]!";
                            alertType = "badge-active";
                        }
                    } else {
                        // Multi-selection synchronization or direct single-user assignment
                        if (assignedUsersParam != null) {
                            Set<String> selectedUsernames = new TreeSet<>();
                            if (!assignedUsersParam.isBlank()) {
                                for (String u : assignedUsersParam.split(",")) {
                                    if (!u.isBlank()) selectedUsernames.add(u.trim());
                                }
                            }
                            if (existingUsername != null && !existingUsername.isBlank()) {
                                selectedUsernames.add(existingUsername.trim());
                            }
                            int synced = syncDatabaseUsers(targetDb, selectedUsernames);
                            alertMessage = "Sincronización de usuarios completada para la base de datos '" + targetDb + "': " + selectedUsernames.size() + " usuarios autorizados (" + synced + " cambios aplicados).";
                            alertType = "badge-active";
                        } else {
                            String userToAssign = (existingUsername != null && !existingUsername.isBlank())
                                ? existingUsername.trim()
                                : (newUsername != null ? newUsername.trim() : "");
                            if (userToAssign.isBlank()) {
                                alertMessage = "Debe seleccionar un usuario existente para asignar a la base de datos.";
                                alertType = "badge-raft";
                            } else {
                                SystemUser updated = assignExistingUser(targetDb, userToAssign, roleName);
                                alertMessage = "Usuario existente '" + userToAssign + "' asignado exitosamente a la base de datos '" + targetDb + "' con rol [" + updated.role() + "]!";
                                alertType = "badge-active";
                            }
                        }
                    }
                }
            } catch (UserAlreadyExistsException e) {
                alertMessage = "Error de unicidad: " + e.getMessage();
                alertType = "badge-raft";
            } catch (Exception e) {
                alertMessage = "Operation failed: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Discover all databases and their components from storage core
        Map<String, DatabaseMetadata> allDiscoveredDatabases = discoverDatabases();

        // Ensure system_db is always present as the default core database
        allDiscoveredDatabases.computeIfAbsent("system_db", db -> {
            DatabaseMetadata defaultDb = new DatabaseMetadata("system_db");
            defaultDb.addComponent("RECORDS", 0);
            return defaultDb;
        });

        // Load all users from system_db (single source of truth) and legacy userRepo
        List<SystemUser> systemUsers = systemUserRepo.findAll();
        List<JUser> allUsers = userRepo.findAll();

        // Resolve Security Principal from SecurityContextHolder or session cookie
        SecurityContext secContext = SecurityContextHolder.getContext();
        SecurityPrincipal principal = (secContext != null && secContext.isAuthenticated())
            ? secContext.principal()
            : null;

        if (principal == null && exchange != null) {
            String u = getLoggedUser(exchange);
            String r = getLoggedRole(exchange);
            String d = getLoggedDepartment(exchange);
            if (u != null && !u.isBlank()) {
                principal = SecurityPrincipal.of(u, r != null ? r : "USER", d != null ? d : "");
            }
        }

        // Functional Filtering via Java Streams & Predicates based on user permissions
        final SecurityPrincipal activePrincipal = principal;
        Predicate<String> hasDbPermission = dbName -> isAuthorizedForDatabase(activePrincipal, dbName, allUsers);

        Map<String, DatabaseMetadata> databases = allDiscoveredDatabases.entrySet().stream()
            .filter(entry -> hasDbPermission.test(entry.getKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new
            ));

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1,
                    Icon.of("fas fa-server").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Databases & Components Console")
                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(
                    Text.of("Visual management of database namespaces, multi-model storage components, Java 25 Records, and per-database RBAC security.")
                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            ),
            Row.of(
                Button.of(
                    Icon.of("fas fa-plus-circle"),
                    Text.of(" New Database")
                ).attribute("onclick", "openCreateDbModal()")
                 .modifier(new Modifier().cssClass("btn-action btn-primary")),
                Link.of(JettraServer.resolvePath("/users"),
                    Icon.of("fas fa-users-cog"),
                    Text.of(" User Security")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-left:8px;")),
                Link.of(JettraServer.resolvePath("/engines"),
                    Icon.of("fas fa-cubes"),
                    Text.of(" Engines Matrix")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-left:8px;"))
            ).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner
        Widget alertWidget = alertMessage.isEmpty() ? Div.of() : Div.of(
            Div.of(
                Icon.of("fas fa-check-circle").modifier(new Modifier().style("color:#38bdf8; font-size:18px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:14px; color:#f8fafc; font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
            Span.of("SYNCHRONIZED").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;"));

        // Stats Summary (computed over authorized databases)
        int totalDatabases = databases.size();
        int totalComponents = databases.values().stream().mapToInt(d -> d.getEngineCounts().size()).sum();
        int totalObjects = databases.values().stream().mapToInt(DatabaseMetadata::getTotalObjects).sum();
        int totalRecords = databases.values().stream().mapToInt(d -> d.getEngineCounts().getOrDefault("RECORDS", 0)).sum();

        // Encapsulated Engine & Cluster Metrics Panel using JettraFlux
        Widget metricsPanel = Panel.builder()
            .header(PanelHeader.builder()
                .title("Engine & Cluster Operational Metrics")
                .subtitle("Real-time distributed telemetry across authorized database namespaces and storage engines")
                .icon("fas fa-chart-line", "#38bdf8")
                .badge(Badge.active("ACTIVE"))
                .build())
            .body(PanelBody.grid(4,
                MetricCard.builder()
                    .title("Active Databases")
                    .value(totalDatabases + " Databases")
                    .subtext("LSM / B-Tree Storage")
                    .icon("fas fa-database", "#3b82f6")
                    .badge(Badge.active("ACTIVE"))
                    .build(),
                MetricCard.builder()
                    .title("Multi-Model Components")
                    .value(totalComponents + " Active Engine Models")
                    .subtext("9 Supported Engines")
                    .icon("fas fa-cubes", "#a855f7")
                    .badge(Badge.info("ACTIVE"))
                    .build(),
                MetricCard.builder()
                    .title("Java 25 Records")
                    .value(totalRecords + " Typed Records")
                    .subtext("JEP 450 Compact Headers")
                    .icon("fas fa-id-card", "#f43f5e")
                    .badge(Badge.warning("ACTIVE"))
                    .build(),
                MetricCard.builder()
                    .title("Total Stored Entities")
                    .value(totalObjects + " Total Objects")
                    .subtext("Raft State Synchronized")
                    .icon("fas fa-layer-group", "#10b981")
                    .badge(Badge.success("ACTIVE"))
                    .build()
            ))
            .build();

        // Compute Multi-Model Components aggregation across authorized databases
        Map<String, Integer> globalEngineCounts = new LinkedHashMap<>();
        for (DatabaseMetadata dbMeta : databases.values()) {
            for (Map.Entry<String, Integer> comp : dbMeta.getEngineCounts().entrySet()) {
                globalEngineCounts.put(comp.getKey(), globalEngineCounts.getOrDefault(comp.getKey(), 0) + comp.getValue());
            }
        }

        // Section 1: Multi-Model Storage Components Overview Cards
        Widget multiModelSectionHeader = Div.of(
            Div.of(
                Icon.of("fas fa-cubes").modifier(new Modifier().style("color:#a855f7; font-size:18px; margin-right:8px;")),
                Span.of("Multi-Model Storage Components Overview").modifier(new Modifier().style("font-size:15px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of(globalEngineCounts.size() + " Active Storage Engines").modifier(new Modifier().cssClass("store-badge badge-raft"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;"));

        List<Widget> globalCompBoxes = new ArrayList<>();
        if (globalEngineCounts.isEmpty()) {
            globalCompBoxes.add(Div.of(
                Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#94a3b8; margin-right:6px;")),
                Span.of("No active storage components detected in authorized databases.").modifier(new Modifier().style("color:#94a3b8; font-size:13px;"))
            ).modifier(new Modifier().style("padding:14px; background:rgba(15,23,42,0.5); border-radius:8px; width:100%;")));
        } else {
            for (Map.Entry<String, Integer> comp : globalEngineCounts.entrySet()) {
                String eng = comp.getKey();
                int cnt = comp.getValue();
                String badgeStyle = getBadgeStyleForEngine(eng);
                String icon = getIconForEngine(eng);
                String desc = getDescForEngine(eng);

                Widget box = Div.of(
                    Div.of(
                        Icon.of(icon).modifier(new Modifier().style("font-size:18px;")),
                        Div.of(
                            Div.of(Text.of(eng)).modifier(new Modifier().style("font-weight:700; font-size:13px;")),
                            Div.of(Text.of(desc)).modifier(new Modifier().style("font-size:11px; opacity:0.8;"))
                        )
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
                    Span.of(cnt + " keys").modifier(new Modifier().style("background:rgba(0,0,0,0.35); padding:3px 10px; border-radius:6px; font-weight:700; font-size:12px;"))
                ).modifier(new Modifier().style(badgeStyle + " padding:12px 16px; border-radius:10px; display:flex; align-items:center; gap:12px; min-width:210px; flex:1; justify-content:space-between;"));

                globalCompBoxes.add(box);
            }
        }

        Widget multiModelGrid = Div.of(globalCompBoxes.toArray(new Widget[0]))
            .modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:12px; margin-bottom:18px;"));

        Widget sectionDivider = Div.of()
            .modifier(new Modifier().style("height:1px; width:100%; background:rgba(255,255,255,0.08); margin:6px 0 16px 0;"));

        // Section 2: Active Databases Cards & Controls
        Widget activeDatabasesHeader = Div.of(
            Div.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; font-size:18px; margin-right:8px;")),
                Span.of("Authorized Active Databases").modifier(new Modifier().style("font-size:15px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Span.of("Scope: " + (activePrincipal != null ? activePrincipal.username() : "ANONYMOUS"))
                .modifier(new Modifier().cssClass("store-badge badge-active"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;"));

        List<Widget> dbCardList = new ArrayList<>();

        if (databases.isEmpty()) {
            // Friendly Empty State Component when user has no permissions on any database
            Widget emptyState = EmptyStateComponent.of(
                "No Authorized Databases",
                "Your account (" + (activePrincipal != null ? activePrincipal.username() : "Guest") +
                ") does not possess READ or ADMIN permissions for any database namespaces in this cluster. Contact your administrator."
            ).icon("fas fa-shield-alt")
             .action("Request Access / Refresh", "window.location.reload()");
            dbCardList.add(emptyState);
        } else {
            for (DatabaseMetadata dbMeta : databases.values()) {
                String dbName = dbMeta.getName();
                int objCount = dbMeta.getTotalObjects();

                // Users scoped to this db
                List<JUser> dbUsers = allUsers.stream()
                    .filter(u -> u.isAuthorizedForDatabase(dbName))
                    .toList();

                boolean isSystemDb = "system_db".equalsIgnoreCase(dbName);

                // Header of each DB card
                Widget dbStatusBadge = isSystemDb
                    ? Span.of(RawHtml.of("<span class='pulse-dot'></span> SYSTEM CORE")).modifier(new Modifier().cssClass("store-badge badge-records"))
                    : Span.of(RawHtml.of("<span class='pulse-dot'></span> ONLINE")).modifier(new Modifier().cssClass("store-badge badge-active"));

                Widget dbHeaderLeft = Div.of(
                    Div.of(Icon.of(isSystemDb ? "fas fa-shield-alt" : "fas fa-database"))
                        .modifier(new Modifier().style("width:46px; height:46px; border-radius:10px; background:" + (isSystemDb ? "rgba(244,63,94,0.15)" : "rgba(56,189,248,0.15)") + "; display:flex; align-items:center; justify-content:center; color:" + (isSystemDb ? "#f43f5e" : "#38bdf8") + "; font-size:22px;")),
                    Div.of(
                        Div.of(
                            Header.of(2, Text.of(dbName)).modifier(new Modifier().style("margin:0; font-size:20px; font-weight:700; color:#f8fafc;")),
                            dbStatusBadge
                        ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
                        Div.of(
                            Text.of("Storage Engine: "),
                            Span.of("LSM-BTree Hybrid Core").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;")),
                            Text.of(isSystemDb ? " | Protected Cluster System Namespace" : " | Raft Quorum Replication")
                        ).modifier(new Modifier().style("font-size:13px; color:#94a3b8;"))
                    )
                ).modifier(new Modifier().style("display:flex; align-items:center; gap:12px;"));

                // Clean actions: system_db cannot be renamed nor deleted
                List<Widget> actionButtons = new ArrayList<>();
                actionButtons.add(Link.of(JettraServer.resolvePath("/engines?engine=RECORDS&db=" + dbName),
                    Icon.of("fas fa-search"),
                    Text.of(" Explore Data")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")));

                if (!isSystemDb) {
                    actionButtons.add(Button.of(Icon.of("fas fa-pen"), Text.of(" Rename"))
                        .attribute("onclick", "openRenameDbModal('" + dbName + "')")
                        .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")));
                }

                actionButtons.add(Button.of(Icon.of("fas fa-user-plus"), Text.of(" Assign User"))
                    .attribute("onclick", "openAssignUserModal('" + dbName + "')")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")));

                if (!isSystemDb) {
                    actionButtons.add(Button.of(Icon.of("fas fa-trash-alt"), Text.of(" Delete Database"))
                        .attribute("onclick", "JettraConfirmDialog.open('dropDbConfirmDialog', '" + dbName + "', '" + dbName + "')")
                        .attribute("title", "Delete Database")
                        .modifier(new Modifier().cssClass("btn-action btn-danger").style("padding:6px 12px; font-size:12px;")));
                } else {
                    actionButtons.add(Span.of(
                        Icon.of("fas fa-lock").modifier(new Modifier().style("margin-right:4px;")),
                        Text.of("SYSTEM PROTECTED")
                    ).modifier(new Modifier().cssClass("store-badge badge-records").style("font-size:11px; padding:6px 10px;")));
                }

                Widget dbHeaderRight = Div.of(actionButtons.toArray(new Widget[0]))
                    .modifier(new Modifier().style("display:flex; gap:8px; flex-wrap:wrap; align-items:center;"));

                Widget dbTopRow = Row.of(dbHeaderLeft, dbHeaderRight)
                    .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:12px; margin-bottom:16px;"));

                // Multi-Model Components Inside This Database
                List<Widget> compBoxes = new ArrayList<>();
                for (Map.Entry<String, Integer> comp : dbMeta.getEngineCounts().entrySet()) {
                    String eng = comp.getKey();
                    int cnt = comp.getValue();
                    String badgeStyle = getBadgeStyleForEngine(eng);
                    String icon = getIconForEngine(eng);
                    String desc = getDescForEngine(eng);

                    Widget compBox = Div.of(
                        Div.of(
                            Icon.of(icon).modifier(new Modifier().style("font-size:16px;")),
                            Div.of(
                                Div.of(Text.of(eng)).modifier(new Modifier().style("font-weight:700; font-size:13px;")),
                                Div.of(Text.of(desc)).modifier(new Modifier().style("font-size:11px; opacity:0.8;"))
                            )
                        ).modifier(new Modifier().style("display:flex; align-items:center; gap:8px;")),
                        Span.of(String.valueOf(cnt)).modifier(new Modifier().style("background:rgba(0,0,0,0.3); padding:2px 8px; border-radius:6px; font-weight:700; font-size:12px;"))
                    ).modifier(new Modifier().style(badgeStyle + " padding:10px 14px; border-radius:8px; display:flex; align-items:center; gap:10px; min-width:200px; justify-content:space-between;"));

                    compBoxes.add(compBox);
                }

                Widget internalComponentsBox = Div.of(
                    Div.of(
                        Div.of(Icon.of("fas fa-cubes").modifier(new Modifier().style("color:#a855f7; margin-right:6px;")), Text.of("Internal Multi-Model Components (" + dbMeta.getEngineCounts().size() + " Engines Initialized)")),
                        Div.of(Text.of("Total Keys: "), Span.of(String.valueOf(objCount)).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
                    ).modifier(new Modifier().style("font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:10px; display:flex; justify-content:space-between; align-items:center;")),
                    Div.of(compBoxes.toArray(new Widget[0])).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:10px;"))
                ).modifier(new Modifier().style("background:rgba(15,23,42,0.6); border-radius:10px; padding:16px; border:1px solid rgba(255,255,255,0.06); margin-bottom:16px;"));

                // Scoped Users (loaded from system_db)
                List<SystemUser> scopedSysUsers = systemUsers.stream()
                    .filter(u -> u.hasDatabaseAccess(dbName))
                    .toList();

                List<Widget> userBadges = new ArrayList<>();
                if (scopedSysUsers.isEmpty()) {
                    userBadges.add(Span.of("No users assigned specifically (inherited from global admin).").modifier(new Modifier().style("color:#64748b;")));
                } else {
                    for (SystemUser u : scopedSysUsers) {
                        String role = u.role() != null ? u.role() : "READ_WRITE";
                        String roleBadge = "DB_ADMIN".equalsIgnoreCase(role) ? "badge-raft" : "badge-engine";
                        userBadges.add(Span.of(u.username() + " (" + role + ")").modifier(new Modifier().cssClass("store-badge " + roleBadge).style("font-size:11px; margin-right:4px;")));
                    }
                }

                Widget scopedUsersBar = Div.of(
                    Div.of(
                        Icon.of("fas fa-user-shield").modifier(new Modifier().style("color:#38bdf8;")),
                        Span.of("Scoped Users (" + scopedSysUsers.size() + "): "),
                        Div.of(userBadges.toArray(new Widget[0]))
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:8px; flex-wrap:wrap;")),
                    Button.of(Text.of("+ Assign User to " + dbName))
                        .attribute("onclick", "openAssignUserModal('" + dbName + "')")
                        .modifier(new Modifier().style("background:none; border:none; color:#38bdf8; font-size:12px; cursor:pointer; text-decoration:underline;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; font-size:13px; color:#94a3b8; padding-top:10px; border-top:1px solid rgba(255,255,255,0.06);"));

                Widget dbCard = Div.of(
                    RawHtml.of("<div style='position:absolute; top:0; left:0; width:4px; height:100%; background: linear-gradient(180deg, #38bdf8, #f43f5e);'></div>"),
                    dbTopRow,
                    internalComponentsBox,
                    scopedUsersBar
                ).modifier(new Modifier().cssClass("store-card").style("position:relative; overflow:hidden; background:rgba(15,23,42,0.7); border:1px solid rgba(255,255,255,0.08);"));

                dbCardList.add(dbCard);
            }
        }

        Widget databasesContainer = Div.of(dbCardList.toArray(new Widget[0]))
            .modifier(new Modifier().style("display: flex; flex-direction: column; gap: 20px;"));

        // Unified JettraFlux Panel consolidating Multi-Model Components and Active Databases
        Widget unifiedPanel = JettraCardPanel.of("Multi-Model Database Workspace")
            .subtitle("Consolidated panel for multi-model storage engine components and authorized active databases.")
            .icon("fas fa-layer-group")
            .iconColor("#38bdf8")
            .badge(databases.size() + " Active Databases", "badge-active")
            .add(multiModelSectionHeader)
            .add(multiModelGrid)
            .add(sectionDivider)
            .add(activeDatabasesHeader)
            .add(databasesContainer);

        // Modal 1: Create Database
        Widget createDbHeader = Row.of(
            Row.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Header.of(3, Text.of("Provision New Database")).modifier(new Modifier().style("margin:0; font-size:20px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;").attribute("onclick", "document.getElementById('createDbModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;"));

        Widget createDbForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='create_db'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Database Namespace Name:</label>"),
                RawHtml.of("<input type='text' name='db_name' required placeholder='e.g. enterprise_store' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Engine Component:</label>"),
                RawHtml.of("<select name='initial_engine' onchange='updatePayloadTemplate(this.value, \"createPayload\")' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'>\n" +
                    "          <option value='RECORDS' selected>RECORDS (Java 25 Immutable Records)</option>\n" +
                    "          <option value='DOCUMENT'>DOCUMENT (NoSQL JSON Documents)</option>\n" +
                    "          <option value='VECTOR'>VECTOR (AI ANN Cosine Embeddings)</option>\n" +
                    "          <option value='GRAPH'>GRAPH (LPG Nodes & Relations)</option>\n" +
                    "          <option value='TIMESERIES'>TIMESERIES (IoT Sensor Telemetry)</option>\n" +
                    "          <option value='COLUMN'>COLUMN (OLAP Wide Column Tables)</option>\n" +
                    "          <option value='KEYVALUE'>KEYVALUE (High-Speed In-Memory Cache)</option>\n" +
                    "          <option value='GEOSPATIAL'>GEOSPATIAL (2D GIS Spatial Points)</option>\n" +
                    "          <option value='OBJECT'>OBJECT (Binary BLOBs & Media)</option>\n" +
                    "        </select>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Entity ID / Key:</label>"),
                RawHtml.of("<input type='text' name='initial_key' value='entity_01' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Payload JSON:</label>"),
                RawHtml.of("<textarea id='createPayload' name='payload' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:13px; font-family:monospace; box-sizing:border-box;'>{\"_recordClass\": \"com.enterprise.model.InitRecord\", \"_schema\": {\"name\":\"String\", \"active\":\"Boolean\"}, \"components\": {\"name\": \"Enterprise System\", \"active\": true}}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:20px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('createDbModal').close();")),
                Button.of(Icon.of("fas fa-check"), Text.of(" Create Database")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget createDbModal = Dialog.of(createDbHeader, createDbForm)
            .id("createDbModal")
            .modifier(new Modifier().cssClass("store-card").style("width:540px; max-width:90%; background:#1e293b; border:1px solid rgba(255,255,255,0.15); box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:28px; margin:auto;"));

        // Modal 2: Assign User to Database (Pure JettraFlux Components)
        Widget assignUserHeader = Row.of(
            Row.of(
                Icon.of("fas fa-user-shield").modifier(new Modifier().style("color:#38bdf8; margin-right:8px; font-size:20px;")),
                Header.of(3,
                    Text.of("Assign User to "),
                    Span.of("").id("assignUserDbLabel").modifier(new Modifier().style("color:#38bdf8; text-decoration:underline;"))
                ).modifier(new Modifier().style("margin:0; font-size:20px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times"))
                .modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer; padding:4px 8px;"))
                .attribute("onclick", "document.getElementById('assignUserModal').close();")
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;"));

        Widget assignUserSubtitle = Paragraph.of(
            Text.of("Select an existing identity from the internal system database (system_db) or provision a new account with guaranteed uniqueness.")
        ).modifier(new Modifier().style("margin:0 0 16px 0; color:#94a3b8; font-size:13px; line-height:1.4;"));

        // ToggleTabs: Existing User vs Create New User
        ToggleTabs modeTabs = ToggleTabs.of("assignUserModeTabs")
            .addTab("existing", "Existing System User", "fas fa-user-check", true)
            .addTab("new", "Create New User", "fas fa-user-plus", false)
            .onTabChange("switchAssignUserMode('{tabId}')");

        Widget modeTabsContainer = Div.of(modeTabs)
            .modifier(new Modifier().style("margin-bottom:18px; display:flex; justify-content:center;"));

        // UserSelectionTable for mass selection and identity authorization synchronization
        UserSelectionTable userSelectionTable = UserSelectionTable.of("assignUserSelectionTable", "assigned_users")
            .searchPlaceholder("Filter system users by username, role, or email...")
            .emptyMessage("No system users registered or matching search criteria")
            .maxHeight("230px")
            .quickActions(true);

        for (SystemUser su : systemUsers) {
            boolean isAdmin = "admin".equalsIgnoreCase(su.username());
            String roleBadge = su.role() != null ? su.role() : "READ_WRITE";
            String desc = su.email() != null ? su.email() : su.username() + "@jettra.io";

            ToggleSelectionItem item = ToggleSelectionItem.of(su.username(), su.username())
                .id("user_toggle_" + su.username().replaceAll("[^a-zA-Z0-9_]", "_"))
                .subtitle(desc)
                .roleBadge(roleBadge)
                .assignedDatabases(su.assignedDatabases())
                .disabled(isAdmin);

            userSelectionTable.addItem(item);
        }

        Widget existingSection = Div.of(
            Div.of(
                Label.of("Registered System Users:").modifier(new Modifier().style("display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;")),
                userSelectionTable,
                Paragraph.of(Text.of("Select or deselect users to dynamically grant or revoke database authorization. Admin maintains cluster-wide access."))
                    .modifier(new Modifier().style("font-size:11.5px; color:#64748b; margin:6px 0 0 0;"))
            ).modifier(new Modifier().style("margin-bottom:16px;"))
        ).id("assignUserExistingSection");

        // New User Inputs with TextInput and ValidationFeedback
        TextInput usernameInput = TextInput.builder()
            .id("new_username_input")
            .name("username")
            .placeholder("e.g. dev_analyst")
            .inputType("text")
            .build();

        TextInput emailInput = TextInput.builder()
            .id("new_email_input")
            .name("email")
            .placeholder("analyst@company.com")
            .inputType("email")
            .build();

        TextInput passwordInput = TextInput.builder()
            .id("new_password_input")
            .name("password")
            .placeholder("••••••••")
            .inputType("password")
            .build();

        Widget newSection = Div.of(
            Div.of(
                Label.of("Username:").modifier(new Modifier().style("display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;")),
                usernameInput,
                ValidationFeedback.forInput("username").id("new_username_feedback")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                Label.of("Email Address:").modifier(new Modifier().style("display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;")),
                emailInput
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                Label.of("Password:").modifier(new Modifier().style("display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;")),
                passwordInput
            ).modifier(new Modifier().style("margin-bottom:16px;"))
        ).id("assignUserNewSection").modifier(new Modifier().style("display:none;"));

        // Role Selector for target database assignment
        JettraFluxSelect roleSelect = JettraFluxSelect.of("assignUserRoleSelect", "role")
            .addOption("DB_ADMIN", "DB_ADMIN (Full DDL & Read/Write)")
            .addOption("READ_WRITE", "READ_WRITE (Insert, Update, Query)", true)
            .addOption("READ_ONLY", "READ_ONLY (Query Only)")
            .addOption("MANAGER", "MANAGER (Backup & Operations)");

        Widget roleSection = Div.of(
            Label.of("Assigned RBAC Role:").modifier(new Modifier().style("display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;")),
            roleSelect,
            Paragraph.of(Text.of("Role determines database permissions. Admin roles inherit cluster-wide management rights."))
                .modifier(new Modifier().style("font-size:11.5px; color:#64748b; margin:6px 0 0 0;"))
        ).modifier(new Modifier().style("margin-bottom:22px;"));

        Widget actionButtonsRow = Div.of(
            Button.of(Text.of("Cancel"))
                .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:8px 16px;"))
                .attribute("type", "button")
                .attribute("onclick", "document.getElementById('assignUserModal').close();"),
            Button.of(Icon.of("fas fa-user-check"), Text.of(" Assign User"))
                .id("assignUserSubmitBtn")
                .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:8px 18px;"))
                .attribute("type", "submit")
        ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"));

        Widget assignUserForm = Form.of(
            InputHidden.of("action", "assign_user"),
            InputHidden.of("target_db", "").id("assignUserDbInput"),
            InputHidden.of("assign_mode", "existing").id("assignUserModeInput"),
            modeTabsContainer,
            existingSection,
            newSection,
            roleSection,
            actionButtonsRow
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget assignUserModal = Dialog.of(assignUserHeader, assignUserSubtitle, assignUserForm)
            .id("assignUserModal")
            .modifier(new Modifier().cssClass("store-card").style("width:620px; max-width:94%; background:#1e293b; border:1px solid rgba(56,189,248,0.3); box-shadow:0 20px 50px rgba(0,0,0,0.7); border-radius:14px; padding:26px; margin:auto;"));

        // Modal 3: Rename Database
        Widget renameDbHeader = Row.of(
            Row.of(
                Icon.of("fas fa-pen").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Header.of(3, Text.of("Rename Database")).modifier(new Modifier().style("margin:0; font-size:17px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer; padding:4px 8px;").attribute("onclick", "document.getElementById('renameDbModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));

        Widget renameDbForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='rename_db'/>"),
            RawHtml.of("<input type='hidden' name='old_db' id='renameOldDbInput'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Current Database Name:</label>"),
                RawHtml.of("<input type='text' id='renameOldDbDisplay' disabled style='width:100%; padding:10px 12px; background:#1e293b; border:1px solid rgba(255,255,255,0.1); border-radius:8px; color:#94a3b8; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>New Database Name:</label>"),
                RawHtml.of("<input type='text' name='new_db' placeholder='e.g. inventory_prod_db' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:18px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('renameDbModal').close();")),
                Button.of(Icon.of("fas fa-save"), Text.of(" Rename Database")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget renameDbModal = Dialog.of(renameDbHeader, renameDbForm)
            .id("renameDbModal")
            .modifier(new Modifier().cssClass("store-card").style("max-width:480px; width:90%; background:#0f172a; border:1px solid rgba(56,189,248,0.4); border-radius:14px; padding:24px; margin:auto;"));

        // Modal 4: Native JettraConfirmDialog for Destructive Database Drop Confirmation
        Widget dropDbModal = JettraConfirmDialog.of("dropDbConfirmDialog")
            .title("Confirm Database Deletion")
            .warningMessage("Are you sure you want to permanently delete and drop this database? All stored records, multi-model components, schema definitions, and storage partitions will be permanently purged.")
            .targetItemLabel("Database to be dropped:")
            .confirmText("Confirm Deletion")
            .cancelText("Cancel")
            .formAction(JettraServer.resolvePath("/databases"))
            .actionName("drop_db")
            .targetParamName("target_db");

        Widget scriptsWidget = RawHtml.of(
            "<script>\n" +
            "  function openModal(id) { document.getElementById(id).showModal(); }\n" +
            "  function openCreateDbModal() { openModal('createDbModal'); }\n" +
            "  function openAssignUserModal(db) {\n" +
            "    var dbInp = document.getElementById('assignUserDbInput');\n" +
            "    if (dbInp) dbInp.value = db;\n" +
            "    var dbLabel = document.getElementById('assignUserDbLabel');\n" +
            "    if (dbLabel) dbLabel.innerText = db;\n" +
            "    if (window.UserSelectionTable) {\n" +
            "      window.UserSelectionTable.syncForDatabase('assignUserSelectionTable', db);\n" +
            "    }\n" +
            "    if (window.switchAssignUserMode) window.switchAssignUserMode('existing');\n" +
            "    openModal('assignUserModal');\n" +
            "  }\n" +
            "  function switchAssignUserMode(mode) {\n" +
            "    var modeInput = document.getElementById('assignUserModeInput');\n" +
            "    if (modeInput) modeInput.value = mode;\n" +
            "    var existingSec = document.getElementById('assignUserExistingSection');\n" +
            "    var newSec = document.getElementById('assignUserNewSection');\n" +
            "    var submitBtn = document.getElementById('assignUserSubmitBtn');\n" +
            "    var newUsernameInp = document.getElementById('new_username_input');\n" +
            "    var newEmailInp = document.getElementById('new_email_input');\n" +
            "    var newPassInp = document.getElementById('new_password_input');\n" +
            "    if (mode === 'existing') {\n" +
            "      if (existingSec) existingSec.style.display = 'block';\n" +
            "      if (newSec) newSec.style.display = 'none';\n" +
            "      if (newUsernameInp) newUsernameInp.removeAttribute('required');\n" +
            "      if (newEmailInp) newEmailInp.removeAttribute('required');\n" +
            "      if (newPassInp) newPassInp.removeAttribute('required');\n" +
            "      if (submitBtn) submitBtn.innerHTML = '<i class=\"fas fa-user-check\"></i> Assign User';\n" +
            "    } else {\n" +
            "      if (existingSec) existingSec.style.display = 'none';\n" +
            "      if (newSec) newSec.style.display = 'block';\n" +
            "      if (newUsernameInp) newUsernameInp.setAttribute('required', 'required');\n" +
            "      if (newEmailInp) newEmailInp.setAttribute('required', 'required');\n" +
            "      if (newPassInp) newPassInp.setAttribute('required', 'required');\n" +
            "      if (submitBtn) submitBtn.innerHTML = '<i class=\"fas fa-plus-circle\"></i> Create & Assign User';\n" +
            "    }\n" +
            "  }\n" +
            "  function openRenameDbModal(oldDb) {\n" +
            "    document.getElementById('renameOldDbInput').value = oldDb;\n" +
            "    document.getElementById('renameOldDbDisplay').value = oldDb;\n" +
            "    openModal('renameDbModal');\n" +
            "  }\n" +
            "  function confirmDropDb(db) {\n" +
            "    if (window.JettraConfirmDialog) {\n" +
            "      window.JettraConfirmDialog.open('dropDbConfirmDialog', db, db);\n" +
            "    }\n" +
            "  }\n" +
            "  function updatePayloadTemplate(engine, targetId) {\n" +
            "    var t = document.getElementById(targetId);\n" +
            "    if (!t) return;\n" +
            "    switch(engine) {\n" +
            "      case 'RECORDS': t.value = '{\"_recordClass\": \"com.enterprise.model.EmployeeRecord\", \"_schema\": {\"id\":\"String\", \"fullName\":\"String\", \"salary\":\"Double\"}, \"components\": {\"id\": \"emp_101\", \"fullName\": \"Carlos Mendez\", \"salary\": 95000.0}}'; break;\n" +
            "      case 'DOCUMENT': t.value = '{\"name\": \"Enterprise Doc\", \"active\": true, \"tier\": \"Premium\"}'; break;\n" +
            "      case 'VECTOR': t.value = '{\"coords\": [0.12, 0.45, 0.88, 0.31], \"meta\": {\"title\": \"Paper Embedding\"}}'; break;\n" +
            "      case 'GRAPH': t.value = '{\"label\": \"ServerNode\", \"properties\": {\"ip\": \"192.168.1.100\", \"status\": \"ACTIVE\"}}'; break;\n" +
            "      case 'TIMESERIES': t.value = '{\"value\": 42.50, \"unit\": \"celsius\", \"tags\": {\"host\": \"server-01\"}}'; break;\n" +
            "      case 'COLUMN': t.value = '{\"col1\": \"val1\", \"col2\": 100, \"status\": \"OK\"}'; break;\n" +
            "      case 'KEYVALUE': t.value = 'raw_string_or_json_payload'; break;\n" +
            "      case 'GEOSPATIAL': t.value = '{\"lat\": 8.9824, \"lon\": -79.5199, \"name\": \"Hub Panama\"}'; break;\n" +
            "      case 'OBJECT': t.value = '{\"class\": \"BlobObject\", \"data\": \"base64_or_stream\"}'; break;\n" +
            "    }\n" +
            "  }\n" +
            "</script>\n"
        );

        return Column.of(
            titleBlock,
            alertWidget,
            metricsPanel,
            unifiedPanel,
            createDbModal,
            assignUserModal,
            renameDbModal,
            dropDbModal,
            scriptsWidget
        );
    }

    /**
     * Evaluates whether the current security principal possesses access permissions (READ or ADMIN)
     * over a specific database namespace.
     */
    public boolean isAuthorizedForDatabase(SecurityPrincipal principal, String dbName, List<JUser> allUsers) {
        if (principal == null) {
            return false;
        }

        // 1. Global Admin, SuperUser, or Manager roles have cluster-wide access
        if (principal.hasRole("ADMIN") || principal.hasRole("SUPER_USER") || principal.hasRole("MANAGER")) {
            return true;
        }

        // 2. Direct SecurityPrincipal assignedDatabases validation
        if (principal.isAuthorizedForDatabase(dbName)) {
            return true;
        }

        // 3. Department or specific database roles matching database name
        if (principal.department() != null && !principal.department().isBlank()) {
            if ("*".equals(principal.department()) || principal.department().equalsIgnoreCase(dbName)) {
                return true;
            }
        }
        if (principal.hasRole("DB_" + dbName.toUpperCase()) ||
            principal.hasRole("READ_" + dbName.toUpperCase()) ||
            principal.hasRole("ADMIN_" + dbName.toUpperCase())) {
            return true;
        }

        // 4. System user entity database scoping and role validation against system_db
        if (systemUserRepo != null && principal.username() != null) {
            Optional<SystemUser> sysOpt = systemUserRepo.findByUsername(principal.username());
            if (sysOpt.isPresent() && sysOpt.get().hasDatabaseAccess(dbName)) {
                return true;
            }
        }

        // 5. User entity database scoping and role validation
        if (allUsers != null) {
            for (JUser u : allUsers) {
                boolean match = u.firstName().equalsIgnoreCase(principal.username()) ||
                                (u.email() != null && u.email().equalsIgnoreCase(principal.username()));
                if (match) {
                    if (u.isAuthorizedForDatabase(dbName)) {
                        return true;
                    }
                    String dbScope = u.lastName();
                    boolean scopeMatch = "*".equals(dbScope) || (dbScope != null && dbScope.equalsIgnoreCase(dbName));
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
                            // User scoped to this db with default permissions
                            return true;
                        }
                    }
                }
            }
        }

        // 4. Default user namespace matching
        if (principal.hasRole("READ") || principal.hasRole("USER") || principal.hasRole("READ_WRITE") || principal.hasRole("READ_ONLY")) {
            if (principal.username().equalsIgnoreCase(dbName)) {
                return true;
            }
        }

        return false;
    }

    private int renameDatabase(String oldDb, String newDb) {
        if (oldDb == null || newDb == null || oldDb.equalsIgnoreCase(newDb) || "system_db".equalsIgnoreCase(oldDb.trim())) return 0;
        String cleanNewDb = newDb.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        int count = 0;
        for (String p : prefixes) {
            String dbPrefix = p + oldDb + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPrefix);
            for (Map.Entry<String, byte[]> e : keys.entrySet()) {
                String oldKey = e.getKey();
                String keyId = oldKey.substring(dbPrefix.length());
                String newKey = p + cleanNewDb + ":" + keyId;
                engine.getStorageCore().put(newKey, e.getValue(), System.currentTimeMillis());
                engine.getStorageCore().delete(oldKey, System.currentTimeMillis());
                count++;
            }
        }
        return count;
    }

    private Map<String, DatabaseMetadata> discoverDatabases() {
        Map<String, DatabaseMetadata> databases = new LinkedHashMap<>();

        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
            String engineName = getEngineNameForPrefix(p);

            for (String k : keys.keySet()) {
                String rest = k.substring(p.length());
                int colonIdx = rest.indexOf(':');
                String dbName = colonIdx > 0 ? rest.substring(0, colonIdx) : "default";

                DatabaseMetadata meta = databases.computeIfAbsent(dbName, DatabaseMetadata::new);
                meta.incrementEngine(engineName);
            }
        }
        return databases;
    }

    private int purgeDatabase(String targetDb) {
        if (targetDb == null || "system_db".equalsIgnoreCase(targetDb.trim())) {
            return 0;
        }
        int count = 0;
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        for (String p : prefixes) {
            String dbPrefix = p + targetDb + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPrefix);
            for (String k : keys.keySet()) {
                engine.getStorageCore().delete(k, System.currentTimeMillis());
                count++;
            }
        }
        engine.getStorageCore().dropDatabase(targetDb);
        return count;
    }

    private String getPrefixForEngine(String engine) {
        return switch (engine.toUpperCase()) {
            case "RECORDS" -> "rec:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "KEYVALUE" -> "kv:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };
    }

    private String getEngineNameForPrefix(String p) {
        return switch (p) {
            case "rec:" -> "RECORDS";
            case "vec:" -> "VECTOR";
            case "graph:" -> "GRAPH";
            case "ts:" -> "TIMESERIES";
            case "col:" -> "COLUMN";
            case "kv:" -> "KEYVALUE";
            case "geo:" -> "GEOSPATIAL";
            case "obj:" -> "OBJECT";
            default -> "DOCUMENT";
        };
    }

    private String getBadgeStyleForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "background:rgba(244,63,94,0.15); color:#f43f5e; border:1px solid rgba(244,63,94,0.3);";
            case "DOCUMENT" -> "background:rgba(56,189,248,0.15); color:#38bdf8; border:1px solid rgba(56,189,248,0.3);";
            case "VECTOR" -> "background:rgba(168,85,247,0.15); color:#c084fc; border:1px solid rgba(168,85,247,0.3);";
            case "GRAPH" -> "background:rgba(16,185,129,0.15); color:#34d399; border:1px solid rgba(16,185,129,0.3);";
            case "TIMESERIES" -> "background:rgba(245,158,11,0.15); color:#fbbf24; border:1px solid rgba(245,158,11,0.3);";
            case "COLUMN" -> "background:rgba(6,182,212,0.15); color:#22d3ee; border:1px solid rgba(6,182,212,0.3);";
            case "KEYVALUE" -> "background:rgba(34,197,94,0.15); color:#4ade80; border:1px solid rgba(34,197,94,0.3);";
            case "GEOSPATIAL" -> "background:rgba(249,115,22,0.15); color:#fb923c; border:1px solid rgba(249,115,22,0.3);";
            default -> "background:rgba(99,102,241,0.15); color:#818cf8; border:1px solid rgba(99,102,241,0.3);";
        };
    }

    private String getIconForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "fas fa-id-card";
            case "DOCUMENT" -> "fas fa-file-alt";
            case "VECTOR" -> "fas fa-project-diagram";
            case "GRAPH" -> "fas fa-share-alt";
            case "TIMESERIES" -> "fas fa-chart-line";
            case "COLUMN" -> "fas fa-table";
            case "KEYVALUE" -> "fas fa-key";
            case "GEOSPATIAL" -> "fas fa-globe-americas";
            default -> "fas fa-archive";
        };
    }

    private String getDescForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "Java 25 Records";
            case "DOCUMENT" -> "NoSQL JSON";
            case "VECTOR" -> "AI ANN Vectors";
            case "GRAPH" -> "LPG Graph Nodes";
            case "TIMESERIES" -> "IoT Telemetry";
            case "COLUMN" -> "OLAP Columns";
            case "KEYVALUE" -> "Memory Cache";
            case "GEOSPATIAL" -> "2D GIS Spatial";
            default -> "Binary BLOBs";
        };
    }

    public static class DatabaseMetadata {
        private final String name;
        private final Map<String, Integer> engineCounts = new LinkedHashMap<>();

        public DatabaseMetadata(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void addComponent(String engine, int count) {
            engineCounts.put(engine, count);
        }

        public void incrementEngine(String engine) {
            engineCounts.put(engine, engineCounts.getOrDefault(engine, 0) + 1);
        }

        public Map<String, Integer> getEngineCounts() {
            return engineCounts;
        }

        public int getTotalObjects() {
            return engineCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
