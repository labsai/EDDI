package ai.labs.output.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.output.impl.SimpleOutputTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class OutputGenerationModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.output").to(SimpleOutputTask.class);
    }
}
