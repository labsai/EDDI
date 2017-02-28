package ai.labs.runtime.service;

import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;

import javax.inject.Inject;
import javax.inject.Named;


/**
 * @author ginccc
 */
public class BotStoreService implements IBotStoreService {

    private final IRestInterfaceFactory restInterfaceFactory;
    private final String configurationServerURI;

    @Inject
    public BotStoreService(IRestInterfaceFactory restInterfaceFactory,
                           @Named("system.apiServerURI") String configurationServerURI) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.configurationServerURI = configurationServerURI;
    }

    @Override
    public BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException {
        try {
            IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class, configurationServerURI);
            return (BotConfiguration) restBotStore.readBot(botId, version).getEntity();
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
