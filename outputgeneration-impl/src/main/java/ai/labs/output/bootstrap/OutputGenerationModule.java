package ai.labs.output.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.output.impl.OutputGenerationTask;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
public class OutputGenerationModule {
    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put("ai.labs.output", () -> instance.select(OutputGenerationTask.class).get());
    }
}
