package com.jettra.store.engine.hierarchy;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.ColumnEngine;
import com.jettra.store.engine.models.DocumentEngine;
import com.jettra.store.engine.models.GeospatialEngine;
import com.jettra.store.engine.models.GraphEngine;
import com.jettra.store.engine.models.KeyValueEngine;
import com.jettra.store.engine.models.ObjectEngine;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.models.TimeSeriesEngine;
import com.jettra.store.engine.models.VectorEngine;
import io.jettra.json.JsonArray;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.models.VersionSnapshotRecord;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * High-performance Hierarchy Explorer Service orchestrating parallel multi-model discovery
 * across all 9 engines using Java 25 Virtual Threads.
 */
public class HierarchyExplorerService {

    private final JettraStorageEngine engine;
    private final JettraJson jsonParser = new JettraJson();

    private static final String[][] ENGINE_SPECS = {
        {"DOCUMENT", "#3b82f6", "fas fa-file-alt", "Collections", "Collection", "Document", "fas fa-file-code"},
        {"KEYVALUE", "#10b981", "fas fa-key", "Namespaces", "Namespace", "Key-Value Pair", "fas fa-cube"},
        {"VECTOR", "#8b5cf6", "fas fa-project-diagram", "Vector Indexes", "Vector Index", "Embedding", "fas fa-braille"},
        {"GRAPH", "#ec4899", "fas fa-share-alt", "Labels", "Label", "Vertex / Edge", "fas fa-circle-nodes"},
        {"TIMESERIES", "#06b6d4", "fas fa-chart-line", "Metrics", "Metric", "Time Point", "fas fa-stopwatch"},
        {"COLUMN", "#f97316", "fas fa-table", "Column Families", "Column Family", "Dynamic Row", "fas fa-bars-staggered"},
        {"GEOSPATIAL", "#14b8a6", "fas fa-globe-americas", "Spatial Layers", "Spatial Layer", "GIS Feature", "fas fa-location-dot"},
        {"OBJECT", "#a855f7", "fas fa-archive", "Buckets", "Bucket", "BLOB Object", "fas fa-box-archive"},
        {"RECORDS", "#f43f5e", "fas fa-id-card", "Record Tables", "Record Table", "Record", "fas fa-address-card"}
    };

