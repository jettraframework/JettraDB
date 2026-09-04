package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import jcf.annotation.PageWidgetAllow;
import jcf.AppRole;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.ArrayList;

/**
 * Visual User and RBAC Role Management Console for JettraStoreEngine.
 * Built with pure JettraFlux components.
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

    @Override
    protected String getPageTitle() {
        return "Users & Access Security - JettraStoreEngine";
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
                    String roleName = params.get("role");

                    if (username != null && !username.isBlank()) {
                        UUID newId = UUID.randomUUID();
                        JRole role = new JRole(UUID.randomUUID(), roleName != null ? roleName : "READ_WRITE", true);
                        Set<JRole> roles = new HashSet<>();
                        roles.add(role);

                        String dbScope = targetDb != null && !targetDb.isBlank() ? targetDb : "*";
                        JUser newUser = new JUser(newId, username, dbScope, email != null ? email : username + "@jettra.io", "+123456", true, roles);
                        userRepo.save(newUser);

                        JCredential cred = new JCredential(UUID.randomUUID(), newUser, username, password != null && !password.isBlank() ? password : "password123", true, Instant.now());
                        credRepo.save(cred);

                        alertMessage = "User '" + username + "' provisioned with role [" + roleName + "] for database scope '" + dbScope + "'!";
                        alertType = "badge-active";
                    }
                } else if ("delete_user".equalsIgnoreCase(action)) {
                    String userId = params.get("user_id");
                    if (userId != null && !userId.isBlank()) {
                        userRepo.delete(UUID.fromString(userId));
                        alertMessage = "User account revoked and access removed.";
                        alertType = "badge-raft";
                    }
                }
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
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
            for (String k : keys.keySet()) {
                String rest = k.substring(p.length());
                int colonIdx = rest.indexOf(':');
                if (colonIdx > 0) {
                    discoveredDbs.add(rest.substring(0, colonIdx));
                }
            }
        }

        // Filter DB
        String filterDb = params != null && params.containsKey("filter_db") ? params.get("filter_db") : "*";

        // Load users from JettraSecurityDB
        List<JUser> allUsers = userRepo.findAll();
        List<JUser> users = "*".equals(filterDb) ? allUsers : allUsers.stream().filter(u -> filterDb.equalsIgnoreCase(u.lastName()) || "*".equals(u.lastName())).toList();

        // Build Users Table
        List<Widget> tableHeaders = List.of(
            Text.of("Username"),
            Text.of("Email"),
            Text.of("Database Scope"),
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
                String dbScope = u.lastName() != null && !u.lastName().isBlank() ? u.lastName() : "* (ALL)";
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
                Widget scopeCell = Span.of(
                    Icon.of("fas fa-database").modifier(new Modifier().style("margin-right:4px;")),
                    Text.of(" " + dbScope)
                ).modifier(new Modifier().cssClass("store-badge badge-engine"));
                Widget rolesCell = Div.of(roleBadges.toArray(new Widget[0]));
                Widget statusCell = u.active()
                    ? Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge badge-active"))
                    : Span.of("DISABLED").modifier(new Modifier().cssClass("store-badge").style("background:rgba(239,68,68,0.2); color:#f87171;"));

                Button revokeBtn = Button.of(Icon.of("fas fa-trash"), Text.of(" Revoke"));
                revokeBtn.attribute("onclick", "deleteUser('" + u.id() + "')");
                revokeBtn.modifier(new Modifier().cssClass("btn-action btn-danger").style("padding:4px 8px; font-size:11px;"));

                tableRows.add(List.of(userCell, emailCell, scopeCell, rolesCell, statusCell, revokeBtn));
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

        // Build Database options for user creation
        List<String> userDbOptions = new ArrayList<>();
        userDbOptions.add("*");
        userDbOptions.addAll(discoveredDbs);

        Dropdown userDbDropdown = Dropdown.of(userDbOptions).selected("*").placeholder(null);
        userDbDropdown.attribute("name", "target_db");
        userDbDropdown.modifier(new Modifier().style("width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;"));

        Dropdown roleDropdown = Dropdown.of("DB_ADMIN", "READ_WRITE", "READ_ONLY", "MANAGER").selected("READ_WRITE").placeholder(null);
        roleDropdown.attribute("name", "role");
        roleDropdown.modifier(new Modifier().style("width:100%; padding:8px 10px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:6px; color:#f8fafc; box-sizing:border-box;"));

        // Create User Form Card
        Widget createUserCard = Div.of(
            Header.of(3,
                Icon.of("fas fa-user-plus").modifier(new Modifier().style("color:#4ade80; margin-right:8px;")),
                Text.of("Provision New User for Database")
            ).modifier(new Modifier().style("margin: 0 0 12px 0; font-size: 16px; font-weight: 600;")),
            Paragraph.of(Text.of("Assign user permissions scoped directly to a specific database namespace or globally across all 9 engines."))
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
                        Label.of("Database Scope").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        userDbDropdown
                    ),
                    Div.of(
                        Label.of("Assigned Role").modifier(new Modifier().style("font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;")),
                        roleDropdown
                    )
                ).modifier(new Modifier().style("display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin-bottom:14px;")),
                Button.of(Icon.of("fas fa-user-plus"), Text.of(" Provision User"))
                    .attribute("type", "submit")
                    .modifier(new Modifier().cssClass("btn-action btn-primary"))
            ).action(JettraServer.resolvePath("/users")).method("POST"),
            Form.of(
                Hidden.of("action", "delete_user"),
                Hidden.of("user_id").id("delUserId")
            ).action(JettraServer.resolvePath("/users")).method("POST").id("deleteUserForm").modifier(new Modifier().style("display:none;")),
            RawScript.of(
                "function deleteUser(uid) {\n" +
                "  if (confirm('Revoke access for this user account?')) {\n" +
                "    document.getElementById('delUserId').value = uid;\n" +
                "    document.getElementById('deleteUserForm').submit();\n" +
                "  }\n" +
                "}"
            )
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

        return Column.of(
            titleBlock,
            alertWidget,
            usersCard,
            createUserCard,
            bottomGrid
        );
    }
}
