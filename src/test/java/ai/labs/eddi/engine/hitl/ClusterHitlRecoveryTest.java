/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClusterHitlRecovery — eligibility")
class ClusterHitlRecoveryTest {

    private static final String ID = "6ac11c7a3dc075f0b3dbb8eb";

    private NatsConnectionManager connection;
    private IConversationLeaseManager leases;
    private IConversationMemoryStore store;
    private ClusterHitlRecovery recovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connection = mock(NatsConnectionManager.class);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.isResolvable()).thenReturn(true);
        when(connections.get()).thenReturn(connection);
        leases = mock(IConversationLeaseManager.class);
        store = mock(IConversationMemoryStore.class);
        recovery = new ClusterHitlRecovery(ClusterConfig.defaults().withMessagingType("nats"), connections, leases, store,
                mock(HitlCrashRecoveryObserver.class));
    }

    @Test
    @DisplayName("A11: while NATS cannot be read, a conversation is never taken for one nobody holds")
    void notEligibleWhileNatsIsUnreachable() {
        when(connection.isConnected()).thenReturn(false);
        when(leases.peek(ID)).thenReturn(Optional.empty()); // what peek answers when it cannot read

        assertFalse(recovery.eligible(ID));
        assertFalse(recovery.eligible(ID), "not on a later sweep either");
        verify(store, never()).getRevision(ID);
    }

    @Test
    @DisplayName("A11: a connection lost during the read makes the answer untrustworthy")
    void notEligibleWhenTheConnectionDropsDuringThePeek() {
        when(connection.isConnected()).thenReturn(true, false);
        when(leases.peek(ID)).thenReturn(Optional.empty());

        assertFalse(recovery.eligible(ID));
        verify(store, never()).getRevision(ID);
    }
}
