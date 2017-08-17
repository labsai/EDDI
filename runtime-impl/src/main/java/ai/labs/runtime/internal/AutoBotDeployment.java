package ai.labs.runtime.internal;

import ai.labs.memory.model.Deployment;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import ai.labs.runtime.IAutoBotDeployment;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.service.ServiceException;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;

/**
 * @author ginccc
 */
@Slf4j
public class AutoBotDeployment implements IAutoBotDeployment {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;

    @Inject
    public AutoBotDeployment(IDeploymentStore deploymentStore, IBotFactory botFactory) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
    }

    @Override
    public void autoDeployBots() throws AutoDeploymentException {
        try {
            deploymentStore.readDeploymentInfos().stream().filter(
                    deploymentInfo -> deploymentInfo.getDeploymentStatus() == DeploymentInfo.DeploymentStatus.deployed).
                    forEach(deploymentInfo -> {
                        try {
                            logBotDeployment(deploymentInfo, null);
                            botFactory.deployBot(
                                    Deployment.Environment.valueOf(deploymentInfo.getEnvironment().toString()),
                                    deploymentInfo.getBotId(), deploymentInfo.getBotVersion(),
                                    status -> logBotDeployment(deploymentInfo, status)
                            );
                        } catch (ServiceException | IllegalAccessException | IllegalArgumentException e) {
                            log.error("Error while auto deploying bots!\n" + e.getLocalizedMessage(), e);
                        }
                    });
        } catch (IResourceStore.ResourceStoreException e) {
            throw new AutoDeploymentException(e.getLocalizedMessage(), e);
        }
    }

    private void logBotDeployment(DeploymentInfo deploymentInfo, Deployment.Status status) {
        if (status == null) {
            log.info(String.format("Deploying Bot... (environment=%s, id=%s , version=%s)",
                    deploymentInfo.getEnvironment(), deploymentInfo.getBotId(), deploymentInfo.getBotVersion()));
        } else {
            log.info(String.format("Bot is deployed. (environment=%s, id=%s , version=%s)  Status: %s",
                    deploymentInfo.getEnvironment(), deploymentInfo.getBotId(), deploymentInfo.getBotVersion(), status));
        }
    }
}
