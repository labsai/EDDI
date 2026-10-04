/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

public interface IComponentCache {
    Map<String, Object> getComponentMap(String type);

    void put(String type, String key, Object component);

    /**
     * Drops the components built for a workflow version ({@code null}: every
     * version) — see {@code LifecycleUtilities.createComponentKey}.
     */
    default void evictWorkflow(String workflowId, Integer workflowVersion) {
    }
}
