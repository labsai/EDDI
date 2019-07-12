package ai.labs.callback.bootstrap;

import ai.labs.callback.impl.ConversationCallbackTask;
import ai.labs.lifecycle.ILifecycleTask;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Provider;
import java.util.Map;

/**
 * @author rpi
 */
public class ConversationCallbackModule {

    @PostConstruct
    protected void configure(Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put("ai.labs.callback", () -> instance.select(ConversationCallbackTask.class).get());
    }
}
