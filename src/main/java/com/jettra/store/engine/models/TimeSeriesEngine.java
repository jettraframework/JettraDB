package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.nio.charset.StandardCharsets;

public class TimeSeriesEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;

    public TimeSeriesEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
    }

    @Override
    public String getName() {
        return "TIMESERIES";
    }

    @Override
    public void init() {
        System.out.println("Initializing TimeSeries Engine...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing TimeSeries Engine...");
        raftClient.close();
    }
    
    /**
     * Inserts a data point. The ID is automatically generated based on timestamp to optimize for range queries.
     */
    public void insert(String measurement, long timestamp, JsonObject dataPoint) {
        String key = "ts:" + measurement + ":" + timestamp;
        
        // Add timestamp inside payload too
        dataPoint.addProperty("timestamp", timestamp);
        String jsonString = gson.toJson(dataPoint);
        
        // Send to Raft Consensus
        String command = "PUT " + key + " " + jsonString;
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate timeseries data via Raft.");
        }
    }
    
    /**
     * Fallback local get for exact timestamp. In a real time-series, range queries are preferred.
     */
    public JsonObject get(String measurement, long timestamp) {
        String key = "ts:" + measurement + ":" + timestamp;
        byte[] payload = engine.getStorageCore().get(key);
        if (payload != null) {
            String jsonString = new String(payload, StandardCharsets.UTF_8);
            return gson.fromJson(jsonString, JsonObject.class);
        }
        return null;
    }

    public void delete(String measurement, long timestamp) {
        String key = "ts:" + measurement + ":" + timestamp;
        engine.getStorageCore().delete(key, System.currentTimeMillis());
        String command = "PUT " + key + " ";
        raftClient.sendCommand(command);
    }

    public java.util.Map<String, JsonObject> list(String measurement) {
        java.util.Map<String, JsonObject> points = new java.util.LinkedHashMap<>();
        String prefix = "ts:" + measurement + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String tsKey = entry.getKey().substring(prefix.length());
            try {
                JsonObject obj = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                points.put(tsKey, obj);
            } catch (Exception ignored) {}
        }
        return points;
    }
}
