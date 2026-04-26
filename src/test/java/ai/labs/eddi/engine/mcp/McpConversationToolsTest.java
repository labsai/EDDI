/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
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
 * Unit tests for McpConversationTools — MCP tools for Agent conversations.
 */
class McpConversationToolsTest {

    private static final String AGENT_ID = "test-agent-id";
    private static final String CONV_ID = "test-conv-id";

    private IConversationService conversationService;
    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore AgentStore;
    private IJsonSerialization jsonSerialization;
    private BoundedLogStore boundedLogStore;
    private IRestAuditStore auditStore;
    private IRestAgentTriggerStore AgentTriggerStore;
    private IUserConversationStore userConversationStore;
    private IRestAgentEngine RestAgentEngine;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() throws IOException {
        conversationService = mock(IConversationService.class);
        agentAdmin = mock(IRestAgentAdministration.class);
        AgentStore = mock(IRestAgentStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        boundedLogStore = mock(BoundedLogStore.class);
        auditStore = mock(IRestAuditStore.class);
        AgentTriggerStore = mock(IRestAgentTriggerStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        RestAgentEngine = mock(IRestAgentEngine.class);
        // Default: lenient serialize returns empty JSON
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);
        tools = new McpConversationTools(conversationService, agentAdmin, AgentStore, restInterfaceFactory, jsonSerialization, boundedLogStore,
                auditStore, AgentTriggerStore, userConversationStore, RestAgentEngine, mockIdentity, false);
    }

    // --- listAgents ---

    @Test
    void listAgents_returnsDeployedAgents() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"agentId\":\"test-agent-id\"}]");

        String result = tools.listAgents("production");

