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
import java.time.Year;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test Suite validating the global, reusable AppFooter in JettraDB master layout.
 */
public class StoreGlobalFooterTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDashboardPage dashboardPage;
    private StoreEnginesPage enginesPage;
    private StoreUsersPage usersPage;
    private InformationPage infoPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_footer_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();

        authManager = new AuthManager();
        dashboardPage = new StoreDashboardPage(engine);
        enginesPage = new StoreEnginesPage(engine);
        usersPage = new StoreUsersPage(engine, authManager);
        infoPage = new InformationPage(engine);
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
    @DisplayName("1. Dashboard page renders global AppFooter with dynamic year, branding, and links")
    void testDashboardRendersGlobalFooter() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/dashboard");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        dashboardPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Semantic tag and styling class assertions
        assertTrue(body.contains("<footer"), "Dashboard HTML must contain semantic <footer> tag");
        assertTrue(body.contains("</footer>"), "Dashboard HTML must contain closing </footer> tag");
        assertTrue(body.contains("jettra-app-footer"), "Must contain standard jettra-app-footer CSS class");

        // Metadata and dynamic year assertions
        assertTrue(body.contains("JettraDB Studio"), "Footer must render application brand name");
        assertTrue(body.contains(String.valueOf(Year.now().getValue())), "Footer must render current dynamic year");
        assertTrue(body.contains("JettraStack"), "Footer must render copyright holder");
        assertTrue(body.contains("Cluster Online"), "Footer must render cluster status indicator");

        // Sticky flex structural container assertions
        assertTrue(body.contains("jettra-content-wrapper"), "Content must be wrapped in flex container for sticky footer");
        assertTrue(body.contains("jettra-workspace-body"), "Workspace body must contain flex column layout");
    }

    @Test
    @DisplayName("2. Secondary management views (Engines, Users, Info) inherit global AppFooter automatically")
    void testSecondaryViewsInheritGlobalFooter() throws IOException {
        // Test Engines Page
        TestHttpExchange exchangeEngines = new TestHttpExchange("GET", "/engines");
        exchangeEngines.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        enginesPage.handle(exchangeEngines);
        assertEquals(200, exchangeEngines.getResponseCode());
        assertTrue(exchangeEngines.getResponseBodyAsString().contains("<footer"), "Engines view must inherit footer");
        assertTrue(exchangeEngines.getResponseBodyAsString().contains("JettraDB Studio"), "Engines footer must contain app name");

        // Test Users Page
        TestHttpExchange exchangeUsers = new TestHttpExchange("GET", "/users");
        exchangeUsers.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        usersPage.handle(exchangeUsers);
        assertEquals(200, exchangeUsers.getResponseCode());
        assertTrue(exchangeUsers.getResponseBodyAsString().contains("<footer"), "Users view must inherit footer");

        // Test Information Page
        TestHttpExchange exchangeInfo = new TestHttpExchange("GET", "/information");
        exchangeInfo.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        infoPage.handle(exchangeInfo);
        assertEquals(200, exchangeInfo.getResponseCode());
        assertTrue(exchangeInfo.getResponseBodyAsString().contains("<footer"), "Information view must inherit footer");
    }

    @Test
    @DisplayName("3. Footer navigation links contain valid target paths without script leaks")
    void testFooterNavigationLinks() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/dashboard");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        dashboardPage.handle(exchange);
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("Overview"), "Footer must contain Overview link");
        assertTrue(body.contains("Engines"), "Footer must contain Engines link");
        assertTrue(body.contains("Architecture"), "Footer must contain Architecture link");
        assertTrue(body.contains("REST API"), "Footer must contain REST API link");
        assertFalse(body.contains("javascript:"), "Footer must not contain inline javascript pseudoprotocols");
    }

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

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
        public String getResponseBodyAsString() { return responseBody.toString(StandardCharsets.UTF_8); }
    }
}
