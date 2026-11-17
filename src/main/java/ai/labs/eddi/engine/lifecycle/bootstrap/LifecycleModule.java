/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Provider;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns the registry of lifecycle task providers, keyed by step type id and
 * produced under the {@link LifecycleExtensions} qualifier. Each area's
 * bootstrap module (for example {@code ApiCallsModule}) puts its task's
 * provider into the map in a {@code @PostConstruct}; a workflow step type that
 * no module registered cannot be deployed.
 */
@Startup(1000)
@ApplicationScoped
public class LifecycleModule {

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders = new HashMap<>();

    @LifecycleExtensions
    @ApplicationScoped
    public Map<String, Provider<ILifecycleTask>> getLifecycleTaskProviders() {
        return lifecycleTaskProviders;
    }

}
