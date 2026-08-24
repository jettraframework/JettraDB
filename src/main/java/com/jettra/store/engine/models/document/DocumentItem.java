package com.jettra.store.engine.models.document;

import com.jettra.store.engine.core.abstractions.StorageItem;
import io.jettra.json.JsonObject;
import java.util.HashMap;
import java.util.Map;

public class DocumentItem implements StorageItem<String, JsonObject> {
    private String id;
    private JsonObject value;
    private Map<String, Object> metadata;

    public DocumentItem(String id, JsonObject value) {
        this.id = id;
        this.value = value;
        this.metadata = new HashMap<>();
    }

    @Override
    public String getId() { return id; }
    
    @Override
    public void setId(String id) { this.id = id; }
    
    @Override
    public JsonObject getValue() { return value; }
    
    @Override
    public void setValue(JsonObject value) { this.value = value; }
    
    @Override
    public Map<String, Object> getMetadata() { return metadata; }
    
    @Override
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
