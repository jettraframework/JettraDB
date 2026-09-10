package com.jettra.store.engine.web;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.Objects;

/**
 * Route Visibility Guard & Navigation Configuration Policy for JettraDB Web Console.
 * Uses Java 25 pattern matching and immutable records to enforce contextual component visibility
 * across global dashboard vs secondary data management routes.
 */
public final class RouteVisibilityGuard {

    private RouteVisibilityGuard() {}

    /**
     * Enumeration of primary system navigation routes.
     */
    public enum RouteType {
        DASHBOARD,
        MANAGEMENT_EXPLORER,
        DATABASES,
        SECURITY,
        COMPONENTS,
        INFORMATION,
        LOGIN,
        OTHER
    }

    /**
     * Immutable Java 25 Record defining component visibility for a route.
     */
    public record NavigationRouteConfig(
        RouteType routeType,
        String requestPath,
        boolean showDatabaseSelector,
        boolean showTopNavigationTabs,
        boolean showGlobalActionButtons,
        boolean showThemeToggle,
        boolean topToolbarVisible
    ) {
        public NavigationRouteConfig(
            RouteType routeType,
            String requestPath,
            boolean showDatabaseSelector,
            boolean showTopNavigationTabs,
            boolean showGlobalActionButtons,
            boolean showThemeToggle
        ) {
            this(routeType, requestPath, showDatabaseSelector, showTopNavigationTabs, showGlobalActionButtons, showThemeToggle, true);
        }

        public static NavigationRouteConfig dashboardConfig() {
            return new NavigationRouteConfig(RouteType.DASHBOARD, "/dashboard", false, false, false, true, true);
        }

        public static NavigationRouteConfig explorerConfig(String path) {
            return new NavigationRouteConfig(RouteType.MANAGEMENT_EXPLORER, path, true, true, true, true, true);
        }

        public static NavigationRouteConfig databasesConfig(String path) {
            return new NavigationRouteConfig(RouteType.DATABASES, path, true, false, true, true, true);
        }

        public static NavigationRouteConfig securityConfig(String path) {
            return new NavigationRouteConfig(RouteType.SECURITY, path, false, false, false, true, true);
        }

        public static NavigationRouteConfig informationConfig(String path) {
            return new NavigationRouteConfig(RouteType.INFORMATION, path, false, false, false, true, true);
        }

        public static NavigationRouteConfig componentsConfig(String path) {
            return new NavigationRouteConfig(RouteType.COMPONENTS, path, false, false, false, true, true);
        }

        public static NavigationRouteConfig defaultManagementConfig(String path) {
            return new NavigationRouteConfig(RouteType.OTHER, path, true, true, true, true, true);
        }

        public NavigationRouteConfig withoutGlobalActionButtons() {
            return new NavigationRouteConfig(routeType, requestPath, showDatabaseSelector, showTopNavigationTabs, false, showThemeToggle, topToolbarVisible);
        }

        public NavigationRouteConfig withGlobalActionButtons(boolean visible) {
            return new NavigationRouteConfig(routeType, requestPath, showDatabaseSelector, showTopNavigationTabs, visible, showThemeToggle, topToolbarVisible);
        }

        public NavigationRouteConfig withTopToolbarVisible(boolean visible) {
            return new NavigationRouteConfig(routeType, requestPath, showDatabaseSelector, showTopNavigationTabs, showGlobalActionButtons, showThemeToggle, visible);
        }
    }

    /**
     * Resolves the navigation route configuration using Java 25 pattern matching on URI paths.
     */
    public static NavigationRouteConfig resolveConfig(String path) {
        RouteType type = matchRouteType(path);
        return switch (type) {
            case DASHBOARD -> NavigationRouteConfig.dashboardConfig();
            case MANAGEMENT_EXPLORER -> NavigationRouteConfig.explorerConfig(path);
            case DATABASES -> NavigationRouteConfig.databasesConfig(path);
            case SECURITY -> NavigationRouteConfig.securityConfig(path);
            case COMPONENTS -> NavigationRouteConfig.componentsConfig(path);
            case INFORMATION -> NavigationRouteConfig.informationConfig(path);
            case LOGIN -> new NavigationRouteConfig(RouteType.LOGIN, path, false, false, false, false, false);
            default -> NavigationRouteConfig.defaultManagementConfig(path);
        };
    }

    /**
     * Resolves the navigation route configuration from HttpExchange and query parameters.
     */
    public static NavigationRouteConfig resolveConfig(HttpExchange exchange, Map<String, String> params, String defaultTitle) {
        String path = null;
        if (exchange != null && exchange.getRequestURI() != null) {
            path = exchange.getRequestURI().getPath();
        }
        if (path == null && params != null) {
            path = params.get("route");
        }
        if (path == null && defaultTitle != null) {
            String lowerTitle = defaultTitle.toLowerCase();
            if (lowerTitle.contains("dashboard")) {
                return NavigationRouteConfig.dashboardConfig();
            }
            if (lowerTitle.contains("user") || lowerTitle.contains("security")) {
                return NavigationRouteConfig.securityConfig("/users");
            }
            if (lowerTitle.contains("information") || lowerTitle.contains("información")) {
                return NavigationRouteConfig.informationConfig("/information");
            }
            if (lowerTitle.contains("component") || lowerTitle.contains("internal")) {
                return NavigationRouteConfig.componentsConfig("/components");
            }
        }
        return resolveConfig(path != null ? path : "/dashboard");
    }

    /**
     * Matches raw path to RouteType using pattern matching.
     */
    public static RouteType matchRouteType(String path) {
        if (path == null || path.isBlank()) {
            return RouteType.DASHBOARD;
        }
        String clean = path.trim().toLowerCase();
        if (clean.equals("/") || clean.equals("/dashboard") || clean.equals("/wui")) {
            return RouteType.DASHBOARD;
        }
        if (clean.startsWith("/engines")) {
            return RouteType.MANAGEMENT_EXPLORER;
        }
        if (clean.startsWith("/databases")) {
            return RouteType.DATABASES;
        }
        if (clean.startsWith("/users")) {
            return RouteType.SECURITY;
        }
        if (clean.startsWith("/components")) {
            return RouteType.COMPONENTS;
        }
        if (clean.startsWith("/information") || clean.startsWith("/informations")) {
            return RouteType.INFORMATION;
        }
        if (clean.startsWith("/login")) {
            return RouteType.LOGIN;
        }
        return RouteType.OTHER;
    }
}
