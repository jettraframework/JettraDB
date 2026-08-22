package com.jettra.store.engine;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.GraphEngine;
import com.jettra.store.engine.models.TimeSeriesEngine;
import com.jettra.store.engine.models.VectorEngine;
import com.jettra.store.engine.models.ColumnEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.GeospatialEngine;
import com.jettra.store.engine.models.ObjectEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.server.JettraServerOrchestrator;
import com.jettra.store.engine.core.BackupManager;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the JettraStoreEngine application.
 */
public class App {
    
    public static void main(String[] args) {
        System.out.println("Initializing JettraStoreEngine...");
        
        // Load Config
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream input = new java.io.FileInputStream("jettrastoreengine.properties")) {
            props.load(input);
        } catch (java.io.IOException e) {
            System.out.println("No jettrastoreengine.properties found, falling back to ENV.");
        }
        
        // 1. Initialize Storage
        String storagePath = props.getProperty("jettra.data.dir", System.getenv().getOrDefault("JETTRA_DATA_DIR", "./data"));
        
        if (storagePath.startsWith("~/")) {
            storagePath = System.getProperty("user.home") + storagePath.substring(1);
        }
        
        Path dataDir = Path.of(storagePath);
        if (!java.nio.file.Files.exists(dataDir)) {
            System.out.println("El directorio de datos no existe. Creando automáticamente: " + storagePath);
            try {
                java.nio.file.Files.createDirectories(dataDir);
            } catch (java.io.IOException e) {
                System.err.println("Error al crear el directorio de datos " + storagePath + ": " + e.getMessage());
                System.err.println("Por favor verifique que tiene los permisos necesarios o cambie la ruta en jettra.data.dir.");
            }
        }
        
        JettraStorageEngine storageEngine = new JettraStorageEngine(storagePath);
        
        storageEngine.registerEngine("DOCUMENT", new DocumentEngine(storageEngine));
        storageEngine.registerEngine("VECTOR", new VectorEngine(storageEngine));
        storageEngine.registerEngine("GRAPH", new GraphEngine(storageEngine));
        storageEngine.registerEngine("TIMESERIES", new TimeSeriesEngine(storageEngine));
        storageEngine.registerEngine("COLUMN", new ColumnEngine(storageEngine));
        storageEngine.registerEngine("KEYVALUE", new KeyValueEngine(storageEngine));
        storageEngine.registerEngine("GEOSPATIAL", new GeospatialEngine(storageEngine));
        storageEngine.registerEngine("OBJECT", new ObjectEngine(storageEngine));
        storageEngine.registerEngine("RECORDS", new RecordsEngine(storageEngine));
        
        boolean autoRestore = Boolean.parseBoolean(props.getProperty("store.restore.auto", "false"));
        if (autoRestore) {
            System.out.println("Auto-restore is enabled. Attempting to restore latest backup...");
            BackupManager backupManager = new BackupManager(Path.of(storagePath));
            backupManager.restoreLatestBackup();
        }
        
        storageEngine.start();
        
        boolean backupEnabled = Boolean.parseBoolean(props.getProperty("store.backup.enabled", "false"));
        ScheduledExecutorService backupExecutor = null;
        if (backupEnabled) {
            int backupIntervalMinutes = Integer.parseInt(props.getProperty("store.backup.interval.minutes", "1440"));
            System.out.println("Auto-backup is enabled. Interval: " + backupIntervalMinutes + " minutes.");
            backupExecutor = Executors.newSingleThreadScheduledExecutor();
            final BackupManager backupManager = new BackupManager(Path.of(storagePath));
            backupExecutor.scheduleAtFixedRate(
                backupManager::createBackup,
                backupIntervalMinutes,
                backupIntervalMinutes,
                TimeUnit.MINUTES
            );
        }
        
