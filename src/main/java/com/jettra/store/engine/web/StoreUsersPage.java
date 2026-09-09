package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.security.widget.PageWidgetAllow;
import jcf.AppRole;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.server.autentification.repository.JettraSecurityDBInitializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Optional;
import io.jettra.flux.security.SecurityContext;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.server.autentification.repository.UserUpdateCommand;
import com.jettra.store.engine.exception.ImmutableAccountException;

/**
 * Visual User and RBAC Role Management Console for JettraStoreEngine.
 * Built with pure JettraFlux components, integrated user editing modal, and encapsulated native confirmation dialogs.
 */
@PageWidgetAllow(role = { jcf.AppRole.ADMIN })
public class StoreUsersPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final AuthManager authManager;
    private final JUserRepository userRepo;
    private final JCredentialRepository credRepo;

    public StoreUsersPage(JettraStorageEngine engine, AuthManager authManager) {
        this.engine = engine;
        this.authManager = authManager;
        this.userRepo = new JUserRepositoryImpl();
        this.credRepo = new JCredentialRepositoryImpl();
    }

    protected String resolveCurrentUser(HttpExchange exchange) {
        if (exchange != null) {
            try {
                String loggedUser = getLoggedUser(exchange);
                if (loggedUser != null && !loggedUser.isBlank()) {
                    return loggedUser;
                }
            } catch (Exception ignored) {}
        }
        SecurityContext sec = SecurityContextHolder.getContext();
        if (sec != null && sec.principal() != null && sec.principal().username() != null) {
            return sec.principal().username();
        }
        return "";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> queryParams = parseQueryParams(query);
        if ("get_user".equalsIgnoreCase(queryParams.get("action")) || queryParams.containsKey("fetch_user")) {
            String targetUsername = queryParams.getOrDefault("username", queryParams.get("fetch_user"));
            handleGetUserJson(exchange, targetUsername);
            return;
        }
        super.handle(exchange);
    }

    private void handleGetUserJson(HttpExchange exchange, String username) throws IOException {
        if (username == null || username.isBlank()) {
            sendJsonResponse(exchange, 400, "{\"status\":\"ERROR\",\"message\":\"Username parameter is required\"}");
            return;
        }
        if ("admin".equalsIgnoreCase(username.trim())) {
            String currentUser = resolveCurrentUser(exchange);
            if (!"admin".equalsIgnoreCase(currentUser)) {
                sendJsonResponse(exchange, 403, "{\"status\":\"ERROR\",\"message\":\"Acceso denegado: Solo el usuario admin puede consultar sus datos de edición.\"}");
                return;
            }
        }
        Optional<JUser> userOpt = userRepo.findByUsername(username.trim());
        if (userOpt.isEmpty()) {
            sendJsonResponse(exchange, 404, "{\"status\":\"ERROR\",\"message\":\"User not found\"}");
            return;
        }
        JUser u = userOpt.get();
        String roleName = (u.jRoles() != null && !u.jRoles().isEmpty()) ? u.jRoles().iterator().next().name() : "READ_WRITE";
        String dbs = (u.assignedDatabases() != null && !u.assignedDatabases().isEmpty())
            ? String.join(",", u.assignedDatabases())
            : (u.lastName() != null ? u.lastName() : "*");

        String json = String.format(
            "{\"status\":\"SUCCESS\",\"userId\":\"%s\",\"username\":\"%s\",\"email\":\"%s\",\"role\":\"%s\",\"active\":%b,\"databases\":\"%s\"}",
            u.id(),
            escapeJson(u.firstName()),
            escapeJson(u.email() != null ? u.email() : ""),
            escapeJson(roleName),
            u.active() != null ? u.active() : true,
            escapeJson(dbs)
        );
        sendJsonResponse(exchange, 200, json);
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    protected String getPageTitle() {
        return "Users & Access Security - JettraStoreEngine";
    }

    @Override
    protected RouteVisibilityGuard.NavigationRouteConfig getRouteConfig(HttpExchange exchange, Map<String, String> params) {
        return RouteVisibilityGuard.NavigationRouteConfig.securityConfig(JettraServer.resolvePath("/users"));
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String alertMessage = "";
        String alertType = "badge-active";

        // Handle POST Operations
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                String action = params != null ? params.get("action") : null;
                if ("create_user".equalsIgnoreCase(action)) {
                    String username = params.get("username");
                    String email = params.get("email");
                    String password = params.get("password");
                    String targetDb = params.get("target_db");
                    String targetDbs = params.get("target_dbs");
                    String assignedDbsParam = params.get("assigned_databases");
                    String roleName = params.get("role");

                    if (username != null && !username.isBlank()) {
                        UUID newId = UUID.randomUUID();
                        JRole role = new JRole(UUID.randomUUID(), roleName != null ? roleName : "READ_WRITE", true);
                        Set<JRole> roles = new HashSet<>();
                        roles.add(role);

                        String rawDbParam = targetDbs != null && !targetDbs.isBlank() ? targetDbs
                                          : (targetDb != null && !targetDb.isBlank() ? targetDb
                                          : (assignedDbsParam != null && !assignedDbsParam.isBlank() ? assignedDbsParam : "*"));

                        Set<String> assignedDatabases = new TreeSet<>();
                        for (String part : rawDbParam.split(",")) {
                            String trimmed = part.trim();
                            if (!trimmed.isEmpty()) {
                                assignedDatabases.add(trimmed);
                            }
                        }
                        if (assignedDatabases.isEmpty()) {
                            assignedDatabases.add("*");
                        }

                        String dbScope = String.join(", ", assignedDatabases);
                        JUser newUser = new JUser(newId, username, dbScope, email != null ? email : username + "@jettra.io", "+123456", true, roles, assignedDatabases);
                        userRepo.save(newUser);

                        String rawPassword = password != null && !password.isBlank() ? password : "password123";
                        String hashedPassword = JettraSecurityDBInitializer.hashPassword(rawPassword);
                        JCredential cred = new JCredential(UUID.randomUUID(), newUser, username, hashedPassword, true, Instant.now());
                        credRepo.save(cred);

                        if (authManager != null) {
                            authManager.register(username, rawPassword);
                        }

                        alertMessage = "User '" + username + "' provisioned with role [" + roleName + "] for database scope '" + dbScope + "'!";
                        alertType = "badge-active";
                    }
                } else if ("delete_user".equalsIgnoreCase(action)) {
                    String userId = params.get("user_id");
                    if (userId != null && !userId.isBlank()) {
                        UUID uId = UUID.fromString(userId);
                        Optional<JUser> userOpt = userRepo.findById(uId);
                        if (userOpt.isPresent()) {
                            String uName = userOpt.get().firstName();
                            if ("admin".equalsIgnoreCase(uName)) {
                                throw new ImmutableAccountException("El usuario admin no puede ser revocado.");
                            }
                            if (authManager != null) {
                                authManager.unregister(uName);
                            }
                        }
                        List<JCredential> allCreds = credRepo.findAll();
                        for (JCredential c : allCreds) {
                            if (c.jUser() != null && uId.equals(c.jUser().id())) {
                                credRepo.delete(c.id());
                            }
                        }
                        userRepo.delete(uId);
                        alertMessage = "User account revoked and access removed.";
                        alertType = "badge-raft";
                    }
                } else if ("update_user".equalsIgnoreCase(action) || "edit_user".equalsIgnoreCase(action)) {
                    String userIdStr = params.get("user_id");
                    String username = params.get("username");
                    String email = params.get("email");
                    String roleName = params.get("role");
                    String activeStr = params.get("active");
                    String targetDbs = params.get("target_dbs");
                    String targetDb = params.get("target_db");
                    String assignedDbsParam = params.get("assigned_databases");
                    String newPassword = params.get("password");

                    if ((username == null || username.isBlank()) && userIdStr != null && !userIdStr.isBlank()) {
                        try {
                            UUID uId = UUID.fromString(userIdStr.trim());
                            Optional<JUser> byId = userRepo.findById(uId);
                            if (byId.isPresent()) {
                                username = byId.get().firstName();
                            }
                        } catch (Exception ignored) {}
                    }

                    if (username == null || username.isBlank()) {
                        alertMessage = "Update failed: Username is required.";
                        alertType = "badge-raft";
                    } else {
                        Optional<JUser> userOpt = userRepo.findByUsername(username.trim());
                        if (userOpt.isEmpty()) {
                            alertMessage = "Update failed: User '" + username + "' not found.";
                            alertType = "badge-raft";
                        } else {
                            JUser existingUser = userOpt.get();

                            String loggedUser = resolveCurrentUser(exchange);

                            // Strict immutability guard: Only active authenticated user 'admin' can edit the admin record
                            if ("admin".equalsIgnoreCase(existingUser.firstName()) && !"admin".equalsIgnoreCase(loggedUser)) {
                                throw new ImmutableAccountException("Operación denegada: Solo el usuario admin activo puede modificar el perfil de admin.");
                            }

                            // Self-lockout check: an admin editing their own account cannot remove their admin role or deactivate their account
                            boolean isSelf = loggedUser != null && loggedUser.equalsIgnoreCase(username.trim());
                            boolean isDemotingSelf = isSelf && roleName != null && !"DB_ADMIN".equalsIgnoreCase(roleName) && !"ADMIN".equalsIgnoreCase(roleName) && !"SUPERADMIN".equalsIgnoreCase(roleName);
                            boolean isDeactivatingSelf = isSelf && "false".equalsIgnoreCase(activeStr);

                            if (isDemotingSelf || isDeactivatingSelf) {
                                alertMessage = "Self-lockout prevented: You cannot remove your own administrator role or deactivate your own account.";
                                alertType = "badge-raft";
                            } else {
                                String effectiveRole = (roleName != null && !roleName.isBlank()) ? roleName.trim() : "READ_WRITE";
                                JRole updatedRole = new JRole(UUID.randomUUID(), effectiveRole, true);
                                Set<JRole> newRoles = new HashSet<>();
                                newRoles.add(updatedRole);

                                String rawDbParam = targetDbs != null && !targetDbs.isBlank() ? targetDbs
                                                  : (targetDb != null && !targetDb.isBlank() ? targetDb
                                                  : (assignedDbsParam != null && !assignedDbsParam.isBlank() ? assignedDbsParam : null));

                                Set<String> newAssignedDbs = new TreeSet<>();
                                if (rawDbParam != null && !rawDbParam.isBlank()) {
                                    for (String part : rawDbParam.split(",")) {
                                        String trimmed = part.trim();
                                        if (!trimmed.isEmpty()) {
                                            newAssignedDbs.add(trimmed);
                                        }
                                    }
                                } else if (existingUser.assignedDatabases() != null) {
                                    newAssignedDbs.addAll(existingUser.assignedDatabases());
                                }
                                if (newAssignedDbs.isEmpty()) {
                                    newAssignedDbs.add("*");
                                }

                                Boolean active = (activeStr != null && !activeStr.isBlank()) ? Boolean.parseBoolean(activeStr) : existingUser.active();
                                String updatedEmail = (email != null && !email.isBlank()) ? email.trim() : existingUser.email();

                                UserUpdateCommand updateCmd = new UserUpdateCommand(
                                    updatedEmail,
                                    existingUser.phone(),
                                    active,
                                    newRoles,
                                    newAssignedDbs,
                                    newPassword
                                );

                                Optional<JUser> updatedOpt = userRepo.updateUser(username.trim(), updateCmd);
                                if (updatedOpt.isPresent()) {
                                    JUser updatedUser = updatedOpt.get();

                                    // Synchronize credentials: preserve existing password hash unless explicit new password is provided
                                    List<JCredential> allCreds = credRepo.findAll();
                                    for (JCredential c : allCreds) {
                                        if (c.jUser() != null && existingUser.id().equals(c.jUser().id())) {
                                            String passHash = (newPassword != null && !newPassword.isBlank())
                                                ? JettraSecurityDBInitializer.hashPassword(newPassword.trim())
                                                : c.passwordHash();
                                            JCredential updatedCred = new JCredential(
                                                c.id(),
                                                updatedUser,
                                                c.username(),
                                                passHash,
                                                active,
                                                c.lastLogin()
                                            );
                                            credRepo.save(updatedCred);
                                        }
                                    }

                                    if (authManager != null && newPassword != null && !newPassword.isBlank()) {
                                        authManager.register(username.trim(), newPassword.trim());
                                    }

                                    alertMessage = "User '" + username + "' updated successfully! Role: [" + effectiveRole + "], Scoped DBs: [" + String.join(", ", newAssignedDbs) + "]";
                                    alertType = "badge-active";
                                } else {
                                    alertMessage = "Update failed in persistence layer.";
                                    alertType = "badge-raft";
                                }
                            }
                        }
                    }
                }
            } catch (io.jettra.server.autentification.exception.ImmutableAccountException e) {
                alertMessage = e.getMessage();
                alertType = "badge-raft";
            } catch (Exception e) {
                alertMessage = "Operation failed: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1,
                    Icon.of("fas fa-users-cog").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Users & Per-Database Security")
                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(
                    Text.of("Manage database user accounts, scoped database permissions, RBAC roles, and authentication credentials.")
                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            ),
            Row.of(
                Link.of(JettraServer.resolvePath("/databases"),
                    Icon.of("fas fa-server"),
                    Text.of(" Databases")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-right: 8px;")),
                Link.of(JettraServer.resolvePath("/dashboard"),
                    Icon.of("fas fa-arrow-left"),
                    Text.of(" Dashboard")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary"))
            ).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner (if any)
        Widget alertWidget = alertMessage.isEmpty() ? Div.of() : Div.of(
            Div.of(
                Icon.of("fas fa-user-check").modifier(new Modifier().style("color:#38bdf8; font-size:18px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:14px; color:#f8fafc; font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
            Span.of("RBAC SYNCED").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;"));

        // Discover active databases to populate select
        Set<String> discoveredDbs = new TreeSet<>();
        discoveredDbs.add("records_store");
        discoveredDbs.add("system_db");
        if (engine != null && engine.getStorageCore() != null) {
            String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
            for (String p : prefixes) {
                Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
                if (keys != null) {
                    for (String k : keys.keySet()) {
                        String rest = k.substring(p.length());
                        int colonIdx = rest.indexOf(':');
                        if (colonIdx > 0) {
                            discoveredDbs.add(rest.substring(0, colonIdx));
                        }
                    }
                }
            }
        }

        // Filter DB
        String filterDb = params != null && params.containsKey("filter_db") ? params.get("filter_db") : "*";

        // Load users from JettraSecurityDB
        List<JUser> allUsers = userRepo.findAll();
        List<JUser> users = "*".equals(filterDb) ? allUsers : allUsers.stream().filter(u -> u.isAuthorizedForDatabase(filterDb)).toList();

        // Build Users Table
        List<Widget> tableHeaders = List.of(
            Text.of("Username"),
            Text.of("Email"),
            Text.of("Assigned Databases"),
            Text.of("Assigned Role"),
            Text.of("Account Status"),
            Text.of("Actions")
        );

        List<List<Widget>> tableRows = new ArrayList<>();
        if (users.isEmpty()) {
            tableRows.add(List.of(
                Span.of("No users provisioned for database scope '" + filterDb + "'. Use the form below to create one.")
                    .modifier(new Modifier().style("color:#94a3b8; text-align:center;")),
                Span.of(""), Span.of(""), Span.of(""), Span.of(""), Span.of("")
            ));
        } else {
            for (JUser u : users) {
                List<Widget> roleBadges = new ArrayList<>();
                if (u.jRoles() != null && !u.jRoles().isEmpty()) {
                    for (JRole r : u.jRoles()) {
                        String roleColor = switch (r.name()) {
                            case "DB_ADMIN" -> "badge-raft";
                            case "READ_WRITE" -> "badge-engine";
                            case "MANAGER" -> "badge-active";
                            default -> "";
                        };
                        roleBadges.add(Span.of(r.name()).modifier(new Modifier().cssClass("store-badge " + roleColor).style("margin-right:4px; font-size:11px;")));
                    }
                } else {
                    roleBadges.add(Span.of("READ_WRITE").modifier(new Modifier().cssClass("store-badge badge-engine")));
                }

                Widget userCell = Div.of(
                    Icon.of("fas fa-user").modifier(new Modifier().style("color:#38bdf8; margin-right:6px;")),
                    Span.of(u.firstName()).modifier(new Modifier().style("font-weight:bold;"))
                );
                Widget emailCell = RawHtml.of("<code style='color:#38bdf8;'>" + (u.email() != null ? u.email() : "-") + "</code>");
                Widget scopeCell = BadgeList.of(u.assignedDatabases())
                    .defaultSeverity("info")
                    .wildcardSeverity("active");
                Widget rolesCell = Div.of(roleBadges.toArray(new Widget[0]));
                Widget statusCell = u.active()
                    ? Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active"))
                    : Span.of("DISABLED").modifier(new Modifier().cssClass("store-badge").style("background:rgba(239,68,68,0.2); color:#f87171;"));

                String loggedInUser = resolveCurrentUser(exchange);
                boolean isRowAdmin = "admin".equalsIgnoreCase(u.firstName());
                boolean isCurrentSessionAdmin = "admin".equalsIgnoreCase(loggedInUser);

                Widget editAction;
                if (isRowAdmin && !isCurrentSessionAdmin) {
                    editAction = Span.of(
                        Icon.of("fas fa-lock").modifier(new Modifier().style("margin-right:4px;")),
                        Text.of("Bloqueado")
                    ).attribute("title", "Solo el usuario admin puede editar su propia cuenta")
                     .modifier(new Modifier().cssClass("store-badge").style("background:rgba(148,163,184,0.15); color:#94a3b8; border:1px solid rgba(148,163,184,0.3); padding:4px 8px; font-size:11px; margin-right:6px; cursor:not-allowed; display:inline-flex; align-items:center;"));
                } else {
                    Button editBtn = Button.of(Icon.of("fas fa-user-edit"), Text.of(" Edit"));
                    String safeUsername = u.firstName().replace("'", "\\'");
                    String safeEmail = (u.email() != null ? u.email() : "").replace("'", "\\'");
                    String userRole = (u.jRoles() != null && !u.jRoles().isEmpty()) ? u.jRoles().iterator().next().name() : "READ_WRITE";
                    String userDbs = (u.assignedDatabases() != null && !u.assignedDatabases().isEmpty())
                        ? String.join(",", u.assignedDatabases())
                        : (u.lastName() != null ? u.lastName() : "*");
                    editBtn.attribute("onclick", "openEditUser('" + u.id() + "', '" + safeUsername + "', '" + safeEmail + "', '" + userRole + "', '" + userDbs + "', " + u.active() + ")");
                    editBtn.modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:4px 8px; font-size:11px; margin-right:6px;"));
                    editAction = editBtn;
                }

                Widget revokeAction;
                if (isRowAdmin) {
                    revokeAction = Button.of(Icon.of("fas fa-shield-alt"), Text.of(" Protegido"))
                        .attribute("disabled", "disabled")
                        .attribute("title", "Acción protegida")
                        .modifier(new Modifier().cssClass("btn-action").style("padding:4px 8px; font-size:11px; background:rgba(148,163,184,0.15); color:#64748b; border:1px solid rgba(148,163,184,0.25); cursor:not-allowed; opacity:0.7;"));
                } else {
                    Button revokeBtn = Button.of(Icon.of("fas fa-trash"), Text.of(" Revoke"));
                    revokeBtn.attribute("onclick", "confirmRevokeUser('" + u.id() + "', '" + u.firstName() + "')");
                    revokeBtn.modifier(new Modifier().cssClass("btn-action btn-danger").style("padding:4px 8px; font-size:11px;"));
                    revokeAction = revokeBtn;
                }

                Widget actionsCell = Div.of(editAction, revokeAction).modifier(new Modifier().style("display:inline-flex; align-items:center;"));

                tableRows.add(List.of(userCell, emailCell, scopeCell, rolesCell, statusCell, actionsCell));
            }
        }

        Datatable datatable = Datatable.ofWidgets(tableHeaders, tableRows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        List<String> filterOptionsList = new ArrayList<>();
        filterOptionsList.add("*");
        filterOptionsList.addAll(discoveredDbs);

        Dropdown filterDropdown = Dropdown.of(filterOptionsList)
            .selected(filterDb)
            .placeholder(null);
        filterDropdown.attribute("onchange", "window.location.href='" + JettraServer.resolvePath("/users?filter_db=") + "' + this.value");
        filterDropdown.modifier(new Modifier().style("padding:6px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#38bdf8; font-size:13px;"));

        Widget usersCard = Div.of(
            Row.of(
                Column.of(
                    Header.of(3,
                        Icon.of("fas fa-user-shield").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                        Text.of("Database User Accounts (" + users.size() + ")")
                    ).modifier(new Modifier().style("margin: 0; font-size: 18px; font-weight: 600;")),
                    Div.of(
                        Text.of("Scope: "),
                        Span.of("*".equals(filterDb) ? "All Scopes" : filterDb).modifier(new Modifier().style("color:#38bdf8; font-weight:bold;"))
                    ).modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin-top:2px;"))
                ),
                Row.of(
                    Label.of("Filter by DB:").modifier(new Modifier().style("font-size:12px; color:#94a3b8; margin-right:6px;")),
                    filterDropdown
                ).modifier(new Modifier().style("align-items:center;"))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 16px;")),
            Div.of(datatable).modifier(new Modifier().cssClass("table-responsive"))
        ).modifier(new Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Build MultiSelect Database options for user creation
        MultiSelect userDbMultiSelect = MultiSelect.of("userTargetDbs", "target_dbs")
            .label("Target Databases Access")
            .selectAllOption(true, "* (All Databases)")
            .options(discoveredDbs)
            .selectedValues("*")
            .placeholder("Select one or more databases...")
            .quickActions(true);

        Dropdown roleDropdown = Dropdown.of("DB_ADMIN", "READ_WRITE", "READ_ONLY", "MANAGER").selected("READ_WRITE").placeholder(null);
        roleDropdown.attribute("name", "role");
        roleDropdown.modifier(new Modifier().style("width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;"));

        // Create User Form Card
        Widget createUserCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-user-plus").modifier(new Modifier().style("color:#4ade80; margin-right:8px;")),
                Text.of("Provision New User for Databases")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Paragraph.of(Text.of("Assign user permissions scoped directly to one or multiple databases or globally across all 9 engines."))
                .modifier(new Modifier().style("font-size: 13px; color: #94a3b8; margin-bottom: 16px;")),
            Form.of(
                Hidden.of("action", "create_user"),
                Div.of(
                    Div.of(
                        Label.of("Username").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        TextField.of("username", "e.g. carlos_mendez")
                            .modifier(new Modifier().style("width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;"))
                    ),
                    Div.of(
                        Label.of("Email").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        TextField.of("email", "carlos@company.com")
                            .modifier(new Modifier().style("width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;"))
                    ),
                    Div.of(
                        Label.of("Password").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        RawHtml.of("<input class='form-input' type='password' name='password' placeholder='••••••••' required style='width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;'/>")
                    ),
                    Div.of(
                        Label.of("Assigned Role").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        roleDropdown
                    )
                ).modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin-bottom:14px;")),
                Div.of(
                    userDbMultiSelect
                ).modifier(new Modifier().style("margin-bottom:16px;")),
                Button.of(Icon.of("fas fa-user-plus"), Text.of(" Provision User"))
                    .attribute("type", "submit")
                    .modifier(new Modifier().cssClass("btn-action btn-primary"))
            ).action(JettraServer.resolvePath("/users")).method("POST")
        ).modifier(new Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Roles & Policy Grid
        Widget rolesCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-id-badge").modifier(new Modifier().style("color:#a855f7; margin-right:8px;")),
                Text.of("Per-Database Role Matrix (RBAC)")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Div.of(
                Div.of(
                    Span.of(Span.of("DB_ADMIN").modifier(new Modifier().cssClass("store-badge badge-raft")), Text.of(" Database Administrator")),
                    Span.of("Create/Drop Collections, Full R/W").modifier(new Modifier().style("color:#4ade80;"))
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;")),
                Div.of(
                    Span.of(Span.of("READ_WRITE").modifier(new Modifier().cssClass("store-badge badge-engine")), Text.of(" Application Read/Write")),
                    Span.of("Insert, Update, Delete & Queries").modifier(new Modifier().style("color:#60a5fa;"))
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;")),
                Div.of(
                    Span.of(Span.of("READ_ONLY").modifier(new Modifier().cssClass("store-badge").style("background:rgba(255,255,255,0.1); color:#e2e8f0;")), Text.of(" Data Analyst / Reader")),
                    Span.of("Scan & Query Only").modifier(new Modifier().style("color:#94a3b8;"))
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;")),
                Div.of(
                    Span.of(Span.of("MANAGER").modifier(new Modifier().cssClass("store-badge").style("background:rgba(245,158,11,0.2); color:#fbbf24;")), Text.of(" Ops & Backup Manager")),
                    Span.of("WAL Snapshots & Recovery").modifier(new Modifier().style("color:#f59e0b;"))
                ).modifier(new Modifier().style("padding:8px 0; display:flex; justify-content:space-between; align-items:center;"))
            ).modifier(new Modifier().style("font-size:13px; color:#cbd5e1;"))
        ).modifier(new Modifier().cssClass("store-card"));

        Widget tokenPolicyCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-key").modifier(new Modifier().style("color:#f59e0b; margin-right:8px;")),
                Text.of("Security Policies & Token Config")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Div.of(
                Div.of(
                    Span.of("JWT Expiration:"),
                    RawHtml.of("<code style='color:#f59e0b;'>3600000 ms (1 Hour)</code>")
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;")),
                Div.of(
                    Span.of("Algorithm:"),
                    RawHtml.of("<code style='color:#38bdf8;'>HMAC-SHA256 (JettraJWT)</code>")
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;")),
                Div.of(
                    Span.of("Credential Storage:"),
                    RawHtml.of("<code style='color:#34d399;'>JettraSecurityDB SQLite / Memory</code>")
                ).modifier(new Modifier().style("padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;")),
                Div.of(
                    Span.of("Password Hashing:"),
                    RawHtml.of("<code style='color:#a78bfa;'>SHA-256 with Salt</code>")
                ).modifier(new Modifier().style("padding:8px 0; display:flex; justify-content:space-between;"))
            ).modifier(new Modifier().style("font-size:13px; color:#cbd5e1;"))
        ).modifier(new Modifier().cssClass("store-card"));

        Widget bottomGrid = Div.of(rolesCard, tokenPolicyCard)
            .modifier(new Modifier().style("display: grid; grid-template-columns: 1fr 1fr; gap: 20px;"));

        // Native JettraFlux Modal for User Profile & Multi-Database Assignment Editing
        Widget editUserModal = JettraUserEditModal.of("editUserModal")
            .title("Edit User Profile & Security Permissions")
            .subtitle("Update RBAC role, account status, and authorized target databases.")
            .formAction(JettraServer.resolvePath("/users"))
            .actionName("update_user")
            .roles("DB_ADMIN", "READ_WRITE", "READ_ONLY", "MANAGER")
            .databases(discoveredDbs)
            .submitText("Guardar Cambios")
            .cancelText("Cancelar");

        // Native JettraFlux Confirmation Dialog for User Revocation
        Widget revokeUserConfirmModal = JettraConfirmDialog.of("revokeUserConfirmDialog")
            .title("Confirm User Access Revocation")
            .warningMessage("Are you sure you want to revoke this user account? The user credentials and assigned RBAC permissions will be permanently removed.")
            .targetItemLabel("Target User Account:")
            .targetItemIcon("fas fa-user-slash")
            .confirmText("Revoke Access")
            .confirmIcon("fas fa-user-slash")
            .cancelText("Cancel")
            .formAction(JettraServer.resolvePath("/users"))
            .actionName("delete_user")
            .targetParamName("user_id");

        Widget scriptsWidget = RawHtml.of(
            "<script>\n" +
            "  function confirmRevokeUser(userId, username) {\n" +
            "    if (window.JettraConfirmDialog) {\n" +
            "      window.JettraConfirmDialog.open('revokeUserConfirmDialog', userId, username);\n" +
            "    }\n" +
            "  }\n" +
            "  function openEditUser(userId, username, email, role, databases, active) {\n" +
            "    if (window.JettraUserEditModal) {\n" +
            "      window.JettraUserEditModal.open('editUserModal', {\n" +
            "        userId: userId,\n" +
            "        username: username,\n" +
            "        email: email,\n" +
            "        role: role,\n" +
            "        databases: databases,\n" +
            "        active: active\n" +
            "      });\n" +
            "    }\n" +
            "  }\n" +
            "</script>\n"
        );

        return Column.of(
            titleBlock,
            alertWidget,
            usersCard,
            createUserCard,
            bottomGrid,
            editUserModal,
            revokeUserConfirmModal,
            scriptsWidget
        );
    }
}
