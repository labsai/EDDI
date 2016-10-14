package io.sls.core.runtime;

import io.sls.memory.model.Deployment;
import io.sls.core.runtime.service.ServiceException;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 19:25
 */
public interface IBotFactory {
    IBot getLatestBot(Deployment.Environment environment, String botId) throws ServiceException;

    IBot getBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException;

    void deployBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException, IllegalAccessException;

    void undeployBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException, IllegalAccessException;
}
