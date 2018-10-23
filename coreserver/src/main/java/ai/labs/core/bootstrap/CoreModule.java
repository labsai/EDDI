package ai.labs.core.bootstrap;

import ai.labs.core.rest.internal.RestBotAdministration;
import ai.labs.core.rest.internal.RestBotEngine;
import ai.labs.core.rest.internal.RestBotManagement;
import ai.labs.core.rest.internal.RestHealthCheck;
import ai.labs.rest.rest.IRestBotAdministration;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.rest.rest.IRestBotManagement;
import ai.labs.rest.rest.IRestHealthCheck;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.runtime.internal.ConversationCoordinator;
import com.google.inject.Scopes;

import java.io.InputStream;

public class CoreModule extends AbstractBaseModule {
    public CoreModule(InputStream... inputStream) {
        super(inputStream);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(IRestBotEngine.class).to(RestBotEngine.class);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class);
        bind(IRestBotManagement.class).to(RestBotManagement.class);
        bind(IRestHealthCheck.class).to(RestHealthCheck.class);
        bind(IConversationCoordinator.class).to(ConversationCoordinator.class).in(Scopes.SINGLETON);
    }
}
