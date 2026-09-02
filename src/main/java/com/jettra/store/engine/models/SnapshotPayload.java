package com.jettra.store.engine.models;

import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java 25 Sealed Interface for strongly-typed Snapshot Payloads.
 * Allows pattern matching in switch expressions to handle structured JSON, Key-Value pairs,
 * formatted raw text, and binary payloads safely and exhaustively.
 */
public sealed interface SnapshotPayload
    permits SnapshotPayload.StructuredJsonPayload,
            SnapshotPayload.KeyValuePayload,
            SnapshotPayload.RawTextPayload,
            SnapshotPayload.BinaryPayload {

    /**
     * Returns raw byte representation of the snapshot payload.
     */
    byte[] toBytes();

    /**
     * Returns human-readable summary or JSON representation.
     */
    String toDisplayString();

    /**
     * Returns compact preview snippet.
     */
    String toPreviewSnippet(int maxLength);

    /**
     * Structured JSON snapshot payload.
     */
    record StructuredJsonPayload(
        JsonObject jsonObject,
        Map<String, Object> attributes,
        int fieldCount
    ) implements SnapshotPayload {
        @Override
        public byte[] toBytes() {
            String s = jsonObject != null ? jsonObject.toString() : "{}";
            return s.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toDisplayString() {
            return jsonObject != null ? jsonObject.toString() : "{}";
        }

        @Override
        public String toPreviewSnippet(int maxLength) {
            String s = toDisplayString().replaceAll("[\\r\\n\\t]+", " ");
            return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
        }
    }

    /**
     * Key-Value snapshot payload.
     */
    record KeyValuePayload(
        String key,
        String value,
        String dataType
    ) implements SnapshotPayload {
        @Override
        public byte[] toBytes() {
            return (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toDisplayString() {
            return key + " = " + value;
        }

        @Override
        public String toPreviewSnippet(int maxLength) {
            String s = toDisplayString().replaceAll("[\\r\\n\\t]+", " ");
            return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
        }
    }

    /**
     * Raw Text snapshot payload.
     */
    record RawTextPayload(
        String rawText,
        String encoding
    ) implements SnapshotPayload {
        @Override
        public byte[] toBytes() {
            return (rawText != null ? rawText : "").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String toDisplayString() {
            return rawText != null ? rawText : "";
        }

        @Override
        public String toPreviewSnippet(int maxLength) {
            String s = (rawText != null ? rawText : "").replaceAll("[\\r\\n\\t]+", " ");
            return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
        }
    }

    /**
     * Binary snapshot payload.
     */
    record BinaryPayload(
        byte[] rawBytes,
        String mimeType
    ) implements SnapshotPayload {
        @Override
        public byte[] toBytes() {
            return rawBytes != null ? rawBytes : new byte[0];
        }

        @Override
        public String toDisplayString() {
            return "[Binary Data: " + (rawBytes != null ? rawBytes.length : 0) + " bytes, type=" + mimeType + "]";
        }

        @Override
        public String toPreviewSnippet(int maxLength) {
            return toDisplayString();
        }
    }

    /**
     * Factory method to automatically classify and construct typed SnapshotPayload from bytes.
     */
    static SnapshotPayload fromBytes(byte[] data, JettraJson jsonParser) {
        if (data == null || data.length == 0) {
            return new StructuredJsonPayload(new JsonObject(), Collections.emptyMap(), 0);
        }
        String text = new String(data, StandardCharsets.UTF_8).trim();

        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                JsonObject jo = jsonParser.fromJson(text, JsonObject.class);
                if (jo != null) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (String k : jo.keySet()) {
                        map.put(k, jo.get(k));
                    }
                    return new StructuredJsonPayload(jo, map, jo.keySet().size());
                }
            } catch (Exception ignored) {}
        }

        if (text.contains("=") && !text.contains("\n") && text.length() < 256) {
            String[] parts = text.split("=", 2);
            return new KeyValuePayload(parts[0].trim(), parts[1].trim(), "STRING");
        }

        return new RawTextPayload(text, "UTF-8");
    }

    /**
     * Pattern Matching utility to format snapshot payload into a standardized summary.
     */
    static String formatPayload(SnapshotPayload payload) {
        return switch (payload) {
            case StructuredJsonPayload s -> "JSON (" + s.fieldCount() + " fields): " + s.toPreviewSnippet(60);
            case KeyValuePayload kv -> "KV: " + kv.key() + " -> " + kv.toPreviewSnippet(50);
            case RawTextPayload r -> "TEXT (" + r.encoding() + "): " + r.toPreviewSnippet(60);
            case BinaryPayload b -> "BIN: " + b.rawBytes().length + " bytes (" + b.mimeType() + ")";
        };
    }
}
