package com.jettra.store.engine.hierarchy;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid;
import com.jettra.store.engine.models.RecordMemento;
import com.jettra.store.engine.models.SnapshotPayload;
import io.jettra.json.JettraJson;

import java.util.List;

/**
 * Service orchestrator for Memento Pattern snapshot capture and atomic rollback restoration.
 * Ensures consistent append-only auditing, Java 25 pattern matching, and storage integrity.
 */
public class MementoService {

    private final JettraStorageEngine engine;
    private final HierarchyExplorerService hierarchyService;
    private final JettraJson jsonParser = new JettraJson();

    public record MementoRestoreResult(
        boolean success,
        RecordMemento restoredMemento,
        long newVersionTimestamp,
        int newVersionNumber,
        String message
    ) {}

    public MementoService(JettraStorageEngine engine) {
        this.engine = engine;
        this.hierarchyService = new HierarchyExplorerService(engine);
    }

    /**
     * Resolves and builds a RecordMemento from the storage partition at a given timestamp.
     */
    public RecordMemento findMemento(String engineType, String database, String unit, String recordId, long targetTimestamp) {
        String prefix = hierarchyService.getPrefixForEngine(engineType);
        String[] candidateKeys = {
            prefix + database + ":" + unit + ":" + recordId,
            prefix + database + ":" + recordId,
            database + ":" + unit + ":" + recordId,
            database + ":" + recordId
        };

        String matchedKey = null;
        byte[] snapshotBytes = null;

        for (String k : candidateKeys) {
            snapshotBytes = engine.getStorageCore().getVersion(k, targetTimestamp);
            if (snapshotBytes != null && snapshotBytes.length > 0) {
                matchedKey = k;
                break;
            }
        }

        if (matchedKey == null || snapshotBytes == null) {
            // Fallback: search in version history list
            for (String k : candidateKeys) {
                List<LsmBTreeHybrid.RecordVersion> history = engine.getStorageCore().getVersionHistory(k);
                if (history != null) {
                    for (LsmBTreeHybrid.RecordVersion rv : history) {
                        if (rv.timestamp() == targetTimestamp) {
                            matchedKey = k;
                            snapshotBytes = rv.data() != null ? rv.data() : (rv.payload() != null ? rv.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
                            break;
                        }
                    }
                }
                if (matchedKey != null && snapshotBytes != null) break;
            }
        }

        if (matchedKey == null || snapshotBytes == null) {
            return null;
        }

        int vCount = engine.getStorageCore().getVersionCount(matchedKey);
        return RecordMemento.of(matchedKey, engineType, database, unit, recordId, vCount, targetTimestamp, snapshotBytes, false, jsonParser);
    }

    /**
     * Atomically restores aggregate state from a RecordMemento using Pattern Matching,
     * persisting the new state via append-only version strategy.
     */
    public MementoRestoreResult applyRollback(RecordMemento memento) {
        if (memento == null) {
            return new MementoRestoreResult(false, null, 0, 0, "Null memento provided for rollback.");
        }

        String key = memento.key();
        if (key == null || key.isBlank()) {
            String prefix = hierarchyService.getPrefixForEngine(memento.engineType());
            key = prefix + memento.database() + ":" + memento.unit() + ":" + memento.recordId();
        }

        // Extract restored byte payload using Java 25 Pattern Matching
        byte[] payloadBytes = switch (memento.payload()) {
            case SnapshotPayload.StructuredJsonPayload s -> s.toBytes();
            case SnapshotPayload.KeyValuePayload kv -> kv.toBytes();
            case SnapshotPayload.RawTextPayload r -> r.toBytes();
            case SnapshotPayload.BinaryPayload b -> b.toBytes();
        };

        if (payloadBytes == null || payloadBytes.length == 0) {
            return new MementoRestoreResult(false, memento, 0, 0, "Snapshot payload is empty for key: " + key);
        }

        long newTimestamp = System.currentTimeMillis();
        // Append-only write: creates new active version from historical snapshot
        engine.getStorageCore().put(key, payloadBytes, newTimestamp);

        int newVersionNumber = engine.getStorageCore().getVersionCount(key);
        String msg = "[" + memento.engineType() + "] Record '" + memento.recordId() + "' successfully restored to snapshot version "
            + memento.formattedDate() + " (Active version is now v" + newVersionNumber + ")!";

        return new MementoRestoreResult(true, memento, newTimestamp, newVersionNumber, msg);
    }
}
