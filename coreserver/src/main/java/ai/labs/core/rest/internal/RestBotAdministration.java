package ai.labs.core.rest.internal;

import ai.labs.memory.model.Deployment;
import ai.labs.memory.model.Deployment.Status;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.rest.rest.IRestBotAdministration;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.ThreadContext;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.util.EnumSet;
import java.util.concurrent.Callable;

import static ai.labs.resources.rest.deployment.model.DeploymentInfo.DeploymentStatus;

/**
 * @author ginccc
 */
@RequestScoped
@Slf4j
public class RestBotAdministration implements IRestBotAdministration {
    private final IBotFactory botFactory;
    private final IDeploymentStore deploymentStore;

    @Inject
    public RestBotAdministration(IBotFactory botFactory, IDeploymentStore deploymentStore) {
        this.botFactory = botFactory;
        this.deploymentStore = deploymentStore;
    }

    @Override
    public Response deployBot(final Deployment.Environment environment,
                              final String botId, final Integer version, final Boolean autoDeploy) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(autoDeploy, "autoDeploy");

        try {
            deploy(environment, botId, version, autoDeploy);
            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private void deploy(final Deployment.Environment environment,
                        final String botId, final Integer version, final Boolean autoDeploy) {
        Callable<Void> deployBot = () -> {
            try {
                if (EnumSet.of(Status.NOT_FOUND, Status.ERROR).contains(getStatus(environment, botId, version))) {
                    botFactory.deployBot(environment, botId, version,
                            status -> {
                                if (status == Status.READY && autoDeploy) {
                                    deploymentStore.setDeploymentInfo(environment.toString(),
                                            botId, version, DeploymentStatus.deployed);
                                }
                            });
                }
            } catch (ServiceException e) {
                String message = "Error while deploying bot! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                log.error(message, e);
                throw new InternalServerErrorException(message, e);
            } catch (IllegalAccessException e) {
                String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                log.error(message, e);
                throw new NoLogWebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }

            return null;
        };
        SystemRuntime.getRuntime().submitCallable(deployBot, ThreadContext.getResources());
    }

    @Override
    public Response undeployBot(Deployment.Environment environment, String botId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        try {
            //todo check if there are still active (READY) conversation going on with this botId/botVersion
            undeploy(environment, botId, version);
            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private void undeploy(Deployment.Environment environment, String botId, Integer version) {
        Callable<Void> undeployBot = () -> {
            try {
                botFactory.undeployBot(environment, botId, version);
                deploymentStore.setDeploymentInfo(environment.toString(),
                        botId, version, DeploymentStatus.undeployed);
            } catch (ServiceException e) {
                String message = "Error while undeploying bot! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                log.error(message, e);
                throw new InternalServerErrorException(message, e);
            } catch (IllegalAccessException e) {
                String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
                message = String.format(message, botId, version);
                log.error(message, e);
                throw new NoLogWebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }

            return null;
        };
        SystemRuntime.getRuntime().submitCallable(undeployBot, ThreadContext.getResources());
    }

    @Override
    public String getDeploymentStatus(Deployment.Environment environment, String botId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        return getStatus(environment, botId, version).toString();
    }

    private Status getStatus(Deployment.Environment environment, String botId, Integer version) {
        try {
            IBot bot = botFactory.getBot(environment, botId, version);
            if (bot != null) {
                return bot.getDeploymentStatus();
            } else {
                return Status.NOT_FOUND;
            }
        } catch (ServiceException e) {
            String message = "Error while deploying bot! (botId=%s , version=%s)";
            message = String.format(message, botId, version);
            log.error(message, e);
            throw new InternalServerErrorException(message, e);
        }
    }
}
