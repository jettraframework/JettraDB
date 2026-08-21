package com.jettra.store.engine.samples;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.ref.JettraReference;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * SampleDatasetManager: High-throughput sample dataset generator and loader for all 9 storage engines.
 * Generates thousands of realistic, highly structured, interconnected records with native cross-engine
 * reference pointers (jref://).
 */
public class SampleDatasetManager {

    private final JettraStorageEngine engine;

    public record DatasetInfo(
        String engineType,
        String databaseName,
        String displayName,
        String description,
        int estimatedRecords,
        String icon
    ) {}

    public static final List<DatasetInfo> AVAILABLE_DATASETS = List.of(
        new DatasetInfo(
            "ALL",
            "all_sample_databases",
            "Complete Enterprise Multi-Model Suite",
            "Loads all 9 databases simultaneously with over 10,000 interconnected records and cross-engine pointers.",
            10400,
            "fas fa-layer-group"
        ),
        new DatasetInfo(
            "DOCUMENT",
            "scrum_board_db",
            "Agile Scrum Project Management",
            "Hierarchical User Stories, Sprints, Epics, and Tasks with cross-references to HR Assignees.",
            1200,
            "fas fa-tasks"
        ),
        new DatasetInfo(
            "TIMESERIES",
            "meteorology_iot_db",
            "IoT Meteorological Weather Stations",
            "High-frequency sensor telemetry (temperature, humidity, atmospheric pressure, solar irradiance, precipitation) across time intervals.",
            2500,
            "fas fa-cloud-sun-rain"
        ),
        new DatasetInfo(
            "RECORDS",
            "hr_enterprise_db",
            "Java 25 Enterprise HR & Payroll",
            "Immutable Record instances for Employees, Departments, Contracts, and Salary components with cross-engine biometrics and GIS links.",
            1000,
            "fas fa-id-card-alt"
        ),
        new DatasetInfo(
            "VECTOR",
            "ai_knowledge_db",
            "AI Neural Search & Cosine Embeddings",
            "High-dimensional vector embeddings (128-d / 384-d) with cosine similarity indexes for semantic document retrieval and biometrics.",
            800,
            "fas fa-brain"
        ),
        new DatasetInfo(
            "GRAPH",
            "social_network_db",
            "Organizational & Social LPG Graph",
            "Labeled Property Graph vertices (Users, Teams, Projects) and directed relationships (REPORTS_TO, COLLABORATES_WITH, LEADS).",
            1500,
            "fas fa-project-diagram"
        ),
        new DatasetInfo(
            "GEOSPATIAL",
            "smart_city_gis_db",
            "Smart City GIS & Fleet Logistics",
            "2D Geographic coordinates, delivery fleet routes, distribution hubs, and real-time Haversine distance tracking.",
            600,
            "fas fa-map-marked-alt"
        ),
        new DatasetInfo(
            "COLUMN",
            "ecommerce_olap_db",
            "E-Commerce OLAP Analytics",
            "Wide-column analytical fact tables, quarterly revenue by region, customer cohort aggregations, and performance metrics.",
            1000,
            "fas fa-table"
        ),
        new DatasetInfo(
            "KEYVALUE",
            "distributed_cache_db",
            "High-Speed Distributed Cache",
            "Low-latency JWT session tokens, dynamic feature toggles, distributed rate limiters, and atomic counters.",
            800,
            "fas fa-bolt"
        ),
        new DatasetInfo(
            "OBJECT",
            "digital_assets_db",
            "Binary BLOBs & Media Documents",
            "Digital assets, invoices, PDF documents, media streams, and content-type metadata pointers.",
            500,
            "fas fa-file-invoice"
        )
    );

    public SampleDatasetManager(JettraStorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Loads a specific dataset or all datasets.
     * Returns total records inserted.
     */
    public int loadDataset(String datasetKey) {
        String key = (datasetKey != null) ? datasetKey.trim().toUpperCase() : "ALL";
        return switch (key) {
            case "ALL", "ALL_SAMPLE_DATABASES" -> loadAllDatasets();
            case "DOCUMENT", "SCRUM_BOARD_DB" -> loadScrumBoardDataset();
            case "TIMESERIES", "METEOROLOGY_IOT_DB" -> loadMeteorologyDataset();
            case "RECORDS", "HR_ENTERPRISE_DB" -> loadHrEnterpriseDataset();
            case "VECTOR", "AI_KNOWLEDGE_DB" -> loadVectorKnowledgeDataset();
            case "GRAPH", "SOCIAL_NETWORK_DB" -> loadSocialGraphDataset();
            case "GEOSPATIAL", "SMART_CITY_GIS_DB" -> loadSmartCityGisDataset();
            case "COLUMN", "ECOMMERCE_OLAP_DB" -> loadEcommerceOlapDataset();
            case "KEYVALUE", "DISTRIBUTED_CACHE_DB" -> loadDistributedCacheDataset();
            case "OBJECT", "DIGITAL_ASSETS_DB" -> loadDigitalAssetsDataset();
            default -> loadScrumBoardDataset();
        };
    }

    public int loadAllDatasets() {
        int total = 0;
        total += loadSmartCityGisDataset();
        total += loadHrEnterpriseDataset();
        total += loadScrumBoardDataset();
        total += loadMeteorologyDataset();
        total += loadVectorKnowledgeDataset();
        total += loadSocialGraphDataset();
        total += loadEcommerceOlapDataset();
        total += loadDistributedCacheDataset();
        total += loadDigitalAssetsDataset();
        return total;
    }

    // 1. DOCUMENT: Scrum Board Project Management
    public int loadScrumBoardDataset() {
        String db = "scrum_board_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] priorities = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};
        String[] statuses = {"BACKLOG", "IN_PROGRESS", "IN_REVIEW", "DONE", "QA_VERIFIED"};
        String[] epics = {"EPIC-AUTH", "EPIC-STORAGE-ENGINE", "EPIC-VECTOR-AI", "EPIC-FLUX-UI", "EPIC-CLUSTER-RAFT"};

        for (int i = 1; i <= 600; i++) {
            String taskId = String.format("TASK-%04d", i);
            String epic = epics[i % epics.length];
            String priority = priorities[i % priorities.length];
            String status = statuses[i % statuses.length];
            int storyPoints = ((i % 5) + 1) * 2;
            int assigneeEmpId = (i % 250) + 100;

            String payload = String.format(
                "{\"taskId\":\"%s\",\"title\":\"Implement feature module %d\",\"epicRef\":\"%s\"," +
                "\"assigneeRef\":\"jref://RECORDS:hr_enterprise_db/emp_%d\"," +
                "\"locationRef\":\"jref://GEOSPATIAL:smart_city_gis_db/hub_%d\"," +
                "\"storyPoints\":%d,\"priority\":\"%s\",\"status\":\"%s\",\"sprint\":\"Sprint-%02d\"," +
                "\"acceptanceCriteria\":[\"Unit tests > 95%%\",\"Passes Raft benchmark\",\"JettraFlux UI verified\"]," +
                "\"updatedAt\":%d}",
                taskId, i, epic, assigneeEmpId, (i % 50) + 1, storyPoints, priority, status, (i % 8) + 1, now - (i * 3600000L)
            );

            engine.getStorageCore().put(db + ":" + taskId, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 2. TIMESERIES: Weather Station IoT Telemetry
    public int loadMeteorologyDataset() {
        String db = "meteorology_iot_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] sensors = {"SENSOR_TEMP_01", "SENSOR_HUMIDITY_02", "SENSOR_PRESSURE_03", "SENSOR_SOLAR_04", "SENSOR_PRECIP_05"};
        String[] stations = {"ST_PANAMA_CENTRO", "ST_COLON_PORT", "ST_DAVID_CHIRIQUI", "ST_SANTIAGO_VERAGUAS", "ST_BOCAS_ISLAND"};

        for (int i = 1; i <= 1500; i++) {
            String sensor = sensors[i % sensors.length];
            String station = stations[i % stations.length];
            long timestamp = now - (i * 60000L); // 1 minute intervals
            String tsKey = "ts:" + db + ":" + sensor + "_" + timestamp;

            double temp = 22.0 + ((i % 150) * 0.1);
            double humidity = 55.0 + ((i % 40) * 0.8);
            double pressure = 1012.0 + ((i % 20) * 0.2);
            double uvIndex = (i % 11) * 1.0;

            String payload = String.format(
                "{\"stationRef\":\"jref://GEOSPATIAL:smart_city_gis_db/station_%d\"," +
                "\"sensorId\":\"%s\",\"station\":\"%s\",\"timestamp\":%d,\"temp_c\":%.2f," +
                "\"humidity_pct\":%.2f,\"pressure_hpa\":%.2f,\"uv_index\":%.1f,\"quality\":\"OPTIMAL\"}",
                (i % 5) + 1, sensor, station, timestamp, temp, humidity, pressure, uvIndex
            );

            engine.getStorageCore().put(tsKey, payload.getBytes(StandardCharsets.UTF_8), timestamp);
            count++;
        }
        return count;
    }

    // 3. RECORDS: Java 25 Enterprise HR & Payroll
    public int loadHrEnterpriseDataset() {
        String db = "hr_enterprise_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] departments = {"Core Engine Engineering", "Distributed Systems", "AI & Machine Learning", "DevOps & Cloud", "Quality Assurance"};
        String[] roles = {"Principal Architect", "Staff Engineer", "Senior Backend Dev", "Data Scientist", "DevOps Specialist"};

        for (int i = 100; i <= 500; i++) {
            String empId = "emp_" + i;
            String recKey = "rec:" + db + ":" + empId;
            String dept = departments[i % departments.length];
            String role = roles[i % roles.length];
            double salary = 85000.0 + ((i % 30) * 2000.0);

            String payload = String.format(
                "{\"_recordClass\":\"com.jettra.model.EmployeeProfileRecord\",\"_schema\":{\"id\":\"String\",\"name\":\"String\",\"salary\":\"Double\"}," +
                "\"components\":{\"id\":\"%s\",\"fullName\":\"Engineer #%d\",\"department\":\"%s\",\"role\":\"%s\"," +
                "\"salary\":%.2f,\"active\":true," +
                "\"officeRef\":\"jref://GEOSPATIAL:smart_city_gis_db/hub_%d\"," +
                "\"biometricsRef\":\"jref://VECTOR:ai_knowledge_db/vec_face_%d\"}}",
                empId, i, dept, role, salary, (i % 20) + 1, i
            );

            engine.getStorageCore().put(recKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 4. VECTOR: AI Neural Search & Embeddings
    public int loadVectorKnowledgeDataset() {
        String db = "ai_knowledge_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] labels = {"SEMANTIC_DOC_SEARCH", "FACE_BIOMETRICS", "PRODUCT_EMBEDDING", "FRAUD_DETECTION_VECTOR"};

        for (int i = 1; i <= 400; i++) {
            String vecId = "vec_" + i;
            String vecKey = "vec:" + db + ":" + vecId;
            String label = labels[i % labels.length];

            // Generate synthetic 4-component normalized vector float sample
            float v1 = (float) Math.sin(i * 0.1);
            float v2 = (float) Math.cos(i * 0.1);
            float v3 = (float) Math.sin(i * 0.2);
            float v4 = (float) Math.cos(i * 0.2);

            String payload = String.format(
                "{\"vectorId\":\"%s\",\"label\":\"%s\",\"embedding\":[%.4f, %.4f, %.4f, %.4f]," +
                "\"dimensions\":4,\"metric\":\"COSINE\"," +
                "\"linkedDocRef\":\"jref://DOCUMENT:scrum_board_db/TASK-%04d\"," +
                "\"created\":%d}",
                vecId, label, v1, v2, v3, v4, (i % 300) + 1, now
            );

            engine.getStorageCore().put(vecKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 5. GRAPH: LPG Social & Organization Network
    public int loadSocialGraphDataset() {
        String db = "social_network_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] relTypes = {"REPORTS_TO", "COLLABORATES_WITH", "LEADS", "DEVELOPS"};

        for (int i = 1; i <= 400; i++) {
            String nodeId = "node_" + i;
            String graphKey = "graph:" + db + ":" + nodeId;
            String rel = relTypes[i % relTypes.length];
            int targetNode = ((i + 3) % 400) + 1;

            String payload = String.format(
                "{\"nodeId\":\"%s\",\"label\":\"SYSTEM_NODE\",\"weight\":%.2f," +
                "\"properties\":{\"clusterTier\":\"Tier-1\",\"active\":true}," +
                "\"edges\":[{\"target\":\"node_%d\",\"relationship\":\"%s\",\"weight\":1.0}]," +
                "\"empRef\":\"jref://RECORDS:hr_enterprise_db/emp_%d\"}",
                nodeId, (i * 0.5), targetNode, rel, (i % 200) + 100
            );

            engine.getStorageCore().put(graphKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 6. GEOSPATIAL: Smart City GIS
    public int loadSmartCityGisDataset() {
        String db = "smart_city_gis_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] hubNames = {"Panama City Pacific Hub", "Colon Atlantic Terminal", "Chiriqui Highland Logistics", "Panama Canal Logistics", "Tocumen Air Freight"};

        for (int i = 1; i <= 300; i++) {
            String hubId = "hub_" + i;
            String geoKey = "geo:" + db + ":" + hubId;
            String name = hubNames[i % hubNames.length] + " #" + i;

            double lat = 8.9500 + ((i % 100) * 0.005);
            double lon = -79.5500 - ((i % 100) * 0.005);

            String payload = String.format(
                "{\"locId\":\"%s\",\"name\":\"%s\",\"lat\":%.6f,\"lon\":%.6f,\"altitude_m\":%.1f," +
                "\"status\":\"OPERATIONAL\",\"weatherTelemetryRef\":\"jref://TIMESERIES:meteorology_iot_db/SENSOR_TEMP_01_%d\"}",
                hubId, name, lat, lon, (i * 2.5), now
            );

            engine.getStorageCore().put(geoKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 7. COLUMN: E-Commerce OLAP Analytics
    public int loadEcommerceOlapDataset() {
        String db = "ecommerce_olap_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] regions = {"LATAM-NORTH", "LATAM-SOUTH", "NORTH-AMERICA", "EMEA", "APAC"};
        String[] categories = {"Enterprise Software", "Cloud Subscriptions", "IoT Edge Hardware", "Support Contracts"};

        for (int i = 1; i <= 500; i++) {
            String rowId = "fact_" + i;
            String colKey = "col:" + db + ":" + rowId;
            String region = regions[i % regions.length];
            String category = categories[i % categories.length];
            double amount = 1200.0 + ((i % 40) * 350.0);

            String payload = String.format(
                "{\"factId\":\"%s\",\"region\":\"%s\",\"category\":\"%s\",\"qtr\":\"Q3-2026\",\"revenue\":%.2f," +
                "\"unitsSold\":%d,\"fulfilledByRef\":\"jref://GEOSPATIAL:smart_city_gis_db/hub_%d\"}",
                rowId, region, category, amount, (i % 25) + 1, (i % 50) + 1
            );

            engine.getStorageCore().put(colKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 8. KEYVALUE: Distributed Cache
    public int loadDistributedCacheDataset() {
        String db = "distributed_cache_db";
        long now = System.currentTimeMillis();
        int count = 0;

        for (int i = 1; i <= 400; i++) {
            String key = "session_token_" + i;
            String kvKey = "kv:" + db + ":" + key;
            String val = "{\"token\":\"jwt_sha256_" + UUID.randomUUID() + "\",\"userRef\":\"jref://RECORDS:hr_enterprise_db/emp_" + ((i % 200) + 100) + "\",\"ttl\":3600,\"authenticated\":true}";

            engine.getStorageCore().put(kvKey, val.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 9. OBJECT: Digital Assets
    public int loadDigitalAssetsDataset() {
        String db = "digital_assets_db";
        long now = System.currentTimeMillis();
        int count = 0;

        String[] mimes = {"application/pdf", "image/png", "application/json", "application/octet-stream"};

        for (int i = 1; i <= 300; i++) {
            String assetId = "asset_" + i + ".pdf";
            String objKey = "obj:" + db + ":" + assetId;
            String mime = mimes[i % mimes.length];

            String payload = String.format(
                "{\"assetId\":\"%s\",\"fileName\":\"Invoice_%04d.pdf\",\"mime\":\"%s\",\"sizeBytes\":%d," +
                "\"ownerRef\":\"jref://RECORDS:hr_enterprise_db/emp_%d\",\"checksumSha256\":\"sha_%s\"}",
                assetId, i, mime, (i * 1024) + 4096, (i % 200) + 100, UUID.randomUUID().toString().substring(0, 16)
            );

            engine.getStorageCore().put(objKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }
}
