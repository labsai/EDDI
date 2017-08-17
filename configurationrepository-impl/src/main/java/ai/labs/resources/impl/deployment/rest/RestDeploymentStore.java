package ai.labs.resources.impl.deployment.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.IRestDeploymentStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;
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
