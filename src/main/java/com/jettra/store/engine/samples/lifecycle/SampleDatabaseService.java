package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyResult;
import com.jettra.store.engine.samples.SampleDatabaseNamingPolicy;
import com.jettra.store.engine.samples.SampleDatasetManager;

import java.util.*;
import java.util.concurrent.*;

/**
 * Service managing the on-demand lifecycle (installation, uninstallation, status tracking)
 * of sample databases in JettraStoreEngine using Java 25 Virtual Threads.
 */
public class SampleDatabaseService {

    private final JettraStorageEngine engine;
    private final SampleDatasetManager datasetManager;
    private final Map<String, InstallState> transientStates = new ConcurrentHashMap<>();
    private final ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public static final List<SampleDatabaseDefinition> CATALOG = List.of(
        new SampleDatabaseDefinition(
            SampleDatabaseNamingPolicy.EXAMPLE_DB_REFERENCES,
            "MULTI-MODEL",
            SampleDatabaseNamingPolicy.EXAMPLE_DB_REFERENCES,
            "Cross-Engine & Multi-Cluster References Suite",
            "Demonstrates direct O(1) object references (jref://) with primary storage addresses, multi-cluster node pointers, and dynamic reference resolution across Document, Records, Geo, Vector, Object, KeyValue, TimeSeries, Graph, and Column engines.",
            120,
            "fas fa-link",
            List.of("References", "Jref", "Multi-Cluster", "Composite")
        ),
        new SampleDatabaseDefinition(
            SampleDatabaseNamingPolicy.EXAMPLE_HR_ENTERPRISE_DB,
            "RECORDS",
            SampleDatabaseNamingPolicy.EXAMPLE_HR_ENTERPRISE_DB,
            "Java 25 Enterprise HR & Payroll",
            "Immutable Record instances for Employees, Departments, Contracts, and Salary components with cross-engine biometrics and GIS links.",
            1000,
            "fas fa-id-card-alt",
            List.of("Records", "Schema", "HR", "Immutable")
        ),
        new SampleDatabaseDefinition(
            SampleDatabaseNamingPolicy.EXAMPLE_METEOROLOGY_IOT_DB,
            "TIMESERIES",
            SampleDatabaseNamingPolicy.EXAMPLE_METEOROLOGY_IOT_DB,
            "IoT Meteorological Weather Stations",
            "High-frequency sensor telemetry (temperature, humidity, atmospheric pressure, solar irradiance, precipitation) across time intervals.",
            2500,
            "fas fa-cloud-sun-rain",
            List.of("IoT", "Telemetry", "TimeSeries", "Sensors")
        ),
        new SampleDatabaseDefinition(
            SampleDatabaseNamingPolicy.EXAMPLE_ECOMMERCE_OLAP_DB,
            "COLUMN",
            SampleDatabaseNamingPolicy.EXAMPLE_ECOMMERCE_OLAP_DB,
            "E-Commerce OLAP Analytics",
            "Wide-column analytical fact tables, quarterly revenue by region, customer cohort aggregations, and performance metrics.",
            1000,
            "fas fa-table",
            List.of("Column", "OLAP", "Analytics", "Wide-Table")
        ),
        new SampleDatabaseDefinition(
            SampleDatabaseNamingPolicy.EXAMPLE_FACTURA,
            "MULTI-MODEL",
            SampleDatabaseNamingPolicy.EXAMPLE_FACTURA,
            "Enterprise Invoicing & Billing (Multi-Engine)",
            "High-volume enterprise invoicing and billing suite with 1,000,000 customers (Document), 500,000 products (Records), inventories (TimeSeries), invoices & details (Column), branches (Geospatial), categories (Graph), and salespersons (KeyValue).",
            1566175,
            "fas fa-file-invoice-dollar",
            List.of("Billing", "Invoicing", "Multi-Engine", "Large-Scale", "Stress-Test")
        )
    );

    private final DatasetInstallInvoker installInvoker;

