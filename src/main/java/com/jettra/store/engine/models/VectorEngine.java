package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonArray;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VectorEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;
    private final Map<String, float[]> vectorIndex;

    public VectorEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
        this.vectorIndex = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return "VECTOR";
    }

    @Override
    public void init() {
        System.out.println("Initializing Vector Engine (Custom implementation)...");
        raftClient.init();
        // In a real scenario, we would load existing vectors from disk into memory here
    }

    @Override
    public void close() {
        System.out.println("Closing Vector Engine...");
        raftClient.close();
    }
    
    public void insertVector(String collection, String vectorId, float[] vectorData, JsonObject metadata) {
        String key = "vec:" + collection + ":" + vectorId;
        JsonObject document = new JsonObject();
        
        JsonArray vecArray = new JsonArray();
        for (float v : vectorData) {
            vecArray.add(v);
        }
        document.add("vector", vecArray);
        
        if (metadata != null) {
            document.add("metadata", metadata);
        }
        
        String jsonString = gson.toJson(document);
        String command = "PUT " + key + " " + jsonString;
        
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate vector data via Raft.");
        } else {
            // Add to in-memory index if successful
            vectorIndex.put(key, vectorData);
        }
    }
    
    public JsonObject getVector(String collection, String vectorId) {
        String key = "vec:" + collection + ":" + vectorId;
        byte[] payload = engine.getStorageCore().get(key);
        if (payload != null && payload.length > 0) {
            return gson.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        }
        return null;
    }

    public List<JsonObject> searchVector(String collection, float[] queryVector, int topK) {
        String prefix = "vec:" + collection + ":";
        List<SearchResult> results = new ArrayList<>();

        // Scan the in-memory index
        for (Map.Entry<String, float[]> entry : vectorIndex.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                float similarity = calculateCosineSimilarity(queryVector, entry.getValue());
                results.add(new SearchResult(entry.getKey(), similarity));
            }
        }

        // Sort by similarity descending
        results.sort(Comparator.comparingDouble(SearchResult::getSimilarity).reversed());

        // Get top K results and fetch full documents
        List<JsonObject> topResults = new ArrayList<>();
        int limit = Math.min(topK, results.size());
        for (int i = 0; i < limit; i++) {
            String key = results.get(i).getKey();
            byte[] payload = engine.getStorageCore().get(key);
            if (payload != null && payload.length > 0) {
                JsonObject doc = gson.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
                doc.addProperty("_similarity", results.get(i).getSimilarity());
                topResults.add(doc);
            }
        }
        return topResults;
    }

    public void deleteVector(String collection, String vectorId) {
        String key = "vec:" + collection + ":" + vectorId;
        String command = "PUT " + key + " ";
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate vector delete via Raft.");
        } else {
            vectorIndex.remove(key);
        }
    }

    public java.util.Map<String, JsonObject> list(String collection) {
        java.util.Map<String, JsonObject> map = new java.util.LinkedHashMap<>();
        String prefix = "vec:" + collection + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String id = entry.getKey().substring(prefix.length());
            try {
                JsonObject doc = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                map.put(id, doc);
            } catch (Exception ignored) {}
        }
        return map;
    }

    private float calculateCosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length != vectorB.length) {
            return 0.0f; // Dimension mismatch or null
        }
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f; // Avoid division by zero
        }
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static class SearchResult {
        private final String key;
        private final float similarity;

        public SearchResult(String key, float similarity) {
            this.key = key;
            this.similarity = similarity;
        }

        public String getKey() {
            return key;
        }

        public float getSimilarity() {
            return similarity;
        }
    }
}
