/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
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

import java.util.List;

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
 * {@code RUNNING} record may well be live on another node. A run records its
 * owner ({@code <nodeId>/<bootId>}) and refreshes {@code heartbeatAt} while it
 * executes ({@link AgentTestRunner}). A run is an orphan when
 * <ul>
 * <li>an earlier boot of this node owned it — that boot is gone; or</li>
 * <li>its heartbeat (or, for a run recorded before 6.6 with none, its start) is
 * older than {@link #STALE_AFTER_MS}, whoever owned it.</li>
 * </ul>
 * A run of this very boot is never touched. The sweep runs at startup and then
 * every few minutes, so a run whose node never comes back — or came back under
 * another host name — is still closed.
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
     * A heartbeat older than this means the owner is gone: six missed beats of
     * {@link AgentTestRunner#HEARTBEAT_INTERVAL_MS}.
     */
    static final long STALE_AFTER_MS = 6 * AgentTestRunner.HEARTBEAT_INTERVAL_MS;

    private final IAgentTestRunStore runStore;
    private final IAgentTestSuiteStore suiteStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ConversationPurgeService purgeService;
    private final AgentTestRunner runner;
    private final NodeIdentity nodeIdentity;

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

    @Scheduled(every = "3m", delayed = "3m", identity = "agent-test-orphan-sweep", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepPeriodically() {
        sweep("in the periodic sweep");
    }

    private void sweep(String when) {
        try {
            int aborted = abortOrphanedRuns(System.currentTimeMillis());
            if (aborted > 0) {
                LOGGER.warnf("Marked %d agent test run(s) ABORTED %s: the node executing them stopped before they finished", aborted, when);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not close orphaned agent test runs %s: %s", when, e.getMessage());
        }
    }

    /** @return how many runs were marked aborted */
    int abortOrphanedRuns(long now) throws Exception {
        List<AgentTestRun> running = runStore.listByStatus(AgentTestRun.Status.RUNNING, MAX_RUNS_PER_SWEEP);
        int aborted = 0;
        for (AgentTestRun run : running) {
            if (!isOrphan(run, now)) {
                continue;
            }
            try {
                runStore.update(run.aborted(ABORT_REASON, System.currentTimeMillis()));
                aborted++;
            } catch (Exception e) {
                LOGGER.warnf("Could not mark agent test run %s aborted: %s", sanitize(run.id()), e.getMessage());
                continue;
            }
            if (!Boolean.TRUE.equals(run.keepConversations())) {
                deleteConversationsOf(run);
            }
        }
        return aborted;
    }

    /** See the class comment. */
    boolean isOrphan(AgentTestRun run, long now) {
        if (runner != null && runner.isActive(run.id())) {
            return false;
        }
        String self = nodeIdentity != null ? nodeIdentity.toString() : null;
        String owner = run.ownerNode();
        if (owner != null && owner.equals(self)) {
            // This boot's own run, between its record being created and its runner
            // starting: live, not an orphan.
            return false;
        }
        if (owner != null && nodeIdentity != null && owner.startsWith(nodeIdentity.nodeId() + "/")) {
            return true; // an earlier boot of this node
        }
        Long lastSign = run.heartbeatAt() != null ? run.heartbeatAt() : run.startedAt();
        return lastSign == null || now - lastSign > STALE_AFTER_MS;
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
