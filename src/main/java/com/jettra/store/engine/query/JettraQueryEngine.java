package com.jettra.store.engine.query;

import com.jettra.store.engine.core.JettraStorageEngine;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JettraQueryEngine: Advanced multi-model query engine supporting both:
 * 1. JettraQueryLanguage (JQL) Declarative SQL-like Syntax
 * 2. Java Lambda Stream Fluent API Syntax
 *
 * Provides in-memory acceleration, indexing, schema-less field extraction,
 * multi-condition filtering, sorting, projection, and pagination.
 */
public class JettraQueryEngine {

    private final JettraStorageEngine storageEngine;
    private final JettraJson jsonParser;

    public JettraQueryEngine(JettraStorageEngine storageEngine) {
        this.storageEngine = storageEngine;
        this.jsonParser = new JettraJson();
    }

    public record QueryResultRow(
        String id,
        String database,
        String engineType,
        int versionCount,
        JsonObject data,
        String rawPayload
    ) {}

    public record QueryResult(
        String queryType,
        String originalQuery,
        long executionTimeMs,
        int totalScanned,
        int totalMatched,
        List<String> projectedFields,
        List<QueryResultRow> rows,
        String executionPlan
    ) {}

    /**
     * Automatically detects query mode (JQL or Java Stream) and executes against the target database.
     */
    public QueryResult execute(String query, String defaultDb) {
        long startTime = System.nanoTime();
        if (query == null || query.trim().isEmpty()) {
            return new QueryResult("EMPTY", query, 0, 0, 0, List.of(), List.of(), "Empty query string provided.");
        }

        String trimmed = query.trim();
        QueryResult result;
        if (trimmed.startsWith(".") || trimmed.startsWith("stream()") || trimmed.contains("->") || trimmed.contains(".filter(") || trimmed.contains(".map(")) {
            result = executeStreamQuery(trimmed, defaultDb, startTime);
        } else {
            result = executeJqlQuery(trimmed, defaultDb, startTime);
        }
        return result;
    }

    // =========================================================================
    // 1. JettraQueryLanguage (JQL) Declarative Engine
    // =========================================================================

    private QueryResult executeJqlQuery(String jql, String defaultDb, long startTime) {
        String dbName = defaultDb;
        List<String> projectedFields = new ArrayList<>();
        List<Predicate<QueryResultRow>> filters = new ArrayList<>();
        String sortField = null;
        boolean sortAsc = true;
        int limit = 100;
        int skip = 0;

        String normalized = jql.replaceAll("\\s+", " ").trim();

        // 1. Extract SELECT / Projection
        Pattern selectPattern = Pattern.compile("(?i)^SELECT\\s+(.+?)\\s+FROM\\s+(\\S+)(.*)$");
        Pattern fromPattern = Pattern.compile("(?i)^FROM\\s+(\\S+)(.*)$");

        String remainder = normalized;
        Matcher mSelect = selectPattern.matcher(normalized);
        if (mSelect.matches()) {
            String fieldsPart = mSelect.group(1).trim();
            dbName = mSelect.group(2).trim();
            remainder = mSelect.group(3).trim();

            if (!"*".equals(fieldsPart)) {
                for (String f : fieldsPart.split(",")) {
                    projectedFields.add(f.trim());
                }
            }
        } else {
            Matcher mFrom = fromPattern.matcher(normalized);
            if (mFrom.matches()) {
                dbName = mFrom.group(1).trim();
                remainder = mFrom.group(2).trim();
            }
        }

        // 2. Extract LIMIT & SKIP
        Pattern limitPattern = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");
        Matcher mLimit = limitPattern.matcher(remainder);
        if (mLimit.find()) {
            limit = Integer.parseInt(mLimit.group(1));
            remainder = remainder.substring(0, mLimit.start()) + remainder.substring(mLimit.end());
        }

        Pattern skipPattern = Pattern.compile("(?i)\\b(SKIP|OFFSET)\\s+(\\d+)");
        Matcher mSkip = skipPattern.matcher(remainder);
        if (mSkip.find()) {
            skip = Integer.parseInt(mSkip.group(2));
            remainder = remainder.substring(0, mSkip.start()) + remainder.substring(mSkip.end());
        }

        // 3. Extract ORDER BY
        Pattern orderPattern = Pattern.compile("(?i)\\bORDER\\s+BY\\s+([a-zA-Z0-9_.]+)(\\s+(ASC|DESC))?");
        Matcher mOrder = orderPattern.matcher(remainder);
        if (mOrder.find()) {
            sortField = mOrder.group(1).trim();
            String dir = mOrder.group(3);
            if (dir != null && dir.equalsIgnoreCase("DESC")) {
                sortAsc = false;
            }
            remainder = remainder.substring(0, mOrder.start()) + remainder.substring(mOrder.end());
        }

        // 4. Extract WHERE Conditions
        Pattern wherePattern = Pattern.compile("(?i)\\bWHERE\\s+(.+)$");
        Matcher mWhere = wherePattern.matcher(remainder.trim());
        if (mWhere.find()) {
            String whereClause = mWhere.group(1).trim();
            filters.addAll(parseWhereClause(whereClause));
        }

        // 5. Scan & Process
        List<QueryResultRow> allRecords = scanDatabaseRecords(dbName);
        int totalScanned = allRecords.size();

        Stream<QueryResultRow> stream = allRecords.stream();
        for (Predicate<QueryResultRow> filter : filters) {
            stream = stream.filter(filter);
        }

        if (sortField != null) {
            final String f = sortField;
            final boolean asc = sortAsc;
            stream = stream.sorted((r1, r2) -> {
                String v1 = extractField(r1, f);
                String v2 = extractField(r2, f);
                int cmp;
                try {
                    double d1 = Double.parseDouble(v1);
                    double d2 = Double.parseDouble(v2);
                    cmp = Double.compare(d1, d2);
                } catch (Exception e) {
                    cmp = Objects.compare(v1, v2, String::compareToIgnoreCase);
                }
                return asc ? cmp : -cmp;
            });
        }

        List<QueryResultRow> matched = stream.collect(Collectors.toList());
        int totalMatched = matched.size();

        List<QueryResultRow> paginated = matched.stream()
                .skip(skip)
                .limit(limit)
                .collect(Collectors.toList());

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        String plan = "JQL Engine [Target DB: " + dbName + ", Scanned: " + totalScanned + ", Matched: " + totalMatched + ", Limit: " + limit + "]";

        return new QueryResult("JQL (Declarative)", jql, elapsedMs, totalScanned, totalMatched, projectedFields, paginated, plan);
    }