    public HierarchyExplorerService(JettraStorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "Storage engine must not be null");
    }

    public HierarchyResult<HierarchyNode.DatabaseNode> resolveDatabaseHierarchy(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            dbName = "customers_db";
        }
        final String targetDb = resolveExistingDatabaseName(dbName.trim());

        try (ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<HierarchyNode.EngineNode>> engineTasks = new ArrayList<>();
            for (String[] spec : ENGINE_SPECS) {
                engineTasks.add(() -> resolveEngineHierarchy(spec, targetDb));
            }

            Callable<List<HierarchyNode.IndexNode>> indexTask = () -> resolveIndexes(targetDb);
            Callable<List<HierarchyNode.SchemaNode>> schemaTask = () -> resolveSchemas(targetDb);

            Future<List<HierarchyNode.IndexNode>> indexFuture = vThreadExecutor.submit(indexTask);
            Future<List<HierarchyNode.SchemaNode>> schemaFuture = vThreadExecutor.submit(schemaTask);
            List<Future<HierarchyNode.EngineNode>> engineFutures = vThreadExecutor.invokeAll(engineTasks);

            List<HierarchyNode.EngineNode> engineNodes = new ArrayList<>();
            int totalDbItems = 0;
            for (Future<HierarchyNode.EngineNode> f : engineFutures) {
                HierarchyNode.EngineNode engNode = f.get();
                totalDbItems += engNode.totalItems();
                engineNodes.add(engNode);
            }

            List<HierarchyNode.IndexNode> indexes = indexFuture.get();
            List<HierarchyNode.SchemaNode> schemas = schemaFuture.get();

            boolean hasCustomIndex = indexes.stream().anyMatch(idx -> !"idx_primary_id".equals(idx.name()));
            boolean hasDiscoveredDb = discoverAllDatabases().contains(targetDb) || discoverAllDatabases().contains(dbName.trim());
            boolean hasComponents = (totalDbItems > 0) || !schemas.isEmpty() || hasCustomIndex || hasDiscoveredDb;

            HierarchyNode.DatabaseNode dbNode = new HierarchyNode.DatabaseNode(
                "db_" + targetDb,
                dbName.trim(),
                totalDbItems,
                hasComponents,
                engineNodes,
                indexes,
                schemas
            );

            return HierarchyResult.success(dbNode);
        } catch (Exception e) {
            return HierarchyResult.failure("Failed to resolve hierarchy for database '" + dbName + "': " + e.getMessage(), e);
        }
    }

    private HierarchyNode.EngineNode resolveEngineHierarchy(String[] spec, String db) {
        String engName = spec[0];
        String engColor = spec[1];
        String engIcon = spec[2];
        String unitPlural = spec[3];
        String unitSingle = spec[4];
        String itemLabel = spec[5];
        String itemIcon = spec[6];

        Map<String, List<String>> unitsAndItems = discoverUnitsAndItems(engName, db);
        if (unitsAndItems.isEmpty() && (discoverAllDatabases().contains(db) || discoverAllDatabases().contains(resolveExistingDatabaseName(db)))) {
            unitsAndItems.put("default", new ArrayList<>());
        }
        List<HierarchyNode.UnitNode> unitNodes = new ArrayList<>();
        int totalEngItems = 0;

        for (Map.Entry<String, List<String>> uEntry : unitsAndItems.entrySet()) {
            String uName = uEntry.getKey();
            List<String> items = uEntry.getValue();
            List<HierarchyNode.RecordNode> recordNodes = new ArrayList<>();

            for (String itemId : items) {
                int vCount = getItemVersionCount(engName, db, uName, itemId);
                String itemPayload = getItemPayload(engName, db, uName, itemId);
                String itemVersions = getVersionsJson(engName, db, uName, itemId);
                String payloadB64 = Base64.getEncoder().encodeToString(itemPayload.getBytes(StandardCharsets.UTF_8));
                String versionsB64 = Base64.getEncoder().encodeToString(itemVersions.getBytes(StandardCharsets.UTF_8));

                Map<String, Object> summaryProps = extractSummaryProperties(itemPayload);

                recordNodes.add(new HierarchyNode.RecordNode(
                    itemId,
                    itemId,
                    engName,
                    uName,
                    vCount,
                    itemPayload,
                    payloadB64,
                    versionsB64,
                    summaryProps
                ));
            }

            unitNodes.add(new HierarchyNode.UnitNode(
                "unit_" + engName + "_" + db + "_" + uName,
                uName,
                recordNodes.size(),
                recordNodes
            ));
            totalEngItems += recordNodes.size();
        }

        return new HierarchyNode.EngineNode(
            "eng_" + engName + "_" + db,
            engName,
            engColor,
            engIcon,
            unitPlural,
            unitSingle,
            itemLabel,
            itemIcon,
            totalEngItems,
            unitNodes
        );
    }

    private Map<String, Object> extractSummaryProperties(String payload) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) return summary;
        try {
            JsonObject parsed = jsonParser.fromJson(payload, JsonObject.class);
            if (parsed != null) {
                int count = 0;
                for (String k : parsed.keySet()) {
                    if (count >= 8) break;
                    Object val = parsed.get(k);
                    summary.put(k, val != null ? val : "null");
                    count++;
                }
            }
        } catch (Exception ignored) {
            summary.put("raw", payload.length() > 60 ? payload.substring(0, 60) + "..." : payload);
        }
        return summary;
    }

    private List<HierarchyNode.IndexNode> resolveIndexes(String db) {
        Map<String, HierarchyNode.IndexNode> map = new LinkedHashMap<>();

        // 1. Scan "idx:<db>:"
        Map<String, byte[]> idxEntries = engine.getStorageCore().scanPrefix("idx:" + db + ":");
        for (Map.Entry<String, byte[]> entry : idxEntries.entrySet()) {
            String k = entry.getKey();
            if (k.contains("@")) continue;
            String idxName = k.substring(("idx:" + db + ":").length());
            if (idxName.isBlank()) continue;
            try {
                String json = new String(entry.getValue(), StandardCharsets.UTF_8);
                JsonObject obj = jsonParser.fromJson(json, JsonObject.class);
                String name = obj.has("name") ? obj.getAsString("name") : idxName;
                String type = obj.has("type") ? obj.getAsString("type") : "BTREE";
                String unit = obj.has("collection") ? obj.getAsString("collection") : (obj.has("unit") ? obj.getAsString("unit") : "default");
                String field = obj.has("field") ? obj.getAsString("field") : "id";
                int count = obj.has("count") ? obj.getAsInt("count") : 0;
                map.put(name, new HierarchyNode.IndexNode("idx_" + name, name, type, unit, field, count));
            } catch (Exception e) {
                map.put(idxName, new HierarchyNode.IndexNode("idx_" + idxName, idxName, "BTREE", "default", "id", 0));
            }
        }

        // 2. Scan "meta:<db>:index:"
        Map<String, byte[]> metaEntries = engine.getStorageCore().scanPrefix("meta:" + db + ":index:");
        for (Map.Entry<String, byte[]> entry : metaEntries.entrySet()) {
            String k = entry.getKey();
            if (k.contains("@")) continue;
            String idxName = k.substring(("meta:" + db + ":index:").length());
            if (idxName.isBlank() || map.containsKey(idxName)) continue;
            try {
                String json = new String(entry.getValue(), StandardCharsets.UTF_8);
                JsonObject obj = jsonParser.fromJson(json, JsonObject.class);
                String name = obj.has("name") ? obj.getAsString("name") : idxName;
                String type = obj.has("type") ? obj.getAsString("type") : "BTREE";
                String unit = obj.has("collection") ? obj.getAsString("collection") : (obj.has("unit") ? obj.getAsString("unit") : "default");
                String field = obj.has("field") ? obj.getAsString("field") : "id";
                int count = obj.has("count") ? obj.getAsInt("count") : 0;
                map.put(name, new HierarchyNode.IndexNode("idx_" + name, name, type, unit, field, count));
            } catch (Exception e) {
                map.put(idxName, new HierarchyNode.IndexNode("idx_" + idxName, idxName, "BTREE", "default", "id", 0));
            }
        }

        if (map.isEmpty()) {
            map.put("idx_primary_id", new HierarchyNode.IndexNode("idx_idx_primary_id", "idx_primary_id", "BTREE", "default", "_id", 0));
        }

        return new ArrayList<>(map.values());
    }

    private List<HierarchyNode.SchemaNode> resolveSchemas(String db) {
        List<HierarchyNode.SchemaNode> list = new ArrayList<>();
        Map<String, byte[]> entries = engine.getStorageCore().scanPrefix("schema:" + db + ":");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String scName = entry.getKey().substring(("schema:" + db + ":").length());
            String scJson = new String(entry.getValue(), StandardCharsets.UTF_8);
            String b64 = Base64.getEncoder().encodeToString(scJson.getBytes(StandardCharsets.UTF_8));
            list.add(new HierarchyNode.SchemaNode("sc_" + scName, scName, scJson, b64));
        }
        return list;
    }

    public Map<String, List<String>> discoverUnitsAndItems(String engineKey, String db) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (engine == null || engine.getStorageCore() == null || db == null || db.isBlank()) return result;

        String queryDb = resolveExistingDatabaseName(db);
        String prefix = getPrefixForEngine(engineKey);
        String dbPrefix = prefix + queryDb + ":";
        Map<String, byte[]> entries = engine.getStorageCore().scanPrefix(dbPrefix);

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String k = e.getKey();
            if (k.contains("@")) continue; // Ignore versioned entries
            byte[] val = e.getValue();
            if (val == null || val.length == 0) continue;
            String valStr = new String(val, StandardCharsets.UTF_8).trim();
            if (valStr.isEmpty() || "__TOMBSTONE__".equals(valStr)) continue;

            String remainder = k.substring(dbPrefix.length());
            String[] parts = remainder.split(":", 2);
            if (parts.length == 2) {
                String unit = parts[0];
                String id = parts[1];
                if (!id.startsWith("meta_") && !id.startsWith("__") && !id.contains(":v_")) {
                    if (!id.equals("init_01")) {
                        result.computeIfAbsent(unit, u -> new ArrayList<>()).add(id);
                    } else {
                        result.computeIfAbsent(unit, u -> new ArrayList<>());
                    }
                }
            } else if (parts.length == 1) {
                String id = parts[0];
                if (!id.startsWith("meta_") && !id.startsWith("__") && !id.contains(":v_")) {
                    if (!id.equals("init_01")) {
                        result.computeIfAbsent("default", u -> new ArrayList<>()).add(id);
                    } else {
                        result.computeIfAbsent("default", u -> new ArrayList<>());
                    }
                }
            }
        }

        // Also check un-prefixed partition keys for DOCUMENT engine or direct keys
        if ("DOCUMENT".equalsIgnoreCase(engineKey)) {
            String docPrefix = queryDb + ":";
            Map<String, byte[]> simpleEntries = engine.getStorageCore().scanPrefix(docPrefix);
            for (Map.Entry<String, byte[]> e : simpleEntries.entrySet()) {
                String k = e.getKey();
                if (k.contains("@")) continue;
                byte[] val = e.getValue();
                if (val == null || val.length == 0) continue;
                String valStr = new String(val, StandardCharsets.UTF_8).trim();
                if (valStr.isEmpty() || "__TOMBSTONE__".equals(valStr)) continue;

                String remainder = k.substring(docPrefix.length());
                String[] parts = remainder.split(":", 2);
                if (parts.length == 2) {
                    String unit = parts[0];
                    String id = parts[1];
                    if (!id.startsWith("meta_") && !id.startsWith("__") && !id.contains(":v_")) {
                        if (!id.equals("init_01")) {
                            List<String> list = result.computeIfAbsent(unit, u -> new ArrayList<>());
                            if (!list.contains(id)) list.add(id);
                        } else {
                            result.computeIfAbsent(unit, u -> new ArrayList<>());
                        }
                    }
                } else if (parts.length == 1) {
                    String id = parts[0];
                    if (!id.startsWith("meta_") && !id.startsWith("__") && !id.contains(":v_")) {
                        if (!id.equals("init_01")) {
                            List<String> list = result.computeIfAbsent("default", u -> new ArrayList<>());
                            if (!list.contains(id)) list.add(id);
                        } else {
                            result.computeIfAbsent("default", u -> new ArrayList<>());
                        }
                    }
                }
            }
        }

        // Ensure natural ordering and uniqueness of IDs
        for (Map.Entry<String, List<String>> entry : result.entrySet()) {
            Set<String> unique = new LinkedHashSet<>(entry.getValue());
            List<String> sorted = new ArrayList<>(unique);
            sorted.sort(String::compareTo);
            entry.setValue(sorted);
        }

        return result;
    }

    public String getItemPayload(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String[] candidateKeys = {
            prefix + db + ":" + coll + ":" + id,
            prefix + db + ":" + id,
            db + ":" + coll + ":" + id,
            db + ":" + id
        };

        for (String k : candidateKeys) {
            byte[] b = engine.getStorageCore().get(k);
            if (b != null && b.length > 0) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
        return "{}";
    }

    public int getItemVersionCount(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String directKey = prefix + db + ":" + id;
        String collKey = prefix + db + ":" + coll + ":" + id;
        String simpleKey = db + ":" + id;

        if (engine.getStorageCore().get(directKey) != null) {
            return engine.getStorageCore().getVersionCount(directKey);
        }
        if (engine.getStorageCore().get(collKey) != null) {
            return engine.getStorageCore().getVersionCount(collKey);
        }
        if (engine.getStorageCore().get(simpleKey) != null) {
            return engine.getStorageCore().getVersionCount(simpleKey);
        }
        return 1;
    }

    public String getVersionsJson(String engineKey, String db, String coll, String id) {
        String prefix = getPrefixForEngine(engineKey);
        String primaryKey = prefix + db + ":" + coll + ":" + id;
        if (engine.getStorageCore().get(primaryKey) == null) {
            primaryKey = prefix + db + ":" + id;
        }
        if (engine.getStorageCore().get(primaryKey) == null) {
            primaryKey = db + ":" + coll + ":" + id;
        }
        if (engine.getStorageCore().get(primaryKey) == null) {
            primaryKey = db + ":" + id;
        }

        List<com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion> rawVersions = engine.getStorageCore().getVersionHistory(primaryKey);
        List<VersionSnapshotRecord> snapshots = new ArrayList<>();

        if (rawVersions != null && !rawVersions.isEmpty()) {
            for (com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion rv : rawVersions) {
                byte[] data = rv.data();
                if (data == null && rv.payload() != null) {
                    data = rv.payload().getBytes(StandardCharsets.UTF_8);
                }
                if (data != null && data.length > 0) {
                    snapshots.add(VersionSnapshotRecord.of(
                        rv.versionNumber(),
                        rv.timestamp(),
                        data,
                        rv.isCurrent()
                    ));
                }
            }
        }

        if (snapshots.isEmpty()) {
            byte[] cur = engine.getStorageCore().get(primaryKey);
            if (cur != null && cur.length > 0) {
                snapshots.add(VersionSnapshotRecord.of(
                    1,
                    System.currentTimeMillis(),
                    cur,
                    true
                ));
            }
        }

        JsonArray arr = new JsonArray();
        for (VersionSnapshotRecord snap : snapshots) {
            arr.add(snap.toJsonObject(jsonParser));
        }
        return jsonParser.toJson(arr);
    }

    public String resolveExistingDatabaseName(String dbName) {
        if (dbName == null || dbName.isBlank()) return "customers_db";
        Set<String> allDbs = discoverAllDatabases();
        if (allDbs.contains(dbName)) return dbName;
        for (String d : allDbs) {
            if (d.equalsIgnoreCase(dbName)) return d;
        }
        for (String d : allDbs) {
            if (d.equalsIgnoreCase(dbName + "s") || (dbName.endsWith("s") && d.equalsIgnoreCase(dbName.substring(0, dbName.length() - 1)))) {
                return d;
            }
        }
        return dbName;
    }

    public Set<String> discoverAllDatabases() {
        Set<String> databases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (engine == null || engine.getStorageCore() == null) return databases;

        Map<String, byte[]> allKeys = engine.getStorageCore().scanPrefix("");
        for (String key : allKeys.keySet()) {
            if (key.startsWith("meta:") || key.startsWith("schema:") || key.startsWith("rule:") || key.startsWith("idx:")) {
                String[] parts = key.split(":");
                if (parts.length > 1 && !parts[1].isBlank()) {
                    databases.add(parts[1]);
                }
                continue;
            }

            for (String[] spec : ENGINE_SPECS) {
                String pfx = getPrefixForEngine(spec[0]);
                if (key.startsWith(pfx)) {
                    String sub = key.substring(pfx.length());
                    String[] parts = sub.split(":");
                    if (parts.length > 0 && !parts[0].isBlank()) {
                        databases.add(parts[0]);
                    }
                    break;
                }
            }

            String[] parts = key.split(":");
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[0].contains("/")) {
                databases.add(parts[0]);
            }
        }
        return databases;
    }

    public String getPrefixForEngine(String engineKey) {
        if (engineKey == null) return "doc:";
        return StorageEngineType.fromString(engineKey)
                .map(StorageEngineType::prefix)
                .orElse("doc:");
    }
}
