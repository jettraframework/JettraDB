# Guía del Motor de Alto Rendimiento para JettraDB
### Integración Arquitectónica Inspirada en   y  

---

## 1. Introducción y Objetivos

El objetivo de esta arquitectura de alto rendimiento para **JettraDB** es combinar dos de los paradigmas más avanzados en la gestión moderna de datos en la JVM:

1. **Eliminación de la Desadaptación de Impedancia **:
   - Persistencia directa de grafos de objetos Java nativos a nivel de bytes sin capas pesadas de mapeo objeto-relacional (ORM) ni conversiones redundantes a JSON/BSON.
   - Consultas in-memory ejecutadas mediante la API nativa de **Java Streams** con latencias de CPU de nivel de microsegundos ($\mu s$).
   - Soporte de carga perezosa (*Lazy Loading*) y consolidación incremental de escrituras mediante **micro-snapshots** no bloqueantes.

2. **Control Físico y Acceso Directo a Disco**:
   - Gestión propia de páginas físicas de tamaño fijo (64 KB) sobre archivos binarios dedicados (`.jpage`).
   - Punteros físicos inmutables (**Record IDs / RID**: `#fileId:pageIndex:offset`) para resolución $O(1)$ sin recurrir a costosas búsquedas en árboles B-Tree o tablas hash secundarias.
   - Gestión de memoria fuera del Heap (**Off-Heap Memory**) mediante **Project Panama** (`java.lang.foreign.Arena` y `MemorySegment`) en **Java 25**, eliminando pausas de Garbage Collection (GC) bajo cargas de alta densidad de concurrencia.

---

## 2. Matriz de Verificación de Características

A continuación se detalla la verificación del estado del motor antes y después de la implementación:

| Característica | Estado Previo en JettraDB | Estado Posterior a la Implementación | Componente / Clase Responsable |
| :--- | :--- | :--- | :--- |
| **Identificadores Físicos Directos (RID)** | ❌ No implementado. Solo identificadores alfanuméricos arbitrarios (`String id`). | ✅ **Completamente Implementado**. Puntero inmutable `RecordId(fileId, pageIndex, offset)` con resolución directa $O(1)$. | `com.jettra.store.engine.highperformance.paged.RecordId` |
| **Gestión Propia de Páginas Físicas (64 KB)** | ❌ No implementado. I/O lineal simple en `JettraFileManager` (`append` al final de `data_0.jettra`). | ✅ **Completamente Implementado**. Páginas fijas de 64 KB con cabecera de 32 bytes, CRC32, slots de registros y control de tombstones. | `com.jettra.store.engine.highperformance.paged.Page`<br>`com.jettra.store.engine.highperformance.paged.PagedStorageEngine` |
| **Caché Fuera del Heap (Off-Heap / Panama)** | ❌ No implementado. Asignaciones continuas en Heap con `ByteBuffer.allocate()`. | ✅ **Completamente Implementado**. `OffHeapPageCache` con `Arena.ofShared()` y `MemorySegment` nativo en Java 25. | `com.jettra.store.engine.highperformance.paged.OffHeapPageCache` |
| **Persistencia Directa de Grafos de Objetos** | ⚠️ Rudimentario (`ObjectStorage` con serialización Java estándar o `ObjectEngine` vía JSON). | ✅ **Completamente Implementado**. `ObjectGraphSerializer` optimizado con etiquetas de tipo y serialización binaria directa. | `com.jettra.store.engine.highperformance.graph.ObjectGraphSerializer` |
| **Consultas Nativas con Java Streams** | ❌ No implementado para grafos in-memory (búsquedas por disco o scan string en LSM). | ✅ **Completamente Implementado**. Consultas y filtros nativos in-memory a velocidad de CPU con `Stream<T>`. | `com.jettra.store.engine.highperformance.graph.NativeObjectGraphStore` |
| **Carga Perezosa (Lazy Loading)** | ❌ No implementado. Carga de grafos completos o entidades aisladas. | ✅ **Completamente Implementado**. `LazyReference<T>` resoluble bajo demanda por `RecordId` con capacidad de `evict()`. | `com.jettra.store.engine.highperformance.graph.LazyReference` |
| **Micro-Snapshots Incrementales** | ❌ No implementado. Volcados pesados de WAL o backups globales. | ✅ **Completamente Implementado**. `MicroSnapshotEngine` asíncrono con hilos virtuales de Java 25 (`Virtual Threads`). | `com.jettra.store.engine.highperformance.graph.MicroSnapshotEngine` |
| **Integración con JettraStorageEngine** | ⚠️ Parcial (orquestador multi-modelo estándar). | ✅ **Completamente Integrado**. Motor registrado como `HIGH_PERFORMANCE` en el orquestador principal. | `com.jettra.store.engine.highperformance.integration.HighPerformanceEngineBridge` |

