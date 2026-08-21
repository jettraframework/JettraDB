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
import java.util.List;
import com.jettra.store.engine.models.*;

public class ModelRestController implements HttpHandler {

    private final JettraStorageEngine storageEngine;
    private final AuthManager authManager;
    private final JettraJson gson;

    public ModelRestController(JettraStorageEngine storageEngine, AuthManager authManager) {
        this.storageEngine = storageEngine;
        this.authManager = authManager;
        this.gson = new JettraJson();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ") || !authManager.validateToken(authHeader.substring(7))) {
            exchange.sendResponseHeaders(401, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");
        // Expected format: /api/model/{type}/{namespace}/{id}
        if (segments.length < 6) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        String modelType = segments[3].toUpperCase();
        String namespace = segments[4];
        String id = segments[5];

        String method = exchange.getRequestMethod();

        if ("POST".equals(method)) {
            try (InputStream is = exchange.getRequestBody()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                
                switch (modelType) {
                    case "VECTOR":
                        ((VectorEngine) storageEngine.getEngine(modelType)).insertVector(namespace, id, new float[]{0.0f}, gson.fromJson(body, JsonObject.class)); // Simplified for generic
                        break;
                    case "GRAPH":
                        ((GraphEngine) storageEngine.getEngine(modelType)).addNode(namespace, id, gson.fromJson(body, JsonObject.class));
                        break;
                    case "TIMESERIES":
                        ((TimeSeriesEngine) storageEngine.getEngine(modelType)).insert(namespace, Long.parseLong(id), gson.fromJson(body, JsonObject.class));
                        break;
                    case "COLUMN":
                        ((ColumnEngine) storageEngine.getEngine(modelType)).insertRow(namespace, id, gson.fromJson(body, JsonObject.class));
                        break;
                    case "KEYVALUE":
                        ((KeyValueEngine) storageEngine.getEngine(modelType)).put(namespace, id, body);
                        break;
                    case "GEOSPATIAL":
                        ((GeospatialEngine) storageEngine.getEngine(modelType)).insertLocation(namespace, id, 0.0, 0.0, gson.fromJson(body, JsonObject.class));
                        break;
                    case "OBJECT":
                        ((ObjectEngine) storageEngine.getEngine(modelType)).saveObject(namespace, id, "Unknown", gson.fromJson(body, JsonObject.class));
                        break;
                    case "RECORDS":
                        JsonObject recBody = gson.fromJson(body, JsonObject.class);
                        String rClass = recBody.has("_recordClass") ? (String) recBody.get("_recordClass") : (recBody.has("_class") ? (String) recBody.get("_class") : "java.lang.Record");
                        JsonObject rComps = recBody.has("components") && recBody.get("components") instanceof JsonObject ? (JsonObject) recBody.get("components") : recBody;
                        JsonObject rSchema = recBody.has("_schema") && recBody.get("_schema") instanceof JsonObject ? (JsonObject) recBody.get("_schema") : null;
                        ((RecordsEngine) storageEngine.getEngine(modelType)).saveRecord(namespace, id, rClass, rComps, rSchema);
                        break;
                    case "DOCUMENT":
                    default:
                        ((DocumentEngine) storageEngine.getEngine("DOCUMENT")).insert(namespace, id, gson.fromJson(body, JsonObject.class));
                        break;
                }
                
                exchange.sendResponseHeaders(201, -1);
            } catch (Exception e) {
                String response = "Error inserting model: " + e.getMessage();
                exchange.sendResponseHeaders(500, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
            }
        } else if ("GET".equals(method)) {
             try {
                Object result = null;
                switch (modelType) {
                    case "VECTOR": result = ((VectorEngine) storageEngine.getEngine(modelType)).getVector(namespace, id); break;
                    case "GRAPH": result = ((GraphEngine) storageEngine.getEngine(modelType)).getNode(namespace, id); break;
                    case "TIMESERIES": result = ((TimeSeriesEngine) storageEngine.getEngine(modelType)).get(namespace, Long.parseLong(id)); break;
                    case "COLUMN": result = ((ColumnEngine) storageEngine.getEngine(modelType)).getRow(namespace, id); break;
                    case "KEYVALUE": result = ((KeyValueEngine) storageEngine.getEngine(modelType)).get(namespace, id); break;
                    case "GEOSPATIAL": result = ((GeospatialEngine) storageEngine.getEngine(modelType)).getLocation(namespace, id); break;
                    case "OBJECT": result = ((ObjectEngine) storageEngine.getEngine(modelType)).getObject(namespace, id); break;
                    case "RECORDS":
                        String query = exchange.getRequestURI().getQuery();
                        if (query != null && query.contains("fields=")) {
                            String[] fParams = query.split("&");
                            List<String> fList = new java.util.ArrayList<>();
                            for (String p : fParams) {
                                if (p.startsWith("fields=")) {
                                    String[] fArray = p.substring(7).split(",");
                                    for (String f : fArray) fList.add(f.trim());
                                }
                            }
                            result = ((RecordsEngine) storageEngine.getEngine(modelType)).projectFields(namespace, id, fList);
                        } else {
                            result = ((RecordsEngine) storageEngine.getEngine(modelType)).getRecord(namespace, id);
                        }
                        break;
                    case "DOCUMENT": default: result = ((DocumentEngine) storageEngine.getEngine("DOCUMENT")).get(namespace, id); break;
                }
                
                if (result != null) {
                    String response = result instanceof String ? (String) result : gson.toJson(result);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        } else if ("DELETE".equals(method)) {
            try {
                switch (modelType) {
                    case "RECORDS":
                        ((RecordsEngine) storageEngine.getEngine(modelType)).deleteRecord(namespace, id);
                        break;
                    case "OBJECT":
                        ((ObjectEngine) storageEngine.getEngine(modelType)).deleteObject(namespace, id);
                        break;
                    case "KEYVALUE":
                        ((KeyValueEngine) storageEngine.getEngine(modelType)).delete(namespace, id);
                        break;
                    case "DOCUMENT":
                    default:
                        ((DocumentEngine) storageEngine.getEngine("DOCUMENT")).delete(namespace, id);
                        break;
                }
                exchange.sendResponseHeaders(204, -1);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        } else {
            exchange.sendResponseHeaders(405, -1);
        }
    }
}
