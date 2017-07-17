package ai.labs.callback.bootstrap;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.http.ConversationCallback;
import ai.labs.callback.impl.ConversationCallbackTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;

import java.io.InputStream;

/**
 * @author rpi
 */
public class ConversationCallbackModule extends AbstractBaseModule {
    public ConversationCallbackModule(InputStream configFile) {
        super(configFile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(IConversationCallback.class).to(ConversationCallback.class).in(Scopes.SINGLETON);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.callback").to(ConversationCallbackTask.class);

    }
}
