package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit and integration test suite verifying that the database selector component
 * ("Connected as" accompanied by selectOne #topDatabaseSelect) is strictly suppressed
 * and eliminated on the /databases view in JettraDB using JettraFlux and JettraTest.
 */
@NotRequiresRunningServer
public class StoreDatabasesSelectorSuppressionTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_databases_selector_suppression_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();
        authManager = new AuthManager();
        databasesPage = new StoreDatabasesPage(engine, authManager);
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

    @JettraTest
    @DisplayName("1. RouteVisibilityGuard: Policy for /databases strictly sets showDatabaseSelector to false")
    void testRouteVisibilityGuardDatabasesSelectorPolicy() {
        RouteVisibilityGuard.NavigationRouteConfig config = RouteVisibilityGuard.resolveConfig("/databases");
        assertNotNull(config, "NavigationRouteConfig must not be null");
        assertEquals(RouteVisibilityGuard.RouteType.DATABASES, config.routeType());
        assertFalse(config.showDatabaseSelector(), "showDatabaseSelector must be false for /databases");
        assertFalse(config.showGlobalActionButtons(), "showGlobalActionButtons must be false for /databases");
        assertFalse(config.showTopNavigationTabs(), "showTopNavigationTabs must be false for /databases");
        assertTrue(config.showThemeToggle(), "showThemeToggle must remain true for /databases");
        assertTrue(config.topToolbarVisible(), "topToolbarVisible must remain true for /databases");
    }

    @JettraTest
    @DisplayName("2. HTTP GET /databases: Top toolbar does not render #topDatabaseSelect or selectOne element")
    void testDatabasesRouteOmitsTopDatabaseSelectDropdown() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render topDatabaseSelect element
        assertFalse(body.contains("id=\"topDatabaseSelect\""), "Rendered HTML must NOT contain id=\"topDatabaseSelect\"");
        assertFalse(body.contains("id='topDatabaseSelect'"), "Rendered HTML must NOT contain id='topDatabaseSelect'");
        assertFalse(body.contains("onchange=\"location.href='/engines?target_db="), "Rendered HTML must NOT contain topDatabaseSelect onchange handler");

        // Must preserve workspace layout and title
        assertTrue(body.contains("Multi-Model Database Workspace"), "Page title card header must be preserved");
        assertTrue(body.contains("Authorized Active Databases"), "Active databases section must be preserved");
    }

    @JettraTest
    @DisplayName("3. HTTP GET /databases?view=tree: Hierarchical tree view preserves selector suppression")
    void testDatabasesRouteTreeViewPreservesSelectorSuppression() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases?view=tree");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Selector must NOT be rendered even in Tree view
        assertFalse(body.contains("id=\"topDatabaseSelect\""), "Tree view must NOT contain topDatabaseSelect");
        assertFalse(body.contains("id='topDatabaseSelect'"), "Tree view must NOT contain topDatabaseSelect");

        // Tree view structure must be intact
        assertTrue(body.contains("databases-workspace-tree"), "Hierarchical tree must be rendered");
    }

    @JettraTest
    @DisplayName("4. JettraFlux buildUI directly omits database selector in component tree")
    void testBuildUiDirectlyOmitsDatabaseSelector() {
        String html = databasesPage.buildUI(null, Map.of("route", "/databases"), "Matrix").render(Themes.Dark());

        assertFalse(html.contains("topDatabaseSelect"), "Component tree must not contain topDatabaseSelect");
        assertTrue(html.contains("Multi-Model Database Workspace"), "Component tree must render workspace header");
    }

    @JettraTest
    @DisplayName("5. Fluent API withDatabaseSelector allows conditional override on StoreDatabasesPage")
    void testFluentOverrideDatabaseSelector() {
        StoreDatabasesPage pageWithSelector = new StoreDatabasesPage(engine, authManager).withDatabaseSelector(true);
        String htmlWithSelector = pageWithSelector.buildUI(null, Collections.emptyMap(), "Matrix").render(Themes.Dark());
        assertTrue(htmlWithSelector.contains("topDatabaseSelect"), "Explicitly enabled withDatabaseSelector(true) must render topDatabaseSelect");

        StoreDatabasesPage pageWithoutSelector = new StoreDatabasesPage(engine, authManager).withDatabaseSelector(false);
        String htmlWithoutSelector = pageWithoutSelector.buildUI(null, Collections.emptyMap(), "Matrix").render(Themes.Dark());
        assertFalse(htmlWithoutSelector.contains("topDatabaseSelect"), "Default or explicitly disabled must NOT render topDatabaseSelect");
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
