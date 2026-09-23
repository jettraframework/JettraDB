package com.jettra.store.engine.core.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Strategy Registry maintaining engine persistence strategies (Strategy Pattern).
 * Maps storage engine prefixes and canonical names to dedicated strategies.
 */
public final class EngineStorageStrategyRegistry {

    private static final List<StorageEngineStrategy> STRATEGIES = new ArrayList<>();
    private static final StorageEngineStrategy DEFAULT_STRATEGY;

    static {
        STRATEGIES.add(new AbstractEngineStrategy("records", "rec:", "table", "employees", "empleado"));
        STRATEGIES.add(new AbstractEngineStrategy("document", "doc:", "collection", "documents", "grupos"));
        STRATEGIES.add(new AbstractEngineStrategy("keyvalue", "kv:", "namespace", "buckets", "kv_store"));
        STRATEGIES.add(new AbstractEngineStrategy("geospatial", "geo:", "layer", "spatial_layers", "sucursales"));
        STRATEGIES.add(new AbstractEngineStrategy("graph", "graph:", "graph_label", "nodes", "taxonomia"));
        STRATEGIES.add(new AbstractEngineStrategy("timeseries", "ts:", "metric", "telemetry", "metricas"));
        STRATEGIES.add(new AbstractEngineStrategy("column", "col:", "family", "columns", "familias"));
        STRATEGIES.add(new AbstractEngineStrategy("object", "obj:", "bucket", "blobs", "archivos"));
        STRATEGIES.add(new AbstractEngineStrategy("vector", "vec:", "index", "embeddings", "vectores"));

        DEFAULT_STRATEGY = STRATEGIES.get(1); // Document strategy as fallback
    }

    private EngineStorageStrategyRegistry() {}

    public static StorageEngineStrategy resolve(String keyOrEngine) {
        if (keyOrEngine == null || keyOrEngine.isBlank()) {
            return DEFAULT_STRATEGY;
        }
        String clean = keyOrEngine.trim().toLowerCase();
        for (StorageEngineStrategy strategy : STRATEGIES) {
            if (strategy.supports(clean)) {
                return strategy;
            }
        }
        return DEFAULT_STRATEGY;
    }

    public static List<StorageEngineStrategy> getAllStrategies() {
        return Collections.unmodifiableList(STRATEGIES);
    }

    /**
     * Concrete abstract implementation for multi-model engine storage strategies.
     */
    private static class AbstractEngineStrategy implements StorageEngineStrategy {
        private final String engineName;
        private final String prefix;
        private final String primaryUnitTag;
        private final String defaultPlural;
        private final String exampleUnit;

        AbstractEngineStrategy(String engineName, String prefix, String primaryUnitTag, String defaultPlural, String exampleUnit) {
            this.engineName = engineName;
            this.prefix = prefix;
            this.primaryUnitTag = primaryUnitTag;
            this.defaultPlural = defaultPlural;
            this.exampleUnit = exampleUnit;
        }

        @Override
        public String getEngineName() {
            return engineName;
        }

        @Override
        public String getPrefix() {
            return prefix;
        }

        @Override
        public boolean supports(String prefixOrEngine) {
            if (prefixOrEngine == null) return false;
            String normalized = prefixOrEngine.trim().toLowerCase();
            return normalized.startsWith(prefix)
                || normalized.equals(engineName)
                || normalized.equals(prefix.replace(":", ""))
                || (engineName.equals("records") && (normalized.contains("record") || normalized.equals("rec")))
                || (engineName.equals("document") && (normalized.contains("doc") || normalized.equals("document")))
                || (engineName.equals("keyvalue") && (normalized.contains("kv") || normalized.contains("keyvalue")))
                || (engineName.equals("geospatial") && (normalized.contains("geo") || normalized.contains("geospatial")))
                || (engineName.equals("timeseries") && (normalized.contains("ts") || normalized.contains("timeseries")))
                || (engineName.equals("column") && (normalized.contains("col") || normalized.contains("column")))
                || (engineName.equals("object") && (normalized.contains("obj") || normalized.contains("object")))
                || (engineName.equals("vector") && (normalized.contains("vec") || normalized.contains("vector")))
                || (engineName.equals("graph") && normalized.contains("graph"));
        }

