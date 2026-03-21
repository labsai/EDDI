package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.engine.botmanagement.IUserConversationStore;
import ai.labs.eddi.configs.agents.IRestBotStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestBotAdministration;
import ai.labs.eddi.engine.api.IRestBotEngine;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.botmanagement.model.BotTriggerConfiguration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
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
    private BoundedLogStore boundedLogStore;
    private IRestAuditStore auditStore;
    private IRestBotTriggerStore botTriggerStore;
    private IUserConversationStore userConversationStore;
    private IRestBotEngine restBotEngine;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() throws IOException {
        conversationService = mock(IConversationService.class);
        botAdmin = mock(IRestBotAdministration.class);
        botStore = mock(IRestBotStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        boundedLogStore = mock(BoundedLogStore.class);
        auditStore = mock(IRestAuditStore.class);
        botTriggerStore = mock(IRestBotTriggerStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        restBotEngine = mock(IRestBotEngine.class);
        // Default: lenient serialize returns empty JSON
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        tools = new McpConversationTools(conversationService, botAdmin, botStore,
                restInterfaceFactory, jsonSerialization, boundedLogStore, auditStore,
                botTriggerStore, userConversationStore, restBotEngine);
    }

    // --- listBots ---

    @Test
    void listBots_returnsDeployedBots() throws IOException {
        var status = new BotDeploymentStatus();
        status.setBotId(BOT_ID);
        when(botAdmin.getDeploymentStatuses(Environment.production))
                .thenReturn(List.of(status));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"botId\":\"test-bot-id\"}]");

        String result = tools.listBots("production");

        assertNotNull(result);
        assertTrue(result.contains("test-bot-id"));
        verify(botAdmin).getDeploymentStatuses(Environment.production);
    }

    @Test
    void listBots_defaultsToUnrestricted() throws IOException {
        when(botAdmin.getDeploymentStatuses(Environment.production))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listBots(null);

        verify(botAdmin).getDeploymentStatuses(Environment.production);
    }

    @Test
    void listBots_handlesException() {
        when(botAdmin.getDeploymentStatuses(any()))
                .thenThrow(new RuntimeException("Service down"));

        String result = tools.listBots("production");

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

        tools.listBotConfigs("search", 5);

        verify(botStore).readBotDescriptors("search", 0, 5);
    }

    // --- createConversation ---

    @Test
    void createConversation_returnsConversationId() throws Exception {
        when(conversationService.startConversation(
                eq(Environment.production), eq(BOT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));
        when(jsonSerialization.serialize(any(Map.class)))
                .thenReturn("{\"conversationId\":\"test-conv-id\"}");

        String result = tools.createConversation(BOT_ID, "production");

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
        doAnswer(invocation -> {
            // ConversationResponseHandler is arg index 8 (0-indexed)
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(
                any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class)))
                .thenReturn("{\"conversationState\":\"READY\",\"response\":{\"conversationSteps\":[]}}");

        String result = tools.talkToBot(BOT_ID, CONV_ID, "Hello bot!", "production");

        assertNotNull(result);
        assertTrue(result.contains("conversationState"));

        // Verify the message was sent correctly
        ArgumentCaptor<InputData> inputCaptor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(
                eq(Environment.production), eq(BOT_ID), eq(CONV_ID),
                eq(false), eq(true), eq(Collections.emptyList()),
                inputCaptor.capture(), eq(false), any());

        assertEquals("Hello bot!", inputCaptor.getValue().getInput());
    }

    @Test
    void talkToBot_nullSnapshot_returnsError() throws Exception {
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(null);  // null snapshot triggers completeExceptionally
            return null;
        }).when(conversationService).say(
                any(), any(), any(), anyBoolean(), anyBoolean(),
                anyList(), any(), anyBoolean(), any(ConversationResponseHandler.class));

        String result = tools.talkToBot(BOT_ID, CONV_ID, "Hello!", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("null response"));
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

    // --- chatWithBot (composite) ---

    @Test
    void chatWithBot_createsConversationAndSendsMessage() throws Exception {
        // Mock conversation creation
        when(conversationService.startConversation(
                eq(Environment.production), eq(BOT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));

        // Mock say
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(
                any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(Map.class)))
                .thenReturn("{\"conversationId\":\"test-conv-id\",\"response\":{}}");

        String result = tools.chatWithBot(BOT_ID, "Hello!", null, "production");

        assertNotNull(result);
        assertTrue(result.contains("test-conv-id"));
        verify(conversationService).startConversation(any(), eq(BOT_ID), isNull(), anyMap());
        verify(conversationService).say(any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void chatWithBot_reusesExistingConversation() throws Exception {
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(
                any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(Map.class)))
                .thenReturn("{\"conversationId\":\"test-conv-id\"}");

        tools.chatWithBot(BOT_ID, "Follow-up", CONV_ID, null);

        // Should NOT create a new conversation
        verify(conversationService, never()).startConversation(any(), any(), any(), anyMap());
        // Should send message to existing conversation
        verify(conversationService).say(any(), eq(BOT_ID), eq(CONV_ID),
                anyBoolean(), anyBoolean(), anyList(), any(), anyBoolean(), any());
    }

    // --- readConversation ---

    @Test
    void readConversation_defaultsToCurrentStepOnly() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(
                eq(Environment.production), eq(BOT_ID), eq(CONV_ID),
                eq(false), eq(true), eq(Collections.emptyList())))
                .thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot))
                .thenReturn("{\"botId\":\"test-bot-id\"}");

        String result = tools.readConversation(BOT_ID, CONV_ID, "production",
                null, null, null);

        assertNotNull(result);
        assertTrue(result.contains("test-bot-id"));
        // Verify currentStepOnly defaults to true
        verify(conversationService).readConversation(
                any(), any(), any(), eq(false), eq(true), anyList());
    }

    @Test
    void readConversation_withReturningFields() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(
                eq(Environment.production), eq(BOT_ID), eq(CONV_ID),
                eq(false), eq(false), eq(List.of("input", "output"))))
                .thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");

        tools.readConversation(BOT_ID, CONV_ID, "production",
                false, false, "input,output");

        verify(conversationService).readConversation(
                any(), any(), any(), eq(false), eq(false), eq(List.of("input", "output")));
    }

    @Test
    void readConversation_notFound_returnsError() throws Exception {
        when(conversationService.readConversation(any(), any(), any(),
                anyBoolean(), anyBoolean(), anyList()))
                .thenThrow(new ResourceNotFoundException("Not found"));

        String result = tools.readConversation(BOT_ID, "unknown-id", null,
                null, null, null);

        assertTrue(result.contains("error"));
    }

    // --- readConversationLog ---

    @Test
    void readConversationLog_returnsText() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenReturn(new IConversationService.ConversationLogResult(
                        "User: Hi\nBot: Hello!", "text/plain"));

        String result = tools.readConversationLog(CONV_ID, null);

        assertEquals("User: Hi\nBot: Hello!", result);
    }

    @Test
    void readConversationLog_handlesException() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenThrow(new RuntimeException("Conversation not found"));

        String result = tools.readConversationLog(CONV_ID, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Conversation not found"));
    }

    // --- input validation ---

    @Test
    void talkToBot_nullBotId_returnsError() {
        String result = tools.talkToBot(null, CONV_ID, "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("botId is required"));
    }

    @Test
    void talkToBot_nullConversationId_returnsError() {
        String result = tools.talkToBot(BOT_ID, null, "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("conversationId is required"));
    }

    @Test
    void talkToBot_nullMessage_returnsError() {
        String result = tools.talkToBot(BOT_ID, CONV_ID, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatWithBot_nullBotId_returnsError() {
        String result = tools.chatWithBot(null, "Hello!", null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("botId is required"));
    }

    @Test
    void chatWithBot_nullMessage_returnsError() {
        String result = tools.chatWithBot(BOT_ID, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatWithBot_conversationCreationFails_returnsError() throws Exception {
        when(conversationService.startConversation(any(), eq(BOT_ID), isNull(), anyMap()))
                .thenThrow(new IConversationService.BotNotReadyException("Bot not deployed"));

        String result = tools.chatWithBot(BOT_ID, "Hello!", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Bot not deployed"));
    }

    @Test
    void createConversation_nullBotId_returnsError() {
        String result = tools.createConversation(null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("botId is required"));
    }

    // --- readBotLogs ---

    @Test
    void readBotLogs_returnsFilteredEntries() throws IOException {
        var entry = new LogEntry(System.currentTimeMillis(), "ERROR", "ai.labs.test",
                "Something failed", null, BOT_ID, 1, CONV_ID, null, "inst-1");
        when(boundedLogStore.getEntries(BOT_ID, CONV_ID, "ERROR", 50))
                .thenReturn(List.of(entry));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1,\"entries\":[]}");

        String result = tools.readBotLogs(BOT_ID, CONV_ID, "ERROR", null);

        assertNotNull(result);
        verify(boundedLogStore).getEntries(BOT_ID, CONV_ID, "ERROR", 50);
    }

    @Test
    void readBotLogs_allNullFilters_returnsAll() throws IOException {
        when(boundedLogStore.getEntries(null, null, null, 50))
                .thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.readBotLogs(null, null, null, null);

        verify(boundedLogStore).getEntries(null, null, null, 50);
    }

    @Test
    void readBotLogs_handlesException() {
        when(boundedLogStore.getEntries(any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Ring buffer error"));

        String result = tools.readBotLogs(BOT_ID, null, null, 10);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Ring buffer error"));
    }

    // --- readAuditTrail ---

    @Test
    void readAuditTrail_returnsEntries() throws IOException {
        var auditEntry = new AuditEntry(
                "ae1", CONV_ID, BOT_ID, 1, "user1", "production",
                0, "ai.labs.langchain", "langchain", 0, 150,
                Map.of("input", "hello"), Map.of("output", "hi"),
                null, null, List.of("send_output"), 0.001, Instant.now(), null);
        when(auditStore.getAuditTrail(CONV_ID, 0, 20))
                .thenReturn(List.of(auditEntry));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.readAuditTrail(CONV_ID, null);

        assertNotNull(result);
        verify(auditStore).getAuditTrail(CONV_ID, 0, 20);
    }

    @Test
    void readAuditTrail_missingConversationId_returnsError() {
        String result = tools.readAuditTrail(null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("conversationId is required"));
    }

    // --- discover_bots ---

    @Test
    void discoverBots_returnsEnrichedList() throws IOException {
        var status = new BotDeploymentStatus();
        status.setBotId(BOT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Bot");
        descriptor.setDescription("A test bot");
        status.setDescriptor(descriptor);

        when(botAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));

        var trigger = new BotTriggerConfiguration();
        trigger.setIntent("test_intent");
        var deployment = new BotDeployment();
        deployment.setBotId(BOT_ID);
        trigger.setBotDeployments(List.of(deployment));
        when(botTriggerStore.readAllBotTriggers()).thenReturn(List.of(trigger));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverBots(null, "production");

        assertNotNull(result);
        verify(botAdmin).getDeploymentStatuses(Environment.production);
        verify(botTriggerStore).readAllBotTriggers();
    }

    @Test
    void discoverBots_withFilter_filtersResults() throws IOException {
        var status = new BotDeploymentStatus();
        status.setBotId(BOT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Bot");
        status.setDescriptor(descriptor);

        when(botAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(botTriggerStore.readAllBotTriggers()).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverBots("Test", "production");

        assertNotNull(result);
    }

    @Test
    void discoverBots_triggerReadFailure_stillReturns() throws IOException {
        var status = new BotDeploymentStatus();
        status.setBotId(BOT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Bot");
        status.setDescriptor(descriptor);

        when(botAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(botTriggerStore.readAllBotTriggers()).thenThrow(new RuntimeException("trigger error"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverBots(null, "production");

        // Should still return results — trigger read is non-fatal
        assertNotNull(result);
        assertFalse(result.contains("error"));
    }

    // --- chat_managed ---

    @Test
    void chatManaged_missingIntent_returnsError() {
        String result = tools.chatManaged(null, "user1", "hello", "production");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    @Test
    void chatManaged_missingUserId_returnsError() {
        String result = tools.chatManaged("support", null, "hello", "production");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void chatManaged_missingMessage_returnsError() {
        String result = tools.chatManaged("support", "user1", null, "production");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatManaged_noTriggerConfigured_returnsError() throws Exception {
        when(userConversationStore.readUserConversation("no_trigger", "user1"))
                .thenThrow(new ai.labs.eddi.datastore.IResourceStore.ResourceStoreException("not found"));
        when(botTriggerStore.readBotTrigger("no_trigger")).thenReturn(null);

        String result = tools.chatManaged("no_trigger", "user1", "hello", "production");

        assertTrue(result.contains("error"));
    }
}
