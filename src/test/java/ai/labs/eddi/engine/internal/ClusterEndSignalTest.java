/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cluster mode: an end whose turn runs on another node. The end used to reach
 * that node as a plain cancel, so its cancelled turn — whose live memory holds
 * the sub-agents it created, and is never stored — kept them deployed when it
 * settled before the ending node had stored {@code ENDED}. The cancel request
 * now says when it is an end.
 */
@DisplayName("Cluster cancel signal: end vs plain cancel")
class ClusterEndSignalTest {

    private static final String ID = "0123456789abcdef01234567";

    @SuppressWarnings("unchecked")
    private Function<Map<String, Object>, Map<String, Object>> cancelHandler(ConversationService service) {
        IClusterRpc rpc = mock(IClusterRpc.class);
        new ClusterControlHandlers(rpc, service, mock(GroupConversationService.class), mock(Instance.class),
                mock(IConversationCoordinator.class)).startCluster();
        ArgumentCaptor<Function<Map<String, Object>, Map<String, Object>>> handler = ArgumentCaptor.forClass(Function.class);
        verify(rpc).handle(eq(IClusterRpc.CONVERSATION_CANCEL), handler.capture());
        return handler.getValue();
    }

    @Test
    @DisplayName("the receiving node reads the end reason; a request without one is a plain cancel")
    void handlerPassesTheReason() {
        ConversationService service = mock(ConversationService.class);
        var handler = cancelHandler(service);

        handler.apply(Map.of("conversationId", ID, ConversationService.CANCEL_REASON, ConversationService.CANCEL_REASON_END));
        verify(service).signalLocalInFlight(ID, true);

        handler.apply(Map.of("conversationId", ID));
        verify(service).signalLocalInFlight(ID, false);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a turn cancelled by a remote end tears down from its live memory; one cancelled plainly does not")
    void remoteEndTearsDownPlainCancelDoesNot() {
        ConversationService service = mock(ConversationService.class);
        IConversationMemoryStore store = mock(IConversationMemoryStore.class);
        // The ending node has not stored ENDED yet when this turn settles.
        when(store.getConversationState(ID)).thenReturn(ConversationState.EXECUTION_INTERRUPTED);
        var runner = new ConversationStepRunner(service, store, mock(IConversationDescriptorStore.class), mock(IRuntime.class),
                mock(ICache.class), new ConcurrentHashMap<>(), 0);
        IConversationMemory ended = mock(IConversationMemory.class);
        IConversationMemory cancelled = mock(IConversationMemory.class);
        when(service.wasEndSignalled(ended)).thenReturn(true);

        runner.tearDownSubAgentsIfEnded(ID, ended);
        runner.tearDownSubAgentsIfEnded(ID, cancelled);

        verify(service).tearDownStandaloneSubAgents(ID, ended);
        verify(service, never()).tearDownStandaloneSubAgents(ID, cancelled);
    }
}
