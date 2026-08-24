package com.jettra.store.engine.core.abstractions;

import java.util.List;

/**
 * Interfaz principal que cada Motor de Base de Datos implementará.
 * Proporciona acceso a los contenedores y gestiona su ciclo de vida.
 */
public interface EngineContext<C extends StorageContainer<?>> {
    String getEngineType();
    
    C getContainer(String name);
    C createContainer(String name);
    void dropContainer(String name);
    
    List<String> listContainerNames();
    
    void initialize();
    void shutdown();
}
