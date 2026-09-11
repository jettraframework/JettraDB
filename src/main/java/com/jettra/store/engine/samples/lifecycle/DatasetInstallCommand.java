package com.jettra.store.engine.samples.lifecycle;

import com.jettra.store.engine.hierarchy.HierarchyResult;

/**
 * Command Pattern interface for sample dataset lifecycle installation operations.
 * Java 25+ clean object-oriented contract ensuring encapsulated, transactional
 * single-dataset provisioning.
 */
public interface DatasetInstallCommand {

    /**
     * Target database identifier to be provisioned.
     *
     * @return clean database name
     */
    String databaseName();

    /**
     * Executes the unit installation of the dataset.
     *
     * @return structured result containing count of records created or failure reason
     */
    HierarchyResult<Integer> execute();
}
