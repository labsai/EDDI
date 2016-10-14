package io.sls.core.runtime.service;

import io.sls.resources.rest.bots.model.BotConfiguration;

/**
 * Created by jariscgr on 09.08.2016.
 */
public interface IBotStoreService {
    BotConfiguration getBotConfiguration(String botId, Integer version) throws ServiceException;
}
