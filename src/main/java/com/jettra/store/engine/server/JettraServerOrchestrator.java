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
    private io.jettra.server.JettraServer jettraServer;
    private io.jettra.server.JettraServer jettraGuiServer;
    
    public JettraServerOrchestrator(JettraStorageEngine engine, int restPort, int grpcPort) {
        this(engine, restPort, grpcPort, 50050);
    }

    public JettraServerOrchestrator(JettraStorageEngine engine, int restPort, int grpcPort, int guiPort) {
        this.engine = engine;
        this.restPort = restPort;
        this.grpcPort = grpcPort;
        this.guiPort = guiPort;
        this.authManager = new AuthManager();
    }
    
    public void start() {
        System.out.println("Starting JettraServerOrchestrator...");
        System.out.println("REST API Port: " + restPort);
        System.out.println("GUI Web Port:  " + guiPort);
        System.out.println("gRPC Port:     " + grpcPort);
        
        // 1. Initialize Handlers and Pages
        StoreDashboardPage dashboardPage = new StoreDashboardPage(engine);
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
        jettraServer.addHandler("/api/document/", new DocumentRestController((DocumentEngine) engine.getEngine("DOCUMENT"), authManager));
        
        // Universal Model endpoint
        jettraServer.addHandler("/api/model/", new ModelRestController(engine, authManager));
        
        // Backup API
        jettraServer.addHandler("/api/backup", new BackupHandler(engine));
        
        // Also register Web Console on restPort
        jettraServer.addHandler("/", dashboardPage);
        jettraServer.addHandler("/dashboard", dashboardPage);
        jettraServer.addHandler("/wui", dashboardPage);
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
            jettraGuiServer.addHandler("/engines", enginesPage);
            jettraGuiServer.addHandler("/users", usersPage);
            jettraGuiServer.addHandler("/components", componentsPage);
            jettraGuiServer.addHandler("/login", loginPage);
            jettraGuiServer.addHandler("/swagger-ui", io.jettra.flux.complex.SwaggerUIPage.class);
            jettraGuiServer.start();
        }
        
        // Shell Startup Banner with Web Console URLs
        System.out.println();
        System.out.println("==================================================================================");
        System.out.println("                   JETTRA STORE ENGINE - WEB CONSOLE ACTIVE                       ");
        System.out.println("==================================================================================");
        System.out.println("  Web Management UI (GUI): http://localhost:" + guiPort + "/ (or /dashboard, /wui)");
        System.out.println("  Multi-Model Engines:     http://localhost:" + guiPort + "/engines");
        System.out.println("  Users & Security:        http://localhost:" + guiPort + "/users");
        System.out.println("  Cluster & Internals:     http://localhost:" + guiPort + "/components");
        System.out.println("  Swagger OpenAPI:         http://localhost:" + guiPort + "/swagger-ui");
        System.out.println("  --------------------------------------------------------------------------------");
        System.out.println("  REST Database API:       http://localhost:" + restPort + "/api/");
        System.out.println("  REST Multi-Model API:    http://localhost:" + restPort + "/api/model/");
        System.out.println("  REST Document API:       http://localhost:" + restPort + "/api/document/");
        System.out.println("  gRPC Cluster Port:       " + grpcPort);
        System.out.println("  Default Credentials:     admin / admin  (or super-user / superUserZ)");
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
