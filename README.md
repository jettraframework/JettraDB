# JettraStoreEngine

Autonomous, high-density, multi-model storage engine engineered natively in **Java 25** with Compact Object Headers, Generational ZGC, and Virtual Threads.

`JettraStoreEngine` unifies **9 distinct database models** over a single resilient storage core combining Log-Structured Merge Trees (LSM), B-Trees, and Raft consensus log replication.

## Architecture Overview

```
                      JettraStoreEngine (Port 8086 / 50050 / 50051)
   ┌─────────────────────────────────────────────────────────────────────────┐
   │ 1. RECORDS    : Java 25 Immutable Records & Schema Reflection           │
   │ 2. DOCUMENT   : NoSQL JSON / BSON Trees                                 │
   │ 3. VECTOR     : AI ANN Embeddings & Cosine Similarity                   │
   │ 4. GRAPH      : LPG Nodes, Edges & Deep Traversal                       │
   │ 5. TIMESERIES : High-Frequency IoT Telemetry & Downsampling             │
   │ 6. COLUMN     : OLAP Columnar Projections & Vectorized Scans            │
   │ 7. KEYVALUE   : Atomic In-Memory MemTable Cache                         │
   │ 8. GEOSPATIAL : 2D GIS Coordinates & Haversine Distance                 │
   │ 9. OBJECT     : Chunked Binary BLOBs & Media Streams                    │
   └─────────────────────────────────────────────────────────────────────────┘
                                      │
                   Hybrid LSM-Tree / B-Tree Storage Core
                                      │
                   Raft Quorum Consensus & Distributed WAL
```

## Quick Start

### Build & Run
```bash
mvn clean package -DskipTests
java --enable-preview -jar target/JettraStoreEngine-1.0-SNAPSHOT.jar
```

- **REST API Port**: `8086` (`http://localhost:8086`)
- **JettraFlux Web Management Console**: `50050` (`http://localhost:50050/wui`)
- **Raft Consensus & gRPC Port**: `50051`

---

## Records Engine (`RECORDS`)

The **Records Engine** provides native first-class storage and schema introspection for Java 25 Records (`java.lang.Record`).

### Key Format
`rec:{collection}:{recordId}`

### Payload Structure
```json
{
  "_recordClass": "com.enterprise.model.EmployeeRecord",
  "_timestamp": 1755735492000,
  "_version": 1,
  "_schema": {
    "id": "String",
    "fullName": "String",
    "department": "String",
    "salary": "Double",
    "active": "Boolean"
  },
  "components": {
    "id": "EMP-001",
    "fullName": "Carlos Mendez",
    "department": "Engineering",
    "salary": 95000.0,
    "active": true
  }
}
```

### REST Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/model/records/{collection}/{id}` | Store or update a Java record |
| `GET` | `/api/model/records/{collection}/{id}` | Retrieve full record payload |
| `GET` | `/api/model/records/{collection}/{id}?fields=a,b` | Field projection (returns selected components) |
| `DELETE`| `/api/model/records/{collection}/{id}` | Delete record across cluster |

### cURL Examples

```bash
# 1. Store Record
curl -X POST http://localhost:8086/api/model/records/employees/EMP-001 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "_recordClass": "com.enterprise.model.EmployeeRecord",
    "components": {
      "id": "EMP-001",
      "fullName": "Carlos Mendez",
      "department": "Engineering",
      "salary": 95000.0,
      "active": true
    }
  }'

# 2. Retrieve Full Record
curl -X GET http://localhost:8086/api/model/records/employees/EMP-001 \
  -H "Authorization: Bearer $TOKEN"

# 3. Field Projection Query
curl -X GET "http://localhost:8086/api/model/records/employees/EMP-001?fields=fullName,salary" \
  -H "Authorization: Bearer $TOKEN"

# 4. Delete Record
curl -X DELETE http://localhost:8086/api/model/records/employees/EMP-001 \
  -H "Authorization: Bearer $TOKEN"
```

---

## Detailed Documentation

Comprehensive architectural book and guides:
- [JettraStoreEngine Architectural Book](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/book.md)
- [JettraStoreShell Guide](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreShell/guide/shell.md)
- [Java Driver Guide](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreDriverJava/README.md)
- [Go Driver Guide](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreDriverGo/README.md)
- [Python Driver Guide](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreDriverPython/README.md)
