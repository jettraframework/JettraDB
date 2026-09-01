package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JettraFlux reactive modal dialog form handling, subtree state preservation,
 * and AJAX non-destructive branch updates.
 */
public class TreeStatePreservationTest {

    private Path tempDir;
    private JettraStorageEngine storageEngine;
    private StoreEnginesPage page;
    private final JettraJson jsonParser = new JettraJson();

    // Java 25 Immutable Records for Subtree Mutations
    public record RecordItem(String id, String engine, String unit, String payload) {}
    public sealed interface HierarchyMutationEvent permits SubtreeInsertEvent, SubtreeEditEvent, SubtreeDeleteEvent {
        String targetNodeId();
        String database();
    }
    public record SubtreeInsertEvent(String targetNodeId, String database, RecordItem newRecord) implements HierarchyMutationEvent {}
    public record SubtreeEditEvent(String targetNodeId, String database, RecordItem updatedRecord) implements HierarchyMutationEvent {}
    public record SubtreeDeleteEvent(String targetNodeId, String database, String recordId) implements HierarchyMutationEvent {}

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_tree_preservation_test");
        storageEngine = new JettraStorageEngine(tempDir.toString());
        storageEngine.registerEngine("DOCUMENT", new DocumentEngine(storageEngine));
        storageEngine.registerEngine("KEYVALUE", new KeyValueEngine(storageEngine));
        storageEngine.registerEngine("RECORDS", new RecordsEngine(storageEngine));
        storageEngine.start();
        page = new StoreEnginesPage(storageEngine);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storageEngine != null) {
            storageEngine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("Test Pattern Matching with Sealed HierarchyMutationEvent Records")
    void testHierarchyMutationEventPatternMatching() {
        HierarchyMutationEvent insertEvent = new SubtreeInsertEvent(
                "unit_subtree_1_1_1",
                "customers_db",
                new RecordItem("cust_001", "DOCUMENT", "customers", "{\"name\":\"Alice\"}")
        );

        String result = switch (insertEvent) {
            case SubtreeInsertEvent ins -> "INSERT:" + ins.database() + ":" + ins.newRecord().id();
            case SubtreeEditEvent edt -> "EDIT:" + edt.database() + ":" + edt.updatedRecord().id();
            case SubtreeDeleteEvent del -> "DELETE:" + del.database() + ":" + del.recordId();
        };

        assertEquals("INSERT:customers_db:cust_001", result);
    }

    @Test
    @DisplayName("Test Virtual Thread Concurrent Subtree Record Insertions")
    void testVirtualThreadConcurrentSubtreeInsertions() throws InterruptedException, ExecutionException {
        int workerCount = 50;
        ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        List<Callable<StoreEnginesPage.InsertResult>> tasks = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            final int idx = i;
            tasks.add(() -> {
                Map<String, String> params = new HashMap<>();
                params.put("action", "insert_object_ajax");
                params.put("is_ajax", "true");
                params.put("engine", "DOCUMENT");
                params.put("target_db", "concurrent_test_db");
                params.put("target_coll", "orders");
                params.put("id_gen_mode", "MANUAL");
                params.put("target_id", "order_vt_" + idx);
                params.put("doc_payload", "{\"orderId\":" + idx + ",\"amount\":" + (idx * 10.5) + "}");

                MockHttpExchange exchange = new MockHttpExchange();
                page.handleAjaxPost(exchange, params);

                String json = exchange.getResponseBodyAsString();
                JsonObject obj = jsonParser.fromJson(json, JsonObject.class);
                assertEquals("SUCCESS", obj.getAsString("status"));
                assertEquals("order_vt_" + idx, obj.getAsString("itemId"));

                return new StoreEnginesPage.InsertResult("concurrent_test_db", "DOCUMENT", "orders", "order_vt_" + idx);
            });
        }

        List<Future<StoreEnginesPage.InsertResult>> futures = vThreadExecutor.invokeAll(tasks);
        assertEquals(workerCount, futures.size());

        for (Future<StoreEnginesPage.InsertResult> f : futures) {
            StoreEnginesPage.InsertResult res = f.get();
            assertNotNull(res);
            assertEquals("concurrent_test_db", res.database());
            assertEquals("DOCUMENT", res.engineName());
            assertEquals("orders", res.targetColl());
        }

        vThreadExecutor.shutdown();
    }

    @Test
    @DisplayName("Test AJAX Post Handler for Document Insert Without Full Page Reload")
    void testAjaxPostInsertDocumentReturnsSuccessJson() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange();
        Map<String, String> params = new HashMap<>();
        params.put("action", "insert_object");
        params.put("is_ajax", "true");
        params.put("engine", "DOCUMENT");
        params.put("target_db", "sales_db");
        params.put("target_coll", "invoices");
        params.put("id_gen_mode", "MANUAL");
        params.put("target_id", "inv_2026_999");
        params.put("doc_payload", "{\"customer\":\"Acme Corp\",\"total\":1500.0}");

        page.handleAjaxPost(exchange, params);

        assertEquals(200, exchange.responseCode);
        assertEquals("application/json; charset=UTF-8", exchange.responseHeaders.getFirst("Content-Type"));

        String body = exchange.getResponseBodyAsString();
        JsonObject json = jsonParser.fromJson(body, JsonObject.class);

        assertEquals("SUCCESS", json.getAsString("status"));
        assertEquals("sales_db", json.getAsString("database"));
        assertEquals("DOCUMENT", json.getAsString("engine"));
        assertEquals("invoices", json.getAsString("collection"));
        assertEquals("inv_2026_999", json.getAsString("itemId"));
        assertTrue(json.getAsString("message").contains("inv_2026_999"));
    }

    @Test
    @DisplayName("Test AJAX Post Handler for Unit/Collection Creation")
    void testAjaxPostCreateUnit() throws IOException {
        MockHttpExchange exchange = new MockHttpExchange();
        Map<String, String> params = new HashMap<>();
        params.put("action", "create_unit");
        params.put("is_ajax", "true");
        params.put("engine_type", "KEYVALUE");
        params.put("target_db", "cache_db");
        params.put("unit_name", "user_sessions");

        page.handleAjaxPost(exchange, params);

        assertEquals(200, exchange.responseCode);
        String body = exchange.getResponseBodyAsString();
        JsonObject json = jsonParser.fromJson(body, JsonObject.class);

        assertEquals("SUCCESS", json.getAsString("status"));
        assertEquals("cache_db", json.getAsString("database"));
        assertEquals("KEYVALUE", json.getAsString("engine"));
        assertEquals("user_sessions", json.getAsString("collection"));
    }

    @Test
    @DisplayName("Test Non-Destructive Subtree JSON Hierarchy Discovery")
    void testHierarchyJsonDiscoveryPreservesUnits() {
        // Insert items across 2 collections
        Map<String, String> p1 = new HashMap<>();
        p1.put("action", "insert_object");
        p1.put("is_ajax", "true");
        p1.put("engine", "DOCUMENT");
        p1.put("target_db", "multi_unit_db");
        p1.put("target_coll", "coll_alpha");
        p1.put("id_gen_mode", "MANUAL");
        p1.put("target_id", "doc_a1");
        p1.put("doc_payload", "{\"val\":\"alpha\"}");

        Map<String, String> p2 = new HashMap<>();
        p2.put("action", "insert_object");
        p2.put("is_ajax", "true");
        p2.put("engine", "DOCUMENT");
        p2.put("target_db", "multi_unit_db");
        p2.put("target_coll", "coll_beta");
        p2.put("id_gen_mode", "MANUAL");
        p2.put("target_id", "doc_b1");
        p2.put("doc_payload", "{\"val\":\"beta\"}");

        assertDoesNotThrow(() -> {
            page.handleAjaxPost(new MockHttpExchange(), p1);
            page.handleAjaxPost(new MockHttpExchange(), p2);
        });

        JsonObject hierarchy = page.buildDatabaseHierarchyJson("multi_unit_db");
        assertTrue(hierarchy.getAsBoolean("hasComponents"));
        assertTrue(hierarchy.getAsInt("totalItems") >= 2);
    }

    // Lightweight MockHttpExchange for unit testing
    private static class MockHttpExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode = -1;

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/engines"); }
        @Override public String getRequestMethod() { return "POST"; }
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
