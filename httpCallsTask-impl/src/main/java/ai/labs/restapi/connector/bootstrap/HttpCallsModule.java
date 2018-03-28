package ai.labs.restapi.connector.bootstrap;

import ai.labs.restapi.connector.impl.HttpCallsTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

public class HttpCallsModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.httpcalls").to(HttpCallsTask.class);
    }
}
