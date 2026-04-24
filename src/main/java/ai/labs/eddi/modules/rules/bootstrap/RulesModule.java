/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.rules.impl.RulesEvaluationTask;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class RulesModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public RulesModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders, Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(RulesEvaluationTask.ID, () -> instance.select(RulesEvaluationTask.class).get());
        // V6 alias: ai.labs.behavior → ai.labs.rules
        lifecycleTaskProviders.put("ai.labs.rules", lifecycleTaskProviders.get(RulesEvaluationTask.ID));
        LOGGER.debug("Added Behaviour Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
