package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.ref.JettraReferenceResolver;
import com.jettra.store.engine.samples.SampleDatasetManager;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * JettraTest Suite to verify that sample databases (ExampleDBReferences, hr_enterprise_db,
 * meteorology_iot_db, ecommerce_olap_db) are never provisioned autonomously or implicitly
 * on startup, catalog inspection, or reference resolution, and that their physical deployment
 * on disk occurs strictly and exclusively upon manual user request.
 */
@NotRequiresRunningServer
public class SampleDatabasesStartupLifecycleTest {

    private static final Set<String> ALLOWED_SAMPLE_DATABASES = Set.of(
        "ExampleDBReferences",
        "hr_enterprise_db",
        "meteorology_iot_db",
        "ecommerce_olap_db"
    );

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SampleDatabaseService sampleService;
    private StoreDatabasesPage databasesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_sample_lifecycle_test_");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        authManager = new AuthManager();
        sampleService = new SampleDatabaseService(engine);
        databasesPage = new StoreDatabasesPage(
            engine,
            authManager,
            new SystemUserRepositoryImpl(tempDir.resolve("system_db")),
            new JUserRepositoryImpl(),
            new JCredentialRepositoryImpl()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        com.jettra.store.engine.test.TestDatabaseCleanup.cleanUp(engine, tempDir, sampleService);
    }

    @JettraTest
    @DisplayName("1. Clean Engine Startup: Storage initializes with zero sample databases on disk or in partitions")
    void testCleanEngineStartupHasNoSampleDatabases() {
        Path databasesDir = tempDir.resolve("databases");
        if (Files.exists(databasesDir)) {
            for (String sampleDb : ALLOWED_SAMPLE_DATABASES) {
                Path samplePath = databasesDir.resolve(sampleDb);
                assertFalse(Files.exists(samplePath), "Sample database directory [" + sampleDb + "] must NOT exist on initial startup");
            }
        }

        Set<String> activeDbs = engine.getStorageCore().getDatabaseNames();
        for (String sampleDb : ALLOWED_SAMPLE_DATABASES) {
            assertFalse(activeDbs.contains(sampleDb), "Sample database [" + sampleDb + "] must NOT be present in active database names");
        }
    }

    @JettraTest
    @DisplayName("2. Passive Catalog Query: Catalog inspection does not trigger physical directory creation on disk")
    void testPassiveCatalogQueryDoesNotCreateDirectoriesOnDisk() {
        List<SampleDatabaseDefinition> catalog = sampleService.getCatalog();
        assertEquals(4, catalog.size(), "Catalog must contain exactly the 4 authorized sample databases");

        for (SampleDatabaseDefinition def : catalog) {
            String dbName = def.databaseName();
            InstallState state = sampleService.getInstallState(dbName);
            int recordCount = sampleService.getInstalledRecordCount(dbName);

            assertEquals(InstallState.NOT_INSTALLED, state, "Sample database [" + dbName + "] must report NOT_INSTALLED");
            assertEquals(0, recordCount, "Sample database [" + dbName + "] must have 0 records initially");

            Path dbPath = tempDir.resolve("databases").resolve(dbName);
            assertFalse(Files.exists(dbPath), "Inspection must NOT create physical folder on disk for [" + dbName + "]");
        }
    }

    @JettraTest
    @DisplayName("3. Passive Reference Resolution: Uninstalled sample database references return NOT_FOUND without auto-loading")
    void testPassiveReferenceResolutionDoesNotAutoCreateDatabases() {
        JettraReferenceResolver resolver = new JettraReferenceResolver(engine, "test-node");

        var resDoc = resolver.resolve("jref://DOCUMENT:ExampleDBReferences/cust_101");
        assertFalse(resDoc.exists(), "Reference to uninstalled ExampleDBReferences must return not found");

        var resRec = resolver.resolve("jref://RECORDS:hr_enterprise_db/emp_201");
        assertFalse(resRec.exists(), "Reference to uninstalled hr_enterprise_db must return not found");

        Path databasesDir = tempDir.resolve("databases");
        assertFalse(Files.exists(databasesDir.resolve("ExampleDBReferences")), "ExampleDBReferences must NOT be auto-loaded on disk");
        assertFalse(Files.exists(databasesDir.resolve("hr_enterprise_db")), "hr_enterprise_db must NOT be auto-loaded on disk");
    }

