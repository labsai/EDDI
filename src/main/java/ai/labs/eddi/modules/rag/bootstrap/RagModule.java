/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.rag.RagTask;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

@Startup(1000)
@ApplicationScoped
public class RagModule {
    private static final Logger LOGGER = Logger.getLogger("Startup");

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> lifecycleTaskInstance;

    @Inject
    public RagModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
            Instance<ILifecycleTask> lifecycleTaskInstance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.lifecycleTaskInstance = lifecycleTaskInstance;
    }

    @PostConstruct
    protected void configure() {
        lifecycleTaskProviders.put(RagTask.ID, () -> lifecycleTaskInstance.select(RagTask.class).get());
        LOGGER.debug("Added RAG Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
