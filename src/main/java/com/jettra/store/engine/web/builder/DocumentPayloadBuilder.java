package com.jettra.store.engine.web.builder;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Builder Pattern implementation for constructing formatted entity payloads
 * across multi-model storage engines in JettraDB under Java 25+.
 */
public class DocumentPayloadBuilder {

    private final JettraJson jsonParser = new JettraJson();

    private String engineType = "DOCUMENT";
    private String collection = "default";
    private int version = 1;
    private String recordId = "";
    private String rawPayload = "{}";
    private Map<String, String> params = Collections.emptyMap();

    public static DocumentPayloadBuilder builder() {
        return new DocumentPayloadBuilder();
    }

    public DocumentPayloadBuilder version(int version) {
        if (version > 0) {
            this.version = version;
        }
        return this;
    }

    public DocumentPayloadBuilder engineType(String engineType) {
        if (engineType != null && !engineType.isBlank()) {
            this.engineType = engineType.toUpperCase();
        }
        return this;
    }

    public DocumentPayloadBuilder collection(String collection) {
        if (collection != null && !collection.isBlank()) {
            this.collection = collection;
        }
        return this;
    }

    public DocumentPayloadBuilder recordId(String recordId) {
        if (recordId != null) {
            this.recordId = recordId;
        }
        return this;
    }

    public DocumentPayloadBuilder rawPayload(String rawPayload) {
        if (rawPayload != null) {
            this.rawPayload = rawPayload;
        }
        return this;
    }

    public DocumentPayloadBuilder params(Map<String, String> params) {
        if (params != null) {
            this.params = params;
        }
        return this;
    }

    /**
     * Immutable result holding the formatted string and UTF-8 byte array.
     */
    public record BuiltPayload(String formattedJson, byte[] payloadBytes) {}

    public BuiltPayload build() {
        String eng = normalizeEngineType(this.engineType);
        String finalPayloadStr = formatByEngine(eng);
        byte[] bytes = finalPayloadStr.getBytes(StandardCharsets.UTF_8);
        return new BuiltPayload(finalPayloadStr, bytes);
    }

    private String normalizeEngineType(String type) {
        if ("RECORD".equalsIgnoreCase(type)) return "RECORDS";
        if ("KEY_VALUE".equalsIgnoreCase(type) || "KEY-VALUE".equalsIgnoreCase(type)) return "KEYVALUE";
        if ("TIME_SERIES".equalsIgnoreCase(type) || "TIMESERIE".equalsIgnoreCase(type)) return "TIMESERIES";
        if ("GEO".equalsIgnoreCase(type)) return "GEOSPATIAL";
        return type != null ? type.toUpperCase() : "DOCUMENT";
    }