    @JettraTest
    @DisplayName("4. Manual User Action: Installation creates exclusively the selected dataset and deploys it to disk")
    void testManualUserInstallationCreatesStrictlySelectedDatabase() {
        String targetDb = "hr_enterprise_db";

        var result = sampleService.install(targetDb);
        assertTrue(result.isSuccess(), "Manual installation of hr_enterprise_db must succeed");
        assertTrue(result.getOrNull() > 0, "hr_enterprise_db must have inserted records");

        Path targetPath = tempDir.resolve("databases").resolve(targetDb);
        assertTrue(Files.exists(targetPath), "Physical directory for hr_enterprise_db must now exist on disk");
        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb), "State must be INSTALLED");
        assertTrue(sampleService.getInstalledRecordCount(targetDb) > 0, "Record count must be greater than 0");

        // Assert all other 3 sample databases remain completely untouched
        for (String otherDb : ALLOWED_SAMPLE_DATABASES) {
            if (!otherDb.equals(targetDb)) {
                Path otherPath = tempDir.resolve("databases").resolve(otherDb);
                assertFalse(Files.exists(otherPath), "Other sample database [" + otherDb + "] must NOT exist on disk");
                assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(otherDb), "Other database must remain NOT_INSTALLED");
                assertEquals(0, sampleService.getInstalledRecordCount(otherDb), "Other database must have 0 records");
            }
        }
    }

    @JettraTest
    @DisplayName("5. Manual User Action: Uninstallation purges the physical database directory from disk")
    void testManualUserUninstallationPurgesPhysicalDatabaseFromDisk() {
        String targetDb = "hr_enterprise_db";

        sampleService.install(targetDb);
        Path targetPath = tempDir.resolve("databases").resolve(targetDb);
        assertTrue(Files.exists(targetPath), "Database must exist before uninstallation");

        var uninstallResult = sampleService.uninstall(targetDb);
        assertTrue(uninstallResult.isSuccess(), "Uninstallation must succeed");
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(targetDb), "State must return to NOT_INSTALLED");
        assertFalse(Files.exists(targetPath), "Physical database directory must be removed from disk upon uninstallation");
    }

    @JettraTest
    @DisplayName("6. JettraFlux Web Page POST: Manual action via form triggers isolated installation of single dataset")
    void testWebConsoleFormSubmissionManualActionTriggersInstall() throws IOException {
        String targetDb = "meteorology_iot_db";

        String formData = "action=install_sample_db&target_db=" + targetDb;
        HttpExchange exchange = createMockExchange("POST", "/databases", formData);

        databasesPage.handle(exchange);

        Path targetPath = tempDir.resolve("databases").resolve(targetDb);
        assertTrue(Files.exists(targetPath), "meteorology_iot_db must be deployed to disk after user clicks Install");
        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb));

        // The other databases must remain uninstalled and not present on disk
        for (String otherDb : ALLOWED_SAMPLE_DATABASES) {
            if (!otherDb.equals(targetDb)) {
                Path otherPath = tempDir.resolve("databases").resolve(otherDb);
                assertFalse(Files.exists(otherPath), "Database [" + otherDb + "] must NOT exist on disk");
            }
        }
    }

    private HttpExchange createMockExchange(String method, String path, String body) {
        return new HttpExchange() {
            private final Headers requestHeaders = new Headers();
            private final Headers responseHeaders = new Headers();
            private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            private int responseCode = 200;

            {
                requestHeaders.set("Cookie", "username=admin; role=ADMIN");
                if ("POST".equalsIgnoreCase(method)) {
                    requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
                }
            }

            @Override public Headers getRequestHeaders() { return requestHeaders; }
            @Override public Headers getResponseHeaders() { return responseHeaders; }
            @Override public URI getRequestURI() { return URI.create(path); }
            @Override public String getRequestMethod() { return method; }
            @Override public HttpContext getHttpContext() { return null; }
            @Override public void close() {}
            @Override public InputStream getRequestBody() {
                return new ByteArrayInputStream(body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
            }
            @Override public OutputStream getResponseBody() { return responseBody; }
            @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
            @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
            @Override public int getResponseCode() { return responseCode; }
            @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
            @Override public String getProtocol() { return "HTTP/1.1"; }
            @Override public Object getAttribute(String name) { return null; }
            @Override public void setAttribute(String name, Object value) {}
            @Override public void setStreams(InputStream i, OutputStream o) {}
            @Override public HttpPrincipal getPrincipal() { return new HttpPrincipal("admin", "admin"); }
        };
    }
}
