package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.StorageModalCommands.*;
import com.jettra.store.engine.web.StorageTableView.FlatRecordItem;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Suite validating Action Handlers and Dialog Modals in StoreEnginesPage Table View
 * (Expanded Row Detail and Actions Column).
 */
public class StorageTableViewActionsTest {

    private Path tempDir;
    private JettraStorageEngine engine;
    private JettraJson jsonParser;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_table_view_actions_test");
        engine = new JettraStorageEngine(tempDir.toString());
        engine.registerEngine("DOCUMENT", new DocumentEngine(engine));
        engine.registerEngine("KEYVALUE", new KeyValueEngine(engine));
        engine.registerEngine("RECORDS", new RecordsEngine(engine));
        engine.start();

        jsonParser = new JettraJson();

        // Seed initial records
        long now = System.currentTimeMillis();
        engine.getStorageCore().put("doc:customers_db:default:cust_01", "{\"name\":\"Alice\",\"tier\":\"GOLD\"}".getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put("kv:customers_db:default:session_01", "auth_token_xyz".getBytes(StandardCharsets.UTF_8), now);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("Test 1: Table View Actions Column Renders Functional onClick Handlers")
    void testActionsColumnButtons() {
        String payload = "{\"name\":\"Alice\",\"tier\":\"GOLD\"}";
        String payloadB64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String versionsB64 = Base64.getEncoder().encodeToString("[{\"versionNumber\":1}]".getBytes(StandardCharsets.UTF_8));

        FlatRecordItem item = new FlatRecordItem(
            "DOCUMENT", "#38bdf8", "fas fa-file-alt", "customers_db", "default", "cust_01", 1, payload, payloadB64, versionsB64
        );

        Widget table = StorageTableView.build(
            "DOCUMENT", "customers_db", "default", "/engines?engine=", List.of(item), Collections.emptyMap(), jsonParser
        );

        String renderedHtml = table.render(Themes.FlatTheme());
        assertNotNull(renderedHtml);

        // 1. VER Action
        assertTrue(renderedHtml.contains("openInspectRecordModal"), "Must contain openInspectRecordModal handler");
        assertTrue(renderedHtml.contains("cust_01"), "Must pass record ID");

        // 2. EDITAR Action
        assertTrue(renderedHtml.contains("openUniversalEditModal"), "Must contain openUniversalEditModal handler");

        // 3. VERSIONES Action
        assertTrue(renderedHtml.contains("openUniversalRestoreModal"), "Must contain openUniversalRestoreModal handler");

        // 4. ELIMINAR Action
        assertTrue(renderedHtml.contains("openUniversalDeleteModal"), "Must contain openUniversalDeleteModal handler");
    }

    @Test
    @DisplayName("Test 2: Expanded Row Detail Summary Renders Modal Action Handlers")
    void testExpandedRowDetailButtons() {
        String payload = "{\"name\":\"Bob\",\"city\":\"Panama City\",\"_ref\":\"jref://customers_db:orders:ord_99\"}";
        String payloadB64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String versionsB64 = Base64.getEncoder().encodeToString("[{\"versionNumber\":1}]".getBytes(StandardCharsets.UTF_8));

        FlatRecordItem item = new FlatRecordItem(
            "DOCUMENT", "#38bdf8", "fas fa-file-alt", "customers_db", "default", "cust_02", 2, payload, payloadB64, versionsB64
        );

        Widget table = StorageTableView.build(
            "DOCUMENT", "customers_db", "default", "/engines?engine=", List.of(item), Collections.emptyMap(), jsonParser
        );

        String renderedHtml = table.render(Themes.FlatTheme());
        assertNotNull(renderedHtml);

        // Verify Detail Panel Buttons: Inspeccionar, Editar, Historial
        assertTrue(renderedHtml.contains("Inspeccionar"), "Expanded detail must have Inspeccionar button");
        assertTrue(renderedHtml.contains("Editar"), "Expanded detail must have Editar button");
        assertTrue(renderedHtml.contains("Historial (v2)"), "Expanded detail must have Historial (v2) button");

        // Verify jref detection in preview
        assertTrue(renderedHtml.contains("jref://"), "Must highlight jref references");
    }

    @Test
    @DisplayName("Test 3: Sealed HierarchyRowCommand Pattern Matching Dispatch")
    void testHierarchyRowCommandDispatch() {
        HierarchyRowCommand viewCmd = new ViewCommand("DOCUMENT", "customers_db", "default", "cust_01", "{}", 1);
        HierarchyRowCommand editCmd = new EditCommand("DOCUMENT", "customers_db", "default", "cust_01", "{}", new JsonObject());
        HierarchyRowCommand restoreCmd = new RestoreCommand("DOCUMENT", "customers_db", "default", "cust_01", 1700000000000L, 2);
        HierarchyRowCommand deleteCmd = new DeleteCommand("DOCUMENT", "customers_db", "default", "cust_01");

        assertEquals("VIEW:DOCUMENT:customers_db:default:cust_01:v1", StorageModalCommands.dispatchCommand(viewCmd));
        assertEquals("EDIT:DOCUMENT:customers_db:default:cust_01", StorageModalCommands.dispatchCommand(editCmd));
        assertEquals("RESTORE:DOCUMENT:customers_db:default:cust_01@1700000000000", StorageModalCommands.dispatchCommand(restoreCmd));
        assertEquals("DELETE:DOCUMENT:customers_db:default:cust_01", StorageModalCommands.dispatchCommand(deleteCmd));
    }

    @Test
    @DisplayName("Test 4: Modal Dialog Components HTML Structure & Element IDs")
    void testModalDialogComponentsRendering() {
        String actionUrl = "/engines?engine=DOCUMENT";

        // 1. Inspect Modal (VER)
        Widget inspectModal = StorageModalCommands.buildInspectModal();
        String inspectHtml = inspectModal.render(Themes.FlatTheme());
        assertTrue(inspectHtml.contains("id=\"inspectRecordModal\"") || inspectHtml.contains("id='inspectRecordModal'"));
        assertTrue(inspectHtml.contains("inspectRecordPayloadDisplay"));
        assertTrue(inspectHtml.contains("chkInspectResolveRefs"));

        // 2. Universal Edit Modal (EDITAR)
        Widget editModal = StorageModalCommands.buildUniversalEditModal(actionUrl);
        String editHtml = editModal.render(Themes.FlatTheme());
        assertTrue(editHtml.contains("id=\"universalEditModal\"") || editHtml.contains("id='universalEditModal'"));
        assertTrue(editHtml.contains("universalEditPayloadInput"));
        assertTrue(editHtml.contains("action=\"/engines?engine=DOCUMENT\"") || editHtml.contains("action='/engines?engine=DOCUMENT'"));

        // 3. Universal Restore Modal (VERSIONES)
        Widget restoreModal = StorageModalCommands.buildVersionsModal(actionUrl);
        String restoreHtml = restoreModal.render(Themes.FlatTheme());
        assertTrue(restoreHtml.contains("id=\"universalRestoreModal\"") || restoreHtml.contains("id='universalRestoreModal'"));
        assertTrue(restoreHtml.contains("universalVersionsContainer"));

        // 4. Confirm Delete Modal (ELIMINAR)
        Widget deleteModal = StorageModalCommands.buildDeleteModal(actionUrl);
        String deleteHtml = deleteModal.render(Themes.FlatTheme());
        assertTrue(deleteHtml.contains("id=\"confirmDeleteModal\"") || deleteHtml.contains("id='confirmDeleteModal'"));
        assertTrue(deleteHtml.contains("confirmDeleteIdInput"));

        // 5. Script definitions
        Widget script = StorageModalCommands.buildModalActionHandlersScript();
        String scriptHtml = script.render(Themes.FlatTheme());
        assertTrue(scriptHtml.contains("window.openInspectRecordModal"));
        assertTrue(scriptHtml.contains("window.openUniversalEditModal"));
        assertTrue(scriptHtml.contains("window.openUniversalRestoreModal"));
        assertTrue(scriptHtml.contains("window.openUniversalDeleteModal"));
    }

    @Test
    @DisplayName("Test 5: Full StoreEnginesPage Integration with Modals & Table View")
    void testStoreEnginesPageIntegration() {
        StoreEnginesPage page = new StoreEnginesPage(engine);
        Map<String, String> params = new HashMap<>();
        params.put("engine", "DOCUMENT");
        params.put("target_db", "customers_db");
        params.put("view_mode", "table");

        Widget content = page.buildContent(null, params, "Ast");
        assertNotNull(content);

        String renderedHtml = content.render(Themes.FlatTheme());
        assertTrue(renderedHtml.contains("inspectRecordModal"));
        assertTrue(renderedHtml.contains("universalEditModal"));
        assertTrue(renderedHtml.contains("universalRestoreModal"));
        assertTrue(renderedHtml.contains("confirmDeleteModal"));
        assertTrue(renderedHtml.contains("openInspectRecordModal"));
    }
}
