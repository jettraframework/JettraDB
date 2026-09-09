package com.jettra.store.engine.server;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.test.annotation.*;
import static io.jettra.test.core.JettraAssert.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class DatabaseRestControllerTest {

    private Path tempDir;

    private JettraStorageEngine engine;
    private AuthManager authManager;
    private DatabaseRestController controller;
    private String validToken;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jettra_db_rest_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        authManager = new AuthManager();
        controller = new DatabaseRestController(engine, authManager);
        validToken = authManager.login("admin", "admin");
    }

    @AfterEach
    void tearDown() throws Exception {
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
    @DisplayName("1. Rejects request without valid bearer token")
    void testUnauthorizedRequest() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/api/databases");
        controller.handle(exchange);
        assertEquals(401, exchange.getResponseCode());
        assertTrue(exchange.getResponseBodyAsString().contains("Unauthorized"));
    }

    @JettraTest
    @DisplayName("2. List databases includes system_db")
    void testListDatabases() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/api/databases");
        exchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        controller.handle(exchange);
        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("system_db"));
    }

    @JettraTest
    @DisplayName("3. Create database via POST /api/databases with body")
    void testCreateDatabase() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("POST", "/api/databases");
        exchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        exchange.setRequestBody("{\"name\":\"JMeterDB\"}");
        controller.handle(exchange);
        assertEquals(201, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();
        assertTrue(body.contains("CREATED"));
        assertTrue(body.contains("JMeterDB"));

        // Verify it exists in listing
        MockHttpExchange listExchange = new MockHttpExchange("GET", "/api/databases");
        listExchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        controller.handle(listExchange);
        assertTrue(listExchange.getResponseBodyAsString().contains("JMeterDB"));
    }

    @JettraTest
    @DisplayName("4. Protect system_db from deletion")
    void testProtectSystemDb() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange("DELETE", "/api/databases/system_db");
        exchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        controller.handle(exchange);
        assertEquals(400, exchange.getResponseCode());
        assertTrue(exchange.getResponseBodyAsString().contains("protected"));
    }

    @JettraTest
    @DisplayName("5. Delete created database drops partition and purges keys")
    void testDeleteDatabase() throws IOException {
        // First create
        MockHttpExchange createExchange = new MockHttpExchange("POST", "/api/databases");
        createExchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        createExchange.setRequestBody("{\"name\":\"JMeterDB\"}");
        controller.handle(createExchange);
        assertEquals(201, createExchange.getResponseCode());

        // Put some data in it
        engine.getStorageCore().put("kv:JMeterDB:testKey", "testVal".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        assertNotNull(engine.getStorageCore().get("kv:JMeterDB:testKey"));

        // Delete it
        MockHttpExchange deleteExchange = new MockHttpExchange("DELETE", "/api/databases/JMeterDB");
        deleteExchange.getRequestHeaders().set("Authorization", "Bearer " + validToken);
        controller.handle(deleteExchange);
        assertEquals(200, deleteExchange.getResponseCode());
        assertTrue(deleteExchange.getResponseBodyAsString().contains("DELETED"));

        // Verify key is gone
        assertNull(engine.getStorageCore().get("kv:JMeterDB:testKey"));
    }

    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = -1;

        public MockHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create("http://localhost:8086" + path);
        }

        public void setRequestBody(String body) {
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        public String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
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
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
