package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.bots.model.BotConfiguration;

/**
 * @author ginccc
 */
public interface IBotStoreService {
    BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException;
}
