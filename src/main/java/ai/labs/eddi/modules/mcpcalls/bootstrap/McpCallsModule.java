/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.mcpcalls.impl.McpCallsTask;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.Map;

@Startup(1000)
@ApplicationScoped
public class McpCallsModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public McpCallsModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders, Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(McpCallsTask.ID, () -> instance.select(McpCallsTask.class).get());
        LOGGER.debug("Added McpCalls Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

}
