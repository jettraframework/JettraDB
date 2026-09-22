package com.jettra.store.engine.test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Reusable mock HttpExchange implementation for JettraDB unit and integration tests.
 */
public class MockHttpExchange extends HttpExchange {

    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
    private URI requestUri = URI.create("/engines");
    private String requestMethod = "GET";
    private int responseCode = -1;

    public MockHttpExchange() {
        requestHeaders.set("Cookie", "username=admin; jettra_user=admin; role=ADMIN; jettra_role=ADMIN");
    }

    public void setRequestURI(URI uri) {
        this.requestUri = uri;
    }

    public void setRequestMethod(String method) {
        this.requestMethod = method;
    }

    public void setRequestBody(String body) {
        this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return requestUri; }
    @Override public String getRequestMethod() { return requestMethod; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() {}
    @Override public InputStream getRequestBody() { return requestBody; }
    @Override public OutputStream getResponseBody() { return responseBody; }
    @Override public void sendResponseHeaders(int rCode, long responseLength) { this.responseCode = rCode; }
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
    @Override public int getResponseCode() { return responseCode; }
    @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) {}
    @Override public void setStreams(InputStream i, OutputStream o) {}
    @Override public HttpPrincipal getPrincipal() { return null; }

    public String getResponseBodyAsString() {
        return responseBody.toString(StandardCharsets.UTF_8);
    }
}