        @Override
        public String resolveUnit(String key, byte[] payload) {
            if (key == null) return "default";

            String cleanKey = key.contains("@") ? key.substring(0, key.lastIndexOf('@')) : key;
            String stripped = cleanKey.startsWith(prefix) ? cleanKey.substring(prefix.length()) : cleanKey;

            // Pattern: [db]:[unit]:[id]
            String[] segments = stripped.split(":");
            if (segments.length >= 3) {
                String candidate = segments[1].trim();
                if (!candidate.isBlank()) {
                    return normalizeUnit(candidate);
                }
            }

            // Inspect JSON payload for table / collection / unit attributes
            if (payload != null && payload.length > 0) {
                String payloadStr = new String(payload, StandardCharsets.UTF_8);
                String fromPayload = extractUnitFromPayload(payloadStr);
                if (fromPayload != null && !fromPayload.isBlank()) {
                    return normalizeUnit(fromPayload);
                }
            }

            // Check record id prefix heuristics
            String recordId = resolveRecordId(key);
            if (recordId != null) {
                String idLower = recordId.toLowerCase();
                if (idLower.startsWith("emp_") || idLower.startsWith("employee")) return "empleado";
                if (idLower.startsWith("group_") || idLower.startsWith("grp_") || idLower.startsWith("cat_")) return "grupos";
                if (idLower.startsWith("comp_") || idLower.startsWith("company")) return "companies";
                if (idLower.startsWith("sucursal_") || idLower.startsWith("branch")) return "sucursales";
                if (idLower.startsWith("seller_") || idLower.startsWith("vendedor")) return "sellers";
                if (idLower.startsWith("prod_") || idLower.startsWith("item_")) return "products";
                if (idLower.startsWith("cust_") || idLower.startsWith("client")) return "customers";
                if (idLower.startsWith("hub_")) return "hubs";
                if (idLower.startsWith("inv_")) return "inventario";
                if (idLower.startsWith("factura_") || idLower.startsWith("invoice_")) return "facturas";
            }

            return "default";
        }

        private String normalizeUnit(String unit) {
            if (unit == null || unit.isBlank()) return "default";
            String u = unit.trim().toLowerCase();
            if (u.equals("employees") || u.equals("empleados") || u.equals("empleado")) return "empleado";
            if (u.equals("vendedores") || u.equals("vendedor") || u.equals("sellers") || u.equals("seller")) return "sellers";
            if (u.equals("productos") || u.equals("producto") || u.equals("products") || u.equals("product")) return "products";
            if (u.equals("clientes") || u.equals("cliente") || u.equals("customers") || u.equals("customer")) return "customers";
            if (u.equals("companias") || u.equals("compania") || u.equals("companies") || u.equals("company")) return "companies";
            if (u.equals("sucursales") || u.equals("sucursal") || u.equals("branches") || u.equals("branch")) return "sucursales";
            if (u.equals("grupos") || u.equals("grupo") || u.equals("groups") || u.equals("group")) return "grupos";
            return sanitizeIdentifier(u);
        }

        @Override
        public String resolveRecordId(String key) {
            if (key == null || key.isBlank()) return "unknown_record";
            String cleanKey = key.contains("@") ? key.substring(0, key.lastIndexOf('@')) : key;
            int lastColon = cleanKey.lastIndexOf(':');
            if (lastColon >= 0 && lastColon < cleanKey.length() - 1) {
                return sanitizeIdentifier(cleanKey.substring(lastColon + 1));
            }
            return sanitizeIdentifier(cleanKey);
        }

        @Override
        public StorageRecordPath buildPath(Path rootDir, String database, String key, byte[] payload) {
            String unit = resolveUnit(key, payload);
            String recordId = resolveRecordId(key);
            return StoragePathBuilder.create()
                .withRoot(rootDir)
                .withDatabase(database)
                .withEngine(engineName)
                .withUnit(unit)
                .withRecordId(recordId)
                .build();
        }

        private String extractUnitFromPayload(String json) {
            if (json == null || json.length() < 5) return null;
            // High-speed light scanning for "_table", "_collection", "table", "collection", "layer", "bucket"
            String[] tags = {"\"_table\":\"", "\"_collection\":\"", "\"table\":\"", "\"collection\":\"", "\"_layer\":\"", "\"bucket\":\""};
            for (String tag : tags) {
                int idx = json.indexOf(tag);
                if (idx >= 0) {
                    int start = idx + tag.length();
                    int end = json.indexOf('"', start);
                    if (end > start) {
                        return json.substring(start, end);
                    }
                }
            }
            return null;
        }

        private String sanitizeIdentifier(String name) {
            if (name == null || name.isBlank()) return "default";
            return name.trim().replaceAll("[^a-zA-Z0-9._\\-]", "_");
        }
    }
}
