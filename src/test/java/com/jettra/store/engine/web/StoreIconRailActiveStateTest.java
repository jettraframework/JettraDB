package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.RouteVisibilityGuard.NavigationRouteConfig;
import com.jettra.store.engine.web.RouteVisibilityGuard.RouteType;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.flux.widgets.IconRail;
import io.jettra.flux.widgets.IconRailItem;
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
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Test Suite validating dynamic selection state management in the left Icon Rail.
 * Verifies that:
 * 1. Visual active state (.rail-item.active and aria-current="page") updates dynamically
 *    when navigating to /databases, /components, /information, /engines, etc.
 * 2. Item 3 (DATABASE) is NO LONGER permanently active on all pages.
 * 3. Exactly one menu item is marked active for each route.
 */
@NotRequiresRunningServer
public class StoreIconRailActiveStateTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private StoreDatabasesPage databasesPage;
    private StoreComponentsPage componentsPage;
    private InformationPage infoPage;
    private StoreEnginesPage enginesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_icon_rail_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        databasesPage = new StoreDatabasesPage(engine, authManager);
        componentsPage = new StoreComponentsPage(engine);
        infoPage = new InformationPage(engine);
        enginesPage = new StoreEnginesPage(engine);
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
    @DisplayName("1. /databases route activates Databases icon and deactivates DATABASE icon")
    void testDatabasesRouteSelectionState() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Databases item MUST be active
        assertTrue(body.contains("href=\"/databases\" title=\"Databases\" aria-label=\"Databases\" aria-current=\"page\""),
            "Databases item must be active with aria-current='page'");

        // DATABASE item MUST NOT be active
        assertFalse(body.contains("title=\"DATABASE\" aria-label=\"DATABASE\" aria-current=\"page\""),
            "DATABASE item (3rd element) must NOT be active when on /databases");

        // Verify only one item has aria-current="page" in icon rail
        int activeCount = countMatches(body, "aria-current=\"page\"");
        assertEquals(1, activeCount, "Exactly one rail item must have aria-current='page'");
    }

    @JettraTest
    @DisplayName("2. /components route activates SERVER (components) icon and deactivates others")
    void testComponentsRouteSelectionState() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/components");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        componentsPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("href=\"/components\" title=\"SERVER\" aria-label=\"SERVER\" aria-current=\"page\""),
            "SERVER (components) item must be active with aria-current='page'");

        assertFalse(body.contains("title=\"DATABASE\" aria-label=\"DATABASE\" aria-current=\"page\""),
            "DATABASE item must NOT be active when on /components");
        assertFalse(body.contains("title=\"Databases\" aria-label=\"Databases\" aria-current=\"page\""),
            "Databases item must NOT be active when on /components");

        int activeCount = countMatches(body, "aria-current=\"page\"");
        assertEquals(1, activeCount, "Exactly one rail item must have aria-current='page'");
    }

    @JettraTest
    @DisplayName("3. /information route activates INFO icon and deactivates others")
    void testInformationRouteSelectionState() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/information");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        infoPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("href=\"/information\" title=\"INFO\" aria-label=\"INFO\" aria-current=\"page\""),
            "INFO item must be active with aria-current='page'");

        assertFalse(body.contains("title=\"DATABASE\" aria-label=\"DATABASE\" aria-current=\"page\""),
            "DATABASE item must NOT be active when on /information");
        assertFalse(body.contains("title=\"Databases\" aria-label=\"Databases\" aria-current=\"page\""),
            "Databases item must NOT be active when on /information");

        int activeCount = countMatches(body, "aria-current=\"page\"");
        assertEquals(1, activeCount, "Exactly one rail item must have aria-current='page'");
    }

    @JettraTest
    @DisplayName("4. /engines route activates DATABASE icon")
    void testEnginesRouteSelectionState() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines?tab=schema&engine=DOCUMENT&target_db=system_db");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("title=\"DATABASE\" aria-label=\"DATABASE\" aria-current=\"page\""),
            "DATABASE item must be active when on /engines schema view");

        assertFalse(body.contains("title=\"Databases\" aria-label=\"Databases\" aria-current=\"page\""),
            "Databases item must NOT be active when on /engines");
        assertFalse(body.contains("title=\"SERVER\" aria-label=\"SERVER\" aria-current=\"page\""),
            "SERVER item must NOT be active when on /engines");

        int activeCount = countMatches(body, "aria-current=\"page\"");
        assertEquals(1, activeCount, "Exactly one rail item must have aria-current='page'");
    }

    @JettraTest
    @DisplayName("5. /engines?tab=settings route activates Settings icon in bottom rail section")
    void testSettingsRouteSelectionState() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines?tab=settings&target_db=system_db");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("title=\"Settings\" aria-label=\"Settings\" aria-current=\"page\""),
            "Settings item must be active when currentTab=settings");

        assertFalse(body.contains("title=\"DATABASE\" aria-label=\"DATABASE\" aria-current=\"page\""),
            "DATABASE item must NOT be active when on Settings");

        int activeCount = countMatches(body, "aria-current=\"page\"");
        assertEquals(1, activeCount, "Exactly one rail item must have aria-current='page'");
    }

    @JettraTest
    @DisplayName("6. State Pattern resolution of active menu keys across RouteType and custom tabs")
    void testStatePatternActiveKeyResolution() {
        NavigationRouteConfig dbConfig = RouteVisibilityGuard.resolveConfig("/databases");
        assertEquals("databases", databasesPage.resolveActiveRailKey(dbConfig, "schema"));

        NavigationRouteConfig compConfig = RouteVisibilityGuard.resolveConfig("/components");
        assertEquals("components", databasesPage.resolveActiveRailKey(compConfig, "schema"));

        NavigationRouteConfig userConfig = RouteVisibilityGuard.resolveConfig("/users");
        assertEquals("users", databasesPage.resolveActiveRailKey(userConfig, "schema"));

        NavigationRouteConfig infoConfig = RouteVisibilityGuard.resolveConfig("/information");
        assertEquals("information", databasesPage.resolveActiveRailKey(infoConfig, "schema"));

        NavigationRouteConfig swaggerConfig = RouteVisibilityGuard.resolveConfig("/swagger-ui");
        assertEquals("swagger", databasesPage.resolveActiveRailKey(swaggerConfig, "schema"));

        NavigationRouteConfig engConfig = RouteVisibilityGuard.resolveConfig("/engines");
        assertEquals("engines", databasesPage.resolveActiveRailKey(engConfig, "schema"));

        // When tab is settings, it must resolve to settings regardless of route
        assertEquals("settings", databasesPage.resolveActiveRailKey(engConfig, "settings"));
    }

    private int countMatches(String text, String target) {
        if (text == null || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
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
