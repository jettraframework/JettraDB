package com.jettra.store.engine.web;

import jcf.annotation.PageWidgetAllow;
import jcf.AppRole;
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
@PageWidgetAllow(role = { jcf.AppRole.ADMIN, jcf.AppRole.MANAGER, jcf.AppRole.USER })
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
    protected boolean onGet(HttpExchange exchange, Map<String, String> params) throws java.io.IOException {
        String action = params != null ? params.get("action") : null;
        if ("download".equalsIgnoreCase(action)) {
            handleDownloadRequest(exchange, params);
            return true;
        }
        return super.onGet(exchange, params);
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
            com.jettra.store.engine.dashboard.CreateSnapshotCommand command =
                new com.jettra.store.engine.dashboard.CreateSnapshotCommand(engine.getStorageDir(), snapshot, user, themeName, mode);
            io.jettra.flux.download.DownloadResource resource = command.execute();

            String fileName = resource.fileName();
            String downloadUrl = io.jettra.server.JettraServer.resolvePath("/dashboard?action=download&file=" + fileName);

            if (params != null && "true".equalsIgnoreCase(params.get("stream"))) {
                io.jettra.flux.download.JettraDownloadHandler.sendDownload(exchange, resource);
                return;
            }

            java.nio.file.Path snapshotPath = SnapshotService.resolveSnapshotDirectory(engine.getStorageDir()).resolve(fileName);
            long size = resource.contentLength() >= 0 ? resource.contentLength()
                : (java.nio.file.Files.exists(snapshotPath) ? java.nio.file.Files.size(snapshotPath) : 0L);

            String json = String.format(
                "{\"success\":true,\"fileName\":\"%s\",\"path\":\"%s\",\"size\":%d,\"downloadUrl\":\"%s\",\"timestamp\":\"%s\"}",
                fileName,
                snapshotPath.toAbsolutePath().toString().replace("\\", "/"),
                size,
                downloadUrl,
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

    private void handleDownloadRequest(HttpExchange exchange, Map<String, String> params) throws java.io.IOException {
        String fileName = params != null ? params.get("file") : null;
        if (fileName == null || fileName.isBlank()) {
            sendError(exchange, 400, "Missing required 'file' parameter");
            return;
        }

        try {
            String safeName = io.jettra.flux.download.DownloadSecurity.sanitizeFileName(fileName);
            java.nio.file.Path snapshotDir = SnapshotService.resolveSnapshotDirectory(engine.getStorageDir());
            java.nio.file.Path targetFile = snapshotDir.resolve(safeName);

            io.jettra.flux.download.DownloadSecurity.validatePathWithinDirectory(snapshotDir, targetFile);

            if (!java.nio.file.Files.exists(targetFile)) {
                sendError(exchange, 404, "Snapshot file not found: " + safeName);
                return;
            }

            io.jettra.flux.download.DownloadResource resource =
                io.jettra.flux.download.DownloadResource.ofPath(targetFile, "text/markdown; charset=UTF-8", false);
            io.jettra.flux.download.JettraDownloadHandler.sendDownload(exchange, resource);
        } catch (SecurityException se) {
            sendError(exchange, 403, "Access denied: " + se.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Download error: " + e.getMessage());
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws java.io.IOException {
        String errJson = String.format("{\"success\":false,\"error\":\"%s\"}", message != null ? message.replace("\"", "\\\"") : "Error");
        byte[] bytes = errJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