    private List<Predicate<QueryResultRow>> parseWhereClause(String where) {
        List<Predicate<QueryResultRow>> list = new ArrayList<>();
        String[] andParts = where.split("(?i)\\s+AND\\s+");

        for (String part : andParts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            Pattern compPattern = Pattern.compile("([a-zA-Z0-9_.]+)\\s*(=|!=|<>|>=|<=|>|<|LIKE|CONTAINS|ILIKE)\\s*(.+)");
            Matcher m = compPattern.matcher(part);
            if (m.matches()) {
                String field = m.group(1).trim();
                String op = m.group(2).trim().toUpperCase();
                String rawVal = m.group(3).trim();
                if ((rawVal.startsWith("'") && rawVal.endsWith("'")) || (rawVal.startsWith("\"") && rawVal.endsWith("\""))) {
                    rawVal = rawVal.substring(1, rawVal.length() - 1);
                }
                final String targetVal = rawVal;

                list.add(row -> {
                    String actualVal = extractField(row, field);
                    if (actualVal == null) return false;

                    switch (op) {
                        case "=" -> { return actualVal.equalsIgnoreCase(targetVal); }
                        case "!=", "<>" -> { return !actualVal.equalsIgnoreCase(targetVal); }
                        case "CONTAINS", "LIKE", "ILIKE" -> {
                            String needle = targetVal.replace("%", "").toLowerCase();
                            return actualVal.toLowerCase().contains(needle);
                        }
                        case ">" -> { return compareNumeric(actualVal, targetVal) > 0; }
                        case ">=" -> { return compareNumeric(actualVal, targetVal) >= 0; }
                        case "<" -> { return compareNumeric(actualVal, targetVal) < 0; }
                        case "<=" -> { return compareNumeric(actualVal, targetVal) <= 0; }
                        default -> { return actualVal.contains(targetVal); }
                    }
                });
            }
        }
        return list;
    }

    // =========================================================================
    // 2. Java Lambda Stream Fluent API Engine
    // =========================================================================

