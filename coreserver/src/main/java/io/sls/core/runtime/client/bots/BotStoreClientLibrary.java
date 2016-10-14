package io.sls.core.runtime.client.bots;

import io.sls.core.runtime.IBot;
import io.sls.core.runtime.IExecutablePackage;
import io.sls.core.runtime.IPackageFactory;
import io.sls.core.runtime.internal.Bot;
import io.sls.core.runtime.service.IBotStoreService;
import io.sls.core.runtime.service.ServiceException;
import io.sls.core.utilities.URIUtilities;
import io.sls.resources.rest.bots.model.BotConfiguration;

import javax.inject.Inject;
import java.net.URI;


/**
 * User: jarisch
 * Date: 17.05.12
 * Time: 18:11
 */
public class BotStoreClientLibrary {
    private final IBotStoreService botStoreService;
    private final IPackageFactory packageFactory;

    @Inject
    public BotStoreClientLibrary(IBotStoreService botStoreService,
                                 IPackageFactory packageFactory) {
        this.botStoreService = botStoreService;
        this.packageFactory = packageFactory;
    }

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
