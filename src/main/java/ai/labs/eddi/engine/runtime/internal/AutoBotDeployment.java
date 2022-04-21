package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.IAutoBotDeployment;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Deployment.Environment;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

/**
 * @author ginccc
 */

@Startup(4000)
@ApplicationScoped
public class AutoBotDeployment implements IAutoBotDeployment {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;

    private static final Logger LOGGER = Logger.getLogger(AutoBotDeployment.class);

    @Inject
    public AutoBotDeployment(IDeploymentStore deploymentStore, IBotFactory botFactory) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            autoDeployBots();
        } catch (IAutoBotDeployment.AutoDeploymentException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void autoDeployBots() throws AutoDeploymentException {
        try {
            LOGGER.info("Starting auto deployment of bots...");
            deploymentStore.readDeploymentInfos().stream().filter(
                            deploymentInfo -> deploymentInfo.getDeploymentStatus() == DeploymentStatus.deployed).
                    forEach(deploymentInfo -> {
                        Environment environment = Environment.valueOf(deploymentInfo.getEnvironment().toString());
                        String botId = deploymentInfo.getBotId();
                        Integer botVersion = deploymentInfo.getBotVersion();
                        try {
                            botFactory.deployBot(environment, botId, botVersion, null);
                        } catch (ServiceException | IllegalAccessException | IllegalArgumentException e) {
                            String message = "Error while auto deploying bot (environment=%s, botId=%s, version=%s)!\n";
                            LOGGER.error(String.format(message, environment, botId, botVersion));
                            LOGGER.error(e.getLocalizedMessage(), e);
                        }
                    });
            LOGGER.info("Finished auto deployment of bots.");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new AutoDeploymentException(e.getLocalizedMessage(), e);
        }
    }
}
