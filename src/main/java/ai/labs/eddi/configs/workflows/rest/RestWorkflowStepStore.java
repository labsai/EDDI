/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.workflows.IRestWorkflowStepStore;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class RestWorkflowStepStore implements IRestWorkflowStepStore {
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;

    @Inject
    public RestWorkflowStepStore(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public List<ExtensionDescriptor> getWorkflowSteps(String filter) {
        // The v6 aliases (ai.labs.rules → ai.labs.behavior, ai.labs.apicalls →
        // ai.labs.httpcalls) are registered as a second key pointing at the SAME
        // provider, so listing keys returned those two steps twice. Distinct on the
        // provider keeps one descriptor per task while a filter matching only the
        // alias name still finds it.
        return lifecycleExtensionsProvider.entrySet().stream()
                .filter(entry -> filter == null || filter.isEmpty() || entry.getKey().contains(filter))
                .map(Map.Entry::getValue)
                .distinct()
                .map(taskProvider -> taskProvider.get().getExtensionDescriptor())
                .toList();
    }
}
