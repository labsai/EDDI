/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import io.quarkus.runtime.StartupEvent;
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
 * {@code RUNNING} forever, and a CI job polling it would wait forever too. At
 * startup no run can be live on this node yet, and runs never execute in
 * cluster mode ({@code AgentTestService} refuses them there), so every
 * {@code RUNNING} record found now is an orphan. Each is marked
 * {@link AgentTestRun.Status#ABORTED} with the reason, and its test
 * conversations are deleted — best effort, through the purge that refuses
 * anything not belonging to that run.
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

    private final IAgentTestRunStore runStore;
    private final IAgentTestSuiteStore suiteStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ConversationPurgeService purgeService;

    @Inject
    public AgentTestRunRecovery(IAgentTestRunStore runStore, IAgentTestSuiteStore suiteStore, IConversationMemoryStore conversationMemoryStore,
            ConversationPurgeService purgeService) {
        this.runStore = runStore;
        this.suiteStore = suiteStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.purgeService = purgeService;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            int aborted = abortOrphanedRuns();
            if (aborted > 0) {
                LOGGER.warnf("Marked %d agent test run(s) ABORTED: they were RUNNING when this node last stopped", aborted);
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not close orphaned agent test runs at startup: %s", e.getMessage());
        }
    }

    /** @return how many runs were marked aborted */
    int abortOrphanedRuns() throws Exception {
        List<AgentTestRun> orphans = runStore.listByStatus(AgentTestRun.Status.RUNNING, MAX_RUNS_PER_SWEEP);
        int aborted = 0;
        for (AgentTestRun run : orphans) {
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
