package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.security.DatabaseSecurityFilter;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.flux.security.SecurityContextHolder;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;
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
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Integration Test Suite validating database filtering on /databases and /engines
 * based on authenticated user privileges using JettraFlux components.
 */
@NotRequiresRunningServer
public class StoreDatabasePrivilegeFilteringTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private StoreDatabasesPage databasesPage;
    private StoreEnginesPage enginesPage;

    private static final String SCOPED_USER = "operator_sec_test";
    private static final String ALLOWED_DB = "production_db";
    private static final String FORBIDDEN_DB = "classified_vault_db";

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_privilege_filter_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo);
        enginesPage = new StoreEnginesPage(engine, new DatabaseSecurityFilter(systemUserRepo, new JUserRepositoryImpl()));

        // Create physical database folders on disk
        Path dbRoot = tempDir.resolve("databases");
        Files.createDirectories(dbRoot.resolve(ALLOWED_DB));
        Files.createDirectories(dbRoot.resolve(FORBIDDEN_DB));

        // Seed some sample data into both databases
        engine.getStorageCore().put("doc:" + ALLOWED_DB + ":items:1", "{\"item\":\"prod\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:" + FORBIDDEN_DB + ":secrets:1", "{\"secret\":\"top\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // Provision scoped user assigned ONLY to ALLOWED_DB
        SystemUser user = SystemUser.create(SCOPED_USER, "hash123", SCOPED_USER + "@jettra.io", "USER", Set.of(ALLOWED_DB));
        systemUserRepo.save(user);
    }

    @AfterEach
    void tearDown() throws IOException {
        SecurityContextHolder.clear();
        com.jettra.store.engine.test.TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. /databases page: Scoped user ONLY sees authorized database in workspace")
    void testDatabasesPageFiltersUnauthorizedDatabases() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=" + SCOPED_USER + "; role=USER");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must display allowed database
        assertTrue(body.contains(ALLOWED_DB), "Allowed database must be visible in the workspace");

        // Must NOT display forbidden database
        assertFalse(body.contains(FORBIDDEN_DB), "Forbidden database must NOT appear in the workspace");
    }

    @JettraTest
    @DisplayName("2. /databases page: Administrator sees all physical databases")
    void testDatabasesPageAdminSeesAllDatabases() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains(ALLOWED_DB), "Admin must see allowed_db");
        assertTrue(body.contains(FORBIDDEN_DB), "Admin must see forbidden_db");
        assertTrue(body.contains("system_db"), "Admin must see system_db");
    }

    @JettraTest
    @DisplayName("3. /engines topDatabaseSelect: Scoped user ONLY sees authorized database in selector")
    void testEnginesTopSelectorFiltersUnauthorizedDatabases() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines");
        exchange.getRequestHeaders().set("Cookie", "username=" + SCOPED_USER + "; role=USER");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must contain topDatabaseSelect rendered via JettraFluxSelect
        assertTrue(body.contains("id=\"topDatabaseSelect\""), "Must contain select component with id topDatabaseSelect");
        assertTrue(body.contains("jettra-flux-select"), "Must use JettraFlux native select styling");

        // Allowed database must be an option
        assertTrue(body.contains("value=\"" + ALLOWED_DB + "\""), "Allowed database option must be present");

        // Forbidden database must NOT be an option
        assertFalse(body.contains("value=\"" + FORBIDDEN_DB + "\""), "Forbidden database option must NOT be present");
        assertFalse(body.contains("value=\"system_db\""), "system_db must NOT be present for scoped user");

        // Badge count must match authorized databases count (1)
        assertTrue(body.contains("(1)"), "Selector must display count (1) for single authorized database");
    }

    @JettraTest
    @DisplayName("4. /engines topDatabaseSelect: Admin user sees all physical databases in selector")
    void testEnginesTopSelectorAdminSeesAllDatabases() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        assertTrue(body.contains("id=\"topDatabaseSelect\""), "Must contain topDatabaseSelect");
        assertTrue(body.contains("value=\"" + ALLOWED_DB + "\""), "Admin must see allowed_db option");
        assertTrue(body.contains("value=\"" + FORBIDDEN_DB + "\""), "Admin must see forbidden_db option");
    }

    @JettraTest
    @DisplayName("5. /engines access control: Direct access to unauthorized target_db is blocked with Access Denied banner")
    void testEnginesDirectAccessToForbiddenDbBlocked() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines?target_db=" + FORBIDDEN_DB);
        exchange.getRequestHeaders().set("Cookie", "username=" + SCOPED_USER + "; role=USER");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must render Access Denied banner
        assertTrue(body.contains("Access Denied"), "Must render Access Denied banner for unauthorized target_db");
        assertTrue(body.contains(FORBIDDEN_DB), "Banner must reference forbidden database name");
        assertTrue(body.contains(SCOPED_USER), "Banner must reference scoped username");
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
