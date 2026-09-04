package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-End Integration Test Suite validating Declarative Role-Based Access Control (RBAC)
 * with @PageWidgetAllow and JettraFlux security interceptor across JettraDB management views.
 */
public class DeclarativeSecurityAccessControlTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDashboardPage dashboardPage;
    private StoreUsersPage usersPage;
    private StoreLoginPage loginPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_rbac_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();

        authManager = new AuthManager();
        dashboardPage = new StoreDashboardPage(engine);
        usersPage = new StoreUsersPage(engine, authManager);
        loginPage = new StoreLoginPage(authManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityContextHolder.clear();
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("1. Protected page access without session triggers clean HTTP 302 redirect to /login with zero script leaks")
    void testUnauthenticatedAccessToProtectedPageRedirectsToLogin() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/dashboard");

        dashboardPage.handle(exchange);

        assertEquals(302, exchange.getResponseCode(), "Must issue HTTP 302 Found redirect");
        String location = exchange.getResponseHeaders().getFirst("Location");
        assertNotNull(location, "Location header must be present");
        assertTrue(location.endsWith("/login"), "Must redirect cleanly to login route");

        // Zero Script Leak assertion: response body must not contain setTimeout scripts
        String body = exchange.getResponseBodyAsString();
        assertFalse(body.contains("<script>setTimeout"), "Redirect must not inject setTimeout script leaks");
    }

    @Test
    @DisplayName("2. Authenticated user with valid role successfully renders protected page (HTTP 200)")
    void testAuthenticatedAccessWithValidRoleRendersPage() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/dashboard");
        exchange.getRequestHeaders().set("Cookie", "username=developer; role=USER");

        dashboardPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "Authorized user must receive HTTP 200 OK");
        String body = exchange.getResponseBodyAsString();
        assertNotNull(body);
        assertTrue(body.contains("<!DOCTYPE html>"), "Must render full HTML scaffold");
        assertTrue(body.contains("Dashboard - JettraStoreEngine"), "Title must match target view");
    }

    @Test
    @DisplayName("3. Authenticated user without required role (USER accessing ADMIN-only StoreUsersPage) yields HTTP 403 Access Denied")
    void testAuthenticatedAccessWithoutRequiredRoleYields403() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=regularUser; role=USER");

        usersPage.handle(exchange);

        assertEquals(403, exchange.getResponseCode(), "Insufficient privileges must return HTTP 403 Forbidden");
        String body = exchange.getResponseBodyAsString();
        assertNotNull(body);
        assertTrue(body.contains("Acceso Denegado (403)"), "Response should render Access Denied message");
        assertFalse(body.contains("<script>alert("), "Access denied must not leak script alert popups");
    }

    @Test
    @DisplayName("4. Authenticated ADMIN user successfully accesses ADMIN-only StoreUsersPage (HTTP 200)")
    void testAdminAccessToStoreUsersPageSucceeds() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/users");
        exchange.getRequestHeaders().set("Cookie", "username=adminUser; role=ADMIN");

        usersPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "Admin user must be granted HTTP 200 OK on user management page");
        String body = exchange.getResponseBodyAsString();
        assertNotNull(body);
        assertTrue(body.contains("<!DOCTYPE html>"), "Must render full HTML scaffold");
    }

    @Test
    @DisplayName("5. Public page with @NoLoginRequired (StoreLoginPage) is accessible without credentials")
    void testPublicPageAccessibleWithoutLogin() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/login");

        loginPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode(), "Public login page must return HTTP 200 OK without session");
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("Sign In - JettraStoreEngine Console"), "Login UI must render");
    }

    /**
     * In-memory test implementation of HttpExchange.
     */
    private static class TestHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = -1;

        TestHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

        public String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }
}
