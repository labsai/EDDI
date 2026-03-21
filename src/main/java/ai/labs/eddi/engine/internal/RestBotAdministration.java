package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IRestBotAdministration;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.BotDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.utils.RuntimeUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.util.*;
import java.util.concurrent.*;

import static ai.labs.eddi.engine.model.Deployment.Status.*;

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
    private final IDeploymentListener deploymentListener;
    private final IScheduleStore scheduleStore;
    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(RestBotAdministration.class);

    @Inject
    public RestBotAdministration(IRuntime runtime,
            IBotFactory botFactory,
            IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore,
            IRestConversationStore restConversationStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IDeploymentListener deploymentListener,
            IScheduleStore scheduleStore) {
        this.runtime = runtime;
        this.botFactory = botFactory;
        this.deploymentStore = deploymentStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.restConversationStore = restConversationStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.deploymentListener = deploymentListener;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public Response deployBot(final Deployment.Environment environment,
            final String botId, final Integer version, final Boolean autoDeploy,
            final Boolean waitForCompletion) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(autoDeploy, "autoDeploy");

        try {
            Future<Void> deployFuture = deploy(environment, botId, version, autoDeploy);

            boolean shouldWait = waitForCompletion != null && waitForCompletion;
            if (shouldWait) {
                String deployError = null;
                try {
                    deployFuture.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("Deployment wait timed out for bot " + botId + " v" + version);
                    deployError = "Deployment timed out";
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // Log full details server-side, expose only safe message to client
                    log.warn("Deployment failed for bot " + botId + " v" + version +
                            ": " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
                    deployError = "Deployment failed. Check server logs for details.";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    deployError = "Deployment was interrupted";
                }

                // Return the actual status after waiting
                Status status = checkDeploymentStatus(environment, botId, version);
                var responseBody = new java.util.LinkedHashMap<String, Object>();
                responseBody.put("status", status.toString());
                responseBody.put("botId", botId);
                responseBody.put("version", version);
                responseBody.put("environment", environment.name());
                if (deployError != null) {
                    responseBody.put("error", deployError);
                }
                return Response.ok(responseBody, MediaType.APPLICATION_JSON).build();
            }

            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Future<Void> deploy(final Deployment.Environment environment,
            final String botId, final Integer version, final Boolean autoDeploy) {
        Callable<Void> deployBot = () -> {
            try {
                if (EnumSet.of(NOT_FOUND, ERROR).contains(checkDeploymentStatus(environment, botId, version))) {
                    botFactory.deployBot(environment, botId, version,
                            status -> {
                                if (status == READY && autoDeploy) {
                                    deploymentStore.setDeploymentInfo(environment.toString(),
                                            botId, version, DeploymentInfo.DeploymentStatus.deployed);
                                }
                            });
                }

                deploymentListener.onDeploymentEvent(
                        new DeploymentEvent(botId, version, environment, READY));

                // Lifecycle hook: auto-enable schedules for this bot
                enableSchedulesForBot(botId);

            } catch (Exception e) {
                handleDeploymentException(e, botId, version, environment);
            }

            return null;
        };

        return runtime.submitCallable(deployBot, ThreadContext.getResources());
    }

    private void handleDeploymentException(Exception e, String botId, Integer version,
            Deployment.Environment environment) {
        deploymentListener.onDeploymentEvent(new DeploymentEvent(botId, version, environment, ERROR));

        if (e instanceof ServiceException) {
            throwError(botId, version, (ServiceException) e,
                    "Error while deploying bot! (botId=%s , version=%s)");
        } else if (e instanceof IllegalAccessException) {
            throwErrorForbidden(botId, version, (IllegalAccessException) e);
        } else {
            throw sneakyThrow(e);
        }
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
                        var message = getConflictExplanations(botId, version, activeConversationCount);
                        return Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN)
                                .build();
                    }
                }

                undeploy(environment, botId, version);
                log.info(String.format("Successfully undeployed bot (botId=%s, botVersion=%s, environment=%s)", botId,
                        version, environment));
            } while (undeployThisAndAllPreviousBotVersions && version-- > 1);

            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private static String getConflictExplanations(String botId, Integer version, Long activeConversationCount) {
        var message = """
                %s active (thus not ENDED) conversation(s) going on with this bot!\

                Check GET /conversationstore/conversations/active/%s?botVersion=%s \
                to see active conversations and end conversations with \
                POST /conversationstore/conversations/end , \
                providing the list you receive with GET\

                In order to end all active conversations, the query param 'endAllActiveConversations' \
                can be set to true.""";
        message = String.format(message, activeConversationCount, botId, version);
        return message;
    }

    private void undeploy(Deployment.Environment environment, String botId, Integer version) {
        Callable<Void> undeployBot = () -> {
            try {
                botFactory.undeployBot(environment, botId, version);
                deploymentStore.setDeploymentInfo(environment.toString(),
                        botId, version, DeploymentInfo.DeploymentStatus.undeployed);

                // Lifecycle hook: auto-disable schedules for this bot
                disableSchedulesForBot(botId);
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

    @Override
    public Response getDeploymentStatus(Deployment.Environment environment,
            String botId,
            Integer version,
            String format) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(version, "version");

        String status = checkDeploymentStatus(environment, botId, version).toString();

        if ("text".equalsIgnoreCase(format)) {
            return Response.ok(status, MediaType.TEXT_PLAIN).build();
        }

        return Response.ok(Map.of("status", status), MediaType.APPLICATION_JSON).build();
    }

    @Override
    public List<BotDeploymentStatus> getDeploymentStatuses(Deployment.Environment environment) {
        RuntimeUtilities.checkNotNull(environment, "environment");

        try {
            List<BotDeploymentStatus> botDeploymentStatuses = new LinkedList<>();
            for (IBot latestBot : botFactory.getAllLatestBots(environment)) {
                var botId = latestBot.getBotId();
                var botVersion = latestBot.getBotVersion();
                var documentDescriptor = documentDescriptorStore.readDescriptor(botId, botVersion);
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
        } catch (ServiceException | IResourceStore.ResourceStoreException
                | IResourceStore.ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Status checkDeploymentStatus(Deployment.Environment environment, String botId, Integer version) {
        try {
            IBot bot = botFactory.getBot(environment, botId, version);
            return bot != null ? bot.getDeploymentStatus() : NOT_FOUND;
        } catch (ServiceException e) {
            return throwError(botId, version, e, "Error while deploying bot! (botId=%s , version=%s)");
        }
    }

    private Status throwError(String botId, Integer version, ServiceException e, String message) {
        message = String.format(message, botId, version);
        log.error(message, e);
        throw sneakyThrow(e);
    }

    private Void throwErrorForbidden(String botId, Integer version, IllegalAccessException e) {
        String message = "Bot deployment is currently in progress! (botId=%s , version=%s)";
        message = String.format(message, botId, version);
        log.error(message, e);
        throw new WebApplicationException(new Throwable(message), Response.Status.FORBIDDEN.getStatusCode());
    }

    // --- Schedule Lifecycle Hooks ---

    private void enableSchedulesForBot(String botId) {
        try {
            var schedules = scheduleStore.readSchedulesByBotId(botId);
            for (var schedule : schedules) {
                if (!schedule.isEnabled()) {
                    var nextFire = schedule.getNextFire() != null
                            ? schedule.getNextFire()
                            : java.time.Instant.now();
                    scheduleStore.setScheduleEnabled(schedule.getId(), true, nextFire);
                    log.infof("[SCHEDULE] Auto-enabled schedule '%s' (id=%s) on bot %s deploy",
                            schedule.getName(), schedule.getId(), botId);
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-enable schedules for bot %s (non-fatal)", botId);
        }
    }

    private void disableSchedulesForBot(String botId) {
        try {
            var schedules = scheduleStore.readSchedulesByBotId(botId);
            for (var schedule : schedules) {
                if (schedule.isEnabled()) {
                    scheduleStore.setScheduleEnabled(schedule.getId(), false, null);
                    log.infof("[SCHEDULE] Auto-disabled schedule '%s' (id=%s) on bot %s undeploy",
                            schedule.getName(), schedule.getId(), botId);
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-disable schedules for bot %s (non-fatal)", botId);
        }
    }
}
