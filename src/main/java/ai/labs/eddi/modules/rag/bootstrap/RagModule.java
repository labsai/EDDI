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

/**
 * Registers {@link RagTask} as the {@code ai.labs.rag} lifecycle extension.
 * <p>
 * Without this registration {@code WorkflowStoreClientLibrary} throws
 * {@code UnrecognizedExtensionException} for any workflow containing a
 * {@code eddi://ai.labs.rag} step, and {@code RestWorkflowStepStore} — which
 * builds the Manager's step chooser from the same map — never offers one.
 */
@Startup(1000)
@ApplicationScoped
public class RagModule {
    private static final Logger LOGGER = Logger.getLogger("Startup");

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public RagModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
            Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(RagTask.ID, () -> instance.select(RagTask.class).get());
        LOGGER.debug("Added Rag Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
