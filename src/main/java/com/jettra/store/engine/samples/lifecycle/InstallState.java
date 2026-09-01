package com.jettra.store.engine.samples.lifecycle;

/**
 * Lifecycle installation state for on-demand sample databases.
 */
public enum InstallState {
    NOT_INSTALLED("badge-secondary", "Not Installed", "fas fa-download"),
    INSTALLING("badge-warning", "Installing...", "fas fa-spinner fa-spin"),
    INSTALLED("badge-active", "Installed", "fas fa-check-circle"),
    REMOVING("badge-danger", "Removing...", "fas fa-trash-alt fa-spin");

    private final String badgeCss;
    private final String label;
    private final String icon;

    InstallState(String badgeCss, String label, String icon) {
        this.badgeCss = badgeCss;
        this.label = label;
        this.icon = icon;
    }

    public String getBadgeCss() {
        return badgeCss;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
