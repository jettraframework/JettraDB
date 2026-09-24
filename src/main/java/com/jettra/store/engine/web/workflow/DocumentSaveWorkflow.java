package com.jettra.store.engine.web.workflow;

import com.jettra.store.engine.web.EditDocumentCommand;
import com.jettra.store.engine.web.EditDocumentResult;
import com.jettra.store.engine.web.builder.DocumentPayloadBuilder;

import java.util.List;

/**
 * Abstract base class implementing the Template Method Pattern for document
 * and multi-model record persistence and versioning under Java 25+.
 *
 * Invariant algorithm execution flow:
 * 1. validate(cmd)
 * 2. resolveTargetKeys(cmd)
 * 3. buildPayload(cmd)
 * 4. persistAtomic(keys, payloadBytes, timestamp)
 * 5. computeVersion(cmd, keys)
 * 6. buildResult(...) & notifySuccess(...)
 */
public abstract class DocumentSaveWorkflow {

    /**
     * Immutable record encapsulating the primary storage key and associated mirror/alias keys.
     */
    public record ResolvedKeys(String primaryKey, List<String> mirrorKeys, int initialVersionCount) {}

    /**
     * Template Method defining the standard, invariant lifecycle for persisting document modifications.
     */
    public final EditDocumentResult execute(EditDocumentCommand cmd) {
        if (cmd == null) {
            String err = "Command cannot be null";
            notifyFailure(null, err);
            return EditDocumentResult.failure("UNKNOWN", "", "", "", err);
        }

        String engType = normalizeEngineType(cmd.engineType());
        String db = cmd.database() != null ? cmd.database() : "default_db";
        String coll = cmd.collection() != null ? cmd.collection() : "default";
        String id = cmd.recordId() != null ? cmd.recordId() : "";

        // 1. Validation Step
        ValidationOutcome validation = validate(cmd);
        if (!validation.isValid()) {
            notifyFailure(cmd, validation.errorMessage());
            return EditDocumentResult.failure(engType, db, coll, id, validation.errorMessage());
        }

        try {
            long now = System.currentTimeMillis();

            // 2. Key Resolution Step
            ResolvedKeys keys = resolveTargetKeys(cmd, engType, db, coll, id);

            // 3. Payload Construction Step (via Builder Pattern)
            int targetVersion = Math.max(keys.initialVersionCount() + 1, 2);
            DocumentPayloadBuilder.BuiltPayload built = buildPayload(cmd, engType, coll, id, targetVersion);

            // 4. Atomic Persistence & Mirroring Step
            persistAtomic(keys, built.payloadBytes(), now);

            // 5. Version Count Computation Step
            int newVersionCount = computeVersion(cmd, engType, db, coll, id, keys);

            // 6. Build Result and Publish Reactive Event
            String successMsg = String.format("[%s] Record '%s' updated successfully (new version v%d created)!",
                    engType, id, newVersionCount);
            EditDocumentResult result = EditDocumentResult.success(engType, db, coll, id, now, newVersionCount, successMsg);

            notifySuccess(cmd, result, now);
            return result;

        } catch (Exception e) {
            String errorMsg = "Persistence error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            notifyFailure(cmd, errorMsg);
            return EditDocumentResult.failure(engType, db, coll, id, errorMsg);
        }
    }

    public record ValidationOutcome(boolean isValid, String errorMessage) {
        public static ValidationOutcome ok() { return new ValidationOutcome(true, null); }
        public static ValidationOutcome error(String msg) { return new ValidationOutcome(false, msg); }
    }

    protected ValidationOutcome validate(EditDocumentCommand cmd) {
        if (cmd.recordId() == null || cmd.recordId().isBlank()) {
            return ValidationOutcome.error("Target Record/Document ID cannot be empty");
        }
        return ValidationOutcome.ok();
    }

    protected abstract ResolvedKeys resolveTargetKeys(EditDocumentCommand cmd, String engType, String db, String coll, String id);

    protected DocumentPayloadBuilder.BuiltPayload buildPayload(EditDocumentCommand cmd, String engType, String coll, String id) {
        return buildPayload(cmd, engType, coll, id, 2);
    }

    protected DocumentPayloadBuilder.BuiltPayload buildPayload(EditDocumentCommand cmd, String engType, String coll, String id, int targetVersion) {
        return DocumentPayloadBuilder.builder()
                .engineType(engType)
                .collection(coll)
                .recordId(id)
                .rawPayload(cmd.payload())
                .params(cmd.extraParams())
                .version(targetVersion)
                .build();
    }

    protected abstract void persistAtomic(ResolvedKeys keys, byte[] payloadBytes, long timestamp);

    protected abstract int computeVersion(EditDocumentCommand cmd, String engType, String db, String coll, String id, ResolvedKeys keys);

    protected abstract void notifySuccess(EditDocumentCommand cmd, EditDocumentResult result, long timestamp);

    protected abstract void notifyFailure(EditDocumentCommand cmd, String error);

    protected String normalizeEngineType(String engineType) {
        if (engineType == null || engineType.isBlank()) return "DOCUMENT";
        String upper = engineType.toUpperCase();
        if ("RECORD".equals(upper)) return "RECORDS";
        if ("KEY_VALUE".equals(upper) || "KEY-VALUE".equals(upper)) return "KEYVALUE";
        if ("TIME_SERIES".equals(upper) || "TIMESERIE".equals(upper)) return "TIMESERIES";
        if ("GEO".equals(upper)) return "GEOSPATIAL";
        return upper;
    }
}
