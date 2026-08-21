package com.jettra.store.engine.server;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import com.jettra.store.engine.auth.AuthManager;
import com.jettra.store.engine.core.IdGenerator;
import com.jettra.store.engine.core.IdGenerator.IdMode;
import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.core.LsmBTreeHybrid.RecordVersion;
import com.jettra.store.engine.models.DocumentEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles REST requests for Document Engine operations with multi-mode ID generation,
 * CRUD operations, version history, and point-in-time restoration.
 *
 * Endpoints:
 * - POST /api/document/{collection} (Auto/UUID generated ID)
 * - POST /api/document/{collection}/{id} (Optional ?id_mode=manual|autoincrement|uuid)
 * - GET /api/document/{collection}/{id}
 * - GET /api/document/{collection}/{id}/history
 * - POST /api/document/{collection}/{id}/restore?timestamp={ts}
 * - DELETE /api/document/{collection}/{id}
 */
public class DocumentRestController implements HttpHandler {

    private final DocumentEngine engine;
    private final JettraStorageEngine storageEngine;
    private final AuthManager authManager;
    private final JettraJson gson;

    public DocumentRestController(DocumentEngine engine, AuthManager authManager) {
        this.engine = engine;
        this.storageEngine = null;
        this.authManager = authManager;
        this.gson = new JettraJson();
    }

    public DocumentRestController(DocumentEngine engine, JettraStorageEngine storageEngine, AuthManager authManager) {
        this.engine = engine;
        this.storageEngine = storageEngine;
        this.authManager = authManager;
        this.gson = new JettraJson();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        
        // Authenticate request
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        String token = authHeader.substring(7);
        if (!authManager.validateToken(token)) {
            sendResponse(exchange, 401, "{\"error\":\"Invalid Token\"}");
            return;
        }
        
        // Path breakdown: /api/document/{collection}[/{id}[/{subAction}]]
        String[] parts = path.split("/");
        if (parts.length < 4) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid path format. Expected /api/document/{collection}[/{id}]\"}");
            return;
        }
        
        String collection = parts[3];
        String id = parts.length >= 5 ? parts[4] : null;
        String subAction = parts.length >= 6 ? parts[5] : null;

        try {
            switch (method) {
                case "POST" -> {
                    if ("restore".equalsIgnoreCase(subAction)) {
                        handleRestore(exchange, collection, id);
                    } else {
                        handlePost(exchange, collection, id);
                    }
                }
                case "GET" -> {
                    if ("history".equalsIgnoreCase(subAction)) {
                        handleHistory(exchange, collection, id);
                    } else {
                        handleGet(exchange, collection, id);
                    }
                }
                case "DELETE" -> handleDelete(exchange, collection, id);
                default -> sendResponse(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private void handlePost(HttpExchange exchange, String collection, String id) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        IdMode idMode = parseIdMode(query, id);

        try (InputStream is = exchange.getRequestBody()) {
            String jsonBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject doc = gson.fromJson(jsonBody, JsonObject.class);
            String actualId = engine.insert(collection, id, doc, idMode);
            
            JsonObject res = new JsonObject();
            res.addProperty("status", "inserted");
            res.addProperty("id", actualId);
            res.addProperty("collection", collection);
            res.addProperty("id_mode", idMode.name());
            sendResponse(exchange, 201, gson.toJson(res));
        }
    }

    private void handleGet(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            // List all documents in collection
            var list = engine.list(collection);
            sendResponse(exchange, 200, gson.toJson(list));
            return;
        }

        JsonObject doc = engine.get(collection, id);
        if (doc != null) {
            sendResponse(exchange, 200, doc.toString());
        } else {
            sendResponse(exchange, 404, "{\"error\":\"Document not found\"}");
        }
    }

    private void handleHistory(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Document ID required for history\"}");
            return;
        }

        String storageKey = collection + ":" + id;
        if (storageEngine != null) {
            List<RecordVersion> versions = storageEngine.getStorageCore().getVersionHistory(storageKey);
            sendResponse(exchange, 200, gson.toJson(versions));
        } else {
            sendResponse(exchange, 200, "[]");
        }
    }

    private void handleRestore(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Document ID required for restoration\"}");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        long timestamp = 0;
        if (query != null && query.contains("timestamp=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("timestamp=")) {
                    try {
                        timestamp = Long.parseLong(param.substring(10));
                    } catch (Exception ignored) {}
                }
            }
        }

        if (timestamp <= 0) {
            sendResponse(exchange, 400, "{\"error\":\"Valid timestamp query parameter required (e.g. ?timestamp=1700000000000)\"}");
            return;
        }

        String storageKey = collection + ":" + id;
        boolean restored = storageEngine != null && storageEngine.getStorageCore().restoreVersion(storageKey, timestamp);
        if (restored) {
            JsonObject res = new JsonObject();
            res.addProperty("status", "restored");
            res.addProperty("id", id);
            res.addProperty("restored_timestamp", timestamp);
            sendResponse(exchange, 200, gson.toJson(res));
        } else {
            sendResponse(exchange, 404, "{\"error\":\"Could not restore version for document " + id + "\"}");
        }
    }

    private void handleDelete(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Document ID required for deletion\"}");
            return;
        }
        engine.delete(collection, id);
        sendResponse(exchange, 200, "{\"status\":\"deleted\",\"id\":\"" + id + "\"}");
    }

    private IdMode parseIdMode(String query, String id) {
        if ("auto".equalsIgnoreCase(id) || "autoincrement".equalsIgnoreCase(id)) return IdMode.AUTOINCREMENT;
        if ("uuid".equalsIgnoreCase(id)) return IdMode.UUID;
        if (query != null && query.contains("id_mode=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("id_mode=")) {
                    return IdMode.fromString(param.substring(8));
                }
            }
        }
        if (id == null || id.isBlank()) return IdMode.UUID;
        return IdMode.MANUAL;
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
