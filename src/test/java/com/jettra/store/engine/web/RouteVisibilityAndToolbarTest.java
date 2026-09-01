package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.RouteVisibilityGuard.NavigationRouteConfig;
import com.jettra.store.engine.web.RouteVisibilityGuard.RouteType;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Suite validating Conditional Navigation Bar & Toolbar Rendering.
 * Verifies that the global dashboard route (/dashboard) hides operational toolbars,
 * structural tabs, and the database selectOne dropdown, while secondary management routes
 * retain full controls.
 */
public class RouteVisibilityAndToolbarTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreDashboardPage dashboardPage;
    private StoreEnginesPage enginesPage;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_route_visibility_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        dashboardPage = new StoreDashboardPage(engine);
        enginesPage = new StoreEnginesPage(engine);
    }

    @AfterEach
    void tearDown() throws IOException {
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

    @Test
    @DisplayName("Test 1: RouteVisibilityGuard Pattern Matching Resolution")
    void testRouteVisibilityGuardResolution() {
        // 1. Dashboard routes -> Hidden toolbars, tabs, and database select
        NavigationRouteConfig dash1 = RouteVisibilityGuard.resolveConfig("/dashboard");
        assertEquals(RouteType.DASHBOARD, dash1.routeType());
        assertFalse(dash1.showDatabaseSelector(), "Dashboard must hide database selectOne");
        assertFalse(dash1.showTopNavigationTabs(), "Dashboard must hide structural navigation tabs");
        assertFalse(dash1.showGlobalActionButtons(), "Dashboard must hide global action buttons");
        assertTrue(dash1.showThemeToggle(), "Dashboard must keep theme toggle");

        NavigationRouteConfig dashRoot = RouteVisibilityGuard.resolveConfig("/");
        assertEquals(RouteType.DASHBOARD, dashRoot.routeType());
        assertFalse(dashRoot.showDatabaseSelector());

        NavigationRouteConfig dashWui = RouteVisibilityGuard.resolveConfig("/wui");
        assertEquals(RouteType.DASHBOARD, dashWui.routeType());
        assertFalse(dashWui.showDatabaseSelector());

        // 2. Management & secondary routes -> Visible toolbars
        NavigationRouteConfig enginesConfig = RouteVisibilityGuard.resolveConfig("/engines");
        assertEquals(RouteType.MANAGEMENT_EXPLORER, enginesConfig.routeType());
        assertTrue(enginesConfig.showDatabaseSelector(), "Explorer must show database selectOne");
        assertTrue(enginesConfig.showTopNavigationTabs(), "Explorer must show navigation tabs");
        assertTrue(enginesConfig.showGlobalActionButtons(), "Explorer must show global action buttons");

        NavigationRouteConfig dbConfig = RouteVisibilityGuard.resolveConfig("/databases");
        assertEquals(RouteType.DATABASES, dbConfig.routeType());
        assertTrue(dbConfig.showDatabaseSelector());
        assertTrue(dbConfig.showGlobalActionButtons());
    }

    @Test
    @DisplayName("Test 2: Dashboard Route HTML Output Excludes Toolbar, Tabs & Database Select")
    void testDashboardRouteExcludesToolbars() {
        Widget dashboardUi = dashboardPage.buildUI(null, Collections.emptyMap(), "Ast");
        assertNotNull(dashboardUi);

        String renderedHtml = dashboardUi.render(Themes.FlatTheme());
        assertNotNull(renderedHtml);

        // 1. Assert Absence of Database Select Dropdown Component
        assertFalse(renderedHtml.contains("id='topDatabaseSelect'"), "Dashboard must NOT contain #topDatabaseSelect");
        assertFalse(renderedHtml.contains("id=\"topDatabaseSelect\""), "Dashboard must NOT contain #topDatabaseSelect");

        // 2. Assert Absence of Top Navigation Tabs Elements
        assertFalse(renderedHtml.contains("class='top-tab"), "Dashboard must NOT contain .top-tab class elements");
        assertFalse(renderedHtml.contains("class=\"top-tab"), "Dashboard must NOT contain .top-tab class elements");
        assertFalse(renderedHtml.contains("tab=buckets"), "Dashboard must NOT contain Buckets tab");
        assertFalse(renderedHtml.contains("tab=dictionary"), "Dashboard must NOT contain Dictionary tab");

        // 3. Assert Absence of Top Action Buttons in TopBar
        assertFalse(renderedHtml.contains("+ DB"), "Dashboard TopBar must NOT contain + DB");
        assertFalse(renderedHtml.contains("+ Unit"), "Dashboard TopBar must NOT contain + Unit");
        assertFalse(renderedHtml.contains("Búsqueda Avanzada"), "Dashboard TopBar must NOT contain Búsqueda Avanzada");
        assertFalse(renderedHtml.contains("Sample DBs"), "Dashboard TopBar must NOT contain Sample DBs");

        // 4. Assert Presence of Dashboard Workspace & Connection Identity
        assertTrue(renderedHtml.contains("Connected as"), "Dashboard must show clean connection identity");
        assertTrue(renderedHtml.contains("Storage Engine Dashboard"), "Dashboard must show main title");
        assertTrue(renderedHtml.contains("TOTAL DATABASES"), "Dashboard must show KPI cards");
    }

    @Test
    @DisplayName("Test 3: Management Route (/engines) Retains Full Toolbar, Tabs & Database Select")
    void testEnginesRouteRetainsToolbars() {
        Map<String, String> params = new HashMap<>();
        params.put("route", "/engines");
        params.put("engine", "DOCUMENT");
        params.put("target_db", "customers_db");

        Widget enginesUi = enginesPage.buildUI(null, params, "Ast");
        assertNotNull(enginesUi);

        String renderedHtml = enginesUi.render(Themes.FlatTheme());
        assertNotNull(renderedHtml);

        // 1. Assert Presence of Database Select Dropdown
        assertTrue(renderedHtml.contains("id='topDatabaseSelect'") || renderedHtml.contains("id=\"topDatabaseSelect\""),
            "Engines page must contain #topDatabaseSelect");

        // 2. Assert Presence of Top Navigation Tabs
        assertTrue(renderedHtml.contains("top-tab"), "Engines page must contain top-tabs");
        assertTrue(renderedHtml.contains("Schema"), "Engines page must contain Schema tab");
        assertTrue(renderedHtml.contains("Buckets"), "Engines page must contain Buckets tab");
        assertTrue(renderedHtml.contains("Indexes"), "Engines page must contain Indexes tab");

        // 3. Assert Presence of Action Buttons
        assertTrue(renderedHtml.contains("+ DB"), "Engines page must contain + DB action button");
        assertTrue(renderedHtml.contains("+ Unit"), "Engines page must contain + Unit action button");
        assertTrue(renderedHtml.contains("Backup"), "Engines page must contain Backup action button");
        assertTrue(renderedHtml.contains("Export"), "Engines page must contain Export action button");
    }
}
