package ai.labs.runtime.client.bots;

import ai.labs.runtime.IBot;
import ai.labs.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IBotStoreClientLibrary {
    IBot getBot(String botId, Integer version) throws ServiceException, IllegalAccessException;
}
