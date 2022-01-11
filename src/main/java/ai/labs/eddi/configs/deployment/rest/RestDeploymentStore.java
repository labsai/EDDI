package ai.labs.eddi.configs.deployment.rest;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.IRestDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestDeploymentStore implements IRestDeploymentStore {
    private final IDeploymentStore deploymentStore;

    @Inject
    public RestDeploymentStore(IDeploymentStore deploymentStore) {
        this.deploymentStore = deploymentStore;
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() {
        try {
            return deploymentStore.readDeploymentInfos();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error while reading DeploymentInfos.");
        }
    }
}
