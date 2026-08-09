/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.undeployed;
import static ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import static ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.DAYS;

/**
 * @author ginccc
 */

@Startup
@Priority(4000)
@ApplicationScoped
public class AgentDeploymentManagement implements IAgentDeploymentManagement {
    private final IDeploymentStore deploymentStore;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IMigrationManager migrationManager;
    private final V6RenameMigration v6RenameMigration;
    private final V6QuteMigration v6QuteMigration;
    private final ChannelConnectorMigration channelConnectorMigration;
    private final IAgentsReadiness agentsReadiness;
    private final IRuntime runtime;
    private final IWorkflowStore workflowStore;
    private final IRuleSetStore ruleSetStore;
    private final int maximumLifeTimeOfIdleConversationsInDays;
    private Instant lastDeploymentCheck = null;
    private static final Logger LOGGER = Logger.getLogger(AgentDeploymentManagement.class);
    /**
     * The deployment records this instance has already handed to the factory.
     * <p>
     * Concurrent, not a plain {@code LinkedList}: {@link #checkDeployments()} runs
     * both on the Quarkus scheduler (every 10s, and {@code @Scheduled} defaults to
     * {@code ConcurrentExecution.PROCEED}, so a slow pass can overlap the next one)
     * and on the runtime executor at startup via {@link #autoDeployAgents()}.
     * <p>
     * Rebuilt from each poll rather than appended to forever:
     * {@link DeploymentInfo} equality ignores the status, so an entry left behind
     * by an undeployed agent used to suppress its redeployment permanently.
     */
    private final Set<DeploymentInfo> deploymentInfos = ConcurrentHashMap.newKeySet();

    @Inject
    public AgentDeploymentManagement(IDeploymentStore deploymentStore, IAgentFactory agentFactory, IAgentStore agentStore,
            IAgentsReadiness agentsReadiness, IConversationMemoryStore conversationMemoryStore, IDocumentDescriptorStore documentDescriptorStore,
            IMigrationManager migrationManager, V6RenameMigration v6RenameMigration, V6QuteMigration v6QuteMigration,
            ChannelConnectorMigration channelConnectorMigration, IRuntime runtime, IWorkflowStore workflowStore, IRuleSetStore ruleSetStore,
            @ConfigProperty(name = "eddi.conversations.maximumLifeTimeOfIdleConversationsInDays") int maximumLifeTimeOfIdleConversationsInDays) {
        this.deploymentStore = deploymentStore;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.agentsReadiness = agentsReadiness;
        this.conversationMemoryStore = conversationMemoryStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.migrationManager = migrationManager;
        this.v6RenameMigration = v6RenameMigration;
        this.v6QuteMigration = v6QuteMigration;
        this.channelConnectorMigration = channelConnectorMigration;
        this.runtime = runtime;
        this.workflowStore = workflowStore;
        this.ruleSetStore = ruleSetStore;
        this.maximumLifeTimeOfIdleConversationsInDays = maximumLifeTimeOfIdleConversationsInDays;
    }

