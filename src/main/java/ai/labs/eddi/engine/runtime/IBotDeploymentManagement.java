package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Deployment;

/**
 * @author ginccc
 */
public interface IBotDeploymentManagement {
    void autoDeployBots() throws AutoDeploymentException;

    IBot attemptBotDeployment(Deployment.Environment environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException, ServiceException, IllegalAccessException;


    class AutoDeploymentException extends Exception {
        public AutoDeploymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
