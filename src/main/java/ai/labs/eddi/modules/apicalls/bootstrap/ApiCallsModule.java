/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.apicalls.impl.ApiCallsTask;
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
public class ApiCallsModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public ApiCallsModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders, Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(ApiCallsTask.ID, () -> instance.select(ApiCallsTask.class).get());
        // V6 alias: ai.labs.httpcalls → ai.labs.apicalls
        lifecycleTaskProviders.put("ai.labs.apicalls", lifecycleTaskProviders.get(ApiCallsTask.ID));
        LOGGER.debug("Added ApiCalls Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

}
