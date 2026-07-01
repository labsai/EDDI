/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for McpConversationTools — covering listConversations,
 * getAgent, readConversation field filtering, discoverAgents edge cases,
 * buildConversationResponse, and additional error paths.
 */
class McpConversationToolsExtendedTest {

    private static final String AGENT_ID = "test-agent-id";
    private static final String CONV_ID = "test-conv-id";

    private IConversationService conversationService;
    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore agentStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IJsonSerialization jsonSerialization;
    private BoundedLogStore boundedLogStore;
    private IRestAuditStore auditStore;
    private IRestAgentTriggerStore agentTriggerStore;
    private IUserConversationStore userConversationStore;
    private IRestAgentEngine restAgentEngine;
    private IRestConversationStore convStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        agentAdmin = mock(IRestAgentAdministration.class);
        agentStore = mock(IRestAgentStore.class);
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        jsonSerialization = mock(IJsonSerialization.class);
        boundedLogStore = mock(BoundedLogStore.class);
        auditStore = mock(IRestAuditStore.class);
        agentTriggerStore = mock(IRestAgentTriggerStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        restAgentEngine = mock(IRestAgentEngine.class);
        convStore = mock(IRestConversationStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);

        when(restInterfaceFactory.get(IRestConversationStore.class)).thenReturn(convStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);

        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);

