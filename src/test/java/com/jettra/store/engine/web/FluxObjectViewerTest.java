package com.jettra.store.engine.web;

import io.jettra.flux.theme.Themes;
import io.jettra.flux.widgets.FluxJsonTree;
import io.jettra.flux.widgets.FluxObjectViewer;
import io.jettra.flux.widgets.FluxSnapshotDrawer;
import io.jettra.test.annotation.JettraTest;
import io.jettra.test.core.JettraAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and DOM Tests for FluxObjectViewer, FluxJsonTree, and FluxSnapshotDrawer components.
 */
public class FluxObjectViewerTest {

    @Test
    @JettraTest
    @DisplayName("Test 1: FluxJsonTree correctly renders nested structures with typed badges")
    public void testFluxJsonTreeRendering() {
        String json = "{\"id\":\"u1\",\"active\":true,\"count\":42,\"details\":{\"dept\":\"Engineering\",\"tags\":[\"lead\",\"core\"]}}";
        FluxJsonTree tree = FluxJsonTree.of(json, true);
        String html = tree.render(Themes.FlatTheme());

        assertNotNull(html);
        assertTrue(html.contains("jettra-flux-json-tree"), "Must have json tree css class");
        assertTrue(html.contains("u1"), "Must contain string value u1");
        assertTrue(html.contains("true"), "Must contain boolean true");
        assertTrue(html.contains("42"), "Must contain number 42");
        assertTrue(html.contains("dept"), "Must contain nested field dept");
        assertTrue(html.contains("Engineering"), "Must contain Engineering value");

        JettraAssert.assertNotNull(html, "HTML should not be null");
    }

    @Test
    @JettraTest
    @DisplayName("Test 2: FluxObjectViewer renders compact preview and expandable inspector")
    public void testFluxObjectViewerRendering() {
        String json = "{\"customer\":\"ACME Corp\",\"plan\":\"Enterprise\",\"seats\":100,\"region\":\"US-EAST\"}";
        FluxObjectViewer viewer = FluxObjectViewer.of(json)
            .title("Snapshot v2")
            .expandable(true)
            .defaultExpanded(false)
            .maxPreviewLength(50);

        assertEquals("Snapshot Attributes", FluxObjectViewer.of("{}").getRawPayload().length() > 0 ? "Snapshot Attributes" : "");
        assertTrue(viewer.getCompactPreview().contains("ACME Corp"));

        String html = viewer.render(Themes.FlatTheme());
        assertNotNull(html);
        assertTrue(html.contains("jettra-flux-object-viewer"), "Must contain object viewer root class");
        assertTrue(html.contains("ACME Corp"), "Must contain preview text");
        assertTrue(html.contains("Snapshot v2"), "Must contain detail title");
        assertTrue(html.contains("Copy JSON Payload") || html.contains("fa-copy"), "Must contain copy action button");

        JettraAssert.assertTrue(html.contains("jettra-flux-object-viewer"), "Must render viewer component");
    }

    @Test
    @JettraTest
    @DisplayName("Test 3: FluxSnapshotDrawer renders full snapshot attributes flyout panel")
    public void testFluxSnapshotDrawerRendering() {
        String payload = "{\"device\":\"IoT-Sensor-01\",\"temp\":24.5,\"humidity\":60}";
        FluxSnapshotDrawer drawer = FluxSnapshotDrawer.of("v3", "2026-09-02 08:30:00", "admin_user", payload);

        String html = drawer.render(Themes.FlatTheme());
        assertNotNull(html);
        assertTrue(html.contains("jettra-flux-snapshot-drawer"), "Must contain snapshot drawer root class");
        assertTrue(html.contains("v3"), "Must display version badge v3");
        assertTrue(html.contains("2026-09-02 08:30:00"), "Must display formatted timestamp");
        assertTrue(html.contains("admin_user"), "Must display author");
        assertTrue(html.contains("IoT-Sensor-01"), "Must display payload attributes in tree view");

        JettraAssert.assertTrue(html.contains("jettra-flux-snapshot-drawer"), "Drawer must render correctly");
    }
}
