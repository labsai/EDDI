package ai.labs.core.bootstrap;

import ai.labs.core.rest.internal.*;
import ai.labs.rest.rest.*;
import ai.labs.runtime.DatabaseLogs;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.IDatabaseLogs;
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

        bind(ILogoutEndpoint.class).to(LogoutEndpoint.class);
        bind(IRestBotEngine.class).to(RestBotEngine.class);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class);
        bind(IRestBotManagement.class).to(RestBotManagement.class);
        bind(IRestHealthCheck.class).to(RestHealthCheck.class);
        bind(IRestLogs.class).to(RestLogs.class);
        bind(IDatabaseLogs.class).to(DatabaseLogs.class).in(Scopes.SINGLETON);
        bind(IConversationCoordinator.class).to(ConversationCoordinator.class).in(Scopes.SINGLETON);
        bind(IContextLogger.class).to(ContextLogger.class).in(Scopes.SINGLETON);
    }
}
