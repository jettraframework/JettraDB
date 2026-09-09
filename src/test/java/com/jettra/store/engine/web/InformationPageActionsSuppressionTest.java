package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
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
import java.util.Comparator;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit and integration tests verifying that global action buttons
 * (+ DB, + UNIT, BACKUP, RESTORE, EXPORT, BÚSQUEDA AVANZADA, SAMPLE DBS)
 * are strictly suppressed and not rendered on /information and /informations routes.
 */
@NotRequiresRunningServer
public class InformationPageActionsSuppressionTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private InformationPage infoPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_info_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();
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

    @JettraTest
    @DisplayName("1. RouteVisibilityGuard correctly assigns INFORMATION route type and suppresses global action buttons")
    void testRouteVisibilityGuardInformationPolicy() {
        RouteVisibilityGuard.NavigationRouteConfig infoConfig = RouteVisibilityGuard.resolveConfig("/information");
        assertEquals(RouteVisibilityGuard.RouteType.INFORMATION, infoConfig.routeType());
        assertFalse(infoConfig.showGlobalActionButtons(), "showGlobalActionButtons must be false for /information");

        RouteVisibilityGuard.NavigationRouteConfig infosConfig = RouteVisibilityGuard.resolveConfig("/informations");
        assertEquals(RouteVisibilityGuard.RouteType.INFORMATION, infosConfig.routeType());
        assertFalse(infosConfig.showGlobalActionButtons(), "showGlobalActionButtons must be false for /informations");

        RouteVisibilityGuard.NavigationRouteConfig enginesConfig = RouteVisibilityGuard.resolveConfig("/engines");
        assertTrue(enginesConfig.showGlobalActionButtons(), "showGlobalActionButtons must remain true for /engines");
    }

    @JettraTest
    @DisplayName("2. InformationPage at /information suppresses all 7 top action buttons from rendered DOM")
    void testInformationRouteSuppressesAllTopActionButtons() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/information");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        infoPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render any of the 7 top action buttons
        assertFalse(body.contains("+ DB"), "DOM must not contain '+ DB' button");
        assertFalse(body.contains("+ Unit"), "DOM must not contain '+ Unit' button");
        assertFalse(body.contains("Backup Database"), "DOM must not contain 'Backup' button");
        assertFalse(body.contains("Restore Database"), "DOM must not contain 'Restore' button");
        assertFalse(body.contains("Export Data"), "DOM must not contain 'Export' button");
        assertFalse(body.contains("Búsqueda Avanzada"), "DOM must not contain 'Búsqueda Avanzada' button");
        assertFalse(body.contains("Sample DBs"), "DOM must not contain 'Sample DBs' button");

        // Must still render core informative page content
        assertTrue(body.contains("Multi-Model Engines Information"), "Page title header must be rendered");
        assertTrue(body.contains("All 9 Supported Multi-Model Engines"), "Matrix table header must be rendered");
        assertTrue(body.contains("DOCUMENT"), "Engine DOCUMENT must be present");
        assertTrue(body.contains("RECORDS"), "Engine RECORDS must be present");
    }

    @JettraTest
    @DisplayName("3. InformationPage at /informations suppresses all 7 top action buttons from rendered DOM")
    void testInformationsRouteSuppressesAllTopActionButtons() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/informations");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        infoPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render any of the 7 top action buttons
        assertFalse(body.contains("+ DB"), "DOM must not contain '+ DB' button on /informations");
        assertFalse(body.contains("+ Unit"), "DOM must not contain '+ Unit' button on /informations");
        assertFalse(body.contains("Backup Database"), "DOM must not contain 'Backup' button on /informations");
        assertFalse(body.contains("Restore Database"), "DOM must not contain 'Restore' button on /informations");
        assertFalse(body.contains("Export Data"), "DOM must not contain 'Export' button on /informations");
        assertFalse(body.contains("Búsqueda Avanzada"), "DOM must not contain 'Búsqueda Avanzada' button on /informations");
        assertFalse(body.contains("Sample DBs"), "DOM must not contain 'Sample DBs' button on /informations");

        // Must still render informative content
        assertTrue(body.contains("Multi-Model Engines Information"), "Header must be rendered on /informations");
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
