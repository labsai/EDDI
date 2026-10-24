/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Closes runs a stopped node left behind.
 * <p>
 * A run executes on a virtual thread of the node that accepted it, and nothing
 * else ever finishes it: after a restart or a crash its record would read
 * {@code RUNNING} forever, and a CI job polling it would wait forever too. Each
 * orphan is marked {@link AgentTestRun.Status#ABORTED} with the reason, and its
 * test conversations are deleted — best effort, through the purge that refuses
 * anything not belonging to that run.
 * <h3>Which RUNNING run is an orphan</h3> Several replicas can share one
 * database without cluster mode (runs are refused only under NATS), so a
 * {@code RUNNING} record may well be live on another node — or in another EDDI
 * process on the same host, which derives the same node id. A run records its
 * owner ({@code <nodeId>/<bootId>}) and refreshes {@code heartbeatAt} while it
 * executes ({@link AgentTestRunner}). A run is an orphan only when its
 * heartbeat has <em>stopped changing</em>: unchanged across this node's own
 * sweeps for {@link #STALE_AFTER_MS} — or {@link #SAME_NODE_STALE_AFTER_MS}
 * when the owner has this node's id but another boot, the likely restart of
 * this very process. The time is measured on this node's monotonic clock
 * between its own observations; the owner's timestamp is only compared for
 * equality, so clock skew between nodes cannot make a live run look stale. A
 * run of this very boot, or one executing in this JVM, is never touched.
 * <p>
 * The abort is a compare-and-swap on {@code heartbeatAt}: it lands only while
 * the record still holds the heartbeat that was seen to stop. A heartbeat that
 * resumes in the meantime wins, and the run's conversations are left alone.
 * Once the abort has landed the owner's next heartbeat fails the same way, and
 * the owner stops the run instead of writing {@code RUNNING} back
 * ({@link AgentTestRunner}). An owner that cannot write its heartbeat for that
 * long — a database outage on its side — therefore loses its run.
 * <p>
 * The sweep runs at startup (which only observes) and then every minute.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class AgentTestRunRecovery {

    private static final Logger LOGGER = Logger.getLogger(AgentTestRunRecovery.class);

    /** Runs closed per startup; anything beyond is closed at the next one. */
    static final int MAX_RUNS_PER_SWEEP = 500;

    static final String ABORT_REASON = "The node executing this run stopped before it finished (restart or crash); "
            + "its results are incomplete. Start the run again.";

    /**
     * A heartbeat unchanged this long means its owner is gone: six missed beats of
     * {@link AgentTestRunner#HEARTBEAT_INTERVAL_MS}.
     */
    static final long STALE_AFTER_MS = 6 * AgentTestRunner.HEARTBEAT_INTERVAL_MS;

    /**
     * The same for an owner with this node's id and another boot — most likely this
     * process before a restart, but possibly a second process on this host, which
     * is why a stopped heartbeat is still required: three missed beats.
     */
    static final long SAME_NODE_STALE_AFTER_MS = 3 * AgentTestRunner.HEARTBEAT_INTERVAL_MS;

    /** A RUNNING run's heartbeat as this node last saw it, and since when. */
    record Observation(Long heartbeatAt, long sinceNanos) {
    }

    private final IAgentTestRunStore runStore;
    private final IAgentTestSuiteStore suiteStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ConversationPurgeService purgeService;
    private final AgentTestRunner runner;
    private final NodeIdentity nodeIdentity;
    private final Map<String, Observation> observations = new ConcurrentHashMap<>();

    @Inject
    public AgentTestRunRecovery(IAgentTestRunStore runStore, IAgentTestSuiteStore suiteStore, IConversationMemoryStore conversationMemoryStore,
            ConversationPurgeService purgeService, AgentTestRunner runner, NodeIdentity nodeIdentity) {
        this.runStore = runStore;
        this.suiteStore = suiteStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.purgeService = purgeService;
        this.runner = runner;
        this.nodeIdentity = nodeIdentity;
    }

    void onStart(@Observes StartupEvent event) {
        sweep("at startup");
    }

    @Scheduled(every = "1m", delayed = "1m", identity = "agent-test-orphan-sweep", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepPeriodically() {
        sweep("in the periodic sweep");
    }

    private void sweep(String when) {
        try {
            int aborted = abortOrphanedRuns(System.nanoTime());
            if (aborted > 0) {
                LOGGER.warnf("Marked %d agent test run(s) ABORTED %s: the node executing them stopped before they finished", aborted, when);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not close orphaned agent test runs %s: %s", when, e.getMessage());
        }
    }

    /**
     * @param nowNanos
     *            this node's monotonic clock ({@link System#nanoTime})
     * @return how many runs were marked aborted
     */
    int abortOrphanedRuns(long nowNanos) throws Exception {
        List<AgentTestRun> running = runStore.listByStatus(AgentTestRun.Status.RUNNING, MAX_RUNS_PER_SWEEP);
        Set<String> listed = new HashSet<>();
        int aborted = 0;
        for (AgentTestRun run : running) {
            listed.add(run.id());
            if (!isOrphan(run, nowNanos)) {
                continue;
            }
            AgentTestRun closed = run.aborted(ABORT_REASON, System.currentTimeMillis());
            try {
                if (run.heartbeatAt() != null) {
                    runStore.updateIfHeartbeat(closed, run.heartbeatAt());
                } else {
                    // Recorded before heartbeats existed: nothing to compare against.
                    runStore.update(closed);
                }
                aborted++;
            } catch (IResourceStore.ResourceModifiedException | IResourceStore.ResourceNotFoundException e) {
                // The heartbeat moved (or the run finished) between the read and this
                // write: the owner is alive after all. Leave the run and its
                // conversations alone.
                LOGGER.infof("Agent test run %s changed while being closed; it is live, not aborted", sanitize(run.id()));
                continue;
            } catch (Exception e) {
                LOGGER.warnf("Could not mark agent test run %s aborted: %s", sanitize(run.id()), e.getMessage());
                continue;
            } finally {
                observations.remove(run.id());
            }
            if (!Boolean.TRUE.equals(run.keepConversations())) {
                deleteConversationsOf(run);
            }
        }
        observations.keySet().retainAll(listed);
        return aborted;
    }

    /** See the class comment. Records what it saw for the next sweep. */
    boolean isOrphan(AgentTestRun run, long nowNanos) {
        if (runner != null && runner.isActive(run.id())) {
            return false;
        }
        String owner = run.ownerNode();
        if (owner != null && nodeIdentity != null && owner.equals(nodeIdentity.toString())) {
            // This boot's own run, between its record being created and its runner
            // starting: live, not an orphan.
            return false;
        }
        Observation seen = observations.get(run.id());
        if (seen == null || !Objects.equals(seen.heartbeatAt(), run.heartbeatAt())) {
            observations.put(run.id(), new Observation(run.heartbeatAt(), nowNanos));
            return false;
        }
        boolean sameNode = owner != null && nodeIdentity != null && owner.startsWith(nodeIdentity.nodeId() + "/");
        long window = sameNode ? SAME_NODE_STALE_AFTER_MS : STALE_AFTER_MS;
        return nowNanos - seen.sinceNanos() > TimeUnit.MILLISECONDS.toNanos(window);
    }

    /**
     * The run's conversations are found by their deterministic user ids, so the
     * suite version the run used tells which ones can exist.
     */
    private void deleteConversationsOf(AgentTestRun run) {
        AgentTestSuite suite;
        try {
            suite = suiteStore.read(run.suiteId(), run.suiteVersion());
        } catch (Exception e) {
            LOGGER.warnf("Could not read suite %s of aborted run %s; its test conversations are left to retention: %s",
                    sanitize(run.suiteId()), sanitize(run.id()), e.getMessage());
            return;
        }
        if (suite == null || suite.cases() == null) {
            return;
        }
        int defaultRepetitions = suite.effectiveDefaults().repetitions();
        for (int c = 0; c < suite.cases().size(); c++) {
            var testCase = suite.cases().get(c);
            int repetitions = testCase.repetitions() != null ? testCase.repetitions() : defaultRepetitions;
            for (int r = 1; r <= repetitions; r++) {
                String userId = AgentTestConversations.userIdFor(run.id(), c, r);
                try {
                    for (String conversationId : conversationMemoryStore.getConversationIdsByUserId(userId)) {
                        purgeService.purgeAgentTestConversation(conversationId, run.id());
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Could not delete the test conversations of %s: %s", sanitize(userId), e.getMessage());
                }
            }
        }
    }
}
