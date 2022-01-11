package ai.labs.eddi.engine.runtime.client.bots;

import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IBotStoreClientLibrary {
    IBot getBot(String botId, Integer version) throws ServiceException, IllegalAccessException;
}
