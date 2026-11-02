/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The entry point for agent test runs, shared by REST and MCP: checks the
 * caller's rights on the request thread, makes sure the agent version under
 * test is the one the {@code test} environment serves, records the run as
 * {@code RUNNING} and hands it to {@link AgentTestRunner} on a virtual thread.
 * <h3>Rights</h3> Starting a run deploys the agent to {@code test}, so it takes
 * exactly what a deploy takes: the deploy itself goes through
 * {@link IRestAgentAdministration#deployAgent}, which checks EDIT on the agent,
 * the space-reference guard and the tenant agent quota against the caller. The
 * suite must be readable by the caller. Reading a run takes VIEW on its agent;
 * cancelling one takes EDIT on it, what starting one takes.
 * <h3>The production environment is never touched</h3> Only {@code test} is
 * deployed to, without recording the deployment ({@code autoDeploy=false}), and
 * every test conversation runs there.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class AgentTestService {

    private static final Logger LOGGER = Logger.getLogger(AgentTestService.class);

    private final IAgentTestSuiteStore suiteStore;
    private final IAgentTestRunStore runStore;
    private final AgentTestRunner runner;
    private final IRestAgentAdministration agentAdministration;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final ResourceAccessGuard accessGuard;
    private final Instance<SecurityIdentity> identity;
    private final ClusterConfig clusterConfig;
    private final NodeIdentity nodeIdentity;
    private final Semaphore concurrentRuns;
    private final int maxConcurrentRuns;

    @Inject
    public AgentTestService(IAgentTestSuiteStore suiteStore, IAgentTestRunStore runStore, AgentTestRunner runner,
            IRestAgentAdministration agentAdministration, IAgentFactory agentFactory, IAgentStore agentStore, ResourceAccessGuard accessGuard,
            Instance<SecurityIdentity> identity, ClusterConfig clusterConfig, NodeIdentity nodeIdentity,
            @ConfigProperty(name = "eddi.agent-tests.max-concurrent-runs", defaultValue = "2") int maxConcurrentRuns) {
        this.suiteStore = suiteStore;
        this.runStore = runStore;
        this.runner = runner;
        this.agentAdministration = agentAdministration;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.accessGuard = accessGuard;
        this.identity = identity;
        this.clusterConfig = clusterConfig;
        this.nodeIdentity = nodeIdentity;
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.concurrentRuns = new Semaphore(this.maxConcurrentRuns);
    }

    /**
     * Validates, deploys to {@code test}, stores the {@code RUNNING} record and
     * starts the run in the background.
     *
     * @param suiteVersion
     *            null for the suite's current version
     * @param agentVersion
     *            null for the agent's current version
     * @return the {@code RUNNING} record, with its id
     */
    public AgentTestRun startRun(String suiteId, Integer suiteVersion, String agentId, Integer agentVersion, boolean keepConversations) {
        if (isBlank(suiteId) || isBlank(agentId)) {
            throw new BadRequestException("suiteId and agentId are required.");
        }
        if (clusterConfig != null && clusterConfig.isNats()) {
            // Tool mocks and the run itself live in this JVM, and cluster mode can route
            // a test conversation's turn to another node, where every tool call would be
            // refused for want of a session — a failure the agent did not cause.
            throw new ClientErrorException("Agent tests are not yet supported in cluster mode (eddi.messaging.type=nats): their tool "
                    + "mocks live on the node that runs the test, and a turn routed to another node could not use them. Run the "
                    + "suite against a single-node deployment.", Response.Status.CONFLICT);
        }
        accessGuard.requireAccess(suiteId, AccessLevel.VIEW, "agent test suite");

        int resolvedSuiteVersion = suiteVersion != null ? suiteVersion : currentVersion(suiteStore, suiteId, "Test suite");
        AgentTestSuite suite;
        try {
            suite = suiteStore.read(suiteId, resolvedSuiteVersion);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Test suite " + suiteId + " version " + resolvedSuiteVersion + " not found.");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Could not read the test suite: " + e.getMessage(), e);
        }
        if (suite == null || suite.cases() == null || suite.cases().isEmpty()) {
            throw new BadRequestException("The test suite has no cases.");
        }

        int resolvedAgentVersion = agentVersion != null ? agentVersion : currentVersion(agentStore, agentId, "Agent");

        // The slot first, the deploy second: a request that is going to be refused
        // for capacity must not first deploy a version to `test` — which changes what
        // every other client of that environment talks to — and then be turned away.
        if (!concurrentRuns.tryAcquire()) {
            throw new ClientErrorException("At most " + maxConcurrentRuns + " agent test runs may execute at once "
                    + "(eddi.agent-tests.max-concurrent-runs). Retry when one has finished.", Response.Status.TOO_MANY_REQUESTS);
        }
        AgentTestRun run;
        try {
            deployToTest(agentId, resolvedAgentVersion);
            var pending = new AgentTestRun(null, suiteId, resolvedSuiteVersion, suite.name(), agentId, resolvedAgentVersion,
                    AgentTestRun.Status.RUNNING, principal(), System.currentTimeMillis(), null, null, null, suite.effectiveMaxCostPerRun(),
                    null, null, null, null, null, null, keepConversations, null, owner(), System.currentTimeMillis());
            run = pending.withId(runStore.create(pending));
        } catch (RuntimeException e) {
            concurrentRuns.release();
            throw e;
        } catch (Exception e) {
            concurrentRuns.release();
            throw new IllegalStateException("Could not record the test run: " + e.getMessage(), e);
        }

        final AgentTestRun started = run;
        try {
            Thread.ofVirtual().name("agent-test-run-" + started.id()).start(() -> {
                try {
                    runner.run(started, suite);
                } finally {
                    concurrentRuns.release();
                }
            });
        } catch (RuntimeException e) {
            concurrentRuns.release();
            throw e;
        }
        LOGGER.infof("Agent test run %s started: suite %s v%d against agent %s v%d", sanitize(started.id()), sanitize(suiteId),
                resolvedSuiteVersion, sanitize(agentId), resolvedAgentVersion);
        return started;
    }

    /** One run; VIEW on its agent. */
    public AgentTestRun readRun(String runId) {
        AgentTestRun run;
        try {
            run = runStore.read(runId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Agent test run " + runId + " not found.");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Could not read the test run: " + e.getMessage(), e);
        }
        accessGuard.requireAccess(run.agentId(), AccessLevel.VIEW, "agent");
        return run;
    }

    /**
     * Asks a running run to stop; EDIT on its agent, like starting one. The run
     * stops cooperatively — no further turn starts, a waiting turn is abandoned,
     * its conversations are ended and deleted — and is stored {@code CANCELLED}
     * moments later, which a poll of the run shows.
     *
     * @return the run as it is now (still {@code RUNNING})
     */
    public AgentTestRun cancelRun(String runId) {
        AgentTestRun run;
        try {
            run = runStore.read(runId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Agent test run " + runId + " not found.");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Could not read the test run: " + e.getMessage(), e);
        }
        accessGuard.requireAccess(run.agentId(), AccessLevel.EDIT, "agent");
        if (run.status() != AgentTestRun.Status.RUNNING) {
            throw new ClientErrorException("Agent test run " + runId + " has already finished (" + run.status() + ").",
                    Response.Status.CONFLICT);
        }
        if (!runner.cancel(runId, principal())) {
            throw new ClientErrorException("Agent test run " + runId + " is not executing on this node"
                    + (run.ownerNode() != null ? " (it runs on " + run.ownerNode() + ")" : "")
                    + ". Cancel it on the node that runs it; a run whose node has stopped is closed as ABORTED automatically.",
                    Response.Status.CONFLICT);
        }
        LOGGER.infof("Agent test run %s cancelled by %s", sanitize(runId), sanitize(principal()));
        return run;
    }

    /** The runs of one agent, newest first; VIEW on the agent. */
    public List<AgentTestRun> listRuns(String agentId, int index, int limit) {
        if (isBlank(agentId)) {
            throw new BadRequestException("agentId is required.");
        }
        accessGuard.requireAccess(agentId, AccessLevel.VIEW, "agent");
        try {
            return runStore.listByAgent(agentId, Math.max(0, index), Math.clamp(limit, 1, 100));
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Could not list the test runs: " + e.getMessage(), e);
        }
    }

    /**
     * Deploys {@code version} to {@code test} (a no-op when it is already there)
     * and checks it is the version a new test conversation will run on.
     */
    private void deployToTest(String agentId, int version) {
        Response response = agentAdministration.deployAgent(Environment.test, agentId, version, false, true);
        Object status = response.getEntity() instanceof Map<?, ?> body ? body.get("status") : null;
        if (response.getStatus() != 200 || !"READY".equals(String.valueOf(status))) {
            Object reason = response.getEntity() instanceof Map<?, ?> body ? body.get("error") : null;
            throw new ClientErrorException("Agent " + agentId + " version " + version + " could not be deployed to the test environment"
                    + " (status " + status + (reason != null ? ": " + reason : "") + ").", Response.Status.CONFLICT);
        }
        IAgent served;
        try {
            served = agentFactory.getLatestReadyAgent(Environment.test, agentId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the test deployment of agent " + agentId + ": " + e.getMessage(), e);
        }
        if (served != null && served.getAgentVersion() != null && served.getAgentVersion() != version) {
            // Conversations always start on the newest ready version of an environment,
            // so this run would silently test the wrong version. Undeploying it is not
            // this service's call to make.
            throw new ClientErrorException("Version " + served.getAgentVersion() + " of agent " + agentId
                    + " is also deployed to the test environment, and new conversations start on the newest ready version — this run "
                    + "would not test version " + version + ". Undeploy version " + served.getAgentVersion()
                    + " from test, or run the suite against it.", Response.Status.CONFLICT);
        }
    }

    private static int currentVersion(IResourceStore<?> store, String id, String label) {
        try {
            return store.getCurrentResourceId(id).getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(label + " " + id + " not found.");
        }
    }

    /** The node and boot that execute runs started here. */
    private String owner() {
        return nodeIdentity != null ? nodeIdentity.toString() : null;
    }

    private String principal() {
        try {
            if (identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous() && current.getPrincipal() != null) {
                    return current.getPrincipal().getName();
                }
            }
        } catch (RuntimeException e) {
            // no request-scoped identity
        }
        return "anonymous";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
