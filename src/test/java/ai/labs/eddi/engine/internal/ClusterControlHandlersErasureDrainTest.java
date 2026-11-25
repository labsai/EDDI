/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Cluster control — the erasure drain barrier on a peer node")
class ClusterControlHandlersErasureDrainTest {

    private IClusterRpc rpc;
    private ErasureGuard guard;
    private UserErasureParticipant participant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rpc = mock(IClusterRpc.class);
        guard = mock(ErasureGuard.class);
        participant = mock(UserErasureParticipant.class);
        when(participant.stopInFlightWorkByHash("hash")).thenReturn(1);
        Instance<UserErasureParticipant> participants = mock(Instance.class);
        when(participants.iterator()).thenAnswer(inv -> List.of(participant).iterator());
        ClusterControlHandlers handlers = new ClusterControlHandlers(rpc, mock(ConversationService.class), mock(GroupConversationService.class),
                participants, mock(IConversationCoordinator.class));
        handlers.erasureGuard = guard;
        handlers.startCluster();
    }

    @SuppressWarnings("unchecked")
    private Function<Map<String, Object>, Map<String, Object>> handler(String op) {
        ArgumentCaptor<Function<Map<String, Object>, Map<String, Object>>> handler = ArgumentCaptor.forClass(Function.class);
        verify(rpc).handle(eq(op), handler.capture());
        return handler.getValue();
    }

    @Test
    @DisplayName("gdpr-stop: stops the user's work first, then passes the drain barrier, and says so")
    void stopThenDrain() {
        when(guard.drainReply()).thenReturn(Map.of("drained", true));
        Map<String, Object> reply = handler(IClusterRpc.GDPR_STOP).apply(Map.of("userIdHash", "hash", GdprComplianceService.TOMBSTONED, true));

        assertEquals(1, reply.get("stopped"));
        assertEquals(true, reply.get("drained"));
        assertFalse(reply.containsKey("error"), String.valueOf(reply));
        InOrder order = inOrder(participant, guard);
        order.verify(participant).stopInFlightWorkByHash("hash");
        order.verify(guard).drainReply();
    }

    @Test
    @DisplayName("gdpr-stop: a barrier that timed out is an error in the reply, so the erasing node fails its cluster stop")
    void drainTimeout() {
        when(guard.drainReply()).thenReturn(Map.of("drained", false, "error", "async writer(s) not drained within PT30S: alerts"));
        Map<String, Object> reply = handler(IClusterRpc.GDPR_STOP).apply(Map.of("userIdHash", "hash", GdprComplianceService.TOMBSTONED, true));

        assertEquals(false, reply.get("drained"));
        assertTrue(String.valueOf(reply.get("error")).contains("alerts"), String.valueOf(reply));
    }

    @Test
    @DisplayName("gdpr-stop from a coordinator that wrote no tombstones (an older release): stopped and drained, but answered with an error")
    void unguardedErasureIsAnError() {
        when(guard.drainReply()).thenReturn(Map.of("drained", true));
        Map<String, Object> reply = handler(IClusterRpc.GDPR_STOP).apply(Map.of("userIdHash", "hash"));

        assertEquals(1, reply.get("stopped"), "the stop still runs");
        assertEquals(true, reply.get("drained"), "and so does the drain");
        // Every release's coordinator records a reply error as an incomplete cluster
        // stop, so the erasure is not reported complete over unguarded writes.
        assertEquals(ClusterControlHandlers.UNGUARDED_ERASURE_ERROR, reply.get("error"));
    }

    @Test
    @DisplayName("erasure-drain: the conversation-delete barrier answers with this node's drain verdict")
    void conversationDeleteBarrier() {
        when(guard.drainReply()).thenReturn(Map.of("drained", true));
        assertEquals(Map.of("drained", true), handler(IClusterRpc.ERASURE_DRAIN).apply(Map.of("conversationId", "c1")));
    }
}
