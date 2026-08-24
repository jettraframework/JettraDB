package com.jettra.store.engine.core.abstractions;

import java.util.List;

/**
 * Nivel 1: Base de Datos / Catálogo / Tenant.
 * Representa el contenedor superior que agrupa múltiples unidades lógicas.
 * (Database, Keyspace, Graph Database, Vector Catalog, Object Store).
 */
public interface StorageContainer<U extends StorageUnit<?>> {
    String getName();
    
    U getUnit(String name);
    U createUnit(String name);
    void dropUnit(String name);
    
    List<String> listUnitNames();
}
