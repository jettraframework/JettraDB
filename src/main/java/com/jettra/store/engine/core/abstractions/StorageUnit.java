package com.jettra.store.engine.core.abstractions;

import java.util.List;
import java.util.Optional;

/**
 * Nivel 2: Tabla / Agrupación.
 * Representa una agrupación lógica de ítems 
 * (Colección, Bucket, Familia de Columnas, Etiqueta, Índice Vectorial).
 */
public interface StorageUnit<I extends StorageItem<?, ?>> {
    String getName();
    
    void insert(I item);
    void update(I item);
    void delete(Object id);
    
    Optional<I> findById(Object id);
    List<I> findAll();
    
    long count();
    void clear();
}
