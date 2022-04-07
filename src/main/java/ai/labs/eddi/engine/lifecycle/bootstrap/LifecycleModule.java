package ai.labs.eddi.engine.lifecycle.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Provider;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class LifecycleModule {

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders = new HashMap<>();

    @LifecycleExtensions
    @ApplicationScoped
    public Map<String, Provider<ILifecycleTask>> getLifecycleTaskProviders() {
        return lifecycleTaskProviders;
    }

    public void start() {}
}
