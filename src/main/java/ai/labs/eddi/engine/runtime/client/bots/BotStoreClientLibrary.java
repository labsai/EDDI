package ai.labs.eddi.engine.runtime.client.bots;

import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.IPackageFactory;
import ai.labs.eddi.engine.runtime.internal.Bot;
import ai.labs.eddi.engine.runtime.internal.BotFactory;
import ai.labs.eddi.engine.runtime.service.IBotStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;

import static java.lang.String.format;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BotStoreClientLibrary implements IBotStoreClientLibrary {
    private final IBotStoreService botStoreService;
    private final IPackageFactory packageFactory;
    private static final Logger LOGGER = Logger.getLogger(BotFactory.class);

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
            IResourceId resourceId = RestUtilities.extractResourceId(packageURI);
            if (resourceId != null) {
                IExecutablePackage thePackage = packageFactory.getExecutablePackage(resourceId.getId(), resourceId.getVersion());
                bot.addPackage(thePackage);
            } else {
                LOGGER.warn(format("packageId should not have been null! (botId=%s,botVersion=%d)", botId, version));
            }
        }

        return bot;
    }
}
