package com.jettra.store.engine.hierarchy;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
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
import com.jettra.store.engine.models.RecordVersionSnapshot;
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
            dbName = "system_db";
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

    public List<HierarchyNode.IndexNode> resolveIndexes(String db) {
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
        Set<String> keys = engine.getStorageCore().scanPrefixKeys(dbPrefix);

        for (String k : keys) {
            if (k.contains("@")) continue; // Ignore versioned entries

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
            Set<String> simpleKeys = engine.getStorageCore().scanPrefixKeys(docPrefix);
            for (String k : simpleKeys) {
                if (k.contains("@")) continue;

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

    public List<String> resolveCandidateKeys(String engineKey, String db, String coll, String id) {
        if (id == null || id.isBlank()) return Collections.emptyList();
        String prefix = getPrefixForEngine(engineKey);
        List<String> candidateKeys = new ArrayList<>();
        boolean hasColl = coll != null && !coll.isBlank();
        boolean isDefault = hasColl && "default".equalsIgnoreCase(coll);

        if (db != null && !db.isBlank()) {
            if (hasColl && !isDefault) {
                candidateKeys.add(prefix + db + ":" + coll + ":" + id);
                candidateKeys.add(db + ":" + coll + ":" + id);
            } else if (isDefault) {
                candidateKeys.add(prefix + db + ":default:" + id);
                candidateKeys.add(db + ":default:" + id);
                candidateKeys.add(prefix + db + ":" + id);
                candidateKeys.add(db + ":" + id);
            }
            if (!candidateKeys.contains(prefix + db + ":" + id)) {
                candidateKeys.add(prefix + db + ":" + id);
            }
            if (!candidateKeys.contains(db + ":" + id)) {
                candidateKeys.add(db + ":" + id);
            }
        }
        if (hasColl) {
            String cKey1 = prefix + coll + ":" + id;
            if (!candidateKeys.contains(cKey1)) candidateKeys.add(cKey1);
            String cKey2 = coll + ":" + id;
            if (!candidateKeys.contains(cKey2)) candidateKeys.add(cKey2);
        }
        if (!candidateKeys.contains(prefix + id)) candidateKeys.add(prefix + id);
        if (!candidateKeys.contains(id)) candidateKeys.add(id);

        // Special resolution for GRAPH engine
        if ("GRAPH".equalsIgnoreCase(engineKey)) {
            if (db != null && !db.isBlank()) {
                if (hasColl) {
                    addUnique(candidateKeys, "graph:" + db + ":" + coll + ":node:" + id);
                    addUnique(candidateKeys, "graph:" + db + ":" + coll + ":edge:" + id);
                    addUnique(candidateKeys, "graph:" + db + ":" + coll + ":" + id);
                }
                addUnique(candidateKeys, "graph:" + db + ":node:" + id);
                addUnique(candidateKeys, "graph:" + db + ":edge:" + id);
                addUnique(candidateKeys, "graph:" + db + ":" + id);
            }
            if (hasColl) {
                addUnique(candidateKeys, "graph:" + coll + ":node:" + id);
                addUnique(candidateKeys, "graph:" + coll + ":edge:" + id);
                addUnique(candidateKeys, "graph:" + coll + ":" + id);
            }
            addUnique(candidateKeys, "graph:node:" + id);
            addUnique(candidateKeys, "graph:edge:" + id);
            addUnique(candidateKeys, "graph:" + id);
        }

        // Special resolution for TIMESERIES engine
        if ("TIMESERIES".equalsIgnoreCase(engineKey) || "TIME_SERIES".equalsIgnoreCase(engineKey)) {
            if (db != null && !db.isBlank()) {
                if (hasColl) {
                    addUnique(candidateKeys, "ts:" + db + ":" + coll + ":" + id);
                    addUnique(candidateKeys, db + ":" + coll + ":" + id);
                }
                addUnique(candidateKeys, "ts:" + db + ":" + id);
                addUnique(candidateKeys, db + ":" + id);
            }
            if (hasColl) {
                addUnique(candidateKeys, "ts:" + coll + ":" + id);
                addUnique(candidateKeys, coll + ":" + id);
            }
            addUnique(candidateKeys, "ts:" + id);
        }

        // Alternative prefix for DOCUMENT/RECORDS interchangeability
        if ("DOCUMENT".equalsIgnoreCase(engineKey) || "RECORDS".equalsIgnoreCase(engineKey) || "RECORD".equalsIgnoreCase(engineKey)) {
            String altPrefix = "rec:".equals(prefix) ? "doc:" : "rec:";
            if (db != null && !db.isBlank()) {
                if (hasColl) candidateKeys.add(altPrefix + db + ":" + coll + ":" + id);
                candidateKeys.add(altPrefix + db + ":" + id);
            }
            if (hasColl) candidateKeys.add(altPrefix + coll + ":" + id);
            candidateKeys.add(altPrefix + id);
        }

        return candidateKeys;
    }

    private static void addUnique(List<String> list, String key) {
        if (!list.contains(key)) {
            list.add(key);
        }
    }

    public String getItemPayload(String engineKey, String db, String coll, String id) {
        if (id == null || id.isBlank()) return "{}";
        String prefix = getPrefixForEngine(engineKey);
        List<String> candidateKeys = resolveCandidateKeys(engineKey, db, coll, id);

        for (String k : candidateKeys) {
            byte[] b = engine.getStorageCore().get(k);
            if (b != null && b.length > 0) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }

        // Fallback: scan prefix for matching suffix
        if (db != null && !db.isBlank()) {
            String scanPfx = prefix + db + ":";
            Map<String, byte[]> scanned = engine.getStorageCore().scanPrefix(scanPfx);
            for (Map.Entry<String, byte[]> entry : scanned.entrySet()) {
                String key = entry.getKey();
                if (key.contains("@")) continue;
                if (key.endsWith(":" + id) || key.equals(scanPfx + id)) {
                    byte[] b = entry.getValue();
                    if (b != null && b.length > 0) {
                        return new String(b, StandardCharsets.UTF_8);
                    }
                }
            }
        }

        return "{}";
    }

    public int getItemVersionCount(String engineKey, String db, String coll, String id) {
        int maxVersions = 1;
        boolean found = false;
        for (String k : resolveCandidateKeys(engineKey, db, coll, id)) {
            if (engine.getStorageCore().get(k) != null) {
                int count = engine.getStorageCore().getVersionCount(k);
                if (count > maxVersions) {
                    maxVersions = count;
                }
                found = true;
            }
        }
        return found ? maxVersions : 1;
    }

    public List<RecordVersionSnapshot> getVersionSnapshots(String engineKey, String db, String coll, String id) {
        String primaryKey = null;
        int maxCount = -1;
        for (String k : resolveCandidateKeys(engineKey, db, coll, id)) {
            if (engine.getStorageCore().get(k) != null) {
                int count = engine.getStorageCore().getVersionCount(k);
                if (count > maxCount) {
                    maxCount = count;
                    primaryKey = k;
                }
            }
        }
        if (primaryKey == null) {
            String prefix = getPrefixForEngine(engineKey);
            primaryKey = prefix + (db != null ? db + ":" : "") + (coll != null && !coll.isBlank() ? coll + ":" : "") + id;
        }

        List<com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion> rawVersions = engine.getStorageCore().getVersionHistory(primaryKey);
        List<RecordVersionSnapshot> snapshots = new ArrayList<>();

        if (rawVersions != null && !rawVersions.isEmpty()) {
            for (com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion rv : rawVersions) {
                byte[] data = rv.data();
                if (data == null && rv.payload() != null) {
                    data = rv.payload().getBytes(StandardCharsets.UTF_8);
                }
                if (data != null && data.length > 0) {
                    snapshots.add(RecordVersionSnapshot.of(
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
                snapshots.add(RecordVersionSnapshot.of(
                    1,
                    System.currentTimeMillis(),
                    cur,
                    true
                ));
            }
        }

        return snapshots;
    }

    public String getVersionsJson(String engineKey, String db, String coll, String id) {
        List<RecordVersionSnapshot> snapshots = getVersionSnapshots(engineKey, db, coll, id);
        JsonArray arr = new JsonArray();
        for (RecordVersionSnapshot snap : snapshots) {
            arr.add(snap.toJsonObject(jsonParser));
        }
        return jsonParser.toJson(arr);
    }

    public String resolveExistingDatabaseName(String dbName) {
        if (dbName == null || dbName.isBlank()) return "system_db";
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

        try {
            Set<String> phys = engine.getStorageCore().getDatabaseNames();
            if (phys != null) {
                for (String d : phys) {
                    if (d != null && !d.isBlank() && !"_system".equalsIgnoreCase(d) && !"system_db".equalsIgnoreCase(d) && !LsmBTreeHybrid.isReservedDatabaseName(d)) {
                        databases.add(d.trim());
                    }
                }
            }
        } catch (Exception ignored) {}

        databases.removeIf(LsmBTreeHybrid::isReservedDatabaseName);
        databases.removeIf("system_db"::equalsIgnoreCase);
        return databases;
    }

    public String getPrefixForEngine(String engineKey) {
        if (engineKey == null) return "doc:";
        return StorageEngineType.fromString(engineKey)
                .map(StorageEngineType::prefix)
                .orElse("doc:");
    }
}
