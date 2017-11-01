package ai.labs.property.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.property.IPropertyDisposer;
import ai.labs.property.impl.PropertyDisposer;
import ai.labs.property.impl.PropertyDisposerTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class PropertyDisposerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IPropertyDisposer.class).to(PropertyDisposer.class);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.property").to(PropertyDisposerTask.class);
    }
}
