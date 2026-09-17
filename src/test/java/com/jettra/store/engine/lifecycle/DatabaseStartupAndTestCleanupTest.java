package com.jettra.store.engine.lifecycle;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.server.DatabaseRestController;
import com.jettra.store.engine.test.TestDatabaseCleanup;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.web.StoreDatabasesPage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
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
import java.util.Map;
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the startup and test lifecycle database policy:
 * 1. Upon JettraDB startup, ONLY genuinely created databases and the system database (system_db) exist.
 * 2. When databases are created in tests, they must be purged and dropped upon test teardown.
 * 3. The internal system_db database is permanently protected against accidental drops.
 */
@NotRequiresRunningServer
public class DatabaseStartupAndTestCleanupTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepositoryImpl systemUserRepo;
    private JUserRepositoryImpl userRepo;
    private JCredentialRepositoryImpl credRepo;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_startup_cleanup_test_");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl(tempDir.resolve("system_db"));
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        authManager = new AuthManager(systemUserRepo);
        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
    }

    @JettraTest
    @DisplayName("1. Startup Database Policy: Fresh startup contains exclusively system_db and no sample or phantom databases")
    void testStartupHasOnlySystemDbWhenNoDatabasesCreated() throws Exception {
        Set<String> activeDbs = engine.getStorageCore().getDatabaseNames();
        assertNotNull(activeDbs, "Database names set should not be null");
        assertTrue(activeDbs.contains("system_db"), "system_db must be present on startup");
        assertEquals(1, activeDbs.size(), "On clean startup, exactly one database (system_db) must exist");

        // Verify that sample databases are NOT installed
        assertFalse(activeDbs.contains("ExampleDBReferences"), "ExampleDBReferences must not exist on startup");
        assertFalse(activeDbs.contains("hr_enterprise_db"), "hr_enterprise_db must not exist on startup");
        assertFalse(activeDbs.contains("meteorology_iot_db"), "meteorology_iot_db must not exist on startup");
        assertFalse(activeDbs.contains("ecommerce_olap_db"), "ecommerce_olap_db must not exist on startup");
        assertFalse(activeDbs.contains("default"), "Phantom 'default' database must not exist on startup");
        assertFalse(activeDbs.contains("_system"), "Internal '_system' partition must not be exposed");

        // Verify via DatabaseRestController
        DatabaseRestController restController = new DatabaseRestController(engine, authManager);
        String token = authManager.login("admin", "admin");
        MockHttpExchange getExchange = new MockHttpExchange("GET", "/api/databases");
        getExchange.getRequestHeaders().set("Authorization", "Bearer " + token);
        restController.handle(getExchange);

        assertEquals(200, getExchange.getResponseCode());
        String restResponseBody = getExchange.getResponseBodyString();
        assertTrue(restResponseBody.contains("\"system_db\""), "REST response must list system_db");
        assertFalse(restResponseBody.contains("\"ExampleDBReferences\""), "REST response must not contain sample databases");
        assertFalse(restResponseBody.contains("\"default\""), "REST response must not contain phantom default");
    }

    @JettraTest
    @DisplayName("2. Dynamic Creation: Explicitly created databases appear alongside system_db")
    void testCreatingDatabasesAreRecognizedAlongsideSystemDb() {
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("rec:custom_sales_db:tx_001", "{\"amount\":150.0}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:warehouse_db:stock_001", "{\"sku\":\"W-99\"}".getBytes(StandardCharsets.UTF_8), now);

        Set<String> activeDbs = engine.getStorageCore().getDatabaseNames();
        assertTrue(activeDbs.contains("system_db"), "system_db must remain present");
        assertTrue(activeDbs.contains("custom_sales_db"), "custom_sales_db must be recognized");
        assertTrue(activeDbs.contains("warehouse_db"), "warehouse_db must be recognized");
        assertEquals(3, activeDbs.size(), "Must contain exactly system_db and the two created databases");
    }

    @JettraTest
    @DisplayName("3. Test Cleanup Teardown: All test-created databases are deleted on dropAllDatabases")
    void testTestCleanupPurgesAllCreatedDatabasesLeavingOnlySystemDb() {
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("rec:temp_test_db_1:rec_1", "{\"key\":\"val1\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("doc:temp_test_db_2:doc_1", "{\"key\":\"val2\"}".getBytes(StandardCharsets.UTF_8), now);

        // Verify databases exist
        assertTrue(engine.getStorageCore().getDatabaseNames().contains("temp_test_db_1"));
        assertTrue(engine.getStorageCore().getDatabaseNames().contains("temp_test_db_2"));

        // Drop all databases as performed during test teardowns
        engine.dropAllDatabases();

        Set<String> dbsAfterCleanup = engine.getStorageCore().getDatabaseNames();
        assertFalse(dbsAfterCleanup.contains("temp_test_db_1"), "temp_test_db_1 must be deleted");
        assertFalse(dbsAfterCleanup.contains("temp_test_db_2"), "temp_test_db_2 must be deleted");
        assertTrue(dbsAfterCleanup.contains("system_db"), "system_db must be preserved");
        assertEquals(1, dbsAfterCleanup.size(), "Only system_db should remain after dropping all databases");

        // Verify physical directories were removed from disk
        Path dbRootDir = tempDir.resolve("databases");
        if (Files.exists(dbRootDir)) {
            assertFalse(Files.exists(dbRootDir.resolve("temp_test_db_1")), "temp_test_db_1 disk directory must be deleted");
            assertFalse(Files.exists(dbRootDir.resolve("temp_test_db_2")), "temp_test_db_2 disk directory must be deleted");
        }
    }

    @JettraTest
    @DisplayName("4. System Database Protection: dropDatabase('system_db') is ignored and protected")
    void testProtectedSystemDbCannotBeDropped() {
        engine.getStorageCore().dropDatabase("system_db");
        Set<String> activeDbs = engine.getStorageCore().getDatabaseNames();
        assertTrue(activeDbs.contains("system_db"), "system_db must not be dropped");
    }

    private static class MockHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers responseHeaders = new Headers();
        private final Headers requestHeaders = new Headers();
        private int responseCode = -1;

        MockHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        String getResponseBodyString() {
            return responseBody.toString(StandardCharsets.UTF_8);
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
        @Override public HttpPrincipal getPrincipal() { return new HttpPrincipal("admin", "admin-realm"); }
    }
}
