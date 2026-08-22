package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.model.CredentialFlux;
import io.jettra.flux.pages.FluxBaseHandler;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.core.JettraContext;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication and Login page for JettraStoreEngine Web Console.
 * Built with pure JettraFlux components and standard lifecycle hooks (onPost, onGet, buildUI).
 */
@NoLoginRequired
public class StoreLoginPage extends FluxBaseHandler {

    private final AuthManager authManager;
    private final JCredentialRepository credRepo;

    public StoreLoginPage(AuthManager authManager) {
        this.authManager = authManager;
        this.credRepo = new JCredentialRepositoryImpl();
    }

    @Override
    protected String getTitle() {
        return "Sign In - JettraStoreEngine Console";
    }

    @Override
    protected boolean onGet(HttpExchange exchange, Map<String, String> params) throws IOException {
        if (params != null && ("true".equals(params.get("logout")) || params.containsKey("logout"))) {
            clearSessionCookie(exchange);
            redirect(exchange, "/login");
            return true;
        }
        return false;
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws IOException {
        String username = params != null ? params.get("username") : null;
        String password = params != null ? params.get("password") : null;

        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            redirect(exchange, "/login?error=empty_fields");
            return true;
        }

        username = username.trim();
        password = password.trim();

        boolean authenticated = false;
        String userRole = "ADMIN";

        // 1. Authenticate with AuthManager
        try {
            authManager.login(username, password);
            authenticated = true;
        } catch (Exception ignored) {}

        // 2. Authenticate with JettraSecurityDB repository
        if (!authenticated) {
            try {
                Optional<JCredential> credOpt = credRepo.findByUsernamePassword(username, password);
                if (credOpt.isPresent() && credOpt.get().active()) {
                    authenticated = true;
                    JUser u = credOpt.get().jUser();
                    if (u != null && u.jRoles() != null && !u.jRoles().isEmpty()) {
                        userRole = u.jRoles().iterator().next().name();
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback check for bootstrap admin credentials
        if (!authenticated) {
            if (("admin".equals(username) && "admin".equals(password)) ||
                ("super-user".equals(username) && "superUserZ".equals(password))) {
                authenticated = true;
            }
        }

        if (authenticated) {
            CredentialFlux credentialFlux = new CredentialFlux(username, username.toUpperCase(), userRole, "ENGINE", "");
            if (JettraContext.getCurrent() != null) {
                JettraContext.getCurrent().set(JettraContext.Scope.SESSION, "username", username);
                JettraContext.getCurrent().set(JettraContext.Scope.SESSION, "credentialFlux", credentialFlux);
            }
            setSessionCookie(exchange, username, userRole, "ENGINE");
            redirect(exchange, "/dashboard");
            return true;
        } else {
            redirect(exchange, "/login?error=invalid_credentials");
            return true;
        }
    }

    @Override
    protected Widget buildUI(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String errorMessage = "";
        if (params != null && params.containsKey("error")) {
            String err = params.get("error");
            if ("empty_fields".equalsIgnoreCase(err)) {
                errorMessage = "Por favor ingrese tanto el usuario como la contraseña.";
            } else if ("invalid_credentials".equalsIgnoreCase(err)) {
                errorMessage = "Usuario o contraseña inválidos. Verifique sus credenciales.";
            } else {
                errorMessage = "Error de autenticación. Intente nuevamente.";
            }
        }

        Widget customCss = RawHtml.of(
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
            Icon.of("fas fa-layer-group").modifier(new Modifier().style("color: #38bdf8; font-size: 28px; margin-right: 12px;")),
            Column.of(
                Header.of(2, Text.of("JettraStoreEngine")).modifier(new Modifier().style("margin: 0; font-size: 22px; font-weight: 700; color: #f8fafc;")),
                Div.of(Text.of("Multi-Model Database Administration")).modifier(new Modifier().style("font-size: 12px; color: #94a3b8;"))
            )
        ).modifier(new Modifier().style("align-items: center; justify-content: center; margin-bottom: 28px;"));

        Widget alertWidget = errorMessage.isEmpty() ? Div.of() : Div.of(
            Icon.of("fas fa-exclamation-circle").modifier(new Modifier().style("color:#f87171; font-size:16px; margin-right:8px;")),
            Span.of(errorMessage).modifier(new Modifier().style("font-weight:500;"))
        ).modifier(new Modifier().style("background: rgba(239,68,68,0.15); border: 1px solid rgba(239,68,68,0.35); color: #fca5a5; padding: 12px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 20px; display: flex; align-items: center;"));

        Widget form = Form.of(
            Div.of(
                Label.of(Icon.of("fas fa-user"), Text.of(" Username")).modifier(new Modifier().cssClass("form-label")),
                TextField.of("username", "Username").value("admin").modifier(new Modifier().cssClass("form-input"))
            ).modifier(new Modifier().cssClass("form-group")),
            Div.of(
                Label.of(Icon.of("fas fa-lock"), Text.of(" Password")).modifier(new Modifier().cssClass("form-label")),
                PasswordField.of("password", "Password").value("admin").modifier(new Modifier().cssClass("form-input"))
            ).modifier(new Modifier().cssClass("form-group")),
            Button.of(Icon.of("fas fa-sign-in-alt"), Text.of(" Sign In to Store Console"))
                .attribute("type", "submit")
                .modifier(new Modifier().cssClass("btn-submit"))
        ).action(JettraServer.resolvePath("/login")).method("POST");

        Widget footer = Div.of(
            Text.of("Default Credentials: "),
            RawHtml.of("<code style='color:#38bdf8;'>admin / admin</code> or <code style='color:#a855f7;'>super-user / superUserZ</code>")
        ).modifier(new Modifier().style("margin-top: 24px; padding-top: 16px; border-top: 1px solid rgba(255,255,255,0.08); font-size: 12px; color: #64748b; text-align: center;"));

        Widget card = Div.of(logo, alertWidget, form, footer)
            .modifier(new Modifier().cssClass("login-card"));

        Widget container = Div.of(card)
            .modifier(new Modifier().cssClass("login-container"));

        return Column.of(
            customCss,
            container
        );
    }
}
