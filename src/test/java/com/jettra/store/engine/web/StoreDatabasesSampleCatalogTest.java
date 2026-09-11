package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.samples.SampleDatasetManager;
import com.jettra.store.engine.samples.lifecycle.DatasetInstallInvoker;
import com.jettra.store.engine.samples.lifecycle.InstallSingleDatasetCommand;
import com.jettra.store.engine.samples.lifecycle.InstallState;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseDefinition;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseService;
import com.jettra.store.engine.users.SystemUser;
import com.jettra.store.engine.users.SystemUserRepository;
import com.jettra.store.engine.users.SystemUserRepositoryImpl;
import com.jettra.store.engine.hierarchy.HierarchyResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.json.JsonArray;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
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
 * JettraTest Suite verifying the migration and isolation of Sample Databases
 * Catalog
 * to StoreDatabasesPage (/databases), strict 4-database catalog restriction,
 * and isolated Command-based single-dataset lifecycle execution.
 */
@NotRequiresRunningServer
public class StoreDatabasesSampleCatalogTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private AuthManager authManager;
    private SystemUserRepository systemUserRepo;
    private JUserRepository userRepo;
    private JCredentialRepository credRepo;
    private StoreDatabasesPage databasesPage;
    private StoreEnginesPage enginesPage;
    private SampleDatasetManager datasetManager;
    private SampleDatabaseService sampleService;
    private DatasetInstallInvoker invoker;
    private final JettraJson json = new JettraJson();

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_sample_catalog_test_");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        systemUserRepo = new SystemUserRepositoryImpl();
        userRepo = new JUserRepositoryImpl();
        credRepo = new JCredentialRepositoryImpl();
        authManager = new AuthManager(systemUserRepo);

        // Seed admin user baseline
        if (!systemUserRepo.existsByUsername("admin")) {
            systemUserRepo.save(SystemUser.create(
                    "admin",
                    SystemUserRepositoryImpl.hashPassword("admin123"),
                    "admin@jettra.io",
                    "DB_ADMIN",
                    Set.of("*")));
        }

        databasesPage = new StoreDatabasesPage(engine, authManager, systemUserRepo, userRepo, credRepo);
        enginesPage = new StoreEnginesPage(engine);
        datasetManager = new SampleDatasetManager(engine);
        sampleService = new SampleDatabaseService(engine);
        invoker = new DatasetInstallInvoker(datasetManager);

        io.jettra.flux.security.SecurityContextHolder.setContext(
            io.jettra.flux.security.SecurityContext.authenticated(
                io.jettra.flux.security.SecurityPrincipal.of("admin", "ADMIN", "*")
            )
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @JettraTest
    @DisplayName("1. Catalog is strictly restricted to exactly 4 authorized sample databases")
    void testCatalogContainsStrictlyAuthorizedFourDatabases() {
        List<SampleDatabaseDefinition> catalog = sampleService.getCatalog();
        assertNotNull(catalog);
        assertEquals(4, catalog.size(), "Catalog must contain exactly 4 authorized sample databases");

        List<String> dbNames = catalog.stream().map(SampleDatabaseDefinition::databaseName).toList();
        assertTrue(dbNames.contains("ExampleDBReferences"), "Must include ExampleDBReferences");
        assertTrue(dbNames.contains("hr_enterprise_db"), "Must include hr_enterprise_db");
        assertTrue(dbNames.contains("meteorology_iot_db"), "Must include meteorology_iot_db");
        assertTrue(dbNames.contains("ecommerce_olap_db"), "Must include ecommerce_olap_db");

        // Verify deprecated/removed databases are strictly excluded
        assertFalse(dbNames.contains("social_network_db"), "social_network_db must be excluded");
        assertFalse(dbNames.contains("scrum_board_db"), "scrum_board_db must be excluded");
        assertFalse(dbNames.contains("fleet_telematics_db"), "fleet_telematics_db must be excluded");
        assertFalse(dbNames.contains("supply_chain_db"), "supply_chain_db must be excluded");
        assertFalse(dbNames.contains("fintech_risk_db"), "fintech_risk_db must be excluded");
        assertFalse(dbNames.contains("genomic_embeddings_db"), "genomic_embeddings_db must be excluded");

        // Verify datasetManager whitelist matches exactly
        List<String> availableDatasets = SampleDatasetManager.AVAILABLE_DATASETS.stream()
                .map(SampleDatasetManager.DatasetInfo::databaseName)
                .toList();
        assertEquals(4, availableDatasets.size(), "Available datasets must equal 4");
        assertTrue(availableDatasets.containsAll(dbNames));
    }

    @JettraTest
    @DisplayName("2. StoreDatabasesPage renders + Sample DBs button and both JettraFlux catalog modals")
    void testStoreDatabasesPageRendersSampleCatalogButtonAndModal() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must render the + Sample DBs button in page header
        assertTrue(body.contains("+ Sample DBs"), "Databases page must render '+ Sample DBs' button");
        assertTrue(body.contains("openSampleDatabasesModal()"), "Button must trigger openSampleDatabasesModal()");

        // Must render both dialog modals
        assertTrue(body.contains("id=\"sampleDatabasesModal\""),
                "Databases page must render sampleDatabasesModal Dialog");
        assertTrue(body.contains("id=\"confirmUninstallSampleDbModal\""),
                "Databases page must render confirmUninstallSampleDbModal Dialog");
        assertTrue(
                body.contains("Sample Databases &amp; Datasets Catalog")
                        || body.contains("Sample Databases & Datasets Catalog"),
                "Modal header title must be present");
        assertTrue(body.contains("sampleDbsCatalogContainer"), "Dynamic catalog container must be present");

        // Must render pure JettraFlux components: form, radio buttons, and the Install DataSet action button
        assertTrue(body.contains("id=\"sampleDatabasesForm\""), "Must render sampleDatabasesForm Form");
        assertTrue(body.contains("id=\"btnInstallSampleDataSet\""), "Must render btnInstallSampleDataSet button");
        assertTrue(body.contains("Install DataSet"), "Must render 'Install DataSet' button text");
        assertTrue(body.contains("radio_sample_ExampleDBReferences"), "Must render radio for ExampleDBReferences");
        assertTrue(body.contains("radio_sample_hr_enterprise_db"), "Must render radio for hr_enterprise_db");
        assertTrue(body.contains("radio_sample_meteorology_iot_db"), "Must render radio for meteorology_iot_db");
        assertTrue(body.contains("radio_sample_ecommerce_olap_db"), "Must render radio for ecommerce_olap_db");
    }

    @JettraTest
    @DisplayName("3. StoreEnginesPage does NOT render + Sample DBs button or sample catalog modals")
    void testStoreEnginesPageDoesNotRenderSampleCatalogButtonOrModal() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/engines");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");

        enginesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        String body = exchange.getResponseBodyAsString();

        // Must NOT render "+ Sample DBs" button in engines top toolbar or page
        assertFalse(body.contains("+ Sample DBs"), "Engines page must NOT render '+ Sample DBs' button");
        // Must NOT render sample catalog dialogs in engines page
        assertFalse(body.contains("id=\"sampleDatabasesModal\""), "Engines page must NOT render sampleDatabasesModal");
        assertFalse(body.contains("id=\"confirmUninstallSampleDbModal\""),
                "Engines page must NOT render confirmUninstallSampleDbModal");
    }

    @JettraTest
    @DisplayName("4. InstallSingleDatasetCommand installs exclusively the target database in complete isolation")
    void testInstallSingleDatasetCommandExecutionAndIsolation() {
        String targetDb = "hr_enterprise_db";
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(targetDb));

        // Create and execute command
        InstallSingleDatasetCommand cmd = new InstallSingleDatasetCommand(datasetManager, targetDb);
        assertEquals(targetDb, cmd.databaseName());

        HierarchyResult<Integer> res = invoker.execute(cmd);
        assertTrue(res.isSuccess(), "Execution of InstallSingleDatasetCommand must succeed");
        assertTrue(res.getOrNull() > 0, "Installed records count must be greater than 0");

        // Verify target database is installed
        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb));
        assertTrue(sampleService.getInstalledRecordCount(targetDb) > 0);

        // Verify other authorized databases are NOT installed and have 0 records
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ExampleDBReferences"));
        assertEquals(0, sampleService.getInstalledRecordCount("ExampleDBReferences"));

        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("meteorology_iot_db"));
        assertEquals(0, sampleService.getInstalledRecordCount("meteorology_iot_db"));

        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ecommerce_olap_db"));
        assertEquals(0, sampleService.getInstalledRecordCount("ecommerce_olap_db"));
    }

    @JettraTest
    @DisplayName("5. InstallSingleDatasetCommand strictly rejects unauthorized or obsolete databases")
    void testInstallSingleDatasetCommandRejectsUnauthorizedDatabases() {
        // Attempt with obsolete dataset
        InstallSingleDatasetCommand cmdObsolete = new InstallSingleDatasetCommand(datasetManager, "scrum_board_db");
        HierarchyResult<Integer> resObsolete = cmdObsolete.execute();
        assertFalse(resObsolete.isSuccess(), "Must fail for scrum_board_db as unauthorized");
        assertTrue(resObsolete.errorMessage().contains("Unauthorized or obsolete"));

        InstallSingleDatasetCommand cmdUnknown = new InstallSingleDatasetCommand(datasetManager,
                "malicious_unauthorized_db");
        HierarchyResult<Integer> resUnknown = cmdUnknown.execute();
        assertFalse(resUnknown.isSuccess(), "Must fail for unknown database");

        // Invoker rejection check
        HierarchyResult<Integer> resInvoker = invoker.executeInstall("unauthorized_db");
        assertFalse(resInvoker.isSuccess(), "Invoker must return failure for unauthorized database");
    }

    @JettraTest
    @DisplayName("6. GET /databases?action=list_sample_dbs returns JSON catalog of the 4 authorized databases")
    void testDatabasesAjaxListSampleDatabasesEndpoint() throws IOException {
        TestHttpExchange exchange = new TestHttpExchange("GET", "/databases?action=list_sample_dbs");
        exchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        exchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        exchange.getRequestHeaders().set("Accept", "application/json");

        databasesPage.handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        assertEquals("application/json; charset=UTF-8", exchange.getResponseHeaders().getFirst("Content-Type"));

        String jsonResp = exchange.getResponseBodyAsString();
        JsonObject obj = json.fromJson(jsonResp, JsonObject.class);
        assertEquals("SUCCESS", obj.getAsString("status"));
        assertTrue(obj.has("databases"));

        JsonArray arr = obj.getAsJsonArray("databases");
        assertEquals(4, arr.size(), "AJAX list must contain exactly 4 sample databases");

        for (int i = 0; i < arr.size(); i++) {
            JsonObject dbObj = arr.getAsJsonObject(i);
            String dbName = dbObj.getAsString("databaseName");
            assertTrue(InstallSingleDatasetCommand.ALLOWED_DATABASES.contains(dbName));
            assertNotNull(dbObj.getAsString("installState"));
            assertNotNull(dbObj.getAsString("displayName"));
        }
    }

    @JettraTest
    @DisplayName("7. POST /databases action=install_sample_db_ajax and uninstall_sample_db_ajax lifecycle")
    void testDatabasesAjaxInstallAndUninstallLifecycle() throws IOException {
        String targetDb = "meteorology_iot_db";

        // 1. Install via AJAX POST
        TestHttpExchange installExchange = new TestHttpExchange("POST", "/databases");
        installExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        installExchange.setRequestBody("action=install_sample_db_ajax&target_db=" + targetDb);
        installExchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");

        databasesPage.handle(installExchange);

        assertEquals(200, installExchange.getResponseCode());
        String installJson = installExchange.getResponseBodyAsString();
        JsonObject installObj = json.fromJson(installJson, JsonObject.class);
        assertEquals("SUCCESS", installObj.getAsString("status"));
        assertEquals(targetDb, installObj.getAsString("database"));
        assertTrue(installObj.has("installedRecords"));
        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb));

        // 2. Uninstall via AJAX POST
        TestHttpExchange uninstallExchange = new TestHttpExchange("POST", "/databases");
        uninstallExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        uninstallExchange.setRequestBody("action=uninstall_sample_db_ajax&target_db=" + targetDb);
        uninstallExchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");

        databasesPage.handle(uninstallExchange);

        assertEquals(200, uninstallExchange.getResponseCode());
        String uninstallJson = uninstallExchange.getResponseBodyAsString();
        JsonObject uninstallObj = json.fromJson(uninstallJson, JsonObject.class);
        assertEquals("SUCCESS", uninstallObj.getAsString("status"));
        assertEquals(targetDb, uninstallObj.getAsString("database"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState(targetDb));
    }

    @JettraTest
    @DisplayName("8. Form POST action=install_sample_db with selected target_db installs strictly that single database")
    void testFormPostInstallsStrictlySelectedSampleDatabase() throws IOException {
        String targetDb = "hr_enterprise_db";

        TestHttpExchange formExchange = new TestHttpExchange("POST", "/databases");
        formExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        formExchange.getRequestHeaders().set("Content-Type", "application/x-www-form-urlencoded");
        formExchange.setRequestBody("action=install_sample_db&target_db=" + targetDb);

        databasesPage.handle(formExchange);

        assertEquals(200, formExchange.getResponseCode());
        String body = formExchange.getResponseBodyAsString();
        assertTrue(body.contains("instalada exitosamente"), "Page response must contain success message");
        assertTrue(body.contains(targetDb), "Response must mention target database");

        // Verify target database is installed
        assertEquals(InstallState.INSTALLED, sampleService.getInstallState(targetDb));
        assertTrue(sampleService.getInstalledRecordCount(targetDb) > 0);

        // Verify strictly that the other 3 authorized databases are NOT installed (isolation guarantee)
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ExampleDBReferences"));
        assertEquals(0, sampleService.getInstalledRecordCount("ExampleDBReferences"));

        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("meteorology_iot_db"));
        assertEquals(0, sampleService.getInstalledRecordCount("meteorology_iot_db"));

        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ecommerce_olap_db"));
        assertEquals(0, sampleService.getInstalledRecordCount("ecommerce_olap_db"));
    }

    @JettraTest
    @DisplayName("9. Form POST action=install_sample_db without target_db is rejected without installing any database")
    void testFormPostWithoutTargetDbIsRejectedWithoutMassInstallation() throws IOException {
        TestHttpExchange emptyExchange = new TestHttpExchange("POST", "/databases");
        emptyExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        emptyExchange.getRequestHeaders().set("Content-Type", "application/x-www-form-urlencoded");
        emptyExchange.setRequestBody("action=install_sample_db");

        databasesPage.handle(emptyExchange);

        assertEquals(200, emptyExchange.getResponseCode());
        String body = emptyExchange.getResponseBodyAsString();
        assertTrue(body.contains("Debe seleccionar una base de datos de ejemplo"), "Validation message must be shown");

        // Verify that NO database was installed
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ExampleDBReferences"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("hr_enterprise_db"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("meteorology_iot_db"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ecommerce_olap_db"));
    }

    @JettraTest
    @DisplayName("10. AJAX POST install_sample_db_ajax without target_db returns 400 error without installing any database")
    void testAjaxInstallWithoutTargetDbReturnsError() throws IOException {
        TestHttpExchange emptyAjaxExchange = new TestHttpExchange("POST", "/databases");
        emptyAjaxExchange.getRequestHeaders().set("Cookie", "username=admin; role=ADMIN");
        emptyAjaxExchange.getRequestHeaders().set("X-Requested-With", "XMLHttpRequest");
        emptyAjaxExchange.setRequestBody("action=install_sample_db_ajax");

        databasesPage.handle(emptyAjaxExchange);

        assertEquals(400, emptyAjaxExchange.getResponseCode());
        String resp = emptyAjaxExchange.getResponseBodyAsString();
        assertTrue(resp.contains("Missing target_db parameter"), "Must return error message for missing target_db");

        // Verify that NO database was installed
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ExampleDBReferences"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("hr_enterprise_db"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("meteorology_iot_db"));
        assertEquals(InstallState.NOT_INSTALLED, sampleService.getInstallState("ecommerce_olap_db"));
    }

    private static class TestHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private int responseCode = -1;

        TestHttpExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        void setRequestBody(String body) {
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            this.requestHeaders.set("Content-Type", "application/x-www-form-urlencoded");
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.responseCode = rCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return new HttpPrincipal("admin", "defaultRealm");
        }

        String getResponseBodyAsString() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }
    }
}
