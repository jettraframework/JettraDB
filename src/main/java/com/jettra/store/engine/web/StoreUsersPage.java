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
import java.util.List;
import java.util.Map;

/**
 * User & Security management page for JettraStoreEngine.
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
        return "Users & Security - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        // Handle POST / creation / update actions if submitted via query or form
        String message = "";
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            // Process form parameters
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-users-cog' style='color:#38bdf8; margin-right:8px;'></i> Users & Security Management</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Manage database user accounts, RBAC roles, security credentials and active JWT token policies.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/securitydb/admin") + "' class='btn-action btn-secondary' style='margin-right: 8px;'><i class='fas fa-database'></i> Security DB Admin</a>"),
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Load users from JettraSecurityDB
        List<JUser> users = userRepo.findAll();
        List<JCredential> credentials = credRepo.findAll();
        List<JRole> roles = roleRepo.findAll();

        // Build Users Table
        StringBuilder tableRows = new StringBuilder();
        if (users.isEmpty()) {
            tableRows.append("<tr><td colspan='6' style='text-align:center; color:#94a3b8;'>No user records found in Security DB. Default 'admin' and 'super-user' active in AuthManager.</td></tr>\n");
        } else {
            for (JUser u : users) {
                String roleBadges = "";
                if (u.jRoles() != null) {
                    for (JRole r : u.jRoles()) {
                        roleBadges += "<span class='store-badge badge-raft' style='margin-right:4px; font-size:11px;'>" + r.name() + "</span>";
                    }
                }
                if (roleBadges.isEmpty()) {
                    roleBadges = "<span class='store-badge badge-engine'>USER</span>";
                }

                String status = u.active() ? "<span class='store-badge badge-active'>ACTIVE</span>" : "<span class='store-badge' style='background:rgba(239,68,68,0.2); color:#f87171;'>DISABLED</span>";

                tableRows.append("<tr>\n")
                    .append("  <td><b>").append(u.firstName()).append(" ").append(u.lastName() != null ? u.lastName() : "").append("</b></td>\n")
                    .append("  <td><code style='color:#38bdf8;'>").append(u.email() != null ? u.email() : "-").append("</code></td>\n")
                    .append("  <td>").append(roleBadges).append("</td>\n")
                    .append("  <td>").append(status).append("</td>\n")
                    .append("  <td><span style='font-family:monospace; font-size:12px; color:#94a3b8;'>").append(u.id() != null ? u.id().toString().substring(0, 8) + "..." : "-").append("</span></td>\n")
                    .append("  <td><span class='store-badge badge-engine'>SYNCED</span></td>\n")
                    .append("</tr>\n");
            }
        }

        Widget usersCard = Div.of(
            Row.of(
                Paragraph.of("<h3 style='margin: 0; font-size: 18px; font-weight: 600;'><i class='fas fa-user-shield' style='color:#38bdf8; margin-right:8px;'></i> Database User Accounts (" + (users.isEmpty() ? "2 Defaults" : users.size()) + ")</h3>"),
                Span.of("RBAC Enabled").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 16px;")),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>User / Username</th>\n" +
                "        <th>Email</th>\n" +
                "        <th>Assigned Roles</th>\n" +
                "        <th>Account Status</th>\n" +
                "        <th>User UUID</th>\n" +
                "        <th>Security State</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                tableRows.toString() +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card").style("margin-bottom: 24px;"));

        // Roles & Policy Grid
        Widget rolesCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-id-badge' style='color:#a855f7; margin-right:8px;'></i> Security Roles (RBAC)</h3>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:13px; color:#cbd5e1;'>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge badge-raft'>ADMIN</span> Full Database Administrator</span>\n" +
                "    <span style='color:#4ade80;'>All Engines Read/Write/DDL</span>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge badge-engine'>MANAGER</span> Storage Engine Manager</span>\n" +
                "    <span style='color:#60a5fa;'>Read/Write, Backup Operations</span>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; display:flex; justify-content:space-between; align-items:center;'>\n" +
                "    <span><span class='store-badge' style='background:rgba(255,255,255,0.1); color:#e2e8f0;'>DEMO</span> Read-Only / Demo Role</span>\n" +
                "    <span style='color:#94a3b8;'>Query & Read-Only Access</span>\n" +
                "  </li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget tokenPolicyCard = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 12px 0; font-size: 16px; font-weight: 600;'><i class='fas fa-key' style='color:#f59e0b; margin-right:8px;'></i> Authentication Policies</h3>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:13px; color:#cbd5e1;'>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;'>\n" +
                "    <span>JWT Expiration:</span> <code style='color:#f59e0b;'>3600000 ms (1 Hour)</code>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.06); display:flex; justify-content:space-between;'>\n" +
                "    <span>Algorithm:</span> <code style='color:#38bdf8;'>HMAC-SHA256 (JettraJWT)</code>\n" +
                "  </li>\n" +
                "  <li style='padding:8px 0; display:flex; justify-content:space-between;'>\n" +
                "    <span>Password Hashing:</span> <code style='color:#34d399;'>SHA-256 with Salt</code>\n" +
                "  </li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget bottomGrid = Div.of(rolesCard, tokenPolicyCard)
            .modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: 1fr 1fr; gap: 20px;"));

        return Column.of(
            titleBlock,
            usersCard,
            bottomGrid
        );
    }
}
