package com.jettra.store.engine.models.document;

import com.jettra.store.engine.core.abstractions.StorageContainer;
import com.jettra.store.engine.models.DocumentEngine;
import java.util.List;
import java.util.ArrayList;

public class DocumentDatabase implements StorageContainer<DocumentCollection> {
    private final String name;
    private final DocumentEngine engine;

    public DocumentDatabase(String name, DocumentEngine engine) {
        this.name = name;
        this.engine = engine;
    }

    public DocumentEngine getEngine() { return engine; }

    @Override
    public String getName() { return name; }

    @Override
    public DocumentCollection getUnit(String name) {
        return new DocumentCollection(name, this);
    }

    @Override
    public DocumentCollection createUnit(String name) {
        return new DocumentCollection(name, this);
    }

    @Override
    public void dropUnit(String name) {
        // Find and delete all items in the collection
        DocumentCollection col = getUnit(name);
        col.clear();
    }

    @Override
    public List<String> listUnitNames() {
        // Needs engine support to list collections precisely, returning default for now
        List<String> list = new ArrayList<>();
        list.add("default");
        return list;
    }
}
