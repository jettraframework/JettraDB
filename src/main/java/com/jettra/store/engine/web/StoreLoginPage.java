package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.pages.FluxBaseHandler;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.util.Map;

/**
 * Authentication and Login page for JettraStoreEngine Web Console.
 */
@NoLoginRequired
public class StoreLoginPage extends FluxBaseHandler {

    private final AuthManager authManager;

    public StoreLoginPage(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        if (params != null && params.containsKey("logout")) {
            // Handle logout if needed
            io.jettra.server.core.JettraContext ctx = io.jettra.server.core.JettraContext.getCurrent();
            if (ctx != null) {
                ctx.set(io.jettra.server.core.JettraContext.Scope.SESSION, "username", null);
                ctx.set(io.jettra.server.core.JettraContext.Scope.SESSION, "credentialFlux", null);
            }
        }

        String errorMessage = "";
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String username = params != null ? params.get("username") : null;
            String password = params != null ? params.get("password") : null;

            if (username != null && password != null) {
                boolean authenticated = false;
                try {
                    authManager.login(username, password);
                    authenticated = true;
                } catch (Exception e) {
                    if (("admin".equals(username) && "admin".equals(password)) ||
                        ("super-user".equals(username) && "superUserZ".equals(password))) {
                        authenticated = true;
                    }
                }

                if (authenticated) {
                    io.jettra.server.core.JettraContext ctx = io.jettra.server.core.JettraContext.getCurrent();
                    if (ctx != null) {
                        ctx.set(io.jettra.server.core.JettraContext.Scope.SESSION, "username", username);
                        ctx.set(io.jettra.server.core.JettraContext.Scope.SESSION, "credentialFlux",
                            new io.jettra.flux.model.CredentialFlux(username, username.toUpperCase(), "ADMIN", "ENGINE", ""));
                    }
                    try {
                        redirect(exchange, "/dashboard");
                        return Column.of();
                    } catch (Exception ignored) {}
                } else {
                    errorMessage = "Invalid username or password.";
                }
            }
        }

        Widget customCss = Paragraph.of(
            "<style>\n" +
            "  .login-container { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: radial-gradient(circle at center, #1e293b 0%, #0f172a 100%); font-family: system-ui, -apple-system, sans-serif; }\n" +
            "  .login-card { width: 100%; max-width: 420px; background: rgba(30, 41, 59, 0.85); border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 16px; padding: 36px; box-shadow: 0 20px 40px rgba(0, 0, 0, 0.4); backdrop-filter: blur(16px); }\n" +
            "  .form-group { margin-bottom: 20px; }\n" +
            "  .form-label { display: block; font-size: 13px; font-weight: 600; color: #94a3b8; margin-bottom: 8px; }\n" +
            "  .form-input { width: 100%; box-sizing: border-box; background: rgba(15, 23, 42, 0.8); border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 8px; padding: 12px 16px; color: #f8fafc; font-size: 14px; outline: none; transition: border-color 0.2s; }\n" +
            "  .form-input:focus { border-color: #3b82f6; box-shadow: 0 0 10px rgba(59, 130, 246, 0.3); }\n" +
            "  .btn-submit { width: 100%; background: linear-gradient(135deg, #3b82f6, #2563eb); color: white; padding: 12px; border-radius: 8px; font-weight: 600; font-size: 15px; border: none; cursor: pointer; transition: all 0.2s; box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3); }\n" +
            "  .btn-submit:hover { background: linear-gradient(135deg, #2563eb, #1d4ed8); transform: translateY(-1px); }\n" +
            "</style>\n"
        );

        Widget logo = Row.of(
            Icon.of("fas fa-layer-group").modifier(new io.jettra.flux.core.Modifier().style("color: #38bdf8; font-size: 28px; margin-right: 12px;")),
            Column.of(
                Paragraph.of("<h2 style='margin: 0; font-size: 22px; font-weight: 700; color: #f8fafc;'>JettraStoreEngine</h2>"),
                Paragraph.of("<div style='font-size: 12px; color: #94a3b8;'>Multi-Model Database Administration</div>")
            )
        ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center; justify-content: center; margin-bottom: 28px;"));

        String alertHtml = errorMessage.isEmpty() ? "" : "<div style='background: rgba(239,68,68,0.2); border: 1px solid rgba(239,68,68,0.4); color: #fca5a5; padding: 10px 14px; border-radius: 8px; font-size: 13px; margin-bottom: 20px;'><i class='fas fa-exclamation-circle'></i> " + errorMessage + "</div>";

        Widget form = Paragraph.of(
            alertHtml +
            "<form method='POST' action='" + JettraServer.resolvePath("/login") + "'>\n" +
            "  <div class='form-group'>\n" +
            "    <label class='form-label'><i class='fas fa-user'></i> Username</label>\n" +
            "    <input class='form-input' type='text' name='username' value='admin' required autocomplete='username' />\n" +
            "  </div>\n" +
            "  <div class='form-group'>\n" +
            "    <label class='form-label'><i class='fas fa-lock'></i> Password</label>\n" +
            "    <input class='form-input' type='password' name='password' value='admin' required autocomplete='current-password' />\n" +
            "  </div>\n" +
            "  <button class='btn-submit' type='submit'><i class='fas fa-sign-in-alt'></i> Sign In to Store Console</button>\n" +
            "</form>\n" +
            "<div style='margin-top: 24px; padding-top: 16px; border-top: 1px solid rgba(255,255,255,0.08); font-size: 12px; color: #64748b; text-align: center;'>\n" +
            "  Default Credentials: <code style='color:#38bdf8;'>admin / admin</code> or <code style='color:#a855f7;'>super-user / superUserZ</code>\n" +
            "</div>\n"
        );

        Widget card = Div.of(logo, form)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("login-card"));

        Widget container = Div.of(card)
            .modifier(new io.jettra.flux.core.Modifier().cssClass("login-container"));

        return Column.of(
            customCss,
            container
        );
    }
}
