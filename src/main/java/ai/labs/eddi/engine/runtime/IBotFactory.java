package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Deployment;

import java.util.List;

/**
 * @author ginccc
 */
public interface IBotFactory {
    IBot getLatestBot(Deployment.Environment environment, String botId) throws ServiceException;

    List<IBot> getAllLatestBots(Deployment.Environment environment) throws ServiceException;

    IBot getBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException;

    void deployBot(Deployment.Environment environment, String botId, Integer version, DeploymentProcess deploymentProcess)
            throws ServiceException, IllegalAccessException;

    void undeployBot(Deployment.Environment environment, String botId, Integer version)
            throws ServiceException, IllegalAccessException;

    interface DeploymentProcess {
        void completed(Deployment.Status status);
    }
}
