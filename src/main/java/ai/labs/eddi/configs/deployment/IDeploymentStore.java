package ai.labs.eddi.configs.deployment;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;

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
