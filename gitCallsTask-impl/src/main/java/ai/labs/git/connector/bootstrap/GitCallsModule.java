package ai.labs.git.connector.bootstrap;

import ai.labs.git.connector.impl.GitCallsTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

public class GitCallsModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.gitcalls").to(GitCallsTask.class);
    }
}
