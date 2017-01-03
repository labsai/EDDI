package io.sls.runtime.client.bots;

import io.sls.core.utilities.URIUtilities;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.runtime.IBot;
import io.sls.runtime.IExecutablePackage;
import io.sls.runtime.IPackageFactory;
import io.sls.runtime.internal.Bot;
import io.sls.runtime.service.IBotStoreService;
import io.sls.runtime.service.ServiceException;

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
            URIUtilities.ResourceId resourceId = URIUtilities.extractResourceId(packageURI);
            IExecutablePackage thePackage = packageFactory.getExecutablePackage(resourceId.getId(), resourceId.getVersion());
            bot.addPackage(thePackage);
        }

        return bot;
    }
}
