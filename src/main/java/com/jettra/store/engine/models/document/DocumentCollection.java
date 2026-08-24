package com.jettra.store.engine.models.document;

import com.jettra.store.engine.core.abstractions.StorageUnit;
import io.jettra.json.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

public class DocumentCollection implements StorageUnit<DocumentItem> {
    private final String name;
    private final DocumentDatabase database;

    public DocumentCollection(String name, DocumentDatabase database) {
        this.name = name;
        this.database = database;
    }

    @Override
    public String getName() { return name; }

    @Override
    public void insert(DocumentItem item) {
        database.getEngine().insert(database.getName(), name, item.getId(), item.getValue());
    }

    @Override
    public void update(DocumentItem item) {
        insert(item);
    }

    @Override
    public void delete(Object id) {
        database.getEngine().delete(database.getName(), name, id.toString());
    }

    @Override
    public Optional<DocumentItem> findById(Object id) {
        JsonObject obj = database.getEngine().get(database.getName(), name, id.toString());
        if (obj != null) {
            return Optional.of(new DocumentItem(id.toString(), obj));
        }
        return Optional.empty();
    }

    @Override
    public List<DocumentItem> findAll() {
        List<DocumentItem> list = new ArrayList<>();
        java.util.Map<String, JsonObject> map = database.getEngine().list(database.getName(), name);
        for (java.util.Map.Entry<String, JsonObject> entry : map.entrySet()) {
            list.add(new DocumentItem(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    @Override
    public long count() {
        return findAll().size();
    }

    @Override
    public void clear() {
        for (DocumentItem item : findAll()) {
            delete(item.getId());
        }
    }
}
