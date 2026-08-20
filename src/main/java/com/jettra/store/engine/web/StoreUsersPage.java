package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JRoleRepository;
import io.jettra.server.autentification.repository.JRoleRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
import io.jettra.core.login.NoLoginRequired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User & Security management page for JettraStoreEngine.
 * Supports per-database and per-engine scoped user management and RBAC role assignment.
 */
@NoLoginRequired
public class StoreUsersPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final AuthManager authManager;
    private final JUserRepository userRepo;
    private final JCredentialRepository credRepo;
    private final JRoleRepository roleRepo;

    public StoreUsersPage(JettraStorageEngine engine, AuthManager authManager) {
        this.engine = engine;
        this.authManager = authManager;
        this.userRepo = new JUserRepositoryImpl();
        this.credRepo = new JCredentialRepositoryImpl();
        this.roleRepo = new JRoleRepositoryImpl();
    }

    @Override
    protected String getPageTitle() {
        return "Users & Per-Database Security - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String alertMessage = "";
        String alertType = "badge-active";

        // Handle POST User Creation
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
                        java.util.Set<JRole> roles = new java.util.HashSet<>();
                        roles.add(role);

                        String dbScope = targetDb != null && !targetDb.isBlank() ? targetDb : "*";
                        JUser newUser = new JUser(newId, username, dbScope, email != null ? email : username + "@jettra.io", "+123456", true, roles);
                        userRepo.save(newUser);

                        JCredential cred = new JCredential(UUID.randomUUID(), newUser, username, password != null ? password : "password123", true, Instant.now());
                        credRepo.save(cred);

                        alertMessage = "User '" + username + "' successfully created with role '" + roleName + "' for database scope '" + dbScope + "'!";
                        alertType = "badge-active";
                    }
                }
            } catch (Exception e) {
                alertMessage = "Error creating user: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-users-cog' style='color:#38bdf8; margin-right:8px;'></i> Users & Per-Database Security</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Manage database user accounts, scoped database permissions, RBAC roles, and authentication credentials.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/securitydb/admin") + "' class='btn-action btn-secondary' style='margin-right: 8px;'><i class='fas fa-database'></i> Security DB Admin</a>"),
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner (if any)
        Widget alertWidget = alertMessage.isEmpty() ? Paragraph.of("") : Paragraph.of(
            "<div style='background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;'>\n" +
            "  <div style='display:flex; align-items:center; gap:10px;'><i class='fas fa-user-check' style='color:#38bdf8; font-size:18px;'></i> <span style='font-size:14px; color:#f8fafc; font-weight:500;'>" + alertMessage + "</span></div>\n" +
            "  <span class='store-badge " + alertType + "'>RBAC SYNCED</span>\n" +
            "</div>\n"
        );

        // Load users from JettraSecurityDB
        List<JUser> users = userRepo.findAll();

        // Build Users Table
        StringBuilder tableRows = new StringBuilder();
        if (users.isEmpty()) {
            tableRows.append("<tr>\n")
                .append("  <td><b>admin</b></td>\n")
                .append("  <td><code style='color:#38bdf8;'>admin@jettra.io</code></td>\n")
                .append("  <td><span class='store-badge badge-engine'>* (GLOBAL ALL)</span></td>\n")
                .append("  <td><span class='store-badge badge-raft'>ADMIN</span></td>\n")
                .append("  <td><span class='store-badge badge-active'>ACTIVE</span></td>\n")
                .append("  <td><span class='store-badge badge-engine'>SUPERUSER</span></td>\n")
                .append("</tr>\n");
        } else {
            for (JUser u : users) {
                String roleBadges = "";
                String dbScope = u.lastName() != null && !u.lastName().isBlank() ? u.lastName() : "* (ALL)";
                if (u.jRoles() != null) {
                    for (JRole r : u.jRoles()) {
                        roleBadges += "<span class='store-badge badge-raft' style='margin-right:4px; font-size:11px;'>" + r.name() + "</span>";
                    }
                }
                if (roleBadges.isEmpty()) {
                    roleBadges = "<span class='store-badge badge-engine'>READ_WRITE</span>";
                }

                String status = u.active() ? "<span class='store-badge badge-active'>ACTIVE</span>" : "<span class='store-badge' style='background:rgba(239,68,68,0.2); color:#f87171;'>DISABLED</span>";

                tableRows.append("<tr>\n")
                    .append("  <td><b>").append(u.firstName()).append("</b></td>\n")
                    .append("  <td><code style='color:#38bdf8;'>").append(u.email() != null ? u.email() : "-").append("</code></td>\n")
                    .append("  <td><span class='store-badge badge-engine'>").append(dbScope).append("</span></td>\n")
                    .append("  <td>").append(roleBadges).append("</td>\n")
                    .append("  <td>").append(status).append("</td>\n")
                    .append("  <td><span style='font-family:monospace; font-size:12px; color:#94a3b8;'>").append(u.id() != null ? u.id().toString().substring(0, 8) + "..." : "-").append("</span></td>\n")
                    .append("</tr>\n");
            }
        }

        Widget usersCard = Div.of(
            Row.of(
                Paragraph.of("<h3 style='margin: 0; font-size: 18px; font-weight: 600;'><i class='fas fa-user-shield' style='color:#38bdf8; margin-right:8px;'></i> Database User Accounts (" + (users.isEmpty() ? 1 : users.size()) + ")</h3>"),
                Span.of("Per-Database RBAC").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 16px;")),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>Username</th>\n" +
                "        <th>Email</th>\n" +
                "        <th>Database Scope</th>\n" +
                "        <th>Assigned Role</th>\n" +
                "        <th>Account Status</th>\n" +
                "        <th>User UUID</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                tableRows.toString() +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Create User Form Card
        Widget createUserCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-user-plus' style='color:#4ade80; margin-right:8px;'></i> Create New Database User & Assign Roles</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #94a3b8; margin-bottom: 16px;'>Provision user credentials with granular role-based permissions scoped to a specific database or engine.</p>"),
            Paragraph.of(
                "<form method='POST' action='" + JettraServer.resolvePath("/users") + "'>\n" +
                "  <input type='hidden' name='action' value='create_user' />\n" +
                "  <div style='display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin-bottom:14px;'>\n" +
                "    <div>\n" +
                "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Username</label>\n" +
                "      <input class='form-input' type='text' name='username' placeholder='e.g. dev_user' required />\n" +
                "    </div>\n" +
                "    <div>\n" +
                "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Email</label>\n" +
                "      <input class='form-input' type='email' name='email' placeholder='dev@company.com' required />\n" +
                "    </div>\n" +
                "    <div>\n" +
                "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Password</label>\n" +
                "      <input class='form-input' type='password' name='password' placeholder='••••••••' required />\n" +
                "    </div>\n" +
                "    <div>\n" +
                "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Database Scope</label>\n" +
                "      <select name='target_db' class='form-input'>\n" +
                "        <option value='*'>* (All Databases / Global)</option>\n" +
                "        <option value='customers_db'>customers_db (DOCUMENT)</option>\n" +
                "        <option value='ai_embeddings_db'>ai_embeddings_db (VECTOR)</option>\n" +
                "        <option value='knowledge_graph'>knowledge_graph (GRAPH)</option>\n" +
                "        <option value='iot_telemetry'>iot_telemetry (TIMESERIES)</option>\n" +
                "        <option value='analytics_olap'>analytics_olap (COLUMN)</option>\n" +
                "        <option value='cache_store'>cache_store (KEYVALUE)</option>\n" +
                "        <option value='gis_layers'>gis_layers (GEOSPATIAL)</option>\n" +
                "        <option value='media_bucket'>media_bucket (OBJECT)</option>\n" +
                "      </select>\n" +
                "    </div>\n" +
                "    <div>\n" +
                "      <label style='font-size:12px; color:#94a3b8; font-weight:600; display:block; margin-bottom:4px;'>Assigned Role</label>\n" +
                "      <select name='role' class='form-input'>\n" +
                "        <option value='DB_ADMIN'>DB_ADMIN (Full DDL & Read/Write)</option>\n" +
                "        <option value='READ_WRITE'>READ_WRITE (Insert, Update, Query)</option>\n" +
                "        <option value='READ_ONLY'>READ_ONLY (Query Only)</option>\n" +
                "        <option value='MANAGER'>MANAGER (Backup & Operations)</option>\n" +
                "      </select>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <button type='submit' class='btn-action btn-primary'><i class='fas fa-user-plus'></i> Create & Provision User</button>\n" +
                "</form>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Roles & Policy Grid
        Widget rolesCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-id-badge' style='color:#a855f7; margin-right:8px;'></i> Per-Database Role Matrix (RBAC)</h3>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:13px; color:#cbd5e1;'>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge badge-raft'>DB_ADMIN</span> Database Administrator</span>\n" +
                "    <span style='color:#4ade80;'>Create/Drop Collections, Full R/W</span>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge badge-engine'>READ_WRITE</span> Application Read/Write</span>\n" +
                "    <span style='color:#60a5fa;'>Insert, Update, Delete & Queries</span>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge' style='background:rgba(255,255,255,0.1); color:#e2e8f0;'>READ_ONLY</span> Data Analyst / Reader</span>\n" +
                "    <span style='color:#94a3b8;'>Scan & Query Only</span>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge' style='background:rgba(245,158,11,0.2); color:#fbbf24;'>MANAGER</span> Ops & Backup Manager</span>\n" +
                "    <span style='color:#f59e0b;'>WAL Snapshots & Recovery</span>\n" +
                "  </li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget tokenPolicyCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-key' style='color:#f59e0b; margin-right:8px;'></i> Security Policies & Token Config</h3>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:13px; color:#cbd5e1;'>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;'>\n" +
                "    <span>JWT Expiration:</span> <code style='color:#f59e0b;'>3600000 ms (1 Hour)</code>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;'>\n" +
                "    <span>Algorithm:</span> <code style='color:#38bdf8;'>HMAC-SHA256 (JettraJWT)</code>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;'>\n" +
                "    <span>Credential Storage:</span> <code style='color:#34d399;'>JettraSecurityDB SQLite / Memory</code>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; display:flex; justify-content:space-between;'>\n" +
                "    <span>Password Hashing:</span> <code style='color:#a78bfa;'>SHA-256 with Salt</code>\n" +
                "  </li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget bottomGrid = Div.of(rolesCard, tokenPolicyCard)
            .modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1fr 1fr; gap: 20px;"));

        return Column.of(
            titleBlock,
            alertWidget,
            usersCard,
            createUserCard,
            bottomGrid
        );
    }
}
