# Conceptos Descartados y No Implementados en JettraStoreEngine

Este documento analiza en detalle los conceptos, componentes y arquitecturas presentes en la documentación del proyecto anterior (`JettraStoreEngine/guide/old/`), explicando las razones técnicas y de diseño por las cuales **no fueron implementados** o fueron **descartados** en la nueva arquitectura de **JettraStoreEngine** (Java 25).

---

## Matriz Resumen de Conceptos Descartados

| Concepto / Módulo Anterior | Archivo de Origen (`guide/old/`) | Estado en JettraStoreEngine | Motivo del Descarte / Reemplazo Técnico |
| :--- | :--- | :--- | :--- |
| **Placement Driver (PD) Standalone** | `architecture.md`, `monitoring.md` | **Descartado** | Reemplazado por consenso Raft embebido y orquestador autónomo in-process. |
| **Interfaz Web Vaadin 24 / Quarkus** | `web.md`, `jettra-ui.md` | **Descartado** | Reemplazado por **JettraFlux** nativo en Java 25 sin dependencias externas pesadas. |
| **Files Engine (`jettra-engine-files`)** | `engines.md` | **Descartado** | La gestión de archivos/blobs binarios es gestionada nativamente por el **OBJECT Engine**. |
| **Plugin NetBeans (`plugins/jettra-netbeans`)** | `plugins/netbeans.md` | **Descartado** | Reemplazado por Web Console universal (`JettraFlux`), REST APIs y Swagger OpenAPI. |
| **Coordinador 2PC Standalone (`jettra-tx`)** | `transactions.md`, `auditing.md` | **Descartado** | Reemplazado por transacciones multi-modelo locales y consenso Raft distribuido. |
| **Reactividad Compleja (SmallRye Mutiny)** | `repository.md`, `monitoring.md` | **Descartado** | Reemplazado por **Virtual Threads de Java 25 (Project Loom)**. |
| **Proxy Gateway SQL-to-Mongo** | `web.md` | **Descartado** | Reemplazado por contratos directos REST/gRPC universales para cada motor. |
| **Daemons Externos de Logging/Métricas** | `auditing.md`, `monitoring.md` | **Descartado** | Telemetría, métricas JVM y estado de almacenamiento integrados en `/components`. |

---

## Análisis Detallado de Conceptos Descartados

### 1. Placement Driver (PD) como Microservicio Independiente
- **Documento Antiguo**: [`architecture.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/architecture.md), [`monitoring.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/monitoring.md)
- **Concepto Original**: Un servicio centralizado externo similar a TiDB PD o etcd que gestionaba los latidos de red (*heartbeats*), la topología de nodos y el enrutamiento de sharding.
- **Razón del Descarte**:
  1. Rompía el principio de **cero dependencias externas y despliegue autónomo**.
  2. Introducía un punto único de falla (SPOF) y latencia adicional por salto de red.
- **Solución en JettraStoreEngine**: Cada nodo de `JettraStoreEngine` incorpora su propio servidor/cliente de consenso Raft (`JettraConsensusServer`), permitiendo a los nodos formar quórum directamente y descubrirse mediante la propiedad `jettra.cluster.peers`.

---

### 2. Panel Web en Vaadin 24 / Quarkus (`jettra-web-vaadin`)
- **Documento Antiguo**: [`web.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/web.md), [`jettra-ui.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/jettra-ui.md)
- **Concepto Original**: Una interfaz gráfica construida con el framework Vaadin 24 / Quarkus corriendo en un puerto separado (`8082`), requiriendo empaquetado de servlets y dependencias web masivas.
- **Razón del Descarte**:
  1. Huella de memoria RAM excesiva y tiempos de arranque lentos.
  2. Complejidad en la integración con el motor de almacenamiento nativo de Java 25.
- **Solución en JettraStoreEngine**: Se implementó **JettraFlux** (`io.jettra.flux.*`), un microframework web puramente funcional en Java 25 que compila vistas HTML5 dinámicas sobre `HttpServer` embebido (puerto `50050`), con tiempo de inicio inferior a 50 ms y consumo casi nulo de memoria.

---

