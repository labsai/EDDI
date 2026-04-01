package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
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
public class RestAgentAdministration implements IRestAgentAdministration {
    private final IAgentFactory agentFactory;
    private final IDeploymentStore deploymentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRestConversationStore restConversationStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IDeploymentListener deploymentListener;
    private final IScheduleStore scheduleStore;
    private final IRuntime runtime;

    private static final Logger log = Logger.getLogger(RestAgentAdministration.class);

    @Inject
    public RestAgentAdministration(IRuntime runtime, IAgentFactory agentFactory, IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore, IRestConversationStore restConversationStore,
            IDocumentDescriptorStore documentDescriptorStore, IDeploymentListener deploymentListener, IScheduleStore scheduleStore) {
        this.runtime = runtime;
        this.agentFactory = agentFactory;
        this.deploymentStore = deploymentStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.restConversationStore = restConversationStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.deploymentListener = deploymentListener;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public Response deployAgent(final Deployment.Environment environment, final String agentId, final Integer version, final Boolean autoDeploy,
                                final Boolean waitForCompletion) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(autoDeploy, "autoDeploy");

        try {
            Future<Void> deployFuture = deploy(environment, agentId, version, autoDeploy);

            boolean shouldWait = waitForCompletion != null && waitForCompletion;
            if (shouldWait) {
                String deployError = null;
                try {
                    deployFuture.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("Deployment wait timed out for Agent " + agentId + " v" + version);
                    deployError = "Deployment timed out";
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // Log full details server-side, expose only safe message to client
                    log.warn("Deployment failed for Agent " + agentId + " v" + version + ": " + (cause != null ? cause.getMessage() : e.getMessage()),
                            cause != null ? cause : e);
                    deployError = "Deployment failed. Check server logs for details.";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    deployError = "Deployment was interrupted";
                }

                // Return the actual status after waiting
                Status status = checkDeploymentStatus(environment, agentId, version);
                var responseBody = new java.util.LinkedHashMap<String, Object>();
                responseBody.put("status", status.toString());
                responseBody.put("agentId", agentId);
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

    private Future<Void> deploy(final Deployment.Environment environment, final String agentId, final Integer version, final Boolean autoDeploy) {
        Callable<Void> deployAgentCallable = () -> {
            try {
                if (EnumSet.of(NOT_FOUND, ERROR).contains(checkDeploymentStatus(environment, agentId, version))) {
                    agentFactory.deployAgent(environment, agentId, version, status -> {
                        if (status == READY && autoDeploy) {
                            deploymentStore.setDeploymentInfo(environment.toString(), agentId, version, DeploymentInfo.DeploymentStatus.deployed);
                        }
                    });
                }

                deploymentListener.onDeploymentEvent(new DeploymentEvent(agentId, version, environment, READY));

                // Lifecycle hook: auto-enable schedules for this agent
                enableSchedulesForAgent(agentId);

            } catch (Exception e) {
                handleDeploymentException(e, agentId, version, environment);
            }

            return null;
        };

        return runtime.submitCallable(deployAgentCallable, ThreadContext.getResources());
    }

    private void handleDeploymentException(Exception e, String agentId, Integer version, Deployment.Environment environment) {
        deploymentListener.onDeploymentEvent(new DeploymentEvent(agentId, version, environment, ERROR));

        if (e instanceof ServiceException) {
            throwError(agentId, version, (ServiceException) e, "Error while deploying agent! (agentId=%s , version=%s)");
        } else if (e instanceof IllegalAccessException) {
            throwErrorForbidden(agentId, version, (IllegalAccessException) e);
        } else {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response undeployAgent(Deployment.Environment environment, String agentId, Integer version, Boolean endAllActiveConversations,
                                  Boolean undeployThisAndAllPreviousAgentVersions) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");

        try {
            do {
                Long activeConversationCount = conversationMemoryStore.getActiveConversationCount(agentId, version);
                if (activeConversationCount > 0) {
                    if (endAllActiveConversations) {
                        var activeConversations = restConversationStore.getActiveConversations(agentId, version);
                        restConversationStore.endActiveConversations(activeConversations);
                    } else {
                        var message = getConflictExplanations(agentId, version, activeConversationCount);
                        return Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build();
                    }
                }

                undeploy(environment, agentId, version);
                log.info(String.format("Successfully undeployed Agent (agentId=%s, agentVersion=%s, environment=%s)", agentId, version, environment));
            } while (undeployThisAndAllPreviousAgentVersions && version-- > 1);

            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private static String getConflictExplanations(String agentId, Integer version, Long activeConversationCount) {
        var message = """
                %s active (thus not ENDED) conversation(s) going on with this agent!\

                Check GET /conversationstore/conversations/active/%s?agentVersion=%s \
                to see active conversations and end conversations with \
                POST /conversationstore/conversations/end , \
                providing the list you receive with GET\

                In order to end all active conversations, the query param 'endAllActiveConversations' \
                can be set to true.""";
        message = String.format(message, activeConversationCount, agentId, version);
        return message;
    }

    private void undeploy(Deployment.Environment environment, String agentId, Integer version) {
        Callable<Void> undeployAgentCallable = () -> {
            try {
                agentFactory.undeployAgent(environment, agentId, version);
                deploymentStore.setDeploymentInfo(environment.toString(), agentId, version, DeploymentInfo.DeploymentStatus.undeployed);

                // Lifecycle hook: auto-disable schedules for this agent
                disableSchedulesForAgent(agentId);
            } catch (ServiceException e) {
                throwError(agentId, version, e, "Error while undeploying agent! (agentId=%s , version=%s)");
            } catch (IllegalAccessException e) {
                return throwErrorForbidden(agentId, version, e);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }

            return null;
        };

        runtime.submitCallable(undeployAgentCallable, ThreadContext.getResources());
    }

    @Override
    public Response getDeploymentStatus(Deployment.Environment environment, String agentId, Integer version, String format) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");

        String status = checkDeploymentStatus(environment, agentId, version).toString();

        if ("text".equalsIgnoreCase(format)) {
            return Response.ok(status, MediaType.TEXT_PLAIN).build();
        }

        return Response.ok(Map.of("status", status), MediaType.APPLICATION_JSON).build();
    }

    @Override
    public List<AgentDeploymentStatus> getDeploymentStatuses(Deployment.Environment environment) {
        RuntimeUtilities.checkNotNull(environment, "environment");

        try {
            List<AgentDeploymentStatus> agentDeploymentStatuses = new LinkedList<>();
            for (IAgent latestAgent : agentFactory.getAllLatestAgents(environment)) {
                var agentId = latestAgent.getAgentId();
                var agentVersion = latestAgent.getAgentVersion();
                var documentDescriptor = documentDescriptorStore.readDescriptor(agentId, agentVersion);
                agentDeploymentStatuses
                        .add(new AgentDeploymentStatus(environment, agentId, agentVersion, latestAgent.getDeploymentStatus(), documentDescriptor));
            }

            agentDeploymentStatuses.sort(Comparator.comparing(o -> o.getDescriptor().getLastModifiedOn()));
            Collections.reverse(agentDeploymentStatuses);

            return agentDeploymentStatuses;
        } catch (ServiceException | IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Status checkDeploymentStatus(Deployment.Environment environment, String agentId, Integer version) {
        try {
            IAgent agent = agentFactory.getAgent(environment, agentId, version);
            return agent != null ? agent.getDeploymentStatus() : NOT_FOUND;
        } catch (ServiceException e) {
            return throwError(agentId, version, e, "Error while deploying agent! (agentId=%s , version=%s)");
        }
    }

    private Status throwError(String agentId, Integer version, ServiceException e, String message) {
        message = String.format(message, agentId, version);
        log.error(message, e);
        throw sneakyThrow(e);
    }

    private Void throwErrorForbidden(String agentId, Integer version, IllegalAccessException e) {
        String message = "Agent deployment is currently in progress! (agentId=%s , version=%s)";
        message = String.format(message, agentId, version);
        log.error(message, e);
        throw new WebApplicationException(new Throwable(message), Response.Status.FORBIDDEN.getStatusCode());
    }

    // --- Schedule Lifecycle Hooks ---

    private void enableSchedulesForAgent(String agentId) {
        try {
            var schedules = scheduleStore.readSchedulesByAgentId(agentId);
            for (var schedule : schedules) {
                if (!schedule.isEnabled()) {
                    var nextFire = schedule.getNextFire() != null ? schedule.getNextFire() : java.time.Instant.now();
                    scheduleStore.setScheduleEnabled(schedule.getId(), true, nextFire);
                    log.infof("[SCHEDULE] Auto-enabled schedule '%s' (id=%s) on Agent %s deploy", schedule.getName(), schedule.getId(), agentId);
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-enable schedules for Agent %s (non-fatal)", agentId);
        }
    }

    private void disableSchedulesForAgent(String agentId) {
        try {
            var schedules = scheduleStore.readSchedulesByAgentId(agentId);
            for (var schedule : schedules) {
                if (schedule.isEnabled()) {
                    scheduleStore.setScheduleEnabled(schedule.getId(), false, null);
                    log.infof("[SCHEDULE] Auto-disabled schedule '%s' (id=%s) on Agent %s undeploy", schedule.getName(), schedule.getId(), agentId);
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-disable schedules for Agent %s (non-fatal)", agentId);
        }
    }
}
