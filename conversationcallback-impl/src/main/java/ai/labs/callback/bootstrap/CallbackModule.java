package ai.labs.callback.bootstrap;

import ai.labs.callback.ICallback;
import ai.labs.callback.impl.Callback;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

import java.io.InputStream;

/**
 * @author rpi
 */
public class CallbackModule extends AbstractBaseModule {
    private final InputStream configFile;


    public CallbackModule(InputStream configFile) {
        this.configFile = configFile;
    }

    @Override
    protected void configure() {
        bind(ICallback.class).to(Callback.class).in(Scopes.SINGLETON);
        registerConfigFiles(configFile);
    }
}
