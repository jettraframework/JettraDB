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
     * Resolves a JettraReference directly with O(1) storage access.
     */
    public ResolvedEntity resolve(JettraReference ref) {
        if (ref == null) {
            return new ResolvedEntity(null, false, 0, null, null, System.currentTimeMillis());
        }

        // 1. Direct O(1) fetch from Storage Core using precalculated key
        String storageKey = ref.directStorageKey();
        byte[] rawBytes = storageEngine.getStorageCore().get(storageKey);

        if (rawBytes == null || rawBytes.length == 0) {
            return new ResolvedEntity(ref, false, 0, null, null, System.currentTimeMillis());
        }

        String rawStr = new String(rawBytes, StandardCharsets.UTF_8);
        if (rawStr.isBlank() || "__TOMBSTONE__".equals(rawStr)) {
            return new ResolvedEntity(ref, false, 0, null, null, System.currentTimeMillis());
        }

        int version = Math.max(1, storageEngine.getStorageCore().getVersionCount(storageKey));
        JsonObject json = null;
        try {
            json = jsonParser.fromJson(rawStr, JsonObject.class);
        } catch (Exception ignored) {}

        return new ResolvedEntity(ref, true, version, rawStr, json, System.currentTimeMillis());
    }

    /**
     * Resolves a reference string URI directly.
     */
    public ResolvedEntity resolve(String uri) {
        return resolve(JettraReference.parse(uri));
    }

    /**
     * Recursively traverses a JsonObject and dereferences all JettraReference fields up to maxDepth.
     * E.g. <Persona> --> <Pais> expands persona.pais into the full resolved Pais document.
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
                    if (resolved.exists() && resolved.jsonPayload() != null) {
                        JsonObject deepResolved = expandReferences(resolved.jsonPayload(), maxDepth - 1);
                        JsonObject enriched = new JsonObject();
                        enriched.addProperty("$jref", jrefUri);
                        enriched.addProperty("_engine", resolved.reference().engine());
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
                    if (resolved.exists() && resolved.jsonPayload() != null) {
                        JsonObject deepResolved = expandReferences(resolved.jsonPayload(), maxDepth - 1);
                        JsonObject enriched = new JsonObject();
                        enriched.addProperty("$jref", strVal);
                        enriched.addProperty("_engine", resolved.reference().engine());
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
