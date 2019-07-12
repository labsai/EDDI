package ai.labs.lifecycle.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Provider;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConversationLifecycleModule {
    @LifecycleExtensions
    @Produces
    @ApplicationScoped
    public Map<String, Provider<ILifecycleTask>> provideLifecycleTasks() {
        return new LinkedHashMap<>();
    }
}
