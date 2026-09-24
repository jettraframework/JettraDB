package com.jettra.store.engine.cluster;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * High-performance Cluster Replication Client for JettraDB.
 * Supports Cloud-Native 3-node cluster topology (Primary - Secondary - Secondary).
 * Uses Java 25 Virtual Threads to replicate operations concurrently to all cluster peers.
 */
public class JettraConsensusClient {

    public enum NodeRole {
        PRIMARY,
        SECONDARY
    }

    public record PeerEndpoint(String host, int port) {}

    private String nodeId = "node1";
    private NodeRole nodeRole = NodeRole.PRIMARY;
    private int localPort = 50051;
    private String primaryHost = "127.0.0.1";
    private int primaryPort = 50051;
    private final List<PeerEndpoint> allPeers = new CopyOnWriteArrayList<>();
    private final List<PeerEndpoint> secondaryPeers = new CopyOnWriteArrayList<>();

    public void init() {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream input = new java.io.FileInputStream("jettrastoreengine.properties")) {
            props.load(input);
        } catch (java.io.IOException ignored) {
        }

        this.nodeId = props.getProperty("jettra.node.id", System.getenv().getOrDefault("JETTRA_NODE_ID",
                System.getenv().getOrDefault("NODE_ID", "node1"))).trim();

        String configuredRole = props.getProperty("jettra.node.role", System.getenv().getOrDefault("JETTRA_NODE_ROLE", "")).trim();
        if (!configuredRole.isBlank()) {
            this.nodeRole = "SECONDARY".equalsIgnoreCase(configuredRole) ? NodeRole.SECONDARY : NodeRole.PRIMARY;
        } else {
            // Default 3-node convention: node1 is PRIMARY, node2 and node3 are SECONDARY
            if (nodeId.equalsIgnoreCase("node2") || nodeId.equalsIgnoreCase("node3")
                    || nodeId.toLowerCase().contains("secondary")) {
                this.nodeRole = NodeRole.SECONDARY;
            } else {
                this.nodeRole = NodeRole.PRIMARY;
            }
        }

        String localPortStr = props.getProperty("jettra.grpc.port", System.getenv().getOrDefault("JETTRA_GRPC_PORT", "50051"));
        try {
            if (localPortStr.contains(":")) {
                this.localPort = Integer.parseInt(localPortStr.split(":")[1]);
            } else {
                this.localPort = Integer.parseInt(localPortStr);
            }
        } catch (Exception ignored) {}

        String peersEnv = props.getProperty("jettra.cluster.peers",
                System.getenv().getOrDefault("JETTRA_CLUSTER_PEERS",
                System.getenv().getOrDefault("CLUSTER_ADDRESS", "127.0.0.1:50051")));

        allPeers.clear();
        secondaryPeers.clear();

        String[] peerEntries = peersEnv.split(",");
        for (int i = 0; i < peerEntries.length; i++) {
            String p = peerEntries[i].trim();
            if (p.isEmpty()) continue;
            String h = "127.0.0.1";
            int pt = 50051 + (i * 2);
            if (p.contains(":")) {
                String[] parts = p.split(":");
                h = parts[0];
                try {
                    pt = Integer.parseInt(parts[1]);
                } catch (Exception ignored) {}
            } else {
                h = p;
            }

            PeerEndpoint endpoint = new PeerEndpoint(h, pt);
            allPeers.add(endpoint);

            if (i == 0) {
                this.primaryHost = h;
                this.primaryPort = pt;
            }

            // Distinguish secondary peers from the primary peer
            if (i > 0) {
                secondaryPeers.add(endpoint);
            }
        }

        if (allPeers.isEmpty()) {
            allPeers.add(new PeerEndpoint("127.0.0.1", 50051));
        }

        System.out.println("[JettraConsensusClient] Initialized on " + nodeId + " (" + nodeRole
                + ") with " + allPeers.size() + " cluster peers (Secondaries: " + secondaryPeers.size() + ")");
    }

    /**
     * Sends a command across the cluster.
     * In a Primary node: replicates concurrently to all remote secondary peers using Virtual Threads.
     * In a Secondary node: forwards write to Primary or first configured peer.
     */
    public boolean sendCommand(String command) {
        if (command == null || command.isBlank()) return false;

        // If this node is PRIMARY in 3-node cluster, replicate to all secondaries in parallel
        if (nodeRole == NodeRole.PRIMARY && !secondaryPeers.isEmpty()) {
            return broadcastToSecondaries(command);
        }

        // Secondary or standalone mode: send to Primary host/port
        if (trySend(this.primaryHost, this.primaryPort, command)) {
            return true;
        }

        // Fallback to localhost if different
        if (!"127.0.0.1".equals(this.primaryHost) && !"localhost".equalsIgnoreCase(this.primaryHost)) {
            return trySend("127.0.0.1", this.primaryPort, command);
        }

        // Fallback: try any available peer in cluster
        for (PeerEndpoint peer : allPeers) {
            if (trySend(peer.host(), peer.port(), command)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Replicates command concurrently to all secondary peers via Java 25 Virtual Threads.
     */
    public boolean broadcastToSecondaries(String command) {
        if (secondaryPeers.isEmpty()) {
            return true;
        }

        try (ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (PeerEndpoint peer : secondaryPeers) {
                tasks.add(() -> trySend(peer.host(), peer.port(), command));
            }

            List<Future<Boolean>> futures = vExecutor.invokeAll(tasks, 1500, TimeUnit.MILLISECONDS);
            int successCount = 0;
            for (Future<Boolean> f : futures) {
                try {
                    if (f.get()) {
                        successCount++;
                    }
                } catch (Exception ignored) {}
            }

            // In 3-node cluster (Primary + 2 Secondaries), success if at least 1 secondary acknowledged or no exceptions
            return successCount > 0 || secondaryPeers.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean broadcastCommand(String command) {
        if (allPeers.isEmpty()) return false;
        try (ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (PeerEndpoint peer : allPeers) {
                tasks.add(() -> trySend(peer.host(), peer.port(), command));
            }
            List<Future<Boolean>> futures = vExecutor.invokeAll(tasks, 1500, TimeUnit.MILLISECONDS);
            boolean anySuccess = false;
            for (Future<Boolean> f : futures) {
                try {
                    if (f.get()) anySuccess = true;
                } catch (Exception ignored) {}
            }
            return anySuccess;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean trySend(String targetHost, int targetPort, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), 800);
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

    public boolean isPrimary() {
        return nodeRole == NodeRole.PRIMARY;
    }

    public NodeRole getNodeRole() {
        return nodeRole;
    }

    public String getNodeId() {
        return nodeId;
    }

    public List<PeerEndpoint> getAllPeers() {
        return Collections.unmodifiableList(allPeers);
    }

    public List<PeerEndpoint> getSecondaryPeers() {
        return Collections.unmodifiableList(secondaryPeers);
    }

    public void close() {
        // Resources closed per socket call
    }
}
