package io.sls.runtime;

import io.sls.memory.model.Deployment;
import io.sls.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IBotFactory {
    IBot getLatestBot(Deployment.Environment environment, String botId) throws ServiceException;

    IBot getBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException;

    void deployBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException, IllegalAccessException;

    void undeployBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException, IllegalAccessException;
}
