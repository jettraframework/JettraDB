package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.nio.charset.StandardCharsets;

public class ColumnEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;

    public ColumnEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
    }

    @Override
    public String getName() {
        return "COLUMN";
    }

    @Override
    public void init() {
        System.out.println("Initializing Column Engine...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing Column Engine...");
        raftClient.close();
    }

    public void insertRow(String columnFamily, String rowKey, JsonObject columns) {
        String internalKey = "col:" + columnFamily + ":" + rowKey;
        String jsonString = gson.toJson(columns);
        engine.getStorageCore().put(internalKey, jsonString.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        String command = "PUT " + internalKey + " " + jsonString;
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate column data via Raft.");
        }
    }

    public JsonObject getRow(String columnFamily, String rowKey) {
        String internalKey = "col:" + columnFamily + ":" + rowKey;
        byte[] payload = engine.getStorageCore().get(internalKey);
        if (payload != null && payload.length > 0) {
            return gson.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        }
        return null;
    }

    public void deleteRow(String columnFamily, String rowKey) {
        String internalKey = "col:" + columnFamily + ":" + rowKey;
        engine.getStorageCore().delete(internalKey, System.currentTimeMillis());
        String command = "PUT " + internalKey + " ";
        raftClient.sendCommand(command);
    }

    public java.util.Map<String, JsonObject> list(String columnFamily) {
        java.util.Map<String, JsonObject> rows = new java.util.LinkedHashMap<>();
        String prefix = "col:" + columnFamily + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String rk = entry.getKey().substring(prefix.length());
            try {
                JsonObject obj = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                rows.put(rk, obj);
            } catch (Exception ignored) {}
        }
        return rows;
    }
}
