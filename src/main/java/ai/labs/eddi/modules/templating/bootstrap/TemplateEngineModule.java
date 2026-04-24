/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.templating.OutputTemplateTask;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Registers the OutputTemplate lifecycle task. Template engine and extensions
 * are handled by Qute CDI auto-discovery.
 *
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class TemplateEngineModule {
    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public TemplateEngineModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
            Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(OutputTemplateTask.ID, () -> instance.select(OutputTemplateTask.class).get());
        LOGGER.debug("Added TemplateEngine Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
