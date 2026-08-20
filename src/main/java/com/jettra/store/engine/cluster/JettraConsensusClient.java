package com.jettra.store.engine.cluster;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client to interact with the Jettra Consensus Server.
 * Routes write requests through the custom algorithm.
 */
public class JettraConsensusClient {

    private String host;
    private int port;

    public void init() {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream input = new java.io.FileInputStream("jettrastoreengine.properties")) {
            props.load(input);
        } catch (java.io.IOException e) {
            // Ignorar, caemos a variables de entorno
        }

        String peersEnv = props.getProperty("jettra.cluster.peers", System.getenv().getOrDefault("CLUSTER_ADDRESS", "127.0.0.1:50051"));
        String[] peerEntries = peersEnv.split(",");
        
        // Use the first peer for simplicity in this basic implementation
        String address = peerEntries[0];
        String[] parts = address.split(":");
        this.host = parts[0];
        this.port = 50051;
        
        try {
            if (parts.length == 2) {
                this.port = Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
            System.err.println("Could not parse port, using 50051");
        }
    }

    public boolean sendCommand(String command) {
        // Try configured host first
        if (trySend(this.host, this.port, command)) {
            return true;
        }
        // Fallback to localhost if different
        if (!"127.0.0.1".equals(this.host) && !"localhost".equalsIgnoreCase(this.host)) {
            return trySend("127.0.0.1", this.port, command);
        }
        return false;
    }

    private boolean trySend(String targetHost, int targetPort, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(targetHost, targetPort), 1000);
            socket.setSoTimeout(1000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(command);
                String response = in.readLine();
                return "OK".equals(response);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void close() {
        // Nothing to keep open in this simple implementation
    }
}
