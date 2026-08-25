package com.jettra.store.engine.models;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.cluster.JettraConsensusClient;
import com.jettra.store.engine.core.EngineFamily;
import com.jettra.store.engine.core.JettraStorageEngine;
import java.nio.charset.StandardCharsets;

public class GeospatialEngine implements EngineFamily {

    private final JettraStorageEngine engine;
    private final JettraConsensusClient raftClient;
    private final JettraJson gson;

    public GeospatialEngine(JettraStorageEngine engine) {
        this.engine = engine;
        this.raftClient = new JettraConsensusClient();
        this.gson = new JettraJson();
    }

    @Override
    public String getName() {
        return "GEOSPATIAL";
    }

    @Override
    public void init() {
        System.out.println("Initializing Geospatial Engine...");
        raftClient.init();
    }

    @Override
    public void close() {
        System.out.println("Closing Geospatial Engine...");
        raftClient.close();
    }

    public void insertLocation(String collection, String locId, double lat, double lon, JsonObject metadata) {
        String internalKey = "geo:" + collection + ":" + locId;
        JsonObject doc = new JsonObject();
        JsonObject coords = new JsonObject();
        coords.addProperty("lat", lat);
        coords.addProperty("lon", lon);
        doc.add("coordinates", coords);
        if (metadata != null) {
            doc.add("metadata", metadata);
        }
        
        String command = "PUT " + internalKey + " " + gson.toJson(doc);
        boolean success = raftClient.sendCommand(command);
        if (!success) {
            System.err.println("Failed to replicate geo data via Raft.");
        }
    }

    public JsonObject getLocation(String collection, String locId) {
        String internalKey = "geo:" + collection + ":" + locId;
        byte[] payload = engine.getStorageCore().get(internalKey);
        if (payload != null && payload.length > 0) {
            return gson.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        }
        return null;
    }

    public void deleteLocation(String collection, String locId) {
        String internalKey = "geo:" + collection + ":" + locId;
        engine.getStorageCore().delete(internalKey, System.currentTimeMillis());
        String command = "PUT " + internalKey + " ";
        raftClient.sendCommand(command);
    }

    public java.util.Map<String, JsonObject> list(String collection) {
        java.util.Map<String, JsonObject> locs = new java.util.LinkedHashMap<>();
        String prefix = "geo:" + collection + ":";
        java.util.Map<String, byte[]> raw = engine.getStorageCore().scanPrefix(prefix);
        for (java.util.Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String locId = entry.getKey().substring(prefix.length());
            try {
                JsonObject obj = gson.fromJson(new String(entry.getValue(), StandardCharsets.UTF_8), JsonObject.class);
                locs.put(locId, obj);
            } catch (Exception ignored) {}
        }
        return locs;
    }

    /**
     * Calculates the great-circle distance between two points in Kilometers (Haversine formula).
     */
    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
