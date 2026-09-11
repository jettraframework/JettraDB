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

import java.io.*;
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
 * Unit and integration tests verifying that global action buttons
 * (+ DB, + UNIT, BACKUP, RESTORE, EXPORT, BÚSQUEDA AVANZADA, SAMPLE DBS)
 * are strictly suppressed and not rendered on /databases route.
 */
@NotRequiresRunningServer
public class StoreDatabasesPageActionsSuppressionTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_databases_suppression_test");
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
    @DisplayName("1. RouteVisibilityGuard correctly assigns DATABASES route type and suppresses global action buttons & database selector")
    void testRouteVisibilityGuardDatabasesPolicy() {
        RouteVisibilityGuard.NavigationRouteConfig dbConfig = RouteVisibilityGuard.resolveConfig("/databases");
        assertEquals(RouteVisibilityGuard.RouteType.DATABASES, dbConfig.routeType());
        assertFalse(dbConfig.showGlobalActionButtons(), "showGlobalActionButtons must be false for /databases");
        assertFalse(dbConfig.showDatabaseSelector(), "showDatabaseSelector must be false for /databases");
        assertFalse(dbConfig.showTopNavigationTabs(), "showTopNavigationTabs must be false for /databases");
        assertTrue(dbConfig.showThemeToggle(), "showThemeToggle must remain true for /databases");
        assertTrue(dbConfig.topToolbarVisible(), "topToolbarVisible must remain true for /databases");
    }

    @JettraTest
    @DisplayName("2. StoreDatabasesPage at /databases suppresses all 7 top action buttons and database selectOne from rendered DOM")
    void testDatabasesRouteSuppressesAllTopActionButtons() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render top toolbar action buttons
        assertFalse(body.contains("+ DB"), "DOM must not contain '+ DB' toolbar button on /databases");
        assertFalse(body.contains("+ Unit"), "DOM must not contain '+ Unit' toolbar button on /databases");
        assertFalse(body.contains("Backup Database"), "DOM must not contain 'Backup' toolbar button on /databases");
        assertFalse(body.contains("Restore Database"), "DOM must not contain 'Restore' toolbar button on /databases");
        assertFalse(body.contains("Export Data"), "DOM must not contain 'Export' toolbar button on /databases");
        assertFalse(body.contains("Búsqueda Avanzada"), "DOM must not contain 'Búsqueda Avanzada' toolbar button on /databases");
        assertTrue(body.contains("+ Sample DBs"), "DOM must contain relocated '+ Sample DBs' button on /databases");

        // Must NOT render database selector selectOne component
        assertFalse(body.contains("topDatabaseSelect"), "DOM must not contain database selectOne on /databases");
        assertFalse(body.contains("id=\"topDatabaseSelect\""), "DOM must not contain id=\"topDatabaseSelect\" on /databases");

        // Must still render core informative / database management content
        assertTrue(body.contains("Multi-Model Database Workspace"), "Page title card header must be rendered");
        assertTrue(body.contains("Authorized Active Databases"), "Active databases section must be rendered");
    }

    @JettraTest
    @DisplayName("3. StoreDatabasesPage buildUI suppresses action buttons and database selectOne directly in JettraFlux component tree")
    void testDatabasesBuildUiSuppressesActionButtons() {
        String html = databasesPage.buildUI(null, Map.of("route", "/databases"), "Matrix").render(Themes.Dark());

        assertFalse(html.contains("+ DB"));
        assertFalse(html.contains("+ Unit"));
        assertFalse(html.contains("Backup Database"));
        assertFalse(html.contains("Restore Database"));
        assertFalse(html.contains("Export Data"));
        assertFalse(html.contains("Búsqueda Avanzada"));
        assertTrue(html.contains("+ Sample DBs"), "Component tree must render relocated '+ Sample DBs' button on /databases");
        assertFalse(html.contains("topDatabaseSelect"), "Component tree must not render topDatabaseSelect on /databases");

        assertTrue(html.contains("Multi-Model Database Workspace"));
        assertTrue(html.contains("Connected as"));
    }

    @JettraTest
    @DisplayName("4. Fluent override withGlobalActionButtons allows conditional toggle")
    void testFluentOverrideGlobalActionButtons() {
        StoreDatabasesPage customPage = new StoreDatabasesPage(engine, authManager).withGlobalActionButtons(true);
        String html = customPage.buildUI(null, Collections.emptyMap(), "Matrix").render(Themes.Dark());

        assertTrue(html.contains("+ DB"), "When explicitly enabled with fluent API, + DB must be rendered");
        assertTrue(html.contains("Sample DBs"), "When explicitly enabled with fluent API, Sample DBs must be rendered");
    }

    @JettraTest
    @DisplayName("5. Fluent override withDatabaseSelector allows conditional toggle of database selectOne on /databases")
    void testFluentOverrideDatabaseSelector() {
        StoreDatabasesPage customPage = new StoreDatabasesPage(engine, authManager).withDatabaseSelector(true);
        String html = customPage.buildUI(null, Collections.emptyMap(), "Matrix").render(Themes.Dark());

        assertTrue(html.contains("topDatabaseSelect"), "When explicitly enabled with fluent API, topDatabaseSelect must be rendered");
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
