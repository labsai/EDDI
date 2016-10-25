package io.sls.core.runtime.service;

import io.sls.resources.rest.bots.model.BotConfiguration;

/**
 * @author ginccc
 */
public interface IBotStoreService {
    BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException;
}
