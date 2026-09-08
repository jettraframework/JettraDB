package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.models.RecordsEngine;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.json.JsonArray;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Gatherers;

/**
 * RecordModelPayloadHandler - High-performance service, validator, and serializer for JettraDB Record model.
 * Implements Java 25 canonical records, pattern matching for switch/instanceof, Stream Gatherers,
 * and zero-copy/compact byte buffer serialization for the storage engine.
 *
 * Enforces the canonical JettraDB Record schema structure:
 * {
 *   "_recordClass": "com.jettra.model.EmployeeRecord",
 *   "_timestamp": 1788809869770,
 *   "_version": 1,
 *   "_schema": {
 *     "first_name": "String",
 *     ...
 *   },
 *   "components": {
 *     "first_name": "John",
 *     ...
 *   }
 * }
 */
public final class RecordModelPayloadHandler {

    private static final JettraJson JSON = new JettraJson();
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d*\\.\\d+$");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ISO_TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");
    private static final Pattern ISO_LOCAL_DATETIME_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");
    private static final Pattern ISO_OFFSET_OR_ZONED_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}(:?\\d{2})?(\\[.*\\])?)$");

    private RecordModelPayloadHandler() {}

    /**
     * Immutable Canonical Record representation in Java 25.
     */
    public record CanonicalRecord(
        String recordClass,
        long timestamp,
        long version,
        Map<String, String> schema,
        Map<String, Object> components,
        String table,
        String recordId
    ) {
        public CanonicalRecord {
            schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema != null ? schema : Map.of()));
            components = Collections.unmodifiableMap(new LinkedHashMap<>(components != null ? components : Map.of()));
        }

        /**
         * Assembles the canonical JsonObject structure.
         */
        public JsonObject toJsonObject() {
            JsonObject root = new JsonObject();
            root.addProperty("_recordClass", recordClass);
            root.addProperty("_timestamp", timestamp > 0 ? timestamp : System.currentTimeMillis());
            root.addProperty("_version", version > 0 ? version : 1L);

            JsonObject schemaJson = new JsonObject();
            for (Map.Entry<String, String> entry : schema.entrySet()) {
                schemaJson.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("_schema", schemaJson);

            JsonObject compJson = new JsonObject();
            for (Map.Entry<String, Object> entry : components.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Number n) {
                    compJson.addProperty(entry.getKey(), n);
                } else if (val instanceof Boolean b) {
                    compJson.addProperty(entry.getKey(), b);
                } else if (val instanceof Character c) {
                    compJson.addProperty(entry.getKey(), c);
                } else if (val instanceof JsonObject jo) {
                    compJson.add(entry.getKey(), jo);
                } else if (val instanceof JsonArray ja) {
                    compJson.add(entry.getKey(), ja);
                } else if (val instanceof Record rec) {
                    compJson.add(entry.getKey(), RecordsEngine.recordToJson(rec));
                } else if (val != null) {
                    compJson.addProperty(entry.getKey(), val.toString());
                }
            }
            root.add("components", compJson);

            return root;
        }

        /**
         * Serializes directly to compact UTF-8 bytes for zero-copy I/O storage.
         */
        public byte[] toCompactBytes() {
            return JSON.toJson(toJsonObject()).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Returns compact JSON string.
         */
        public String toCompactJson() {
            return JSON.toJson(toJsonObject());
        }
    }

    /**
     * Validates and parses parameters into a CanonicalRecord instance.
     */
    public static ValidationResult validateParameters(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return ValidationResult.failure("Parámetros de inserción vacíos");
        }

        List<String> errors = new ArrayList<>();
        String table = params.get("target_coll");
        if (table == null || table.isBlank()) {
            errors.add("El nombre de la tabla de registros es obligatorio.");
        }

        String rawPayload = params.get("rec_payload");
        String recClass = params.get("rec_class");

        if (rawPayload != null && !rawPayload.isBlank()) {
            try {
                JsonObject parsed = JsonPayloadHelper.parseJsonOrEmpty(rawPayload);
                if (parsed.has("_recordClass")) {
                    String clazz = parsed.getAsString("_recordClass");
                    if (clazz == null || clazz.isBlank()) {
                        errors.add("La propiedad '_recordClass' no puede estar vacía.");
                    }
                }
            } catch (Exception e) {
                errors.add("Error de sintaxis en el payload del Record: " + e.getMessage());
            }
        } else if (recClass == null || recClass.isBlank()) {
            errors.add("La clase canonical del Record (_recordClass) es obligatoria.");
        }

        // Stream Gatherer validation across parameters
        List<String> gathererErrors = params.entrySet().stream()
                .gather(Gatherers.fold(
                        ArrayList<String>::new,
                        (acc, entry) -> {
                            String k = entry.getKey();
                            String v = entry.getValue();
                            if ("target_id".equals(k) && v != null && (v.contains(":") || v.contains("/"))) {
                                acc.add("El identificador del record no puede contener ':' ni '/'");
                            }
                            return acc;
                        }
                ))
                .findFirst()
                .orElseGet(ArrayList::new);

        errors.addAll(gathererErrors);

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Infers typed schema type using modern Java 25 Pattern Matching.
     * Supports primitives, temporals (LocalDate, LocalTime, LocalDateTime, Instant,
     * ZonedDateTime, OffsetDateTime, Date), collections (List, Set, Collection),
     * arrays, enums, and nested records/objects.
     */
    public static String inferType(Object val) {
        return switch (val) {
            case null -> "String";
            case java.time.LocalDate ignored -> "LocalDate";
            case java.time.LocalTime ignored -> "LocalTime";
            case java.time.LocalDateTime ignored -> "LocalDateTime";
            case java.time.Instant ignored -> "Instant";
            case java.time.ZonedDateTime ignored -> "ZonedDateTime";
            case java.time.OffsetDateTime ignored -> "OffsetDateTime";
            case java.util.Date ignored -> "Date";
            case Integer ignored -> "Integer";
            case Long ignored -> "Long";
            case Double ignored -> "Double";
            case Float ignored -> "Float";
            case Boolean ignored -> "Boolean";
            case Byte ignored -> "Byte";
            case Short ignored -> "Short";
            case Character ignored -> "Character";
            case Enum<?> e -> "Enum<" + e.getDeclaringClass().getSimpleName() + ">";
            case Record r -> r.getClass().getSimpleName();
            case List<?> ignored -> "List<String>";
            case Set<?> ignored -> "Set<String>";
            case Collection<?> ignored -> "Collection<String>";
            case io.jettra.json.JsonArray ignored -> "List<String>";
            case JsonObject jo -> jo.has("_recordClass") ? jo.getAsString("_recordClass") : "Object";
            case Number n -> n.toString().contains(".") ? "Double" : "Long";
            case String s -> {
                String trimmed = s.trim();
                if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                    yield "Boolean";
                } else if (INTEGER_PATTERN.matcher(trimmed).matches()) {
                    try {
                        Integer.parseInt(trimmed);
                        yield "Integer";
                    } catch (NumberFormatException nfe) {
                        yield "Long";
                    }
                } else if (DECIMAL_PATTERN.matcher(trimmed).matches()) {
                    yield "Double";
                } else if (ISO_OFFSET_OR_ZONED_PATTERN.matcher(trimmed).matches()) {
                    yield trimmed.contains("[") ? "ZonedDateTime" : (trimmed.endsWith("Z") ? "Instant" : "OffsetDateTime");
                } else if (ISO_LOCAL_DATETIME_PATTERN.matcher(trimmed).matches()) {
                    yield "LocalDateTime";
                } else if (ISO_DATE_PATTERN.matcher(trimmed).matches()) {
                    yield "LocalDate";
                } else if (ISO_TIME_PATTERN.matcher(trimmed).matches()) {
                    yield "LocalTime";
                } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    yield "List<String>";
                } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        JsonObject parsed = JsonPayloadHelper.parseJsonOrEmpty(trimmed);
                        if (parsed.has("_recordClass")) {
                            yield parsed.getAsString("_recordClass");
                        }
                    } catch (Exception ignored2) {}
                    yield "Object";
                } else {
                    yield "String";
                }
            }
            default -> {
                if (val.getClass().isArray()) {
                    yield "Array<" + val.getClass().getComponentType().getSimpleName() + ">";
                }
                yield "String";
            }
        };
    }

    /**
     * Parses raw input string or object according to the typed schema.
     */
    public static Object parseValueForType(Object rawVal, String type) {
        if (rawVal == null) return null;
        if (rawVal instanceof JsonObject || rawVal instanceof io.jettra.json.JsonArray) {
            return rawVal;
        }
        if (rawVal instanceof Record rec) {
            return RecordsEngine.recordToJson(rec);
        }
        String str = rawVal.toString().trim();
        String normalizedType = (type != null && !type.isBlank()) ? type.trim() : "String";
        String lowerType = normalizedType.toLowerCase();

        if (lowerType.equals("integer") || lowerType.equals("int")) {
            try { return Integer.parseInt(str); } catch (Exception e) { return 0; }
        } else if (lowerType.equals("long")) {
            try { return Long.parseLong(str); } catch (Exception e) { return 0L; }
        } else if (lowerType.equals("double")) {
            try { return Double.parseDouble(str); } catch (Exception e) { return 0.0; }
        } else if (lowerType.equals("float")) {
            try { return Float.parseFloat(str); } catch (Exception e) { return 0.0f; }
        } else if (lowerType.equals("short")) {
            try { return Short.parseShort(str); } catch (Exception e) { return (short) 0; }
        } else if (lowerType.equals("byte")) {
            try { return Byte.parseByte(str); } catch (Exception e) { return (byte) 0; }
        } else if (lowerType.equals("boolean") || lowerType.equals("bool")) {
            return Boolean.parseBoolean(str) || "1".equals(str);
        } else if (lowerType.equals("character") || lowerType.equals("char")) {
            return str.isEmpty() ? ' ' : str.charAt(0);
        } else if (lowerType.startsWith("list") || lowerType.startsWith("array") || lowerType.startsWith("set") || lowerType.startsWith("collection")) {
            if (rawVal instanceof Iterable<?> iter) {
                io.jettra.json.JsonArray arr = new io.jettra.json.JsonArray();
                for (Object o : iter) {
                    if (o instanceof Record r) arr.add(RecordsEngine.recordToJson(r));
                    else if (o != null) arr.add(o.toString());
                }
                return arr;
            }
            if (rawVal.getClass().isArray()) {
                io.jettra.json.JsonArray arr = new io.jettra.json.JsonArray();
                int len = java.lang.reflect.Array.getLength(rawVal);
                for (int i = 0; i < len; i++) {
                    Object o = java.lang.reflect.Array.get(rawVal, i);
                    if (o instanceof Record r) arr.add(RecordsEngine.recordToJson(r));
                    else if (o != null) arr.add(o.toString());
                }
                return arr;
            }
            if (str.startsWith("[")) {
                try {
                    return JSON.fromJson(str, io.jettra.json.JsonArray.class);
                } catch (Exception e) {
                    io.jettra.json.JsonArray arr = new io.jettra.json.JsonArray();
                    for (String part : str.replaceAll("[\\[\\]\"]", "").split(",")) {
                        if (!part.trim().isEmpty()) arr.add(part.trim());
                    }
                    return arr;
                }
            }
            io.jettra.json.JsonArray arr = new io.jettra.json.JsonArray();
            if (!str.isEmpty()) arr.add(str);
            return arr;
        } else if (lowerType.startsWith("enum")) {
            return str;
        } else if (str.startsWith("{")) {
            try {
                return JsonPayloadHelper.parseJsonOrEmpty(str);
            } catch (Exception ignored) {
                return str;
            }
        }
        return str;
    }

    /**
     * Builds and validates a CanonicalRecord from raw parameters and target identifiers.
     */
    public static CanonicalRecord buildCanonicalRecord(
            String database,
            String unit,
            String id,
            Map<String, String> params
    ) {
        String table = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "employees");
        String recordId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "rec_" + System.currentTimeMillis());
        String defaultClass = params.getOrDefault("rec_class", "com.jettra.model.EmployeeRecord");
        String rawPayload = params.get("rec_payload");

        Map<String, String> schemaMap = new LinkedHashMap<>();
        Map<String, Object> componentsMap = new LinkedHashMap<>();
        String recordClass = defaultClass;
        long timestamp = System.currentTimeMillis();
        long version = 1L;

        // 1. Check if rawPayload contains a full canonical record JSON
        if (rawPayload != null && !rawPayload.isBlank()) {
            try {
                JsonObject root = JsonPayloadHelper.parseJsonOrEmpty(rawPayload);
                if (root.has("_recordClass")) {
                    recordClass = root.getAsString("_recordClass");
                }
                if (root.has("_timestamp") && root.get("_timestamp") instanceof Number n) {
                    timestamp = n.longValue();
                }
                if (root.has("_version") && root.get("_version") instanceof Number n) {
                    version = n.longValue();
                }

                // Parse schema if present
                if (root.has("_schema") && root.get("_schema") instanceof JsonObject sObj) {
                    for (Map.Entry<String, Object> entry : sObj.entrySet()) {
                        schemaMap.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "String");
                    }
                }

                // Parse components
                JsonObject compSource = root.has("components") && root.get("components") instanceof JsonObject cObj ? cObj : root;
                for (Map.Entry<String, Object> entry : compSource.entrySet()) {
                    String k = entry.getKey();
                    if (k.startsWith("_record") || "_schema".equals(k) || "_timestamp".equals(k) || "_version".equals(k)) {
                        continue;
                    }
                    Object val = entry.getValue();
                    String declaredType = schemaMap.computeIfAbsent(k, key -> inferType(val));
                    componentsMap.put(k, parseValueForType(val, declaredType));
                }
            } catch (Exception ignored) {}
        }

        // 2. Parse from separate rec_schema and rec_components if present
        if (componentsMap.isEmpty()) {
            String schemaJsonStr = params.get("rec_schema");
            if (schemaJsonStr != null && !schemaJsonStr.isBlank()) {
                try {
                    JsonObject sObj = JsonPayloadHelper.parseJsonOrEmpty(schemaJsonStr);
                    for (Map.Entry<String, Object> entry : sObj.entrySet()) {
                        schemaMap.put(entry.getKey(), entry.getValue().toString());
                    }
                } catch (Exception ignored) {}
            }

            String compJsonStr = params.get("rec_components");
            if (compJsonStr != null && !compJsonStr.isBlank()) {
                try {
                    JsonObject cObj = JsonPayloadHelper.parseJsonOrEmpty(compJsonStr);
                    for (Map.Entry<String, Object> entry : cObj.entrySet()) {
                        String k = entry.getKey();
                        Object val = entry.getValue();
                        String declaredType = schemaMap.computeIfAbsent(k, key -> inferType(val));
                        componentsMap.put(k, parseValueForType(val, declaredType));
                    }
                } catch (Exception ignored) {}
            }
        }

        // Ensure table metadata is consistent
        schemaMap.putIfAbsent("_table", "String");
        componentsMap.putIfAbsent("_table", table);

        return new CanonicalRecord(recordClass, timestamp, version, schemaMap, componentsMap, table, recordId);
    }

    /**
     * Dispatches the CanonicalRecord to the storage engine with zero-copy compaction.
     */
    public static InsertionResult dispatchToEngine(
            JettraStorageEngine storageEngine,
            String database,
            CanonicalRecord canonical
    ) {
        RecordsEngine recEngine = (RecordsEngine) storageEngine.getEngine("RECORDS");
        if (recEngine == null) {
            return InsertionResult.ofError("RECORDS", database, canonical.table(), canonical.recordId(),
                    "RecordsEngine not registered in storage orchestrator");
        }

        String tableKey = (canonical.table() != null && !canonical.table().isBlank() && !canonical.table().equalsIgnoreCase("default"))
                ? database + ":" + canonical.table().trim()
                : database;

        JsonObject compJson = new JsonObject();
        for (Map.Entry<String, Object> entry : canonical.components().entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Number n) {
                compJson.addProperty(entry.getKey(), n);
            } else if (val instanceof Boolean b) {
                compJson.addProperty(entry.getKey(), b);
            } else if (val instanceof Character c) {
                compJson.addProperty(entry.getKey(), c);
            } else if (val instanceof JsonObject jo) {
                compJson.add(entry.getKey(), jo);
            } else if (val instanceof io.jettra.json.JsonArray ja) {
                compJson.add(entry.getKey(), ja);
            } else if (val instanceof Record rec) {
                compJson.add(entry.getKey(), RecordsEngine.recordToJson(rec));
            } else if (val != null) {
                compJson.addProperty(entry.getKey(), val.toString());
            }
        }

        JsonObject schemaJson = new JsonObject();
        for (Map.Entry<String, String> entry : canonical.schema().entrySet()) {
            schemaJson.addProperty(entry.getKey(), entry.getValue());
        }

        recEngine.saveRecord(tableKey, canonical.recordId(), canonical.recordClass(), compJson, schemaJson);

        return InsertionResult.ofSuccess("RECORDS", database, canonical.table(), canonical.recordId(),
                "Record '" + canonical.recordId() + "' stored in relational table [" + canonical.table() + "] (Class: " + canonical.recordClass() + ")", 1);
    }
}
