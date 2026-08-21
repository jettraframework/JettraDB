package com.jettra.store.engine.server;

import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.jettra.store.engine.web.StoreComponentsPage;
import com.jettra.store.engine.web.StoreDashboardPage;
import com.jettra.store.engine.web.StoreDatabasesPage;
import com.jettra.store.engine.web.StoreEnginesPage;
import com.jettra.store.engine.web.StoreLoginPage;
import com.jettra.store.engine.web.StoreUsersPage;
import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrates the network interfaces for JettraStorageEngine.
 * Bootstraps JettraServer, mounts JettraRest and JettraGRPC endpoints,
 * and configures JettraJWT authentication and JettraFlux Web Console.
 */
public class JettraServerOrchestrator {
    
    private final JettraStorageEngine engine;
    private final int restPort;
    private final int grpcPort;
    private final int guiPort;
    private final AuthManager authManager;
    private final java.util.Properties props;
    private io.jettra.server.JettraServer jettraServer;
    private io.jettra.server.JettraServer jettraGuiServer;
    
    public JettraServerOrchestrator(JettraStorageEngine engine, int restPort, int grpcPort) {
        this(engine, restPort, grpcPort, 50050, new java.util.Properties());
    }

    public JettraServerOrchestrator(JettraStorageEngine engine, int restPort, int grpcPort, int guiPort) {
        this(engine, restPort, grpcPort, guiPort, new java.util.Properties());
    }

    public JettraServerOrchestrator(JettraStorageEngine engine, int restPort, int grpcPort, int guiPort, java.util.Properties props) {
        this.engine = engine;
        this.restPort = restPort;
        this.grpcPort = grpcPort;
        this.guiPort = guiPort;
        this.props = props != null ? props : new java.util.Properties();
        this.authManager = new AuthManager();
    }
    
