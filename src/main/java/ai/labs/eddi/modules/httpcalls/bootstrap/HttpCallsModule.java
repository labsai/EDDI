package ai.labs.eddi.modules.httpcalls.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask;
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
public class HttpCallsModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public HttpCallsModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                           Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(HttpCallsTask.ID, () -> instance.select(HttpCallsTask.class).get());
        LOGGER.debug("Added HttpCalls Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

}