        // ---------------------------------------------------------
        // BOOTSTRAP & SEED DEMO MULTI-MODEL DATABASES
        // ---------------------------------------------------------
        try {
            System.out.println("[Bootstrap] Initializing demo databases & components...");
            
            // 1. Records Engine Demo Database
            RecordsEngine recordsEngine = (RecordsEngine) storageEngine.getEngine("RECORDS");
            if (recordsEngine != null) {
                io.jettra.json.JsonObject empRecord = new io.jettra.json.JsonObject();
                empRecord.addProperty("id", "emp_101");
                empRecord.addProperty("fullName", "Carlos Mendez");
                empRecord.addProperty("department", "Core Engineering");
                empRecord.addProperty("salary", 95000.0);
                empRecord.addProperty("active", true);
                recordsEngine.saveRecord("records_db", "emp_101", "com.enterprise.model.EmployeeRecord", empRecord);

                io.jettra.json.JsonObject custRecord = new io.jettra.json.JsonObject();
                custRecord.addProperty("id", "cust_201");
                custRecord.addProperty("companyName", "Panama Global Logistics");
                custRecord.addProperty("tier", "ENTERPRISE");
                custRecord.addProperty("creditLimit", 500000.0);
                recordsEngine.saveRecord("records_db", "cust_201", "com.enterprise.model.CustomerRecord", custRecord);
                System.out.println("[Bootstrap] Seeded RECORDS engine: records_db [emp_101, cust_201]");
            }

            // 2. Document Engine Demo Database
            DocumentEngine docEngine = (DocumentEngine) storageEngine.getEngine("DOCUMENT");
            if (docEngine != null) {
                io.jettra.json.JsonObject prodDoc = new io.jettra.json.JsonObject();
                prodDoc.addProperty("sku", "SKU-9901");
                prodDoc.addProperty("title", "High-Performance Cloud Server");
                prodDoc.addProperty("price", 1299.99);
                prodDoc.addProperty("inStock", true);
                docEngine.insert("ecommerce_db", "default", "prod_9901", prodDoc);
                System.out.println("[Bootstrap] Seeded DOCUMENT engine: ecommerce_db [prod_9901]");
            }

            // 3. Vector Engine Demo Database
            VectorEngine vecEngine = (VectorEngine) storageEngine.getEngine("VECTOR");
            if (vecEngine != null) {
                io.jettra.json.JsonObject vecMeta = new io.jettra.json.JsonObject();
                vecMeta.addProperty("label", "Semantic Query Embedding");
                vecMeta.addProperty("model", "text-embedding-3-small");
                vecEngine.insertVector("ai_search_db", "vec_embedding_01", new float[]{0.15f, 0.88f, 0.42f, 0.91f}, vecMeta);
                System.out.println("[Bootstrap] Seeded VECTOR engine: ai_search_db [vec_embedding_01]");
            }
        } catch (Exception e) {
            System.err.println("[Bootstrap] Non-fatal seed note: " + e.getMessage());
        }
        // ---------------------------------------------------------
        
        // 2. Initialize Network Servers
        int restPort = Integer.parseInt(props.getProperty("jettra.node.port", System.getenv().getOrDefault("JETTRA_DB_PORT", "8086")));
        int guiPort = Integer.parseInt(props.getProperty("jettra.gui.port", System.getenv().getOrDefault("JETTRA_GUI_PORT", "50050")));
        int grpcPort = Integer.parseInt(props.getProperty("jettra.grpc.port", System.getenv().getOrDefault("JETTRA_GRPC_PORT", "50051")));
        
        JettraServerOrchestrator serverOrchestrator = new JettraServerOrchestrator(storageEngine, restPort, grpcPort, guiPort, props);
        serverOrchestrator.start();
        
        // 3. Register Shutdown Hook
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final ScheduledExecutorService finalBackupExecutor = backupExecutor;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (finalBackupExecutor != null) {
                finalBackupExecutor.shutdown();
            }
            serverOrchestrator.stop();
            storageEngine.stop();
            latch.countDown();
        }));
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
