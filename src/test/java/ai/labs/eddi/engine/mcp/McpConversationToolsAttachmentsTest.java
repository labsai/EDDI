/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
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
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code attachments} argument of {@code talk_to_agent} and
 * {@code chat_with_agent}: it reaches the turn as {@code attachment_N} context
 * entries (where the engine resolves them as for REST), and a malformed one is
 * refused before anything runs.
 */
class McpConversationToolsAttachmentsTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final String CONV_ID = "89abcdef0123456789abcdef";
    private static final String STORAGE_REF = "fedcba9876543210fedcba98";

    private IConversationService conversationService;
    private IJsonSerialization jsonSerialization;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(true);
        var conversationAccessGuard = new ConversationAccessGuard(identity, new OwnershipValidator(false),
                mock(IConversationDescriptorStore.class));
        tools = new McpConversationTools(conversationService, mock(IRestAgentAdministration.class), mock(IRestAgentStore.class),
                mock(IRestInterfaceFactory.class), jsonSerialization, mock(BoundedLogStore.class), mock(IRestAuditStore.class),
                mock(IRestAgentTriggerStore.class), mock(IUserConversationStore.class), mock(IRestAgentEngine.class), identity,
                conversationAccessGuard, mock(ResourceAccessGuard.class), false);

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));
    }

    private InputData sentInput() throws Exception {
        ArgumentCaptor<InputData> input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), input.capture(), anyBoolean(), any());
        return input.getValue();
    }

    @Test
    void talkToAgentSendsTheFilesAsTheTurnsAttachmentContexts() throws Exception {
        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Read these", null,
                "[{\"storageRef\":\"" + STORAGE_REF + "\",\"fileName\":\"report.pdf\"},"
                        + "{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\",\"fileName\":\"inline.pdf\"}]");

        assertFalse(result.contains("BAD_REQUEST"), result);
        Map<String, Context> context = sentInput().getContext();
        assertEquals("true", context.get("mcp").getValue(), "the MCP marker stays");
        assertEquals(Context.ContextType.object, context.get("attachment_0").getType());
        assertEquals(Map.of("storageRef", STORAGE_REF, "fileName", "report.pdf"), context.get("attachment_0").getValue());
        assertEquals(Map.of("data", "QUJDRA==", "mimeType", "application/pdf", "fileName", "inline.pdf"),
                context.get("attachment_1").getValue());
        assertEquals(3, context.size());
    }

    @Test
    void withoutFilesTheContextIsOnlyTheMcpMarker() throws Exception {
        tools.talkToAgent(AGENT_ID, CONV_ID, "Hello", null, null);

        Map<String, Context> context = sentInput().getContext();
        assertEquals(List.of("mcp"), new ArrayList<>(context.keySet()));
    }

    @Test
    void talkToAgentRefusesAMalformedArgumentBeforeTheTurn() {
        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Read this", null, "[{\"url\":\"https://example.com/a.png\"}]");

        assertTrue(result.contains("BAD_REQUEST") && result.contains("mimeType"), result);
        verifyNoInteractions(conversationService);
    }

    @Test
    void chatWithAgentRefusesAMalformedArgumentBeforeCreatingAConversation() {
        String result = tools.chatWithAgent(AGENT_ID, "Read this", null, "production", "{\"storageRef\":\"" + STORAGE_REF + "\"}");

        assertTrue(result.contains("BAD_REQUEST"), result);
        verifyNoInteractions(conversationService);
    }

    @Test
    void chatWithAgentSendsTheFilesIntoTheConversationItCreates() throws Exception {
        when(conversationService.startConversation(eq(Environment.production), eq(AGENT_ID), any(), any()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));

        tools.chatWithAgent(AGENT_ID, "Look", null, "production", "[{\"url\":\"https://example.com/a.png\",\"mimeType\":\"image/png\"}]");

        assertEquals(Map.of("url", "https://example.com/a.png", "mimeType", "image/png"), sentInput().getContext().get("attachment_0").getValue());
    }
}
