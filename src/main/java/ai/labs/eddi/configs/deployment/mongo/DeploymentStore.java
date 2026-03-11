package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * DeploymentStore delegates to {@link IDeploymentStorage} for all persistence.
 * The underlying storage is injected via CDI (MongoDB by default, PostgreSQL when configured).
 *
 * @author ginccc
 */
@ApplicationScoped
public class DeploymentStore implements IDeploymentStore {

    private final IDeploymentStorage storage;

    @Inject
    public DeploymentStore(IDeploymentStorage storage) {
        this.storage = storage;
    }

    @Override
    public DeploymentInfo getDeploymentInfo(String environment, String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfo(environment, botId, botVersion);
    }

    @Override
    public void setDeploymentInfo(String environment, String botId, Integer botVersion,
                                  DeploymentInfo.DeploymentStatus deploymentStatus) {
        storage.setDeploymentInfo(environment, botId, botVersion, deploymentStatus);
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfos();
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos(DeploymentInfo.DeploymentStatus deploymentStatus)
            throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfos(deploymentStatus.toString());
    }
}
