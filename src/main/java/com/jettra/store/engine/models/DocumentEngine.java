package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.IdGenerator.IdMode;
import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.rules.core.JettraRulesEngine;
import io.jettra.rules.core.RuleResult;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles Document-oriented storage (JSON-like).
 * Supports advanced Jettra references, embedded documents, annotations validation,
 * and multi-mode identifier generation (Manual, Auto-increment, Composite UUID).
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
    
    public String insert(String database, String documentId, JsonObject document) {
        return insert(database, "default", documentId, document, IdMode.MANUAL);
    }

    public String insert(String database, String collection, String documentId, JsonObject document) {
        return insert(database, collection, documentId, document, IdMode.MANUAL);
    }

    public String insert(String database, String collection, String documentId, JsonObject document, IdMode idMode) {
        validateDocument(document);
        
        String resolvedId = IdGenerator.generateId(database + ":" + collection, idMode, documentId);
        String key = database + ":" + collection + ":" + resolvedId;
        String jsonString = gson.toJson(document);
        
        // 1. Direct Local Storage Core persistence
        engine.getStorageCore().put(key, jsonString.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        // 2. Replicate write through Raft Consensus
        String command = "PUT " + key + " " + jsonString;
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Document inserted locally, Raft replication pending for key: " + key);
        }

        return resolvedId;
    }

    public JsonObject get(String database, String documentId) {
        JsonObject obj = get(database, "default", documentId);
        if (obj != null) return obj;
        String prefix = database + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String fullKey = entry.getKey();
            if (fullKey.endsWith(":" + documentId)) {
                try {
                    String jsonStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (!jsonStr.isBlank() && !jsonStr.equals("__TOMBSTONE__")) {
                        return gson.fromJson(jsonStr, JsonObject.class);
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
    
    public JsonObject get(String database, String collection, String documentId) {
        String key = database + ":" + collection + ":" + documentId;
        byte[] payload = engine.getStorageCore().get(key);
        if (payload != null && payload.length > 0) {
            String jsonString = new String(payload, StandardCharsets.UTF_8);
            if (!jsonString.isBlank() && !jsonString.equals("__TOMBSTONE__")) {
                return gson.fromJson(jsonString, JsonObject.class);
            }
        }
        return null;
    }
    
    public void delete(String database, String documentId) {
        delete(database, "default", documentId);
        String prefix = database + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String fullKey = entry.getKey();
            if (fullKey.endsWith(":" + documentId)) {
                engine.getStorageCore().delete(fullKey, System.currentTimeMillis());
            }
        }
    }

    public void delete(String database, String collection, String documentId) {
        String key = database + ":" + collection + ":" + documentId;
        // 1. Local delete
        engine.getStorageCore().delete(key, System.currentTimeMillis());

        // 2. Replicate delete through Raft
        String command = "PUT " + key + " __TOMBSTONE__";
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Document deleted locally, Raft tombstone replication pending for key: " + key);
        }
    }

    public java.util.Map<String, JsonObject> list(String database) {
        java.util.Map<String, JsonObject> docs = new java.util.LinkedHashMap<>();
        String prefix = database + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String fullKey = entry.getKey();
            if (fullKey.startsWith(prefix)) {
                String rest = fullKey.substring(prefix.length());
                int lastColon = rest.lastIndexOf(':');
                String docId = lastColon >= 0 ? rest.substring(lastColon + 1) : rest;
                try {
                    String jsonStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (!jsonStr.isBlank() && !jsonStr.equals("__TOMBSTONE__")) {
                        JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                        docs.put(docId, obj);
                    }
                } catch (Exception ignored) {}
            }
        }
        return docs;
    }

    public java.util.Map<String, JsonObject> list(String database, String collection) {
        java.util.Map<String, JsonObject> docs = new java.util.LinkedHashMap<>();
        String prefix = database + ":" + collection + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String fullKey = entry.getKey();
            if (fullKey.startsWith(prefix)) {
                String docId = fullKey.substring(prefix.length());
                try {
                    String jsonStr = new String(entry.getValue(), StandardCharsets.UTF_8);
                    if (!jsonStr.isBlank() && !jsonStr.equals("__TOMBSTONE__")) {
                        JsonObject obj = gson.fromJson(jsonStr, JsonObject.class);
                        docs.put(docId, obj);
                    }
                } catch (Exception ignored) {}
            }
        }
        return docs;
    }
    
    private void validateDocument(JsonObject document) {
        if (document != null && document.has("_class")) {
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
