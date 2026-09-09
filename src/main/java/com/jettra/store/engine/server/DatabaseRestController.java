package com.jettra.store.engine.server;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST API controller for Database lifecycle management in JettraStoreEngine:
 * - GET    /api/databases          : List all active databases
 * - POST   /api/databases          : Create a new database { "name": "JMeterDB" }
 * - POST   /api/databases/{name}   : Create a new database by path
 * - DELETE /api/databases/{name}   : Drop database and purge all multi-model storage
 */
public class DatabaseRestController implements HttpHandler {

    private final JettraStorageEngine storageEngine;
    private final AuthManager authManager;
    private final JettraJson gson;

    public DatabaseRestController(JettraStorageEngine storageEngine, AuthManager authManager) {
        this.storageEngine = storageEngine;
        this.authManager = authManager;
        this.gson = new JettraJson();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ") || !authManager.validateToken(authHeader.substring(7))) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized or invalid token\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String dbNameFromPath = extractDatabaseNameFromPath(path);

        try {
            switch (method.toUpperCase()) {
                case "GET" -> handleGet(exchange, dbNameFromPath);
                case "POST" -> handlePost(exchange, dbNameFromPath);
                case "DELETE" -> handleDelete(exchange, dbNameFromPath);
                default -> sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Internal error: " + e.getMessage() + "\"}");
        }
    }

    private void handleGet(HttpExchange exchange, String dbName) throws IOException {
        Set<String> databases = new TreeSet<>(storageEngine.getStorageCore().getDatabaseNames());
        databases.add("system_db");

        if (dbName != null && !dbName.isBlank()) {
            boolean exists = databases.stream().anyMatch(d -> d.equalsIgnoreCase(dbName));
            if (exists) {
                sendResponse(exchange, 200, "{\"status\":\"SUCCESS\",\"database\":\"" + dbName + "\",\"exists\":true}");
            } else {
                sendResponse(exchange, 404, "{\"status\":\"ERROR\",\"database\":\"" + dbName + "\",\"exists\":false}");
            }
            return;
        }

        StringBuilder sb = new StringBuilder("{\"status\":\"SUCCESS\",\"databases\":[");
        int count = 0;
        for (String db : databases) {
            if (count > 0) sb.append(",");
            sb.append("\"").append(db).append("\"");
            count++;
        }
        sb.append("]}");
        sendResponse(exchange, 200, sb.toString());
    }

    private void handlePost(HttpExchange exchange, String dbNameFromPath) throws IOException {
        String dbName = dbNameFromPath;

        if (dbName == null || dbName.isBlank()) {
            try (InputStream is = exchange.getRequestBody()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!body.isBlank()) {
                    JsonObject json = gson.fromJson(body, JsonObject.class);
                    if (json.has("name")) {
                        dbName = (String) json.get("name");
                    } else if (json.has("database")) {
                        dbName = (String) json.get("database");
                    }
                }
            }
        }

        if (dbName == null || dbName.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Database name is required either in URI path or JSON body { 'name': '...' }\"}");
            return;
        }

        String cleanDb = dbName.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        // Initialize storage partition and seed init record
        storageEngine.getStorageCore().getPartition(cleanDb);
        String initKey = "rec:" + cleanDb + ":_init";
        String initPayload = "{\"status\":\"ACTIVE\",\"createdAt\":" + System.currentTimeMillis() + "}";
        storageEngine.getStorageCore().put(initKey, initPayload.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        sendResponse(exchange, 201, "{\"status\":\"CREATED\",\"database\":\"" + cleanDb + "\",\"message\":\"Database initialized successfully\"}");
    }

    private void handleDelete(HttpExchange exchange, String dbName) throws IOException {
        if (dbName == null || dbName.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Target database name is required in URI path /api/databases/{name}\"}");
            return;
        }

        String targetDb = dbName.trim();
        if ("system_db".equalsIgnoreCase(targetDb) || "_system".equalsIgnoreCase(targetDb)) {
            sendResponse(exchange, 400, "{\"error\":\"The system database '" + targetDb + "' is protected and cannot be deleted.\"}");
            return;
        }

        // Purge all multi-model keys for this database
        String[] prefixes = {"rec:", "doc:", "vec:", "graph:", "ts:", "col:", "kv:", "geo:", "obj:", ""};
        int purgedKeys = 0;
        for (String p : prefixes) {
            String dbPrefix = p + targetDb + ":";
            Map<String, byte[]> keys = storageEngine.getStorageCore().scanPrefix(dbPrefix);
            for (String k : keys.keySet()) {
                storageEngine.getStorageCore().delete(k, System.currentTimeMillis());
                purgedKeys++;
            }
        }

        // Drop partition and underlying files
        storageEngine.getStorageCore().dropDatabase(targetDb);

        sendResponse(exchange, 200, "{\"status\":\"DELETED\",\"database\":\"" + targetDb + "\",\"purgedKeys\":" + purgedKeys + ",\"message\":\"Database successfully dropped\"}");
    }

    private String extractDatabaseNameFromPath(String path) {
        if (path == null) return null;
        String prefix = "/api/databases";
        int idx = path.indexOf(prefix);
        if (idx < 0) return null;
        String remainder = path.substring(idx + prefix.length());
        if (remainder.startsWith("/")) remainder = remainder.substring(1);
        if (remainder.endsWith("/")) remainder = remainder.substring(0, remainder.length() - 1);
        return remainder.isBlank() ? null : remainder;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
