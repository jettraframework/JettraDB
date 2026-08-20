# JettraStorageEngine - La Guía Definitiva

Bienvenido a la documentación oficial de **JettraStorageEngine**, el motor de base de datos multimodelo, nativo para la nube y de próxima generación, construido nativamente sobre el ecosistema JettraStack.

## Tabla de Contenidos
1. [Introducción y Arquitectura](#1-introducción-y-arquitectura)
2. [Configuración de Clúster Raft (Alta Disponibilidad)](#2-configuración-de-clúster-raft-alta-disponibilidad)
3. [Compilación y Ejecución](#3-compilación-y-ejecución)
4. [Ejemplos de Uso de Drivers (Java, Python, Golang)](#4-ejemplos-de-uso-de-drivers)
5. [Ejemplos Exhaustivos del Motor Multimodelo](#5-ejemplos-exhaustivos-del-motor-multimodelo)
6. [Agregaciones y Analítica](#6-agregaciones-y-analítica)
7. [Transacciones Distribuidas y Rollback](#7-transacciones-distribuidas-y-rollback)
8. [Seguridad y Administración de Usuarios](#8-seguridad-y-administración-de-usuarios)

---

## 1. Introducción y Arquitectura

JettraStorageEngine es una base de datos ultraligera y altamente optimizada, construida en Java 25. Utiliza **Java Compact Headers** y el recolector de basura **ZGC (Z Garbage Collector)** para ofrecer la máxima eficiencia de memoria.

### Motor de Almacenamiento Híbrido LSM + B-Tree
Para lograr velocidades de lectura/escritura inigualables, el motor central utiliza una arquitectura híbrida:
- **LSM (Log-Structured Merge-tree)**: Absorbe cargas de escritura masivas en memoria (MemTables).
- **B-Tree**: Una vez que la memoria se vacía al disco, los datos se indexan en bloques B-Tree altamente optimizados, asegurando búsquedas rápidas, paginación y escaneos por rango.

---

## 2. Configuración de Clúster Raft (Alta Disponibilidad)

JettraStoreEngine garantiza resiliencia y alta disponibilidad agrupando los nodos en un clúster manejado mediante el algoritmo de consenso **Raft (Apache Ratis)**.

### Configuración del Nodo Primario y Secundarios

Para configurar un clúster, debes declarar las direcciones de los pares (peers) en el archivo `src/main/resources/jettrastorengine.properties` o pasarlo como variable de entorno.

**Ejemplo para el Nodo 1 (jettrastoreengine.properties):**
```properties
jettra.node.id=node1
jettra.data.dir=/data/node1
jettra.gui.port=50050
jettra.node.port=8080
jettra.grpc.port=50051
jettra.cluster.peers=192.168.1.100:50051,192.168.1.101:50051,192.168.1.102:50051
```

**Ejecución:**
```bash
java -XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-preview -jar target/JettraStoreEngine-1.0-SNAPSHOT.jar
```

---

## 3. Compilación y Ejecución

Para compilar y empaquetar el motor desde el código fuente:
```bash
mvn clean package -DskipTests

o

mvn clean verify
```

Para correr de forma local (Standalone):
```bash
java -XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-preview -jar target/JettraStoreEngine-1.0-SNAPSHOT.jar
```
*El usuario por defecto de la administración REST es `admin/admin`.*

---

## 4. Ejemplos de Uso de Drivers (Java, Python, Golang)

JettraStoreEngine soporta integración nativa mediante drivers para múltiples lenguajes. Destacan el uso del patrón **Repository** y el uso del **Fluent API** en Java.

### Driver Java (`JettraStoreDriverJava`) - Patrón Repository y Fluent API

El driver Java soporta un patrón Repository nativo que mapea los tipos como `Java Records` de forma transparente. Adicionalmente, cuenta con una Fluent API para consultas dinámicas complejas.

```java
import com.jettra.driver.java.JettraClient;
import com.jettra.driver.java.JettraRepository;
import java.util.Optional;

public record Planet(String id, String name, double mass, boolean hasRings) {}

public class RepositoryExample {
    public static void main(String[] args) {
        JettraClient client = new JettraClient("127.0.0.1", 8080);
        client.connect();
        
        try {
            if (client.login("admin", "admin")) {
                // 1. Uso de JettraRepository
                JettraRepository<Planet> planetRepo = client.repository(Planet.class, "DOCUMENT", "planets");
                
                Planet mars = new Planet("p_001", "Mars", 0.107, false);
                planetRepo.save(mars.id(), mars); // Se autogestionan las transacciones subyacentes
                
                // 2. Uso de Fluent API para consultas avanzadas
                client.document().collection("planets")
                      .find()
                      .where("hasRings").eq(true)
                      .and("mass").gt(100.0)
                      .limit(10)
                      .execute()
                      .forEach(doc -> System.out.println("Planeta: " + doc.get("name")));
            }
        } finally {
            client.close();
        }
    }
}
```

> [!NOTE]
> Puedes consultar el archivo `example.md` para ver ejemplos detallados en Python y Golang, así como un caso de uso completo de un CRUD.

---

## 5. Ejemplos Exhaustivos del Motor Multimodelo

JettraStoreEngine soporta **8 modelos de datos** distintos sobre su misma arquitectura base.

### 5.1. Motor de Documentos (Document)
```java
client.insertModel("document", "users", "usr_1", "{\"_class\":\"User\", \"name\":\"Bob\"}");
```

### 5.2. Motor Vectorial (Vector)
```java
client.insertModel("vector", "ai_docs", "vec_1", "{\"vector\": [0.2, 0.5, 0.8]}");
```

### 5.3. Motor de Grafos (Graph)
```java
client.insertModel("graph", "social", "node_alice", "{\"name\":\"Alice\", \"age\":30}");
```

### 5.4. Motor Clave-Valor (Key-Value)
```java
client.insertModel("keyvalue", "cache", "session_123", "token_abc_xyz");
```

### 5.5. Motor de Series de Tiempo (Time-Series)
```java
client.insertModel("timeseries", "cpu_usage", "ts_1", "{\"cpu\": 45.5}");
```

### 5.6. Motor Columnar (Column)
```java
client.insertModel("column", "metrics_cf", "row_a", "{\"cf1:col1\":\"val1\"}");
```

### 5.7. Motor Geoespacial (Geospatial)
```java
client.insertModel("geospatial", "places", "poi_1", "{\"lat\":40.71, \"lon\":-74.00, \"name\":\"NY\"}");
```

### 5.8. Motor de Objetos (Object)
```java
client.insertModel("object", "states", "obj_1", "{\"score\":100, \"level\":5}");
```

---

## 6. Agregaciones y Analítica

JettraStoreEngine soporta potentes flujos de agregación (aggregation pipelines) para analítica en tiempo real sobre todos los motores. Esto es accesible a través del driver Java, REST y el Shell.

**Operadores soportados:**
- `$match`: Filtra documentos basados en condiciones.
- `$group`: Agrupa por un identificador.
- `$sum`, `$avg`, `$min`, `$max`: Operadores numéricos.
- `$count`: Cuenta el número de documentos.

**Ejemplo en Java (Fluent API y Repository):**
```java
// 1. High-Level Aggregation con Repository
Double avgMass = planetRepo.avg("mass", "{hasRings: true}").await().indefinitely();

// 2. Generic Pipeline
String pipeline = "[" +
    "{\"$match\": {\"hasRings\": true}}," +
    "{\"$group\": {\"_id\": null, \"totalMass\": {\"$sum\": \"$mass\"}}}" +
"]";

List<Object> results = client.aggregate("planets", pipeline).await().indefinitely();
```

---

## 7. Transacciones Distribuidas y Rollback

JettraStoreEngine garantiza la coherencia a través de múltiples fragmentos (Raft Groups) mediante el protocolo de **Commit en Dos Fases (2PC)**.

> [!IMPORTANT]
> A diferencia de las bases de datos NoSQL tradicionales, JettraStoreEngine soporta **Transacciones ACID completas** incluso en entornos distribuidos.

**El Coordinador de Transacciones (TC)** maneja el ciclo de vida:
1. **Fase Prepare**: Inicia e instruye a los grupos a preparar. Si algún nodo no responde, se lanza abort.
2. **Fase Commit / Rollback**: Si hay consenso, se aplican los cambios. En caso de error, el motor emite automáticamente un **Rollback (GLOBAL_ABORT)** liberando los locks.

**Ejemplo en Java (Uso de Transacciones Explícitas):**
```java
TransactionCoordinator tc = client.getTransactionCoordinator();

String txId = tc.begin();
try {
    // Estas operaciones se enmarcan en la misma transacción lógica
    client.document().collection("accounts").update(txId, "A", "{balance: 500}");
    client.document().collection("accounts").update(txId, "B", "{balance: 1500}");
    
    // Preparar en nodos involucrados
    boolean prepared = tc.prepare(txId, List.of(groupA, groupB));
    
    if (prepared) {
        tc.commit(txId);
        System.out.println("Transferencia exitosa.");
    } else {
        tc.abort(txId); // Rollback
        System.out.println("Rollback de la transferencia.");
    }
} catch (Exception e) {
    tc.abort(txId); // Rollback automático en caso de falla de red o error de negocio
}
```

---

## 8. Seguridad y Administración de Usuarios

El control de acceso en JettraStoreEngine se gestiona a través de autenticación mediante JWT y autorización basada en roles (RBAC).

**Ejemplo de flujo cURL:**
```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin", "password":"admin"}' | jq -r .token)

curl -X POST http://127.0.0.1:8080/api/document/users/u1 \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"name":"Alice"}'
```

---
*Desarrollado con JettraStack. Diseñado para rendimiento extremo y flexibilidad ilimitada.*


## JettraStoreEngine Web Administration UI
JettraStoreEngine now features a built-in futuristic Web User Interface accessible at `http://<direccion>:<puerto>/wui` (using the `jettra.node.port`). This interface provides a powerful, aesthetic dashboard (defaulting to the Astro-inspired `BlackStart` theme) to manage the engine.
Capabilities include:
- **Database Management**: View and manage all database models (Document, Vector, Graph, TimeSeries, Column, KeyValue, Geospatial, Object).
- **User and Privilege Administration**: Easily manage users, roles, and privileges.
- **Cluster Node Monitoring**: View connected nodes, active node status (Master), and network health. (Note: Only the Master node can execute insertions, edits, deletions, stop nodes, or add new nodes).
- **Resource Consumption**: Monitor real-time RAM and Disk consumption, including disk space occupied per database.
- **Backup and Restore**: Perform backups and trigger restores directly from the UI.
- **Rule and Trigger Management**: Support for JettraStore rules and history of database changes.
