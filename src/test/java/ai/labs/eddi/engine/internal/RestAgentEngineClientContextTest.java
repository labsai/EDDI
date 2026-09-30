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
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
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
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST conversation entry points drop engine-reserved context keys before
 * the context reaches {@link IConversationService}.
 */
@DisplayName("REST conversation endpoints — client context boundary")
class RestAgentEngineClientContextTest {

    private static final String AGENT_ID = "6a1b2c3d4e5f60718293a4b5";
    private static final String CONV_ID = "6a1b2c3d4e5f60718293a4c6";

    private IConversationService conversationService;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        var descriptorStore = mock(IConversationDescriptorStore.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        // An owned conversation: the access guard resolves its owner from the
        // descriptor
        // (a conversation with no descriptor at all is a 404 for non-admins).
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId("user-1");
        when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(descriptor);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, descriptorStore);
        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, mock(ResourceAccessGuard.class), hitlAccessGuard, mock(IHitlToolJournalStore.class),
                mock(AgentDisplayNameResolver.class), 30);
    }

    private static Map<String, Context> clientContextWithReservedKeys() {
        Map<String, Context> context = new HashMap<>();
        context.put("groupId", new Context(Context.ContextType.string, "6a1b2c3d4e5f60718293a4d7"));
        context.put("dynamicAgentConfig", new Context(Context.ContextType.object, Map.of("enabled", true)));
        context.put("dynamicCreatedAgentIds", new Context(Context.ContextType.array, List.of(AGENT_ID)));
        context.put("delegationDepth", new Context(Context.ContextType.string, "0"));
        context.put("language", new Context(Context.ContextType.string, "en"));
        return context;
    }

    private static void assertOnlyOrdinaryKeysRemain(Map<String, Context> forwarded) {
        assertEquals(Map.of("language", "en"), Map.of("language", forwarded.get("language").getValue()));
        assertEquals(1, forwarded.size(), "only the ordinary key may reach the engine, got " + forwarded.keySet());
    }

    @Test
    @DisplayName("starting a conversation forwards the context without reserved keys")
    @SuppressWarnings("unchecked")
    void startConversationStripsReservedKeys() throws Exception {
        when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult(CONV_ID, URI.create("/conversations/" + CONV_ID)));

        restAgentEngine.startConversationWithContext(AGENT_ID, Deployment.Environment.production, "user-1",
                clientContextWithReservedKeys());

        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(eq(Deployment.Environment.production), eq(AGENT_ID), eq("user-1"),
                captor.capture());
        assertOnlyOrdinaryKeysRemain(captor.getValue());
    }

    @Test
    @DisplayName("a turn forwards the context without reserved keys")
    void sayStripsReservedKeys() throws Exception {
        restAgentEngine.sayWithinContext(CONV_ID, false, false, List.of(),
                new InputData("hello", clientContextWithReservedKeys()), mock(AsyncResponse.class));

        ArgumentCaptor<InputData> captor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(CONV_ID), any(), any(), any(), captor.capture(), eq(false), any());
        assertOnlyOrdinaryKeysRemain(captor.getValue().getContext());
        assertEquals("hello", captor.getValue().getInput());
    }

    @Test
    @DisplayName("a streaming turn forwards the context without reserved keys")
    void sayStreamingStripsReservedKeys() throws Exception {
        var streaming = new RestAgentEngineStreaming(conversationService, mock(ConversationAccessGuard.class));
        var eventSink = mock(SseEventSink.class);
        when(eventSink.isClosed()).thenReturn(false);

        streaming.sayStreaming(CONV_ID, false, false, List.of(), new InputData("hello", clientContextWithReservedKeys()), eventSink,
                mock(Sse.class));

        ArgumentCaptor<InputData> captor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).sayStreaming(eq(CONV_ID), any(), any(), any(), captor.capture(), any());
        assertOnlyOrdinaryKeysRemain(captor.getValue().getContext());
        assertFalse(captor.getValue().getContext().containsKey("groupId"));
    }
}
