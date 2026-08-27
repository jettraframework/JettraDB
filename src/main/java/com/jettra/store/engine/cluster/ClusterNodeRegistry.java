package com.jettra.store.engine.cluster;

import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic cluster node registry and service discovery for JettraStoreEngine.
 * Handles dynamic host/node network topologies, cluster re-targeting, and remote reference resolution.
 */
public class ClusterNodeRegistry {

    public enum NodeStatus {
        ACTIVE,
        UNREACHABLE,
        STANDALONE,
        UNKNOWN
    }

    public record ClusterNodeInfo(
        String nodeId,
        String clusterId,
        String host,
        int grpcPort,
        int restPort,
        NodeStatus status,
        long lastSeenMs,
        Map<String, String> metadata
    ) {}

    private static final ClusterNodeRegistry INSTANCE = new ClusterNodeRegistry();
    private final Map<String, ClusterNodeInfo> nodes = new ConcurrentHashMap<>();
    private final JettraJson jsonParser = new JettraJson();

    public static ClusterNodeRegistry getInstance() {
        return INSTANCE;
    }

    public ClusterNodeRegistry() {
        initDefaultTopology();
    }

    /**
     * Initializes known topology from properties, environment variables, or default cluster seeds.
     */
    public void initDefaultTopology() {
        // 1. Read from system properties / env
        String peers = System.getProperty("jettra.cluster.peers", System.getenv().getOrDefault("JETTRA_CLUSTER_PEERS", ""));
        if (!peers.isBlank()) {
            String[] peerList = peers.split(",");
            for (int i = 0; i < peerList.length; i++) {
                String p = peerList[i].trim();
                if (!p.isEmpty()) {
                    String host = "127.0.0.1";
                    int grpcPort = 50051 + (i * 2);
                    int restPort = 50050 + (i * 2);
                    if (p.contains(":")) {
                        String[] parts = p.split(":");
                        host = parts[0];
                        try {
                            grpcPort = Integer.parseInt(parts[1]);
                            restPort = grpcPort - 1;
                        } catch (Exception ignored) {}
                    }
                    String nodeId = "node" + (i + 1);
                    registerNode(new ClusterNodeInfo(nodeId, "cluster-primary", host, grpcPort, restPort, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of()));
                }
            }
        }

        // 2. Register standard virtual / distributed cluster topology seeds & local aliases
        registerNode(new ClusterNodeInfo("node-local", "node-local", "127.0.0.1", 50051, 50050, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("role", "primary")));
        registerNode(new ClusterNodeInfo("local cluster (primary)", "node-local", "127.0.0.1", 50051, 50050, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("role", "primary")));
        registerNode(new ClusterNodeInfo("primary-node", "node-local", "127.0.0.1", 50051, 50050, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("role", "primary")));
        registerNode(new ClusterNodeInfo("cluster-secondary-02", "cluster-secondary-02", "127.0.0.1", 50053, 50052, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("region", "us-east", "tier", "secondary")));
        registerNode(new ClusterNodeInfo("cluster-east-01", "cluster-east-01", "127.0.0.1", 50051, 50050, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("region", "us-east", "tier", "primary")));
        registerNode(new ClusterNodeInfo("node-cloud-west", "node-cloud-west", "127.0.0.1", 50055, 50054, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("region", "us-west", "type", "vector-ai")));
        registerNode(new ClusterNodeInfo("cluster-europe-03", "cluster-europe-03", "127.0.0.1", 50057, 50056, NodeStatus.ACTIVE, System.currentTimeMillis(), Map.of("region", "eu-central", "tier", "audit")));
    }

    public boolean isLocalNode(String nodeIdOrClusterId) {
        if (nodeIdOrClusterId == null || nodeIdOrClusterId.isBlank()) return true;
        String clean = nodeIdOrClusterId.trim().toLowerCase();
        return clean.equals("local") || clean.equals("node-local") || clean.equals("local-node")
            || clean.equals("primary") || clean.equals("primary-node") || clean.equals("local cluster (primary)")
            || clean.equals("cluster-primary") || clean.equals("localhost") || clean.equals("127.0.0.1");
    }

    public void registerNode(ClusterNodeInfo node) {
        if (node == null || node.nodeId() == null) return;
        nodes.put(node.nodeId().toLowerCase(), node);
        if (node.clusterId() != null && !node.clusterId().isBlank()) {
            nodes.put(node.clusterId().toLowerCase(), node);
        }
    }

    public void updateNodeStatus(String nodeIdOrClusterId, NodeStatus status) {
        if (nodeIdOrClusterId == null) return;
        ClusterNodeInfo existing = getNode(nodeIdOrClusterId);
        if (existing != null) {
            ClusterNodeInfo updated = new ClusterNodeInfo(
                existing.nodeId(),
                existing.clusterId(),
                existing.host(),
                existing.grpcPort(),
                existing.restPort(),
                status,
                System.currentTimeMillis(),
                existing.metadata()
            );
            registerNode(updated);
        }
    }

    public ClusterNodeInfo getNode(String nodeIdOrClusterId) {
        if (nodeIdOrClusterId == null) return null;
        String clean = nodeIdOrClusterId.trim().toLowerCase();
        ClusterNodeInfo direct = nodes.get(clean);
        if (direct != null) return direct;
        if (isLocalNode(clean)) {
            return nodes.get("node-local");
        }
        return null;
    }

    public boolean isNodeRegistered(String nodeIdOrClusterId) {
        if (nodeIdOrClusterId == null) return false;
        String clean = nodeIdOrClusterId.trim().toLowerCase();
        return nodes.containsKey(clean) || isLocalNode(clean);
    }

    public List<ClusterNodeInfo> getAllNodes() {
        return new ArrayList<>(new HashSet<>(nodes.values()));
    }

    /**
     * Attempts dynamic remote lookup against a target cluster node.
     * Returns the raw JSON response string or null if remote lookup could not be completed.
     */
    public String queryRemoteReference(String nodeIdOrClusterId, String refUri, int timeoutMs) {
        if (isLocalNode(nodeIdOrClusterId)) {
            return null; // Local node references are resolved directly in-process
        }

        ClusterNodeInfo node = getNode(nodeIdOrClusterId);
        if (node == null) {
            return null;
        }

        if (node.status() == NodeStatus.UNREACHABLE) {
            return null;
        }

        String host = node.host();
        int port = node.restPort() > 0 ? node.restPort() : 50050;
        
        try {
            String encodedUri = URLEncoder.encode(refUri, StandardCharsets.UTF_8);
            String urlStr = "http://" + host + ":" + port + "/engines?action=resolve_ref&uri=" + encodedUri;
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs > 0 ? timeoutMs : 1000);
            conn.setReadTimeout(timeoutMs > 0 ? timeoutMs : 1000);

            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            // Connection failed or timed out
        }
        return null;
    }
}
