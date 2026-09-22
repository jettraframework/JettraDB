package com.jettra.store.engine.operations.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry managing available export format strategies using the Strategy Pattern.
 */
public final class ExportStrategyRegistry {

    private static final Map<String, ExportStrategy> STRATEGIES = new ConcurrentHashMap<>();

    static {
        register(new JsonExportStrategy());
        register(new CsvExportStrategy());
        register(new ExcelExportStrategy());
        register(new BinaryStructuredExportStrategy());
    }

    private ExportStrategyRegistry() {}

    public static void register(ExportStrategy strategy) {
        if (strategy != null) {
            String fmt = strategy.format().toLowerCase();
            STRATEGIES.put(fmt, strategy);
            if ("excel".equalsIgnoreCase(fmt)) {
                STRATEGIES.put("xls", strategy);
                STRATEGIES.put("xlsx", strategy);
            } else if ("binary".equalsIgnoreCase(fmt)) {
                STRATEGIES.put("bin", strategy);
            }
        }
    }

    public static ExportStrategy getStrategy(String format) {
        if (format == null || format.isBlank()) {
            return STRATEGIES.get("json");
        }
        ExportStrategy s = STRATEGIES.get(format.toLowerCase().trim());
        return s != null ? s : STRATEGIES.get("json");
    }

    public static Map<String, String> getAvailableFormats() {
        Map<String, String> map = new LinkedHashMap<>();
        for (ExportStrategy s : STRATEGIES.values()) {
            map.putIfAbsent(s.format(), s.displayName());
        }
        return Collections.unmodifiableMap(map);
    }
}
