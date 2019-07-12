package ai.labs.restapi.connector.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.restapi.connector.impl.HttpCallsTask;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

public class HttpCallsModule {

    @PostConstruct
    @Inject
    protected void configure(Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put("ai.labs.httpcalls", () -> instance.select(HttpCallsTask.class).get());
    }
}
