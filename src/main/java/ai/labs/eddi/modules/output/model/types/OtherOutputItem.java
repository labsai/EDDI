package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

import java.util.*;

@JsonSchemaInject(json = "{\"additionalProperties\" : true}")
@JsonSchemaTitle("other")
public class OtherOutputItem extends OutputItem implements Map<String, String> {
    private final LinkedHashMap<String, String> internalMap = new LinkedHashMap<>();

    @Override
    protected void initType() {
        super.type = "other";
    }

    @Override
    public int size() {
        return internalMap.size();
    }

    @Override
    public boolean isEmpty() {
        return internalMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return internalMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return internalMap.containsValue(value);
    }

    @Override
    public String get(Object key) {
        return internalMap.get(key);
    }

    @Override
    public String put(String key, String value) {
        return internalMap.put(key, value);
    }

    @Override
    public String remove(Object key) {
        return internalMap.remove(key);
    }

    @Override
    public void putAll(Map m) {
        internalMap.putAll(m);
    }

    @Override
    public void clear() {
        internalMap.clear();
    }

    @Override
    public Set<String> keySet() {
        return internalMap.keySet();
    }

    @Override
    public Collection<String> values() {
        return internalMap.values();
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return internalMap.entrySet();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OtherOutputItem that = (OtherOutputItem) o;
        return Objects.equals(internalMap, that.internalMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(internalMap);
    }
}
