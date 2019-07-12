package ai.labs.resources.impl.client.bots;

import ai.labs.exception.ServiceException;
import ai.labs.models.BotConfiguration;
import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.IPackageFactory;
import ai.labs.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.utilities.URIUtilities;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.URI;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BotStoreClientLibrary implements IBotStoreClientLibrary {
    private final IRestBotStore restBotStore;
    private final IPackageFactory packageFactory;

    @Inject
    public BotStoreClientLibrary(IRestBotStore restBotStore,
                                 IPackageFactory packageFactory) {
        this.restBotStore = restBotStore;
        this.packageFactory = packageFactory;
    }

    @Override
    public IBot getBot(final String botId, final Integer version) throws ServiceException, IllegalAccessException {
        final IBot bot = new Bot(botId, version);
        final BotConfiguration botConfiguration = restBotStore.readBot(botId, version);
        for (final URI packageURI : botConfiguration.getPackages()) {
            ResourceId resourceId = URIUtilities.extractResourceId(packageURI);
            IExecutablePackage thePackage = packageFactory.getExecutablePackage(resourceId.getId(), resourceId.getVersion());
            bot.addPackage(thePackage);
        }

        return bot;
    }
}
