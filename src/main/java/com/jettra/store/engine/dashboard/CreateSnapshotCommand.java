package com.jettra.store.engine.dashboard;

import com.jettra.store.engine.dashboard.DashboardMetrics.ComprehensiveDashboardSnapshot;
import io.jettra.flux.download.DownloadResource;
import io.jettra.flux.download.DownloadableResource;
import io.jettra.flux.theme.ColorMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Command Pattern implementation in Java 25+ decoupling dashboard snapshot generation
 * from HTTP transport and presentation mechanisms.
 * Produces an immutable, safe DownloadResource ready for streaming.
 */
public final class CreateSnapshotCommand implements DownloadableResource {

    private final Path storageDir;
    private final ComprehensiveDashboardSnapshot snapshot;
    private final String user;
    private final String themeName;
    private final ColorMode colorMode;

    public CreateSnapshotCommand(
        Path storageDir,
        ComprehensiveDashboardSnapshot snapshot,
        String user,
        String themeName,
        ColorMode colorMode
    ) {
        this.storageDir = storageDir;
        this.snapshot = Objects.requireNonNull(snapshot, "ComprehensiveDashboardSnapshot must not be null");
        this.user = (user != null && !user.isBlank()) ? user : "root";
        this.themeName = (themeName != null && !themeName.isBlank()) ? themeName : "Matrix";
        this.colorMode = (colorMode != null) ? colorMode : ColorMode.DARK;
    }

    /**
     * Executes the snapshot generation and wraps the produced Markdown report in a DownloadResource.
     *
     * @return DownloadResource pointing to the persisted Markdown snapshot
     * @throws IOException if snapshot file creation fails
     */
    public DownloadResource execute() throws IOException {
        Path snapshotPath = SnapshotService.createSnapshot(storageDir, snapshot, user, themeName, colorMode);
        return DownloadResource.ofPath(snapshotPath, "text/markdown; charset=UTF-8", false);
    }

    @Override
    public DownloadResource getDownloadResource() throws Exception {
        return execute();
    }

    public Path getStorageDir() {
        return storageDir;
    }

    public ComprehensiveDashboardSnapshot getSnapshot() {
        return snapshot;
    }

    public String getUser() {
        return user;
    }

    public String getThemeName() {
        return themeName;
    }

    public ColorMode getColorMode() {
        return colorMode;
    }
}
