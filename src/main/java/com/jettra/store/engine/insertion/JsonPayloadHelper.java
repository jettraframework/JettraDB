package com.jettra.store.engine.insertion;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Robust JSON and primitive parsing utilities for insertion strategies.
 */
public final class JsonPayloadHelper {

    private static final JettraJson JSON = new JettraJson();

    private JsonPayloadHelper() {}

    public static JsonObject parseJsonOrWrap(String raw, String defaultKey) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                Object obj = JSON.fromJson(trimmed, JsonObject.class);
                if (obj instanceof JsonObject jo) return jo;
            }
        } catch (Exception ignored) {}

        JsonObject fallback = new JsonObject();
        fallback.addProperty(defaultKey != null ? defaultKey : "value", trimmed);
        return fallback;
    }

    public static JsonObject parseJsonOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            Object obj = JSON.fromJson(raw.trim(), JsonObject.class);
            if (obj instanceof JsonObject jo) return jo;
        } catch (Exception ignored) {}
        return new JsonObject();
    }

    public static float[] parseFloats(String raw) {
        if (raw == null || raw.isBlank()) return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        String clean = raw.replaceAll("[\\[\\](){}]", "").trim();
        String[] parts = clean.split("[,;\\s]+");
        List<Float> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                try {
                    list.add(Float.parseFloat(p.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (list.isEmpty()) return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public static String formatJsonPretty(JsonObject obj) {
        if (obj == null) return "{\n}";
        try {
            return JSON.toJson(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
