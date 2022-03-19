package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.IAutoBotDeployment;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Deployment.Environment;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * @author ginccc
 */

@ApplicationScoped
public class AutoBotDeployment implements IAutoBotDeployment {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;

    @Inject
    Logger log;

    @Inject
    public AutoBotDeployment(IDeploymentStore deploymentStore, IBotFactory botFactory) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
    }

    @Override
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
                        } catch (ServiceException | IllegalAccessException | IllegalArgumentException e) {
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
