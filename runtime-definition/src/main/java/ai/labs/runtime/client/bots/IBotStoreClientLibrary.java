package ai.labs.runtime.client.bots;

import ai.labs.exception.ServiceException;
import ai.labs.runtime.IBot;

/**
 * @author ginccc
 */
public interface IBotStoreClientLibrary {
    IBot getBot(String botId, Integer version) throws IllegalAccessException, ServiceException;
}
