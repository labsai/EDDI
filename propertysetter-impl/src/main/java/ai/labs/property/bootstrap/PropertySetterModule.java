package ai.labs.property.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.property.IPropertySetter;
import ai.labs.property.impl.PropertySetter;
import ai.labs.property.impl.PropertySetterTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class PropertySetterModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IPropertySetter.class).to(PropertySetter.class);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.property").to(PropertySetterTask.class);
    }
}
