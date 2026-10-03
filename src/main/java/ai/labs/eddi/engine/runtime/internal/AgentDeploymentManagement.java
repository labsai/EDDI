/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import java.util.Set;
import java.util.HashSet;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.mongo.DeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.undeployed;
import static ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import static ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
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
    private final WorkspaceAccessIndexMigration workspaceAccessIndexMigration;
    private final IAgentsReadiness agentsReadiness;
    private final IRuntime runtime;
    private final IWorkflowStore workflowStore;
    private final IRuleSetStore ruleSetStore;
    private final int maximumLifeTimeOfIdleConversationsInDays;
    private Instant lastDeploymentCheck = null;
    private static final Logger LOGGER = Logger.getLogger(AgentDeploymentManagement.class);
    private final List<DeploymentInfo> deploymentInfos = new LinkedList<>();
    /** Whether the "sweep parked" warning has been logged; see checkDeployments. */
    private final AtomicBoolean sweepParkedLogged = new AtomicBoolean();
    /**
     * Set when the startup path could not report ready because the rename migration
     * was still pending, and taken by the first scheduled sweep that completes once
     * it is not. Only the taker grants readiness, so however many ticks follow, it
     * happens exactly once.
     */
    private final AtomicBoolean readinessDeferred = new AtomicBoolean();
    /**
     * Set when startup parked the document-level migrations behind a pending rename
     * migration, and taken by the first sweep that sees it complete — which runs
     * them before it deploys anything or grants readiness. Without this, a
     * migration-log read that failed transiently at boot skipped them until the
     * next restart while the sweep went on to deploy agents and report ready.
     * <p>
     * Guarded by {@link #documentMigrationsLock}, and cleared only once the run has
     * finished: the startup callback and the scheduled sweep can both be in
     * {@link #checkDeployments()} at once (the scheduler's SKIP only keeps sweeps
     * from overlapping each other), and a flag cleared when the run STARTED let the
     * second caller deploy from documents the first was still migrating.
     */
    private boolean documentMigrationsDeferred;
    /**
     * Held while the deferred document migrations run, so every caller of
     * {@link #checkDeployments()} waits for them before deploying anything or
     * granting readiness. Never held across anything that re-enters this class.
     */
    private final Object documentMigrationsLock = new Object();

    /** The wait before the first retry of a deployment that failed. */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    /** The longest wait between two retries of a deployment that keeps failing. */
    static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    /**
     * Deployments that failed, and when each may be tried again.
     *
     * <p>
     * A deployment used to be recorded as handled whatever its outcome:
     * {@code deployAgent} reports some failures by leaving the agent in ERROR and
     * returning normally, so the record went into {@link #deploymentInfos} and no
     * later sweep looked at it again. The agent stayed in ERROR until a restart,
     * even after its cause — a missing index, a vault secret — had been fixed under
     * the running instance. It is now recorded only once it is READY, and a failure
     * is retried with a doubling delay, capped at {@link #MAX_RETRY_DELAY}, for as
     * long as its deployment record says it should be deployed. The ERROR is logged
     * once when the deployment starts failing and once more when it recovers, not
     * on every retry.
     * </p>
     */
    private final Map<DeploymentInfo, RetryState> failingDeployments = new ConcurrentHashMap<>();

    /** What is known about one failing deployment. */
    record RetryState(int failures, Instant nextAttempt) {
    }

    /** Replaced in tests, so that the backoff can be stepped through. */
    Clock clock = Clock.systemUTC();

    /**
     * True while {@link #autoDeployAgents()} runs the startup migrations.
     *
     * <p>
     * The sweep used to wait for the rename migration only. The scheduled tick that
     * followed its completion then deployed agents while the startup thread was
     * still converting their Thymeleaf templates to Qute; such an agent kept the
     * templates it had loaded, was READY, and so was never redeployed — it rendered
     * its templates as literal text until a restart. The sweep now waits for all of
     * them.
     * </p>
     */
    private final AtomicBoolean startupMigrationsRunning = new AtomicBoolean();

    /** Whether the startup path reached the point where it grants readiness. */
    private final AtomicBoolean startupCallbackRan = new AtomicBoolean();

    /**
     * Serializes {@link #checkDeployments()}. {@code SKIP} only stops one scheduled
     * tick overlapping the next; the startup path calls the sweep itself, and two
     * passes deploying the same agents at once shared an unsynchronized list and
     * could record a deployment the other pass then failed.
     */
    private final Object sweepLock = new Object();

    /**
     * Cluster mode only. Deployment changes made on other nodes arrive as events
     * (an undeploy is applied at once; a deploy runs the sweep), and the sweep
     * becomes two-way: an agent this node serves whose record is no longer
     * {@code deployed} is undeployed here too. Field-injected; null in tests.
     */
    @Inject
    ClusterConfig clusterConfig;

    @Inject
    IClusterEventBus clusterEvents;

    /**
     * Locally deployed agents first seen without a {@code deployed} record — an
     * undeploy needs two consecutive sweeps, so an agent whose REST deploy has not
     * written its record yet is never taken down by the race.
     */
    private final Map<String, Instant> missingSince = new ConcurrentHashMap<>();

    private boolean clustered() {
        return clusterConfig != null && clusterConfig.isNats();
    }

    @Override
    public <T> T awaitClusterDeployment(Environment environment, String agentId, Supplier<T> resolve, Duration maxWait) {
        if (!clustered() || agentId == null) {
            return null;
        }
        try {
            boolean deployedSomewhere = deploymentStore.readDeploymentInfos(deployed).stream()
                    .anyMatch(info -> info.getEnvironment() == environment && agentId.equals(info.getAgentId()));
            if (!deployedSomewhere) {
                return null;
            }
        } catch (RuntimeException | IResourceStore.ResourceStoreException e) {
            LOGGER.debugf("On-demand deployment check of %s failed: %s", agentId, e.getMessage());
            return null;
        }
        checkDeployments();
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            T found = resolve.get();
            if (found != null) {
                LOGGER.debugf("Agent %s deployed on demand on this node", agentId);
                return found;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    @Inject
    public AgentDeploymentManagement(IDeploymentStore deploymentStore, IAgentFactory agentFactory, IAgentStore agentStore,
            IAgentsReadiness agentsReadiness, IConversationMemoryStore conversationMemoryStore, IDocumentDescriptorStore documentDescriptorStore,
            IMigrationManager migrationManager, V6RenameMigration v6RenameMigration, V6QuteMigration v6QuteMigration,
            ChannelConnectorMigration channelConnectorMigration, WorkspaceAccessIndexMigration workspaceAccessIndexMigration,
            IRuntime runtime, IWorkflowStore workflowStore, IRuleSetStore ruleSetStore,
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
        this.workspaceAccessIndexMigration = workspaceAccessIndexMigration;
        this.runtime = runtime;
        this.workflowStore = workflowStore;
        this.ruleSetStore = ruleSetStore;
        this.maximumLifeTimeOfIdleConversationsInDays = maximumLifeTimeOfIdleConversationsInDays;
    }

    /**
     * Whether the daily sweep ends conversations for being idle.
     *
     * <p>
     * A limit below one day turns it off. It used to be taken literally: the check
     * is {@code DAYS.between(lastInteraction, today) >= limit}, which every
     * conversation passes for a limit of {@code 0} or {@code -1}, so both ENDED
     * every conversation the sweep reached. {@code -1} is how the neighbouring
     * {@code deleteEndedConversationsOnceOlderThanDays} and
     * {@code eddi.usermemories.deleteOlderThanDays} say "never", and an operator
     * copying that idiom closed every open conversation five minutes after boot.
     * The same threshold as the retention sweep ({@code < 1} is off), so all three
     * settings read {@code -1} the same way.
     * </p>
     *
     * <p>
     * Only the ENDING is switched off. The sweep still deploys the latest version
     * of each agent, still retires an old version whose conversations can move to a
     * newer compatible one, and still undeploys an old version that has <em>no</em>
     * active conversation left. None of that ends a conversation or loses anything
     * — a version with no active conversation serves nobody, and
     * {@code getActiveConversationCount} counts idle-but-open conversations as
     * active, so an old version keeps its deployment for exactly as long as one of
     * them is still open. Stopping those too would leave superseded versions
     * holding memory for ever, which is a different decision from "don't close
     * conversations" and would need a setting of its own.
     * </p>
     */
    boolean idleEndingEnabled() {
        return maximumLifeTimeOfIdleConversationsInDays >= 1;
    }

    void subscribeToCluster(@Observes
    @Priority(60) StartupEvent ev) {
        if (!clustered() || clusterEvents == null) {
            return;
        }
        clusterEvents.subscribe(ClusterEvent.DEPLOYMENT_CHANGED, this::onRemoteDeploymentChange);
        clusterEvents.onResync(() -> runtime.getExecutorService().submit(this::checkDeployments));
    }

    /**
     * Another node changed a deployment record. An undeploy (or a deleted record)
     * is applied here immediately; a deploy runs the sweep, which deploys whatever
     * the records say is missing. Idempotent: both read the database, never the
     * event.
     */
    void onRemoteDeploymentChange(ClusterEvent event) {
        String status = event.getString("status");
        String agentId = event.getString("agentId");
        if (agentId == null) {
            return;
        }
        if ("deployed".equals(status)) {
            runtime.getExecutorService().submit(this::checkDeployments);
            return;
        }
        if (DeploymentStore.TRANSIENT.equals(status)) {
            deployTransient(event.getString("env"), agentId, event.get("version"));
            return;
        }
        Object version = event.get("version");
        String env = event.getString("env");
        for (Environment environment : Environment.values()) {
            if (env != null && !environment.toString().equals(env)) {
                continue;
            }
            try {
                for (IAgent agent : agentFactory.getAllDeployedAgents(environment)) {
                    if (agentId.equals(agent.getAgentId())
                            && (version == null || String.valueOf(version).equals(String.valueOf(agent.getAgentVersion())))) {
                        undeployLocally(environment, agent.getAgentId(), agent.getAgentVersion(), "undeployed on node " + event.originNode());
                    }
                }
            } catch (ServiceException e) {
                LOGGER.warnf("Could not apply a remote undeploy of %s: %s", agentId, e.getMessage());
            }
        }
    }

    /**
     * Another node deployed {@code agentId} with {@code autoDeploy=false}: deploy
     * it here as well, equally unrecorded, so every node serves it.
     */
    private void deployTransient(String env, String agentId, Object version) {
        if (env == null || version == null) {
            return;
        }
        try {
            Environment environment = Environment.valueOf(env);
            Integer agentVersion = Integer.valueOf(String.valueOf(version));
            noteUnrecordedDeployment(environment, agentId, agentVersion);
            runtime.getExecutorService().submit(() -> {
                try {
                    IAgent existing = agentFactory.getAgent(environment, agentId, agentVersion);
                    if (existing == null) {
                        agentFactory.deployAgent(environment, agentId, agentVersion, null);
                        LOGGER.infof("Deployed agent %s version %d in %s on this node (deployed on another node, not recorded)",
                                agentId, agentVersion, environment);
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Could not deploy agent %s version %s locally: %s", agentId, agentVersion, e.getMessage());
                }
            });
        } catch (IllegalArgumentException e) {
            LOGGER.debugf("Ignoring a transient deployment event with env=%s version=%s", env, version);
        }
    }

    private void undeployLocally(Environment environment, String agentId, Integer agentVersion, String why) {
        try {
            agentFactory.undeployAgent(environment, agentId, agentVersion);
            unrecorded.remove(keyOf(environment, agentId, agentVersion));
            synchronized (sweepLock) {
                deploymentInfos.removeIf(info -> info.getEnvironment() == environment && agentId.equals(info.getAgentId())
                        && agentVersion.equals(info.getAgentVersion()));
            }
            LOGGER.infof("Undeployed agent %s version %d in %s on this node (%s)", agentId, agentVersion, environment, why);
        } catch (ServiceException | IllegalAccessException | RuntimeException e) {
            LOGGER.warnf("Could not undeploy agent %s version %d locally: %s", agentId, agentVersion, e.getMessage());
        }
    }

    /**
     * The second direction of the sweep, cluster mode only: an agent this node
     * serves whose record is no longer {@code deployed} — undeployed or deleted on
     * another node — is undeployed here as well. Runs only on a list the store
     * actually returned (an exception means "no information", never "undeploy
     * everything"), and only after the agent was missing in two consecutive sweeps.
     */
    /**
     * Every deployment this node has seen a record of — only those can lose one.
     */
    private final Set<String> everRecorded = ConcurrentHashMap.newKeySet();

    /**
     * Deployments made without a record (autoDeploy=false), here or announced by
     * another node. Exempt from the reconciliation even if the same version was
     * recorded once, then undeployed, before this unrecorded deploy.
     */
    private final Set<String> unrecorded = ConcurrentHashMap.newKeySet();

    private static String keyOf(Environment environment, String agentId, Object version) {
        return environment + "/" + agentId + "/" + version;
    }

    @Override
    public void noteUnrecordedDeployment(Environment environment, String agentId, Integer agentVersion) {
        if (clustered() && environment != null && agentId != null && agentVersion != null) {
            unrecorded.add(keyOf(environment, agentId, agentVersion));
        }
    }

    private void reconcileUndeployed(List<DeploymentInfo> meantToBeDeployed) {
        Set<String> wanted = new HashSet<>();
        for (DeploymentInfo info : meantToBeDeployed) {
            wanted.add(info.getEnvironment() + "/" + info.getAgentId() + "/" + info.getAgentVersion());
        }
        everRecorded.addAll(wanted);
        unrecorded.removeAll(wanted); // a record exists now: the sweep owns it again
        Instant now = clock.instant();
        Set<String> seenMissing = new HashSet<>();
        for (Environment environment : Environment.values()) {
            List<IAgent> served;
            try {
                served = agentFactory.getAllDeployedAgents(environment);
            } catch (ServiceException e) {
                continue;
            }
            for (IAgent agent : served) {
                String key = environment + "/" + agent.getAgentId() + "/" + agent.getAgentVersion();
                if (wanted.contains(key)) {
                    continue;
                }
                if (unrecorded.contains(key) || !everRecorded.contains(key)) {
                    // Never had a record: deployed with autoDeploy=false (here or, through
                    // the cluster event, on another node). Its undeploy arrives as an event;
                    // the sweep must not take it for a record that went away.
                    continue;
                }
                seenMissing.add(key);
                Instant first = missingSince.putIfAbsent(key, now);
                if (first != null && Duration.between(first, now).toSeconds() >= 5) {
                    missingSince.remove(key);
                    undeployLocally(environment, agent.getAgentId(), agent.getAgentVersion(), "its deployment record is gone");
                }
            }
        }
        missingSince.keySet().retainAll(seenMissing);
    }

    void onStart(@Observes StartupEvent ev) {
        if (!idleEndingEnabled()) {
            LOGGER.infof("Idle conversations are never ended: eddi.conversations.maximumLifeTimeOfIdleConversationsInDays=%d "
                    + "(below 1 disables it). Old agent versions with no active conversation are still undeployed.",
                    maximumLifeTimeOfIdleConversationsInDays);
        }
        runtime.getScheduledExecutorService().schedule(() -> {
            autoDeployAgents();

            return null;
        }, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void autoDeployAgents() {
        LOGGER.info("Starting deployment of agents...");
        startupMigrationsRunning.set(true);
        try {
            runStartupMigrationsAndDeploy();
        } finally {
            // Also when a migration threw past its own guard: a sweep parked for good
            // would never deploy anything, which is worse than deploying on configs
            // a failed migration left as they were.
            startupMigrationsRunning.set(false);
            if (!startupCallbackRan.get()) {
                // The startup path never got to grant readiness. Hand it to the first
                // sweep that completes, as for a pending rename migration, rather than
                // leave the instance DOWN while the sweep deploys and serves its agents.
                LOGGER.error("The startup deployment did not complete (logged above); readiness is granted by the first "
                        + "scheduled deployment sweep that does.");
                readinessDeferred.set(true);
            }
        }
        LOGGER.info("Finished deployment of agents.");
    }

    private void runStartupMigrationsAndDeploy() {

        // V6 rename migration must run before document-level migrations.
        // Each migration is independently guarded: a failure logs the error
        // and lets the remaining migrations + agent deployment proceed.
        // The failed migration will retry on next startup (flag not set).
        try {
            v6RenameMigration.runIfNeeded();
        } catch (Exception e) {
            LOGGER.error("V6 rename migration failed — will retry on next startup", e);
        }
        // E3: the document-level migrations read the v6 collections the rename
        // migration creates. Running them while it is still pending (it failed above,
        // or its log could not be read) let each one scan empty collections, find
        // nothing to do and record itself as COMPLETE — so it never ran again, and
        // the documents the rename later moved into place were never migrated. Park
        // them instead; they are unflagged, and the first deployment sweep that sees
        // the rename complete runs them before it deploys anything.
        if (v6RenameMigration.isPending()) {
            synchronized (documentMigrationsLock) {
                documentMigrationsDeferred = true;
            }
            LOGGER.error("Deferring the V6 Qute, channel connector and workspace access-index migrations: the V6 rename "
                    + "migration has not completed, and they would run against collections it has not populated yet. "
                    + "They run as soon as the deployment sweep sees the rename migration complete.");
        } else {
            runDocumentMigrations();
        }

        migrationManager.startMigrationIfFirstTimeRun(() -> {
            startupCallbackRan.set(true);
            startupMigrationsRunning.set(false);
            checkDeployments();
            if (v6RenameMigration.isPending()) {
                // The sweep above was parked, so nothing has been deployed yet.
                // Reporting ready now would send traffic to an instance with no agents.
                //
                // Deferred rather than abandoned. isPending() is fail-safe: a
                // migration-log read that fails answers "pending", because guessing
                // "not pending" would let the sweep read every agent config as
                // deleted and retire its deployment row. That is the right answer
                // for the sweep and the wrong one to hang readiness on for ever —
                // this used to be the only call site of setAgentsReadiness in the
                // process, so a read that failed in this one second left the
                // instance permanently not-ready while checkDeployments() deployed
                // its agents ten seconds later and served them correctly.
                LOGGER.error("Not reporting ready yet: the V6 rename migration has not completed, so no agent has "
                        + "been deployed. Its own error is logged above. The scheduled deployment sweep reports "
                        + "ready if the migration completes; if it does not, resolve it and restart.");
                readinessDeferred.set(true);
                return;
            }
            reportReady();
        });
    }

    private void runDocumentMigrations() {
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
        try {
            // Last of the migrations: it re-derives the access index from whatever the
            // earlier ones left behind, so running it before them would index stale state.
            workspaceAccessIndexMigration.runIfNeeded();
        } catch (Exception e) {
            LOGGER.error("Workspace access-index migration failed — will retry on next startup", e);
        }
    }

    /**
     * Runs the document migrations startup parked, if it parked them — before the
     * sweep deploys agents and before readiness is granted, the same order the
     * startup path uses. A caller that arrives while another is running them blocks
     * here until they have finished, then finds nothing left to do.
     */
    private void runDeferredDocumentMigrations() {
        synchronized (documentMigrationsLock) {
            if (!documentMigrationsDeferred) {
                return;
            }
            LOGGER.info("The V6 rename migration has completed — running the deferred document-level migrations.");
            try {
                runDocumentMigrations();
            } finally {
                documentMigrationsDeferred = false;
            }
        }
    }

    /**
     * Grants readiness once, and logs the line that says so in the same place.
     *
     * <p>
     * The two used to be independent: the flag was set inside the startup lambda
     * and {@code E.D.D.I is ready!} was logged afterwards from a second
     * {@code isPending()} call, so a migration-log read that failed in the first
     * and succeeded in the second logged "ready" against an instance whose
     * readiness flag was false.
     * </p>
     */
    private void reportReady() {
        agentsReadiness.setAgentsReadiness(true);
        LOGGER.info("E.D.D.I is ready!");
    }

    // delayed, not delay: Scheduled#delayUnit defaults to MINUTES, so the numeric
    // form meant this first ran ten minutes after boot rather than ten seconds.
    // SKIP because a slow pass must not overlap the next tick and double-deploy.
    @Scheduled(every = "10s", delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void checkDeployments() {
        synchronized (sweepLock) {
            sweep();
        }
    }

    private void sweep() {
        if (startupMigrationsRunning.get()) {
            LOGGER.debug("Deployment sweep parked: the startup migrations are still running.");
            return;
        }
        // This sweep retires — deletes — the deployment row of any agent whose config
        // it cannot read, and it runs on its own schedule rather than after the
        // startup migrations. While the 6.x rename migration is outstanding the agent
        // configs are still in `bots` and `agents` does not exist, so every deployed
        // agent reads as deleted: on a real staging upgrade this deleted the
        // deployment rows of both deployed agents before the migration had started. A
        // migration that failed keeps the sweep parked deliberately — the collection
        // names are then genuinely unknown, and not deploying beats deleting the
        // record of what was deployed.
        if (v6RenameMigration.isPending()) {
            // Logged once: this runs every ten seconds, and a migration that takes minutes
            // would otherwise print the same warning over and over.
            if (sweepParkedLogged.compareAndSet(false, true)) {
                LOGGER.warn("Deployment sweep parked: the V6 rename migration has not completed, so agent configs "
                        + "cannot be read yet and every deployment would look stale. It stays parked until the "
                        + "migration completes.");
            } else {
                LOGGER.debug("Deployment sweep still parked: the V6 rename migration has not completed.");
            }
            return;
        }
        runDeferredDocumentMigrations();
        try {
            List<DeploymentInfo> meantToBeDeployed = deploymentStore.readDeploymentInfos(deployed).stream()
                    .filter(deploymentInfo -> deploymentInfo.getAgentId() != null && deploymentInfo.getAgentVersion() != null).toList();
            // A deployment that is no longer meant to be deployed is no longer failing.
            failingDeployments.keySet().retainAll(meantToBeDeployed);
            if (clustered()) {
                reconcileUndeployed(meantToBeDeployed);
            }
            Instant now = clock.instant();
            meantToBeDeployed.stream()
                    .filter(deploymentInfo -> !this.deploymentInfos.contains(deploymentInfo))
                    .filter(deploymentInfo -> isRetryDue(deploymentInfo, now)).forEach(deploymentInfo -> {
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

                            Deployment.Status outcome = deployedStatus(deploymentInfo);
                            if (outcome == Deployment.Status.ERROR) {
                                recordFailure(deploymentInfo, "the deployment ended in ERROR — its cause is logged above", null);
                                return;
                            }
                            if (outcome != Deployment.Status.READY) {
                                // Still in progress elsewhere (a REST deploy), or not registered:
                                // not done, and not a failure either — the next sweep looks again.
                                LOGGER.debugf("Deployment of agent %s version %d not confirmed yet (%s); checked again on the next sweep",
                                        deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion(), outcome);
                                return;
                            }
                            recordSuccess(deploymentInfo);
                            this.deploymentInfos.add(deploymentInfo);

                            lintInertHitlConfig(deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion());
                        } catch (ServiceException | IllegalAccessException e) {
                            recordFailure(deploymentInfo, e.getLocalizedMessage(), e);
                        } catch (Exception e) {
                            // Catch any other exception (e.g. IllegalStateException wrapping
                            // ResourceNotFoundException) so one broken Agent doesn't block all others
                            // If the root cause is a missing resource, auto-clean the stale record
                            if (!isCausedByResourceNotFound(e)) {
                                recordFailure(deploymentInfo, e.getMessage(), null);
                            } else {
                                LOGGER.warn(format("Agent config not found for id=%s version=%d — marking deployment as undeployed",
                                        deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion()));
                                deploymentStore.setDeploymentInfo(deploymentInfo.getEnvironment().toString(), deploymentInfo.getAgentId(),
                                        deploymentInfo.getAgentVersion(), undeployed);
                            }
                        }
                    });
            // The sweep ran to completion with the migration no longer pending, so
            // the agents this instance is supposed to serve are deployed. If the
            // startup path had to defer readiness, this is where it is granted —
            // there is no other scheduled path that would, and without this the
            // instance stays not-ready for the life of the process after a single
            // transient migration-log read failure at boot.
            if (readinessDeferred.compareAndSet(true, false)) {
                reportReady();
            }
            agentsReadiness.setAgentsInError(failingDeployments.keySet().stream()
                    .map(info -> info.getEnvironment() + "/" + info.getAgentId() + "/" + info.getAgentVersion()).sorted().toList());
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean isRetryDue(DeploymentInfo deploymentInfo, Instant now) {
        RetryState state = failingDeployments.get(deploymentInfo);
        return state == null || !now.isBefore(state.nextAttempt());
    }

    /**
     * The status the registry reports after {@code deployAgent} returned, or
     * {@code null} when it has none. {@code deployAgent} reports a workflow that
     * cannot be built by leaving the agent in ERROR and returning normally, and it
     * returns at once when another caller holds the deployment IN_PROGRESS — so
     * only READY means this deployment is done.
     */
    private Deployment.Status deployedStatus(DeploymentInfo deploymentInfo) {
        try {
            var agent = agentFactory.getAgent(deploymentInfo.getEnvironment(), deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion());
            return agent == null ? null : agent.getDeploymentStatus();
        } catch (Exception e) {
            return null;
        }
    }

    private void recordFailure(DeploymentInfo deploymentInfo, String cause, Throwable error) {
        RetryState previous = failingDeployments.get(deploymentInfo);
        int failures = previous == null ? 1 : previous.failures() + 1;
        Duration delay = retryDelay(failures);
        failingDeployments.put(deploymentInfo, new RetryState(failures, clock.instant().plus(delay)));
        String message = format("Agent %s version %d (%s) failed to deploy: %s. Retrying with a growing delay (next in %ds, at most "
                + "every %d min) while its deployment record says it is deployed.", deploymentInfo.getAgentId(),
                deploymentInfo.getAgentVersion(), deploymentInfo.getEnvironment(), cause, delay.toSeconds(), MAX_RETRY_DELAY.toMinutes());
        if (previous == null) {
            // Once per state change: the retries below would otherwise repeat this
            // every few seconds for as long as the agent stays broken.
            LOGGER.error(message, error);
        } else {
            LOGGER.debugf("%s (failure %d)", message, failures);
        }
    }

    private void recordSuccess(DeploymentInfo deploymentInfo) {
        RetryState previous = failingDeployments.remove(deploymentInfo);
        if (previous != null) {
            LOGGER.infof("Agent %s version %d (%s) deployed after %d failed attempt(s)", deploymentInfo.getAgentId(),
                    deploymentInfo.getAgentVersion(), deploymentInfo.getEnvironment(), previous.failures());
        }
    }

    /**
     * {@link #FIRST_RETRY_DELAY}, doubled per further failure, capped at
     * {@link #MAX_RETRY_DELAY}.
     */
    static Duration retryDelay(int failures) {
        Duration delay = FIRST_RETRY_DELAY;
        for (int i = 1; i < failures && delay.compareTo(MAX_RETRY_DELAY) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
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

    // delayed, not delay: the numeric form is MINUTES, so 300 meant five hours
    // after
    // boot. A pod restarted more often than that never ran this at all, leaving
    // superseded agent versions deployed and idle conversations never ended.
    @Scheduled(every = "24h", delayed = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
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
                                            // Evaluated here, after every current version has been
                                            // deployed above, so a newer compatible version is ready.
                                            if (retireIfConversationsCanMove(environment, agentId, agentVersion)) {
                                                return;
                                            }
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

    /**
     * Retires an old version at once when its conversations have somewhere to go: a
     * newer version of the same compatibility generation that is ready on this
     * node.
     * <p>
     * Without this the sweep treated such a version like any other old one — it
     * ENDED its idle conversations and kept it deployed while any were left. Those
     * conversations can simply continue on the newer version whenever they return,
     * so ending them destroys exactly what version following exists to keep, and
     * keeping the old version deployed for them serves no one. A version without a
     * generation, or with no newer compatible version ready, takes the old path.
     *
     * @return {@code true} when the version was undeployed here
     */
    boolean retireIfConversationsCanMove(Environment environment, String agentId, Integer agentVersion)
            throws ServiceException, IllegalAccessException {
        Integer generation;
        try {
            var configuration = agentStore.read(agentId, agentVersion);
            generation = configuration != null ? configuration.getCompatibilityGeneration() : null;
        } catch (ResourceNotFoundException | ResourceStoreException | RuntimeException e) {
            return false;
        }
        if (generation == null) {
            return false;
        }
        IAgent successor = agentFactory.getLatestReadyAgentOfGeneration(environment, agentId, generation);
        if (successor == null || successor.getAgentVersion() <= agentVersion) {
            return false;
        }
        agentFactory.undeployAgent(environment, agentId, agentVersion);
        deploymentStore.setDeploymentInfo(environment.toString(), agentId, agentVersion, undeployed);
        LOGGER.info(format("Retired Agent (id: %s, version: %d): its conversations continue on compatible version %d", sanitize(agentId),
                agentVersion, successor.getAgentVersion()));
        return true;
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
        if (!idleEndingEnabled()) {
            // Disabled (see idleEndingEnabled): no conversation is loaded, let alone
            // ended. The caller's undeploy check still runs, and keeps this version
            // deployed while it has any conversation open.
            return;
        }

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

            // Age comes from the CONVERSATION's own newest step timestamp.
            //
            // It used to come from the AGENT document's lastModifiedOn, which is not a
            // property of the conversation at all: every conversation on a given agent
            // version shared one age, so a conversation the user was talking in an hour
            // ago counted as idle whenever its agent config happened to be old. That
            // mis-signal was masked by the arithmetic bug in isOlderThanDays below —
            // fixing the arithmetic alone would have started ENDing live conversations,
            // which is why both are corrected together.
            var lastInteraction = lastInteractionOf(conversationMemory);
            if (lastInteraction == null) {
                // No step ever carried a timestamp. Fall back to the agent descriptor,
                // and skip entirely when even that is unavailable: "cannot prove it is
                // idle" must never end a conversation.
                //
                // Read LAZILY, and never fatally. This lookup used to run
                // unconditionally at the top of the loop, but readDescriptor THROWS for
                // a deleted agent — and manageAgentDeployments deliberately routes
                // deleted agents down this very path (getCurrentResourceId throwing is
                // how it decides the agent is gone). One deleted agent therefore
                // aborted the whole sweep, including conversations that carry a
                // perfectly good timestamp of their own and never needed the descriptor.
                Instant descriptorLastModified = descriptorLastModifiedOf(conversationMemory);
                if (descriptorLastModified == null) {
                    continue;
                }
                lastInteraction = descriptorLastModified;
            }
            // ONE calendar date drives both the decision and the message, and ONE
            // reference "today" drives both this check and the age it reports. The
            // sweep expires by LocalDate, so reporting elapsed 24-hour periods could
            // end a conversation on its 30th calendar day while the log said 29 — and
            // evaluating LocalDate.now() twice could disagree across midnight.
            var lastInteractionDate = lastInteraction.atZone(ZoneId.systemDefault()).toLocalDate();
            var today = LocalDate.now();

            var isOlderThanMaximumAmountOfDays = isOlderThanDays(lastInteractionDate, maximumLifeTimeOfIdleConversationsInDays, today);

            if (isOlderThanMaximumAmountOfDays) {
                String conversationId = conversationMemory.getId();
                // Conditional on the state this snapshot was loaded with, NOT an
                // unconditional write. The snapshots were read at the top of the sweep,
                // so between that read and this write a user can send a turn, or the
                // conversation can pause at an HITL gate — and an unconditional ENDED
                // would destroy a live turn or a pending approval. This was a latent
                // check-then-act race while the arithmetic bug kept the sweep mostly
                // inert; correcting the arithmetic is exactly what makes it fire.
                // Re-read immediately before the write. The snapshots were loaded at
                // the top of the sweep, so without this the window in which a user can
                // send a turn spans the entire scan.
                //
                // The state CAS alone is not enough: a turn that starts AND completes
                // inside the window returns the state to the value we observed, so the
                // CAS succeeds against a conversation that is demonstrably active. The
                // age is the ABA-resistant part — a completed turn moves the newest
                // step timestamp, so re-checking it here catches exactly that case.
                //
                // What remains is a turn completing entirely between this re-read and
                // the CAS below. Closing that needs a revision-conditional update in
                // IConversationMemoryStore (and in both backends) rather than a
                // state-only CAS; that is a store-contract change, deliberately not
                // bundled here.
                if (!stillIdle(conversationId, maximumLifeTimeOfIdleConversationsInDays, today)) {
                    LOGGER.info(format("Skipped ending conversation (id: %s): it became active while the sweep was running",
                            conversationId));
                    continue;
                }
                ConversationState observedState = conversationMemory.getConversationState();
                if (!conversationMemoryStore.compareAndSetState(conversationId, observedState, ConversationState.ENDED)) {
                    LOGGER.info(format("Skipped ending conversation (id: %s): its state changed from %s since the sweep read it",
                            conversationId, observedState));
                    continue;
                }
                var message = format(
                        "Ended conversation (id: %s) with Agent (name: %s, id: %s, version: %d) "
                                + "because it has been idle for %d days, longer than the maximum idle time of %d days",
                        conversationId, descriptorNameOf(conversationMemory), agentId, agentVersion,
                        DAYS.between(lastInteractionDate, today), maximumLifeTimeOfIdleConversationsInDays);

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
     * Re-reads the conversation and re-checks its age against the same limit and
     * reference date the sweep used.
     * <p>
     * A store failure answers {@code false}: unable to confirm it is still idle is
     * not permission to end it.
     */
    private boolean stillIdle(String conversationId, int maxIdleDays, LocalDate today) {
        try {
            var fresh = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (fresh == null) {
                return false;
            }
            Instant freshLastInteraction = lastInteractionOf(fresh);
            if (freshLastInteraction == null) {
                // No conversation-side signal on the re-read; the original decision
                // already used the descriptor fallback, so nothing new to check.
                return true;
            }
            return isOlderThanDays(freshLastInteraction.atZone(ZoneId.systemDefault()).toLocalDate(), maxIdleDays, today);
        } catch (Exception e) {
            LOGGER.warn(format("Could not re-check conversation (id: %s) before ending it (%s) — leaving it alone",
                    conversationId, e.getMessage()));
            return false;
        }
    }

    /**
     * The agent descriptor's {@code lastModifiedOn}, or {@code null} when it cannot
     * be read. Never throws: the descriptor is a fallback age signal and a
     * best-effort display name, and a deleted agent — which this sweep is
     * specifically reached for — makes {@code readDescriptor} throw.
     */
    private Instant descriptorLastModifiedOf(ConversationMemorySnapshot conversationMemory) {
        try {
            var descriptor = documentDescriptorStore.readDescriptor(conversationMemory.getAgentId(), conversationMemory.getAgentVersion());
            return descriptor != null && descriptor.getLastModifiedOn() != null ? descriptor.getLastModifiedOn().toInstant() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The agent's display name for the log line, or its id when unavailable. */
    private String descriptorNameOf(ConversationMemorySnapshot conversationMemory) {
        try {
            var descriptor = documentDescriptorStore.readDescriptor(conversationMemory.getAgentId(), conversationMemory.getAgentVersion());
            return descriptor != null && descriptor.getName() != null ? descriptor.getName() : conversationMemory.getAgentId();
        } catch (Exception e) {
            return conversationMemory.getAgentId();
        }
    }

    /**
     * The newest timestamp any data item in the conversation carries, or
     * {@code null} when nothing is timestamped. This is the conversation's own
     * last-interaction time: every turn writes step data through
     * {@code ConversationStep}, and {@code Data} stamps each entry at construction.
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
     * This was written against {@link Period}, which normalizes into
     * years/months/days — and the check only ever looked at the years and days
     * components, never the months. For a 35-day-old date and a 30-day limit,
     * {@code Period.between(now, date)} is {@code P-1M-4D}, so the test read
     * {@code -4 <= -30} and answered "not old". Whole bands of ages between
     * {@code days} and one year were therefore never reaped, and the ones that were
     * passed only by coincidence of where the month boundary fell. Day arithmetic
     * has no such components.
     */
    static boolean isOlderThanDays(final LocalDate date, final int days) {
        return isOlderThanDays(date, days, LocalDate.now());
    }

    /**
     * Reference-date overload. {@code today} is a parameter so a test can pin both
     * sides of the comparison: with {@code LocalDate.now()} evaluated independently
     * in the test and in this method, a case built at 23:59:59.999 and evaluated at
     * 00:00:00.001 shifts by a day, which is exactly the kind of once-a-day flake
     * that gets a boundary test deleted rather than fixed.
     */
    static boolean isOlderThanDays(final LocalDate date, final int days, final LocalDate today) {
        return DAYS.between(date, today) >= days;
    }

    private interface UndeploymentExecutor {
        void attemptUndeploy();
    }
}
