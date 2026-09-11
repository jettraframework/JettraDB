package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyResult;
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
            "ExampleDBReferences",
            "MULTI-MODEL",
            "ExampleDBReferences",
            "Cross-Engine & Multi-Cluster References Suite",
            "Demonstrates direct O(1) object references (jref://) with primary storage addresses, multi-cluster node pointers, and dynamic reference resolution across Document, Records, Geo, Vector, Object, KeyValue, and TimeSeries engines.",
            120,
            "fas fa-link",
            List.of("References", "Jref", "Multi-Cluster", "Composite")
        ),
        new SampleDatabaseDefinition(
            "hr_enterprise_db",
            "RECORDS",
            "hr_enterprise_db",
            "Java 25 Enterprise HR & Payroll",
            "Immutable Record instances for Employees, Departments, Contracts, and Salary components with cross-engine biometrics and GIS links.",
            1000,
            "fas fa-id-card-alt",
            List.of("Records", "Schema", "HR", "Immutable")
        ),
        new SampleDatabaseDefinition(
            "meteorology_iot_db",
            "TIMESERIES",
            "meteorology_iot_db",
            "IoT Meteorological Weather Stations",
            "High-frequency sensor telemetry (temperature, humidity, atmospheric pressure, solar irradiance, precipitation) across time intervals.",
            2500,
            "fas fa-cloud-sun-rain",
            List.of("IoT", "Telemetry", "TimeSeries", "Sensors")
        ),
        new SampleDatabaseDefinition(
            "ecommerce_olap_db",
            "COLUMN",
            "ecommerce_olap_db",
            "E-Commerce OLAP Analytics",
            "Wide-column analytical fact tables, quarterly revenue by region, customer cohort aggregations, and performance metrics.",
            1000,
            "fas fa-table",
            List.of("Column", "OLAP", "Analytics", "Wide-Table")
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
        InstallState transientState = transientStates.get(dbName);
        if (transientState != null) return transientState;

        boolean exists = isDatabasePresent(dbName);
        return exists ? InstallState.INSTALLED : InstallState.NOT_INSTALLED;
    }

    public boolean isDatabasePresent(String dbName) {
        if (engine == null || engine.getStorageCore() == null) return false;
        String[] prefixes = {"doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:", ""};
        for (String pfx : prefixes) {
            String scanKey = pfx + dbName + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(scanKey);
            if (!keys.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int getInstalledRecordCount(String dbName) {
        if (engine == null || engine.getStorageCore() == null) return 0;
        Set<String> uniqueIds = new HashSet<>();
        String[] prefixes = {"doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:", ""};
        for (String pfx : prefixes) {
            String scanKey = pfx + dbName + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(scanKey);
            uniqueIds.addAll(keys.keySet());
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
        transientStates.put(dbName, InstallState.INSTALLING);
        try {
            HierarchyResult<Integer> result = installInvoker.executeInstall(dbName.trim());
            transientStates.remove(dbName);
            if (!result.isSuccess()) {
                uninstall(dbName);
            }
            return result;
        } catch (Exception e) {
            transientStates.remove(dbName);
            // Rollback on failure
            uninstall(dbName);
            return HierarchyResult.failure("Failed to install sample database '" + dbName + "': " + e.getMessage(), e);
        }
    }

    public CompletableFuture<HierarchyResult<Integer>> uninstallAsync(String dbName) {
        return CompletableFuture.supplyAsync(() -> uninstall(dbName), vThreadExecutor);
    }

    public HierarchyResult<Integer> uninstall(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return HierarchyResult.failure("Invalid database name");
        }
        transientStates.put(dbName, InstallState.REMOVING);
        try {
            int deleted = purgeDatabase(dbName);
            transientStates.remove(dbName);
            return HierarchyResult.success(deleted);
        } catch (Exception e) {
            transientStates.remove(dbName);
            return HierarchyResult.failure("Failed to uninstall sample database '" + dbName + "': " + e.getMessage(), e);
        }
    }

    public int purgeDatabase(String dbName) {
        if (engine == null || engine.getStorageCore() == null) return 0;
        int count = 0;
        String[] prefixes = {
            "doc:", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:",
            "meta:" + dbName + ":", "schema:" + dbName + ":", "rule:" + dbName + ":", "idx:" + dbName + ":",
            dbName + ":"
        };

        Set<String> keysToDelete = new HashSet<>();
        for (String pfx : prefixes) {
            String scanKey = pfx.contains(":") && !pfx.endsWith(":") ? pfx + ":" : pfx;
            if (!pfx.startsWith("meta:") && !pfx.startsWith("schema:") && !pfx.startsWith("rule:") && !pfx.startsWith("idx:") && !pfx.equals(dbName + ":")) {
                scanKey = pfx + dbName + ":";
            }
            Map<String, byte[]> scan = engine.getStorageCore().scanPrefix(scanKey);
            keysToDelete.addAll(scan.keySet());
        }

        for (String k : keysToDelete) {
            engine.getStorageCore().delete(k, System.currentTimeMillis());
            count++;
        }
        return count;
    }
}
