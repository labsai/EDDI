package ai.labs.normalizer.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.normalizer.impl.NormalizeInputTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

@Deprecated
public class NormalizerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.normalizer").to(NormalizeInputTask.class);
    }
}
