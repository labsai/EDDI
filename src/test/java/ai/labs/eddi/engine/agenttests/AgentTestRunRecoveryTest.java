/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("AgentTestRunRecovery")
class AgentTestRunRecoveryTest {

    private static final String RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SUITE_ID = "0123456789abcdef01234567";
    /**
     * Long after every fixture's start: any run without a fresh heartbeat is stale.
     */
    private static final long NOW = 10_000_000L;
    private static final NodeIdentity SELF = new NodeIdentity("node-a", "boot0002");

    private IAgentTestRunStore runStore;
    private IAgentTestSuiteStore suiteStore;
    private IConversationMemoryStore memoryStore;
    private ConversationPurgeService purgeService;
    private AgentTestRunner runner;
    private AgentTestRunRecovery recovery;

    @BeforeEach
    void setUp() throws Exception {
        runStore = mock(IAgentTestRunStore.class);
        suiteStore = mock(IAgentTestSuiteStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
        purgeService = mock(ConversationPurgeService.class);
        runner = mock(AgentTestRunner.class);
        recovery = new AgentTestRunRecovery(runStore, suiteStore, memoryStore, purgeService, runner, SELF);
        when(suiteStore.read(SUITE_ID, 2)).thenReturn(new AgentTestSuite("s", null, null, null, null,
                List.of(new TestCase("c", null, 2, null, List.of(new Turn("hi", null, null))))));
    }

    private static AgentTestRun running(boolean keep) {
        return owned(keep, null, null);
    }

    private static AgentTestRun owned(boolean keep, String ownerNode, Long heartbeatAt) {
        return new AgentTestRun(RUN_ID, SUITE_ID, 2, "s", "abcdefabcdefabcdefabcdef", 1, AgentTestRun.Status.RUNNING, "alice", 1_000L, null,
                null, null, 1.0, null, null, null, null, null, null, keep, null, ownerNode, heartbeatAt);
    }

    private void listed(AgentTestRun run) throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(run));
    }

    /**
     * Replicas sharing one database without cluster mode: one restarting must not
     * abort — and delete the conversations of — a run another is executing.
     */
    @Test
    @DisplayName("another node's run with a fresh heartbeat is left alone")
    void anotherNodesLiveRunIsLeftAlone() throws Exception {
        listed(owned(false, "node-b/boot0001", NOW - 1_000));

        assertEquals(0, recovery.abortOrphanedRuns(NOW));

        verify(runStore, never()).update(any());
        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("another node's run whose heartbeat went stale is aborted")
    void anotherNodesStaleRunIsAborted() throws Exception {
        listed(owned(true, "node-b/boot0001", NOW - AgentTestRunRecovery.STALE_AFTER_MS - 1));

        assertEquals(1, recovery.abortOrphanedRuns(NOW));
        verify(runStore).update(any(AgentTestRun.class));
    }

    @Test
    @DisplayName("an earlier boot of this node owned it: aborted at once, heartbeat or not")
    void earlierBootOfThisNodeIsAborted() throws Exception {
        listed(owned(true, "node-a/boot0001", NOW - 1_000));

        assertEquals(1, recovery.abortOrphanedRuns(NOW));
    }

    @Test
    @DisplayName("this boot's own run, and a run active in this JVM, are never touched")
    void ownLiveRunIsLeftAlone() throws Exception {
        listed(owned(false, SELF.toString(), 1_000L));
        assertEquals(0, recovery.abortOrphanedRuns(NOW));

        listed(owned(false, "node-b/boot0001", 1_000L));
        when(runner.isActive(RUN_ID)).thenReturn(true);
        assertEquals(0, recovery.abortOrphanedRuns(NOW));
        verify(runStore, never()).update(any());
    }

    @Test
    @DisplayName("a run recorded before owners existed counts from its start")
    void legacyRunCountsFromItsStart() throws Exception {
        assertFalse(recovery.isOrphan(running(false), 1_000L + AgentTestRunRecovery.STALE_AFTER_MS));
        assertTrue(recovery.isOrphan(running(false), 1_001L + AgentTestRunRecovery.STALE_AFTER_MS));
    }

    @Test
    @DisplayName("an orphaned RUNNING run is ABORTED with a reason, and its conversations are deleted")
    void orphanIsAbortedAndCleanedUp() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 1))).thenReturn(List.of("c1"));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 2))).thenReturn(List.of("c2"));

        assertEquals(1, recovery.abortOrphanedRuns(NOW));

        var captor = ArgumentCaptor.forClass(AgentTestRun.class);
        verify(runStore).update(captor.capture());
        assertEquals(AgentTestRun.Status.ABORTED, captor.getValue().status());
        assertEquals(AgentTestRunRecovery.ABORT_REASON, captor.getValue().error());
        assertNotNull(captor.getValue().finishedAt());
        verify(purgeService).purgeAgentTestConversation("c1", RUN_ID);
        verify(purgeService).purgeAgentTestConversation("c2", RUN_ID);
    }

    @Test
    void keptConversationsStayKept() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(true)));

        recovery.abortOrphanedRuns(NOW);

        verify(runStore).update(any(AgentTestRun.class));
        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("a cleanup failure never stops the sweep")
    void cleanupFailureIsContained() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(anyString())).thenThrow(new IllegalStateException("db down"));

        assertEquals(1, recovery.abortOrphanedRuns(NOW));
    }
}
