package ai.labs.core.rest.internal;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.models.BotDeploymentStatus;
import ai.labs.models.Deployment;
import ai.labs.models.Deployment.Status;
import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.rest.restinterfaces.IRestBotAdministration;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.ThreadContext;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.Callable;

import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;
import static ai.labs.resources.rest.deployment.model.DeploymentInfo.DeploymentStatus;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotAdministration implements IRestBotAdministration {
    private final IBotFactory botFactory;
    private final IDeploymentStore deploymentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public RestBotAdministration(IBotFactory botFactory,
                                 IDeploymentStore deploymentStore,
                                 IConversationMemoryStore conversationMemoryStore,
                                 IDocumentDescriptorStore documentDescriptorStore) {
        this.botFactory = botFactory;
        this.deploymentStore = deploymentStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.documentDescriptorStore = documentDescriptorStore;
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
                if (EnumSet.of(Status.NOT_FOUND, Status.ERROR).contains(checkDeploymentStatus(environment, botId, version))) {
                    botFactory.deployBot(environment, botId, version,
                            status -> {
                                if (status == Status.READY && autoDeploy) {
                                    deploymentStore.setDeploymentInfo(environment.toString(),
                                            botId, version, DeploymentStatus.deployed);
                                }
                            });
                }
            } catch (ServiceException e) {
                throwError(botId, version, e, "Error while deploying bot! (botId=%s , version=%s)");
            } catch (IllegalAccessException e) {
                throwErrorForbidden(botId, version, e);
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
            Long activeConversationCount = conversationMemoryStore.getActiveConversationCount(botId, version);
            if (activeConversationCount > 0) {
                String message = "%s active (thus not ENDED) conversation(s) going on with this bot!" +
                        "\nCheck GET /conversationstore/conversations/active/%s?botVersion=%s " +
                        "to see active conversations and end conversations with " +
                        "POST /conversationstore/conversations/end , " +
                        "providing the list you receive with GET";
                message = String.format(message, activeConversationCount, botId, version);
                return Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build();
            }

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
                throwError(botId, version, e, "Error while undeploying bot! (botId=%s , version=%s)");
            } catch (IllegalAccessException e) {
                return throwErrorForbidden(botId, version, e);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }

            return null;
        };
        SystemRuntime.getRuntime().submitCallable(undeployBot, ThreadContext.getResources());
    }

    private static Void throwErrorForbidden(String botId, Integer version, IllegalAccessException e) {
        String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
        message = String.format(message, botId, version);
        log.error(message, e);
        throw new NoLogWebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
    }

    @Override
    public String getDeploymentStatus(Deployment.Environment environment, String botId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        return checkDeploymentStatus(environment, botId, version).toString();
    }

    @Override
    public List<BotDeploymentStatus> getDeploymentStatuses(Deployment.Environment environment) {
        RuntimeUtilities.checkNotNull(environment, "environment");

        try {
            List<BotDeploymentStatus> botDeploymentStatuses = new LinkedList<>();
            for (IBot latestBot : botFactory.getAllLatestBots(environment)) {
                String botId = latestBot.getBotId();
                Integer botVersion = latestBot.getBotVersion();
                DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(botId, botVersion);
                botDeploymentStatuses.add(new BotDeploymentStatus(
                        environment,
                        botId,
                        botVersion,
                        latestBot.getDeploymentStatus(),
                        documentDescriptor));
            }

            botDeploymentStatuses.sort(Comparator.comparing(o -> o.getDescriptor().getLastModifiedOn()));
            Collections.reverse(botDeploymentStatuses);

            return botDeploymentStatuses;
        } catch (ServiceException | ResourceStoreException | ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Status checkDeploymentStatus(Deployment.Environment environment, String botId, Integer version) {
        try {
            IBot bot = botFactory.getBot(environment, botId, version);
            if (bot != null) {
                return bot.getDeploymentStatus();
            } else {
                return Status.NOT_FOUND;
            }
        } catch (ServiceException e) {
            return throwError(botId, version, e, "Error while deploying bot! (botId=%s , version=%s)");
        }
    }

    private Status throwError(String botId, Integer version, ServiceException e, String message) {
        message = String.format(message, botId, version);
        log.error(message, e);
        throw new InternalServerErrorException(message, e);
    }
}