    public SampleDatabaseService(JettraStorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "StorageEngine must not be null");
        this.datasetManager = new SampleDatasetManager(engine);
        this.installInvoker = new DatasetInstallInvoker(this.datasetManager);
    }

    public DatasetInstallInvoker getInstallInvoker() {
        return installInvoker;
    }

    public SampleDatasetManager getDatasetManager() {
        return datasetManager;
    }

    public List<SampleDatabaseDefinition> getCatalog() {
        return CATALOG;
    }

    public InstallState getInstallState(String dbName) {
        if (dbName == null || dbName.isBlank()) return InstallState.NOT_INSTALLED;
        String cleanDb = SampleDatabaseNamingPolicy.canonicalize(dbName);
        InstallState transientState = transientStates.get(cleanDb);
        if (transientState == null) transientState = transientStates.get(dbName.trim());
        if (transientState != null) return transientState;

        boolean exists = isDatabasePresent(cleanDb) || isDatabasePresent(dbName.trim());
        return exists ? InstallState.INSTALLED : InstallState.NOT_INSTALLED;
    }

    public boolean isDatabasePresent(String dbName) {
        if (engine == null || engine.getStorageCore() == null || dbName == null || dbName.isBlank()) return false;
        String cleanDb = SampleDatabaseNamingPolicy.canonicalize(dbName);
        Set<String> dbNames = engine.getStorageCore().getDatabaseNames();
        if (!dbNames.contains(cleanDb) && !dbNames.contains(dbName.trim())) {
            return false;
        }
        String[] prefixes = {"doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:", ""};
        for (String target : List.of(cleanDb, dbName.trim())) {
            for (String pfx : prefixes) {
                String scanKey = pfx + target + ":";
                Set<String> keys = engine.getStorageCore().scanPrefixKeys(scanKey);
                if (!keys.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getInstalledRecordCount(String dbName) {
        if (engine == null || engine.getStorageCore() == null || dbName == null || dbName.isBlank()) return 0;
        String cleanDb = SampleDatabaseNamingPolicy.canonicalize(dbName);
        Set<String> uniqueIds = new HashSet<>();
        String[] prefixes = {"doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:", ""};
        for (String target : List.of(cleanDb, dbName.trim())) {
            if (engine.getStorageCore().getDatabaseNames().contains(target)) {
                for (String pfx : prefixes) {
                    String scanKey = pfx + target + ":";
                    Set<String> keys = engine.getStorageCore().scanPrefixKeys(scanKey);
                    uniqueIds.addAll(keys);
                }
            }
        }
        return uniqueIds.size();
    }

    public CompletableFuture<HierarchyResult<Integer>> installAsync(String dbName) {
        return CompletableFuture.supplyAsync(() -> install(dbName), vThreadExecutor);
    }

    public HierarchyResult<Integer> install(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return HierarchyResult.failure("Invalid database name");
        }
        String cleanDb = SampleDatabaseNamingPolicy.canonicalize(dbName);
        transientStates.put(cleanDb, InstallState.INSTALLING);
        transientStates.put(dbName.trim(), InstallState.INSTALLING);
        try {
            HierarchyResult<Integer> result = installInvoker.executeInstall(cleanDb);
            transientStates.remove(cleanDb);
            transientStates.remove(dbName.trim());
            if (!result.isSuccess()) {
                uninstall(cleanDb);
            }
            return result;
        } catch (Exception e) {
            transientStates.remove(cleanDb);
            transientStates.remove(dbName.trim());
            // Rollback on failure
            uninstall(cleanDb);
            return HierarchyResult.failure("Failed to install sample database '" + cleanDb + "': " + e.getMessage(), e);
        }
    }

    public CompletableFuture<HierarchyResult<Integer>> uninstallAsync(String dbName) {
        return CompletableFuture.supplyAsync(() -> uninstall(dbName), vThreadExecutor);
    }

    public HierarchyResult<Integer> uninstall(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return HierarchyResult.failure("Invalid database name");
        }
        String cleanDb = SampleDatabaseNamingPolicy.canonicalize(dbName);
        transientStates.put(cleanDb, InstallState.REMOVING);
        transientStates.put(dbName.trim(), InstallState.REMOVING);
        try {
            int deleted = purgeDatabase(cleanDb);
            if (!cleanDb.equalsIgnoreCase(dbName.trim())) {
                deleted += purgeDatabase(dbName.trim());
            }
            transientStates.remove(cleanDb);
            transientStates.remove(dbName.trim());
            return HierarchyResult.success(deleted);
        } catch (Exception e) {
            transientStates.remove(cleanDb);
            transientStates.remove(dbName.trim());
            return HierarchyResult.failure("Failed to uninstall sample database '" + cleanDb + "': " + e.getMessage(), e);
        }
    }

    public int purgeDatabase(String dbName) {
        if (engine == null || engine.getStorageCore() == null || dbName == null || dbName.isBlank()) return 0;
        String cleanDb = dbName.trim();
        int count = 0;
        String[] prefixes = {
            "doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:",
            "meta:" + cleanDb + ":", "schema:" + cleanDb + ":", "rule:" + cleanDb + ":", "idx:" + cleanDb + ":",
            cleanDb + ":"
        };

        Set<String> keysToDelete = new HashSet<>();
        for (String pfx : prefixes) {
            String scanKey = pfx.contains(":") && !pfx.endsWith(":") ? pfx + ":" : pfx;
            if (!pfx.startsWith("meta:") && !pfx.startsWith("schema:") && !pfx.startsWith("rule:") && !pfx.startsWith("idx:") && !pfx.equals(cleanDb + ":")) {
                scanKey = pfx + cleanDb + ":";
            }
            Set<String> scan = engine.getStorageCore().scanPrefixKeys(scanKey);
            keysToDelete.addAll(scan);
        }

        for (String k : keysToDelete) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
            count++;
        }
        engine.getStorageCore().dropDatabase(cleanDb);
        return count;
    }
}
