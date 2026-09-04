package com.jettra.store.engine.cluster;

import com.jettra.store.engine.core.JettraStorageEngine;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Initializes and manages a custom Jettra Consensus Server using Sockets.
 */
public class JettraConsensusServer {

    private final JettraStorageEngine storageEngine;
    private ServerSocket serverSocket;
    private boolean running;
    private Thread serverThread;

    public JettraConsensusServer(JettraStorageEngine storageEngine) {
        this.storageEngine = storageEngine;
    }

    public void start() throws Exception {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream input = new java.io.FileInputStream("jettrastoreengine.properties")) {
            props.load(input);
        } catch (java.io.IOException e) {
            // fallback
        }
        
        boolean enabled = Boolean.parseBoolean(System.getProperty("jettra.consensus.enabled",
            props.getProperty("jettra.consensus.enabled", "true")));
        if (!enabled) {
            System.out.println("[JettraConsensusServer] Consensus Server disabled via configuration");
            return;
        }

        String nodeId = props.getProperty("jettra.node.id", System.getenv().getOrDefault("NODE_ID", "node1"));
        String myAddress = System.getProperty("jettra.grpc.port",
            props.getProperty("jettra.grpc.port", System.getenv().getOrDefault("CLUSTER_ADDRESS", "127.0.0.1:50051")));
        int port = 50051;
        
        try {
            if (myAddress.contains(":")) {
                port = Integer.parseInt(myAddress.split(":")[1]);
            } else {
                port = Integer.parseInt(myAddress);
            }
        } catch (Exception e) {
            System.err.println("Could not parse port, using 50051");
        }
        
        System.out.println("[JettraConsensusServer] Initializing Consensus Server on " + nodeId + " at port " + port);

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));
        } catch (java.net.BindException be) {
            // Port is already occupied (e.g. concurrent test suites or active engine instances)
            // Fall back gracefully to an available dynamic port
            serverSocket = new ServerSocket(0);
            port = serverSocket.getLocalPort();
            System.out.println("[JettraConsensusServer] Port 50051 in use, allocated fallback dynamic port " + port);
        }
        try {
            serverSocket.setSoTimeout(1000);
        } catch (Exception ignored) {}
        running = true;

        serverThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket);
                } catch (java.net.SocketTimeoutException ste) {
                    // Timeout allows evaluating 'running' and thread interruption
                } catch (Exception e) {
                    if (running && !Thread.currentThread().isInterrupted()) {
                        e.printStackTrace();
                    }
                }
            }
        }, "JettraConsensusServer-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
        System.out.println("[JettraConsensusServer] Consensus Server started successfully on port " + port);
    }

    private void handleClient(Socket clientSocket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)
        ) {
            String commandStr = in.readLine();
            if (commandStr != null) {
                String[] parts = commandStr.split(" ", 3);
                if (parts.length >= 2 && ("DELETE".equalsIgnoreCase(parts[0]) || (parts.length >= 3 && "PUT".equalsIgnoreCase(parts[0]) && ("__TOMBSTONE__".equals(parts[2].trim()) || parts[2].trim().isEmpty())))) {
                    String key = parts[1];
                    long timestamp = System.currentTimeMillis();
                    storageEngine.getStorageCore().delete(key, timestamp);
                    out.println("OK");
                } else if (parts.length >= 3 && "PUT".equalsIgnoreCase(parts[0])) {
                    String key = parts[1];
                    String payloadStr = parts[2];
                    if ("__TOMBSTONE__".equals(payloadStr.trim()) || payloadStr.trim().isEmpty()) {
                        long timestamp = System.currentTimeMillis();
                        storageEngine.getStorageCore().delete(key, timestamp);
                    } else {
                        byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
                        long timestamp = System.currentTimeMillis();
                        storageEngine.getStorageCore().put(key, payload, timestamp);
                    }
                    out.println("OK");
                } else {
                    out.println("UNKNOWN_CMD");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }
}
