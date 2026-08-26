package com.jettra.store.engine.ref;

import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JettraReferenceResolver: High-performance, low-latency cross-engine reference resolver.
 * Executes direct O(1) memory/storage lookups, cluster routing, and deep JSON reference expansion.
 */
public class JettraReferenceResolver {

    private final JettraStorageEngine storageEngine;
    private final JettraJson jsonParser;
    private final String localNodeId;

    public record ResolvedEntity(
        JettraReference reference,
        boolean exists,
        int version,
        String primaryStorageAddress,
        String clusterNode,
        String rawPayload,
        JsonObject jsonPayload,
        long resolvedAt
    ) {}

    public JettraReferenceResolver(JettraStorageEngine storageEngine) {
        this(storageEngine, "node-local");
    }

    public JettraReferenceResolver(JettraStorageEngine storageEngine, String localNodeId) {
        this.storageEngine = storageEngine;
        this.localNodeId = localNodeId != null ? localNodeId : "node-local";
        this.jsonParser = new JettraJson();
    }

    /**
     * Resolves a JettraReference directly with O(1) storage access and candidate key fallback.
     */
    public ResolvedEntity resolve(JettraReference ref) {
        if (ref == null) {
            return new ResolvedEntity(null, false, 0, null, localNodeId, null, null, System.currentTimeMillis());
        }

        String pfx = switch (ref.engine() != null ? ref.engine().toUpperCase() : "DOCUMENT") {
            case "RECORDS" -> "rec:";
            case "KEYVALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            default -> "doc:";
        };

        String[] candidateKeys = {
            pfx + ref.database() + ":" + ref.entityId(),
            ref.directStorageKey(),
            pfx + ref.database() + ":default:" + ref.entityId(),
            ref.database() + ":" + ref.entityId(),
            ref.database() + ":default:" + ref.entityId(),
            "doc:" + ref.database() + ":" + ref.entityId(),
            "rec:" + ref.database() + ":" + ref.entityId(),
            "geo:" + ref.database() + ":" + ref.entityId(),
            "obj:" + ref.database() + ":" + ref.entityId(),
            "vec:" + ref.database() + ":" + ref.entityId(),
            "kv:" + ref.database() + ":" + ref.entityId(),
            "ts:" + ref.database() + ":" + ref.entityId(),
            "graph:" + ref.database() + ":" + ref.entityId(),
            "col:" + ref.database() + ":" + ref.entityId()
        };

        byte[] rawBytes = null;
        String foundKey = ref.directStorageKey();
        for (String k : candidateKeys) {
            rawBytes = storageEngine.getStorageCore().get(k);
            if (rawBytes != null && rawBytes.length > 0) {
                foundKey = k;
                break;
            }
        }

        if (rawBytes == null || rawBytes.length == 0) {
            Map<String, byte[]> scanned = storageEngine.getStorageCore().scanPrefix(pfx + ref.database() + ":");
            for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                String k = e.getKey();
                if (k.endsWith(":" + ref.entityId()) || k.equals(pfx + ref.database() + ":" + ref.entityId()) || k.contains(":" + ref.entityId() + ":") || k.contains(":" + ref.entityId() + "_")) {
                    rawBytes = e.getValue();
                    foundKey = k;
                    break;
                }
            }
        }

        if (rawBytes == null || rawBytes.length == 0) {
            Map<String, byte[]> scanned = storageEngine.getStorageCore().scanPrefix(ref.database() + ":");
            for (Map.Entry<String, byte[]> e : scanned.entrySet()) {
                String k = e.getKey();
                if (k.endsWith(":" + ref.entityId()) || k.equals(ref.database() + ":" + ref.entityId()) || k.contains(":" + ref.entityId() + ":") || k.contains(":" + ref.entityId() + "_")) {
                    rawBytes = e.getValue();
                    foundKey = k;
                    break;
                }
            }
        }

        if (rawBytes == null || rawBytes.length == 0) {
            String[] allPrefixes = {"rec:", "doc:", "vec:", "geo:", "obj:", "kv:", "ts:", "graph:", "col:"};
            for (String ap : allPrefixes) {
                byte[] b = storageEngine.getStorageCore().get(ap + ref.database() + ":" + ref.entityId());
                if (b != null && b.length > 0) {
                    rawBytes = b;
                    foundKey = ap + ref.database() + ":" + ref.entityId();
                    break;
                }
                b = storageEngine.getStorageCore().get(ap + ref.database() + ":default:" + ref.entityId());
                if (b != null && b.length > 0) {
                    rawBytes = b;
                    foundKey = ap + ref.database() + ":default:" + ref.entityId();
                    break;
                }
                Map<String, byte[]> pfxScanned = storageEngine.getStorageCore().scanPrefix(ap + ref.database() + ":");
                for (Map.Entry<String, byte[]> e : pfxScanned.entrySet()) {
                    String k = e.getKey();
                    if (k.endsWith(":" + ref.entityId())) {
                        rawBytes = e.getValue();
                        foundKey = k;
                        break;
                    }
                }
                if (rawBytes != null && rawBytes.length > 0) break;
            }
        }

