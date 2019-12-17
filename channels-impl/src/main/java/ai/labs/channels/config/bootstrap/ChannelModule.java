package ai.labs.channels.config.bootstrap;

import ai.labs.channels.config.ChannelDefinitionStore;
import ai.labs.channels.config.ChannelManager;
import ai.labs.channels.config.IChannelDefinitionStore;
import ai.labs.channels.config.IChannelManager;
import ai.labs.channels.config.IRestChannelDefinitionStore;
import ai.labs.channels.config.RestChannelDefinitionStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

public class ChannelModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IChannelDefinitionStore.class).to(ChannelDefinitionStore.class).in(Scopes.SINGLETON);
        bind(IChannelManager.class).to(ChannelManager.class).in(Scopes.SINGLETON);
        bind(IRestChannelDefinitionStore.class).to(RestChannelDefinitionStore.class).in(Scopes.SINGLETON);
    }
}
