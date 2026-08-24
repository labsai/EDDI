/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
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
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RuntimeUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
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
    private final IAgentStore agentStore;
    private final IDeploymentStore deploymentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRestConversationStore restConversationStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IDeploymentListener deploymentListener;
    private final IScheduleStore scheduleStore;
    private final IRuntime runtime;
    private final TenantQuotaService tenantQuotaService;

    private static final Logger log = Logger.getLogger(RestAgentAdministration.class);

    @Inject
    public RestAgentAdministration(IRuntime runtime, IAgentFactory agentFactory, IAgentStore agentStore, IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore, IRestConversationStore restConversationStore,
            IDocumentDescriptorStore documentDescriptorStore, IDeploymentListener deploymentListener, IScheduleStore scheduleStore,
            TenantQuotaService tenantQuotaService) {
        this.runtime = runtime;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.tenantQuotaService = tenantQuotaService;
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

        // MUST sit before the try below, for the same reason as the quota gate: the
        // catch(Exception) there rethrows as InternalServerErrorException, which
        // would turn this 404 into a 500.
        requireAgentExists(agentId, version);

        // MUST sit before the try below: the catch(Exception) there rethrows as
        // InternalServerErrorException, which would turn the mapper's 429 into a 500.
        // It must also stay on the request thread — anything inside the submitted
        // Callable runs on the runtime executor and can never produce a status code.
        enforceAgentQuota(environment, agentId);

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
                var responseBody = new LinkedHashMap<String, Object>();
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

    /**
     * Rejects a deploy of an agent that does not exist, with the 404 the endpoint
     * has always advertised.
     * <p>
     * Without this the asynchronous path answers {@code 202 Accepted} for any id at
     * all — a typo'd or already-deleted agent included. The deployment then fails
     * on the runtime executor, where no status code can reach the caller, so the
     * only signal is a line in the server log. Everything about the response says
     * the deploy was taken: a CI pipeline, the Manager and the setup API alike read
     * 202 as success and move on to start a conversation that can never exist.
     * <p>
     * Checked against the agent store rather than the deployment status, because
     * {@code checkDeploymentStatus} answers {@code NOT_FOUND} for a perfectly valid
     * agent that simply has not been deployed yet — which is the normal case here.
     *
     * @throws IResourceStore.ResourceNotFoundException
     *             mapped to 404 by {@code ResourceNotFoundExceptionMapper}
     */
    private void requireAgentExists(String agentId, Integer version) {
        try {
            agentStore.read(agentId, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IllegalArgumentException e) {
            // An id the datastore cannot even parse — the MongoDB driver rejects a
            // non-hex or wrong-length id with "state should be: hexString has 24
            // characters" before any lookup happens. That is still "there is no such
            // agent", and answering with the driver's sentence would both mislead
            // (the caller's mistake was the id, not its hex-ness) and leak which
            // datastore is behind the API.
            throw sneakyThrow(new IResourceStore.ResourceNotFoundException(
                    String.format("Resource not found. (id=%s, version=%s)", LogSanitizer.sanitize(agentId), version)));
        } catch (IResourceStore.ResourceStoreException e) {
            // A store outage is not "agent missing" — let the deploy proceed and fail
            // (or succeed) on its own terms rather than reporting a false 404.
            log.warnf("Could not verify that Agent %s v%s exists before deploying: %s", agentId, version, e.getMessage());
        }
    }

    /**
     * Reject a deployment that would push the tenant past
     * {@code maxAgentsPerTenant}.
     *
     * <p>
     * Counts <em>distinct agent ids</em>, so redeploying an agent or bumping its
     * version is always free — necessary because the old-version undeploy sweep
     * legitimately keeps two versions of one agent deployed while the previous one
     * drains.
     * </p>
     *
     * <p>
     * The count unions two sources, and needs both. Persisted {@code deployed} rows
     * are the source of truth across restarts, but they are only written when
     * {@code autoDeploy=true} — while the in-memory deploy is unconditional (see
     * {@link #deploy}). Counting rows alone would therefore let a caller deploy
     * unlimited agents with {@code autoDeploy=false}, and those agents are
     * genuinely live: {@code getLatestReadyAgent} serves them without ever
     * consulting the deployment store, and a lazy re-deploy re-materialises them
     * after a restart. Counting live agents alone would be per-JVM and would miss
     * deployments owned by other cluster nodes.
     * </p>
     *
     * <p>
     * Fails <em>open</em>: a store outage must not block deployments. The denial is
     * logged and metered either way.
     * </p>
     *
     * <p>
     * <strong>Known limit — bounded overshoot under concurrency.</strong> The count
     * is read, checked, and only then acted on, with no lock spanning the read and
     * the eventual write. Concurrent deploys that all observe {@code count == limit
     * - 1} will all pass, so the cap can be exceeded by up to the number of
     * simultaneous requests. This does <em>not</em> heal on its own: once over, the
     * gate simply refuses further deploys until an undeploy brings the count back
     * down.
     * </p>
     *
     * <p>
     * A per-tenant lock is deliberately not used, because it would only serialize
     * within one JVM while the count spans the shared deployment store and every
     * node's in-memory registry — giving the appearance of a hard guarantee in
     * exactly the clustered deployments where it would not hold. A real guarantee
     * needs a distributed lock or a storage-level constraint, and there is no
     * single row to constrain since the count is derived from two sources. The
     * overshoot is accepted instead: deploys are rare, human- or agent-initiated
     * admin operations rather than a hot path, and the gate's purpose — stopping
     * runaway growth such as an LLM creating sub-agents in a loop — survives a
     * small transient overrun.
     * </p>
     */
    private void enforceAgentQuota(Deployment.Environment environment, String agentId) {
        Set<String> deployedAgentIds = new HashSet<>();

        try {
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (info.getAgentId() != null) {
                    deployedAgentIds.add(info.getAgentId());
                }
            }

            for (Deployment.Environment env : Deployment.Environment.values()) {
                for (IAgent agent : agentFactory.getAllLatestAgents(env)) {
                    if (agent.getAgentId() != null && agent.getDeploymentStatus() == READY) {
                        deployedAgentIds.add(agent.getAgentId());
                    }
                }
            }
        } catch (Exception e) {
            log.warnf("Agent quota check: could not determine the deployed-agent count, allowing deploy of %s: %s", agentId, e.getMessage());
            return;
        }

        // Redeploys and version bumps of an already-counted agent are always allowed.
        if (deployedAgentIds.contains(agentId)) {
            return;
        }

        var result = tenantQuotaService.checkAgentQuota(tenantQuotaService.getDefaultTenantId(), deployedAgentIds.size());
        if (!result.allowed()) {
            log.warnf("Denying deployment of Agent %s to %s: %s", agentId, environment, result.reason());
            throw new QuotaExceededException(result.reason());
        }
    }

    private Future<Void> deploy(final Deployment.Environment environment, final String agentId, final Integer version, final Boolean autoDeploy) {
        // Register BEFORE the deployment starts, so a concurrent getAgent that finds
        // the agent IN_PROGRESS has a future to await.
        //
        // This is what makes AgentFactory's wait machinery reachable at all. Only
        // RestImportService ever registered, so for every ordinary deploy
        // getRegisteredDeploymentEvent returned null, waitForDeploymentCompletion had
        // nothing to await, and a caller racing a deployment simply got a null agent.
        // Registration must precede agentFactory.deployAgent: that call is what
        // publishes the IN_PROGRESS placeholder a waiter can observe, so registering
        // afterwards would leave exactly the window this closes.
        deploymentListener.registerAgentDeployment(agentId, version);

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
                    var nextFire = schedule.getNextFire() != null ? schedule.getNextFire() : Instant.now();
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
