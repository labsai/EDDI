package ai.labs.eddi.engine.memory.model;

import java.util.LinkedHashMap;

public class ConversationOutput extends LinkedHashMap<String, Object> {
    public <T> T get(Object key, Class<T> clazz) {
        return (T) super.get(key);
    }
}
