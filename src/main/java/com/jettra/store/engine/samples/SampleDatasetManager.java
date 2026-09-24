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
            "MULTI-MODEL",
            SampleDatabaseNamingPolicy.EXAMPLE_DB_REFERENCES,
            "Cross-Engine & Multi-Cluster References Suite",
            "Demonstrates direct O(1) object references (jref://) with primary storage addresses, multi-cluster node pointers, and dynamic reference resolution across Document, Records, Geo, Vector, Object, KeyValue, TimeSeries, Graph, and Column engines.",
            120,
            "fas fa-link"
        ),
        new DatasetInfo(
            "RECORDS",
            SampleDatabaseNamingPolicy.EXAMPLE_HR_ENTERPRISE_DB,
            "Java 25 Enterprise HR & Payroll",
            "Immutable Record instances for Employees, Departments, Contracts, and Salary components with cross-engine biometrics and GIS links.",
            1000,
            "fas fa-id-card-alt"
        ),
        new DatasetInfo(
            "TIMESERIES",
            SampleDatabaseNamingPolicy.EXAMPLE_METEOROLOGY_IOT_DB,
            "IoT Meteorological Weather Stations",
            "High-frequency sensor telemetry (temperature, humidity, atmospheric pressure, solar irradiance, precipitation) across time intervals.",
            2500,
            "fas fa-cloud-sun-rain"
        ),
        new DatasetInfo(
            "COLUMN",
            SampleDatabaseNamingPolicy.EXAMPLE_ECOMMERCE_OLAP_DB,
            "E-Commerce OLAP Analytics",
            "Wide-column analytical fact tables, quarterly revenue by region, customer cohort aggregations, and performance metrics.",
            1000,
            "fas fa-table"
        ),
        new DatasetInfo(
            "MULTI-MODEL",
            SampleDatabaseNamingPolicy.EXAMPLE_FACTURA,
            "Enterprise Invoicing & Billing (Multi-Engine)",
            "High-volume enterprise invoicing and billing suite with 1,000,000 customers (Document), 500,000 products (Records), inventories (TimeSeries), invoices & details (Column), branches (Geospatial), categories (Graph), and salespersons (KeyValue).",
            1566175,
            "fas fa-file-invoice-dollar"
        )
    );

    public SampleDatasetManager(JettraStorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Loads exclusively one of the authorized sample datasets.
     * Returns total records inserted.
     */
    public int loadDataset(String datasetKey) {
        if (datasetKey == null || datasetKey.isBlank()) {
            throw new IllegalArgumentException("Dataset key must not be null or blank");
        }
        String key = datasetKey.trim().toUpperCase();
        return switch (key) {
            case "EXAMPLEFACTURA", "FACTURA", "EXAMPLE_FACTURA", "BILLING" -> loadExampleFacturaDataset();
            case "EXAMPLEDBREFERENCES", "REFERENCES", "EXAMPLE_DB_REFERENCES" -> loadExampleDBReferencesDataset();
            case "EXAMPLEHRENTERPRISEDB", "EXAMPLEHRENTERPRISE", "EXAMPLE_HR_ENTERPRISE_DB", "HR_ENTERPRISE_DB", "RECORDS" -> loadHrEnterpriseDataset();
            case "EXAMPLEMETEOROLOGYIOTDB", "EXAMPLEMETEOROLOGYIOT", "EXAMPLE_METEOROLOGY_IOT_DB", "METEOROLOGY_IOT_DB", "TIMESERIES" -> loadMeteorologyDataset();
            case "EXAMPLEECOMMERCEOLAPDB", "EXAMPLEECOMMERCEOLAP", "EXAMPLE_ECOMMERCE_OLAP_DB", "ECOMMERCE_OLAP_DB", "COLUMN" -> loadEcommerceOlapDataset();
            case "EXAMPLESCRUMBOARDDB", "EXAMPLESCRUMBOARD", "SCRUM_BOARD_DB", "DOCUMENT" -> loadScrumBoardDataset();
            case "EXAMPLESMARTCITYGISDB", "EXAMPLESMARTCITYGIS", "SMART_CITY_GIS_DB", "GEOSPATIAL" -> loadSmartCityGisDataset();
            case "EXAMPLEAIKNOWLEDGEDB", "EXAMPLEAIKNOWLEDGE", "AI_KNOWLEDGE_DB", "VECTOR" -> loadVectorKnowledgeDataset();
            case "EXAMPLESOCIALNETWORKDB", "EXAMPLESOCIALNETWORK", "SOCIAL_NETWORK_DB", "GRAPH" -> loadSocialGraphDataset();
            case "EXAMPLEDISTRIBUTEDCACHEDB", "EXAMPLEDISTRIBUTEDCACHE", "DISTRIBUTED_CACHE_DB", "KEYVALUE" -> loadDistributedCacheDataset();
            case "EXAMPLEDIGITALASSETSDB", "EXAMPLEDIGITALASSETS", "DIGITAL_ASSETS_DB", "OBJECT" -> loadDigitalAssetsDataset();
            default -> throw new IllegalArgumentException("Unsupported dataset: " + datasetKey);
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
                "{\"id\":\"%s\",\"companyName\":\"%s\",\"taxId\":\"PA-%d-2026\",\"tier\":\"ENTERPRISE\",\"email\":\"billing@%s.com\",\"phone\":\"+507 833-%04d\",\"status\":\"ACTIVE\"}",
                custId, custNames[i - 1], 8000 + i, custNames[i - 1].toLowerCase().replace(" ", ""), i * 111
            );
            engine.getStorageCore().put(docKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + custId, payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 2. Referenced Target: RECORDS Employees (Java 25 Records)
        String[] empNames = {"Carlos Mendez", "Sofia Alarcon", "Elena Rostova", "David Chen"};
        String[] empRoles = {"Principal Distributed Architect", "Lead AI Engineer", "Senior Infrastructure Specialist", "Logistics Operations Lead"};
        String[] hireDates = {"2022-03-15", "2023-07-01", "2021-11-20", "2024-02-10"};
        String[] shifts = {"08:00:00", "09:00:00", "07:30:00", "08:30:00"};
        String[] contractTypes = {"FULL_TIME", "FULL_TIME", "PERMANENT", "CONTRACTOR"};
        String[] skillsArr = {
            "[\"Java 25\", \"Distributed Storage\", \"Raft Consensus\"]",
            "[\"Python\", \"PyTorch\", \"Vector Embeddings\", \"Neural Search\"]",
            "[\"Kubernetes\", \"Linux Kernel\", \"eBPF\", \"Cloud Infrastructure\"]",
            "[\"Supply Chain Logistics\", \"GIS Tracking\", \"Operations Management\"]"
        };
        for (int i = 1; i <= 4; i++) {
            String empId = "emp_" + (200 + i);
            String recKey = "rec:" + db + ":" + empId;
            String payload = String.format(
                "{\"_recordClass\":\"com.enterprise.model.EmployeeProfileRecord\",\"_table\":\"employees\",\"_timestamp\":%d,\"_version\":1,\"id\":\"%s\"," +
                "\"_schema\":{\"id\":\"String\",\"fullName\":\"String\",\"role\":\"String\",\"department\":\"String\",\"salary\":\"Double\",\"hireDate\":\"LocalDate\",\"shift\":\"LocalTime\",\"contractType\":\"Enum\",\"active\":\"Boolean\",\"skills\":\"List<String>\",\"residenceCountry\":\"Object\"}," +
                "\"components\":{\"id\":\"%s\",\"fullName\":\"%s\",\"role\":\"%s\",\"department\":\"Core Architecture\",\"salary\":%.2f,\"hireDate\":\"%s\",\"shift\":\"%s\",\"contractType\":\"%s\",\"active\":true,\"skills\":%s,\"residenceCountry\":{\"code\":\"PA\",\"name\":\"Panama\"}}}",
                now, empId, empId, empNames[i - 1], empRoles[i - 1], 95000.0 + (i * 8000.0), hireDates[i - 1], shifts[i - 1], contractTypes[i - 1], skillsArr[i - 1]
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
                "{\"id\":\"%s\",\"name\":\"%s\",\"lat\":%.4f,\"lon\":%.4f,\"type\":\"REGIONAL_DISTRIBUTION_CENTER\",\"capacityTons\":%d}",
                hubId, hubNames[i - 1], coords[i - 1][0], coords[i - 1][1], 25000 * i
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
                "{\"id\":\"%s\",\"label\":\"%s\",\"coordinates\":[%s],\"dimensions\":4,\"metric\":\"COSINE\"}",
                vecId, vecLabels[i - 1], String.format(Locale.US, "%.2f, %.2f, %.2f, %.2f", vectors[i - 1][0], vectors[i - 1][1], vectors[i - 1][2], vectors[i - 1][3])
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
                "{\"bucket\":\"contracts\",\"fileName\":\"%s\",\"mimeType\":\"application/pdf\",\"sizeBytes\":%d,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852%04d\"}",
                objFiles[i - 1], 102400 * i, i * 77
            );
            engine.getStorageCore().put(objKey, payload.getBytes(StandardCharsets.UTF_8), now);
            engine.getStorageCore().put(db + ":" + objFiles[i - 1], payload.getBytes(StandardCharsets.UTF_8), now);
            count += 2;
        }

        // 6. Referenced Target: KEYVALUE Dynamic Session & Config
        String kvKey1 = "kv:" + db + ":session_token_carlos";
        String kvPayload1 = "{\"token\":\"JWT_SECURE_TOKEN_2026_CARLOS\",\"userId\":\"emp_201\",\"active\":true,\"expiresIn\":86400}";
        engine.getStorageCore().put(kvKey1, kvPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":session_token_carlos", kvPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 7. Referenced Target: TIMESERIES IoT Power Reading
        String tsKey1 = "ts:" + db + ":iot_hub_power_01";
        String tsPayload1 = String.format("{\"metric\":\"power_consumption_kwh\",\"stationRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\",\"value\":48.6,\"unit\":\"kWh\",\"status\":\"OPTIMAL\",\"timestamp\":%d}", now);
        engine.getStorageCore().put(tsKey1, tsPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":iot_hub_power_01", tsPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 8. Referenced Target: GRAPH Knowledge & Logistics Network
        String graphNodeKey1 = "graph:" + db + ":node_network_01";
        String graphNodeKey2 = "graph:" + db + ":node:node_network_01";
        String graphPayload1 = String.format(
            "{\"nodeId\":\"node_network_01\",\"label\":\"Logistics Hub Network\",\"type\":\"VERTEX\"," +
            "\"properties\":{\"clusterTier\":\"Tier-1\",\"active\":true,\"region\":\"LATAM\"}," +
            "\"edges\":[{\"target\":\"node_network_02\",\"relationship\":\"CONNECTS_TO\",\"weight\":1.0}]," +
            "\"leadArchitectRef\":\"jref://RECORDS:ExampleDBReferences/emp_201\"," +
            "\"createdAt\":%d}",
            now
        );
        engine.getStorageCore().put(graphNodeKey1, graphPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(graphNodeKey2, graphPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":node_network_01", graphPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 3;

        // 9. Referenced Target: COLUMN Wide-Column Analytical OLAP Facts
        String colFactKey1 = "col:" + db + ":fact_sales_01";
        String colFactKey2 = "col:" + db + ":sales_facts:fact_sales_01";
        String colPayload1 = String.format(
            "{\"factId\":\"fact_sales_01\",\"columnFamily\":\"sales_facts\",\"region\":\"LATAM-NORTH\"," +
            "\"qtr\":\"Q3-2026\",\"revenue\":24500.00,\"unitsSold\":150,\"status\":\"SETTLED\"," +
            "\"customerRef\":\"jref://DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"createdAt\":%d}",
            now
        );
        engine.getStorageCore().put(colFactKey1, colPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(colFactKey2, colPayload1.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":fact_sales_01", colPayload1.getBytes(StandardCharsets.UTF_8), now);
        count += 3;

        // 10. MASTER ENTITY 1 (DOCUMENT): Order Master with Clean Cross-Engine Jref References
        String orderDocKey = "doc:" + db + ":order_master_7001";
        String orderPayload = String.format(
            "{\"orderId\":\"ORD-2026-7001\",\"description\":\"Enterprise Cloud Server & Logistics Contract Deployment\"," +
            "\"totalAmount\":24500.00,\"currency\":\"USD\",\"status\":\"CONFIRMED\"," +
            "\"customerRef\":\"jref://DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"leadArchitectRef\":\"jref://RECORDS:ExampleDBReferences/emp_201\"," +
            "\"fulfillmentHubRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\"," +
            "\"contractDocRef\":\"jref://OBJECT:ExampleDBReferences/contract_enterprise_2026.pdf\"," +
            "\"biometricsAuditRef\":\"jref://VECTOR:ExampleDBReferences/vec_face_carlos\"," +
            "\"activeSessionRef\":\"jref://KEYVALUE:ExampleDBReferences/session_token_carlos\"," +
            "\"powerMonitoringRef\":\"jref://TIMESERIES:ExampleDBReferences/iot_hub_power_01\"," +
            "\"graphNetworkRef\":\"jref://GRAPH:ExampleDBReferences/node_network_01\"," +
            "\"salesAnalyticsRef\":\"jref://COLUMN:ExampleDBReferences/fact_sales_01\"," +
            "\"createdAt\":%d}",
            now
        );
        engine.getStorageCore().put(orderDocKey, orderPayload.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":order_master_7001", orderPayload.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 9. MASTER ENTITY 2 (DOCUMENT): Order Master 7002 (Clean Single Jref References)
        String order2DocKey = "doc:" + db + ":order_master_7002";
        String order2Payload = String.format(
            "{\"orderId\":\"ORD-2026-7002\",\"description\":\"Port Operations Freight & Telemetry Monitoring\"," +
            "\"totalAmount\":18900.00,\"currency\":\"USD\",\"status\":\"IN_TRANSIT\"," +
            "\"customerRef\":\"jref://DOCUMENT:ExampleDBReferences/cust_102\"," +
            "\"leadArchitectRef\":\"jref://RECORDS:ExampleDBReferences/emp_202\"," +
            "\"fulfillmentHubRef\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_colon\"," +
            "\"contractDocRef\":\"jref://OBJECT:ExampleDBReferences/invoice_ORD-7001.pdf\"," +
            "\"createdAt\":%d}",
            now
        );
        engine.getStorageCore().put(order2DocKey, order2Payload.getBytes(StandardCharsets.UTF_8), now);
        engine.getStorageCore().put(db + ":order_master_7002", order2Payload.getBytes(StandardCharsets.UTF_8), now);
        count += 2;

        // 10. MASTER ENTITY 3 (RECORDS): Invoice Transaction Record (Clean Single Jref References)
        String invRecKey = "rec:" + db + ":rec_invoice_9001";
        String invPayload = String.format(
            "{\"_recordClass\":\"com.enterprise.model.InvoiceTransactionRecord\",\"_table\":\"invoices\",\"_timestamp\":%d,\"_version\":1,\"invoiceId\":\"INV-2026-9001\"," +
            "\"_schema\":{\"invoiceId\":\"String\",\"billingDate\":\"LocalDate\",\"subtotal\":\"Double\",\"tax\":\"Double\",\"total\":\"Double\",\"status\":\"Enum\",\"billedCustomer\":\"String\",\"salesExecutive\":\"String\",\"dispatchHub\":\"String\",\"associatedOrderDoc\":\"String\"}," +
            "\"components\":{\"invoiceId\":\"INV-2026-9001\",\"billingDate\":\"2026-08-25\",\"subtotal\":22897.20,\"tax\":1602.80,\"total\":24500.00,\"status\":\"PAID\"," +
            "\"billedCustomer\":\"jref://DOCUMENT:ExampleDBReferences/cust_101\"," +
            "\"salesExecutive\":\"jref://RECORDS:ExampleDBReferences/emp_201\"," +
            "\"dispatchHub\":\"jref://GEOSPATIAL:ExampleDBReferences/hub_panama\"," +
            "\"associatedOrderDoc\":\"jref://DOCUMENT:ExampleDBReferences/order_master_7001\"}}",
            now
        );
        engine.getStorageCore().put(invRecKey, invPayload.getBytes(StandardCharsets.UTF_8), now);
        count++;

        return count;
    }

    // 1. DOCUMENT: Scrum Board Project Management
    public int loadScrumBoardDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_SCRUM_BOARD_DB;
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
                "\"assigneeRef\":\"jref://RECORDS:ExampleHrEnterpriseDb/emp_%d\"," +
                "\"locationRef\":\"jref://GEOSPATIAL:ExampleSmartCityGisDb/hub_%d\"," +
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
        String db = SampleDatabaseNamingPolicy.EXAMPLE_METEOROLOGY_IOT_DB;
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

            String payload = String.format(Locale.US,
                "{\"stationRef\":\"jref://GEOSPATIAL:ExampleSmartCityGisDb/station_%d\"," +
                "\"sensorId\":\"%s\",\"station\":\"%s\",\"timestamp\":%d,\"temp_c\":%.2f," +
                "\"humidity_pct\":%.2f,\"pressure_hpa\":%.2f,\"uv_index\":%.1f,\"quality\":\"OPTIMAL\",\"operationalState\":\"ONLINE\"}",
                (i % 5) + 1, sensor, station, timestamp, temp, humidity, pressure, uvIndex
            );

            engine.getStorageCore().put(tsKey, payload.getBytes(StandardCharsets.UTF_8), timestamp);
            count++;
        }
        return count;
    }

    // 3. RECORDS: Java 25 Enterprise HR & Payroll
    public int loadHrEnterpriseDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_HR_ENTERPRISE_DB;
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
            String hireDate = String.format("202%d-%02d-%02d", (i % 4) + 1, (i % 12) + 1, (i % 28) + 1);
            String shift = String.format("%02d:00:00", 8 + (i % 4));
            String contractType = (i % 5 == 0) ? "CONTRACTOR" : "PERMANENT";
            String country = (i % 3 == 0) ? "Panama" : ((i % 3 == 1) ? "Costa Rica" : "Colombia");
            String countryCode = (i % 3 == 0) ? "PA" : ((i % 3 == 1) ? "CR" : "CO");
            String skillsJson = String.format("[\"Java 25\", \"Virtual Threads\", \"%s\", \"Distributed Architecture\"]", (i % 2 == 0 ? "Raft Consensus" : "High-Performance I/O"));

            String payload = String.format(Locale.US,
                "{\"_recordClass\":\"com.jettra.model.EmployeeProfileRecord\",\"_table\":\"employees\",\"_timestamp\":%d,\"_version\":1,\"id\":\"%s\"," +
                "\"_schema\":{\"id\":\"String\",\"fullName\":\"String\",\"department\":\"String\",\"role\":\"String\",\"contractType\":\"Enum\",\"salary\":\"Double\",\"hireDate\":\"LocalDate\",\"shift\":\"LocalTime\",\"active\":\"Boolean\",\"skills\":\"List<String>\",\"country\":\"Object\",\"officeRef\":\"String\",\"biometricsRef\":\"String\"}," +
                "\"components\":{\"id\":\"%s\",\"fullName\":\"Engineer #%d\",\"department\":\"%s\",\"role\":\"%s\",\"contractType\":\"%s\"," +
                "\"salary\":%.2f,\"hireDate\":\"%s\",\"shift\":\"%s\",\"active\":true," +
                "\"skills\":%s,\"country\":{\"code\":\"%s\",\"name\":\"%s\"}," +
                "\"officeRef\":\"jref://GEOSPATIAL:ExampleSmartCityGisDb/hub_%d\"," +
                "\"biometricsRef\":\"jref://VECTOR:ExampleAiKnowledgeDb/vec_face_%d\"}}",
                now, empId, empId, i, dept, role, contractType, salary, hireDate, shift, skillsJson, countryCode, country, (i % 20) + 1, i
            );

            engine.getStorageCore().put(recKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 4. VECTOR: AI Neural Search & Embeddings
    public int loadVectorKnowledgeDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_AI_KNOWLEDGE_DB;
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
                "\"linkedDocRef\":\"jref://DOCUMENT:ExampleScrumBoardDb/TASK-%04d\"," +
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
        String db = SampleDatabaseNamingPolicy.EXAMPLE_SOCIAL_NETWORK_DB;
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
                "\"empRef\":\"jref://RECORDS:ExampleHrEnterpriseDb/emp_%d\"}",
                nodeId, (i * 0.5), targetNode, rel, (i % 200) + 100
            );

            engine.getStorageCore().put(graphKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 6. GEOSPATIAL: Smart City GIS
    public int loadSmartCityGisDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_SMART_CITY_GIS_DB;
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
                "\"status\":\"OPERATIONAL\",\"weatherTelemetryRef\":\"jref://TIMESERIES:ExampleMeteorologyIotDb/SENSOR_TEMP_01_%d\"}",
                hubId, name, lat, lon, (i * 2.5), now
            );

            engine.getStorageCore().put(geoKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 7. COLUMN: E-Commerce OLAP Analytics
    public int loadEcommerceOlapDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_ECOMMERCE_OLAP_DB;
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
                "\"unitsSold\":%d,\"fulfilledByRef\":\"jref://GEOSPATIAL:ExampleSmartCityGisDb/hub_%d\"}",
                rowId, region, category, amount, (i % 25) + 1, (i % 50) + 1
            );

            engine.getStorageCore().put(colKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 8. KEYVALUE: Distributed Cache
    public int loadDistributedCacheDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_DISTRIBUTED_CACHE_DB;
        long now = System.currentTimeMillis();
        int count = 0;

        for (int i = 1; i <= 400; i++) {
            String key = "session_token_" + i;
            String kvKey = "kv:" + db + ":" + key;
            String val = "{\"token\":\"jwt_sha256_" + UUID.randomUUID() + "\",\"userRef\":\"jref://RECORDS:ExampleHrEnterpriseDb/emp_" + ((i % 200) + 100) + "\",\"ttl\":3600,\"authenticated\":true}";

            engine.getStorageCore().put(kvKey, val.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    // 9. OBJECT: Digital Assets
    public int loadDigitalAssetsDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_DIGITAL_ASSETS_DB;
        long now = System.currentTimeMillis();
        int count = 0;

        String[] mimes = {"application/pdf", "image/png", "application/json", "application/octet-stream"};

        for (int i = 1; i <= 300; i++) {
            String assetId = "asset_" + i + ".pdf";
            String objKey = "obj:" + db + ":" + assetId;
            String mime = mimes[i % mimes.length];

            String payload = String.format(
                "{\"assetId\":\"%s\",\"fileName\":\"Invoice_%04d.pdf\",\"mime\":\"%s\",\"sizeBytes\":%d," +
                "\"ownerRef\":\"jref://RECORDS:ExampleHrEnterpriseDb/emp_%d\",\"checksumSha256\":\"sha_%s\"}",
                assetId, i, mime, (i * 1024) + 4096, (i % 200) + 100, UUID.randomUUID().toString().substring(0, 16)
            );

            engine.getStorageCore().put(objKey, payload.getBytes(StandardCharsets.UTF_8), now);
            count++;
        }
        return count;
    }

    private boolean isRunningInTest() {
        if (Boolean.getBoolean("jettra.factura.3m")) {
            return false;
        }
        if (System.getProperty("jettra.factura.products") != null) {
            return Integer.getInteger("jettra.factura.products", 799895) <= 1000;
        }
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (className.contains("Test") || className.contains("junit") || className.contains("jettra.test")) {
                return true;
            }
        }
        return false;
    }

    // 10. EXAMPLEFACTURA: High-Volume Multi-Model Enterprise Billing & Invoicing Suite (3,000,000 Objects across 9 Engines)
    public int loadExampleFacturaDataset() {
        String db = SampleDatabaseNamingPolicy.EXAMPLE_FACTURA;
        long now = System.currentTimeMillis();
        int totalInserted = 0;
        final int batchLimit = 50000;
        List<Map.Entry<String, byte[]>> batch = new ArrayList<>(batchLimit);

        boolean isTestScale = isRunningInTest();

        int prodCount = Integer.getInteger("jettra.factura.products", isTestScale ? 100 : 799895);
        int custCount = Integer.getInteger("jettra.factura.customers", isTestScale ? 200 : 600000);
        int contractCount = Integer.getInteger("jettra.factura.contracts", isTestScale ? 50 : 400000);
        int facCount = Integer.getInteger("jettra.factura.invoices", isTestScale ? 50 : 100000);
        int detCount = Integer.getInteger("jettra.factura.invoice_details", isTestScale ? 100 : 700000);
        int invCount = Integer.getInteger("jettra.factura.inventory", isTestScale ? 50 : 150000);
        int kvCount = Integer.getInteger("jettra.factura.sessions", isTestScale ? 50 : 100000);
        int geoCount = Integer.getInteger("jettra.factura.geospatial", isTestScale ? 20 : 50000);
        int graphCount = Integer.getInteger("jettra.factura.graph", isTestScale ? 20 : 50000);
        int objCount = Integer.getInteger("jettra.factura.objects", isTestScale ? 20 : 25000);
        int vecCount = Integer.getInteger("jettra.factura.vectors", isTestScale ? 20 : 25000);

        // 1. COMPAÑÍAS: RECORDS Engine (5 enterprise entities)
        String[] companyNames = {
            "Corporación FacturaTech Global, S.A.",
            "Distribuidora Panameña de Alimentos, S.A.",
            "Soluciones Tecnológicas del Pacífico, Inc.",
            "Logística Marítima y Carga Transístmica, S.A.",
            "Inversiones y Almacenes Comerciales del Istmo, S.A."
        };
        for (int i = 1; i <= companyNames.length; i++) {
            String compId = "comp_" + i;
            String key = "rec:" + db + ":" + compId;
            String payload = String.format(
                "{\"_recordClass\":\"com.factura.model.CompanyRecord\",\"_table\":\"companies\",\"_timestamp\":%d," +
                "\"id\":\"%s\",\"ruc\":\"155689%03d-2-2024\",\"name\":\"%s\",\"taxId\":\"PA-RUC-155689%03d\"," +
                "\"country\":\"PA\",\"currency\":\"USD\",\"email\":\"billing%d@facturatech.com\",\"phone\":\"+507 300-%04d\",\"active\":true}",
                now, compId, i * 37, companyNames[i - 1], i * 37, i, 8000 + i
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;
        }

        // 2. VENDEDORES: RECORDS Engine (100 sales reps)
        String[] sellerFirst = {"Carlos", "Sofia", "Alejandro", "Elena", "David", "Mariana", "Roberto", "Valentina", "Fernando", "Lucia"};
        String[] sellerLast = {"Morales", "Castillo", "Vargas", "Herrera", "Navarro", "Reyes", "Jimenez", "Paredes", "Salazar", "Mendoza"};
        for (int i = 1; i <= 100; i++) {
            String sellerId = "seller_" + i;
            String fullName = sellerFirst[(i - 1) % sellerFirst.length] + " " + sellerLast[(i - 1) / 10];
            String recKey = "rec:" + db + ":" + sellerId;
            String payload = String.format(
                "{\"_recordClass\":\"com.factura.model.SellerRecord\",\"_table\":\"sellers\",\"_timestamp\":%d," +
                "\"id\":\"%s\",\"code\":\"VEND-%03d\",\"name\":\"%s\",\"email\":\"seller%d@facturatech.com\",\"phone\":\"+507 6%07d\"," +
                "\"branchRef\":\"jref://GEOSPATIAL:ExampleFactura/sucursal_%d\",\"companyRef\":\"jref://RECORDS:ExampleFactura/comp_%d\"," +
                "\"commissionPercent\":3.5,\"status\":\"ACTIVE\"}",
                now, sellerId, i, fullName, i, 2000000 + i, ((i - 1) % 20) + 1, ((i - 1) % 5) + 1
            );
            batch.add(new AbstractMap.SimpleEntry<>(recKey, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;
        }

        // 3. PRODUCTOS: RECORDS Engine (799,895 records -> Total RECORDS: 800,000)
        for (int i = 1; i <= prodCount; i++) {
            String prodId = "prod_" + i;
            String key = "rec:" + db + ":" + prodId;
            double unitPrice = 15.0 + ((i % 500) * 4.25);
            double costPrice = unitPrice * 0.65;
            int groupId = (i % 50) + 1;
            int compId = (i % 5) + 1;

            String payload = String.format(Locale.US,
                "{\"_recordClass\":\"com.factura.model.ProductRecord\",\"_table\":\"products\",\"_timestamp\":%d," +
                "\"id\":\"%s\",\"sku\":\"SKU-%06d\",\"barcode\":\"7841%09d\",\"description\":\"Producto Comercial Modelo Pro #%d\"," +
                "\"unitPrice\":%.2f,\"costPrice\":%.2f,\"taxRate\":7.00,\"groupRef\":\"jref://GRAPH:ExampleFactura/group_%d\"," +
                "\"companyRef\":\"jref://RECORDS:ExampleFactura/comp_%d\",\"active\":true}",
                now, prodId, i, i, i, unitPrice, costPrice, groupId, compId
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 4. CLIENTES: DOCUMENT Engine (600,000 documents)
        String[] clientTypes = {"CORPORATE", "ENTERPRISE", "RETAIL", "GOVERNMENT", "SMB"};
        String[] terms = {"30_DAYS", "15_DAYS", "60_DAYS", "CASH_ON_DELIVERY", "IMMEDIATE"};
        String[] cities = {"Panamá", "Colón", "David", "Santiago", "Chitré", "Penonomé", "La Chorrera"};

        for (int i = 1; i <= custCount; i++) {
            String custId = "cust_" + i;
            String key = "doc:" + db + ":" + custId;
            String type = clientTypes[i % clientTypes.length];
            String term = terms[i % terms.length];
            String city = cities[i % cities.length];
            double creditLimit = 5000.0 + ((i % 200) * 500.0);
            int branchId = (i % 20) + 1;
            int sellerId = (i % 100) + 1;

            String payload = String.format(Locale.US,
                "{\"id\":\"%s\",\"taxId\":\"PA-RUC-%08d-DV%02d\",\"name\":\"Cliente Facturacion Empresa %d, S.A.\"," +
                "\"customerType\":\"%s\",\"creditLimit\":%.2f,\"paymentTerms\":\"%s\",\"email\":\"pagos%d@empresa%d.com\"," +
                "\"phone\":\"+507 83%05d\",\"city\":\"%s\",\"preferredBranchRef\":\"jref://GEOSPATIAL:ExampleFactura/sucursal_%d\"," +
                "\"sellerRef\":\"jref://RECORDS:ExampleFactura/seller_%d\",\"status\":\"ACTIVE\"}",
                custId, i + 10000000, i % 99, i, type, creditLimit, term, i, i, i % 100000, city, branchId, sellerId
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 5. CONTRATOS Y ACUERDOS COMERCIALES: DOCUMENT Engine (400,000 documents -> Total DOCUMENT: 1,000,000)
        for (int i = 1; i <= contractCount; i++) {
            String contractId = "contract_" + i;
            String key = "doc:" + db + ":" + contractId;
            int custId = ((i - 1) % Math.max(1, custCount)) + 1;
            int sellerId = ((i - 1) % 100) + 1;

            String payload = String.format(Locale.US,
                "{\"contractId\":\"%s\",\"contractNumber\":\"CTR-2026-%06d\",\"customerRef\":\"jref://DOCUMENT:ExampleFactura/cust_%d\"," +
                "\"sellerRef\":\"jref://RECORDS:ExampleFactura/seller_%d\",\"type\":\"SERVICE_AGREEMENT\",\"sla\":\"99.9%%\"," +
                "\"startDate\":\"2026-01-01\",\"endDate\":\"2028-12-31\",\"status\":\"ACTIVE\"}",
                contractId, i, custId, sellerId
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 6. FACTURAS: COLUMN Engine (100,000 invoices)
        for (int i = 1; i <= facCount; i++) {
            String facId = "fac_" + i;
            String key = "col:" + db + ":" + facId;
            int custId = ((i - 1) % Math.max(1, Math.min(custCount, 10000))) + 1;
            int sellerId = ((i - 1) % 100) + 1;
            int branchId = ((i - 1) % 20) + 1;
            int compId = ((i - 1) % 5) + 1;
            double subtotal = 120.0 + ((i % 150) * 45.0);
            double tax = subtotal * 0.07;
            double total = subtotal + tax;

            String payload = String.format(Locale.US,
                "{\"invoiceId\":\"%s\",\"columnFamily\":\"invoices\",\"invoiceNumber\":\"FAC-2026-%06d\"," +
                "\"fiscalDocNumber\":\"B2B-PA-%06d\",\"issueDate\":\"2026-09-23\"," +
                "\"customerRef\":\"jref://DOCUMENT:ExampleFactura/cust_%d\",\"sellerRef\":\"jref://RECORDS:ExampleFactura/seller_%d\"," +
                "\"branchRef\":\"jref://GEOSPATIAL:ExampleFactura/sucursal_%d\",\"companyRef\":\"jref://RECORDS:ExampleFactura/comp_%d\"," +
                "\"subtotal\":%.2f,\"taxAmount\":%.2f,\"totalAmount\":%.2f,\"paymentMethod\":\"CREDIT\",\"status\":\"ISSUED\"," +
                "\"pdfDocumentRef\":\"jref://OBJECT:ExampleFactura/fac_%d.pdf\"}",
                facId, i, i, custId, sellerId, branchId, compId, subtotal, tax, total, ((i - 1) % 500) + 1
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 7. DETALLES DE FACTURAS: COLUMN Engine (700,000 details -> Total COLUMN: 800,000)
        for (int i = 1; i <= detCount; i++) {
            String detId = "det_" + i;
            String key = "col:" + db + ":" + detId;
            int facId = ((i - 1) % Math.max(1, facCount)) + 1;
            int lineItem = ((i - 1) % 7) + 1;
            int prodId = ((i - 1) % Math.max(1, Math.min(prodCount, 5000))) + 1;
            int qty = ((i - 1) % 10) + 1;
            double unitPrice = 25.0 + ((i % 80) * 5.0);
            double lineTotal = qty * unitPrice;

            String payload = String.format(Locale.US,
                "{\"detailId\":\"%s\",\"columnFamily\":\"invoice_details\",\"invoiceRef\":\"jref://COLUMN:ExampleFactura/fac_%d\"," +
                "\"lineItem\":%d,\"productRef\":\"jref://RECORDS:ExampleFactura/prod_%d\",\"quantity\":%d,\"unitPrice\":%.2f," +
                "\"discountPercent\":0.0,\"lineTotal\":%.2f}",
                detId, facId, lineItem, prodId, qty, unitPrice, lineTotal
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 8. INVENTARIOS Y TELEMETRÍA: TIMESERIES Engine (150,000 metrics)
        for (int i = 1; i <= invCount; i++) {
            String invId = "inv_metric_" + i;
            String key = "ts:" + db + ":" + invId;
            int prodId = ((i - 1) % Math.max(1, Math.min(prodCount, 5000))) + 1;
            int branchId = ((i - 1) % 20) + 1;
            int qty = 100 + ((i * 13) % 850);

            String payload = String.format(
                "{\"metric\":\"inventory_stock_balance\",\"id\":\"%s\",\"productRef\":\"jref://RECORDS:ExampleFactura/prod_%d\"," +
                "\"branchRef\":\"jref://GEOSPATIAL:ExampleFactura/sucursal_%d\",\"quantityOnHand\":%d,\"reorderLevel\":50," +
                "\"lastRestockDate\":\"2026-09-20\",\"status\":\"OPTIMAL\",\"timestamp\":%d}",
                invId, prodId, branchId, qty, now
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 9. SESIONES Y TOKENS DE CAJA: KEYVALUE Engine (100,000 session tokens)
        for (int i = 1; i <= kvCount; i++) {
            String sessId = "session_" + i;
            String key = "kv:" + db + ":" + sessId;
            String payload = String.format(
                "{\"sessionId\":\"%s\",\"terminalId\":\"POS-%04d\",\"cashier\":\"cashier_%d\",\"loginTs\":%d,\"auth\":true}",
                sessId, (i % 250) + 1, (i % 100) + 1, now - (i * 1000L)
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 10. SUCURSALES Y PUNTOS DE ENTREGA: GEOSPATIAL Engine (50,000 GPS coordinates)
        for (int i = 1; i <= geoCount; i++) {
            String branchId = "sucursal_" + i;
            String key = "geo:" + db + ":" + branchId;
            double lat = 8.5000 + ((i % 1000) * 0.001);
            double lon = -80.0000 - ((i % 1000) * 0.002);
            String payload = String.format(Locale.US,
                "{\"id\":\"%s\",\"name\":\"Punto de Entrega Logístico #%d\",\"code\":\"SUC-%05d\",\"lat\":%.4f,\"lon\":%.4f," +
                "\"address\":\"Zona de Distribución Muelle %d, Panamá\",\"companyRef\":\"jref://RECORDS:ExampleFactura/comp_%d\",\"status\":\"OPERATIONAL\"}",
                branchId, i, i, lat, lon, (i % 50) + 1, ((i - 1) % 5) + 1
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 11. TAXONOMÍA Y CATEGORÍAS: GRAPH Engine (50,000 graph nodes and edges)
        String[] groupBase = {"Hardware", "Servidores", "Almacenamiento", "Redes", "Software", "Seguridad", "Servicios", "Insumos", "Periféricos", "Mobiliario"};
        for (int i = 1; i <= graphCount; i++) {
            String groupId = "group_" + i;
            String parentId = (i <= 10) ? "root_catalog" : "group_" + (((i - 1) % 10) + 1);
            String groupName = groupBase[(i - 1) % groupBase.length] + " Categoria #" + i;
            String key = "graph:" + db + ":" + groupId;
            String payload = String.format(
                "{\"nodeId\":\"%s\",\"name\":\"%s\",\"code\":\"GRP-%05d\",\"label\":\"ProductCategory\",\"type\":\"CATEGORY_NODE\"," +
                "\"parentId\":\"%s\",\"edges\":[{\"target\":\"%s\",\"relationship\":\"SUB_CATEGORY_OF\"}],\"status\":\"ACTIVE\"}",
                groupId, groupName, i, parentId, parentId
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 12. FACTURAS DIGITALES Y CERTIFICADOS BLOB: OBJECT Engine (25,000 PDF artifacts)
        for (int i = 1; i <= objCount; i++) {
            String objId = "fac_" + i + ".pdf";
            String key = "obj:" + db + ":" + objId;
            String payload = String.format(
                "{\"bucket\":\"fiscal_invoices\",\"fileName\":\"Factura_Fiscal_FAC-2026-%06d.pdf\",\"mimeType\":\"application/pdf\"," +
                "\"sizeBytes\":%d,\"invoiceRef\":\"jref://COLUMN:ExampleFactura/fac_%d\",\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852%04d\"}",
                i, 120000 + (i * 256), i, i * 13
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        // 13. BÚSQUEDA SEMÁNTICA / RECOMENDACIONES: VECTOR Engine (25,000 embeddings)
        for (int i = 1; i <= vecCount; i++) {
            String vecId = "vec_prod_" + i;
            String key = "vec:" + db + ":" + vecId;
            float c1 = (float) ((i * 17 % 100) / 100.0);
            float c2 = (float) ((i * 31 % 100) / 100.0);
            float c3 = (float) ((i * 47 % 100) / 100.0);
            float c4 = (float) ((i * 61 % 100) / 100.0);

            String payload = String.format(Locale.US,
                "{\"id\":\"%s\",\"label\":\"VectorSemantic_Product_%d\",\"productRef\":\"jref://RECORDS:ExampleFactura/prod_%d\"," +
                "\"dimensions\":4,\"coordinates\":[%.2f, %.2f, %.2f, %.2f],\"metric\":\"COSINE\"}",
                vecId, i, i, c1, c2, c3, c4
            );
            batch.add(new AbstractMap.SimpleEntry<>(key, payload.getBytes(StandardCharsets.UTF_8)));
            totalInserted++;

            if (batch.size() >= batchLimit) {
                engine.getStorageCore().putBatch(db, batch, now);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.getStorageCore().putBatch(db, batch, now);
            batch.clear();
        }

        return totalInserted;
    }
}
