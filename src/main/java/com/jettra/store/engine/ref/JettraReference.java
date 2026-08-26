package com.jettra.store.engine.ref;

import io.jettra.json.JsonObject;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JettraReference: Ultra-fast, direct O(1) cross-engine reference pointer.
 * Supports pointing across all 9 storage engines, distinct databases/namespaces,
 * and remote distributed cluster nodes.
 *
 * URI Format:
 *   jref://[node@][ENGINE:]database/entityId
 *
 * Examples:
 *   jref://DOCUMENT:customers_db/cust_101
 *   jref://RECORDS:hr_db/emp_007
 *   jref://node-01@VECTOR:ai_db/face_emb_42
 *   jref://geo_db/country_panama (defaults to DOCUMENT engine)
 */
public record JettraReference(
    String node,
    String engine,
    String database,
    String entityId,
    String directStorageKey
) {

    private static final Pattern JREF_PATTERN = Pattern.compile(
        "^jref://(?:([a-zA-Z0-9_.-]+)@)?(?:([a-zA-Z0-9_]+):)?([a-zA-Z0-9_.-]+)/(.+)$"
    );

    public static JettraReference of(String engine, String database, String entityId) {
        return of(null, engine, database, entityId);
    }

    public static JettraReference of(String node, String engine, String database, String entityId) {
        String normEngine = (engine != null && !engine.isBlank()) ? engine.toUpperCase() : "DOCUMENT";
        String normDb = database != null ? database.trim() : "default";
        String normId = entityId != null ? entityId.trim() : "";
        String normNode = (node != null && !node.isBlank()) ? node.trim() : null;
        String directKey = computeDirectStorageKey(normEngine, normDb, normId);
        return new JettraReference(normNode, normEngine, normDb, normId, directKey);
    }

    public static JettraReference parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Reference URI cannot be null or empty");
        }
        String clean = uri.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        Matcher m = JREF_PATTERN.matcher(clean);
        if (m.matches()) {
            String node = m.group(1);
            String engine = m.group(2) != null ? m.group(2) : "DOCUMENT";
            String db = m.group(3);
            String id = m.group(4);
            return of(node, engine, db, id);
        }

        // Support shorthand format "engine:db:id" or "db:id"
        if (clean.contains(":")) {
            String[] parts = clean.split(":");
            if (parts.length == 3) {
                return of(null, parts[0], parts[1], parts[2]);
            } else if (parts.length == 2) {
                return of(null, "DOCUMENT", parts[0], parts[1]);
            }
        }
        throw new IllegalArgumentException("Invalid JettraReference URI format: " + uri);
    }

    public static boolean isReference(String str) {
        if (str == null) return false;
        String s = str.trim();
        return s.startsWith("jref://") || s.startsWith("{\"$jref\"") || (s.contains("\"$jref\"") && s.contains("jref://"));
    }

    public static String computeDirectStorageKey(String engine, String database, String entityId) {
        String pfx = switch (engine != null ? engine.toUpperCase() : "DOCUMENT") {
            case "RECORDS" -> "rec:";
            case "KEYVALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:"; // DOCUMENT
        };
        return pfx + database + ":" + entityId;
    }

    public String toUri() {
        StringBuilder sb = new StringBuilder("jref://");
        if (node != null && !node.isBlank()) {
            sb.append(node).append("@");
        }
        if (engine != null && !"DOCUMENT".equalsIgnoreCase(engine)) {
            sb.append(engine.toUpperCase()).append(":");
        }
        sb.append(database).append("/").append(entityId);
        return sb.toString();
    }

    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("$jref", toUri());
        obj.addProperty("engine", engine);
        obj.addProperty("db", database);
        obj.addProperty("id", entityId);
        obj.addProperty("key", directStorageKey);
        if (node != null) {
            obj.addProperty("node", node);
        }
        return obj;
    }

    @Override
    public String toString() {
        return toUri();
    }
}
