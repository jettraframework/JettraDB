package com.jettra.store.engine.web.workflow;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.HierarchyExplorerService;
import com.jettra.store.engine.web.EditDocumentCommand;
import com.jettra.store.engine.web.EditDocumentEvent;
import com.jettra.store.engine.web.EditDocumentFailureEvent;
import com.jettra.store.engine.web.EditDocumentResult;
import com.jettra.store.engine.web.EditDocumentSuccessEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Concrete implementation of the DocumentSaveWorkflow Template Method under Java 25+.
 * Manages atomic storage persistence, version count verification, and reactive observer notification.
 */
public class StandardDocumentSaveWorkflow extends DocumentSaveWorkflow {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private final Consumer<EditDocumentEvent> eventPublisher;

    public StandardDocumentSaveWorkflow(
            JettraStorageEngine engine,
            HierarchyExplorerService hierarchyService,
            Consumer<EditDocumentEvent> eventPublisher
    ) {
        this.engine = engine;
        this.hierarchyService = hierarchyService != null ? hierarchyService : new HierarchyExplorerService(engine);
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected ResolvedKeys resolveTargetKeys(EditDocumentCommand cmd, String engType, String db, String coll, String id) {
        List<String> candidates = hierarchyService.resolveCandidateKeys(engType, db, coll, id);
        String prefix = getPrefixForEngine(engType);

        String bestKey = null;
        int maxVersion = -1;
        Set<String> existingKeys = new LinkedHashSet<>();

        for (String k : candidates) {
            byte[] existing = engine.getStorageCore().get(k);
            if (existing != null && existing.length > 0) {
                existingKeys.add(k);
                int vCount = engine.getStorageCore().getVersionCount(k);
                if (vCount > maxVersion) {
                    maxVersion = vCount;
                    bestKey = k;
                }
            }
        }

        // If no candidate key currently exists in storage, establish canonical primary and counterpart keys
        if (bestKey == null) {
            boolean hasColl = coll != null && !coll.isBlank() && !"default".equalsIgnoreCase(coll);
            if ("DOCUMENT".equalsIgnoreCase(engType)) {
                // Canonical DocumentEngine key
                bestKey = hasColl ? (db + ":" + coll + ":" + id) : (db + ":" + id);
            } else {
                bestKey = hasColl ? (prefix + db + ":" + coll + ":" + id) : (prefix + db + ":" + id);
            }
            maxVersion = 0;
        }

        // Determine mirror keys to maintain full bidirectional consistency
        Set<String> mirrors = new LinkedHashSet<>();
        // 1. Any existing aliases that were previously written
        for (String existKey : existingKeys) {
            if (!existKey.equals(bestKey)) {
                mirrors.add(existKey);
            }
        }

        // 2. Cross-mirror DocumentEngine canonical key <-> prefixed explorer key
        boolean hasColl = coll != null && !coll.isBlank() && !"default".equalsIgnoreCase(coll);
        String simpleCollKey = db + ":" + coll + ":" + id;
        String prefixCollKey = prefix + db + ":" + coll + ":" + id;
        String simpleDirectKey = db + ":" + id;
        String prefixDirectKey = prefix + db + ":" + id;

        if (bestKey.equals(simpleCollKey)) {
            mirrors.add(prefixCollKey);
        } else if (bestKey.equals(prefixCollKey)) {
            mirrors.add(simpleCollKey);
        } else if (bestKey.equals(simpleDirectKey)) {
            mirrors.add(prefixDirectKey);
        } else if (bestKey.equals(prefixDirectKey)) {
            mirrors.add(simpleDirectKey);
        }

        mirrors.remove(bestKey);
        return new ResolvedKeys(bestKey, new ArrayList<>(mirrors), Math.max(maxVersion, 0));
    }

    @Override
    protected void persistAtomic(ResolvedKeys keys, byte[] payloadBytes, long timestamp) {
        // Persist primary key (increments version in LsmBTreeHybrid)
        engine.getStorageCore().put(keys.primaryKey(), payloadBytes, timestamp);

        // Mirror to counterpart keys for cross-model explorer and DocumentEngine compatibility
        for (String mirrorKey : keys.mirrorKeys()) {
            engine.getStorageCore().put(mirrorKey, payloadBytes, timestamp);
        }
    }

    @Override
    protected int computeVersion(EditDocumentCommand cmd, String engType, String db, String coll, String id, ResolvedKeys keys) {
        int vCount = hierarchyService.getItemVersionCount(engType, db, coll, id);
        if (vCount <= keys.initialVersionCount()) {
            int coreCount = engine.getStorageCore().getVersionCount(keys.primaryKey());
            vCount = Math.max(keys.initialVersionCount() + 1, coreCount);
        }
        return Math.max(vCount, 1);
    }

    @Override
    protected void notifySuccess(EditDocumentCommand cmd, EditDocumentResult result, long timestamp) {
        if (eventPublisher != null && cmd != null && result != null) {
            try {
                eventPublisher.accept(new EditDocumentSuccessEvent(cmd, result, timestamp));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void notifyFailure(EditDocumentCommand cmd, String error) {
        if (eventPublisher != null && cmd != null) {
            try {
                eventPublisher.accept(new EditDocumentFailureEvent(cmd, error, System.currentTimeMillis()));
            } catch (Exception ignored) {}
        }
    }

    private String getPrefixForEngine(String engine) {
        if (engine == null) return "doc:";
        return switch (engine.toUpperCase()) {
            case "DOCUMENT" -> "doc:";
            case "KEYVALUE" -> "kv:";
            case "VECTOR" -> "vec:";
            case "GRAPH" -> "graph:";
            case "TIMESERIES" -> "ts:";
            case "COLUMN" -> "col:";
            case "GEOSPATIAL" -> "geo:";
            case "OBJECT" -> "obj:";
            case "RECORDS" -> "rec:";
            default -> "doc:";
        };
    }
}