    private QueryResult executeStreamQuery(String streamExpr, String defaultDb, long startTime) {
        String dbName = defaultDb;
        int limit = 100;
        int skip = 0;
        List<String> projectedFields = new ArrayList<>();
        List<Predicate<QueryResultRow>> filters = new ArrayList<>();
        String sortField = null;
        boolean sortAsc = true;

        // Parse chained fluent pipeline stages: .filter(...), .map(...), .sorted(...), .limit(...)
        Pattern stagePattern = Pattern.compile("\\.([a-zA-Z0-9_]+)\\s*\\((.*?)\\)");
        Matcher m = stagePattern.matcher(streamExpr);

        while (m.find()) {
            String stage = m.group(1);
            String arg = m.group(2).trim();

            switch (stage) {
                case "filter" -> {
                    Predicate<QueryResultRow> p = parseLambdaPredicate(arg);
                    if (p != null) filters.add(p);
                }
                case "map" -> {
                    // Extract projected field: d -> d.get("name") or doc.name
                    Matcher mapField = Pattern.compile("[\"']([a-zA-Z0-9_.]+)[\"']").matcher(arg);
                    if (mapField.find()) {
                        projectedFields.add(mapField.group(1));
                    }
                }
                case "sorted" -> {
                    Matcher sortM = Pattern.compile("[\"']([a-zA-Z0-9_.]+)[\"']").matcher(arg);
                    if (sortM.find()) {
                        sortField = sortM.group(1);
                    }
                    if (arg.contains("reversed()") || arg.contains("DESC") || arg.contains("b - a") || arg.contains("b.get")) {
                        sortAsc = false;
                    }
                }
                case "limit" -> {
                    try { limit = Integer.parseInt(arg.trim()); } catch (Exception ignored) {}
                }
                case "skip" -> {
                    try { skip = Integer.parseInt(arg.trim()); } catch (Exception ignored) {}
                }
            }
        }

        List<QueryResultRow> allRecords = scanDatabaseRecords(dbName);
        int totalScanned = allRecords.size();

        Stream<QueryResultRow> stream = allRecords.stream();
        for (Predicate<QueryResultRow> filter : filters) {
            stream = stream.filter(filter);
        }

        if (sortField != null) {
            final String f = sortField;
            final boolean asc = sortAsc;
            stream = stream.sorted((r1, r2) -> {
                String v1 = extractField(r1, f);
                String v2 = extractField(r2, f);
                int cmp = compareNumeric(v1, v2);
                if (cmp == 0 && v1 != null && v2 != null) {
                    cmp = v1.compareToIgnoreCase(v2);
                }
                return asc ? cmp : -cmp;
            });
        }

        List<QueryResultRow> matched = stream.collect(Collectors.toList());
        int totalMatched = matched.size();

        List<QueryResultRow> paginated = matched.stream()
                .skip(skip)
                .limit(limit)
                .collect(Collectors.toList());

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        String plan = "Java 25 Fluent Stream Pipeline [Target DB: " + dbName + ", Pipeline: " + streamExpr + ", Total Scanned: " + totalScanned + "]";

        return new QueryResult("Java Lambda Stream Fluent API", streamExpr, elapsedMs, totalScanned, totalMatched, projectedFields, paginated, plan);
    }

    private Predicate<QueryResultRow> parseLambdaPredicate(String lambdaBody) {
        // e.g. doc -> doc.get("status").equals("ACTIVE")
        // e.g. d -> d.getInt("score") >= 90
        // e.g. d.get("score") > 80
        // e.g. d.contains("email")
        Pattern getEquals = Pattern.compile("(?i)\\.get(?:String)?\\([\"']([a-zA-Z0-9_.]+)[\"']\\)\\.equals\\([\"'](.*?)[\"']\\)");
        Matcher mEquals = getEquals.matcher(lambdaBody);
        if (mEquals.find()) {
            String f = mEquals.group(1);
            String val = mEquals.group(2);
            return row -> val.equalsIgnoreCase(extractField(row, f));
        }

        Pattern getCompare = Pattern.compile("(?i)\\.get(?:Int|Double|Long|Number)?\\([\"']([a-zA-Z0-9_.]+)[\"']\\)\\s*(==|!=|>=|<=|>|<)\\s*([0-9.]+|[\"'].*?[\"'])");
        Matcher mComp = getCompare.matcher(lambdaBody);
        if (mComp.find()) {
            String f = mComp.group(1);
            String op = mComp.group(2);
            String target = mComp.group(3).replace("\"", "").replace("'", "");
            return row -> {
                String actual = extractField(row, f);
                if (actual == null) return false;
                return switch (op) {
                    case "==" -> actual.equalsIgnoreCase(target);
                    case "!=" -> !actual.equalsIgnoreCase(target);
                    case ">" -> compareNumeric(actual, target) > 0;
                    case ">=" -> compareNumeric(actual, target) >= 0;
                    case "<" -> compareNumeric(actual, target) < 0;
                    case "<=" -> compareNumeric(actual, target) <= 0;
                    default -> false;
                };
            };
        }

        Pattern getContains = Pattern.compile("(?i)\\.get(?:String)?\\([\"']([a-zA-Z0-9_.]+)[\"']\\)\\.contains\\([\"'](.*?)[\"']\\)");
        Matcher mCont = getContains.matcher(lambdaBody);
        if (mCont.find()) {
            String f = mCont.group(1);
            String val = mCont.group(2).toLowerCase();
            return row -> {
                String act = extractField(row, f);
                return act != null && act.toLowerCase().contains(val);
            };
        }

        Pattern hasKey = Pattern.compile("(?i)\\.contains\\([\"']([a-zA-Z0-9_.]+)[\"']\\)");
        Matcher mKey = hasKey.matcher(lambdaBody);
        if (mKey.find()) {
            String f = mKey.group(1);
            return row -> extractField(row, f) != null;
        }

        return null;
    }

