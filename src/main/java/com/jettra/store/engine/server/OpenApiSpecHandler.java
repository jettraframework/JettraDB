package com.jettra.store.engine.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.jettra.core.login.NoLoginRequired;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the OpenAPI 3.0 specification JSON for JettraDB REST APIs.
 * Endpoint: /openapi.json
 */
@NoLoginRequired
public class OpenApiSpecHandler implements HttpHandler {

    private final int restPort;
    private final int guiPort;

    public OpenApiSpecHandler(int restPort, int guiPort) {
        this.restPort = restPort;
        this.guiPort = guiPort;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String json = buildOpenApiJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            return;
        }

        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String buildOpenApiJson() {
        return """
        {
          "openapi": "3.0.3",
          "info": {
            "title": "JettraDB REST API & Multi-Model Storage Engine",
            "version": "1.0.0",
            "description": "High-performance Multi-Model NoSQL Database supporting Document, KeyValue, TimeSeries, Graph, Vector, Column, Geospatial, and Java 25 Records storage engines."
          },
          "servers": [
            {
              "url": "http://localhost:%d",
              "description": "JettraDB REST API Engine (Port %d)"
            },
            {
              "url": "/",
              "description": "Current Server Host"
            }
          ],
          "components": {
            "securitySchemes": {
              "BearerAuth": {
                "type": "http",
                "scheme": "bearer",
                "bearerFormat": "JWT",
                "description": "Enter your authentication token received from /api/auth/login"
              }
            }
          },
          "paths": {
            "/api/auth/login": {
              "post": {
                "tags": ["Authentication"],
                "summary": "Authenticate user and acquire session token",
                "description": "Authenticates credentials against JettraSecurityDB and returns a Bearer session token.",
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/json": {
                      "schema": {
                        "type": "object",
                        "properties": {
                          "username": { "type": "string", "example": "admin" },
                          "password": { "type": "string", "example": "admin" }
                        },
                        "required": ["username", "password"]
                      }
                    }
                  }
                },
                "responses": {
                  "200": {
                    "description": "Authentication successful, token returned.",
                    "content": {
                      "application/json": {
                        "example": { "token": "967ac92d-fec4-4aee-a59e-b56e8897eb74" }
                      }
                    }
                  },
                  "400": { "description": "Missing username or password" },
                  "401": { "description": "Invalid credentials" }
                }
              }
            },
            "/api/databases": {
              "get": {
                "tags": ["Database Management"],
                "summary": "List all active databases",
                "security": [{ "BearerAuth": [] }],
                "responses": {
                  "200": { "description": "List of databases returned" },
                  "401": { "description": "Unauthorized" }
                }
              },
              "post": {
                "tags": ["Database Management"],
                "summary": "Create a new database",
                "security": [{ "BearerAuth": [] }],
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/json": {
                      "schema": {
                        "type": "object",
                        "properties": { "name": { "type": "string", "example": "JMeterDB" } },
                        "required": ["name"]
                      }
                    }
                  }
                },
                "responses": {
                  "201": { "description": "Database created successfully" },
                  "400": { "description": "Invalid database name" },
                  "401": { "description": "Unauthorized" }
                }
              }
            },
            "/api/databases/{name}": {
              "post": {
                "tags": ["Database Management"],
                "summary": "Create a new database by name",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "name", "in": "path", "required": true, "schema": { "type": "string", "example": "JMeterDB" } }
                ],
                "responses": {
                  "201": { "description": "Database created successfully" },
                  "401": { "description": "Unauthorized" }
                }
              },
              "delete": {
                "tags": ["Database Management"],
                "summary": "Drop a database and purge all data",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "name", "in": "path", "required": true, "schema": { "type": "string", "example": "JMeterDB" } }
                ],
                "responses": {
                  "200": { "description": "Database deleted successfully" },
                  "400": { "description": "Protected database or invalid name" },
                  "401": { "description": "Unauthorized" }
                }
              }
            },
            "/api/document/{collection}": {
              "post": {
                "tags": ["Document Engine"],
                "summary": "Insert document with auto-generated UUID",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  {
                    "name": "collection",
                    "in": "path",
                    "required": true,
                    "schema": { "type": "string", "example": "orders" }
                  }
                ],
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/json": {
                      "schema": { "type": "object" },
                      "example": { "order_id": "ORD-101", "customer": "Alice", "amount": 150.0, "status": "CONFIRMED" }
                    }
                  }
                },
                "responses": {
                  "201": {
                    "description": "Document created successfully",
                    "content": {
                      "application/json": {
                        "example": { "status": "inserted", "id": "38a5f4be-1a0866a1597-5049-fdd7be4ee4e4", "collection": "orders", "id_mode": "UUID" }
                      }
                    }
                  },
                  "401": { "description": "Unauthorized or invalid token" }
                }
              }
            },
            "/api/document/{collection}/{id}": {
              "post": {
                "tags": ["Document Engine"],
                "summary": "Insert or update document by ID with ID generation mode",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "collection", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id_mode", "in": "query", "required": false, "schema": { "type": "string", "enum": ["manual", "autoincrement", "uuid"], "default": "manual" } }
                ],
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/json": { "schema": { "type": "object" }, "example": { "item": "Laptop", "price": 1200 } }
                  }
                },
                "responses": {
                  "201": { "description": "Document created or updated successfully" },
                  "401": { "description": "Unauthorized" }
                }
              },
              "get": {
                "tags": ["Document Engine"],
                "summary": "Retrieve document by ID",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "collection", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": { "description": "Document found", "content": { "application/json": {} } },
                  "401": { "description": "Unauthorized" },
                  "404": { "description": "Document not found" }
                }
              },
              "delete": {
                "tags": ["Document Engine"],
                "summary": "Delete document by ID",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "collection", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": { "description": "Document deleted successfully" },
                  "401": { "description": "Unauthorized" }
                }
              }
            },
            "/api/document/{collection}/{id}/history": {
              "get": {
                "tags": ["Document Engine"],
                "summary": "Retrieve document version history",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "collection", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": { "description": "Revision history retrieved", "content": { "application/json": {} } }
                }
              }
            },
            "/api/document/{collection}/{id}/restore": {
              "post": {
                "tags": ["Document Engine"],
                "summary": "Point-in-time document restoration",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "collection", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "timestamp", "in": "query", "required": true, "schema": { "type": "integer", "format": "int64" } }
                ],
                "responses": {
                  "200": { "description": "Document restored to target timestamp revision" }
                }
              }
            },
            "/api/model/{type}/{namespace}/{id}": {
              "post": {
                "tags": ["Multi-Model Engine"],
                "summary": "Store entity in designated multi-model engine",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "type", "in": "path", "required": true, "schema": { "type": "string", "enum": ["KEYVALUE", "DOCUMENT", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT", "RECORDS"] } },
                  { "name": "namespace", "in": "path", "required": true, "schema": { "type": "string", "example": "system_db" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string", "example": "item_001" } }
                ],
                "requestBody": {
                  "required": true,
                  "content": {
                    "application/json": {
                      "schema": { "type": "object" },
                      "example": { "metric": "temperature", "value": 24.5 }
                    },
                    "text/plain": {
                      "schema": { "type": "string" },
                      "example": "Simple string or serialized payload"
                    }
                  }
                },
                "responses": {
                  "201": { "description": "Entity inserted or updated" },
                  "400": { "description": "Invalid format or model type" },
                  "401": { "description": "Unauthorized" }
                }
              },
              "get": {
                "tags": ["Multi-Model Engine"],
                "summary": "Retrieve entity from multi-model engine",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "type", "in": "path", "required": true, "schema": { "type": "string", "enum": ["KEYVALUE", "DOCUMENT", "VECTOR", "GRAPH", "TIMESERIES", "COLUMN", "GEOSPATIAL", "OBJECT", "RECORDS"] } },
                  { "name": "namespace", "in": "path", "required": true, "schema": { "type": "string", "example": "system_db" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string", "example": "item_001" } }
                ],
                "responses": {
                  "200": { "description": "Entity found" },
                  "404": { "description": "Entity not found" }
                }
              },
              "delete": {
                "tags": ["Multi-Model Engine"],
                "summary": "Delete entity from multi-model engine",
                "security": [{ "BearerAuth": [] }],
                "parameters": [
                  { "name": "type", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "namespace", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }
                ],
                "responses": {
                  "200": { "description": "Entity deleted" }
                }
              }
            },
            "/api/backup": {
              "post": {
                "tags": ["System Operations"],
                "summary": "Trigger full database backup and snapshot generation",
                "security": [{ "BearerAuth": [] }],
                "responses": {
                  "200": { "description": "Backup created successfully" },
                  "401": { "description": "Unauthorized" }
                }
              }
            }
          }
        }
        """.formatted(restPort, restPort);
    }
}
