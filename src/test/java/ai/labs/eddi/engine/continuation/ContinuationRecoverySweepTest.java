/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.continuation.ConversationContinuations.Recovery;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingContinuation;
import ai.labs.eddi.engine.memory.model.PendingContinuationCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ContinuationRecoverySweepTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private IConversationMemoryStore memoryStore;
    private ConversationContinuations continuations;
    private IConversationLeaseManager leases;
    private LeaseHandle lease;
    private ContinuationRecoverySweep sweep;

    @BeforeEach
    void setUp() {
        memoryStore = mock(IConversationMemoryStore.class);
        continuations = mock(ConversationContinuations.class);
        leases = mock(IConversationLeaseManager.class);
        lease = mock(LeaseHandle.class);
        doReturn(Optional.of(lease)).when(leases).tryAcquireKey(ContinuationRecoverySweep.LEADER_KEY);
        sweep = new ContinuationRecoverySweep(memoryStore, continuations, leases, true, Duration.ofSeconds(90), 25,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PendingContinuationCandidate candidate(String conversationId) {
        return new PendingContinuationCandidate(conversationId, "agent", "user", ConversationState.READY,
                new PendingContinuation(1, 3, "", null, 0L));
    }

    @Test
    @DisplayName("reads only records older than the grace period, at most a batch, and hands each to recover")
    void readsPastTheGracePeriod() throws Exception {
        var a = candidate("a");
        var b = candidate("b");
        doReturn(List.of(a, b)).when(memoryStore).findPendingContinuations(anyLong(), anyInt());
        doReturn(Recovery.RECOVERED).when(continuations).recover(a);
        doReturn(Recovery.ALREADY_ARMED).when(continuations).recover(b);

        Map<Recovery, Integer> outcomes = sweep.sweep();

        verify(memoryStore).findPendingContinuations(NOW.minusSeconds(90).toEpochMilli(), 25);
        assertEquals(Map.of(Recovery.RECOVERED, 1, Recovery.ALREADY_ARMED, 1), outcomes);
        verify(leases).release(lease);
    }

    @Test
    @DisplayName("one candidate that fails does not stop the others")
    void oneFailureDoesNotStopThePass() throws Exception {
        var a = candidate("a");
        var b = candidate("b");
        doReturn(List.of(a, b)).when(memoryStore).findPendingContinuations(anyLong(), anyInt());
        doThrow(new IResourceStore.ResourceStoreException("down")).when(continuations).recover(a);
        doReturn(Recovery.RECOVERED).when(continuations).recover(b);

        assertEquals(Map.of(Recovery.RECOVERED, 1), sweep.sweep());
    }

    @Test
    @DisplayName("another node holding the lease means this node reads nothing")
    void notTheLeader() throws Exception {
        doReturn(Optional.empty()).when(leases).tryAcquireKey(ContinuationRecoverySweep.LEADER_KEY);

        assertTrue(sweep.sweep().isEmpty());
        verify(memoryStore, never()).findPendingContinuations(anyLong(), anyInt());
    }

    @Test
    @DisplayName("a store that cannot be read is logged, never thrown, and the lease is released")
    void unreadableStore() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("down")).when(memoryStore).findPendingContinuations(anyLong(), anyInt());

        assertTrue(sweep.sweep().isEmpty());
        verify(leases).release(lease);
        verify(continuations, never()).recover(any());
    }

    @Test
    @DisplayName("switched off, the scheduled pass does nothing")
    void disabled() throws Exception {
        var off = new ContinuationRecoverySweep(memoryStore, continuations, leases, false, Duration.ofSeconds(90), 25,
                Clock.fixed(NOW, ZoneOffset.UTC));

        off.scheduledSweep();

        verify(leases, never()).tryAcquireKey(any());
        verify(memoryStore, never()).findPendingContinuations(anyLong(), anyInt());
    }
}
