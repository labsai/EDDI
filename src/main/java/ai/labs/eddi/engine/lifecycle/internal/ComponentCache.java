/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.engine.lifecycle.IComponentCache;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ComponentCache implements IComponentCache {
    private final Map<String, Map<String, Object>> componentMaps = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> getComponentMap(String componentType) {
        return componentMaps.computeIfAbsent(componentType, k -> new ConcurrentHashMap<>());
    }

    @Override
    public void put(String componentType, String key, Object component) {
        componentMaps.computeIfAbsent(componentType, k -> new ConcurrentHashMap<>()).put(key, component);
    }

    @Override
    public void evictWorkflow(String workflowId, Integer workflowVersion) {
        if (workflowId == null) {
            return;
        }
        // Keys are createComponentKey(id, version, step) = "id:version:step".
        String prefix = workflowVersion == null ? workflowId + ":" : workflowId + ":" + workflowVersion + ":";
        for (Map<String, Object> components : componentMaps.values()) {
            components.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }
}