---

## 3. Arquitectura de Bajo Nivel

```
  +-------------------------------------------------------------------------------+
  |                        Aplicación / Servicios JettraDB                       |
  +-------------------------------------------------------------------------------+
         |                                                 |
         | (1) Java Streams & Object Graph                 | (2) Direct Access O(1)
         v                                                 v
  +-------------------------------------+         +-------------------------------+
  |     NativeObjectGraphStore          |         |           RecordId            |
  |  - stream(Type.class, filter)       |         |   #fileId:pageIndex:offset    |
  |  - link(fromNode, toNode)           |         +-------------------------------+
  |  - LazyReference<T>                 |                          |
  +-------------------------------------+                          |
         |                                                         |
         | Direct Binary Serializer                                |
         v                                                         |
  +-------------------------------------+                          |
  |       ObjectGraphSerializer         |                          |
  |   (Zero Impedance Mismatch)         |                          |
  +-------------------------------------+                          |
         |                                                         |
         +----------------------------+----------------------------+
                                      |
                                      v
  +-------------------------------------------------------------------------------+
  |                              PagedStorageEngine                               |
  |   - writeRecord(fileId, bytes) -> RecordId                                    |
  |   - readRecord(RecordId) -> byte[]                                            |
  +-------------------------------------------------------------------------------+
             |                                                 |
             | Hot Pages LRU                                   | Physical Sync
             v                                                 v
  +-------------------------------------+         +-------------------------------+
  |         OffHeapPageCache            |         |         Disk Storage          |
  |   Java 25 Project Panama FFM API    |         |   Fixed 64 KB Page Files      |
  |   Arena.ofShared() + MemorySegment  |         |   bucket_*.jpage              |
  |   (Zero GC Pause Impact)            |         |   snap_*.delta / .meta        |
  +-------------------------------------+         +-------------------------------+
```

---

## 4. Componentes Clave y Detalles de Implementación

### 4.1. RecordId (Puntero Físico Inmutable)
Inspirado en los RIDs de  , representa la ubicación física exacta de un registro:
- **`fileId`** (4 bytes): Identificador del archivo de almacenamiento (bucket/partición).
- **`pageIndex`** (8 bytes): Índice de la página de 64 KB dentro del archivo.
- **`offset`** (4 bytes): Posición en bytes dentro de la página.
- **Formato canónico**: `#fileId:pageIndex:offset` (ej. `#1:0:32`).
- **Huella binaria**: Exactamente 16 bytes, facilitando almacenamiento off-heap y registros de índices en caché.

### 4.2. Estructura de Página Física (64 KB)
Cada página en disco y en memoria posee la siguiente estructura binaria:
- **Cabecera (32 bytes)**:
  - Byte 0: `0x50` ('P', Magic Byte de validación).
  - Byte 1: Versión del formato de página (1).
  - Bytes 2..5: `fileId` (entero de 32 bits).
  - Bytes 6..13: `pageIndex` (entero de 64 bits).
  - Bytes 14..17: `freeOffset` (puntero al espacio libre actual en la página).
  - Bytes 18..21: `recordCount` (número total de registros en la página).
  - Bytes 22..23: `flags` de estado de página.
  - Bytes 24..31: Suma de verificación `CRC32` de 64 bits.
- **Cuerpo de Registros**:
  - `[Length (4 bytes)][Status (2 bytes: 1=Activo, 2=Eliminado/Tombstone)][Payload...]`.

### 4.3. Caché Fuera del Heap con Project Panama (Java 25)
`OffHeapPageCache` utiliza las APIs finales de Java 25 Foreign Function & Memory (`java.lang.foreign`):
```java
// Asignación de memoria nativa contigua fuera del Heap
this.sharedArena = Arena.ofShared();
long totalMemoryBytes = (long) maxCachedPages * Page.PAGE_SIZE;
this.offHeapSegment = sharedArena.allocate(totalMemoryBytes, 8);

// Transferencia directa de bytes entre el array de la página y el segmento nativo
MemorySegment.copy(offHeapSegment, ValueLayout.JAVA_BYTE, offset, pageBytes, 0, Page.PAGE_SIZE);
```
**Ventajas**:
- Los datos de las páginas calientes residen en memoria física administrada por el SO fuera del alcance del Garbage Collector.
- Compatibilidad nativa con los recolectores de baja latencia **ZGC** y **Shenandoah**, manteniendo pausas de GC por debajo de 1 milisegundo incluso con cachés de decenas de gigabytes.
- Al invocar `cache.close()`, la arena libera toda la memoria nativa de inmediato sin esperar a los ciclos de finalización de la JVM.