        String cluster = ref.node() != null ? ref.node() : localNodeId;

        if (rawBytes == null || rawBytes.length == 0) {
            return new ResolvedEntity(ref, false, 0, foundKey, cluster, null, null, System.currentTimeMillis());
        }

        String rawStr = new String(rawBytes, StandardCharsets.UTF_8);
        if (rawStr.isBlank() || "__TOMBSTONE__".equals(rawStr)) {
            return new ResolvedEntity(ref, false, 0, foundKey, cluster, null, null, System.currentTimeMillis());
        }

        int version = Math.max(1, storageEngine.getStorageCore().getVersionCount(foundKey));
        JsonObject json = null;
        try {
            json = jsonParser.fromJson(rawStr, JsonObject.class);
        } catch (Exception ignored) {}

        return new ResolvedEntity(ref, true, version, foundKey, cluster, rawStr, json, System.currentTimeMillis());
    }

    /**
     * Resolves a reference string URI directly.
     */
    public ResolvedEntity resolve(String uri) {
        return resolve(JettraReference.parse(uri));
    }

    /**
     * Recursively traverses a JsonObject and dereferences all JettraReference fields up to maxDepth.
     * E.g. <Persona> --> <Pais> expands persona.pais into the full resolved Pais document with primary storage address.
     */
    public JsonObject expandReferences(JsonObject root, int maxDepth) {
        if (root == null || maxDepth <= 0) return root;

        JsonObject expanded = new JsonObject();
        for (String key : root.keySet()) {
            Object val = root.get(key);
            if (val == null) {
                expanded.addProperty(key, (String) null);
                continue;
            }

            if (val instanceof JsonObject childObj) {
                if (childObj.has("$jref")) {
                    String jrefUri = childObj.getAsString("$jref");
                    ResolvedEntity resolved = resolve(jrefUri);
                    if (resolved.exists()) {
                        JsonObject deepResolved = resolved.jsonPayload() != null ? expandReferences(resolved.jsonPayload(), maxDepth - 1) : new JsonObject();
                        if (deepResolved.keySet().isEmpty() && resolved.rawPayload() != null) {
                            deepResolved.addProperty("raw", resolved.rawPayload());
                        }
                        JsonObject enriched = new JsonObject();
                        enriched.addProperty("$jref", jrefUri);
                        enriched.addProperty("_engine", resolved.reference().engine());
                        enriched.addProperty("_database", resolved.reference().database());
                        enriched.addProperty("_entityId", resolved.reference().entityId());
                        enriched.addProperty("_primaryAddress", resolved.primaryStorageAddress());
                        enriched.addProperty("_clusterNode", resolved.clusterNode());
                        enriched.addProperty("_version", resolved.version());
                        enriched.add("_resolved", deepResolved);
                        expanded.add(key, enriched);
                        continue;
                    }
                }
                expanded.add(key, expandReferences(childObj, maxDepth - 1));
            } else if (val instanceof String strVal) {
                if (JettraReference.isReference(strVal)) {
                    ResolvedEntity resolved = resolve(strVal);
                    if (resolved.exists()) {
                        JsonObject deepResolved = resolved.jsonPayload() != null ? expandReferences(resolved.jsonPayload(), maxDepth - 1) : new JsonObject();
                        if (deepResolved.keySet().isEmpty() && resolved.rawPayload() != null) {
                            deepResolved.addProperty("raw", resolved.rawPayload());
                        }
                        JsonObject enriched = new JsonObject();
                        enriched.addProperty("$jref", strVal);
                        enriched.addProperty("_engine", resolved.reference().engine());
                        enriched.addProperty("_database", resolved.reference().database());
                        enriched.addProperty("_entityId", resolved.reference().entityId());
                        enriched.addProperty("_primaryAddress", resolved.primaryStorageAddress());
                        enriched.addProperty("_clusterNode", resolved.clusterNode());
                        enriched.addProperty("_version", resolved.version());
                        enriched.add("_resolved", deepResolved);
                        expanded.add(key, enriched);
                        continue;
                    }
                }
                expanded.addProperty(key, strVal);
            } else if (val instanceof Number num) {
                expanded.addProperty(key, num);
            } else if (val instanceof Boolean b) {
                expanded.addProperty(key, b);
            } else {
                expanded.addProperty(key, val.toString());
            }
        }
        return expanded;
    }
}
