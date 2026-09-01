package com.jettra.store.engine.hierarchy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * High-performance, streaming RFC 8259 JSON serializer for database hierarchy models.
 * Guarantees zero-truncation, strict character escaping (quotes, backslashes, ASCII control characters),
 * and cycle detection for cross-engine references.
 */
public final class HierarchyJsonStreamer {

    private HierarchyJsonStreamer() {}

    public static String toJson(HierarchyNode.DatabaseNode node) {
        StringWriter sw = new StringWriter(8192);
        try {
            writeDatabaseNode(sw, node);
        } catch (IOException e) {
            throw new RuntimeException("Serialization failure", e);
        }
        return sw.toString();
    }

    public static void streamTo(HierarchyNode.DatabaseNode node, OutputStream os) throws IOException {
        Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        writeDatabaseNode(writer, node);
        writer.flush();
    }

    public static void writeDatabaseNode(Writer w, HierarchyNode.DatabaseNode db) throws IOException {
        w.write('{');
        writeProperty(w, "status", "SUCCESS", true);
        writeProperty(w, "database", db.name(), false);
        writeProperty(w, "totalItems", db.totalItems(), false);
        writeProperty(w, "hasComponents", db.hasComponents(), false);

        w.write(",\"engines\":[");
        boolean firstEng = true;
        if (db.engines() != null) {
            for (HierarchyNode.EngineNode eng : db.engines()) {
                if (!firstEng) w.write(',');
                writeEngineNode(w, eng);
                firstEng = false;
            }
        }
        w.write(']');

        w.write(",\"indexes\":[");
        boolean firstIdx = true;
        if (db.indexes() != null) {
            for (HierarchyNode.IndexNode idx : db.indexes()) {
                if (!firstIdx) w.write(',');
                writeIndexNode(w, idx);
                firstIdx = false;
            }
        }
        w.write(']');

        w.write(",\"schemas\":[");
        boolean firstSc = true;
        if (db.schemas() != null) {
            for (HierarchyNode.SchemaNode sc : db.schemas()) {
                if (!firstSc) w.write(',');
                writeSchemaNode(w, sc);
                firstSc = false;
            }
        }
        w.write(']');

        w.write('}');
    }

    private static void writeEngineNode(Writer w, HierarchyNode.EngineNode eng) throws IOException {
        w.write('{');
        writeProperty(w, "name", eng.name(), true);
        writeProperty(w, "color", eng.color(), false);
        writeProperty(w, "icon", eng.icon(), false);
        writeProperty(w, "unitPlural", eng.unitPlural(), false);
        writeProperty(w, "unitSingle", eng.unitSingle(), false);
        writeProperty(w, "itemLabel", eng.itemLabel(), false);
        writeProperty(w, "itemIcon", eng.itemIcon(), false);
        writeProperty(w, "totalItems", eng.totalItems(), false);

        w.write(",\"units\":[");
        boolean firstUnit = true;
        if (eng.units() != null) {
            for (HierarchyNode.UnitNode unit : eng.units()) {
                if (!firstUnit) w.write(',');
                writeUnitNode(w, unit);
                firstUnit = false;
            }
        }
        w.write(']');
        w.write('}');
    }

    private static void writeUnitNode(Writer w, HierarchyNode.UnitNode unit) throws IOException {
        w.write('{');
        writeProperty(w, "name", unit.name(), true);
        writeProperty(w, "totalItems", unit.totalItems(), false);

        w.write(",\"items\":[");
        boolean firstItem = true;
        if (unit.items() != null) {
            for (HierarchyNode.RecordNode item : unit.items()) {
                if (!firstItem) w.write(',');
                writeRecordNode(w, item);
                firstItem = false;
            }
        }
        w.write(']');
        w.write('}');
    }

    private static void writeRecordNode(Writer w, HierarchyNode.RecordNode rec) throws IOException {
        w.write('{');
        writeProperty(w, "id", rec.id(), true);
        writeProperty(w, "engine", rec.engine(), false);
        writeProperty(w, "unit", rec.unit(), false);
        writeProperty(w, "versionCount", rec.versionCount(), false);
        writeProperty(w, "payload", rec.rawPayload(), false);
        writeProperty(w, "payloadB64", rec.payloadB64(), false);
        writeProperty(w, "versionsB64", rec.versionsB64(), false);

        w.write(",\"summaryProps\":");
        writeMap(w, rec.summaryProps(), Collections.newSetFromMap(new IdentityHashMap<>()));
        w.write('}');
    }

