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
        "^(?:jref|jettra)://(?:([a-zA-Z0-9_.-]+)@)?(?:([a-zA-Z0-9_]+):)?([a-zA-Z0-9_.-]+)/(.+)$"
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

        // Handle JSON objects containing $jref or $ref
        if (clean.startsWith("{") && clean.endsWith("}")) {
            if (clean.contains("\"$jref\"") || clean.contains("\"$ref\"")) {
                int start = clean.indexOf("://");
                if (start > 0) {
                    int prefixStart = clean.lastIndexOf("\"", start);
                    if (prefixStart >= 0) {
                        int end = clean.indexOf("\"", start + 3);
                        if (end > start) {
                            clean = clean.substring(prefixStart + 1, end).trim();
                        }
                    }
                }
            }
        }

        // Handle query parameter URI wrapper: e.g. /engines?action=resolve_ref&uri=jref%3A%2F%2F...
        if (clean.contains("uri=")) {
            int idx = clean.indexOf("uri=");
            clean = clean.substring(idx + 4);
            int amp = clean.indexOf("&");
            if (amp > 0) {
                clean = clean.substring(0, amp);
            }
            try {
                clean = java.net.URLDecoder.decode(clean, java.nio.charset.StandardCharsets.UTF_8);
                if (clean.contains("%")) {
                    clean = java.net.URLDecoder.decode(clean, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            clean = clean.trim();
        }

        Matcher m = JREF_PATTERN.matcher(clean);
        if (m.matches()) {
            String node = m.group(1);
            String engine = m.group(2) != null ? m.group(2) : "DOCUMENT";
            String db = m.group(3);
            String id = m.group(4);
            return of(node, engine, db, id);
        }

        // Support format "engine:db/id" or "db/id"
        if (clean.contains("/") && !clean.contains("://")) {
            int slash = clean.indexOf('/');
            String head = clean.substring(0, slash);
            String tail = clean.substring(slash + 1);
            if (head.contains(":")) {
                String[] hp = head.split(":", 2);
                return of(null, hp[0], hp[1], tail);
            } else {
                return of(null, "DOCUMENT", head, tail);
            }
        }

        // Support shorthand format "engine:db:id" or "db:id"
        if (clean.contains(":")) {
            String[] parts = clean.split(":");
            if (parts.length >= 3) {
                String engine = parts[0];
                String db = parts[1];
                String id = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                return of(null, engine, db, id);
            } else if (parts.length == 2) {
                return of(null, "DOCUMENT", parts[0], parts[1]);
            }
        }
        throw new IllegalArgumentException("Invalid JettraReference URI format: " + uri);
    }

    public static boolean isReference(String str) {
        if (str == null) return false;
        String s = str.trim();
        return s.startsWith("jref://") || s.startsWith("jettra://") || s.startsWith("{\"$jref\"") || s.startsWith("{\"$ref\"") || (s.contains("\"$jref\"") && s.contains("://")) || (s.contains("\"$ref\"") && s.contains("://"));
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
