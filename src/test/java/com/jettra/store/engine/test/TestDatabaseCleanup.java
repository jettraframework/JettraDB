package com.jettra.store.engine.test;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.samples.lifecycle.SampleDatabaseService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

/**
 * Standard test utility ensuring that upon finishing tests, all databases created
 * or installed during test execution are purged and dropped, file handles are closed,
 * and temporary directories are completely removed to prevent system/memory overload.
 */
public final class TestDatabaseCleanup {

    private static final Set<String> KNOWN_SAMPLE_DATABASES = Set.of(
        "ExampleDBReferences",
        "hr_enterprise_db",
        "meteorology_iot_db",
        "ecommerce_olap_db"
    );

    private TestDatabaseCleanup() {}

    public static void cleanUp(JettraStorageEngine engine, Path tempDir) {
        cleanUp(engine, tempDir, null);
    }

    public static void cleanUp(JettraStorageEngine engine, Path tempDir, SampleDatabaseService sampleService) {
        if (sampleService != null) {
            for (String sampleDb : KNOWN_SAMPLE_DATABASES) {
                try {
                    sampleService.uninstall(sampleDb);
                } catch (Exception ignored) {}
            }
        }
        if (engine != null) {
            try {
                engine.dropAllDatabases();
            } catch (Exception ignored) {}
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }
}
