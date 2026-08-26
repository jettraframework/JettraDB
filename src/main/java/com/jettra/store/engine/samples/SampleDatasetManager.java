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
            "ALL",
            "ExampleDBReferences",
            "Cross-Engine & Multi-Cluster References Suite",
            "Demonstrates direct O(1) object references (jref://) with primary storage addresses, multi-cluster node pointers, and dynamic reference resolution across Document, Records, Geo, Vector, Object, KeyValue, and TimeSeries engines.",
            120,
            "fas fa-link"
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
            case "EXAMPLEDBREFERENCES", "REFERENCES" -> loadExampleDBReferencesDataset();
            case "DOCUMENT", "SCRUM_BOARD_DB" -> loadScrumBoardDataset();
            case "TIMESERIES", "METEOROLOGY_IOT_DB" -> loadMeteorologyDataset();
            case "RECORDS", "HR_ENTERPRISE_DB" -> loadHrEnterpriseDataset();
            case "VECTOR", "AI_KNOWLEDGE_DB" -> loadVectorKnowledgeDataset();
            case "GRAPH", "SOCIAL_NETWORK_DB" -> loadSocialGraphDataset();
            case "GEOSPATIAL", "SMART_CITY_GIS_DB" -> loadSmartCityGisDataset();
            case "COLUMN", "ECOMMERCE_OLAP_DB" -> loadEcommerceOlapDataset();
            case "KEYVALUE", "DISTRIBUTED_CACHE_DB" -> loadDistributedCacheDataset();
            case "OBJECT", "DIGITAL_ASSETS_DB" -> loadDigitalAssetsDataset();
            default -> loadExampleDBReferencesDataset();
        };
    }

    public int loadAllDatasets() {
        int total = 0;
        total += loadExampleDBReferencesDataset();
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

    // 0. EXAMPLEDBREFERENCES: Cross-Engine & Multi-Cluster References Demo Suite
    public int loadExampleDBReferencesDataset() {
        String db = "ExampleDBReferences";
        long now = System.currentTimeMillis();
        int count = 0;

        // 1. Referenced Target: DOCUMENT Customers
        String[] custNames = {"Panama Pacifico Logistics Corp", "Global Transatlantic Freight Ltd", "Canal Transit Maritime Services", "Andean Energy Solutions Inc"};
        for (int i = 1; i <= 4; i++) {
            String custId = "cust_" + (100 + i);
            String docKey = "doc:" + db + ":" + custId;
            String payload = String.format(
                "{\"id\":\"%s\",\"companyName\":\"%s\",\"taxId\":\"PA-%d-2026\",\"tier\":\"ENTERPRISE\",\"email\":\"billing@%s.com\",\"phone\":\"+507 833-%04d\",\"status\":\"ACTIVE\",\"primaryStorageAddress\":\"%s\"}",
                custId, custNames[i - 1], 8000 + i, custNames[i - 1].toLowerCase().replace(" ", ""), i * 111, docKey
            );
            engine.getStorageCore().put(docKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + custId, payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 2. Referenced Target: RECORDS Employees (Java 25 Records)
        String[] empNames = {"Carlos Mendez", "Sofia Alarcon", "Elena Rostova", "David Chen"};
        String[] empRoles = {"Principal Distributed Architect", "Lead AI Engineer", "Senior Infrastructure Specialist", "Logistics Operations Lead"};
        for (int i = 1; i <= 4; i++) {
            String empId = "emp_" + (200 + i);
            String recKey = "rec:" + db + ":" + empId;
            String payload = String.format(
                "{\"_recordClass\":\"com.enterprise.model.EmployeeProfileRecord\",\"id\":\"%s\",\"fullName\":\"%s\",\"role\":\"%s\",\"department\":\"Core Architecture\",\"salary\":%.2f,\"active\":true,\"primaryStorageAddress\":\"%s\"}",
                empId, empNames[i - 1], empRoles[i - 1], 95000.0 + (i * 8000.0), recKey
            );
            engine.getStorageCore().put(recKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + empId, payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 3. Referenced Target: GEOSPATIAL Distribution Hubs
        double[][] coords = {{8.9824, -79.5199}, {9.3598, -79.9001}, {8.4273, -82.4309}, {8.0987, -80.9821}};
        String[] hubNames = {"Hub Logistico Central Panama", "Hub Terminal Portuaria Colon", "Hub Occidente David Chiriqui", "Hub Provincias Centrales Santiago"};
        for (int i = 1; i <= 4; i++) {
            String hubId = "hub_" + (i == 1 ? "panama" : (i == 2 ? "colon" : (i == 3 ? "david" : "santiago")));
            String geoKey = "geo:" + db + ":" + hubId;
            String payload = String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"lat\":%.4f,\"lon\":%.4f,\"type\":\"REGIONAL_DISTRIBUTION_CENTER\",\"capacityTons\":%d,\"primaryStorageAddress\":\"%s\"}",
                hubId, hubNames[i - 1], coords[i - 1][0], coords[i - 1][1], 25000 * i, geoKey
            );
            engine.getStorageCore().put(geoKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put("geo:" + db + ":stores_layer:" + hubId, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put("geo:" + db + ":default:" + hubId, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + hubId, payload.getBytes(StandardCharsets.UTF_8), now);
            count += 4;

            if (i == 2) {
                // Also provide geo_202 and 202 alias for Colon port hub
                engine.getStorageCore().put("geo:" + db + ":geo_202", payload.getBytes(StandardCharsets.UTF_8), now);
                engine.getStorageCore().put("geo:" + db + ":202", payload.getBytes(StandardCharsets.UTF_8), now);
                engine.getStorageCore().put(db + ":geo_202", payload.getBytes(StandardCharsets.UTF_8), now);
                count += 3;
            }
        }

        // 4. Referenced Target: VECTOR AI Embeddings
        float[][] vectors = {{0.18f, 0.72f, 0.45f, 0.89f}, {0.85f, 0.12f, 0.63f, 0.41f}, {0.33f, 0.91f, 0.15f, 0.76f}};
        String[] vecLabels = {"VectorFaceAuth_Carlos", "VectorProductSemantic_CloudServer", "VectorSignatureAudit_Contract"};
        for (int i = 1; i <= 3; i++) {
            String vecId = "vec_" + (i == 1 ? "face_carlos" : (i == 2 ? "prod_embed_01" : "signature_contract"));
            String vecKey = "vec:" + db + ":" + vecId;
            String payload = String.format(
                "{\"id\":\"%s\",\"label\":\"%s\",\"coordinates\":[%s],\"dimensions\":4,\"metric\":\"COSINE\",\"primaryStorageAddress\":\"%s\"}",
                vecId, vecLabels[i - 1], String.format(Locale.US, "%.2f, %.2f, %.2f, %.2f", vectors[i - 1][0], vectors[i - 1][1], vectors[i - 1][2], vectors[i - 1][3]), vecKey
            );
            engine.getStorageCore().put(vecKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + vecId, payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 5. Referenced Target: OBJECT Digital BLOBs & Invoices
        String[] objFiles = {"contract_enterprise_2026.pdf", "invoice_ORD-7001.pdf", "audit_compliance_report.pdf"};
        for (int i = 1; i <= 3; i++) {
            String objKey = "obj:" + db + ":" + objFiles[i - 1];
            String payload = String.format(
                "{\"bucket\":\"contracts\",\"fileName\":\"%s\",\"mimeType\":\"application/pdf\",\"sizeBytes\":%d,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852%04d\",\"primaryStorageAddress\":\"%s\"}",
                objFiles[i - 1], 102400 * i, i * 77, objKey
            );
            engine.getStorageCore().put(objKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + objFiles[i - 1], payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 6. Referenced Target: KEYVALUE Dynamic Session & Config
        String kvKey1 = "kv:" + db + ":session_token_carlos";
        String kvPayload1 = "{\"token\":\"JWT_SECURE_TOKEN_2026_CARLOS\",\"userId\":\"emp_201\",\"active\":true,\"expiresIn\":86400,\"primaryStorageAddress\":\"kv:ExampleDBReferences:session_token_carlos\"}";
        engine.getStorageCore().put(kvKey1, kvPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":session_token_carlos", kvPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 7. Referenced Target: TIMESERIES IoT Power Reading
        String tsKey1 = "ts:" + db + ":iot_hub_power_01";
        String tsPayload1 = String.format("{\"metric\":\"power_consumption_kwh\",\"stationRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\",\"value\":48.6,\"unit\":\"kWh\",\"status\":\"OPTIMAL\",\"timestamp\":%d,\"primaryStorageAddress\":\"ts:ExampleDBReferences:iot_hub_power_01\"}", now);
        engine.getStorageCore().put(tsKey1, tsPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":iot_hub_power_01", tsPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 8. MASTER ENTITY 1 (DOCUMENT): Order Master with Local & Multi-Cluster References
        String orderDocKey = "doc:" + db + ":order_master_7001";
        String orderPayload = String.format(
            "{\"orderId\":\"ORD-2026-7001\",\"description\":\"Enterprise Cloud Server & Logistics Contract Deployment\"," +
            "\"totalAmount\":24500.00,\"currency\":\"USD\",\"status\":\"CONFIRMED\",\"primaryStorageAddress\":\"%s\"," +
            "\"customerRef\":\"jref://DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"leadArchitectRef\":\"jref://RECORDS:ExampleDBReferences/emp_201\"," +
            "\"fulfillmentHubRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\"," +
            "\"contractDocRef\":\"jref://OBJECT:ExampleDBReferences/contract_enterprise_2026.pdf\"," +
            "\"biometricsAuditRef\":\"jref://VECTOR:ExampleDBReferences/vec_face_carlos\"," +
            "\"activeSessionRef\":\"jref://KEYVALUE:ExampleDBReferences/session_token_carlos\"," +
            "\"powerMonitoringRef\":\"jref://TIMESERIES:ExampleDBReferences/iot_hub_power_01\"," +
            "\"multiClusterBackupRef\":\"jref://cluster-east-01@DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"remoteAiNeuralNodeRef\":\"jref://node-cloud-west@VECTOR:ExampleDBReferences/vec_prod_embed_01\"," +
            "\"createdAt\":%d}",
            orderDocKey, now
        );
        engine.getStorageCore().put(orderDocKey, orderPayload.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":order_master_7001", orderPayload.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 9. MASTER ENTITY 2 (DOCUMENT): Order Master 7002
        String order2DocKey = "doc:" + db + ":order_master_7002";
        String order2Payload = String.format(
            "{\"orderId\":\"ORD-2026-7002\",\"description\":\"Port Operations Freight & Telemetry Monitoring\"," +
            "\"totalAmount\":18900.00,\"currency\":\"USD\",\"status\":\"IN_TRANSIT\",\"primaryStorageAddress\":\"%s\"," +
            "\"customerRef\":\"jref://DOCUMENT:ExampleDBReferences/cust_102\"," +
            "\"leadArchitectRef\":\"jref://RECORDS:ExampleDBReferences/emp_202\"," +
            "\"fulfillmentHubRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_colon\"," +
            "\"contractDocRef\":\"jref://OBJECT:ExampleDBReferences/invoice_ORD-7001.pdf\"," +
            "\"remoteSecondaryClusterRef\":\"jref://cluster-secondary-02@RECORDS:ExampleDBReferences/emp_202\"," +
            "\"createdAt\":%d}",
            order2DocKey, now
        );
        engine.getStorageCore().put(order2DocKey, order2Payload.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":order_master_7002", order2Payload.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 10. MASTER ENTITY 3 (RECORDS): Invoice Transaction Record
        String invRecKey = "rec:" + db + ":rec_invoice_9001";
        String invPayload = String.format(
            "{\"_recordClass\":\"com.enterprise.model.InvoiceTransactionRecord\",\"invoiceId\":\"INV-2026-9001\",\"billingDate\":\"2026-08-25\"," +
            "\"subtotal\":22897.20,\"tax\":1602.80,\"total\":24500.00,\"primaryStorageAddress\":\"%s\"," +
            "\"billedCustomer\":\"jref://DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"salesExecutive\":\"jref://RECORDS:ExampleDBReferences/emp_201\"," +
            "\"dispatchHub\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\"," +
            "\"associatedOrderDoc\":\"jref://DOCUMENT:ExampleDBReferences/order_master_7001\"," +
            "\"remoteAuditStore\":\"jref://cluster-europe-03@KEYVALUE:ExampleDBReferences/session_token_carlos\"}",
            invRecKey
        );
        engine.getStorageCore().put(invRecKey, invPayload.getBytes(StandardCharsets.UTF_8), now);
        count++;

        return count;
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
