/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Provider;
import java.util.HashMap;
import java.util.Map;

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
