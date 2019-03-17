package ai.labs.runtime.service;

import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;

import javax.inject.Inject;


/**
 * @author ginccc
 */
public class BotStoreService implements IBotStoreService {
    private final IRestInterfaceFactory restInterfaceFactory;

    @Inject
    public BotStoreService(IRestInterfaceFactory restInterfaceFactory) {
        this.restInterfaceFactory = restInterfaceFactory;
    }

    @Override
    public BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException {
        try {
            IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class);
            return restBotStore.readBot(botId, version);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
