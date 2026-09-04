package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import com.jettra.store.engine.web.StoreDashboardPage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.core.Widget;
import io.jettra.flux.download.DownloadResource;
import io.jettra.flux.theme.ColorMode;
import io.jettra.flux.theme.Themes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SnapshotDownloadIntegrationTest {

    private static Path tempBaseDir;
    private static JettraStorageEngine engine;
    private static DashboardMetricsCollector collector;

    @BeforeAll
    static void setup() throws Exception {
        tempBaseDir = Files.createTempDirectory("jettra_snapshot_download_test_");
        engine = new JettraStorageEngine(tempBaseDir.toString());
        engine.start();
        collector = new DashboardMetricsCollector(engine);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (tempBaseDir != null && Files.exists(tempBaseDir)) {
            try (var s = Files.walk(tempBaseDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    @DisplayName("CreateSnapshotCommand executes and encapsulates snapshot in DownloadResource")
    void testCreateSnapshotCommand() throws IOException {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        CreateSnapshotCommand command = new CreateSnapshotCommand(
            engine.getStorageDir(),
            snapshot,
            "admin-user",
            "Matrix",
            ColorMode.DARK
        );

        DownloadResource resource = command.execute();

        assertNotNull(resource);
        assertTrue(resource.fileName().startsWith("snapshot-") && resource.fileName().endsWith(".md"));
        assertEquals("text/markdown; charset=UTF-8", resource.contentType());
        assertTrue(resource.contentLength() > 0);

        try (InputStream in = resource.openStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("# JettraDB System & Storage Dashboard Snapshot"));
            assertTrue(content.contains("admin-user"));
        }
    }

    @Test
    @DisplayName("StoreDashboardPage onPost action=backup responds with JSON containing downloadUrl")
    void testStoreDashboardPageOnPostBackup() throws Exception {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        MockHttpExchange exchange = new MockHttpExchange("POST", "/dashboard?action=backup");

        Map<String, String> params = new HashMap<>();
        params.put("action", "backup");
        params.put("_jettra_theme", "Matrix");
        params.put("_jettra_color_mode", "DARK");

        var onPostMethod = StoreDashboardPage.class.getDeclaredMethod("onPost", HttpExchange.class, Map.class);
        onPostMethod.setAccessible(true);
        boolean handled = (boolean) onPostMethod.invoke(page, exchange, params);

        assertTrue(handled, "onPost must return true when action=backup");
        assertEquals(200, exchange.getResponseCode());
        assertEquals("application/json; charset=UTF-8", exchange.getResponseHeaders().getFirst("Content-Type"));

        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"fileName\":"));
        assertTrue(body.contains("\"downloadUrl\":"));
        assertTrue(body.contains("action=download"));
    }

    @Test
    @DisplayName("StoreDashboardPage onGet action=download streams snapshot markdown with attachment headers")
    void testStoreDashboardPageOnGetDownload() throws Exception {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        CreateSnapshotCommand command = new CreateSnapshotCommand(
            engine.getStorageDir(),
            snapshot,
            "root",
            "Matrix",
            ColorMode.DARK
        );
        DownloadResource resource = command.execute();
        String generatedFile = resource.fileName();

        StoreDashboardPage page = new StoreDashboardPage(engine);
        MockHttpExchange exchange = new MockHttpExchange("GET", "/dashboard?action=download&file=" + generatedFile);

        Map<String, String> params = new HashMap<>();
        params.put("action", "download");
        params.put("file", generatedFile);

        var onGetMethod = StoreDashboardPage.class.getDeclaredMethod("onGet", HttpExchange.class, Map.class);
        onGetMethod.setAccessible(true);
        boolean handled = (boolean) onGetMethod.invoke(page, exchange, params);

        assertTrue(handled, "onGet must handle action=download");
        assertEquals(200, exchange.getResponseCode());
        assertEquals("text/markdown; charset=UTF-8", exchange.getResponseHeaders().getFirst("Content-Type"));
        assertEquals("attachment; filename=\"" + generatedFile + "\"", exchange.getResponseHeaders().getFirst("Content-Disposition"));

        String downloadedBody = exchange.getResponseBodyAsString();
        assertTrue(downloadedBody.contains("# JettraDB System & Storage Dashboard Snapshot"));
    }

    @Test
    @DisplayName("StoreDashboardPage onGet action=download strictly blocks Path Traversal attempts")
    void testStoreDashboardPagePathTraversalBlocked() throws Exception {
        StoreDashboardPage page = new StoreDashboardPage(engine);
        MockHttpExchange exchange = new MockHttpExchange("GET", "/dashboard?action=download&file=../../secret.key");

        Map<String, String> params = new HashMap<>();
        params.put("action", "download");
        params.put("file", "../../secret.key");

        var onGetMethod = StoreDashboardPage.class.getDeclaredMethod("onGet", HttpExchange.class, Map.class);
        onGetMethod.setAccessible(true);
        boolean handled = (boolean) onGetMethod.invoke(page, exchange, params);

        assertTrue(handled);
        assertEquals(403, exchange.getResponseCode(), "Path traversal must be rejected with 403 Forbidden");
        assertTrue(exchange.getResponseBodyAsString().contains("Access denied"));
    }

    @Test
    @DisplayName("MainDashboardView embeds FluxDownload driver and reactive download invocation")
    void testMainDashboardViewDownloadDriver() {
        ComprehensiveDashboardSnapshot snapshot = collector.collectSnapshot();
        Widget mainView = MainDashboardView.build(snapshot);
        String html = mainView.render(Themes.Matrix(ColorMode.DARK));

        assertNotNull(html);
        assertTrue(html.contains("btnCreateBackupSnapshot"), "Backup button ID must exist");
        assertTrue(html.contains("jettraTriggerDownload"), "Must embed JettraFlux client download trigger driver");
        assertTrue(html.contains("downloadUrl"), "Must consume downloadUrl from snapshot response");
        assertTrue(html.contains("Markdown Snapshot created and downloading:"), "Toast feedback must confirm downloading");
    }

    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode = -1;

        public MockHttpExchange(String method, String uri) {
            this.method = method;
            this.uri = URI.create(uri);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }

        public String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }
}
