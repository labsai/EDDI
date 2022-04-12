package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.engine.lifecycle.IComponentCache;

import javax.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ComponentCache implements IComponentCache {
    private final Map<String, Map<String, Object>> componentMaps = new HashMap<>();

    @Override
    public Map<String, Object> getComponent(String type) {
        return componentMaps.get(type);
    }

    @Override
    public void put(String type, String key, Object component) {
        componentMaps.computeIfAbsent(type, k -> new HashMap<>()).put(key, component);
    }
}