### 4.4. Persistencia de Grafos y LazyReference ( )
Para erradicar la sobrecarga de reflexión y la desadaptación de impedancia:
- `ObjectGraphSerializer` serializa directamente estructuras nativas en bytes optimizados.
- `LazyReference<T>` encapsula la carga bajo demanda:
```java
LazyReference<Customer> ref = LazyReference.of(recordId, rid -> {
    byte[] data = pagedStorage.readRecord(rid);
    return (Customer) serializer.deserialize(data);
});

// El objeto no reside en el Heap hasta que se accede por primera vez
Customer c = ref.get();

// Posibilidad de liberar memoria en el Heap manteniendo el puntero físico intacto
ref.evict();
```

### 4.5. Micro-Snapshots con Hilos Virtuales de Java 25
`MicroSnapshotEngine` registra los registros modificados (*dirty tracking*) y escribe los deltas en disco de manera incremental:
```java
// Ejecución asíncrona no bloqueante sobre un Virtual Thread de Java 25
CompletableFuture<SnapshotMetadata> future = snapshotEngine.createMicroSnapshotAsync("Checkpoint incremental");
```
Esto garantiza consistencia **ACID** sin detener hilos lectores ni degradar la concurrencia de la base de datos.

---

## 5. Ejemplos de Uso en Java

### 5.1. Inicialización y Acceso al Puente de Alto Rendimiento
```java
JettraStorageEngine engine = new JettraStorageEngine("/var/data/jettra");
engine.start();

// Obtener el puente de alto rendimiento
HighPerformanceEngineBridge hpBridge = engine.getHighPerformanceBridge();
```

### 5.2. Persistencia Directa y Resolución O(1) por RecordId
```java
public record UserAccount(String id, String username, String email, int level) implements Serializable {}

// 1. Guardar objeto nativo en el namespace "accounts"
UserAccount user = new UserAccount("usr_10", "carlos_dev", "carlos@jettra.io", 5);
RecordId rid = hpBridge.persist("accounts", "usr_10", user);
System.out.println("Registro persistido con RID físico: " + rid); // Ejemplo: #101:0:32

// 2. Recuperación directa O(1) vía RID físico (sin búsquedas en árbol)
UserAccount resolved = hpBridge.fetchByRid("accounts", rid);

// 3. Recuperación por clave lógica
UserAccount byId = hpBridge.fetch("accounts", "usr_10");
```

### 5.3. Navegación de Relaciones en Grafo por Punteros Directos
```java
NativeObjectGraphStore graphStore = hpBridge.getGraphStore("social_graph");

// Guardar nodos
RecordId aliceRid = graphStore.store("u_alice", new UserAccount("1", "Alice", "alice@test.com", 10));
RecordId bobRid   = graphStore.store("u_bob",   new UserAccount("2", "Bob", "bob@test.com", 8));

// Enlazar relación directa
graphStore.link("u_alice", "u_bob");

// Navegación directa por punteros sin join ni hash lookup
List<Object> friends = graphStore.getNeighbors("u_alice");
```

### 5.4. Consultas In-Memory con Java Streams
```java
// Filtrado y procesamiento a microsegundos directamente sobre los objetos en memoria
List<UserAccount> topUsers = hpBridge.stream("accounts", UserAccount.class)
    .filter(u -> u.level() >= 5)
    .sorted(Comparator.comparingInt(UserAccount::level).reversed())
    .toList();
```

### 5.5. Consolidación de Micro-Snapshots
```java
// Micro-snapshot síncrono
hpBridge.triggerSnapshot("Snapshot después de procesamiento batch");

// O asíncrono sin bloquear
hpBridge.getSnapshotEngine().createMicroSnapshotAsync("Snapshot en segundo plano")
    .thenAccept(meta -> System.out.println("Snapshot #" + meta.snapshotId() + " consolidado."));
```

---

## 6. Recomendaciones de Configuración y JVM

Para obtener el máximo rendimiento en producción:

1. **Parámetros de JVM (Java 25+)**:
   ```bash
   java --enable-preview \
        -XX:+UseZGC \
        -XX:+ZGenerational \
        -Xms4g -Xmx4g \
        -XX:+AlwaysPreTouch \
        -jar JettraDB.jar
   ```
2. **Dimensionamiento de la Caché Off-Heap**:
   - Por defecto: 1024 páginas de 64 KB = 64 MB.
   - Para cargas de alto volumen: configurar `maxOffHeapPages = 16384` (1 GB) o `65536` (4 GB) según la memoria RAM física disponible en el servidor.
3. **Optimización de GC**:
   - La combinación de estructuras inmutables de tipo `record`, almacenamiento off-heap y `LazyReference` reduce drásticamente las tasas de promoción de objetos jóvenes al *Tenured Space*, eliminando pausas perceptibles en el servicio.
