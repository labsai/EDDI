package ai.labs.eddi.engine.lifecycle.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Provider;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class LifecycleModule {

    @LifecycleExtensions
    @ApplicationScoped
    Map<String, Provider<ILifecycleTask>> getLifecycleTaskProviders() {
        return new HashMap<>();
    }
}
