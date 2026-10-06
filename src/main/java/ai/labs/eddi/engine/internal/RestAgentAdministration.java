/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IDeploymentStatusReader;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.model.DeploymentImpact;
import ai.labs.eddi.engine.model.DeploymentPreflight;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.secrets.VaultGrantGate;
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
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.*;
import java.util.concurrent.*;

import static ai.labs.eddi.engine.model.Deployment.Status.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestAgentAdministration implements IRestAgentAdministration, IDeploymentStatusReader {
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

    private final ResourceAccessGuard resourceAccessGuard;
    private final SpaceReferenceGuard spaceReferenceGuard;

    /**
     * Field-injected, like {@code AgentFactory}'s, so the test-seam constructors
     * keep their signatures; null there, which reports the check as not run.
     */
    @Inject
    VaultGrantGate vaultGrantGate;

    @Inject
    public RestAgentAdministration(IRuntime runtime, IAgentFactory agentFactory, IAgentStore agentStore, IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore, IRestConversationStore restConversationStore,
            IDocumentDescriptorStore documentDescriptorStore, IDeploymentListener deploymentListener, IScheduleStore scheduleStore,
            TenantQuotaService tenantQuotaService, ResourceAccessGuard resourceAccessGuard, SpaceReferenceGuard spaceReferenceGuard) {
        this.resourceAccessGuard = resourceAccessGuard;
        this.spaceReferenceGuard = spaceReferenceGuard;
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

    /** Without the space-reference check. Test seam. */
    public RestAgentAdministration(IRuntime runtime, IAgentFactory agentFactory, IAgentStore agentStore, IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore, IRestConversationStore restConversationStore,
            IDocumentDescriptorStore documentDescriptorStore, IDeploymentListener deploymentListener, IScheduleStore scheduleStore,
            TenantQuotaService tenantQuotaService, ResourceAccessGuard resourceAccessGuard) {
        this(runtime, agentFactory, agentStore, deploymentStore, conversationMemoryStore, restConversationStore, documentDescriptorStore,
                deploymentListener, scheduleStore, tenantQuotaService, resourceAccessGuard, null);
    }

    @Override
    public Response deployAgent(final Deployment.Environment environment, final String agentId, final Integer version, final Boolean autoDeploy,
                                final Boolean waitForCompletion) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(autoDeploy, "autoDeploy");

        // Deploying is a change to the agent's live behaviour, so it takes EDIT — the
        // same level as editing it. Before the quota gate below, because refusing an
        // unauthorised deploy must not first consume the tenant's agent allowance.
        resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");

        // Checked against the person deploying, on the request thread where their
        // identity is: deployment is what turns a reference to a space's secret into
        // a request that carries it. See SpaceReferenceGuard.
        if (spaceReferenceGuard != null) {
            spaceReferenceGuard.requireMayDeploy(agentId, version);
        }

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
                    log.warn("Deployment wait timed out for Agent " + sanitize(agentId) + " v" + version);
                    deployError = "Deployment timed out";
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // Log full details server-side, expose only safe message to client.
                    //
                    // The MESSAGE is sanitized; the throwable is deliberately not, and
                    // cannot be. quarkus.log.console.format ends in %s%e, so %e renders
                    // the stack trace whose first line is the throwable's own toString()
                    // — a CR/LF in an exception message therefore still reaches the log
                    // through that half. That is not a property of this call site: ~415
                    // log calls in src/main/java pass a throwable, and the only fix that
                    // covers them is a sanitizing log handler or formatter, because
                    // LogSanitizer collapses newlines and would flatten any stack trace
                    // it was pointed at. Dropping the throwable here is not the trade:
                    // the client is told only "Check server logs for details", so this
                    // stack trace is the sole diagnostic a failed deployment leaves.
                    log.warn("Deployment failed for Agent " + sanitize(agentId) + " v" + version + ": "
                            + sanitize(cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
                    deployError = "Deployment failed. Check server logs for details.";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    deployError = "Deployment was interrupted";
                }

                // Return the actual status after waiting
                IAgent deployed = deployedAgent(environment, agentId, version);
                Status status = deployed != null ? deployed.getDeploymentStatus() : NOT_FOUND;
                var responseBody = new LinkedHashMap<String, Object>();
                responseBody.put("status", status.toString());
                responseBody.put("agentId", agentId);
                responseBody.put("version", version);
                responseBody.put("environment", environment.name());
                // Why it failed, when the factory recorded it. Still 200, deliberately:
                // every failed waited deploy has always answered 200 with the outcome in
                // the body, and every caller (the setup API, the MCP tools, the Manager,
                // quarkus-eddi) reads that body. A grant refusal answering 409 instead
                // would give one outcome two shapes. The caller passed EDIT above, so it
                // may see the secret NAMES the failure carries.
                DeploymentFailure failure = status == ERROR && deployed != null ? deployed.getDeploymentFailure() : null;
                if (deployError == null && failure != null) {
                    deployError = failure.message();
                }
                if (deployError != null) {
                    responseBody.put("error", deployError);
                }
                if (failure != null) {
                    responseBody.put("failure", failure);
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
                    String.format("Resource not found. (id=%s, version=%s)", sanitize(agentId), version)));
        } catch (IResourceStore.ResourceStoreException e) {
            // A store outage is not "agent missing" — let the deploy proceed and fail
            // (or succeed) on its own terms rather than reporting a false 404.
            log.warnf("Could not verify that Agent %s v%s exists before deploying: %s",
                    sanitize(agentId), version, e.getMessage());
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
            // A store that could not answer is a 503, not a 429 — same split as the
            // conversation and API-call gates in ConversationService. This one is
            // synchronous, so QuotaAccountingUnavailableExceptionMapper runs and
            // gives it the honest code; without the branch the deploy answered 429
            // quota_exceeded with Retry-After: 60 for an outage the dashboard was
            // already counting on eddi.tenant.quota.unavailable{type=agent}.
            throw result.accountingUnavailable()
                    ? new QuotaAccountingUnavailableException(result.reason())
                    : new QuotaExceededException(result.reason());
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

        // Undeploying takes an agent offline for everyone using it, which is a change
        // to the agent — EDIT, matching deploy. Without this any editor could take down
        // any colleague's live agent.
        resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");

        // Every version this call takes out of service. None of them can be where a
        // conversation moves to: the undeploys run asynchronously, so while the loop
        // checks v5 the record for v6 — undeployed a moment ago in this same call —
        // may still say "deployed".
        final int highestUndeployed = version;
        final int lowestUndeployed = Boolean.TRUE.equals(undeployThisAndAllPreviousAgentVersions) ? 1 : version;
        try {
            // Schedules belong to the agent, not to a version, and a fire starts on
            // whatever version is deployed. They used to be disabled by ANY undeploy —
            // so retiring v5 after deploying v6, the normal way to roll a version out,
            // silently switched every heartbeat of the agent off. Now only when this
            // call leaves no version of the agent deployed here.
            //
            // Uncertainty falls back to the old behaviour, deliberately. deployedVersions
            // swallows a failed lookup, so a failure can only SHRINK the set: an empty or
            // partial set makes this true and the schedules are disabled, exactly as every
            // undeploy did before. The alternative — keep them enabled when a lookup fails
            // — leaves heartbeats firing at an agent that may have no deployed version at
            // all, failing until they dead-letter. Keeping them enabled needs positive
            // evidence: another deployed version actually seen.
            final boolean agentLeavesEnvironment = deployedVersions(environment, agentId).stream()
                    .allMatch(deployed -> deployed >= lowestUndeployed && deployed <= highestUndeployed);
            do {
                Long activeConversationCount = conversationMemoryStore.getActiveConversationCount(agentId, version);
                Integer successor = activeConversationCount > 0
                        ? compatibleDeployedVersion(environment, agentId, version, lowestUndeployed, highestUndeployed)
                        : null;
                if (successor != null) {
                    // Nothing to end and nothing to refuse: these conversations move to the
                    // compatible version on their next turn.
                    log.infof("%d active conversation(s) of Agent %s v%d continue on compatible v%d", activeConversationCount,
                            sanitize(agentId), version, successor);
                } else if (activeConversationCount > 0) {
                    if (endAllActiveConversations) {
                        var activeConversations = restConversationStore.getActiveConversations(agentId, version);
                        // Ending continues past a failed conversation and reports it in
                        // a 500; do not undeploy on top of conversations still open.
                        var endResponse = restConversationStore.endActiveConversations(activeConversations,
                                IConversationService.END_REASON_AGENT_VERSION_RETIRED);
                        if (endResponse != null && endResponse.getStatus() >= 300) {
                            throw new IllegalStateException(String.format(
                                    "Could not end every active conversation of agent %s (version %s) — not undeploying",
                                    sanitize(agentId), version));
                        }
                    } else {
                        var message = getConflictExplanations(agentId, version, activeConversationCount);
                        return Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build();
                    }
                }

                undeploy(environment, agentId, version, agentLeavesEnvironment);
                log.info(String.format("Successfully undeployed Agent (agentId=%s, agentVersion=%s, environment=%s)", sanitize(agentId), version,
                        environment));
            } while (undeployThisAndAllPreviousAgentVersions && version-- > 1);

            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    /**
     * A deployed version outside {@code [lowestUndeployed, highestUndeployed]} with
     * the same compatibility generation as {@code version} — somewhere its
     * conversations can move — or {@code null} when there is none.
     * <p>
     * "Deployed" is the union of the deployment records, which every node follows,
     * and this node's own registry, which also holds deployments made with
     * {@code autoDeploy=false} that are never recorded. Anything that cannot be
     * read answers {@code null}: the undeploy then behaves as it always did — 409,
     * or end the conversations — rather than stranding them on a version that is
     * gone.
     */
    Integer compatibleDeployedVersion(Deployment.Environment environment, String agentId, int version, int lowestUndeployed,
                                      int highestUndeployed) {
        Integer generation = generationOf(agentId, version);
        if (generation == null) {
            return null;
        }
        for (Integer other : deployedVersions(environment, agentId)) {
            if ((other < lowestUndeployed || other > highestUndeployed) && generation.equals(generationOf(agentId, other))) {
                return other;
            }
        }
        return null;
    }

    /**
     * Every deployed version of the agent in {@code environment}, highest first.
     */
    private SortedSet<Integer> deployedVersions(Deployment.Environment environment, String agentId) {
        SortedSet<Integer> versions = new TreeSet<>(Comparator.reverseOrder());
        try {
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (agentId.equals(info.getAgentId()) && info.getEnvironment() == environment && info.getAgentVersion() != null) {
                    versions.add(info.getAgentVersion());
                }
            }
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            log.warnf("Could not read the deployment records of Agent %s: %s", sanitize(agentId), sanitize(e.getMessage()));
        }
        try {
            for (IAgent agent : agentFactory.getAllDeployedAgents(environment)) {
                if (agentId.equals(agent.getAgentId()) && agent.getDeploymentStatus() == READY && agent.getAgentVersion() != null) {
                    versions.add(agent.getAgentVersion());
                }
            }
        } catch (ServiceException | RuntimeException e) {
            log.warnf("Could not list the running versions of Agent %s: %s", sanitize(agentId), sanitize(e.getMessage()));
        }
        return versions;
    }

    /**
     * The version's compatibility generation, or {@code null} when it has none or
     * cannot be read.
     */
    private Integer generationOf(String agentId, Integer version) {
        try {
            var configuration = agentStore.read(agentId, version);
            return configuration != null ? configuration.getCompatibilityGeneration() : null;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException | RuntimeException e) {
            log.debugf("No compatibility generation for Agent %s v%s: %s", sanitize(agentId), version, sanitize(e.getMessage()));
            return null;
        }
    }

    @Override
    public DeploymentImpact getDeploymentImpact(Deployment.Environment environment, String agentId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");
        // Conversation counts are an operator's view of the agent: the same EDIT the
        // undeploy that ends them takes.
        resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
        requireAgentExists(agentId, version);

        Integer generation = generationOf(agentId, version);
        List<DeploymentImpact.VersionImpact> impacts = new ArrayList<>();
        for (Integer deployed : deployedVersions(environment, agentId)) {
            if (deployed.equals(version)) {
                continue;
            }
            Integer deployedGeneration = generationOf(agentId, deployed);
            boolean follows = generation != null && generation.equals(deployedGeneration) && deployed < version;
            Long active = conversationMemoryStore.getActiveConversationCount(agentId, deployed);
            impacts.add(new DeploymentImpact.VersionImpact(deployed, deployedGeneration, active != null ? active : 0L,
                    follows ? DeploymentImpact.Outcome.FOLLOW : DeploymentImpact.Outcome.STAY));
        }
        return new DeploymentImpact(agentId, version, generation, impacts);
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

    private void undeploy(Deployment.Environment environment, String agentId, Integer version, boolean disableSchedules) {
        Callable<Void> undeployAgentCallable = () -> {
            try {
                agentFactory.undeployAgent(environment, agentId, version);
                deploymentStore.setDeploymentInfo(environment.toString(), agentId, version, DeploymentInfo.DeploymentStatus.undeployed);

                // Lifecycle hook: auto-disable the agent's schedules once no version of
                // it is left to run them — see undeployAgent.
                if (disableSchedules) {
                    disableSchedulesForAgent(agentId);
                }
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

        IAgent deployed = deployedAgent(environment, agentId, version);
        String status = (deployed != null ? deployed.getDeploymentStatus() : NOT_FOUND).toString();

        if ("text".equalsIgnoreCase(format)) {
            return Response.ok(status, MediaType.TEXT_PLAIN).build();
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("status", status);
        // The failure names secrets (never values) and the agent's grant situation, so
        // it goes only to someone who may edit, and so deploy, the agent. This
        // endpoint itself is open to any editor, for any agent id.
        if ("detailed".equalsIgnoreCase(format) && deployed != null && deployed.getDeploymentStatus() == ERROR
                && deployed.getDeploymentFailure() != null && resourceAccessGuard.hasAccess(agentId, AccessLevel.EDIT)) {
            body.put("failure", deployed.getDeploymentFailure());
        }
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }

    @Override
    public DeploymentPreflight preflightDeployment(Deployment.Environment environment, String agentId, Integer version) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(agentId, "agentId");
        RuntimeUtilities.checkNotNull(version, "version");
        // EDIT, like the deploy it previews: the answer describes the agent's grants.
        resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
        requireAgentExists(agentId, version);

        if (vaultGrantGate == null) {
            return new DeploymentPreflight(agentId, version, VaultGrantGate.Mode.OFF.name(), false, true, List.of());
        }
        VaultGrantGate.GrantCheck check = vaultGrantGate.check(agentId, version);
        boolean admin = resourceAccessGuard.isAdmin();
        List<DeploymentPreflight.GrantIssue> issues = new ArrayList<>();
        for (String reference : check.ungranted()) {
            var parsed = VaultGrantGate.parseVaultReference(reference);
            var grant = parsed != null ? vaultGrantGate.grantOf(reference) : null;
            List<String> allowed = grant != null ? grant.allowedAgents() : null;
            issues.add(new DeploymentPreflight.GrantIssue(parsed != null ? parsed.tenantId() : null, parsed != null ? parsed.keyName() : null,
                    reference, false, allowed != null ? allowed.size() : null, admin && allowed != null ? List.copyOf(allowed) : null));
        }
        return new DeploymentPreflight(agentId, version, check.mode().name(), check.checked(), !check.blocked(), issues);
    }

    /**
     * The caller's view of the deployed-agent set: only the agents they may
     * {@link AccessLevel#USE}, each descriptor redacted for them.
     * <p>
     * This backs {@code GET /administration/{env}/deploymentstatus} and,
     * in-process, the MCP {@code list_agents} and {@code discover_agents} tools —
     * all of them reachable by a viewer. It used to serialise every deployed
     * agent's raw descriptor, grant list and access index included, which is the
     * audience of a private share disclosed to anybody who asked, and with
     * workspaces enforced it listed agents the caller could not even start a
     * conversation with.
     * <p>
     * Redaction applies with workspaces off as well: the grant list is recorded
     * whenever authentication is on, and
     * {@link ResourceAccessGuard#redactUnlessOwner} answers the owner-or-admin
     * question structurally in that state. Engine code that needs the full set
     * reads {@link #readAllDeploymentStatuses} instead.
     */
    @Override
    public List<AgentDeploymentStatus> getDeploymentStatuses(Deployment.Environment environment) {
        List<AgentDeploymentStatus> visible = new LinkedList<>();
        for (AgentDeploymentStatus status : readAllDeploymentStatuses(environment)) {
            // Decided against the CURRENT descriptor, not the deployed version's: sharing
            // writes land on the current version, so an older one can carry a previous
            // era's owner and grants.
            AccessLevel level = resourceAccessGuard.currentLevel(status.getAgentId());
            if (level == null || !level.includes(AccessLevel.USE)) {
                continue;
            }
            resourceAccessGuard.redactUnlessOwner(status.getDescriptor(), level);
            visible.add(status);
        }
        return visible;
    }

    @Override
    public List<AgentDeploymentStatus> readAllDeploymentStatuses(Deployment.Environment environment) {
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
        IAgent agent = deployedAgent(environment, agentId, version);
        return agent != null ? agent.getDeploymentStatus() : NOT_FOUND;
    }

    /**
     * The registry entry for this version, carrying status and failure, or null.
     */
    private IAgent deployedAgent(Deployment.Environment environment, String agentId, Integer version) {
        try {
            return agentFactory.getAgent(environment, agentId, version);
        } catch (ServiceException e) {
            throwError(agentId, version, e, "Error while deploying agent! (agentId=%s , version=%s)");
            return null;
        }
    }

    private Status throwError(String agentId, Integer version, ServiceException e, String message) {
        message = String.format(message, sanitize(agentId), version);
        log.error(message, e);
        throw sneakyThrow(e);
    }

    private Void throwErrorForbidden(String agentId, Integer version, IllegalAccessException e) {
        String message = "Agent deployment is currently in progress! (agentId=%s , version=%s)";
        message = String.format(message, sanitize(agentId), version);
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
                    log.infof("[SCHEDULE] Auto-enabled schedule '%s' (id=%s) on Agent %s deploy", sanitize(schedule.getName()),
                            sanitize(schedule.getId()), sanitize(agentId));
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-enable schedules for Agent %s (non-fatal)", sanitize(agentId));
        }
    }

    private void disableSchedulesForAgent(String agentId) {
        try {
            var schedules = scheduleStore.readSchedulesByAgentId(agentId);
            for (var schedule : schedules) {
                if (schedule.isEnabled()) {
                    scheduleStore.setScheduleEnabled(schedule.getId(), false, null);
                    log.infof("[SCHEDULE] Auto-disabled schedule '%s' (id=%s) on Agent %s undeploy", sanitize(schedule.getName()),
                            sanitize(schedule.getId()), sanitize(agentId));
                }
            }
        } catch (Exception e) {
            log.warnf(e, "[SCHEDULE] Failed to auto-disable schedules for Agent %s (non-fatal)", sanitize(agentId));
        }
    }
}
