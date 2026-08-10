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
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
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
    private final List<DeploymentInfo> deploymentInfos = new LinkedList<>();

    /**
     * Deploy-time vault-grant gate. Field-injected rather than a constructor
     * parameter so a directly constructed instance (tests, non-CDI callers) simply
     * sees {@code null} and skips the check — the same pattern the engine already
     * uses for late-bound collaborators.
     */
    @Inject
    VaultGrantChecker vaultGrantChecker;

    /**
     * What to do when an agent's config names a vault secret it is not granted:
     * {@code off}, {@code warn} (default) or {@code enforce}.
     * <p>
     * Warn is the default deliberately. {@code allowedAgents} has never been
     * enforced, so any non-wildcard value in an existing deployment is untested
     * configuration — blocking on it during an upgrade could take agents down for a
     * policy nobody has yet had the chance to verify. Operators turn on
     * {@code enforce} once the warnings are clean.
     */
    @Inject
    @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = "warn")
    String vaultGrantEnforcement;

    private GrantEnforcement grantEnforcement = GrantEnforcement.WARN;

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
            deploymentStore.readDeploymentInfos(deployed).stream()
                    .filter(deploymentInfo -> deploymentInfo.getAgentId() != null && deploymentInfo.getAgentVersion() != null)
                    .filter(deploymentInfo -> !this.deploymentInfos.contains(deploymentInfo)).forEach(deploymentInfo -> {
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

                            if (!deployIfGranted(deploymentInfo.getEnvironment(), deploymentInfo.getAgentId(),
                                    deploymentInfo.getAgentVersion())) {
                                // enforce mode only — left un-added to deploymentInfos so a
                                // corrected grant is picked up by the next poll.
                                return;
                            }

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
                                    deployIfGranted(environment, agentId, agentVersion);
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
            deployIfGranted(environment, agentId, agentVersion);
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

            // NOTE: age is derived from the AGENT document's lastModifiedOn, not the
            // conversation's — a pre-existing heuristic that predates the HITL branch
            // and is intentionally left unchanged here.
            var timeOfLastInteractionInConversation = documentDescriptor.getLastModifiedOn();

            var isOlderThanMaximumAmountOfDays = isOlderThanDays(
                    Instant.ofEpochMilli(timeOfLastInteractionInConversation.getTime()).atZone(ZoneId.systemDefault()).toLocalDate(),
                    maximumLifeTimeOfIdleConversationsInDays);

            if (isOlderThanMaximumAmountOfDays) {
                String conversationId = conversationMemory.getId();
                conversationMemoryStore.setConversationState(conversationId, ConversationState.ENDED);
                var message = format(
                        "Ended conversation (id: %s) with Agent (name: %s, id: %s, version: %d) "
                                + "because it is %d days older than the maximum idle time of %d days",
                        conversationId, documentDescriptor.getName(), agentId, agentVersion,
                        DAYS.between(timeOfLastInteractionInConversation.toInstant(), Instant.now()), maximumLifeTimeOfIdleConversationsInDays);

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

    private boolean isOlderThanDays(final LocalDate date, final int days) {
        boolean result = false;

        LocalDate now = LocalDate.now();
        // period from now to date
        Period period = Period.between(now, date);

        if (period.getYears() < 0) {
            // if year is negative, 100% older than 6 months
            result = true;
        } else if (period.getYears() == 0) {
            if (period.getDays() <= -days) {
                result = true;
            }
        }

        return result;
    }

    /**
     * The ONLY way this class deploys an agent.
     * <p>
     * Guarding {@code checkDeployments} alone left {@code manageAgentDeployments}'
     * latest-version redeploy and {@link #manageDeploymentOfOldAgent} calling the
     * factory directly — so in {@code enforce} mode an agent with ungranted vault
     * references was blocked by one path and deployed by another a day later. A
     * gate with a way round it is not a gate.
     *
     * @return {@code true} if the agent was deployed
     */
    private boolean deployIfGranted(Environment environment, String agentId, Integer agentVersion)
            throws ServiceException, IllegalAccessException {

        if (!vaultGrantsSatisfied(agentId, agentVersion)) {
            return false;
        }
        agentFactory.deployAgent(environment, agentId, agentVersion, null);
        return true;
    }

    /** Enforcement modes for {@code eddi.vault.grant-enforcement}. */
    enum GrantEnforcement {
        OFF, WARN, ENFORCE;

        /**
         * Strict parse — an unrecognised value is rejected, never defaulted.
         * {@code grant-enforcement=enforced} silently behaving as {@code warn} would
         * turn one typo into a security control that is off while appearing on.
         */
        static GrantEnforcement parseStrict(String value) {
            if (value == null || value.isBlank()) {
                return WARN;
            }
            for (GrantEnforcement mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown eddi.vault.grant-enforcement value '" + value
                    + "'. Valid values: off, warn, enforce");
        }
    }

    /**
     * Fails startup on an unusable enforcement mode rather than degrading to warn.
     * This bean is {@code @Startup}, so the error surfaces at boot with the valid
     * values named.
     */
    @PostConstruct
    void validateGrantEnforcement() {
        grantEnforcement = GrantEnforcement.parseStrict(vaultGrantEnforcement);
    }

    /**
     * Deploy-time check that every vault reference this agent's configuration names
     * is granted to it.
     *
     * @return {@code true} when deployment may proceed
     */
    private boolean vaultGrantsSatisfied(String agentId, Integer agentVersion) {
        if (vaultGrantChecker == null || grantEnforcement == GrantEnforcement.OFF) {
            return true;
        }
        List<String> ungranted;
        try {
            ungranted = vaultGrantChecker.findUngrantedReferences(agentStore.read(agentId, agentVersion), agentId);
        } catch (Exception e) {
            // A check that cannot run must never block a deployment.
            LOGGER.warn(format("Skipping the vault-grant check for Agent (id=%s, version=%d): %s", agentId, agentVersion, e.getMessage()));
            return true;
        }
        if (ungranted.isEmpty()) {
            return true;
        }

        String message = format(
                "Agent (id=%s, version=%d) references vault secret(s) it is not granted: %s. "
                        + "SecretMetadata.allowedAgents lists which agents may use a secret; widen the grant or remove the reference.",
                agentId, agentVersion, ungranted);
        if (grantEnforcement == GrantEnforcement.ENFORCE) {
            LOGGER.error(message + " Deployment BLOCKED (eddi.vault.grant-enforcement=enforce).");
            return false;
        }
        LOGGER.warn(message + " Deployment allowed (eddi.vault.grant-enforcement=warn); set it to 'enforce' to block.");
        return true;
    }

    private interface UndeploymentExecutor {
        void attemptUndeploy();
    }
}