    void onStart(@Observes StartupEvent ev) {
        runtime.getScheduledExecutorService().schedule(() -> {
            autoDeployAgents();

            return null;
        }, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void autoDeployAgents() {
        LOGGER.info("Starting deployment of agents...");

        // V6 rename migration must run before document-level migrations.
        // Each migration is independently guarded: a failure logs the error
        // and lets the remaining migrations + agent deployment proceed.
        // The failed migration will retry on next startup (flag not set).
        try {
            v6RenameMigration.runIfNeeded();
        } catch (Exception e) {
            LOGGER.error("V6 rename migration failed — will retry on next startup", e);
        }
        try {
            v6QuteMigration.runIfNeeded();
        } catch (Exception e) {
            LOGGER.error("V6 Qute migration failed — will retry on next startup", e);
        }
        try {
            channelConnectorMigration.runIfNeeded();
        } catch (Exception e) {
            LOGGER.error("Channel connector migration failed — will retry on next startup", e);
        }

        migrationManager.startMigrationIfFirstTimeRun(() -> {
            checkDeployments();
            agentsReadiness.setAgentsReadiness(true);
        });

        LOGGER.info("Finished deployment of agents.");
        LOGGER.info("E.D.D.I is ready!");
    }

    @Scheduled(every = "10s", delay = 10)
    public void checkDeployments() {
        try {
            List<DeploymentInfo> currentlyDeployed = deploymentStore.readDeploymentInfos(deployed).stream()
                    .filter(deploymentInfo -> deploymentInfo.getAgentId() != null && deploymentInfo.getAgentVersion() != null).toList();

            // Drop records that are no longer deployed, so an agent that is undeployed
            // and later redeployed is picked up again instead of being suppressed
            // forever by a stale entry.
            this.deploymentInfos.retainAll(currentlyDeployed);

            currentlyDeployed.stream().filter(deploymentInfo -> !this.deploymentInfos.contains(deploymentInfo)).forEach(deploymentInfo -> {
                try {
                    // A deployment record can outlive its Agent. deployAgent reports that by
                    // logging an ERROR and returning normally, so the catch blocks below never
                    // see it and the record is retried on every startup. Catch it up front.
                    if (isAgentConfigMissing(deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion())) {
                        LOGGER.warn(format("Agent config no longer exists (id=%s, version=%d) — retiring stale deployment record",
                                deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion()));
                        // Delete rather than mark undeployed: setDeploymentInfo upserts, so
                        // if the Agent went away between reading this list and getting here,
                        // marking it would resurrect the row the cascade just removed.
                        // Scoped to this one record — the check above proves only that THIS
                        // version is gone, and an agent-wide delete would take out sibling
                        // records for versions nobody looked at.
                        deploymentStore.deleteDeploymentInfo(deploymentInfo.getEnvironment().toString(), deploymentInfo.getAgentId(),
                                deploymentInfo.getAgentVersion());
                        return;
                    }

                    agentFactory.deployAgent(deploymentInfo.getEnvironment(), deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion(),
                            null);

                    this.deploymentInfos.add(deploymentInfo);

                    lintInertHitlConfig(deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion());
                } catch (ServiceException | IllegalAccessException e) {
                    LOGGER.error(e.getLocalizedMessage(), e);
                } catch (Exception e) {
                    // Catch any other exception (e.g. IllegalStateException wrapping
                    // ResourceNotFoundException) so one broken Agent doesn't block all others
                    LOGGER.error(format("Failed to deploy Agent (id=%s, version=%d, environment=%s), skipping. Cause: %s",
                            deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion(), deploymentInfo.getEnvironment(), e.getMessage()));

                    // If the root cause is a missing resource, auto-clean the stale record
                    if (isCausedByResourceNotFound(e)) {
                        LOGGER.warn(format("Agent config not found for id=%s version=%d — marking deployment as undeployed",
                                deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion()));
                        deploymentStore.setDeploymentInfo(deploymentInfo.getEnvironment().toString(), deploymentInfo.getAgentId(),
                                deploymentInfo.getAgentVersion(), undeployed);
                    }
                }
            });
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    /**
     * True only when the Agent config is provably gone. A store failure is not
     * proof of absence, so anything other than a not-found answer leaves the
     * deployment record untouched.
     */
    private boolean isAgentConfigMissing(String agentId, Integer agentVersion) {
        try {
            agentStore.read(agentId, agentVersion);
            return false;
        } catch (ResourceNotFoundException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCausedByResourceNotFound(Throwable t) {
        while (t != null) {
            if (t instanceof ResourceNotFoundException)
                return true;
            t = t.getCause();
        }
        return false;
    }

    /**
     * Non-fatal deploy-time lint (Task 15): WARNs when a deployed agent's
     * hitlConfig can never actually trigger a pause — i.e. no rule in any ruleset
     * reachable from this agent's workflows emits {@code PAUSE_CONVERSATION}, and
     * {@code hitlConfig.toolApprovals} has no {@code requireApproval} patterns
     * either. Never blocks or fails the deployment; a lookup failure while
     * resolving rulesets is swallowed (logged) so a broken workflow/ruleset
     * reference can't turn an informational lint into a deployment failure.
     */
    private void lintInertHitlConfig(String agentId, Integer agentVersion) {
        try {
            AgentConfiguration agentConfiguration = agentStore.read(agentId, agentVersion);
            AgentConfiguration.HitlConfig hitlConfig = agentConfiguration.getHitlConfig();
            if (hitlConfig == null) {
                return;
            }

            boolean anyRulesetEmitsPause = anyRulesetEmitsPauseConversation(agentConfiguration);
            ReservedActionLint.checkInertHitlConfig(agentId, hitlConfig, anyRulesetEmitsPause)
                    .ifPresent(LOGGER::warn);
        } catch (Exception e) {
            // Non-fatal: this is an informational lint, not a deployment gate.
            LOGGER.warn(format("Skipping inert-hitlConfig lint for Agent (id=%s, version=%d) — could not resolve "
                    + "workflows/rulesets: %s", agentId, agentVersion, e.getMessage()));
        }
    }

    private boolean anyRulesetEmitsPauseConversation(AgentConfiguration agentConfiguration) {
        for (URI workflowUri : agentConfiguration.getWorkflows()) {
            IResourceId workflowId = RestUtilities.extractResourceId(workflowUri);
            if (workflowId == null) {
                continue;
            }

            WorkflowConfiguration workflowConfiguration;
            try {
                workflowConfiguration = workflowStore.read(workflowId.getId(), workflowId.getVersion());
            } catch (Exception e) {
                continue;
            }
            if (workflowConfiguration == null || workflowConfiguration.getWorkflowSteps() == null) {
                continue;
            }

            for (WorkflowConfiguration.WorkflowStep step : workflowConfiguration.getWorkflowSteps()) {
                if (step.getType() == null || !step.getType().toString().contains("ai.labs.behavior")) {
                    continue;
                }

                Object uriObj = step.getConfig() != null ? step.getConfig().get("uri") : null;
                if (uriObj == null) {
                    continue;
                }

                IResourceId ruleSetId = RestUtilities.extractResourceId(URI.create(uriObj.toString()));
                if (ruleSetId == null) {
                    continue;
                }

                RuleSetConfiguration ruleSetConfiguration;
                try {
                    ruleSetConfiguration = ruleSetStore.read(ruleSetId.getId(), ruleSetId.getVersion());
                } catch (Exception e) {
                    continue;
                }

                if (ruleSetEmitsPauseConversation(ruleSetConfiguration)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ruleSetEmitsPauseConversation(RuleSetConfiguration ruleSetConfiguration) {
        if (ruleSetConfiguration == null || ruleSetConfiguration.getBehaviorGroups() == null) {
            return false;
        }
        for (RuleGroupConfiguration group : ruleSetConfiguration.getBehaviorGroups()) {
            if (group == null || group.getRules() == null) {
                continue;
            }
            for (RuleConfiguration rule : group.getRules()) {
                if (rule != null && rule.getActions() != null && rule.getActions().contains(IConversation.PAUSE_CONVERSATION)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Scheduled(every = "24h", delay = 300)
    public void manageAgentDeployments() {
        try {
            var oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            if (lastDeploymentCheck == null || lastDeploymentCheck.isBefore(oneHourAgo)) {
                lastDeploymentCheck = Instant.now();
                var postUndeloymentAttempts = deploymentStore.readDeploymentInfos(deployed).stream()
                        .filter(deploymentInfo -> deploymentInfo.getAgentId() != null && deploymentInfo.getAgentVersion() != null)
                        .map(deploymentInfo -> {
                            var environmentName = deploymentInfo.getEnvironment().toString();
                            var environment = Environment.valueOf(environmentName);
                            var agentId = deploymentInfo.getAgentId();
                            var agentVersion = deploymentInfo.getAgentVersion();
                            try {
                                IResourceId latestAgent;
                                try {
                                    latestAgent = agentStore.getCurrentResourceId(agentId);
                                } catch (ResourceNotFoundException e) {
                                    // there is no latest Agent version found, so this Agent was very likely
                                    // deleted,
                                    // therefore we will treat it like an older Agent version and try to undeploy
                                    // if no longer in use
                                    latestAgent = null;
                                }

                                if (latestAgent != null && latestAgent.getVersion() <= agentVersion) {
                                    // we attempt to deploy a Agent if it is the latest
                                    agentFactory.deployAgent(environment, agentId, agentVersion, null);
                                } else {
                                    manageDeploymentOfOldAgent(environment, agentId, agentVersion);

                                    return (UndeploymentExecutor) () -> {
                                        try {
                                            // attempt to undeploy Agent if this Agent version is no longer in use
                                            endOldConversationsWithOldAgents(agentId, agentVersion);

                                            manageDeploymentOfOldAgent(environment, agentId, agentVersion);
                                        } catch (ResourceStoreException | ResourceNotFoundException | ServiceException | IllegalAccessException e) {
                                            LOGGER.error(e.getLocalizedMessage(), e);
                                        }
                                    };
                                }
                            } catch (ServiceException | IllegalAccessException | IllegalArgumentException e) {
                                var message = "Error while deployment management of Agent " + "(environment=%s, agentId=%s, version=%s)!\n";

                                LOGGER.error(format(message, environment, agentId, agentVersion));
                                LOGGER.error(e.getLocalizedMessage(), e);
                            }

                            return (UndeploymentExecutor) () -> {
                            };
                        }).toList();

                // run all undeploy attempts of old agents after all current agents have been
                // deployed and see
                // if we can undeploy Agent version if we end old conversations
                postUndeloymentAttempts.forEach(UndeploymentExecutor::attemptUndeploy);
            }
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void manageDeploymentOfOldAgent(Environment environment, String agentId, Integer agentVersion)
            throws ServiceException, IllegalAccessException {

        var conversationCount = conversationMemoryStore.getActiveConversationCount(agentId, agentVersion);
        if (conversationCount == 0) {
            // this old Agent version has no more active conversations connected to it,
            // so we undeploy it
            agentFactory.undeployAgent(environment, agentId, agentVersion);
            deploymentStore.setDeploymentInfo(environment.toString(), agentId, agentVersion, undeployed);
            LOGGER.info(format("Successfully undeployed Agent (id: %s, version: %d)", agentId, agentVersion));
        } else {
            // not the latest agent, but still has active conversations connected to it,
            // therefore we deploy it as well to make sure we don't interrupt UX
            agentFactory.deployAgent(environment, agentId, agentVersion, null);
        }
    }

    private void endOldConversationsWithOldAgents(String agentId, Integer agentVersion) throws ResourceStoreException, ResourceNotFoundException {

        var conversationMemorySnapshots = conversationMemoryStore.loadActiveConversationMemorySnapshot(agentId, agentVersion);

        int sparedPausedConversations = 0;
        for (var conversationMemory : conversationMemorySnapshots) {
            // A paused (AWAITING_HUMAN) conversation is a live pending approval:
            // ending it here with a raw setConversationState(ENDED) would leave its
            // armed timeout schedule and HITL bookmark behind, skip the EU AI Act
            // oversight audit, and destroy the pause that getActiveConversationCount
            // deliberately excludes so it survives undeploy. Skip it — reaping
            // paused conversations needs an explicit, audited HITL-aware policy
            // (see the HITL pending-approval retention sweep).
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                sparedPausedConversations++;
                continue;
            }

            var documentDescriptor = documentDescriptorStore.readDescriptor(conversationMemory.getAgentId(), conversationMemory.getAgentVersion());

            // Age is derived from the CONVERSATION's own newest step timestamp.
            //
            // It used to come from the AGENT document's lastModifiedOn, which is not
            // a property of the conversation at all: every conversation on a given
            // agent version shared one age, so a conversation the user was talking in
            // an hour ago was "idle" whenever its agent config happened to be old.
            // That mis-signal was masked by the arithmetic bug in isOlderThanDays
            // (below), which made the sweep effectively fire only past a year;
            // fixing the arithmetic without fixing the signal would have started
            // ENDing live conversations.
            var lastInteraction = lastInteractionOf(conversationMemory);
            if (lastInteraction == null) {
                // No step ever carried a timestamp — nothing to age against. Fall back
                // to the descriptor rather than guessing, and only for this
                // conversation.
                var descriptorLastModified = documentDescriptor.getLastModifiedOn();
                if (descriptorLastModified == null) {
                    // No age signal at all. Skip: "cannot prove it is idle" must never
                    // end a conversation, and dereferencing here would throw an NPE the
                    // enclosing UndeploymentExecutor does not catch — aborting every
                    // remaining undeploy attempt in this pass.
                    continue;
                }
                lastInteraction = descriptorLastModified.toInstant();
            }

            var isOlderThanMaximumAmountOfDays = isOlderThanDays(
                    lastInteraction.atZone(ZoneId.systemDefault()).toLocalDate(), maximumLifeTimeOfIdleConversationsInDays);

            if (isOlderThanMaximumAmountOfDays) {
                String conversationId = conversationMemory.getId();
                conversationMemoryStore.setConversationState(conversationId, ConversationState.ENDED);
                var message = format(
                        "Ended conversation (id: %s) with Agent (name: %s, id: %s, version: %d) "
                                + "because it is %d days older than the maximum idle time of %d days",
                        conversationId, documentDescriptor.getName(), agentId, agentVersion, DAYS.between(lastInteraction, Instant.now()),
                        maximumLifeTimeOfIdleConversationsInDays);

                LOGGER.info(message);
            }
        }

        if (sparedPausedConversations > 0) {
            LOGGER.info(format(
                    "Spared %d paused (AWAITING_HUMAN) conversation(s) of Agent (id: %s, version: %d) from the idle sweep — "
                            + "their pending approvals are preserved",
                    sparedPausedConversations, agentId, agentVersion));
        }
    }

    /**
     * The newest timestamp any data item in the conversation carries, or
     * {@code null} when nothing is timestamped. This is the conversation's own
     * last-interaction time: every turn writes step data through
     * {@code ConversationStep}, which stamps each entry.
     */
    static Instant lastInteractionOf(ConversationMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.getConversationSteps() == null) {
            return null;
        }

        Date newest = null;
        for (var step : snapshot.getConversationSteps()) {
            if (step == null || step.getWorkflows() == null) {
                continue;
            }
            for (var workflow : step.getWorkflows()) {
                if (workflow == null || workflow.getLifecycleTasks() == null) {
                    continue;
                }
                for (var task : workflow.getLifecycleTasks()) {
                    Date timestamp = task != null ? task.getTimestamp() : null;
                    if (timestamp != null && (newest == null || timestamp.after(newest))) {
                        newest = timestamp;
                    }
                }
            }
        }

        return newest != null ? newest.toInstant() : null;
    }

    /**
     * True when {@code date} is at least {@code days} days in the past.
     * <p>
     * This used to be written against {@link Period}, which normalizes into
     * years/months/days — and the check only looked at the years and days
     * components, never the months. For a 35-day-old date and a 30-day limit,
     * {@code Period.between(now, date)} is {@code P-1M-4D}, so the test read
     * {@code -4 <= -30} and answered "not old". Whole bands of ages between
     * {@code days} and one year were therefore never reaped, and the ones that were
     * passed only by coincidence of where the month boundary fell. Day arithmetic
     * has no such components.
     */
    static boolean isOlderThanDays(final LocalDate date, final int days) {
        return DAYS.between(date, LocalDate.now()) >= days;
    }

    private interface UndeploymentExecutor {
        void attemptUndeploy();
    }
}
