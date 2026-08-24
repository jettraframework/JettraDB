package com.jettra.store.engine.core.abstractions;

import java.util.Map;

/**
 * Nivel 3: Registro / Fila / Ítem.
 * Representa la unidad mínima atómica de almacenamiento 
 * (Documento, Par Clave-Valor, Fila, Nodo, Vector, etc.).
 */
public interface StorageItem<ID, V> {
    ID getId();
    void setId(ID id);
    
    V getValue();
    void setValue(V value);
    
    Map<String, Object> getMetadata();
    void setMetadata(Map<String, Object> metadata);
}
