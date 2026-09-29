/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MCP managed-conversation path applies the same client-context boundary as
 * the REST trigger path: a trigger's initial context never carries an
 * engine-reserved key into the conversation.
 */
class McpConversationToolsReservedContextTest {

    private static final String AGENT_ID = "6a1b2c3d4e5f60718293a4b5";
    private static final String CONV_ID = "6a1b2c3d4e5f60718293a4c6";

    @Test
    @SuppressWarnings("unchecked")
    void triggerInitialContextIsStrippedOfReservedKeys() throws Exception {
        var conversationService = mock(IConversationService.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        var triggerStore = mock(IRestAgentTriggerStore.class);
        var userConversationStore = mock(IUserConversationStore.class);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(true);
        var conversationAccessGuard = new ConversationAccessGuard(identity, new OwnershipValidator(false),
                mock(IConversationDescriptorStore.class));
        var tools = new McpConversationTools(conversationService, mock(IRestAgentAdministration.class), mock(IRestAgentStore.class),
                mock(IRestInterfaceFactory.class), jsonSerialization, mock(BoundedLogStore.class), mock(IRestAuditStore.class),
                triggerStore, userConversationStore, mock(IRestAgentEngine.class), identity, conversationAccessGuard,
                mock(ResourceAccessGuard.class), false);

        when(userConversationStore.readUserConversation("support", "user1"))
                .thenThrow(new IResourceStore.ResourceStoreException("not found"));
        Map<String, Context> initialContext = new HashMap<>();
        initialContext.put("groupId", new Context(Context.ContextType.string, "6a1b2c3d4e5f60718293a4d7"));
        initialContext.put("dynamicAgentConfig", new Context(Context.ContextType.object, Map.of("enabled", true)));
        initialContext.put("channel", new Context(Context.ContextType.string, "web"));
        var deployment = new AgentDeployment();
        deployment.setAgentId(AGENT_ID);
        deployment.setEnvironment(Environment.production);
        deployment.setInitialContext(initialContext);
        var trigger = new AgentTriggerConfiguration();
        trigger.setIntent("support");
        trigger.setAgentDeployments(List.of(deployment));
        when(triggerStore.readAgentTrigger("support")).thenReturn(trigger);
        when(conversationService.startConversation(eq(Environment.production), eq(AGENT_ID), eq("user1"), any()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));

        tools.chatManaged("support", "user1", "Hello!", "production");

        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(eq(Environment.production), eq(AGENT_ID), eq("user1"), captor.capture());
        assertEquals(List.of("channel"), List.copyOf(captor.getValue().keySet()),
                "only the ordinary key may reach the engine");
    }
}
