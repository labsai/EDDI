package io.sls.runtime.client.bots;

import io.sls.runtime.IBot;
import io.sls.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IBotStoreClientLibrary {
    IBot getBot(String botId, Integer version) throws ServiceException, IllegalAccessException;
}