    public void start() {
        System.out.println("Starting JettraServerOrchestrator...");
        System.out.println("REST API Port: " + restPort);
        System.out.println("GUI Web Port:  " + guiPort);
        System.out.println("gRPC Port:     " + grpcPort);
        
        // 1. Initialize Handlers and Pages
        StoreDashboardPage dashboardPage = new StoreDashboardPage(engine);
        StoreDatabasesPage databasesPage = new StoreDatabasesPage(engine, authManager);
        StoreEnginesPage enginesPage = new StoreEnginesPage(engine);
        StoreUsersPage usersPage = new StoreUsersPage(engine, authManager);
        StoreComponentsPage componentsPage = new StoreComponentsPage(engine);
        StoreLoginPage loginPage = new StoreLoginPage(authManager);

        // 2. Initialize REST API Server instance
        jettraServer = new io.jettra.server.JettraServer();
        jettraServer.setPort(restPort);
        
        // Configure JettraJWT / Auth for admin/admin bootstrap
        jettraServer.addHandler("/api/auth/login", new AuthRestController(authManager));
        
        // Mount JettraRest controllers mapped to JettraStorageEngine operations
        jettraServer.addHandler("/api/document/", new DocumentRestController((DocumentEngine) engine.getEngine("DOCUMENT"), engine, authManager));
        
        // Universal Model endpoint
        jettraServer.addHandler("/api/model/", new ModelRestController(engine, authManager));
        
        // Backup API
        jettraServer.addHandler("/api/backup", new BackupHandler(engine));
        
        // Also register Web Console on restPort
        jettraServer.addHandler("/", dashboardPage);
        jettraServer.addHandler("/dashboard", dashboardPage);
        jettraServer.addHandler("/wui", dashboardPage);
        jettraServer.addHandler("/databases", databasesPage);
        jettraServer.addHandler("/engines", enginesPage);
        jettraServer.addHandler("/users", usersPage);
        jettraServer.addHandler("/components", componentsPage);
        jettraServer.addHandler("/login", loginPage);
        jettraServer.addHandler("/swagger-ui", io.jettra.flux.complex.SwaggerUIPage.class);

        // Start REST API server
        jettraServer.start();
        
        // 3. Initialize and Serve JettraFlux Web Management Console on guiPort
        if (guiPort == restPort) {
            jettraGuiServer = jettraServer;
        } else {
            jettraGuiServer = new io.jettra.server.JettraServer();
            jettraGuiServer.setPort(guiPort);
            jettraGuiServer.addHandler("/", dashboardPage);
            jettraGuiServer.addHandler("/dashboard", dashboardPage);
            jettraGuiServer.addHandler("/wui", dashboardPage);
            jettraGuiServer.addHandler("/databases", databasesPage);
            jettraGuiServer.addHandler("/engines", enginesPage);
            jettraGuiServer.addHandler("/users", usersPage);
            jettraGuiServer.addHandler("/components", componentsPage);
            jettraGuiServer.addHandler("/login", loginPage);
            jettraGuiServer.addHandler("/swagger-ui", io.jettra.flux.complex.SwaggerUIPage.class);
            jettraGuiServer.start();
        }
        
        // Shell Startup Banner with Web Console URLs and Properties Report
        String nodeId = props.getProperty("jettra.node.id", "node1");
        String dataDir = props.getProperty("jettra.data.dir", engine.getStorageDir().toString());
        String peers = props.getProperty("jettra.cluster.peers", "127.0.0.1:" + grpcPort);
        String restoreAuto = props.getProperty("store.restore.auto", "false");
        String backupEnabled = props.getProperty("store.backup.enabled", "false");
        String backupInterval = props.getProperty("store.backup.interval.minutes", "1440");

        System.out.println();
        System.out.println("==================================================================================");
        System.out.println("                   JETTRA STORE ENGINE - WEB CONSOLE ACTIVE                       ");
        System.out.println("==================================================================================");
        System.out.println("  [Configured Properties (jettrastoreengine.properties)]:");
        System.out.printf("  • Node ID (jettra.node.id):                 %s%n", nodeId);
        System.out.printf("  • Data Directory (jettra.data.dir):         %s%n", dataDir);
        System.out.printf("  • REST Database Port (jettra.node.port):    %d%n", restPort);
        System.out.printf("  • Web Management Port (jettra.gui.port):    %d%n", guiPort);
        System.out.printf("  • gRPC / Consensus Port (jettra.grpc.port): %d%n", grpcPort);
        System.out.printf("  • Cluster Peers (jettra.cluster.peers):     %s%n", peers);
        System.out.printf("  • Auto-Restore (store.restore.auto):        %s%n", restoreAuto);
        System.out.printf("  • Auto-Backup (store.backup.enabled):       %s (Interval: %s min)%n", backupEnabled, backupInterval);
        System.out.println("  --------------------------------------------------------------------------------");
        System.out.println("  [Web Management & Console URLs]:");
        System.out.printf("  • Web Management UI (GUI):                  http://localhost:%d/ (or /dashboard)%n", guiPort);
        System.out.printf("  • Multi-Model Database Engines:             http://localhost:%d/engines%n", guiPort);
        System.out.printf("  • Users & Security (Per-Database RBAC):     http://localhost:%d/users%n", guiPort);
        System.out.printf("  • Cluster Topology & Internals:             http://localhost:%d/components%n", guiPort);
        System.out.printf("  • Swagger OpenAPI Explorer:                 http://localhost:%d/swagger-ui%n", guiPort);
        System.out.println("  --------------------------------------------------------------------------------");
        System.out.println("  [REST Database APIs]:");
        System.out.printf("  • REST Universal Multi-Model API:           http://localhost:%d/api/model/%n", restPort);
        System.out.printf("  • REST Document Engine API:                 http://localhost:%d/api/document/%n", restPort);
        System.out.println("  • Default Admin Credentials:                admin / admin  (or super-user / superUserZ)");
        System.out.println("==================================================================================");
        System.out.println();
        
        // TODO: Mount JettraGRPC services
    }
    
    public void stop() {
        System.out.println("Stopping JettraServerOrchestrator...");
        if (jettraServer != null) {
            jettraServer.stop();
        }
        if (jettraGuiServer != null && jettraGuiServer != jettraServer) {
            jettraGuiServer.stop();
        }
    }

    private static class BackupHandler implements HttpHandler {
        private final JettraStorageEngine engine;
        public BackupHandler(JettraStorageEngine engine) {
            this.engine = engine;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                com.jettra.store.engine.core.BackupManager backupManager = new com.jettra.store.engine.core.BackupManager(engine.getStorageDir());
                backupManager.createBackup();
                String resp = "{\"status\":\"Backup initiated\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp.getBytes());
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
}
