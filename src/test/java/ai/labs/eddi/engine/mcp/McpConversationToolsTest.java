package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IConversationService;
import ai.labs.eddi.engine.IConversationService.ConversationResult;
import ai.labs.eddi.engine.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.BotDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpConversationTools — MCP tools for bot conversations.
 */
class McpConversationToolsTest {

    private static final String BOT_ID = "test-bot-id";
    private static final String CONV_ID = "test-conv-id";

    private IConversationService conversationService;
    private IRestBotAdministration botAdmin;
    private IRestBotStore botStore;
    private IJsonSerialization jsonSerialization;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() throws IOException {
        conversationService = mock(IConversationService.class);
        botAdmin = mock(IRestBotAdministration.class);
        botStore = mock(IRestBotStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        // Default stub for error serialization path — overridden by specific tests
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        tools = new McpConversationTools(conversationService, botAdmin, botStore, jsonSerialization);
    }

    // --- listBots ---

    @Test
    void listBots_returnsDeployedBots() throws IOException {
        var status = new BotDeploymentStatus();
        status.setBotId(BOT_ID);
        when(botAdmin.getDeploymentStatuses(Environment.unrestricted))
                .thenReturn(List.of(status));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"botId\":\"test-bot-id\"}]");

        String result = tools.listBots("unrestricted");

        assertNotNull(result);
        assertTrue(result.contains("test-bot-id"));
        verify(botAdmin).getDeploymentStatuses(Environment.unrestricted);
    }

    @Test
    void listBots_defaultsToUnrestricted() throws IOException {
        when(botAdmin.getDeploymentStatuses(Environment.unrestricted))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listBots(null);

        verify(botAdmin).getDeploymentStatuses(Environment.unrestricted);
    }

    @Test
    void listBots_handlesException() {
        when(botAdmin.getDeploymentStatuses(any()))
                .thenThrow(new RuntimeException("Service down"));

        String result = tools.listBots("unrestricted");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Service down"));
    }

    // --- listBotConfigs ---

    @Test
    void listBotConfigs_returnsDescriptors() throws IOException {
        var descriptor = new DocumentDescriptor();
        when(botStore.readBotDescriptors("", 0, 20))
                .thenReturn(List.of(descriptor));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"name\":\"TestBot\"}]");

        String result = tools.listBotConfigs(null, null);

        assertNotNull(result);
        verify(botStore).readBotDescriptors("", 0, 20);
    }

    @Test
    void listBotConfigs_withFilterAndLimit() throws IOException {
        when(botStore.readBotDescriptors("search", 0, 5))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listBotConfigs("search", "5");

        verify(botStore).readBotDescriptors("search", 0, 5);
    }

    // --- createConversation ---

    @Test
    void createConversation_returnsConversationId() throws Exception {
        when(conversationService.startConversation(
                eq(Environment.unrestricted), eq(BOT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));
        when(jsonSerialization.serialize(any(Map.class)))
                .thenReturn("{\"conversationId\":\"test-conv-id\"}");

        String result = tools.createConversation(BOT_ID, "unrestricted");

        assertNotNull(result);
        assertTrue(result.contains("test-conv-id"));
    }

    @Test
    void createConversation_botNotReady_returnsError() throws Exception {
        when(conversationService.startConversation(any(), eq(BOT_ID), isNull(), anyMap()))
                .thenThrow(new IConversationService.BotNotReadyException("Bot not deployed"));

        String result = tools.createConversation(BOT_ID, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Bot not deployed"));
    }

    // --- talkToBot ---

    @Test
    void talkToBot_sendsMessageAndReturnsResponse() throws Exception {
        // Set up the mock to invoke the callback immediately when say() is called
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(
                any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(SimpleConversationMemorySnapshot.class)))
                .thenReturn("{\"conversationSteps\":[]}");

        String result = tools.talkToBot(BOT_ID, CONV_ID, "Hello bot!", "unrestricted");

        assertNotNull(result);
        assertTrue(result.contains("conversationSteps"));

        // Verify the message was sent correctly
        ArgumentCaptor<InputData> inputCaptor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(
                eq(Environment.unrestricted), eq(BOT_ID), eq(CONV_ID),
                eq(false), eq(true), eq(Collections.emptyList()),
                inputCaptor.capture(), eq(false), any());

        assertEquals("Hello bot!", inputCaptor.getValue().getInput());
    }

    @Test
    void talkToBot_handlesServiceException() throws Exception {
        doThrow(new RuntimeException("Connection lost"))
                .when(conversationService).say(
                        any(), any(), any(), anyBoolean(), anyBoolean(),
                        anyList(), any(), anyBoolean(), any());

        String result = tools.talkToBot(BOT_ID, CONV_ID, "Hello!", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Connection lost"));
    }

    // --- readConversation ---

    @Test
    void readConversation_returnsMemorySnapshot() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(
                eq(Environment.unrestricted), eq(BOT_ID), eq(CONV_ID),
                eq(false), eq(false), anyList()))
                .thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot))
                .thenReturn("{\"botId\":\"test-bot-id\"}");

        String result = tools.readConversation(BOT_ID, CONV_ID, "unrestricted");

        assertNotNull(result);
        assertTrue(result.contains("test-bot-id"));
    }

    @Test
    void readConversation_notFound_returnsError() throws Exception {
        when(conversationService.readConversation(any(), any(), any(), anyBoolean(), anyBoolean(), anyList()))
                .thenThrow(new ResourceNotFoundException("Not found"));

        String result = tools.readConversation(BOT_ID, "unknown-id", null);

        assertTrue(result.contains("error"));
    }

    // --- readConversationLog ---

    @Test
    void readConversationLog_returnsText() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenReturn(new IConversationService.ConversationLogResult("User: Hi\nBot: Hello!", "text/plain"));

        String result = tools.readConversationLog(CONV_ID, null);

        assertEquals("User: Hi\nBot: Hello!", result);
    }
}
