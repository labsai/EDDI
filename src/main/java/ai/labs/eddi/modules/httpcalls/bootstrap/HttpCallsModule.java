package ai.labs.eddi.modules.httpcalls.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

@Startup
@ApplicationScoped
public class HttpCallsModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");

    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put(HttpCallsTask.ID, () -> instance.select(HttpCallsTask.class).get());
        LOGGER.info("Added HttpCalls Module, current size of lifecycle modules " + lifecycleTaskProviders.size());


    }

}
