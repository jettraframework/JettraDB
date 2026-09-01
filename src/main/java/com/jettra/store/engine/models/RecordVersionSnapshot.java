package com.jettra.store.engine.models;

import io.jettra.json.JsonObject;
import io.jettra.json.JettraJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable strongly-typed Java 25 Record DTO representing a historical snapshot of an entity.
 * Provides strict validation against nulls for version ID, timestamp, formatted date, snapshot preview, and metadata.
 */
public record RecordVersionSnapshot(
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

    public RecordVersionSnapshot {
        if (versionNumber <= 0) {
            versionNumber = 1;
        }
        if (versionId == null || versionId.isBlank() || "UNDEFINED".equalsIgnoreCase(versionId.trim())) {
            versionId = "v" + versionNumber;
        }
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }
        if (formattedDate == null || formattedDate.isBlank() || "UNDEFINED".equalsIgnoreCase(formattedDate.trim())) {
            formattedDate = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(ISO_FORMATTER);
        }
        if (snapshotData == null) {
            snapshotData = "{}";
        }
        if (snapshotPreview == null || snapshotPreview.isBlank() || "UNDEFINED".equalsIgnoreCase(snapshotPreview.trim())) {
            snapshotPreview = computePreview(snapshotData);
        }
        if (author == null || author.isBlank()) {
            author = "system";
        }
        if (metadata == null) {
            metadata = "Snapshot " + versionId;
        }
    }

    public static String computePreview(String data) {
        if (data == null || data.isBlank()) return "{}";
        String clean = data.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (clean.length() > 65) {
            return clean.substring(0, 65) + "...";
        }
        return clean;
    }

    public static RecordVersionSnapshot of(int versionNumber, long timestamp, byte[] data, boolean isCurrent) {
        String dataStr = data != null ? new String(data, StandardCharsets.UTF_8) : "{}";
        long validTs = timestamp > 0 ? timestamp : System.currentTimeMillis();
        String vId = "v" + Math.max(1, versionNumber);
        String dateStr = Instant.ofEpochMilli(validTs).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER);
        String preview = computePreview(dataStr);
        return new RecordVersionSnapshot(
            Math.max(1, versionNumber),
            vId,
            validTs,
            dateStr,
            dataStr,
            preview,
            isCurrent,
            "system",
            "Snapshot " + vId
        );
    }

    public static RecordVersionSnapshot of(int versionNumber, String versionId, long timestamp, String dataStr, boolean isCurrent) {
        long validTs = timestamp > 0 ? timestamp : System.currentTimeMillis();
        String vId = (versionId != null && !versionId.isBlank() && !"UNDEFINED".equalsIgnoreCase(versionId.trim()))
            ? versionId
            : ("v" + Math.max(1, versionNumber));
        String dateStr = Instant.ofEpochMilli(validTs).atZone(ZoneId.systemDefault()).format(ISO_FORMATTER);
        String preview = computePreview(dataStr != null ? dataStr : "{}");
        return new RecordVersionSnapshot(
            Math.max(1, versionNumber),
            vId,
            validTs,
            dateStr,
            dataStr != null ? dataStr : "{}",
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
