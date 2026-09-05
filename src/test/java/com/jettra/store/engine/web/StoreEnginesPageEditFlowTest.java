package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.test.annotation.JettraTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the Edit Document dialog flow, JettraFlux LoadingButton/ModalDialog rendering,
 * and the non-blocking execution of version persistence.
 */
public class StoreEnginesPageEditFlowTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private StoreEnginesPage page;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_edit_flow_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.start();

        page = new StoreEnginesPage(engine);
    }

    @AfterEach
    public void tearDown() {
        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception ignored) {}
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    @JettraTest
    @DisplayName("Modal Edit Document should render JettraFlux ModalDialog and LoadingButton")
    public void testEditDocumentModalRendersLoadingButtonAndModalDialog() {
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "customers_db");

        Widget widget = page.buildContent(null, params, "dark");
        assertNotNull(widget);
        String html = widget.render(Themes.FlatTheme());

        // Verify ModalDialog container
        assertTrue(html.contains("id=\"editDocumentModal\""), "Must contain editDocumentModal container");
        assertTrue(html.contains("modal-dialog-card"), "Must use JettraFlux ModalDialog card layout");

        // Verify LoadingButton with State Pattern attributes
        assertTrue(html.contains("Save Changes (New Version)"), "Must render Save Changes (New Version) button label");
        assertTrue(html.contains("data-saving-label=\"Saving...\""), "LoadingButton must configure data-saving-label");
        assertTrue(html.contains("data-saving-icon=\"fas fa-circle-notch fa-spin\""), "LoadingButton must configure spinner icon");
        assertTrue(html.contains("handleModalFormSubmit"), "LoadingButton must wire handleModalFormSubmit");
    }

    @Test
    @JettraTest
    @DisplayName("Edit entity should persist new version and update view without blocking")
    public void testEditEntityPersistsNewVersionViaPage() throws Exception {
        String db = "customers_db";
        String coll = "accounts";
        String id = "acc_001";
        String key = "doc:" + db + ":" + coll + ":" + id;

        // Seed initial v1
        long t1 = System.currentTimeMillis() - 5000;
        String v1Data = "{\"accountNumber\":\"ACC-100\",\"balance\":500.0}";
        engine.getStorageCore().put(key, v1Data.getBytes(StandardCharsets.UTF_8), t1);
        assertEquals(1, engine.getStorageCore().getVersionCount(key));

        // Submit edit via Command Handler
        String v2Data = "{\"accountNumber\":\"ACC-100\",\"balance\":750.0}";
        EditDocumentCommand cmd = EditDocumentCommand.of("DOCUMENT", db, coll, id, v2Data);
        EditDocumentResult res = page.getEditActionHandler().executeEditAsync(cmd).get(5, TimeUnit.SECONDS);

        assertTrue(res.success());
        assertEquals("DOCUMENT", res.engineType());
        assertEquals(db, res.database());
        assertEquals(id, res.recordId());
        assertEquals(2, res.versionCount());

        // Storage verification
        assertEquals(2, engine.getStorageCore().getVersionCount(key));
        byte[] current = engine.getStorageCore().get(key);
        assertNotNull(current);
        assertTrue(new String(current, StandardCharsets.UTF_8).contains("750.0"));
    }

    @Test
    @JettraTest
    @DisplayName("Non-AJAX POST on StoreEnginesPage creates new version and produces alert message")
    public void testNonAjaxPostEditProducesSuccessAlert() {
        String db = "inventory_db";
        String coll = "parts";
        String id = "part_88";
        String key = "doc:" + db + ":" + coll + ":" + id;

        // Seed initial version
        engine.getStorageCore().put(key, "{\"name\":\"Gear\",\"qty\":10}".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis() - 2000);
        assertEquals(1, engine.getStorageCore().getVersionCount(key));

        Map<String, String> postParams = new HashMap<>();
        postParams.put("action", "edit_object");
        postParams.put("engine_type", "DOCUMENT");
        postParams.put("target_db", db);
        postParams.put("target_coll", coll);
        postParams.put("target_id", id);
        postParams.put("doc_payload", "{\"name\":\"Heavy Gear\",\"qty\":25}");
        postParams.put("view_mode", "table");

        Widget widget = page.buildContent(null, postParams, "dark");
        assertNotNull(widget);
        String html = widget.render(Themes.FlatTheme());

        assertTrue(html.contains("updated successfully"), "Must show flash success message for edit");
        assertEquals(2, engine.getStorageCore().getVersionCount(key), "Version count must be incremented to 2");
    }
}
