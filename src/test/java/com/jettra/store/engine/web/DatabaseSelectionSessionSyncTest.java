package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.test.MockHttpExchange;
import com.jettra.store.engine.test.TestDatabaseCleanup;
import com.jettra.store.engine.web.page.StoreDatabasesPage;
import com.jettra.store.engine.web.page.StoreEnginesPage;
import io.jettra.flux.core.FluxContext;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.annotation.NotRequiresRunningServer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Validates the session state transfer and synchronization of selected database between
 * /database (StoreDatabasesPage) and /engines (StoreEnginesPage).
 * Ensures selection is persisted to FluxContext session and cookies, and bound by default to topDatabaseSelect.
 */
@NotRequiresRunningServer
public class DatabaseSelectionSessionSyncTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreDatabasesPage databasesPage;
    private StoreEnginesPage enginesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_sync_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.start();

        // Seed 2 databases
        engine.getStorageCore().put("doc:finance_db:accounts:acc_001",
                "{\"balance\":5000}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        engine.getStorageCore().put("doc:logistics_db:shipments:ship_001",
                "{\"tracking\":\"TRK123\"}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        databasesPage = new StoreDatabasesPage(engine, null);
        enginesPage = new StoreEnginesPage(engine);

        // Ensure clean authenticated FluxContext
        FluxContext ctx = new FluxContext("sync-test-session");
        ctx.set(FluxContext.Scope.SESSION, "username", "admin");
        ctx.set(FluxContext.Scope.SESSION, "role", "ADMIN");
        FluxContext.setCurrent(ctx);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestDatabaseCleanup.cleanUp(engine, tempDir);
        FluxContext.clear();
    }

    @JettraTest
    @DisplayName("1. Session Persistence: Opening database on /database stores selection in FluxContext SESSION")
    void testOpenDatabaseStoresInFluxContextSession() throws Exception {
        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestURI(URI.create("/databases?action=select_db&target_db=finance_db&engine=DOCUMENT"));

        databasesPage.handle(exchange);

        // Verify FluxContext SESSION contains finance_db
        Object currentDb = FluxContext.getCurrent().get(FluxContext.Scope.SESSION, "current_database");
        assertNotNull(currentDb, "current_database must be stored in FluxContext SESSION");
        assertEquals("finance_db", currentDb.toString());

        // Verify Set-Cookie header contains jettra_selected_db
        String setCookie = exchange.getResponseHeaders().getFirst("Set-Cookie");
        assertNotNull(setCookie, "Set-Cookie header must be emitted");
        assertTrue(setCookie.contains("jettra_selected_db=finance_db"), "Cookie must contain finance_db");
    }

    @JettraTest
    @DisplayName("2. State Transfer: /engines resolves database from FluxContext SESSION when not in query params")
    void testEnginesPageResolvesDatabaseFromSession() throws Exception {
        // Set database in session
        FluxContext.getCurrent().set(FluxContext.Scope.SESSION, "current_database", "logistics_db");

        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestURI(URI.create("/engines?engine=DOCUMENT"));

        String resolvedDb = enginesPage.resolveTargetDatabase(exchange, Map.of("engine", "DOCUMENT"), "root");
        assertEquals("logistics_db", resolvedDb, "resolveTargetDatabase must pick up logistics_db from session");

        // Render engines page and check topDatabaseSelect binding
        Widget ui = enginesPage.buildUI(exchange, Map.of("engine", "DOCUMENT"), "dark");
        String html = ui.render(Themes.FlatTheme());

        assertTrue(html.contains("id=\"topDatabaseSelect\""), "HTML must contain topDatabaseSelect");
        assertTrue(html.contains("value=\"logistics_db\" selected") || html.contains("<option value=\"logistics_db\" selected"),
                "topDatabaseSelect must have logistics_db selected by default");
    }

    @JettraTest
    @DisplayName("3. Cookie Fallback: /engines resolves database from jettra_selected_db cookie")
    void testEnginesPageResolvesDatabaseFromCookie() throws Exception {
        // Simulate a new session with cookie
        FluxContext ctx = new FluxContext("cookie-session-test");
        ctx.set(FluxContext.Scope.SESSION, "username", "admin");
        ctx.set(FluxContext.Scope.SESSION, "role", "ADMIN");
        FluxContext.setCurrent(ctx);

        MockHttpExchange exchange = new MockHttpExchange();
        exchange.setRequestURI(URI.create("/engines?engine=DOCUMENT"));
        exchange.getRequestHeaders().set("Cookie", "jettra_selected_db=finance_db; other_cookie=xyz");

        String resolvedDb = enginesPage.resolveTargetDatabase(exchange, new HashMap<>(), "root");
        assertEquals("finance_db", resolvedDb, "resolveTargetDatabase must extract finance_db from Cookie");

        // Verify it was copied to session for future requests
        Object sessionDb = FluxContext.getCurrent().get(FluxContext.Scope.SESSION, "current_database");
        assertEquals("finance_db", sessionDb != null ? sessionDb.toString() : null);
    }

    @JettraTest
    @DisplayName("4. Database Card & Tree Links: /database renders links with target_db and selectDatabaseAndNavigate")
    void testDatabasesPageRendersInteractiveOpenLinks() {
        Widget cardUi = databasesPage.buildContent(null, Map.of("view_mode", "card"), "dark");
        String cardHtml = cardUi.render(Themes.FlatTheme());

        assertTrue(cardHtml.contains("selectDatabaseAndNavigate"),
                "Card view must include selectDatabaseAndNavigate interactive JavaScript function");
        assertTrue(cardHtml.contains("target_db=finance_db") || cardHtml.contains("finance_db"),
                "Card view must have navigation link to finance_db");

        Widget treeUi = databasesPage.buildContent(null, Map.of("view_mode", "tree"), "dark");
        String treeHtml = treeUi.render(Themes.FlatTheme());

        assertTrue(treeHtml.contains("selectDatabaseAndNavigate"),
                "Tree view must include selectDatabaseAndNavigate interactive JavaScript function");
    }

    @JettraTest
    @DisplayName("5. Explicit Override: query parameter target_db takes precedence and updates session")
    void testExplicitQueryParamOverridesSession() {
        FluxContext.getCurrent().set(FluxContext.Scope.SESSION, "current_database", "finance_db");

        MockHttpExchange exchange = new MockHttpExchange();
        String resolvedDb = enginesPage.resolveTargetDatabase(exchange, Map.of("target_db", "logistics_db"), "root");

        assertEquals("logistics_db", resolvedDb, "Explicit query param must take precedence over session");

        // Session must be updated with the new target database
        Object updatedSession = FluxContext.getCurrent().get(FluxContext.Scope.SESSION, "current_database");
        assertEquals("logistics_db", updatedSession != null ? updatedSession.toString() : null);
    }
}
