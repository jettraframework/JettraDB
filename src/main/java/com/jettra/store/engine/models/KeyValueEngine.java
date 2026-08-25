package com.jettra.store.engine.models;

import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.nio.charset.StandardCharsets;

public class KeyValueEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;

    public KeyValueEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
    }

    @Override
    public String getName() {
        return "KEYVALUE";
    }

    @Override
    public void init() {
        System.out.println("Initializing KeyValue Engine...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing KeyValue Engine...");
        raftClient.close();
    }

    public void put(String namespace, String key, String value) {
        String internalKey = "kv:" + namespace + ":" + key;
        engine.getStorageCore().put(internalKey, (value != null ? value : "").getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        String command = "PUT " + internalKey + " " + value;
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate kv data via Raft.");
        }
    }

    public String get(String namespace, String key) {
        String internalKey = "kv:" + namespace + ":" + key;
        byte[] payload = engine.getStorageCore().get(internalKey);
        if (payload != null && payload.length > 0) {
            return new String(payload, StandardCharsets.UTF_8);
        }
        return null;
    }

    public void delete(String namespace, String key) {
        String internalKey = "kv:" + namespace + ":" + key;
        engine.getStorageCore().delete(internalKey, System.currentTimeMillis());
        String command = "PUT " + internalKey + " ";
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate kv deletion via Raft.");
        }
    }

    public java.util.Map<String, String> list(String namespace) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        String prefix = "kv:" + namespace + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String k = entry.getKey().substring(prefix.length());
            map.put(k, new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return map;
    }
}
