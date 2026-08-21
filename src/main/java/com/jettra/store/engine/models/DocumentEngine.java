package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles Document-oriented storage (JSON-like).
 * Supports advanced Jettra references, embedded documents, and annotations validation.
 */
public class DocumentEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;

    public DocumentEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
    }

    @Override
    public String getName() {
        return "DOCUMENT";
    }

    @Override
    public void init() {
        System.out.println("Initializing Document Engine (JSON storage with validation)...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing Document Engine...");
        raftClient.close();
    }
    
    // Document Operations
    
    public void insert(String collection, String documentId, JsonObject document) {
        validateDocument(document);
        
        String key = collection + ":" + documentId;
        String jsonString = gson.toJson(document);
        
        // Route write through Raft Consensus
        String command = "PUT " + key + " " + jsonString;
        boolean success = raftClient.sendCommand(command);
        
        if (!success) {
            System.err.println("Failed to replicate insert operation via Raft.");
        }
    }
    
    public JsonObject get(String collection, String documentId) {
        String key = collection + ":" + documentId;
        byte[] payload = engine.getStorageCore().get(key);
        if (payload != null) {
            String jsonString = new String(payload, StandardCharsets.UTF_8);
            return gson.fromJson(jsonString, JsonObject.class);
        }
        return null;
    }
    
    public void delete(String collection, String documentId) {
        String key = collection + ":" + documentId;
        // Route soft delete through Raft
        String command = "PUT " + key + " ";
        boolean success = raftClient.sendCommand(command);
        
        if (!success) {
            System.err.println("Failed to replicate delete operation via Raft.");
        }
    }

    public java.util.Map<String, JsonObject> list(String collection) {
        java.util.Map<String, JsonObject> docs = new java.util.LinkedHashMap<>();
        String prefix = collection + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String docId = entry.getKey().substring(prefix.length());
            try {
                JsonObject obj = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                docs.put(docId, obj);
            } catch (Exception ignored) {}
        }
        return docs;
    }
    
    private void validateDocument(JsonObject document) {
        if (document.has("_class")) {
            try {
                String className = (String) document.get("_class");
                Class<?> clazz = Class.forName(className);
                Object obj = gson.fromJson(document.toString(), clazz);
                
                List<RuleResult> results = JettraRulesEngine.validate(obj);
                for (RuleResult res : results) {
                    if (!res.isValid()) {
                        throw new IllegalArgumentException("Validation failed for field " + res.getField() + ": " + res.getMessage());
                    }
                }
            } catch (ClassNotFoundException e) {
                System.out.println("Warning: Class " + document.get("_class") + " not found. Skipping JettraRules validation.");
            }
        }
    }
}