        assertNotNull(result);
        assertTrue(result.contains("test-agent-id"));
        verify(agentAdmin).getDeploymentStatuses(Environment.production);
    }

    @Test
    void listAgents_defaultsToProduction() throws IOException {
        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listAgents(null);

        verify(agentAdmin).getDeploymentStatuses(Environment.production);
    }

    @Test
    void listAgents_handlesException() {
        when(agentAdmin.getDeploymentStatuses(any())).thenThrow(new RuntimeException("Service down"));

        String result = tools.listAgents("production");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Service down"));
    }

    // --- listAgentConfigs ---

    @Test
    void listAgentConfigs_returnsDescriptors() throws IOException {
        var descriptor = new DocumentDescriptor();
        when(AgentStore.readAgentDescriptors("", 0, 20)).thenReturn(List.of(descriptor));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"name\":\"TestAgent\"}]");

        String result = tools.listAgentConfigs(null, null);

        assertNotNull(result);
        verify(AgentStore).readAgentDescriptors("", 0, 20);
    }

    @Test
    void listAgentConfigs_withFilterAndLimit() throws IOException {
        when(AgentStore.readAgentDescriptors("search", 0, 5)).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listAgentConfigs("search", 5);

        verify(AgentStore).readAgentDescriptors("search", 0, 5);
    }

    // --- createConversation ---

    @Test
    void createConversation_returnsConversationId() throws Exception {
        when(conversationService.startConversation(eq(Environment.production), eq(AGENT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));
        when(jsonSerialization.serialize(any(Map.class))).thenReturn("{\"conversationId\":\"test-conv-id\"}");

        String result = tools.createConversation(AGENT_ID, "production");

        assertNotNull(result);
        assertTrue(result.contains("test-conv-id"));
    }

    @Test
    void createConversation_agentNotReady_returnsError() throws Exception {
        when(conversationService.startConversation(any(), eq(AGENT_ID), isNull(), anyMap()))
                .thenThrow(new IConversationService.AgentNotReadyException("Agent not deployed"));

        String result = tools.createConversation(AGENT_ID, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent not deployed"));
    }

    // --- talkToAgent ---

    @Test
    void talkToAgent_sendsMessageAndReturnsResponse() throws Exception {
        doAnswer(invocation -> {
            // ConversationResponseHandler is arg index 6 (0-indexed) in conversation-only
            // overload
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class)))
                .thenReturn("{\"conversationState\":\"READY\",\"response\":{\"conversationSteps\":[]}}");

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello agent!", "production");

        assertNotNull(result);
        assertTrue(result.contains("conversationState"));

        // Verify the message was sent correctly
        ArgumentCaptor<InputData> inputCaptor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(CONV_ID), eq(false), eq(true), eq(Collections.emptyList()), inputCaptor.capture(), eq(false), any());

        assertEquals("Hello agent!", inputCaptor.getValue().getInput());
    }

    @Test
    void talkToAgent_nullSnapshot_returnsError() throws Exception {
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(null); // null snapshot triggers completeExceptionally
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), anyList(), any(), anyBoolean(), any(ConversationResponseHandler.class));

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("null response"));
    }

    @Test
    void talkToAgent_handlesServiceException() throws Exception {
        doThrow(new RuntimeException("Connection lost")).when(conversationService).say(any(), anyBoolean(), anyBoolean(), anyList(), any(),
                anyBoolean(), any());

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Connection lost"));
    }

    // --- chatWithAgent (composite) ---

    @Test
    void chatWithAgent_createsConversationAndSendsMessage() throws Exception {
        // Mock conversation creation
        when(conversationService.startConversation(eq(Environment.production), eq(AGENT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));

        // Mock say — conversation-only overload (7 args)
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(Map.class))).thenReturn("{\"conversationId\":\"test-conv-id\",\"response\":{}}");

        String result = tools.chatWithAgent(AGENT_ID, "Hello!", null, "production");

        assertNotNull(result);
        assertTrue(result.contains("test-conv-id"));
        verify(conversationService).startConversation(any(), eq(AGENT_ID), isNull(), anyMap());
        verify(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void chatWithAgent_reusesExistingConversation() throws Exception {
        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(Map.class))).thenReturn("{\"conversationId\":\"test-conv-id\"}");

        tools.chatWithAgent(AGENT_ID, "Follow-up", CONV_ID, null);

        // Should NOT create a new conversation
        verify(conversationService, never()).startConversation(any(), any(), any(), anyMap());
        // Should send message to existing conversation
        verify(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(), any(), anyBoolean(), any());
    }

    // --- readConversation ---

    @Test
    void readConversation_defaultsToCurrentStepOnly() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(eq(CONV_ID), eq(false), eq(true), eq(Collections.emptyList()))).thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"agentId\":\"test-agent-id\"}");

        String result = tools.readConversation(AGENT_ID, CONV_ID, "production", null, null, null);

        assertNotNull(result);
        assertTrue(result.contains("test-agent-id"));
        // Verify currentStepOnly defaults to true
        verify(conversationService).readConversation(any(), eq(false), eq(true), anyList());
    }

    @Test
    void readConversation_withReturningFields() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(eq(CONV_ID), eq(false), eq(false), eq(List.of("input", "output")))).thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");

        tools.readConversation(AGENT_ID, CONV_ID, "production", false, false, "input,output");

        verify(conversationService).readConversation(any(), eq(false), eq(false), eq(List.of("input", "output")));
    }

    @Test
    void readConversation_notFound_returnsError() throws Exception {
        when(conversationService.readConversation(any(), anyBoolean(), anyBoolean(), anyList()))
                .thenThrow(new ResourceNotFoundException("Not found"));

        String result = tools.readConversation(AGENT_ID, "unknown-id", null, null, null, null);

        assertTrue(result.contains("error"));
    }

    // --- readConversationLog ---

    @Test
    void readConversationLog_returnsText() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenReturn(new IConversationService.ConversationLogResult("User: Hi\nAgent: Hello!", "text/plain"));

        String result = tools.readConversationLog(CONV_ID, null);

        assertEquals("User: Hi\nAgent: Hello!", result);
    }

    @Test
    void readConversationLog_handlesException() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull())).thenThrow(new RuntimeException("Conversation not found"));

        String result = tools.readConversationLog(CONV_ID, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Conversation not found"));
    }

    // --- input validation ---

    @Test
    void talkToAgent_nullAgentId_returnsError() {
        String result = tools.talkToAgent(null, CONV_ID, "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void talkToAgent_nullConversationId_returnsError() {
        String result = tools.talkToAgent(AGENT_ID, null, "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("conversationId is required"));
    }

    @Test
    void talkToAgent_nullMessage_returnsError() {
        String result = tools.talkToAgent(AGENT_ID, CONV_ID, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatWithAgent_nullAgentId_returnsError() {
        String result = tools.chatWithAgent(null, "Hello!", null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void chatWithAgent_nullMessage_returnsError() {
        String result = tools.chatWithAgent(AGENT_ID, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatWithAgent_conversationCreationFails_returnsError() throws Exception {
        when(conversationService.startConversation(any(), eq(AGENT_ID), isNull(), anyMap()))
                .thenThrow(new IConversationService.AgentNotReadyException("Agent not deployed"));

        String result = tools.chatWithAgent(AGENT_ID, "Hello!", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent not deployed"));
    }

    @Test
    void createConversation_nullAgentId_returnsError() {
        String result = tools.createConversation(null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    // --- readAgentLogs ---

    @Test
    void readAgentLogs_returnsFilteredEntries() throws IOException {
        var entry = new LogEntry(System.currentTimeMillis(), "ERROR", "ai.labs.test", "Something failed", null, AGENT_ID, 1, CONV_ID, null, "inst-1");
        when(boundedLogStore.getEntries(AGENT_ID, CONV_ID, "ERROR", 50)).thenReturn(List.of(entry));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1,\"entries\":[]}");

        String result = tools.readAgentLogs(AGENT_ID, CONV_ID, "ERROR", null);

        assertNotNull(result);
        verify(boundedLogStore).getEntries(AGENT_ID, CONV_ID, "ERROR", 50);
    }

    @Test
    void readAgentLogs_allNullFilters_returnsAll() throws IOException {
        when(boundedLogStore.getEntries(null, null, null, 50)).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.readAgentLogs(null, null, null, null);

        verify(boundedLogStore).getEntries(null, null, null, 50);
    }

    @Test
    void readAgentLogs_handlesException() {
        when(boundedLogStore.getEntries(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("Ring buffer error"));

        String result = tools.readAgentLogs(AGENT_ID, null, null, 10);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Ring buffer error"));
    }

    // --- readAuditTrail ---

    @Test
    void readAuditTrail_returnsEntries() throws IOException {
        var auditEntry = new AuditEntry("ae1", CONV_ID, AGENT_ID, 1, "user1", "production", 0, "ai.labs.llm", "langchain", 0, 150,
                Map.of("input", "hello"), Map.of("output", "hi"), null, null, List.of("send_output"), 0.001, Instant.now(), null, null);
        when(auditStore.getAuditTrail(CONV_ID, 0, 20)).thenReturn(List.of(auditEntry));
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

    // --- discover_agents ---

    @Test
    void discoverAgents_returnsEnrichedList() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        descriptor.setDescription("A test agent");
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));

        var trigger = new AgentTriggerConfiguration();
        trigger.setIntent("test_intent");
        var deployment = new AgentDeployment();
        deployment.setAgentId(AGENT_ID);
        trigger.setAgentDeployments(List.of(deployment));
        when(AgentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(trigger));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverAgents(null, "production");

        assertNotNull(result);
        verify(agentAdmin).getDeploymentStatuses(Environment.production);
        verify(AgentTriggerStore).readAllAgentTriggers();
    }

    @Test
    void discoverAgents_withFilter_filtersResults() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(AgentTriggerStore.readAllAgentTriggers()).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverAgents("Test", "production");

        assertNotNull(result);
    }

    @Test
    void discoverAgents_triggerReadFailure_stillReturns() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(AgentTriggerStore.readAllAgentTriggers()).thenThrow(new RuntimeException("trigger error"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverAgents(null, "production");

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
        when(AgentTriggerStore.readAgentTrigger("no_trigger")).thenReturn(null);

        String result = tools.chatManaged("no_trigger", "user1", "hello", "production");

        assertTrue(result.contains("error"));
    }
}
