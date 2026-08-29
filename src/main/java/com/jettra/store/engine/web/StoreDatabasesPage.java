package com.jettra.store.engine.web;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.samples.SampleDatasetManager;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import io.jettra.server.autentification.entity.JCredential;
import io.jettra.server.autentification.entity.JRole;
import io.jettra.server.autentification.entity.JUser;
import io.jettra.server.autentification.repository.JCredentialRepository;
import io.jettra.server.autentification.repository.JCredentialRepositoryImpl;
import io.jettra.server.autentification.repository.JUserRepository;
import io.jettra.server.autentification.repository.JUserRepositoryImpl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/**
 * Visual Database and Component Management Console for JettraStoreEngine.
 * Features:
 * - Dynamic list of all created databases and their internal components/collections
 * - Full support for the Java 25 RECORDS engine
 * - Granular User & Role Administration per database
 * - Component inspector with item payloads, schema reflection, and deletion
 * Built with pure JettraFlux components.
 */
@NoLoginRequired
public class StoreDatabasesPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;
    private final AuthManager authManager;
    private final JUserRepository userRepo;
    private final JCredentialRepository credRepo;
    private final SampleDatasetManager sampleDatasetManager;

    public StoreDatabasesPage(JettraStorageEngine engine, AuthManager authManager) {
        this.engine = engine;
        this.authManager = authManager;
        this.userRepo = new JUserRepositoryImpl();
        this.credRepo = new JCredentialRepositoryImpl();
        this.sampleDatasetManager = new SampleDatasetManager(engine);
    }

    @Override
    protected String getPageTitle() {
        return "Databases & Components Console - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        String alertMessage = "";
        String alertType = "badge-active";

        // Handle Actions: create_db, drop_db, add_component, assign_user, delete_entity
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                String action = params != null ? params.get("action") : null;
                if ("create_db".equalsIgnoreCase(action)) {
                    String dbName = params.get("db_name");
                    String initialEngine = params.get("initial_engine");
                    String initialKey = params.get("initial_key");
                    String payload = params.get("payload");

                    if (dbName != null && !dbName.isBlank()) {
                        String cleanDb = dbName.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        String keyId = (initialKey != null && !initialKey.isBlank()) ? initialKey.trim() : "init_01";
                        String eng = (initialEngine != null && !initialEngine.isBlank()) ? initialEngine.toUpperCase() : "RECORDS";

                        String prefix = getPrefixForEngine(eng);
                        String internalKey = prefix + cleanDb + ":" + keyId;
                        String data = (payload != null && !payload.isBlank()) ? payload : "{\"status\":\"ACTIVE\",\"createdAt\":" + System.currentTimeMillis() + "}";

                        engine.getStorageCore().put(internalKey, data.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Database '" + cleanDb + "' successfully initialized with component [" + eng + "]!";
                        alertType = "badge-active";
                    }
                } else if ("rename_db".equalsIgnoreCase(action)) {
                    String oldDb = params.get("old_db");
                    String newDb = params.get("new_db");
                    if (oldDb != null && newDb != null && !newDb.isBlank()) {
                        int migrated = renameDatabase(oldDb.trim(), newDb.trim());
                        alertMessage = "Database '" + oldDb + "' renamed to '" + newDb + "' (" + migrated + " keys migrated).";
                        alertType = "badge-active";
                    }
                } else if ("drop_db".equalsIgnoreCase(action)) {
                    String targetDb = params.get("target_db");
                    if (targetDb != null && !targetDb.isBlank()) {
                        int purged = purgeDatabase(targetDb.trim());
                        alertMessage = "Database '" + targetDb + "' dropped (" + purged + " components purged).";
                        alertType = "badge-raft";
                    }
                } else if ("add_component".equalsIgnoreCase(action)) {
                    String targetDb = params.get("target_db");
                    String engineType = params.get("engine_type");
                    String keyId = params.get("key_id");
                    String payload = params.get("payload");

                    if (targetDb != null && keyId != null && engineType != null) {
                        String prefix = getPrefixForEngine(engineType);
                        String internalKey = prefix + targetDb.trim() + ":" + keyId.trim();
                        engine.getStorageCore().put(internalKey, payload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
                        alertMessage = "Component [" + engineType + "] entity '" + keyId + "' added to database '" + targetDb + "'!";
                        alertType = "badge-active";
                    }
                } else if ("delete_entity".equalsIgnoreCase(action)) {
                    String rawKey = params.get("raw_key");
                    if (rawKey != null && !rawKey.isBlank()) {
                        engine.getStorageCore().delete(rawKey, System.currentTimeMillis());
                        alertMessage = "Entity '" + rawKey + "' deleted from storage core.";
                        alertType = "badge-raft";
                    }
                } else if ("load_sample_dataset".equalsIgnoreCase(action)) {
                    String datasetKey = params.get("dataset_key");
                    int loaded = sampleDatasetManager.loadDataset(datasetKey);
                    alertMessage = "Sample Dataset [" + datasetKey + "] loaded successfully (" + loaded + " records populated across multi-model engines with cross-references)!";
                    alertType = "badge-active";
                } else if ("assign_user".equalsIgnoreCase(action)) {
                    String username = params.get("username");
                    String email = params.get("email");
                    String password = params.get("password");
                    String targetDb = params.get("target_db");
                    String roleName = params.get("role");

                    if (username != null && !username.isBlank()) {
                        UUID newId = UUID.randomUUID();
                        JRole role = new JRole(UUID.randomUUID(), roleName != null ? roleName : "READ_WRITE", true);
                        Set<JRole> roles = new HashSet<>();
                        roles.add(role);

                        String dbScope = targetDb != null && !targetDb.isBlank() ? targetDb : "*";
                        JUser newUser = new JUser(newId, username, dbScope, email != null ? email : username + "@jettra.io", "+123456", true, roles);
                        userRepo.save(newUser);

                        JCredential cred = new JCredential(UUID.randomUUID(), newUser, username, password != null && !password.isBlank() ? password : "password123", true, Instant.now());
                        credRepo.save(cred);

                        alertMessage = "User '" + username + "' provisioned with role [" + roleName + "] for database scope '" + dbScope + "'!";
                        alertType = "badge-active";
                    }
                }
            } catch (Exception e) {
                alertMessage = "Operation failed: " + e.getMessage();
                alertType = "badge-raft";
            }
        }

        // Discover all databases and their components
        Map<String, DatabaseMetadata> databases = discoverDatabases();

        if (databases.isEmpty()) {
            DatabaseMetadata defaultDb = new DatabaseMetadata("system_db");
            defaultDb.addComponent("RECORDS", 1);
            defaultDb.addComponent("DOCUMENT", 1);
            databases.put("system_db", defaultDb);
        }

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1,
                    Icon.of("fas fa-server").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Databases & Components Console")
                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(
                    Text.of("Visual management of database namespaces, multi-model storage components, Java 25 Records, and per-database RBAC security.")
                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            ),
            Row.of(
                Button.of(
                    Icon.of("fas fa-plus-circle"),
                    Text.of(" New Database")
                ).attribute("onclick", "openCreateDbModal()")
                 .modifier(new Modifier().cssClass("btn-action btn-primary")),
                Link.of(JettraServer.resolvePath("/users"),
                    Icon.of("fas fa-users-cog"),
                    Text.of(" User Security")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-left:8px;")),
                Link.of(JettraServer.resolvePath("/engines"),
                    Icon.of("fas fa-cubes"),
                    Text.of(" Engines Matrix")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-left:8px;"))
            ).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Alert Banner
        Widget alertWidget = alertMessage.isEmpty() ? Div.of() : Div.of(
            Div.of(
                Icon.of("fas fa-check-circle").modifier(new Modifier().style("color:#38bdf8; font-size:18px;")),
                Span.of(alertMessage).modifier(new Modifier().style("font-size:14px; color:#f8fafc; font-weight:500;"))
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
            Span.of("SYNCHRONIZED").modifier(new Modifier().cssClass("store-badge " + alertType))
        ).modifier(new Modifier().style("background: rgba(30, 41, 59, 0.9); border: 1px solid rgba(59,130,246,0.4); padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;"));

        // Stats Summary
        int totalDatabases = databases.size();
        int totalComponents = databases.values().stream().mapToInt(d -> d.getEngineCounts().size()).sum();
        int totalObjects = databases.values().stream().mapToInt(DatabaseMetadata::getTotalObjects).sum();
        int totalRecords = databases.values().stream().mapToInt(d -> d.getEngineCounts().getOrDefault("RECORDS", 0)).sum();

        Widget statGrid = Div.of(
            createStatCard("fas fa-database", "#3b82f6", "Active Databases", totalDatabases + " Databases", "LSM / B-Tree Hybrid Storage", "badge-active"),
            createStatCard("fas fa-cubes", "#a855f7", "Multi-Model Components", totalComponents + " Active Engine Models", "9 Supported Engines", "badge-raft"),
            createStatCard("fas fa-id-card", "#f43f5e", "Java 25 Records", totalRecords + " Typed Records", "JEP 450 Compact Headers", "badge-records"),
            createStatCard("fas fa-layer-group", "#10b981", "Total Stored Entities", totalObjects + " Total Objects", "Raft State Synchronized", "badge-active")
        ).modifier(new Modifier().cssClass("store-stat-grid"));

        // Load all users
        List<JUser> allUsers = userRepo.findAll();

        // Build Database Interactive Cards & Components Explorer
        List<Widget> dbCardList = new ArrayList<>();

        for (DatabaseMetadata dbMeta : databases.values()) {
            String dbName = dbMeta.getName();
            int objCount = dbMeta.getTotalObjects();

            // Users scoped to this db
            List<JUser> dbUsers = allUsers.stream().filter(u -> dbName.equalsIgnoreCase(u.lastName()) || "*".equals(u.lastName())).toList();

            // Header of each DB card
            Widget dbHeaderLeft = Div.of(
                Div.of(Icon.of("fas fa-database"))
                    .modifier(new Modifier().style("width:46px; height:46px; border-radius:10px; background:rgba(56,189,248,0.15); display:flex; align-items:center; justify-content:center; color:#38bdf8; font-size:22px;")),
                Div.of(
                    Div.of(
                        Header.of(2, Text.of(dbName)).modifier(new Modifier().style("margin:0; font-size:20px; font-weight:700; color:#f8fafc;")),
                        Span.of(RawHtml.of("<span class='pulse-dot'></span> ONLINE")).modifier(new Modifier().cssClass("store-badge badge-active"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:10px;")),
                    Div.of(
                        Text.of("Storage Engine: "),
                        Span.of("LSM-BTree Hybrid Core").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;")),
                        Text.of(" | Raft Quorum Replication")
                    ).modifier(new Modifier().style("font-size:13px; color:#94a3b8;"))
                )
            ).modifier(new Modifier().style("display:flex; align-items:center; gap:12px;"));

            Widget dbHeaderRight = Div.of(
                Button.of(Icon.of("fas fa-plus"), Text.of(" Add Component / Record"))
                    .attribute("onclick", "openAddComponentModal('" + dbName + "')")
                    .modifier(new Modifier().cssClass("btn-action btn-primary").style("padding:6px 12px; font-size:12px;")),
                Button.of(Icon.of("fas fa-user-plus"), Text.of(" Assign User"))
                    .attribute("onclick", "openAssignUserModal('" + dbName + "')")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")),
                Button.of(Icon.of("fas fa-list"), Text.of(" Inspect Entities (" + objCount + ")"))
                    .attribute("onclick", "toggleEntitiesViewer('" + dbName + "')")
                    .modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")),
                Link.of(JettraServer.resolvePath("/engines?engine=RECORDS&db=" + dbName),
                    Icon.of("fas fa-search"),
                    Text.of(" Explore Data")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("padding:6px 12px; font-size:12px;")),
                Button.of(Icon.of("fas fa-trash"), Text.of(""))
                    .attribute("onclick", "confirmDropDb('" + dbName + "')")
                    .attribute("title", "Drop Database")
                    .modifier(new Modifier().cssClass("btn-action btn-danger").style("padding:6px 10px; font-size:12px;"))
            ).modifier(new Modifier().style("display:flex; gap:8px; flex-wrap:wrap;"));

            Widget dbTopRow = Row.of(dbHeaderLeft, dbHeaderRight)
                .modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:12px; margin-bottom:16px;"));

            // Multi-Model Components Inside This Database
            List<Widget> compBoxes = new ArrayList<>();
            for (Map.Entry<String, Integer> comp : dbMeta.getEngineCounts().entrySet()) {
                String eng = comp.getKey();
                int cnt = comp.getValue();
                String badgeStyle = getBadgeStyleForEngine(eng);
                String icon = getIconForEngine(eng);
                String desc = getDescForEngine(eng);

                Widget compBox = Div.of(
                    Div.of(
                        Icon.of(icon).modifier(new Modifier().style("font-size:16px;")),
                        Div.of(
                            Div.of(Text.of(eng)).modifier(new Modifier().style("font-weight:700; font-size:13px;")),
                            Div.of(Text.of(desc)).modifier(new Modifier().style("font-size:11px; opacity:0.8;"))
                        )
                    ).modifier(new Modifier().style("display:flex; align-items:center; gap:8px;")),
                    Span.of(String.valueOf(cnt)).modifier(new Modifier().style("background:rgba(0,0,0,0.3); padding:2px 8px; border-radius:6px; font-weight:700; font-size:12px;"))
                ).modifier(new Modifier().style(badgeStyle + " padding:10px 14px; border-radius:8px; display:flex; align-items:center; gap:10px; min-width:200px; justify-content:space-between;"));

                compBoxes.add(compBox);
            }

            Widget internalComponentsBox = Div.of(
                Div.of(
                    Div.of(Icon.of("fas fa-cubes").modifier(new Modifier().style("color:#a855f7; margin-right:6px;")), Text.of("Internal Multi-Model Components (" + dbMeta.getEngineCounts().size() + " Engines Initialized)")),
                    Div.of(Text.of("Total Keys: "), Span.of(String.valueOf(objCount)).modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
                ).modifier(new Modifier().style("font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:10px; display:flex; justify-content:space-between; align-items:center;")),
                Div.of(compBoxes.toArray(new Widget[0])).modifier(new Modifier().style("display:flex; flex-wrap:wrap; gap:10px;"))
            ).modifier(new Modifier().style("background:rgba(15,23,42,0.6); border-radius:10px; padding:16px; border:1px solid rgba(255,255,255,0.06); margin-bottom:16px;"));

            // Expandable Entities Inspector Table for this database
            List<EntityDetail> entities = getEntitiesForDatabase(dbName);
            List<Widget> entityHeaders = List.of(
                Text.of("Engine"),
                Text.of("Entity Key"),
                Text.of("Type / Class"),
                Text.of("Payload Preview"),
                Text.of("Action")
            );

            List<List<Widget>> entityRows = new ArrayList<>();
            for (EntityDetail ed : entities) {
                String badgeClass = "RECORDS".equals(ed.engine) ? "badge-records" : "badge-engine";
                Widget engCell = Span.of(ed.engine).modifier(new Modifier().cssClass("store-badge " + badgeClass));
                Widget keyCell = RawHtml.of("<code style='color:#38bdf8; font-weight:600;'>" + ed.keyId + "</code>");
                Widget typeCell = Span.of(ed.typeOrClass).modifier(new Modifier().style("color:#cbd5e1; font-size:12px;"));
                Widget previewCell = RawHtml.of("<code class='mono' style='color:#94a3b8; font-size:11px; max-width:320px; display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;'>" + ed.payloadPreview + "</code>");
                Button deleteBtn = Button.of(Icon.of("fas fa-trash"), Text.of(""));
                deleteBtn.attribute("onclick", "deleteEntity('" + ed.rawKey + "')");
                deleteBtn.attribute("title", "Delete Entity");
                deleteBtn.modifier(new Modifier().cssClass("btn-action btn-danger").style("padding:4px 8px; font-size:11px;"));

                entityRows.add(List.of(engCell, keyCell, typeCell, previewCell, deleteBtn));
            }

            Widget entitiesTableWidget = entityRows.isEmpty()
                ? Div.of(Text.of("No components or entities stored yet. Click 'Add Component / Record' to insert one.")).modifier(new Modifier().style("color:#94a3b8; font-size:13px; padding:12px; text-align:center;"))
                : Div.of(Datatable.ofWidgets(entityHeaders, entityRows).modifier(new Modifier().cssClass("jettra-table"))).modifier(new Modifier().cssClass("table-responsive"));

            Widget entitiesViewer = Div.of(
                Div.of(
                    Header.of(4,
                        Icon.of("fas fa-layer-group"),
                        Text.of(" Stored Components & Entities in '" + dbName + "'")
                    ).modifier(new Modifier().style("margin:0; font-size:15px; font-weight:700; color:#38bdf8;")),
                    Span.of("Showing " + entities.size() + " persisted items").modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
                ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;")),
                entitiesTableWidget
            ).id("entities_" + dbName).modifier(new Modifier().style("display:none; background:rgba(15,23,42,0.8); border-radius:10px; padding:16px; border:1px solid rgba(255,255,255,0.08); margin-bottom:16px;"));

            // Scoped Users
            List<Widget> userBadges = new ArrayList<>();
            if (dbUsers.isEmpty()) {
                userBadges.add(Span.of("No users assigned specifically (inherited from global admin).").modifier(new Modifier().style("color:#64748b;")));
            } else {
                for (JUser u : dbUsers) {
                    String role = u.jRoles() != null && !u.jRoles().isEmpty() ? u.jRoles().iterator().next().name() : "READ_WRITE";
                    String roleBadge = "DB_ADMIN".equals(role) ? "badge-raft" : "badge-engine";
                    userBadges.add(Span.of(u.firstName() + " (" + role + ")").modifier(new Modifier().cssClass("store-badge " + roleBadge).style("font-size:11px; margin-right:4px;")));
                }
            }

            Widget scopedUsersBar = Div.of(
                Div.of(
                    Icon.of("fas fa-user-shield").modifier(new Modifier().style("color:#38bdf8;")),
                    Span.of("Scoped Users (" + dbUsers.size() + "): "),
                    Div.of(userBadges.toArray(new Widget[0]))
                ).modifier(new Modifier().style("display:flex; align-items:center; gap:8px;")),
                Button.of(Text.of("+ Assign User to " + dbName))
                    .attribute("onclick", "openAssignUserModal('" + dbName + "')")
                    .modifier(new Modifier().style("background:none; border:none; color:#38bdf8; font-size:12px; cursor:pointer; text-decoration:underline;"))
            ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; font-size:13px; color:#94a3b8; padding-top:10px; border-top:1px solid rgba(255,255,255,0.06);"));

            Widget dbCard = Div.of(
                RawHtml.of("<div style='position:absolute; top:0; left:0; width:4px; height:100%; background: linear-gradient(180deg, #38bdf8, #f43f5e);'></div>"),
                dbTopRow,
                internalComponentsBox,
                entitiesViewer,
                scopedUsersBar
            ).modifier(new Modifier().cssClass("store-card").style("position:relative; overflow:hidden;"));

            dbCardList.add(dbCard);
        }

        Widget databasesContainer = Div.of(dbCardList.toArray(new Widget[0]))
            .modifier(new Modifier().style("display: flex; flex-direction: column; gap: 24px; margin-bottom: 30px;"));

        // Modal 1: Create Database
        Widget createDbHeader = Row.of(
            Row.of(
                Icon.of("fas fa-database").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Header.of(3, Text.of("Provision New Database")).modifier(new Modifier().style("margin:0; font-size:20px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;").attribute("onclick", "document.getElementById('createDbModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;"));

        Widget createDbForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='create_db'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Database Namespace Name:</label>"),
                RawHtml.of("<input type='text' name='db_name' required placeholder='e.g. enterprise_store' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Engine Component:</label>"),
                RawHtml.of("<select name='initial_engine' onchange='updatePayloadTemplate(this.value, \"createPayload\")' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'>\n" +
                    "          <option value='RECORDS' selected>RECORDS (Java 25 Immutable Records)</option>\n" +
                    "          <option value='DOCUMENT'>DOCUMENT (NoSQL JSON Documents)</option>\n" +
                    "          <option value='VECTOR'>VECTOR (AI ANN Cosine Embeddings)</option>\n" +
                    "          <option value='GRAPH'>GRAPH (LPG Nodes & Relations)</option>\n" +
                    "          <option value='TIMESERIES'>TIMESERIES (IoT Sensor Telemetry)</option>\n" +
                    "          <option value='COLUMN'>COLUMN (OLAP Wide Column Tables)</option>\n" +
                    "          <option value='KEYVALUE'>KEYVALUE (High-Speed In-Memory Cache)</option>\n" +
                    "          <option value='GEOSPATIAL'>GEOSPATIAL (2D GIS Spatial Points)</option>\n" +
                    "          <option value='OBJECT'>OBJECT (Binary BLOBs & Media)</option>\n" +
                    "        </select>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Entity ID / Key:</label>"),
                RawHtml.of("<input type='text' name='initial_key' value='entity_01' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Initial Payload JSON:</label>"),
                RawHtml.of("<textarea id='createPayload' name='payload' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:13px; font-family:monospace; box-sizing:border-box;'>{\"_recordClass\": \"com.enterprise.model.InitRecord\", \"_schema\": {\"name\":\"String\", \"active\":\"Boolean\"}, \"components\": {\"name\": \"Enterprise System\", \"active\": true}}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:20px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('createDbModal').close();")),
                Button.of(Icon.of("fas fa-check"), Text.of(" Create Database")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget createDbModal = Dialog.of(createDbHeader, createDbForm)
            .id("createDbModal")
            .modifier(new Modifier().cssClass("store-card").style("width:540px; max-width:90%; background:#1e293b; border:1px solid rgba(255,255,255,0.15); box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:28px; margin:auto;"));

        // Modal 2: Add Component
        Widget addComponentHeader = Row.of(
            Row.of(
                Icon.of("fas fa-plus-circle").modifier(new Modifier().style("color:#f43f5e; margin-right:8px;")),
                RawHtml.of("<h3 style='margin:0; font-size:20px; font-weight:700; color:#f8fafc;'>Add Component to <span id='modalTargetDbLabel'></span></h3>")
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;").attribute("onclick", "document.getElementById('addComponentModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;"));

        Widget addComponentForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='add_component'/>"),
            RawHtml.of("<input type='hidden' name='target_db' id='modalTargetDbInput'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Engine Component Type:</label>"),
                RawHtml.of("<select name='engine_type' onchange='updatePayloadTemplate(this.value, \"addComponentPayload\")' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'>\n" +
                    "          <option value='RECORDS' selected>RECORDS (Java 25 Immutable Records)</option>\n" +
                    "          <option value='DOCUMENT'>DOCUMENT (NoSQL JSON Documents)</option>\n" +
                    "          <option value='VECTOR'>VECTOR (AI Embeddings)</option>\n" +
                    "          <option value='GRAPH'>GRAPH (Graph Node)</option>\n" +
                    "          <option value='TIMESERIES'>TIMESERIES (Metric Point)</option>\n" +
                    "          <option value='COLUMN'>COLUMN (Columnar Row)</option>\n" +
                    "          <option value='KEYVALUE'>KEYVALUE (Cache Key)</option>\n" +
                    "          <option value='GEOSPATIAL'>GEOSPATIAL (GIS Location)</option>\n" +
                    "          <option value='OBJECT'>OBJECT (Binary Stream)</option>\n" +
                    "        </select>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Entity Key / ID:</label>"),
                RawHtml.of("<input type='text' name='key_id' placeholder='e.g. record_101' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Payload JSON:</label>"),
                RawHtml.of("<textarea id='addComponentPayload' name='payload' rows='4' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:13px; font-family:monospace; box-sizing:border-box;'>{\"_recordClass\": \"com.enterprise.model.EmployeeRecord\", \"_schema\": {\"id\":\"String\", \"fullName\":\"String\", \"salary\":\"Double\"}, \"components\": {\"id\": \"emp_101\", \"fullName\": \"Carlos Mendez\", \"salary\": 95000.0}}</textarea>")
            ).modifier(new Modifier().style("margin-bottom:20px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('addComponentModal').close();")),
                Button.of(Icon.of("fas fa-save"), Text.of(" Save Component")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget addComponentModal = Dialog.of(addComponentHeader, addComponentForm)
            .id("addComponentModal")
            .modifier(new Modifier().cssClass("store-card").style("width:540px; max-width:90%; background:#1e293b; border:1px solid rgba(255,255,255,0.15); box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:28px; margin:auto;"));

        // Modal 3: Assign User
        Widget assignUserHeader = Row.of(
            Row.of(
                Icon.of("fas fa-user-shield").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                RawHtml.of("<h3 style='margin:0; font-size:20px; font-weight:700; color:#f8fafc;'>Assign User to <span id='assignUserDbLabel'></span></h3>")
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer;").attribute("onclick", "document.getElementById('assignUserModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;"));

        Widget assignUserForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='assign_user'/>"),
            RawHtml.of("<input type='hidden' name='target_db' id='assignUserDbInput'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Username:</label>"),
                RawHtml.of("<input type='text' name='username' placeholder='e.g. dev_analyst' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Email Address:</label>"),
                RawHtml.of("<input type='email' name='email' placeholder='analyst@company.com' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Password:</label>"),
                RawHtml.of("<input type='password' name='password' placeholder='••••••••' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Assigned RBAC Role:</label>"),
                RawHtml.of("<select name='role' style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'>\n" +
                    "          <option value='DB_ADMIN'>DB_ADMIN (Full DDL & Read/Write)</option>\n" +
                    "          <option value='READ_WRITE' selected>READ_WRITE (Insert, Update, Query)</option>\n" +
                    "          <option value='READ_ONLY'>READ_ONLY (Query Only)</option>\n" +
                    "          <option value='MANAGER'>MANAGER (Backup & Operations)</option>\n" +
                    "        </select>")
            ).modifier(new Modifier().style("margin-bottom:20px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('assignUserModal').close();")),
                Button.of(Icon.of("fas fa-user-check"), Text.of(" Provision User")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget assignUserModal = Dialog.of(assignUserHeader, assignUserForm)
            .id("assignUserModal")
            .modifier(new Modifier().cssClass("store-card").style("width:520px; max-width:90%; background:#1e293b; border:1px solid rgba(255,255,255,0.15); box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:28px; margin:auto;"));

        // Modal 4: Rename Database
        Widget renameDbHeader = Row.of(
            Row.of(
                Icon.of("fas fa-pen").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Header.of(3, Text.of("Rename Database")).modifier(new Modifier().style("margin:0; font-size:17px; font-weight:700; color:#f8fafc;"))
            ).modifier(new Modifier().style("display:flex; align-items:center;")),
            Button.of(Icon.of("fas fa-times")).modifier(new Modifier().style("background:none; border:none; color:#94a3b8; font-size:18px; cursor:pointer; padding:4px 8px;").attribute("onclick", "document.getElementById('renameDbModal').close();"))
        ).modifier(new Modifier().style("display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;"));

        Widget renameDbForm = Form.of(
            RawHtml.of("<input type='hidden' name='action' value='rename_db'/>"),
            RawHtml.of("<input type='hidden' name='old_db' id='renameOldDbInput'/>"),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>Current Database Name:</label>"),
                RawHtml.of("<input type='text' id='renameOldDbDisplay' disabled style='width:100%; padding:10px 12px; background:#1e293b; border:1px solid rgba(255,255,255,0.1); border-radius:8px; color:#94a3b8; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:14px;")),
            Div.of(
                RawHtml.of("<label style='display:block; font-size:13px; font-weight:600; color:#cbd5e1; margin-bottom:6px;'>New Database Name:</label>"),
                RawHtml.of("<input type='text' name='new_db' placeholder='e.g. inventory_prod_db' required style='width:100%; padding:10px 12px; background:#0f172a; border:1px solid rgba(255,255,255,0.15); border-radius:8px; color:#f8fafc; font-size:14px; box-sizing:border-box;'/>")
            ).modifier(new Modifier().style("margin-bottom:18px;")),
            Div.of(
                Button.of(Text.of("Cancel")).modifier(new Modifier().cssClass("btn-action btn-secondary").attribute("type", "button").attribute("onclick", "document.getElementById('renameDbModal').close();")),
                Button.of(Icon.of("fas fa-save"), Text.of(" Rename Database")).modifier(new Modifier().cssClass("btn-action btn-primary").attribute("type", "submit"))
            ).modifier(new Modifier().style("display:flex; justify-content:flex-end; gap:10px;"))
        ).attribute("method", "POST").attribute("action", JettraServer.resolvePath("/databases"));

        Widget renameDbModal = Dialog.of(renameDbHeader, renameDbForm)
            .id("renameDbModal")
            .modifier(new Modifier().cssClass("store-card").style("max-width:480px; width:90%; background:#0f172a; border:1px solid rgba(56,189,248,0.4); border-radius:14px; padding:24px; margin:auto;"));

        Widget scriptsWidget = RawHtml.of(
            "<script>\n" +
            "  function openModal(id) { document.getElementById(id).showModal(); }\n" +
            "  function openCreateDbModal() { openModal('createDbModal'); }\n" +
            "  function openAddComponentModal(db) {\n" +
            "    document.getElementById('modalTargetDbInput').value = db;\n" +
            "    document.getElementById('modalTargetDbLabel').innerText = db;\n" +
            "    openModal('addComponentModal');\n" +
            "  }\n" +
            "  function openAssignUserModal(db) {\n" +
            "    document.getElementById('assignUserDbInput').value = db;\n" +
            "    document.getElementById('assignUserDbLabel').innerText = db;\n" +
            "    openModal('assignUserModal');\n" +
            "  }\n" +
            "  function openRenameDbModal(oldDb) {\n" +
            "    document.getElementById('renameOldDbInput').value = oldDb;\n" +
            "    document.getElementById('renameOldDbDisplay').value = oldDb;\n" +
            "    openModal('renameDbModal');\n" +
            "  }\n" +
            "  function toggleEntities(id) {\n" +
            "    var el = document.getElementById(id);\n" +
            "    el.style.display = (el.style.display === 'none' || el.style.display === '') ? 'block' : 'none';\n" +
            "  }\n" +
            "  function updatePayloadTemplate(engine, targetId) {\n" +
            "    var t = document.getElementById(targetId);\n" +
            "    if (!t) return;\n" +
            "    switch(engine) {\n" +
            "      case 'RECORDS': t.value = '{\"_recordClass\": \"com.enterprise.model.EmployeeRecord\", \"_schema\": {\"id\":\"String\", \"fullName\":\"String\", \"salary\":\"Double\"}, \"components\": {\"id\": \"emp_101\", \"fullName\": \"Carlos Mendez\", \"salary\": 95000.0}}'; break;\n" +
            "      case 'DOCUMENT': t.value = '{\"name\": \"Enterprise Doc\", \"active\": true, \"tier\": \"Premium\"}'; break;\n" +
            "      case 'VECTOR': t.value = '{\"coords\": [0.12, 0.45, 0.88, 0.31], \"meta\": {\"title\": \"Paper Embedding\"}}'; break;\n" +
            "      case 'GRAPH': t.value = '{\"label\": \"ServerNode\", \"properties\": {\"ip\": \"192.168.1.100\", \"status\": \"ACTIVE\"}}'; break;\n" +
            "      case 'TIMESERIES': t.value = '{\"value\": 42.50, \"unit\": \"celsius\", \"tags\": {\"host\": \"server-01\"}}'; break;\n" +
            "      case 'COLUMN': t.value = '{\"col1\": \"val1\", \"col2\": 100, \"status\": \"OK\"}'; break;\n" +
            "      case 'KEYVALUE': t.value = 'raw_string_or_json_payload'; break;\n" +
            "      case 'GEOSPATIAL': t.value = '{\"lat\": 8.9824, \"lon\": -79.5199, \"name\": \"Hub Panama\"}'; break;\n" +
            "      case 'OBJECT': t.value = '{\"class\": \"BlobObject\", \"data\": \"base64_or_stream\"}'; break;\n" +
            "    }\n" +
            "  }\n" +
            "</script>\n"
        );

        return Column.of(
            titleBlock,
            alertWidget,
            statGrid,
            databasesContainer,
            createDbModal,
            addComponentModal,
            assignUserModal,
            renameDbModal,
            scriptsWidget
        );
    }

    private int renameDatabase(String oldDb, String newDb) {
        if (oldDb == null || newDb == null || oldDb.equalsIgnoreCase(newDb)) return 0;
        String cleanNewDb = newDb.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        int count = 0;
        for (String p : prefixes) {
            String dbPrefix = p + oldDb + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPrefix);
            for (Map.Entry<String, byte[]> e : keys.entrySet()) {
                String oldKey = e.getKey();
                String keyId = oldKey.substring(dbPrefix.length());
                String newKey = p + cleanNewDb + ":" + keyId;
                engine.getStorageCore().put(newKey, e.getValue(), System.currentTimeMillis());
                engine.getStorageCore().delete(oldKey, System.currentTimeMillis());
                count++;
            }
        }
        return count;
    }

    private Map<String, DatabaseMetadata> discoverDatabases() {
        Map<String, DatabaseMetadata> databases = new LinkedHashMap<>();

        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(p);
            String engineName = getEngineNameForPrefix(p);

            for (String k : keys.keySet()) {
                String rest = k.substring(p.length());
                int colonIdx = rest.indexOf(':');
                String dbName = colonIdx > 0 ? rest.substring(0, colonIdx) : "default";

                DatabaseMetadata meta = databases.computeIfAbsent(dbName, DatabaseMetadata::new);
                meta.incrementEngine(engineName);
            }
        }
        return databases;
    }

    private List<EntityDetail> getEntitiesForDatabase(String targetDb) {
        List<EntityDetail> list = new ArrayList<>();
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:"};
        for (String p : prefixes) {
            String dbPrefix = p + targetDb + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPrefix);
            String eng = getEngineNameForPrefix(p);

            for (Map.Entry<String, byte[]> e : keys.entrySet()) {
                String fullKey = e.getKey();
                String keyId = fullKey.substring(dbPrefix.length());
                String rawData = new String(e.getValue(), StandardCharsets.UTF_8);

                String typeClass = eng;
                if ("RECORDS".equals(eng) && rawData.contains("\"_recordClass\"")) {
                    int start = rawData.indexOf("\"_recordClass\":");
                    int quote1 = rawData.indexOf('"', start + 15);
                    int quote2 = rawData.indexOf('"', quote1 + 1);
                    if (quote1 > 0 && quote2 > quote1) {
                        typeClass = rawData.substring(quote1 + 1, quote2);
                    }
                }

                list.add(new EntityDetail(eng, keyId, fullKey, typeClass, rawData));
            }
        }
        return list;
    }

    private int purgeDatabase(String targetDb) {
        int count = 0;
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        for (String p : prefixes) {
            String dbPrefix = p + targetDb + ":";
            Map<String, byte[]> keys = engine.getStorageCore().scanPrefix(dbPrefix);
            for (String k : keys.keySet()) {
                engine.getStorageCore().delete(k, System.currentTimeMillis());
                count++;
            }
        }
        engine.getStorageCore().dropDatabase(targetDb);
        return count;
    }

    private String getPrefixForEngine(String engine) {
        return switch (engine.toUpperCase()) {
            case "RECORDS" -> "rec:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "KEYVALUE" -> "kv:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };
    }

    private String getEngineNameForPrefix(String p) {
        return switch (p) {
            case "rec:" -> "RECORDS";
            case "vec:" -> "VECTOR";
            case "graph:" -> "GRAPH";
            case "ts:" -> "TIMESERIES";
            case "col:" -> "COLUMN";
            case "kv:" -> "KEYVALUE";
            case "geo:" -> "GEOSPATIAL";
            case "obj:" -> "OBJECT";
            default -> "DOCUMENT";
        };
    }

    private String getBadgeStyleForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "background:rgba(244,63,94,0.15); color:#f43f5e; border:1px solid rgba(244,63,94,0.3);";
            case "DOCUMENT" -> "background:rgba(56,189,248,0.15); color:#38bdf8; border:1px solid rgba(56,189,248,0.3);";
            case "VECTOR" -> "background:rgba(168,85,247,0.15); color:#c084fc; border:1px solid rgba(168,85,247,0.3);";
            case "GRAPH" -> "background:rgba(16,185,129,0.15); color:#34d399; border:1px solid rgba(16,185,129,0.3);";
            case "TIMESERIES" -> "background:rgba(245,158,11,0.15); color:#fbbf24; border:1px solid rgba(245,158,11,0.3);";
            case "COLUMN" -> "background:rgba(6,182,212,0.15); color:#22d3ee; border:1px solid rgba(6,182,212,0.3);";
            case "KEYVALUE" -> "background:rgba(34,197,94,0.15); color:#4ade80; border:1px solid rgba(34,197,94,0.3);";
            case "GEOSPATIAL" -> "background:rgba(249,115,22,0.15); color:#fb923c; border:1px solid rgba(249,115,22,0.3);";
            default -> "background:rgba(99,102,241,0.15); color:#818cf8; border:1px solid rgba(99,102,241,0.3);";
        };
    }

    private String getIconForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "fas fa-id-card";
            case "DOCUMENT" -> "fas fa-file-alt";
            case "VECTOR" -> "fas fa-project-diagram";
            case "GRAPH" -> "fas fa-share-alt";
            case "TIMESERIES" -> "fas fa-chart-line";
            case "COLUMN" -> "fas fa-table";
            case "KEYVALUE" -> "fas fa-key";
            case "GEOSPATIAL" -> "fas fa-globe-americas";
            default -> "fas fa-archive";
        };
    }

    private String getDescForEngine(String eng) {
        return switch (eng.toUpperCase()) {
            case "RECORDS" -> "Java 25 Records";
            case "DOCUMENT" -> "NoSQL JSON";
            case "VECTOR" -> "AI ANN Vectors";
            case "GRAPH" -> "LPG Graph Nodes";
            case "TIMESERIES" -> "IoT Telemetry";
            case "COLUMN" -> "OLAP Columns";
            case "KEYVALUE" -> "Memory Cache";
            case "GEOSPATIAL" -> "2D GIS Spatial";
            default -> "Binary BLOBs";
        };
    }

    private Widget createStatCard(String icon, String color, String title, String value, String sub, String badgeClass) {
        return Div.of(
            Row.of(
                Div.of(Icon.of(icon).modifier(new Modifier().style("color:" + color + "; font-size:18px;")))
                    .modifier(new Modifier().style("width:36px; height:36px; border-radius:8px; background:" + color + "20; display:flex; align-items:center; justify-content:center;")),
                Span.of(title).modifier(new Modifier().style("font-size:13px; color:#94a3b8; font-weight:500;"))
            ).modifier(new Modifier().style("align-items:center; gap:10px; margin-bottom:10px;")),
            Div.of(Text.of(value)).modifier(new Modifier().style("font-size:22px; font-weight:700; color:#f8fafc; margin-bottom:4px;")),
            Row.of(
                Span.of(sub).modifier(new Modifier().style("font-size:12px; color:#cbd5e1;")),
                Span.of("ACTIVE").modifier(new Modifier().cssClass("store-badge " + badgeClass).style("font-size:10px;"))
            ).modifier(new Modifier().style("justify-content:space-between; align-items:center; margin-top:8px;"))
        ).modifier(new Modifier().cssClass("store-card"));
    }

    public static class EntityDetail {
        public final String engine;
        public final String keyId;
        public final String rawKey;
        public final String typeOrClass;
        public final String payloadPreview;

        public EntityDetail(String engine, String keyId, String rawKey, String typeOrClass, String payloadPreview) {
            this.engine = engine;
            this.keyId = keyId;
            this.rawKey = rawKey;
            this.typeOrClass = typeOrClass;
            this.payloadPreview = payloadPreview;
        }
    }

    public static class DatabaseMetadata {
        private final String name;
        private final Map<String, Integer> engineCounts = new LinkedHashMap<>();

        public DatabaseMetadata(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void addComponent(String engine, int count) {
            engineCounts.put(engine, count);
        }

        public void incrementEngine(String engine) {
            engineCounts.put(engine, engineCounts.getOrDefault(engine, 0) + 1);
        }

        public Map<String, Integer> getEngineCounts() {
            return engineCounts;
        }

        public int getTotalObjects() {
            return engineCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
