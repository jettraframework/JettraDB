package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.hierarchy.HierarchyResult;
import com.jettra.store.engine.samples.SampleDatasetManager;

import java.util.Objects;
import java.util.Set;

/**
 * Concrete Command encapsulating the single-dataset installation of one of the 4 authorized
 * sample databases (ExampleDBReferences, hr_enterprise_db, meteorology_iot_db, ecommerce_olap_db).
 * Guarantees atomic, isolated installation of the chosen dataset without cross-contaminating
 * other sample namespaces.
 */
public final class InstallSingleDatasetCommand implements DatasetInstallCommand {

    public static final Set<String> ALLOWED_DATABASES = Set.of(
        "ExampleDBReferences",
        "hr_enterprise_db",
        "meteorology_iot_db",
        "ecommerce_olap_db"
    );

    private final SampleDatasetManager datasetManager;
    private final String targetDatabase;

    public InstallSingleDatasetCommand(SampleDatasetManager datasetManager, String targetDatabase) {
        this.datasetManager = Objects.requireNonNull(datasetManager, "SampleDatasetManager must not be null");
        this.targetDatabase = Objects.requireNonNull(targetDatabase, "Target database must not be null").trim();
    }

    @Override
    public String databaseName() {
        return targetDatabase;
    }

    @Override
    public HierarchyResult<Integer> execute() {
        // Strict restriction verification against authorized catalog
        boolean isAuthorized = ALLOWED_DATABASES.stream()
            .anyMatch(allowed -> allowed.equalsIgnoreCase(targetDatabase));

        if (!isAuthorized) {
            return HierarchyResult.failure("Unauthorized or obsolete sample database: '" + targetDatabase
                + "'. Only authorized datasets are supported: " + String.join(", ", ALLOWED_DATABASES));
        }

        try {
            int loaded = datasetManager.loadDataset(targetDatabase);
            return HierarchyResult.success(loaded);
        } catch (Exception e) {
            return HierarchyResult.failure("Failed to install single dataset '" + targetDatabase + "': " + e.getMessage(), e);
        }
    }
}
