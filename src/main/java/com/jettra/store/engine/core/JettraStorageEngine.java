package com.jettra.store.engine.core;

import java.nio.file.Path;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.jettra.store.engine.cluster.JettraConsensusServer;

/**
 * Core orchestrator for the Jettra Storage Engine.
 * Manages the initialization of the various multi-model engines (Document, Graph, Vector, etc.).
 * Persists data to .jettra files and operates using Java 25 Compact Object Headers.
 */
public class JettraStorageEngine {
    
    private final Path storageDir;
    private final Map<String, EngineFamily> registeredEngines;
    private LsmBTreeHybrid storageCore;
    private JettraConsensusServer raftOrchestrator;
    
    public JettraStorageEngine(String storagePath) {
        this.storageDir = Paths.get(storagePath);
        this.registeredEngines = new ConcurrentHashMap<>();
    }
    
    /**
     * Initializes the storage engine, loading any existing .jettra files
     * and bootstrapping the Raft consensus cluster if configured.
     */
    public void start() {
        System.out.println("Starting JettraStorageEngine at " + storageDir.toAbsolutePath());
        storageCore = new LsmBTreeHybrid(storageDir);
        
        try {
            raftOrchestrator = new JettraConsensusServer(this);
            raftOrchestrator.start();
        } catch (Exception e) {
            System.err.println("Failed to start Raft Server: " + e.getMessage());
        }
        
        for (EngineFamily engine : registeredEngines.values()) {
            engine.init();
        }
    }
    
    /**
     * Shuts down the storage engine cleanly, flushing memory-mapped buffers.
     */
    public void stop() {
        System.out.println("Shutting down JettraStorageEngine...");
        for (EngineFamily engine : registeredEngines.values()) {
            engine.close();
        }
        if (raftOrchestrator != null) {
            raftOrchestrator.stop();
        }
        if (storageCore != null) {
            storageCore.close();
        }
    }
    
    public void registerEngine(String name, EngineFamily engine) {
        registeredEngines.put(name, engine);
    }
    
    public EngineFamily getEngine(String name) {
        return registeredEngines.get(name);
    }
    
    public LsmBTreeHybrid getStorageCore() {
        return storageCore;
    }
    
    public Path getStorageDir() {
        return storageDir;
    }
}