    // =========================================================================
    // Helper Methods: Storage Scanning and JSON Field Extraction
    // =========================================================================

    private List<QueryResultRow> scanDatabaseRecords(String targetDb) {
        List<QueryResultRow> list = new ArrayList<>();
        String[] prefixes = {"", "rec:", "kv:", "vec:", "graph:", "ts:", "col:", "geo:", "obj:"};
        boolean allDbs = "*".equals(targetDb) || targetDb == null || targetDb.isBlank();

        for (String pfx : prefixes) {
            String searchPrefix = allDbs ? pfx : pfx + targetDb + ":";
            Map<String, byte[]> raw = storageEngine.getStorageCore().scanPrefix(searchPrefix);

            for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
                String key = entry.getKey();
                byte[] val = entry.getValue();
                if (val == null || val.length == 0) continue;

                String rawStr = new String(val, StandardCharsets.UTF_8);
                if (rawStr.isBlank() || rawStr.equals("__TOMBSTONE__")) continue;

                String withoutPfx = pfx.isEmpty() ? key : key.substring(pfx.length());
                int colonIdx = withoutPfx.indexOf(':');
                if (colonIdx < 0) continue;

                String db = withoutPfx.substring(0, colonIdx);
                String id = withoutPfx.substring(colonIdx + 1);
                String engineType = determineEngineType(pfx);

                if (!allDbs && !db.equalsIgnoreCase(targetDb)) continue;

                JsonObject parsedJson = null;
                try {
                    parsedJson = jsonParser.fromJson(rawStr, JsonObject.class);
                } catch (Exception ignored) {}

                if (parsedJson == null) {
                    parsedJson = new JsonObject();
                    parsedJson.addProperty("raw", rawStr);
                }

                int vCount = Math.max(1, storageEngine.getStorageCore().getVersionCount(key));
                list.add(new QueryResultRow(id, db, engineType, vCount, parsedJson, rawStr));
            }
        }
        return list;
    }

    private String extractField(QueryResultRow row, String fieldName) {
        if ("id".equalsIgnoreCase(fieldName) || "_id".equalsIgnoreCase(fieldName)) return row.id();
        if ("database".equalsIgnoreCase(fieldName) || "_db".equalsIgnoreCase(fieldName)) return row.database();
        if ("engine".equalsIgnoreCase(fieldName) || "_engine".equalsIgnoreCase(fieldName)) return row.engineType();

        if (row.data() != null) {
            if (row.data().has(fieldName)) {
                return row.data().getAsString(fieldName);
            }
            // Check components in records
            if (row.data().has("components")) {
                JsonObject comp = row.data().getAsJsonObject("components");
                if (comp != null && comp.has(fieldName)) {
                    return comp.getAsString(fieldName);
                }
            }
        }
        return null;
    }

    private int compareNumeric(String a, String b) {
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        } catch (Exception e) {
            return Objects.compare(a, b, Comparator.nullsLast(String::compareToIgnoreCase));
        }
    }

    private String determineEngineType(String prefix) {
        return switch (prefix) {
            case "rec:" -> "RECORDS";
            case "kv:" -> "KEYVALUE";
            case "vec:" -> "VECTOR";
            case "graph:" -> "GRAPH";
            case "ts:" -> "TIMESERIES";
            case "col:" -> "COLUMN";
            case "geo:" -> "GEOSPATIAL";
            case "obj:" -> "OBJECT";
            default -> "DOCUMENT";
        };
    }
}