    private String formatByEngine(String eng) {
        Map<String, String> p = this.params != null ? this.params : Map.of();
        String payload = this.rawPayload != null ? this.rawPayload : "{}";

        return switch (eng) {
            case "DOCUMENT" -> {
                String json = p.getOrDefault("doc_payload", payload);
                if ((json == null || json.isBlank() || "{}".equals(json.trim())) && p.containsKey("doc_json")) {
                    json = p.get("doc_json");
                }
                JsonObject doc = parseJsonOrWrap(json);
                String docClass = p.get("doc_class");
                if (docClass != null && !docClass.isBlank()) {
                    doc.addProperty("_class", docClass.trim());
                }
                int targetVer = this.version > 0 ? this.version : 2;
                if (p.containsKey("version")) {
                    try { targetVer = Integer.parseInt(p.get("version")); } catch (Exception ignored) {}
                }
                doc.addProperty("_version", targetVer);
                if (doc.has("version")) {
                    doc.addProperty("version", targetVer);
                }
                yield jsonParser.toJson(doc);
            }
            case "KEYVALUE" -> p.getOrDefault("kv_value", payload);
            case "VECTOR" -> {
                String metaStr = p.getOrDefault("vector_meta", payload);
                JsonObject vecObj = parseJsonOrWrap(metaStr);
                vecObj.addProperty("_index", this.collection != null ? this.collection : "default");
                yield jsonParser.toJson(vecObj);
            }
            case "GRAPH" -> {
                String nodeProps = p.getOrDefault("node_props", payload);
                JsonObject gObj = parseJsonOrWrap(nodeProps);
                String nodeLabel = p.getOrDefault("node_label", (this.collection != null && !this.collection.isBlank()) ? this.collection : "Vertex");
                gObj.addProperty("label", nodeLabel);
                yield jsonParser.toJson(gObj);
            }
            case "TIMESERIES" -> {
                String tagsStr = p.getOrDefault("ts_tags", payload);
                JsonObject tsObj = parseJsonOrWrap(tagsStr);
                if (p.containsKey("ts_value")) {
                    try { tsObj.addProperty("value", Double.parseDouble(p.get("ts_value"))); } catch (Exception ignored) {}
                }
                if (p.containsKey("ts_unit") && !p.get("ts_unit").isBlank()) {
                    tsObj.addProperty("unit", p.get("ts_unit"));
                }
                tsObj.addProperty("metric", this.collection != null ? this.collection : "telemetry");
                yield jsonParser.toJson(tsObj);
            }
            case "COLUMN" -> {
                String colData = p.getOrDefault("col_data", payload);
                JsonObject colObj = parseJsonOrColumns(colData);
                colObj.addProperty("_family", this.collection != null ? this.collection : "analytics");
                yield jsonParser.toJson(colObj);
            }
            case "GEOSPATIAL" -> {
                double lat = 8.9824;
                double lon = -79.5199;
                if (p.containsKey("geo_lat")) {
                    try { lat = Double.parseDouble(p.get("geo_lat")); } catch (Exception ignored) {}
                }
                if (p.containsKey("geo_lon")) {
                    try { lon = Double.parseDouble(p.get("geo_lon")); } catch (Exception ignored) {}
                }
                String name = p.getOrDefault("geo_name", this.recordId);
                JsonObject geoObj = parseJsonOrWrap(payload);
                geoObj.addProperty("name", name);
                geoObj.addProperty("lat", lat);
                geoObj.addProperty("lon", lon);
                geoObj.addProperty("_layer", this.collection != null ? this.collection : "stores_layer");
                yield jsonParser.toJson(geoObj);
            }
            case "OBJECT" -> {
                String objMime = p.getOrDefault("obj_mime", "application/json");
                String objPayload = p.getOrDefault("obj_payload", payload);
                JsonObject state = new JsonObject();
                state.addProperty("mimeType", objMime);
                state.addProperty("bucket", this.collection != null ? this.collection : "media_bucket");
                state.addProperty("sizeBytes", objPayload != null ? objPayload.getBytes(StandardCharsets.UTF_8).length : 0);
                state.addProperty("content", objPayload != null ? objPayload : "");
                yield jsonParser.toJson(state);
            }
            case "RECORDS" -> {
                String recClass = p.getOrDefault("rec_class", "com.jettra.model.PersonRecord");
                String recPayload = p.getOrDefault("rec_payload", payload);
                JsonObject recObj = parseJsonOrWrap(recPayload);
                recObj.addProperty("_table", this.collection != null ? this.collection : "default");
                recObj.addProperty("_recordClass", recClass);
                int targetVer = this.version > 0 ? this.version : 2;
                if (p.containsKey("version")) {
                    try { targetVer = Integer.parseInt(p.get("version")); } catch (Exception ignored) {}
                }
                recObj.addProperty("_version", targetVer);
                yield jsonParser.toJson(recObj);
            }
            default -> (payload != null && !payload.isBlank()) ? payload : "{}";
        };
    }

    private JsonObject parseJsonOrWrap(String payload) {
        if (payload == null || payload.isBlank()) return new JsonObject();
        try {
            JsonObject obj = jsonParser.fromJson(payload, JsonObject.class);
            return obj != null ? obj : new JsonObject();
        } catch (Exception e) {
            JsonObject wrap = new JsonObject();
            wrap.addProperty("rawContent", payload);
            wrap.addProperty("parsedAt", System.currentTimeMillis());
            return wrap;
        }
    }

    private JsonObject parseJsonOrColumns(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("status", "OK");
            return obj;
        }
        try {
            JsonObject obj = jsonParser.fromJson(raw, JsonObject.class);
            if (obj != null) return obj;
        } catch (Exception ignored) {}

        JsonObject obj = new JsonObject();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line.contains("=")) {
                String[] kv = line.split("=", 2);
                obj.addProperty(kv[0].trim(), kv[1].trim());
            } else if (line.contains(":")) {
                String[] kv = line.split(":", 2);
                obj.addProperty(kv[0].trim(), kv[1].trim());
            }
        }
        if (obj.entrySet().isEmpty()) {
            obj.addProperty("content", raw);
        }
        return obj;
    }
}