### 3. Files Engine (`jettra-engine-files`)
- **Documento Antiguo**: [`engines.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/engines.md)
- **Concepto Original**: Un noveno motor de base de datos diseñado para tratar el sistema de archivos del sistema operativo directamente como tablas de base de datos.
- **Razón del Descarte**:
  1. Conflictos de permisos de sistema de archivos y problemas de consistencia ACID al replicar mediante Raft.
  2. Redundancia funcional con el motor de objetos binarios.
- **Solución en JettraStoreEngine**: El motor **OBJECT Engine (`ObjectEngine.java`)** cubre de forma completa y estandarizada la persistencia de archivos, imágenes, PDFs y payloads serializados organizados en *buckets*, con control de sumas de verificación (*checksums*), tipos MIME y replicación Raft.

---

### 4. Plugin para NetBeans IDE (`plugins/jettra-netbeans`)
- **Documento Antiguo**: [`plugins/netbeans.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/plugins/netbeans.md)
- **Concepto Original**: Módulos `.nbm` para NetBeans (`jettra-core.nbm`, `jettra-db-admin.nbm`, etc.) que requerían parches en el compilador para deshabilitar advertencias del Security Manager en Java 25.
- **Razón del Descarte**:
  1. El `SecurityManager` fue completamente eliminado en Java 25, haciendo obsoletos los parches.
  2. Mantenimiento costoso y dependencia de un único IDE.
- **Solución en JettraStoreEngine**: Se provee una interfaz web universal multiplataforma (`http://localhost:50050/`), clientes polyglot (Java, Python, Go), consola Swagger OpenAPI (`/swagger-ui`) y endpoints REST/gRPC accesibles desde cualquier entorno de desarrollo (VS Code, IntelliJ IDEA, NetBeans, Neovim, terminal).

---

### 5. Microservicio de Transacciones Distribuidas 2PC (`jettra-tx`)
- **Documento Antiguo**: [`transactions.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/transactions.md), [`auditing.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/auditing.md)
- **Concepto Original**: Un proceso independiente (`jettra-tx`) dedicado a coordinar transacciones Two-Phase Commit (2PC) bloqueando recursos mediante sockets externos.
- **Razón del Descarte**:
  1. Los coordinadores 2PC externos introducen cuellos de botella de latencia y riesgo de bloqueos indefinidos (*blocking protocol*).
- **Solución en JettraStoreEngine**: Las transacciones multi-modelo se ejecutan atómicamente a nivel de nodo mediante la MemTable in-memory y el Write-Ahead Log versionado (`@timestamp`), mientras que la consistencia entre réplicas se garantiza mediante el algoritmo Raft Quorum nativo.

---

### 6. Librerías Reactivas Complejas (SmallRye Mutiny / RxJava)
- **Documento Antiguo**: [`repository.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/repository.md), [`monitoring.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/monitoring.md)
- **Concepto Original**: Métodos de repositorio y clientes forzados a retornar `Uni<T>` o `Multi<T>`.
- **Razón del Descarte**:
  1. Con la llegada de **Virtual Threads (Project Loom)** en Java 25, la programación reactiva orientada a no bloquear hilos del SO se vuelve innecesariamente compleja.
- **Solución en JettraStoreEngine**: Código sincrónico claro y directo (`client.get(...)`, `repository.save(...)`), donde cada petición corre en un Virtual Thread ligero gestionado por la JVM, alcanzando millones de operaciones concurrentes sin la complejidad de `Uni` / `Multi`.

---

### 7. Consola y Gateway de Traducción SQL-to-Mongo
- **Documento Antiguo**: [`web.md`](file:///home/avbravo/NetBeansProjects/jettrastack_local/JettraWorkspace/JettraStoreEngine/guide/old/web.md)
- **Concepto Original**: Un módulo proxy intermedio que parseaba consultas SQL arbitrarias y las traducía a llamadas internas.
- **Razón del Descarte**:
  1. Dificultad para mapear sintaxis SQL relacional hacia modelos disímiles como Vector, Graph o TimeSeries.
- **Solución en JettraStoreEngine**: Cada uno de los 8 motores expone sus endpoints REST y gRPC optimizados y fuertemente tipados, acompañados por la interfaz web tipificada en `/engines`.

---

## Resumen de la Evolución Arquitectónica

| Característica | Arquitectura Antigua | JettraStoreEngine (Actual) |
| :--- | :--- | :--- |
| **Runtime** | Microservicios distribuidos (PD + Web + TX + Store) | **Proceso Único Autónomo (Pure Java 25)** |
| **Concurrencia** | Thread pools clásicos + Mutiny Reactive | **Virtual Threads (Project Loom)** |
| **Memoria** | Objetos estándar JVM (Cabeceras 96-128 bits) | **Compact Object Headers (JEP 450)** |
| **Almacenamiento** | Módulos disjuntos por motor | **LSM-Tree + B-Tree Hybrid Unificado** |
| **Interfaz Web** | Vaadin / Flowbite / Quarkus | **JettraFlux (Zero Overhead Functional UI)** |
| **Gestión de Objetos** | JSON genérico para todos los motores | **Formularios e Inspectores Específicos por Tipo** |
