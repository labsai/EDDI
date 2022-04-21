package ai.labs.eddi.modules.output.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class OutputGenerationModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");

    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put(OutputGenerationTask.ID, () -> instance.select(OutputGenerationTask.class).get());
        LOGGER.info("Added OutputGeneration Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

}
