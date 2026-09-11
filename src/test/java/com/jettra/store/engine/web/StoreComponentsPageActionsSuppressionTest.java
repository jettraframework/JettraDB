package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
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
 * are strictly suppressed and not rendered on /components route.
 */
@NotRequiresRunningServer
public class StoreComponentsPageActionsSuppressionTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreComponentsPage componentsPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_components_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();
        componentsPage = new StoreComponentsPage(engine);
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
    @DisplayName("1. RouteVisibilityGuard correctly assigns COMPONENTS route type and suppresses global action buttons")
    void testRouteVisibilityGuardComponentsPolicy() {
        RouteVisibilityGuard.NavigationRouteConfig compConfig = RouteVisibilityGuard.resolveConfig("/components");
        assertEquals(RouteVisibilityGuard.RouteType.COMPONENTS, compConfig.routeType());
        assertFalse(compConfig.showGlobalActionButtons(), "showGlobalActionButtons must be false for /components");
        assertFalse(compConfig.showDatabaseSelector(), "showDatabaseSelector must be false for /components");
        assertFalse(compConfig.showTopNavigationTabs(), "showTopNavigationTabs must be false for /components");
        assertTrue(compConfig.showThemeToggle(), "showThemeToggle must remain true for /components");
        assertTrue(compConfig.topToolbarVisible(), "topToolbarVisible must remain true for /components");
    }

    @JettraTest
    @DisplayName("2. StoreComponentsPage at /components suppresses all 7 top action buttons from rendered DOM")
    void testComponentsRouteSuppressesAllTopActionButtons() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/components");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        componentsPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render any of the 7 top action buttons
        assertFalse(body.contains("+ DB"), "DOM must not contain '+ DB' button on /components");
        assertFalse(body.contains("+ Unit"), "DOM must not contain '+ Unit' button on /components");
        assertFalse(body.contains("Backup Database"), "DOM must not contain 'Backup' button on /components");
        assertFalse(body.contains("Restore Database"), "DOM must not contain 'Restore' button on /components");
        assertFalse(body.contains("Export Data"), "DOM must not contain 'Export' button on /components");
        assertFalse(body.contains("Búsqueda Avanzada"), "DOM must not contain 'Búsqueda Avanzada' button on /components");
        assertFalse(body.contains("Sample DBs"), "DOM must not contain 'Sample DBs' button on /components");

        // Must still render core informative / technical components content
        assertTrue(body.contains("Engine Components & Cluster Internals"), "Page title header must be rendered");
        assertTrue(body.contains("LSM-BTREE CORE"), "LSM-BTREE card badge must be rendered");
        assertTrue(body.contains("RAFT LEADER"), "RAFT LEADER card badge must be rendered");
        assertTrue(body.contains("JAVA 25 JEP 450"), "JAVA 25 card badge must be rendered");
        assertTrue(body.contains("Data Directory Files"), "Data Directory table header must be rendered");
    }

    @JettraTest
    @DisplayName("3. StoreComponentsPage buildUI suppresses action buttons directly in JettraFlux component tree")
    void testComponentsBuildUiSuppressesActionButtons() {
        String html = componentsPage.buildUI(null, Map.of("route", "/components"), "Matrix").render(Themes.Dark());

        assertFalse(html.contains("+ DB"));
        assertFalse(html.contains("+ Unit"));
        assertFalse(html.contains("Backup Database"));
        assertFalse(html.contains("Restore Database"));
        assertFalse(html.contains("Export Data"));
        assertFalse(html.contains("Búsqueda Avanzada"));
        assertFalse(html.contains("Sample DBs"));

        assertTrue(html.contains("Engine Components & Cluster Internals"));
        assertTrue(html.contains("Connected as"));
    }

    @JettraTest
    @DisplayName("4. Fluent override withGlobalActionButtons allows conditional toggle")
    void testFluentOverrideGlobalActionButtons() {
        StoreComponentsPage customPage = new StoreComponentsPage(engine).withGlobalActionButtons(true);
        String html = customPage.buildUI(null, Collections.emptyMap(), "Matrix").render(Themes.Dark());

        assertTrue(html.contains("+ DB"), "When explicitly enabled with fluent API, + DB must be rendered");
        assertTrue(html.contains("Backup"), "When explicitly enabled with fluent API, Backup must be rendered");
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
