package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.web.page.StoreDatabasesPage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
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
import java.util.*;

import static io.jettra.test.core.JettraAssert.*;

@NotRequiresRunningServer
public class StoreDatabasesRenameWorkflowIntegrationTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_rename_wf_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.start();
        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        authManager = new AuthManager(systemUserRepo);
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        deleteRecursively(tempDir);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    @JettraTest
    @DisplayName("1. StoreDatabasesPage renders JettraRenameDatabaseModal with RENAME DATABASE submit button")
    void testPageRendersRenameDatabaseModal() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("id=\"renameDbModal\""), "Must contain rename modal ID");
        assertTrue(body.contains("RENAME DATABASE"), "Must contain RENAME DATABASE action button text");
        assertTrue(body.contains("name=\"new_db\""), "Must contain new_db input field");
        assertTrue(body.contains("name=\"old_db\""), "Must contain old_db hidden input field");
        assertTrue(body.contains("window.JettraRenameDatabaseModal"), "Must include client-side JettraRenameDatabaseModal controller");
    }

    @JettraTest
    @DisplayName("2. Both /database and /databases routes map cleanly to StoreDatabasesPage")
    void testBothDatabaseRoutesHandled() throws IOException {
        // Test singular /database
        TestHttpExchange exchangeSingular = new TestHttpExchange("GET", "/database");
        exchangeSingular.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        databasesPage.handle(exchangeSingular);
        assertEquals(200, exchangeSingular.getResponseCode());
        assertTrue(exchangeSingular.getResponseBodyAsString().contains("Databases"));

        // Test plural /databases
        TestHttpExchange exchangePlural = new TestHttpExchange("GET", "/databases");
        exchangePlural.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        databasesPage.handle(exchangePlural);
        assertEquals(200, exchangePlural.getResponseCode());
        assertTrue(exchangePlural.getResponseBodyAsString().contains("Databases"));
    }

    @JettraTest
    @DisplayName("3. HTTP POST action=rename_db executes complete cloning, user migration, and drops source database")
    void testRenameDatabasePostActionCompleteWorkflow() throws IOException {
        String oldDb = "production_sales";
        String newDb = "archived_sales";

        // 1. Seed records in oldDb
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:" + oldDb + ":orders:ord_101", "{\"customer\":\"Acme\",\"total\":500}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:" + oldDb + ":orders:ord_102", "{\"customer\":\"Globex\",\"total\":750}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("rec:" + oldDb + ":ledger:led_01", "{\"balance\":1250}".getBytes(StandardCharsets.UTF_8), now);

        // 2. Seed a user assigned to oldDb
        SystemUser salesLead = new SystemUser(
                UUID.randomUUID(), "sales_lead_user", "pass", "lead@company.com", "DB_ADMIN",
                true, Set.of(oldDb), java.time.Instant.now(), java.time.Instant.now()
        );
        systemUserRepo.save(salesLead);

        // 3. Send POST request with action=rename_db
        TestHttpExchange postExchange = new TestHttpExchange("POST", "/databases");
        postExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        postExchange.setRequestBody("action=rename_db&old_db=" + oldDb + "&new_db=" + newDb);

        databasesPage.handle(postExchange);

        assertEquals(200, postExchange.getResponseCode());
        String body = postExchange.getResponseBodyAsString();

        // Must display success feedback
        assertTrue(body.contains("Database '" + oldDb + "' renamed to '" + newDb + "'"), "Body must confirm database renamed");
        assertTrue(body.contains("3 keys migrated"), "Body must indicate keys were cloned/migrated");

        // 4. Verify cloned keys exist in newDb
        byte[] ord101 = engine.getStorageCore().get("doc:" + newDb + ":orders:ord_101");
        assertNotNull(ord101, "Cloned order must exist in newDb");
        assertTrue(new String(ord101, StandardCharsets.UTF_8).contains("Acme"));

        // 5. Verify source keys deleted and database dropped
        assertNull(engine.getStorageCore().get("doc:" + oldDb + ":orders:ord_101"), "Source record must be deleted");
        Set<String> allDbs = engine.getStorageCore().getDatabaseNames();
        assertTrue(allDbs.contains(newDb), "DatabaseNames must contain newDb");
        assertFalse(allDbs.contains(oldDb), "DatabaseNames must NOT contain oldDb");

        // 6. Verify user assigned database updated
        SystemUser updatedUser = systemUserRepo.findByUsername("sales_lead_user").orElseThrow();
        assertTrue(updatedUser.assignedDatabases().contains(newDb), "User must be assigned to newDb");
        assertFalse(updatedUser.assignedDatabases().contains(oldDb), "User must no longer be assigned to oldDb");
    }

    // Lightweight mock HttpExchange for pure JettraTest execution without network overhead
    private static class TestHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private byte[] requestBodyBytes = new byte[0];
        private int responseCode = -1;

        public TestHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create("http://localhost:50050" + path);
        }

        public void setRequestBody(String body) {
            this.requestBodyBytes = body.getBytes(StandardCharsets.UTF_8);
            requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
            requestHeaders.set("Content-Length", String.valueOf(requestBodyBytes.length));
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
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(requestBodyBytes); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 50050); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