    private static void writeIndexNode(Writer w, HierarchyNode.IndexNode idx) throws IOException {
        w.write('{');
        writeProperty(w, "name", idx.name(), true);
        writeProperty(w, "type", idx.type(), false);
        writeProperty(w, "targetUnit", idx.targetUnit(), false);
        writeProperty(w, "collection", idx.targetUnit(), false);
        writeProperty(w, "field", idx.fieldPath(), false);
        writeProperty(w, "fieldPath", idx.fieldPath(), false);
        writeProperty(w, "entryCount", idx.entryCount(), false);
        w.write('}');
    }

    private static void writeSchemaNode(Writer w, HierarchyNode.SchemaNode sc) throws IOException {
        w.write('{');
        writeProperty(w, "name", sc.name(), true);
        writeProperty(w, "schemaJson", sc.schemaJson(), false);
        writeProperty(w, "schemaB64", sc.schemaB64(), false);
        w.write('}');
    }

    private static void writeProperty(Writer w, String key, String value, boolean isFirst) throws IOException {
        if (!isFirst) w.write(',');
        w.write('"');
        escapeJsonString(w, key);
        w.write("\":");
        if (value == null) {
            w.write("null");
        } else {
            w.write('"');
            escapeJsonString(w, value);
            w.write('"');
        }
    }

    private static void writeProperty(Writer w, String key, int value, boolean isFirst) throws IOException {
        if (!isFirst) w.write(',');
        w.write('"');
        escapeJsonString(w, key);
        w.write("\":");
        w.write(Integer.toString(value));
    }

    private static void writeProperty(Writer w, String key, boolean value, boolean isFirst) throws IOException {
        if (!isFirst) w.write(',');
        w.write('"');
        escapeJsonString(w, key);
        w.write("\":");
        w.write(Boolean.toString(value));
    }

    public static void writeValue(Writer w, Object value, Set<Object> visited) throws IOException {
        if (value == null) {
            w.write("null");
            return;
        }
        if (value instanceof String s) {
            w.write('"');
            escapeJsonString(w, s);
            w.write('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            w.write(value.toString());
        } else if (value instanceof Map<?, ?> m) {
            writeMap(w, m, visited);
        } else if (value instanceof Collection<?> c) {
            writeCollection(w, c, visited);
        } else if (value.getClass().isArray()) {
            writeCollection(w, java.util.Arrays.asList((Object[]) value), visited);
        } else {
            w.write('"');
            escapeJsonString(w, value.toString());
            w.write('"');
        }
    }

    private static void writeMap(Writer w, Map<?, ?> map, Set<Object> visited) throws IOException {
        if (map == null) {
            w.write("{}");
            return;
        }
        if (visited.contains(map)) {
            w.write("\"[Circular Reference]\"");
            return;
        }
        visited.add(map);
        w.write('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) w.write(',');
            w.write('"');
            escapeJsonString(w, String.valueOf(entry.getKey()));
            w.write("\":");
            writeValue(w, entry.getValue(), visited);
            first = false;
        }
        w.write('}');
        visited.remove(map);
    }

    private static void writeCollection(Writer w, Collection<?> col, Set<Object> visited) throws IOException {
        if (col == null) {
            w.write("[]");
            return;
        }
        if (visited.contains(col)) {
            w.write("\"[Circular Array Reference]\"");
            return;
        }
        visited.add(col);
        w.write('[');
        boolean first = true;
        for (Object item : col) {
            if (!first) w.write(',');
            writeValue(w, item, visited);
            first = false;
        }
        w.write(']');
        visited.remove(col);
    }

    /**
     * Strict RFC 8259 String escaping for JSON.
     */
    public static void escapeJsonString(Writer w, String s) throws IOException {
        if (s == null) return;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> w.write("\\\"");
                case '\\' -> w.write("\\\\");
                case '\b' -> w.write("\\b");
                case '\f' -> w.write("\\f");
                case '\n' -> w.write("\\n");
                case '\r' -> w.write("\\r");
                case '\t' -> w.write("\\t");
                default -> {
                    if (c < 0x20) {
                        w.write(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        w.write(c);
                    }
                }
            }
        }
    }
}
