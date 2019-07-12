package ai.labs.resources.impl.client.bots;

import ai.labs.exception.ServiceException;
import ai.labs.models.Deployment.Environment;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import ai.labs.runtime.IAutoBotDeployment;
import ai.labs.runtime.IBotFactory;
import io.quarkus.runtime.StartupEvent;
import lombok.extern.slf4j.Slf4j;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

/**
 * @author ginccc
 */
@Slf4j
@ApplicationScoped
public class AutoBotDeployment implements IAutoBotDeployment {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;

    @Inject
    public AutoBotDeployment(IDeploymentStore deploymentStore, IBotFactory botFactory) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            autoDeployBots();
        } catch (AutoDeploymentException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    public void autoDeployBots() throws AutoDeploymentException {
        try {
            log.info("Starting auto deployment of bots...");
            deploymentStore.readDeploymentInfos().stream().filter(
                    deploymentInfo -> deploymentInfo.getDeploymentStatus() == DeploymentInfo.DeploymentStatus.deployed).
                    forEach(deploymentInfo -> {
                        Environment environment = Environment.valueOf(deploymentInfo.getEnvironment().toString());
                        String botId = deploymentInfo.getBotId();
                        Integer botVersion = deploymentInfo.getBotVersion();
                        try {
                            botFactory.deployBot(environment, botId, botVersion, null);
                        } catch (IllegalAccessException | IllegalArgumentException | ServiceException e) {
                            String message = "Error while auto deploying bot (environment=%s, botId=%s, version=%s)!\n";
                            log.error(String.format(message, environment, botId, botVersion));
                            log.error(e.getLocalizedMessage(), e);
                        }
                    });
            log.info("Finished auto deployment of bots.");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new AutoDeploymentException(e.getLocalizedMessage(), e);
        }
    }
}
