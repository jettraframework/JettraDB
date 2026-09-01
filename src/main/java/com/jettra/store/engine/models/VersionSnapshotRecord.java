package com.jettra.store.engine.models;

import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable strongly-typed Java 25 record representing a historical snapshot of a storage record.
 * Encapsulates version identification, temporal metadata, structured snapshot data, and presentation previews.
 */
public record VersionSnapshotRecord(
    int versionNumber,
    String versionId,
    long timestamp,
    String formattedDate,
    String snapshotData,
    String snapshotPreview,
    boolean isCurrent,
    String author,
    String metadata
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public VersionSnapshotRecord {
        if (versionId == null || versionId.isBlank()) {
            versionId = "v" + versionNumber;
        }
        if (formattedDate == null || formattedDate.isBlank()) {
            if (timestamp > 0) {
                formattedDate = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(ISO_FORMATTER);
            } else {
                formattedDate = Instant.now()
                    .atZone(ZoneId.systemDefault())
                    .format(ISO_FORMATTER);
            }
        }
        if (snapshotData == null) {
            snapshotData = "{}";
        }
        if (snapshotPreview == null || snapshotPreview.isBlank()) {
            snapshotPreview = computePreview(snapshotData);
        }
        if (author == null) {
            author = "system";
        }
        if (metadata == null) {
            metadata = "";
        }
    }

    public static String computePreview(String data) {
        if (data == null || data.isBlank()) return "{}";
        String trimmed = data.trim().replaceAll("[\\r\\n]+", " ");
        if (trimmed.length() > 65) {
            return trimmed.substring(0, 65) + "...";
        }
        return trimmed;
    }

    public static VersionSnapshotRecord of(int versionNumber, long timestamp, byte[] data, boolean isCurrent) {
        String dataStr = data != null ? new String(data, StandardCharsets.UTF_8) : "{}";
        String vId = "v" + versionNumber;
        String dateStr = timestamp > 0
            ? Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER)
            : Instant.now().atZone(ZoneId.systemDefault()).format(ISO_FORMATTER);
        String preview = computePreview(dataStr);
        return new VersionSnapshotRecord(
            versionNumber,
            vId,
            timestamp > 0 ? timestamp : System.currentTimeMillis(),
            dateStr,
            dataStr,
            preview,
            isCurrent,
            "system",
            "Snapshot " + vId
        );
    }

    public JsonObject toJsonObject(JettraJson jsonParser) {
        JsonObject obj = new JsonObject();
        obj.addProperty("versionNumber", versionNumber);
        obj.addProperty("versionId", versionId);
        obj.addProperty("version", versionNumber);
        obj.addProperty("timestamp", timestamp);
        obj.addProperty("formattedDate", formattedDate);
        obj.addProperty("snapshotData", snapshotData);
        obj.addProperty("payload", snapshotData);
        obj.addProperty("snapshotPreview", snapshotPreview);
        obj.addProperty("payloadPreview", snapshotPreview);
        obj.addProperty("preview", snapshotPreview);
        obj.addProperty("isCurrent", isCurrent);
        obj.addProperty("author", author);
        obj.addProperty("metadata", metadata);
        return obj;
    }
}
