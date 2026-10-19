/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Under overload a turn that will be refused must not first spend database
 * reads: the capacity pre-check runs before the ownership check (which reads
 * the conversation's descriptor) on every turn-carrying REST entry point.
 */
class RestAgentEngineCapacityPrecheckTest {

    private static final String CONVERSATION_ID = "6a0f5d783ab1ce8997018ce2";

    private IConversationService conversationService;
    private IConversationDescriptorStore descriptorStore;
    private RestAgentEngine restAgentEngine;
    private RestAgentEngineStreaming streaming;
    private ResourceAccessGuard resourceAccessGuard;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        resourceAccessGuard = mock(ResourceAccessGuard.class);
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, descriptorStore);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, resourceAccessGuard, hitlAccessGuard, mock(IHitlToolJournalStore.class),
                mock(AgentDisplayNameResolver.class), 30);
        streaming = new RestAgentEngineStreaming(conversationService, conversationAccessGuard);
        doThrow(new TurnCapacityExceededException("full")).when(conversationService).refuseIfAtCapacity(CONVERSATION_ID);
    }

    @Test
    @DisplayName("say: refused before the descriptor is read or a turn is attempted")
    void say() throws Exception {
        assertThrows(RejectedExecutionException.class, () -> restAgentEngine.sayWithinContext(CONVERSATION_ID, false, true, List.of(),
                new InputData("hello", new HashMap<>()), mock(AsyncResponse.class)));
        verifyNoInteractions(descriptorStore);
        verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("start: refused before the access check, the GDPR read or the agent lookup")
    void startConversation() throws Exception {
        doThrow(new TurnCapacityExceededException("full")).when(conversationService).refuseIfAtCapacity(contains("agent-1"));

        assertThrows(RejectedExecutionException.class,
                () -> restAgentEngine.startConversationWithContext("agent-1", Environment.production, "user-1", Map.of()));
        verifyNoInteractions(resourceAccessGuard);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("start: goes ahead when there is capacity")
    void startConversationWithCapacity() throws Exception {
        when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("c1", URI.create("eddi://conversation/c1")));

        restAgentEngine.startConversationWithContext("agent-1", Environment.production, "user-1", Map.of());

        verify(conversationService).refuseIfAtCapacity(contains("agent-1"));
        verify(conversationService).startConversation(any(), eq("agent-1"), any(), any());
    }

    @Test
    @DisplayName("rerun: refused before the descriptor is read")
    void rerun() {
        assertThrows(RejectedExecutionException.class,
                () -> restAgentEngine.rerunLastConversationStep(CONVERSATION_ID, "en", false, true, List.of(), mock(AsyncResponse.class)));
        verifyNoInteractions(descriptorStore);
    }

    @Test
    @DisplayName("streaming: a plain 503 before the descriptor is read or the sink is touched")
    void sayStreaming() throws Exception {
        SseEventSink sink = mock(SseEventSink.class);
        assertThrows(RejectedExecutionException.class,
                () -> streaming.sayStreaming(CONVERSATION_ID, false, true, List.of(), new InputData("hello", new HashMap<>()), sink,
                        mock(Sse.class)));
        verifyNoInteractions(descriptorStore, sink);
    }
}
