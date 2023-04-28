package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/**
 * @author ginccc
 */
@ApplicationScoped
public class BotStoreService implements IBotStoreService {

    private final IRestBotStore restBotStore;

    @Inject
    public BotStoreService(IRestBotStore restBotStore) {
        this.restBotStore = restBotStore;
    }

    @Override
    public BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException {
        try {
            return restBotStore.readBot(botId, version);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
