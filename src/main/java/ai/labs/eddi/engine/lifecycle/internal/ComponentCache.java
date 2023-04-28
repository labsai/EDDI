package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.engine.lifecycle.IComponentCache;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ComponentCache implements IComponentCache {
    private final Map<String, Map<String, Object>> componentMaps = new HashMap<>();

    @Override
    public Map<String, Object> getComponentMap(String componentType) {
        return componentMaps.computeIfAbsent(componentType, k -> new HashMap<>());
    }

    @Override
    public void put(String componentType, String key, Object component) {
        componentMaps.computeIfAbsent(componentType, k -> new HashMap<>()).put(key, component);
    }
}
