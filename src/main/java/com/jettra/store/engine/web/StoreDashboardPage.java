package com.jettra.store.engine.web;

import io.jettra.core.login.NoLoginRequired;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import com.jettra.store.engine.dashboard.DashboardMetricsCollector;
import com.jettra.store.engine.dashboard.MainDashboardView;
import com.jettra.store.engine.dashboard.SnapshotService;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;

import java.util.Map;
import java.util.Objects;

/**
 * Main Web Management Dashboard for JettraDB built strictly with JettraFlux components.
 * Delegates data aggregation to DashboardMetricsCollector (Java 25 Virtual Threads)
 * and view composition to MainDashboardView (Modular Panels & Native Charts).
 */
@NoLoginRequired
public class StoreDashboardPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final DashboardMetricsCollector metricsCollector;

    public StoreDashboardPage(JettraStorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "JettraStorageEngine must not be null");
        this.metricsCollector = new DashboardMetricsCollector(engine);
    }

    public DashboardMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    @Override
    public String getPageTitle() {
        return "Dashboard - JettraStoreEngine";
    }

    @Override
    protected RouteVisibilityGuard.NavigationRouteConfig getRouteConfig(HttpExchange exchange, Map<String, String> params) {
        return RouteVisibilityGuard.NavigationRouteConfig.dashboardConfig();
    }

    @Override
    public Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        ComprehensiveDashboardSnapshot snapshot = metricsCollector.collectSnapshot();
        return MainDashboardView.build(snapshot);
    }

    @Override
    protected boolean onPost(HttpExchange exchange, Map<String, String> params) throws java.io.IOException {
        String query = exchange.getRequestURI().getQuery();
        String action = params != null ? params.get("action") : null;
        if (action == null && query != null && query.contains("action=backup")) {
            action = "backup";
        }
        if ("backup".equalsIgnoreCase(action) || "snapshot".equalsIgnoreCase(action)) {
            handleSnapshotCreation(exchange, params);
            return true;
        }
        return super.onPost(exchange, params);
    }

    private void handleSnapshotCreation(HttpExchange exchange, Map<String, String> params) throws java.io.IOException {
        ComprehensiveDashboardSnapshot snapshot = metricsCollector.collectSnapshot();
        String user = getLoggedUser(exchange);
        if (user == null || user.isBlank()) {
            user = "root";
        }
        String themeName = params != null && params.containsKey("_jettra_theme") ? params.get("_jettra_theme") : getThemeCookie(exchange);
        io.jettra.flux.theme.ColorMode mode = params != null && params.containsKey("_jettra_color_mode")
            ? io.jettra.flux.theme.ColorMode.fromString(params.get("_jettra_color_mode"), io.jettra.flux.theme.ColorMode.DARK)
            : getColorModeCookie(exchange);

        try {
            java.nio.file.Path path = SnapshotService.createSnapshot(engine.getStorageDir(), snapshot, user, themeName, mode);
            String json = String.format(
                "{\"success\":true,\"fileName\":\"%s\",\"path\":\"%s\",\"size\":%d,\"timestamp\":\"%s\"}",
                path.getFileName().toString(),
                path.toAbsolutePath().toString().replace("\\", "/"),
                java.nio.file.Files.size(path),
                java.time.LocalDateTime.now().toString()
            );
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            String errJson = String.format("{\"success\":false,\"error\":\"%s\"}", e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Internal Error");
            byte[] bytes = errJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, bytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
