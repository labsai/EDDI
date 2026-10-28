/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** Sweeps once to observe, then again {@code laterMs} later. */
    private int sweepTwice(long laterMs) throws Exception {
        assertEquals(0, recovery.abortOrphanedRuns(NOW), "a first sighting never aborts");
        return recovery.abortOrphanedRuns(NOW + ms(laterMs));
    }

    /**
     * Replicas sharing one database without cluster mode: one must not abort — and
     * delete the conversations of — a run another is executing, however far apart
     * their clocks are: only whether the heartbeat changed is compared.
     */
    @Test
    @DisplayName("another node's run whose heartbeat keeps changing is never aborted, whatever its clock says")
    void anotherNodesLiveRunIsLeftAlone() throws Exception {
        listed(owned(false, "node-b/boot0001", 1L)); // a clock far behind ours
        assertEquals(0, recovery.abortOrphanedRuns(NOW));
        listed(owned(false, "node-b/boot0001", 2L));
        assertEquals(0, recovery.abortOrphanedRuns(NOW + ms(AgentTestRunRecovery.STALE_AFTER_MS + 1)));

        verify(runStore, never()).update(any());
        verify(runStore, never()).updateIfHeartbeat(any(), anyLong());
        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("another node's run whose heartbeat stopped is aborted by compare-and-swap on the heartbeat seen")
    void anotherNodesStoppedRunIsAborted() throws Exception {
        listed(owned(true, "node-b/boot0001", 42L));

        assertEquals(0, recovery.abortOrphanedRuns(NOW), "a first sighting never aborts");
        assertEquals(0, recovery.abortOrphanedRuns(NOW + ms(AgentTestRunRecovery.STALE_AFTER_MS - 1)), "not stale yet");
        assertEquals(1, recovery.abortOrphanedRuns(NOW + ms(AgentTestRunRecovery.STALE_AFTER_MS + 1)));

        var captor = ArgumentCaptor.forClass(AgentTestRun.class);
        verify(runStore).updateIfHeartbeat(captor.capture(), eq(42L));
        assertEquals(AgentTestRun.Status.ABORTED, captor.getValue().status());
        assertNull(captor.getValue().heartbeatAt(), "cleared, so the owner's next compare-and-swap fails");
        verify(runStore, never()).update(any());
    }

    /**
     * Two EDDI processes on one host derive the same node id. A run of the other
     * one is live as long as its heartbeat moves.
     */
    @Test
    @DisplayName("same node id, another boot, heartbeat still moving: not an orphan")
    void sameNodeIdWithAFreshHeartbeatIsNotOrphaned() throws Exception {
        listed(owned(false, "node-a/boot0001", 7L));
        assertEquals(0, recovery.abortOrphanedRuns(NOW));
        listed(owned(false, "node-a/boot0001", 8L));
        assertEquals(0, recovery.abortOrphanedRuns(NOW + ms(AgentTestRunRecovery.SAME_NODE_STALE_AFTER_MS + 1)));
        verify(runStore, never()).updateIfHeartbeat(any(), anyLong());
    }

    @Test
    @DisplayName("same node id, another boot, heartbeat stopped: aborted after the shorter window")
    void sameNodeIdWithAStoppedHeartbeatIsAbortedSooner() throws Exception {
        listed(owned(true, "node-a/boot0001", 7L));

        assertEquals(1, sweepTwice(AgentTestRunRecovery.SAME_NODE_STALE_AFTER_MS + 1));
        assertTrue(AgentTestRunRecovery.SAME_NODE_STALE_AFTER_MS < AgentTestRunRecovery.STALE_AFTER_MS);
    }

    @Test
    @DisplayName("another node's stopped heartbeat within the long window is not yet an orphan")
    void anotherNodeGetsTheLongWindow() throws Exception {
        listed(owned(true, "node-b/boot0001", 7L));

        assertEquals(0, sweepTwice(AgentTestRunRecovery.SAME_NODE_STALE_AFTER_MS + 1));
    }

    @Test
    @DisplayName("a heartbeat that resumes before the abort lands wins: nothing is aborted or deleted")
    void aResumedHeartbeatWinsTheRace() throws Exception {
        listed(owned(false, "node-b/boot0001", 42L));
        doThrow(new IResourceStore.ResourceModifiedException("moved")).when(runStore).updateIfHeartbeat(any(), eq(42L));

        assertEquals(0, sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1));

        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("this boot's own run, and a run active in this JVM, are never touched")
    void ownLiveRunIsLeftAlone() throws Exception {
        listed(owned(false, SELF.toString(), 1_000L));
        assertEquals(0, sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1));

        listed(owned(false, "node-b/boot0001", 1_000L));
        when(runner.isActive(RUN_ID)).thenReturn(true);
        assertEquals(0, sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1));
        verify(runStore, never()).update(any());
        verify(runStore, never()).updateIfHeartbeat(any(), anyLong());
    }

    @Test
    @DisplayName("an orphaned RUNNING run is ABORTED with a reason, and its conversations are deleted")
    void orphanIsAbortedAndCleanedUp() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 1))).thenReturn(List.of("c1"));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 2))).thenReturn(List.of("c2"));

        assertEquals(1, sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1), "a legacy run without a heartbeat counts as stopped");

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

        sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1);

        verify(runStore).update(any(AgentTestRun.class));
        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("a cleanup failure never stops the sweep")
    void cleanupFailureIsContained() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(anyString())).thenThrow(new IllegalStateException("db down"));

        assertEquals(1, sweepTwice(AgentTestRunRecovery.STALE_AFTER_MS + 1));
    }
}
