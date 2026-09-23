package com.jettra.store.engine.web.view;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Query encapsulation for Tree View node pagination using the Builder Pattern.
 * Decouples pagination parameter parsing, calculation, and collection slicing
 * for individual engines and storage units.
 */
public final class TreePaginationQuery {

    public static final int DEFAULT_PAGE_SIZE = 15;

    private final String database;
    private final String engine;
    private final String unit;
    private final int page;
    private final int pageSize;

    private TreePaginationQuery(Builder builder) {
        this.database = builder.database;
        this.engine = builder.engine;
        this.unit = builder.unit;
        this.page = builder.page;
        this.pageSize = builder.pageSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getDatabase() {
        return database;
    }

    public String getEngine() {
        return engine;
    }

    public String getUnit() {
        return unit;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    /**
     * Calculates the total number of pages for a given item count.
     */
    public int calculateTotalPages(int totalItems) {
        if (totalItems <= 0) return 1;
        return (int) Math.ceil((double) totalItems / pageSize);
    }

    public boolean hasNext(int totalItems) {
        return page < calculateTotalPages(totalItems);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    /**
     * Returns an immutable sublist slice for the current page.
     */
    public <T> List<T> slice(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        int total = source.size();
        int totalPages = calculateTotalPages(total);
        int activePage = Math.min(page, totalPages);
        int fromIndex = Math.max(0, (activePage - 1) * pageSize);
        if (fromIndex >= total) {
            fromIndex = Math.max(0, (totalPages - 1) * pageSize);
        }
        int toIndex = Math.min(fromIndex + pageSize, total);
        return source.subList(fromIndex, toIndex);
    }

    public static class Builder {
        private String database = "";
        private String engine = "";
        private String unit = "";
        private int page = 1;
        private int pageSize = DEFAULT_PAGE_SIZE;

        public Builder database(String database) {
            this.database = database != null ? database : "";
            return this;
        }

        public Builder engine(String engine) {
            this.engine = engine != null ? engine : "";
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit != null ? unit : "";
            return this;
        }

        public Builder page(int page) {
            this.page = Math.max(1, page);
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = Math.max(1, pageSize);
            return this;
        }

        /**
         * Parses pagination parameters from an HTTP query parameters map.
         * Supports per-unit pagination (`page_<engine>_<unit>` or `tree_unit` + `tree_page`).
         */
        public Builder fromParams(Map<String, String> params, String engine, String unit) {
            this.engine = engine != null ? engine : "";
            this.unit = unit != null ? unit : "";
            if (params != null) {
                if (params.containsKey("database")) {
                    this.database = params.get("database");
                }
                String specificKey = "page_" + this.engine + "_" + this.unit;
                if (params.containsKey(specificKey)) {
                    try {
                        this.page = Math.max(1, Integer.parseInt(params.get(specificKey)));
                    } catch (NumberFormatException ignored) {}
                } else if (params.containsKey("tree_unit") && this.unit.equalsIgnoreCase(params.get("tree_unit")) && params.containsKey("tree_page")) {
                    try {
                        this.page = Math.max(1, Integer.parseInt(params.get("tree_page")));
                    } catch (NumberFormatException ignored) {}
                }
                if (params.containsKey("tree_page_size")) {
                    try {
                        this.pageSize = Math.max(1, Integer.parseInt(params.get("tree_page_size")));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return this;
        }

        public TreePaginationQuery build() {
            return new TreePaginationQuery(this);
        }
    }
}
