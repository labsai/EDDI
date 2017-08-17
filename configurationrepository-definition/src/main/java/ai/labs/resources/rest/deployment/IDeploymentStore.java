package ai.labs.resources.rest.deployment;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDeploymentStore {
    void setDeploymentInfo(String environment,
                           String botId,
                           Integer botVersion,
                           DeploymentInfo.DeploymentStatus deploymentStatus);

    List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException;
}
