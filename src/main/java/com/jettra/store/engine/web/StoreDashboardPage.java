package com.jettra.store.engine.web;

import io.jettra.core.login.NoLoginRequired;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import com.jettra.store.engine.dashboard.DashboardMetricsCollector;
import com.jettra.store.engine.dashboard.MainDashboardView;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;

import java.util.Map;
import java.util.Objects;

/**
 * Main Web Management Dashboard for JettraDB built strictly with JettraFlux components.
 * Delegates data aggregation to DashboardMetricsCollector (Java 25 Virtual Threads)
 * and view composition to MainDashboardView (Modular Panels & Native Charts).
 */
@NoLoginRequired
public class StoreDashboardPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final DashboardMetricsCollector metricsCollector;

    public StoreDashboardPage(JettraStorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "JettraStorageEngine must not be null");
        this.metricsCollector = new DashboardMetricsCollector(engine);
    }

    public DashboardMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    @Override
    protected String getPageTitle() {
        return "Dashboard - JettraStoreEngine";
    }

    @Override
    public Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        ComprehensiveDashboardSnapshot snapshot = metricsCollector.collectSnapshot();
        return MainDashboardView.build(snapshot);
    }
}
