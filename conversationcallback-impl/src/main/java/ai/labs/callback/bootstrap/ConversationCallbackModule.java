package ai.labs.callback.bootstrap;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.http.ConversationCallback;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

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
    }
}
