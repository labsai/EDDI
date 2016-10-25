package io.sls.core.runtime.service;

import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.resources.rest.bots.IRestBotStore;
import io.sls.resources.rest.bots.model.BotConfiguration;

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
                           @Named("system.configurationServerURI") String configurationServerURI) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.configurationServerURI = configurationServerURI;
    }

    @Override
    public BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException {
        try {
            IRestBotStore restBotStore = restInterfaceFactory.get(IRestBotStore.class, configurationServerURI);
            return restBotStore.readBot(botId, version);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
