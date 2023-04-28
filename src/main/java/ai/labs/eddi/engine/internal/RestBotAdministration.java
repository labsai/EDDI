package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.BotDeploymentStatus;
import ai.labs.eddi.models.Deployment;
import ai.labs.eddi.models.Deployment.Status;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestBotAdministration implements IRestBotAdministration {
    private final IBotFactory botFactory;
    private final IDeploymentStore deploymentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRestConversationStore restConversationStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(RestBotAdministration.class);

    @Inject
    public RestBotAdministration(IRuntime runtime,
                                 IBotFactory botFactory,
                                 IDeploymentStore deploymentStore,
                                 IConversationMemoryStore conversationMemoryStore,
                                 IRestConversationStore restConversationStore,
                                 IDocumentDescriptorStore documentDescriptorStore) {
        this.runtime = runtime;
        this.botFactory = botFactory;
        this.deploymentStore = deploymentStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.restConversationStore = restConversationStore;
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
                                            botId, version, DeploymentInfo.DeploymentStatus.deployed);
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

        runtime.submitCallable(deployBot, ThreadContext.getResources());
    }

    @Override
    public Response undeployBot(Deployment.Environment environment, String botId, Integer version,
                                Boolean endAllActiveConversations, Boolean undeployThisAndAllPreviousBotVersions) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        try {
            do {
                Long activeConversationCount = conversationMemoryStore.getActiveConversationCount(botId, version);
                if (activeConversationCount > 0) {
                    if (endAllActiveConversations) {
                        var activeConversations = restConversationStore.getActiveConversations(botId, version);
                        restConversationStore.endActiveConversations(activeConversations);
                    } else {
                        String message = "%s active (thus not ENDED) conversation(s) going on with this bot!" +
                                "\nCheck GET /conversationstore/conversations/active/%s?botVersion=%s " +
                                "to see active conversations and end conversations with " +
                                "POST /conversationstore/conversations/end , " +
                                "providing the list you receive with GET" +
                                "\nIn order to end all active conversations, the query param 'endAllActiveConversations' " +
                                "can be set to true.";
                        message = String.format(message, activeConversationCount, botId, version);
                        return Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build();
                    }
                }

                undeploy(environment, botId, version);
                log.info(String.format("Successfully undeployed bot (botId=%s, botVersion=%s, environment=%s)", botId, version, environment));
            } while (undeployThisAndAllPreviousBotVersions && version-- > 1);

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
                        botId, version, DeploymentInfo.DeploymentStatus.undeployed);
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

        runtime.submitCallable(undeployBot, ThreadContext.getResources());
    }

    private Void throwErrorForbidden(String botId, Integer version, IllegalAccessException e) {
        String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
        message = String.format(message, botId, version);
        log.error(message, e);
        throw new NoLogWebApplicationException(new Throwable(message), Response.Status.FORBIDDEN.getStatusCode());
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
        } catch (ServiceException | IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
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
