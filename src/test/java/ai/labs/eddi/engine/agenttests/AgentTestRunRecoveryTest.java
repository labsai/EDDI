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

    private IAgentTestRunStore runStore;
    private IAgentTestSuiteStore suiteStore;
    private IConversationMemoryStore memoryStore;
    private ConversationPurgeService purgeService;
    private AgentTestRunRecovery recovery;

    @BeforeEach
    void setUp() throws Exception {
        runStore = mock(IAgentTestRunStore.class);
        suiteStore = mock(IAgentTestSuiteStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
        purgeService = mock(ConversationPurgeService.class);
        recovery = new AgentTestRunRecovery(runStore, suiteStore, memoryStore, purgeService);
        when(suiteStore.read(SUITE_ID, 2)).thenReturn(new AgentTestSuite("s", null, null, null, null,
                List.of(new TestCase("c", null, 2, null, List.of(new Turn("hi", null, null))))));
    }

    private static AgentTestRun running(boolean keep) {
        return new AgentTestRun(RUN_ID, SUITE_ID, 2, "s", "abcdefabcdefabcdefabcdef", 1, AgentTestRun.Status.RUNNING, "alice", 1_000L, null,
                null, null, 1.0, null, null, null, null, null, null, keep, null);
    }

    @Test
    @DisplayName("a RUNNING run found at startup is ABORTED with a reason, and its conversations are deleted")
    void orphanIsAbortedAndCleanedUp() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 1))).thenReturn(List.of("c1"));
        when(memoryStore.getConversationIdsByUserId(AgentTestConversations.userIdFor(RUN_ID, 0, 2))).thenReturn(List.of("c2"));

        assertEquals(1, recovery.abortOrphanedRuns());

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

        recovery.abortOrphanedRuns();

        verify(runStore).update(any(AgentTestRun.class));
        verifyNoInteractions(purgeService);
    }

    @Test
    @DisplayName("a cleanup failure never stops the sweep")
    void cleanupFailureIsContained() throws Exception {
        when(runStore.listByStatus(AgentTestRun.Status.RUNNING, AgentTestRunRecovery.MAX_RUNS_PER_SWEEP)).thenReturn(List.of(running(false)));
        when(memoryStore.getConversationIdsByUserId(anyString())).thenThrow(new IllegalStateException("db down"));

        assertEquals(1, recovery.abortOrphanedRuns());
    }
}