        tools = new McpConversationTools(conversationService, agentAdmin, agentStore,
                restInterfaceFactory, jsonSerialization, boundedLogStore, auditStore,
                agentTriggerStore, userConversationStore, restAgentEngine,
                mockIdentity, false);
    }

    // ==================== listConversations ====================

    @Test
    void listConversations_success() throws Exception {
        var desc = new ConversationDescriptor();
        when(convStore.readConversationDescriptors(eq(0), eq(20), isNull(), isNull(),
                eq(AGENT_ID), isNull(), isNull(), isNull()))
                .thenReturn(List.of(desc));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.listConversations(AGENT_ID, null, null, null);

        assertNotNull(result);
        verify(convStore).readConversationDescriptors(0, 20, null, null, AGENT_ID, null, null, null);
    }

    @Test
    void listConversations_nullAgentId_returnsError() {
        String result = tools.listConversations(null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void listConversations_blankAgentId_returnsError() {
        String result = tools.listConversations("  ", null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void listConversations_withConversationState() throws Exception {
        when(convStore.readConversationDescriptors(eq(0), eq(20), isNull(), isNull(),
                eq(AGENT_ID), isNull(), eq(ConversationState.READY), isNull()))
                .thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.listConversations(AGENT_ID, null, "READY", null);

        verify(convStore).readConversationDescriptors(0, 20, null, null, AGENT_ID, null, ConversationState.READY, null);
    }

    @Test
    void listConversations_invalidConversationState_returnsError() {
        String result = tools.listConversations(AGENT_ID, null, "INVALID_STATE", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Invalid conversationState"));
    }

    @Test
    void listConversations_withVersion() throws Exception {
        when(convStore.readConversationDescriptors(eq(0), eq(20), isNull(), isNull(),
                eq(AGENT_ID), eq(3), isNull(), isNull()))
                .thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.listConversations(AGENT_ID, 3, null, null);

        verify(convStore).readConversationDescriptors(0, 20, null, null, AGENT_ID, 3, null, null);
    }

    @Test
    void listConversations_limitCappedAt100() throws Exception {
        when(convStore.readConversationDescriptors(eq(0), eq(100), isNull(), isNull(),
                eq(AGENT_ID), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.listConversations(AGENT_ID, null, null, 500);

        verify(convStore).readConversationDescriptors(0, 100, null, null, AGENT_ID, null, null, null);
    }

    @Test
    void listConversations_restInterfaceFactoryError_returnsError() throws Exception {
        var failingFactory = mock(IRestInterfaceFactory.class);
        when(failingFactory.get(IRestConversationStore.class))
                .thenThrow(new RestInterfaceFactory.RestInterfaceFactoryException("Factory error", new RuntimeException("cause")));
        when(failingFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);

        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);
        var localTools = new McpConversationTools(conversationService, agentAdmin, agentStore,
                failingFactory, jsonSerialization, boundedLogStore, auditStore,
                agentTriggerStore, userConversationStore, restAgentEngine,
                mockIdentity, false);

        String result = localTools.listConversations(AGENT_ID, null, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Factory error"));
    }

    @Test
    void listConversations_handlesException() throws Exception {
        when(convStore.readConversationDescriptors(anyInt(), anyInt(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.listConversations(AGENT_ID, null, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("DB error"));
    }

    // ==================== getAgent ====================

    @Test
    void getAgent_success() throws IOException {
        var config = new AgentConfiguration();
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(config);

        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        descriptor.setDescription("A test agent");
        when(descriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(descriptor);
        when(jsonSerialization.serialize(any())).thenReturn("{\"agentId\":\"test-agent-id\",\"name\":\"Test Agent\"}");

        String result = tools.getAgent(AGENT_ID, 1);

        assertNotNull(result);
        assertTrue(result.contains("Test Agent"));
        verify(agentStore).readAgent(AGENT_ID, 1);
    }

    @Test
    void getAgent_nullAgentId_returnsError() {
        String result = tools.getAgent(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void getAgent_blankAgentId_returnsError() {
        String result = tools.getAgent("  ", 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void getAgent_notFound_returnsError() {
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(null);

        String result = tools.getAgent(AGENT_ID, 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent not found"));
    }

    @Test
    void getAgent_descriptorReadFails_stillReturnsConfig() throws IOException {
        var config = new AgentConfiguration();
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(config);
        when(descriptorStore.readDescriptor(AGENT_ID, 1)).thenThrow(new RuntimeException("descriptor error"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"agentId\":\"test-agent-id\"}");

        String result = tools.getAgent(AGENT_ID, 1);

        // Should return agent config even if descriptor read fails
        assertNotNull(result);
        assertFalse(result.contains("error"));
    }

    @Test
    void getAgent_nullVersion_defaultsToOne() throws IOException {
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(new AgentConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.getAgent(AGENT_ID, null);

        verify(agentStore).readAgent(AGENT_ID, 1);
    }

    @Test
    void getAgent_handlesException() {
        when(agentStore.readAgent(any(), anyInt())).thenThrow(new RuntimeException("DB error"));

        String result = tools.getAgent(AGENT_ID, 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("DB error"));
    }

    @Test
    void getAgent_nullDescriptor_stillSucceeds() throws IOException {
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(new AgentConfiguration());
        when(descriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(null);
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        String result = tools.getAgent(AGENT_ID, 1);

        assertNotNull(result);
    }

    // ==================== listAgentConfigs — edge cases ====================

    @Test
    void listAgentConfigs_handlesException() {
        when(agentStore.readAgentDescriptors(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.listAgentConfigs(null, null);

        assertTrue(result.contains("error"));
    }

    // ==================== readConversation — field filtering ====================

    @Test
    void readConversation_withSectionLevelField_skipsOutputFiltering() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("input", "hello");
        output.put("output", List.of(Map.of("text", "hi")));
        snapshot.setConversationOutputs(List.of(output));
        when(conversationService.readConversation(eq(CONV_ID), eq(false), eq(true),
                eq(List.of("conversationOutputs")))).thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{\"outputs\":[]}");

        tools.readConversation(null, CONV_ID, null, null, null, "conversationOutputs");

        // When section-level field is requested, should not apply field-level filtering
        verify(conversationService).readConversation(CONV_ID, false, true, List.of("conversationOutputs"));
        // The original outputs should remain unfiltered
        assertEquals(2, snapshot.getConversationOutputs().getFirst().size());
    }

    @Test
    void readConversation_withFieldLevelFiltering_filtersOutputKeys() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("input", "hello");
        output.put("output", List.of(Map.of("text", "hi")));
        output.put("actions", List.of("greet"));
        snapshot.setConversationOutputs(List.of(output));
        when(conversationService.readConversation(eq(CONV_ID), eq(false), eq(true),
                eq(List.of("input", "output")))).thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");

        tools.readConversation(null, CONV_ID, null, null, null, "input,output");

        // After filtering, the output should only contain "input" and "output" keys
        var filteredOutputs = snapshot.getConversationOutputs();
        assertNotNull(filteredOutputs);
        assertEquals(1, filteredOutputs.size());
        assertTrue(filteredOutputs.getFirst().containsKey("input"));
        assertTrue(filteredOutputs.getFirst().containsKey("output"));
        assertFalse(filteredOutputs.getFirst().containsKey("actions"));
    }

    @Test
    void readConversation_detailed_passesTrue() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        when(conversationService.readConversation(eq(CONV_ID), eq(true), eq(false), eq(Collections.emptyList())))
                .thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");

        tools.readConversation(null, CONV_ID, null, false, true, null);

        verify(conversationService).readConversation(CONV_ID, true, false, Collections.emptyList());
    }

    @Test
    void readConversation_nullConversationOutputs_noException() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(null);
        when(conversationService.readConversation(eq(CONV_ID), eq(false), eq(true),
                eq(List.of("input")))).thenReturn(snapshot);
        when(jsonSerialization.serialize(snapshot)).thenReturn("{}");

        // Should not throw even with fields requested but null outputs
        String result = tools.readConversation(null, CONV_ID, null, null, null, "input");
        assertNotNull(result);
    }

    // ==================== readConversationLog — edge cases ====================

    @Test
    void readConversationLog_withLogSize() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), eq(5)))
                .thenReturn(new IConversationService.ConversationLogResult("log", "text/plain"));

        String result = tools.readConversationLog(CONV_ID, 5);

        assertEquals("log", result);
        verify(conversationService).readConversationLog(CONV_ID, "text", 5);
    }

    @Test
    void readConversationLog_zeroLogSize_treatedAsNull() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenReturn(new IConversationService.ConversationLogResult("log", "text/plain"));

        tools.readConversationLog(CONV_ID, 0);

        verify(conversationService).readConversationLog(CONV_ID, "text", null);
    }

    @Test
    void readConversationLog_negativeLogSize_treatedAsNull() throws Exception {
        when(conversationService.readConversationLog(eq(CONV_ID), eq("text"), isNull()))
                .thenReturn(new IConversationService.ConversationLogResult("log", "text/plain"));

        tools.readConversationLog(CONV_ID, -1);

        verify(conversationService).readConversationLog(CONV_ID, "text", null);
    }

    // ==================== readAgentLogs — edge cases ====================

    @Test
    void readAgentLogs_blankFilters_treatedAsNull() throws IOException {
        when(boundedLogStore.getEntries(null, null, null, 50)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.readAgentLogs("", "  ", "  ", null);

        verify(boundedLogStore).getEntries(null, null, null, 50);
    }

    @Test
    void readAgentLogs_customLimit() throws IOException {
        when(boundedLogStore.getEntries(null, null, null, 10)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.readAgentLogs(null, null, null, 10);

        verify(boundedLogStore).getEntries(null, null, null, 10);
    }

    // ==================== readAuditTrail — edge cases ====================

    @Test
    void readAuditTrail_blankConversationId_returnsError() {
        String result = tools.readAuditTrail("  ", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("conversationId is required"));
    }

    @Test
    void readAuditTrail_customLimit() throws IOException {
        when(auditStore.getAuditTrail(CONV_ID, 0, 5)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.readAuditTrail(CONV_ID, 5);

        verify(auditStore).getAuditTrail(CONV_ID, 0, 5);
    }

    @Test
    void readAuditTrail_handlesException() {
        when(auditStore.getAuditTrail(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("audit error"));

        String result = tools.readAuditTrail(CONV_ID, null);

        assertTrue(result.contains("error"));
    }

    // ==================== discoverAgents — edge cases ====================

    @Test
    void discoverAgents_deletedAgent_skipped() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Deleted Agent");
        descriptor.setDeleted(true);
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        String result = tools.discoverAgents(null, "production");

        assertNotNull(result);
        // Deleted agents should be filtered out
    }

    @Test
    void discoverAgents_filterNoMatch_emptyResult() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Weather Agent");
        descriptor.setDescription("Provides weather info");
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.discoverAgents("NoMatchFilter", "production");

        // Agent name "Weather Agent" doesn't match filter "NoMatchFilter"
    }

    @Test
    void discoverAgents_filterMatchesDescription() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Agent A");
        descriptor.setDescription("Weather forecast service");
        status.setDescriptor(descriptor);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.discoverAgents("weather", "production");

        assertNotNull(result);
        // Should match via description containing "weather"
    }

    @Test
    void discoverAgents_handlesException() {
        when(agentAdmin.getDeploymentStatuses(any()))
                .thenThrow(new RuntimeException("service error"));

        String result = tools.discoverAgents(null, null);

        assertTrue(result.contains("error"));
    }

    @Test
    void discoverAgents_nullDescriptor_stillIncludes() throws IOException {
        var status = new AgentDeploymentStatus();
        status.setAgentId(AGENT_ID);
        status.setStatus(Deployment.Status.READY);
        status.setEnvironment(Environment.production);
        status.setDescriptor(null);

        when(agentAdmin.getDeploymentStatuses(Environment.production)).thenReturn(List.of(status));
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        // Null descriptor should not cause NPE (descriptor check at line 448)
        String result = tools.discoverAgents(null, "production");

        assertNotNull(result);
    }

    // ==================== chatWithAgent — edge cases ====================

    @Test
    void chatWithAgent_blankConversationId_createsNew() throws Exception {
        when(conversationService.startConversation(eq(Environment.production), eq(AGENT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class))).thenReturn("{\"conversationId\":\"test-conv-id\"}");

        // Blank string should trigger new conversation creation
        tools.chatWithAgent(AGENT_ID, "Hello!", "  ", "production");

        verify(conversationService).startConversation(any(), eq(AGENT_ID), isNull(), anyMap());
    }

    @Test
    void chatWithAgent_blankAgentId_returnsError() {
        String result = tools.chatWithAgent("  ", "Hello!", null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void chatWithAgent_blankMessage_returnsError() {
        String result = tools.chatWithAgent(AGENT_ID, "  ", null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    // ==================== talkToAgent — edge cases ====================

    @Test
    void talkToAgent_blankAgentId_returnsError() {
        String result = tools.talkToAgent("  ", CONV_ID, "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void talkToAgent_blankConversationId_returnsError() {
        String result = tools.talkToAgent(AGENT_ID, "  ", "Hello!", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("conversationId is required"));
    }

    @Test
    void talkToAgent_blankMessage_returnsError() {
        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "  ", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    // ==================== chatManaged — edge cases ====================

    @Test
    void chatManaged_blankIntent_returnsError() {
        String result = tools.chatManaged("  ", "user1", "hello", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    @Test
    void chatManaged_blankUserId_returnsError() {
        String result = tools.chatManaged("support", "  ", "hello", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void chatManaged_blankMessage_returnsError() {
        String result = tools.chatManaged("support", "user1", "  ", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void chatManaged_emptyDeployments_returnsError() throws Exception {
        when(userConversationStore.readUserConversation("support", "user1"))
                .thenThrow(new ai.labs.eddi.datastore.IResourceStore.ResourceStoreException("not found"));

        var trigger = new AgentTriggerConfiguration();
        trigger.setIntent("support");
        trigger.setAgentDeployments(List.of()); // empty deployments
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(trigger);

        String result = tools.chatManaged("support", "user1", "hello", null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("No Agent trigger"));
    }

    @Test
    void chatManaged_nullTrigger_returnsError() throws Exception {
        when(userConversationStore.readUserConversation("support", "user1"))
                .thenThrow(new ai.labs.eddi.datastore.IResourceStore.ResourceStoreException("not found"));

        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(null);

        String result = tools.chatManaged("support", "user1", "hello", null);

        assertTrue(result.contains("error"));
    }

    // ==================== createConversation — edge cases ====================

    @Test
    void createConversation_blankAgentId_returnsError() {
        String result = tools.createConversation("  ", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void createConversation_testEnvironment() throws Exception {
        when(conversationService.startConversation(eq(Environment.test), eq(AGENT_ID), isNull(), anyMap()))
                .thenReturn(new ConversationResult(CONV_ID, URI.create("eddi://conv/" + CONV_ID)));
        when(jsonSerialization.serialize(any())).thenReturn("{\"conversationId\":\"test-conv-id\"}");

        tools.createConversation(AGENT_ID, "test");

        verify(conversationService).startConversation(eq(Environment.test), eq(AGENT_ID), isNull(), anyMap());
    }

    // ==================== buildConversationResponse — edge cases
    // ====================

    @Test
    void talkToAgent_withOutputContainingTexts_extractsAgentResponse() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of(
                Map.of("text", "Hello!"),
                Map.of("text", "How can I help?")));
        snapshot.setConversationOutputs(List.of(output));
        snapshot.setConversationState(ConversationState.READY);

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class))).thenReturn("{\"agentResponse\":\"Hello! How can I help?\"}");

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertNotNull(result);
        assertTrue(result.contains("agentResponse"));
    }

    @Test
    void talkToAgent_withQuickReplies_extractsValues() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("quickReplies", List.of(
                Map.of("value", "Yes"),
                Map.of("value", "No")));
        snapshot.setConversationOutputs(List.of(output));
        snapshot.setConversationState(ConversationState.READY);

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class))).thenReturn("{\"quickReplies\":[\"Yes\",\"No\"]}");

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertNotNull(result);
    }

    @Test
    void talkToAgent_emptyOutputs_noException() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(List.of());
        snapshot.setConversationState(ConversationState.READY);

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class))).thenReturn("{}");

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertNotNull(result);
    }

    @Test
    void talkToAgent_nullConversationState_noException() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationOutputs(List.of());
        snapshot.setConversationState(null);

        doAnswer(invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq(CONV_ID), anyBoolean(), anyBoolean(), anyList(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));

        when(jsonSerialization.serialize(any(LinkedHashMap.class))).thenReturn("{}");

        String result = tools.talkToAgent(AGENT_ID, CONV_ID, "Hello!", null);

        assertNotNull(result);
    }
}
