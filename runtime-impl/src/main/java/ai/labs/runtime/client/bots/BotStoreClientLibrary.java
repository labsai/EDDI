package ai.labs.runtime.client.bots;

import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.IPackageFactory;
import ai.labs.runtime.internal.Bot;
import ai.labs.runtime.service.IBotStoreService;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.URIUtilities;

import javax.inject.Inject;
import java.net.URI;

/**
 * @author ginccc
 */
public class BotStoreClientLibrary implements IBotStoreClientLibrary {
    private final IBotStoreService botStoreService;
    private final IPackageFactory packageFactory;

    @Inject
    public BotStoreClientLibrary(IBotStoreService botStoreService,
                                 IPackageFactory packageFactory) {
        this.botStoreService = botStoreService;
        this.packageFactory = packageFactory;
    }

    @Override
    public IBot getBot(final String botId, final Integer version) throws ServiceException, IllegalAccessException {
        final IBot bot = new Bot(botId, version);
        final BotConfiguration botConfiguration = botStoreService.getBotConfiguration(botId, version);
        for (final URI packageURI : botConfiguration.getPackages()) {
            ResourceId resourceId = URIUtilities.extractResourceId(packageURI);
            IExecutablePackage thePackage = packageFactory.getExecutablePackage(resourceId.getId(), resourceId.getVersion());
            bot.addPackage(thePackage);
        }

        return bot;
    }
}
