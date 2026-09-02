package com.jettra.store.engine.models;

import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable Memento DTO encapsulating a complete point-in-time state of a record aggregate.
 * Part of the Memento Pattern implementation for reliable rollback and audit consistency.
 */
public record RecordMemento(
    String key,
    String engineType,
    String database,
    String unit,
    String recordId,
    int versionNumber,
    long timestamp,
    String formattedDate,
    SnapshotPayload payload,
    boolean isCurrent,
    String author,
    String metadata
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public RecordMemento {
        if (versionNumber <= 0) versionNumber = 1;
        if (timestamp <= 0) timestamp = System.currentTimeMillis();
        if (formattedDate == null || formattedDate.isBlank()) {
            formattedDate = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(ISO_FORMATTER);
        }
        if (payload == null) {
            payload = new SnapshotPayload.StructuredJsonPayload(new JsonObject(), java.util.Collections.emptyMap(), 0);
        }
        if (author == null || author.isBlank()) author = "system";
        if (metadata == null) metadata = "Snapshot v" + versionNumber;
    }

    public static RecordMemento of(String key, String engineType, String db, String unit, String id,
                                   int vNum, long ts, byte[] data, boolean isCurrent, JettraJson jsonParser) {
        SnapshotPayload payload = SnapshotPayload.fromBytes(data, jsonParser);
        String dateStr = Instant.ofEpochMilli(ts > 0 ? ts : System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(ISO_FORMATTER);
        return new RecordMemento(
            key,
            engineType != null ? engineType : "DOCUMENT",
            db != null ? db : "default",
            unit != null ? unit : "default",
            id != null ? id : "",
            vNum,
            ts > 0 ? ts : System.currentTimeMillis(),
            dateStr,
            payload,
            isCurrent,
            "system",
            "Snapshot v" + vNum
        );
    }

    public byte[] getRawBytes() {
        return payload.toBytes();
    }

    public String getPayloadString() {
        return payload.toDisplayString();
    }

    public RecordVersionSnapshot toSnapshotDto() {
        return RecordVersionSnapshot.of(
            versionNumber,
            "v" + versionNumber,
            timestamp,
            payload.toDisplayString(),
            isCurrent
        );
    }
}
