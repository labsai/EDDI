package ai.labs.eddi.configs.deployment;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Database-agnostic storage interface for deployment information.
 * <p>
 * Implementations: MongoDeploymentStorage (@DefaultBean), PostgresDeploymentStorage (@LookupIfProperty).
 */
public interface IDeploymentStorage {

    void setDeploymentInfo(String environment, String botId, Integer botVersion,
                           DeploymentInfo.DeploymentStatus deploymentStatus);

    DeploymentInfo readDeploymentInfo(String environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException;

    List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException;

    List<DeploymentInfo> readDeploymentInfos(String deploymentStatus) throws IResourceStore.ResourceStoreException;
}
