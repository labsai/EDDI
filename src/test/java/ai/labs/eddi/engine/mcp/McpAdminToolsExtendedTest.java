/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for McpAdminTools — schedule management, channel integrations,
 * deploy edge cases, readWorkflow, readResource, updateAgent, and error paths
 * not covered by McpAdminToolsTest or McpAdminToolsCrudTest.
 */
class McpAdminToolsExtendedTest {

    private static final String AGENT_ID = "test-agent-id";
    private static final String RESOURCE_ID = "test-resource-id";
    private static final String SCHEDULE_ID = "test-schedule-id";

    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private IRestRuleSetStore behaviorStore;
    private IRestLlmStore llmStore;
    private IRestApiCallsStore httpCallsStore;
    private IRestMcpCallsStore mcpCallsStore;
    private IRestOutputStore outputStore;
    private IRestPropertySetterStore propertySetterStore;
    private IRestDictionaryStore dictionaryStore;
    private IRestChannelIntegrationStore channelStore;
    private IRestAgentTriggerStore agentTriggerStore;
    private IJsonSerialization jsonSerialization;
    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor scheduleFireExecutor;
    private SchedulePollerService schedulePollerService;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        agentAdmin = mock(IRestAgentAdministration.class);
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        behaviorStore = mock(IRestRuleSetStore.class);
        llmStore = mock(IRestLlmStore.class);
        httpCallsStore = mock(IRestApiCallsStore.class);
        mcpCallsStore = mock(IRestMcpCallsStore.class);
        outputStore = mock(IRestOutputStore.class);
        propertySetterStore = mock(IRestPropertySetterStore.class);
        dictionaryStore = mock(IRestDictionaryStore.class);
        channelStore = mock(IRestChannelIntegrationStore.class);
        agentTriggerStore = mock(IRestAgentTriggerStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        scheduleStore = mock(IScheduleStore.class);
        scheduleFireExecutor = mock(ScheduleFireExecutor.class);
        schedulePollerService = mock(SchedulePollerService.class);

        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(agentStore);
        when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(workflowStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);
        when(restInterfaceFactory.get(IRestRuleSetStore.class)).thenReturn(behaviorStore);
        when(restInterfaceFactory.get(IRestLlmStore.class)).thenReturn(llmStore);
        when(restInterfaceFactory.get(IRestApiCallsStore.class)).thenReturn(httpCallsStore);
        when(restInterfaceFactory.get(IRestMcpCallsStore.class)).thenReturn(mcpCallsStore);
        when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
        when(restInterfaceFactory.get(IRestPropertySetterStore.class)).thenReturn(propertySetterStore);
        when(restInterfaceFactory.get(IRestDictionaryStore.class)).thenReturn(dictionaryStore);
        when(restInterfaceFactory.get(IRestChannelIntegrationStore.class)).thenReturn(channelStore);
        when(restInterfaceFactory.get(IRestAgentTriggerStore.class)).thenReturn(agentTriggerStore);

        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        lenient().when(schedulePollerService.getInstanceId()).thenReturn("test-instance");

        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);

        tools = new McpAdminTools(restInterfaceFactory, agentAdmin, jsonSerialization,
                scheduleStore, scheduleFireExecutor, schedulePollerService,
                mockIdentity, false);
    }

    // ==================== deployAgent — edge cases ====================

    @Test
    void deployAgent_nullVersion_defaultsToOne() throws IOException {
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        String result = tools.deployAgent(AGENT_ID, null, "production");

        assertNotNull(result);
        verify(agentAdmin).deployAgent(Environment.production, AGENT_ID, 1, true, true);
    }

    @Test
    void deployAgent_status202_inProgress() throws IOException {
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true))
                .thenReturn(Response.accepted().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"deploymentStatus\":\"IN_PROGRESS\"}");

        String result = tools.deployAgent(AGENT_ID, 1, "production");

        assertNotNull(result);
        // The result should indicate IN_PROGRESS for 202
    }

    @Test
    void deployAgent_status200_withReadyBody() throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "READY");
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true))
                .thenReturn(Response.ok(body).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"deployed\":true,\"deploymentStatus\":\"READY\"}");

        String result = tools.deployAgent(AGENT_ID, 1, "production");

        assertNotNull(result);
        assertTrue(result.contains("READY"));
    }

    @Test
    void deployAgent_status200_failedBody() throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "ERROR");
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true))
                .thenReturn(Response.ok(body).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deploy_failed\"}");

        String result = tools.deployAgent(AGENT_ID, 1, "production");

        assertNotNull(result);
        assertTrue(result.contains("deploy_failed"));
    }

    @Test
    void deployAgent_testEnvironment() throws IOException {
        when(agentAdmin.deployAgent(Environment.test, AGENT_ID, 1, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"environment\":\"test\"}");

        tools.deployAgent(AGENT_ID, 1, "test");

        verify(agentAdmin).deployAgent(Environment.test, AGENT_ID, 1, true, true);
    }

    // ==================== undeployAgent — edge cases ====================

    @Test
    void undeployAgent_nullVersionAndEndConversations_defaults() throws IOException {
        when(agentAdmin.undeployAgent(Environment.production, AGENT_ID, 1, false, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        tools.undeployAgent(AGENT_ID, null, null, null);

        verify(agentAdmin).undeployAgent(Environment.production, AGENT_ID, 1, false, false);
    }

    @Test
    void undeployAgent_handlesException() {
        when(agentAdmin.undeployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Undeploy failed"));

        String result = tools.undeployAgent(AGENT_ID, 1, null, false);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Undeploy failed"));
    }

    // ==================== getDeploymentStatus — edge cases ====================

    @Test
    void getDeploymentStatus_handlesException() {
        when(agentAdmin.getDeploymentStatus(any(), any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("Status check failed"));

        String result = tools.getDeploymentStatus(AGENT_ID, 1, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Status check failed"));
    }

    @Test
    void getDeploymentStatus_nullVersion_defaultsToOne() throws IOException {
        when(agentAdmin.getDeploymentStatus(Environment.production, AGENT_ID, 1, "json"))
                .thenReturn(Response.ok("ready").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"READY\"}");

        tools.getDeploymentStatus(AGENT_ID, null, null);

        verify(agentAdmin).getDeploymentStatus(Environment.production, AGENT_ID, 1, "json");
    }

    // ==================== listWorkflows — error path ====================

    @Test
    void listWorkflows_handlesException() {
        when(workflowStore.readWorkflowDescriptors(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.listWorkflows(null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("DB error"));
    }

    // ==================== readWorkflow ====================

    @Test
    void readWorkflow_success() throws IOException {
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of());
        when(workflowStore.readWorkflow("wf1", 1)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{\"workflowId\":\"wf1\"}");

        String result = tools.readWorkflow("wf1", 1);

        assertNotNull(result);
        verify(workflowStore).readWorkflow("wf1", 1);
    }

    @Test
    void readWorkflow_nullId_returnsError() {
        String result = tools.readWorkflow(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("workflowId is required"));
    }

    @Test
    void readWorkflow_blankId_returnsError() {
        String result = tools.readWorkflow("  ", 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("workflowId is required"));
    }

    @Test
    void readWorkflow_notFound_returnsError() {
        when(workflowStore.readWorkflow("missing", 1)).thenReturn(null);

        String result = tools.readWorkflow("missing", 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Workflow not found"));
    }

    @Test
    void readWorkflow_nullVersion_defaultsToOne() throws IOException {
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of());
        when(workflowStore.readWorkflow("wf1", 1)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readWorkflow("wf1", null);

        verify(workflowStore).readWorkflow("wf1", 1);
    }

    @Test
    void readWorkflow_handlesException() {
        when(workflowStore.readWorkflow(any(), anyInt())).thenThrow(new RuntimeException("Read failed"));

        String result = tools.readWorkflow("wf1", 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Read failed"));
    }

    // ==================== readResource ====================

    @Test
    void readResource_behavior_success() throws IOException {
        var config = new RuleSetConfiguration();
        when(behaviorStore.readRuleSet(RESOURCE_ID, 1)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{\"resourceType\":\"behavior\"}");

        String result = tools.readResource("behavior", RESOURCE_ID, 1);

        assertNotNull(result);
        verify(behaviorStore).readRuleSet(RESOURCE_ID, 1);
    }

    @Test
    void readResource_langchain_success() throws IOException {
        var config = new LlmConfiguration(List.of());
        when(llmStore.readLlm(RESOURCE_ID, 1)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("langchain", RESOURCE_ID, 1);

        verify(llmStore).readLlm(RESOURCE_ID, 1);
    }

    @Test
    void readResource_httpcalls_success() throws IOException {
        when(httpCallsStore.readApiCalls(RESOURCE_ID, 1)).thenReturn(new ApiCallsConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("httpcalls", RESOURCE_ID, 1);

        verify(httpCallsStore).readApiCalls(RESOURCE_ID, 1);
    }

    @Test
    void readResource_mcpcalls_success() throws IOException {
        when(mcpCallsStore.readMcpCalls(RESOURCE_ID, 1)).thenReturn(new McpCallsConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("mcpcalls", RESOURCE_ID, 1);

        verify(mcpCallsStore).readMcpCalls(RESOURCE_ID, 1);
    }

    @Test
    void readResource_output_success() throws IOException {
        when(outputStore.readOutputSet(RESOURCE_ID, 1, "", "", 0, 0)).thenReturn(new OutputConfigurationSet());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("output", RESOURCE_ID, 1);

        verify(outputStore).readOutputSet(RESOURCE_ID, 1, "", "", 0, 0);
    }

    @Test
    void readResource_propertysetter_success() throws IOException {
        when(propertySetterStore.readPropertySetter(RESOURCE_ID, 1)).thenReturn(new PropertySetterConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("propertysetter", RESOURCE_ID, 1);

        verify(propertySetterStore).readPropertySetter(RESOURCE_ID, 1);
    }

    @Test
    void readResource_dictionaries_success() throws IOException {
        when(dictionaryStore.readRegularDictionary(RESOURCE_ID, 1, "", "", 0, 0)).thenReturn(new DictionaryConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("dictionaries", RESOURCE_ID, 1);

        verify(dictionaryStore).readRegularDictionary(RESOURCE_ID, 1, "", "", 0, 0);
    }

    @Test
    void readResource_nullType_returnsError() {
        String result = tools.readResource(null, RESOURCE_ID, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void readResource_nullId_returnsError() {
        String result = tools.readResource("behavior", null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void readResource_unknownType_returnsError() {
        String result = tools.readResource("unknown_type", RESOURCE_ID, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown resource type"));
    }

    @Test
    void readResource_notFound_returnsError() {
        when(behaviorStore.readRuleSet(RESOURCE_ID, 1)).thenReturn(null);

        String result = tools.readResource("behavior", RESOURCE_ID, 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Resource not found"));
    }

    @Test
    void readResource_nullVersion_defaultsToOne() throws IOException {
        when(behaviorStore.readRuleSet(RESOURCE_ID, 1)).thenReturn(new RuleSetConfiguration());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readResource("behavior", RESOURCE_ID, null);

        verify(behaviorStore).readRuleSet(RESOURCE_ID, 1);
    }

    // ==================== updateAgent ====================

    @Test
    void updateAgent_nullAgentId_returnsError() {
        String result = tools.updateAgent(null, 1, "Name", null, false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void updateAgent_blankAgentId_returnsError() {
        String result = tools.updateAgent("  ", 1, "Name", null, false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void updateAgent_nameOnly() throws IOException {
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true}");

        String result = tools.updateAgent(AGENT_ID, 1, "New Name", null, false, null);

        assertNotNull(result);
        assertTrue(result.contains("updated"));
        verify(descriptorStore).patchDescriptor(eq(AGENT_ID), eq(1), any());
    }

    @Test
    void updateAgent_descriptionOnly() throws IOException {
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true}");

        tools.updateAgent(AGENT_ID, 1, null, "New Description", false, null);

        verify(descriptorStore).patchDescriptor(eq(AGENT_ID), eq(1), any());
    }

    @Test
    void updateAgent_noNameOrDescription_skipsDescriptorPatch() throws IOException {
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true}");

        tools.updateAgent(AGENT_ID, 1, null, null, false, null);

        verify(descriptorStore, never()).patchDescriptor(any(), anyInt(), any());
    }

    @Test
    void updateAgent_withRedeploy_success() throws IOException {
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true,\"redeployed\":true}");

        String result = tools.updateAgent(AGENT_ID, 1, "Name", null, true, "production");

        assertTrue(result.contains("redeployed"));
        verify(agentAdmin).deployAgent(Environment.production, AGENT_ID, 1, true, true);
    }

    @Test
    void updateAgent_redeployFails_stillReturnsSuccess() throws IOException {
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy error"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true,\"redeployed\":false}");

        String result = tools.updateAgent(AGENT_ID, 1, "Name", null, true, null);

        assertNotNull(result);
        // Should still return result (updated=true with deployError info)
    }

    @Test
    void updateAgent_nullVersion_defaultsToOne() throws IOException {
        when(jsonSerialization.serialize(any())).thenReturn("{\"updated\":true}");

        tools.updateAgent(AGENT_ID, null, "Name", null, false, null);

        verify(descriptorStore).patchDescriptor(eq(AGENT_ID), eq(1), any());
    }

    @Test
    void updateAgent_handlesException() {
        doThrow(new RuntimeException("DB error")).when(descriptorStore).patchDescriptor(any(), anyInt(), any());

        String result = tools.updateAgent(AGENT_ID, 1, "Name", null, false, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("DB error"));
    }

    // ==================== createAgent — edge cases ====================

    @Test
    void createAgent_nullName_returnsError() {
        String result = tools.createAgent(null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent name is required"));
    }

    @Test
    void createAgent_blankName_returnsError() {
        String result = tools.createAgent("   ", null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent name is required"));
    }

    // ==================== deleteResource — additional type coverage
    // ====================

    @Test
    void deleteResource_langchain_success() throws IOException {
        when(llmStore.deleteLlm(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteResource("langchain", RESOURCE_ID, 1, false);

        assertNotNull(result);
        verify(llmStore).deleteLlm(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_mcpcalls_success() throws IOException {
        when(mcpCallsStore.deleteMcpCalls(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteResource("mcpcalls", RESOURCE_ID, 1, false);

        verify(mcpCallsStore).deleteMcpCalls(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_output_success() throws IOException {
        when(outputStore.deleteOutputSet(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteResource("output", RESOURCE_ID, 1, false);

        verify(outputStore).deleteOutputSet(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_propertysetter_success() throws IOException {
        when(propertySetterStore.deletePropertySetter(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteResource("propertysetter", RESOURCE_ID, 1, false);

        verify(propertySetterStore).deletePropertySetter(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_dictionaries_success() throws IOException {
        when(dictionaryStore.deleteRegularDictionary(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteResource("dictionaries", RESOURCE_ID, 1, false);

        verify(dictionaryStore).deleteRegularDictionary(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_unknownType_returnsError() {
        String result = tools.deleteResource("unknown_type", RESOURCE_ID, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown resource type"));
    }

    @Test
    void deleteResource_nullVersion_defaultsToOne() throws IOException {
        when(behaviorStore.deleteRuleSet(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteResource("behavior", RESOURCE_ID, null, null);

        verify(behaviorStore).deleteRuleSet(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_handlesException() {
        when(behaviorStore.deleteRuleSet(any(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("Delete failed"));

        String result = tools.deleteResource("behavior", RESOURCE_ID, 1, false);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Delete failed"));
    }

    // ==================== createResource — additional types ====================

    @Test
    void createResource_behavior_success() throws IOException {
        var config = new RuleSetConfiguration();
        when(jsonSerialization.deserialize("{}", RuleSetConfiguration.class)).thenReturn(config);
        when(behaviorStore.createRuleSet(config))
                .thenReturn(Response.created(URI.create("/rulestore/rulesets/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createResource("behavior", "{}");

        assertNotNull(result);
        verify(behaviorStore).createRuleSet(config);
    }

    @Test
    void createResource_langchain_success() throws IOException {
        var config = new LlmConfiguration(List.of());
        when(jsonSerialization.deserialize("{}", LlmConfiguration.class)).thenReturn(config);
        when(llmStore.createLlm(config))
                .thenReturn(Response.created(URI.create("/llmstore/llms/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createResource("langchain", "{}");

        verify(llmStore).createLlm(config);
    }

    @Test
    void createResource_mcpcalls_success() throws IOException {
        var config = new McpCallsConfiguration();
        when(jsonSerialization.deserialize("{}", McpCallsConfiguration.class)).thenReturn(config);
        when(mcpCallsStore.createMcpCalls(config))
                .thenReturn(Response.created(URI.create("/mcpcallsstore/mcpcalls/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createResource("mcpcalls", "{}");

        verify(mcpCallsStore).createMcpCalls(config);
    }

    @Test
    void createResource_propertysetter_success() throws IOException {
        var config = new PropertySetterConfiguration();
        when(jsonSerialization.deserialize("{}", PropertySetterConfiguration.class)).thenReturn(config);
        when(propertySetterStore.createPropertySetter(config))
                .thenReturn(Response.created(URI.create("/propertysetterstore/propertysetters/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createResource("propertysetter", "{}");

        verify(propertySetterStore).createPropertySetter(config);
    }

    @Test
    void createResource_dictionaries_success() throws IOException {
        var config = new DictionaryConfiguration();
        when(jsonSerialization.deserialize("{}", DictionaryConfiguration.class)).thenReturn(config);
        when(dictionaryStore.createRegularDictionary(config))
                .thenReturn(Response.created(URI.create("/regulardictionarystore/regulardictionaries/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createResource("dictionaries", "{}");

        verify(dictionaryStore).createRegularDictionary(config);
    }

    @Test
    void createResource_unknownType_returnsError() throws IOException {
        when(jsonSerialization.deserialize(anyString(), any())).thenReturn(null);

        String result = tools.createResource("unknown_type", "{}");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown resource type"));
    }

    @Test
    void createResource_blankType_returnsError() {
        String result = tools.createResource("  ", "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void createResource_blankConfig_returnsError() {
        String result = tools.createResource("behavior", "  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    // ==================== updateResource — additional types ====================

    @Test
    void updateResource_httpcalls_success() throws IOException {
        var config = new ApiCallsConfiguration();
        when(jsonSerialization.deserialize("{}", ApiCallsConfiguration.class)).thenReturn(config);
        when(httpCallsStore.updateApiCalls(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/apicallstore/apicalls/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateResource("httpcalls", RESOURCE_ID, 1, "{}");

        assertNotNull(result);
        verify(httpCallsStore).updateApiCalls(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_mcpcalls_success() throws IOException {
        var config = new McpCallsConfiguration();
        when(jsonSerialization.deserialize("{}", McpCallsConfiguration.class)).thenReturn(config);
        when(mcpCallsStore.updateMcpCalls(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/store/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        tools.updateResource("mcpcalls", RESOURCE_ID, 1, "{}");

        verify(mcpCallsStore).updateMcpCalls(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_output_success() throws IOException {
        var config = new OutputConfigurationSet();
        when(jsonSerialization.deserialize("{}", OutputConfigurationSet.class)).thenReturn(config);
        when(outputStore.updateOutputSet(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/store/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        tools.updateResource("output", RESOURCE_ID, 1, "{}");

        verify(outputStore).updateOutputSet(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_propertysetter_success() throws IOException {
        var config = new PropertySetterConfiguration();
        when(jsonSerialization.deserialize("{}", PropertySetterConfiguration.class)).thenReturn(config);
        when(propertySetterStore.updatePropertySetter(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/store/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        tools.updateResource("propertysetter", RESOURCE_ID, 1, "{}");

        verify(propertySetterStore).updatePropertySetter(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_dictionaries_success() throws IOException {
        var config = new DictionaryConfiguration();
        when(jsonSerialization.deserialize("{}", DictionaryConfiguration.class)).thenReturn(config);
        when(dictionaryStore.updateRegularDictionary(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/store/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        tools.updateResource("dictionaries", RESOURCE_ID, 1, "{}");

        verify(dictionaryStore).updateRegularDictionary(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_blankType_returnsError() {
        String result = tools.updateResource("  ", RESOURCE_ID, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void updateResource_blankId_returnsError() {
        String result = tools.updateResource("langchain", "  ", 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void updateResource_blankConfig_returnsError() {
        String result = tools.updateResource("langchain", RESOURCE_ID, 1, "  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateResource_nullVersion_defaultsToOne() throws IOException {
        var config = new LlmConfiguration(List.of());
        when(jsonSerialization.deserialize("{}", LlmConfiguration.class)).thenReturn(config);
        when(llmStore.updateLlm(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/llmstore/llms/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.updateResource("langchain", RESOURCE_ID, null, "{}");

        verify(llmStore).updateLlm(RESOURCE_ID, 1, config);
    }

    // ==================== listAgentResources — edge cases ====================

    @Test
    void listAgentResources_descriptorReadFailure_stillSucceeds() throws IOException {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);
        when(descriptorStore.readDescriptor(AGENT_ID, 1)).thenThrow(new RuntimeException("descriptor error"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"agentId\":\"test\"}");

        String result = tools.listAgentResources(AGENT_ID, 1);

        assertNotNull(result);
        // Should succeed even if descriptor read fails
    }

    @Test
    void listAgentResources_nullVersion_defaultsToOne() throws IOException {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.listAgentResources(AGENT_ID, null);

        verify(agentStore).readAgent(AGENT_ID, 1);
    }

    @Test
    void listAgentResources_blankAgentId_returnsError() {
        String result = tools.listAgentResources("  ", 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    // ==================== Schedule Management ====================

    @Test
    void createSchedule_cron_success() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn(SCHEDULE_ID);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_created\"}");

        String result = tools.createSchedule(AGENT_ID, "CRON", "0 9 * * MON-FRI", null,
                "Daily report", "Morning Report", "Europe/Vienna", "new", "system:scheduler", "production");

        assertNotNull(result);
        assertTrue(result.contains("schedule_created"));
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    void createSchedule_heartbeat_success() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn(SCHEDULE_ID);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_created\"}");

        String result = tools.createSchedule(AGENT_ID, "HEARTBEAT", null, 300L,
                null, "Health Check", null, null, null, null);

        assertNotNull(result);
        assertTrue(result.contains("schedule_created"));

        ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(captor.capture());
        assertEquals(ScheduleConfiguration.TriggerType.HEARTBEAT, captor.getValue().getTriggerType());
        assertEquals("heartbeat", captor.getValue().getMessage());
        assertEquals("persistent", captor.getValue().getConversationStrategy());
    }

    @Test
    void createSchedule_inferHeartbeat_fromInterval() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn(SCHEDULE_ID);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_created\"}");

        // No triggerType, but heartbeatIntervalSeconds is set
        tools.createSchedule(AGENT_ID, null, null, 60L,
                "ping", "Pinger", null, null, null, null);

        ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(captor.capture());
        assertEquals(ScheduleConfiguration.TriggerType.HEARTBEAT, captor.getValue().getTriggerType());
    }

    @Test
    void createSchedule_missingAgentId_returnsError() {
        String result = tools.createSchedule(null, "CRON", "0 9 * * *", null,
                "msg", "name", null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void createSchedule_missingName_returnsError() {
        String result = tools.createSchedule(AGENT_ID, "CRON", "0 9 * * *", null,
                "msg", null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("name is required"));
    }

    @Test
    void createSchedule_cronMissingExpression_returnsError() {
        String result = tools.createSchedule(AGENT_ID, "CRON", null, null,
                "msg", "name", null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("cron expression is required"));
    }

    @Test
    void createSchedule_cronMissingMessage_returnsError() {
        String result = tools.createSchedule(AGENT_ID, "CRON", "0 9 * * *", null,
                null, "name", null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void createSchedule_heartbeatMissingInterval_returnsError() {
        String result = tools.createSchedule(AGENT_ID, "HEARTBEAT", null, null,
                "msg", "name", null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("heartbeatIntervalSeconds is required"));
    }

    @Test
    void createSchedule_heartbeatZeroInterval_returnsError() {
        String result = tools.createSchedule(AGENT_ID, "HEARTBEAT", null, 0L,
                "msg", "name", null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("heartbeatIntervalSeconds is required"));
    }

    @Test
    void createSchedule_handlesException() throws Exception {
        when(scheduleStore.createSchedule(any())).thenThrow(new RuntimeException("DB error"));

        String result = tools.createSchedule(AGENT_ID, "HEARTBEAT", null, 300L,
                "msg", "name", null, null, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("DB error"));
    }

    // ==================== listSchedules ====================

    @Test
    void listSchedules_noFilter_returnsAll() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setId(SCHEDULE_ID);
        schedule.setName("Test Schedule");
        schedule.setAgentId(AGENT_ID);
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
        schedule.setCronExpression("0 9 * * *");
        schedule.setEnabled(true);
        schedule.setFireStatus(ScheduleConfiguration.FireStatus.PENDING);
        when(scheduleStore.readAllSchedules(100)).thenReturn(List.of(schedule));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.listSchedules(null);

        assertNotNull(result);
        verify(scheduleStore).readAllSchedules(100);
    }

    @Test
    void listSchedules_withAgentFilter() throws Exception {
        when(scheduleStore.readSchedulesByAgentId(AGENT_ID)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.listSchedules(AGENT_ID);

        verify(scheduleStore).readSchedulesByAgentId(AGENT_ID);
        verify(scheduleStore, never()).readAllSchedules(anyInt());
    }

    @Test
    void listSchedules_blankFilter_treatedAsNoFilter() throws Exception {
        when(scheduleStore.readAllSchedules(100)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");

        tools.listSchedules("  ");

        verify(scheduleStore).readAllSchedules(100);
    }

    @Test
    void listSchedules_handlesException() throws Exception {
        when(scheduleStore.readAllSchedules(anyInt())).thenThrow(new RuntimeException("list error"));

        String result = tools.listSchedules(null);

        assertTrue(result.contains("error"));
    }

    @Test
    void listSchedules_heartbeatSchedule_includesInterval() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setId(SCHEDULE_ID);
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
        schedule.setHeartbeatIntervalSeconds(300L);
        when(scheduleStore.readAllSchedules(100)).thenReturn(List.of(schedule));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.listSchedules(null);

        assertNotNull(result);
    }

    // ==================== readSchedule ====================

    @Test
    void readSchedule_success() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setId(SCHEDULE_ID);
        schedule.setName("Test");
        schedule.setAgentId(AGENT_ID);
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
        schedule.setCronExpression("0 9 * * *");
        schedule.setTimeZone("UTC");
        schedule.setEnabled(true);
        when(scheduleStore.readSchedule(SCHEDULE_ID)).thenReturn(schedule);
        when(scheduleStore.readFireLogs(SCHEDULE_ID, 10)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"scheduleId\":\"test-schedule-id\"}");

        String result = tools.readSchedule(SCHEDULE_ID);

        assertNotNull(result);
        verify(scheduleStore).readSchedule(SCHEDULE_ID);
        verify(scheduleStore).readFireLogs(SCHEDULE_ID, 10);
    }

    @Test
    void readSchedule_nullId_returnsError() {
        String result = tools.readSchedule(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("scheduleId is required"));
    }

    @Test
    void readSchedule_blankId_returnsError() {
        String result = tools.readSchedule("  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("scheduleId is required"));
    }

    @Test
    void readSchedule_handlesException() throws Exception {
        when(scheduleStore.readSchedule(SCHEDULE_ID)).thenThrow(new RuntimeException("not found"));

        String result = tools.readSchedule(SCHEDULE_ID);

        assertTrue(result.contains("error"));
    }

    // ==================== deleteSchedule ====================

    @Test
    void deleteSchedule_success() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_deleted\"}");

        String result = tools.deleteSchedule(SCHEDULE_ID);

        assertNotNull(result);
        assertTrue(result.contains("schedule_deleted"));
        verify(scheduleStore).deleteSchedule(SCHEDULE_ID);
    }

    @Test
    void deleteSchedule_nullId_returnsError() {
        String result = tools.deleteSchedule(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("scheduleId is required"));
    }

    @Test
    void deleteSchedule_handlesException() throws Exception {
        doThrow(new RuntimeException("delete failed")).when(scheduleStore).deleteSchedule(SCHEDULE_ID);

        String result = tools.deleteSchedule(SCHEDULE_ID);

        assertTrue(result.contains("error"));
    }

    // ==================== fireScheduleNow ====================

    @Test
    void fireScheduleNow_success() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setName("Test Schedule");
        when(scheduleStore.readSchedule(SCHEDULE_ID)).thenReturn(schedule);

        var fireLog = new ScheduleFireLog("log1", SCHEDULE_ID, "fire1",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(2),
                "COMPLETED", "inst-1", "conv-1", null, 1, 0.01);
        when(scheduleFireExecutor.fire(eq(schedule), eq("test-instance"), eq(1))).thenReturn(fireLog);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_fired\"}");

        String result = tools.fireScheduleNow(SCHEDULE_ID);

        assertNotNull(result);
        assertTrue(result.contains("schedule_fired"));
        verify(scheduleFireExecutor).fire(schedule, "test-instance", 1);
    }

    @Test
    void fireScheduleNow_withError() throws Exception {
        var schedule = new ScheduleConfiguration();
        schedule.setName("Test Schedule");
        when(scheduleStore.readSchedule(SCHEDULE_ID)).thenReturn(schedule);

        var fireLog = new ScheduleFireLog("log1", SCHEDULE_ID, "fire1",
                Instant.now(), Instant.now(), null,
                "FAILED", "inst-1", null, "Agent timeout", 1, 0.0);
        when(scheduleFireExecutor.fire(eq(schedule), eq("test-instance"), eq(1))).thenReturn(fireLog);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_fired\",\"error\":\"Agent timeout\"}");

        String result = tools.fireScheduleNow(SCHEDULE_ID);

        assertNotNull(result);
    }

    @Test
    void fireScheduleNow_nullId_returnsError() {
        String result = tools.fireScheduleNow(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("scheduleId is required"));
    }

    @Test
    void fireScheduleNow_handlesException() throws Exception {
        when(scheduleStore.readSchedule(SCHEDULE_ID)).thenThrow(new RuntimeException("not found"));

        String result = tools.fireScheduleNow(SCHEDULE_ID);

        assertTrue(result.contains("error"));
    }

    // ==================== retryFailedSchedule ====================

    @Test
    void retryFailedSchedule_success() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"schedule_requeued\"}");

        String result = tools.retryFailedSchedule(SCHEDULE_ID);

        assertNotNull(result);
        assertTrue(result.contains("schedule_requeued"));
        verify(scheduleStore).requeueDeadLetter(SCHEDULE_ID);
    }

    @Test
    void retryFailedSchedule_nullId_returnsError() {
        String result = tools.retryFailedSchedule(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("scheduleId is required"));
    }

    @Test
    void retryFailedSchedule_handlesException() throws Exception {
        doThrow(new RuntimeException("not dead-lettered")).when(scheduleStore).requeueDeadLetter(SCHEDULE_ID);

        String result = tools.retryFailedSchedule(SCHEDULE_ID);

        assertTrue(result.contains("error"));
    }

    // ==================== Channel Integration Tools ====================

    @Test
    void listChannelIntegrations_success() throws IOException {
        when(channelStore.readChannelDescriptors("", 0, 20)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        String result = tools.listChannelIntegrations(null, null);

        assertNotNull(result);
        verify(channelStore).readChannelDescriptors("", 0, 20);
    }

    @Test
    void listChannelIntegrations_withFilterAndLimit() throws IOException {
        when(channelStore.readChannelDescriptors("slack", 0, 5)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listChannelIntegrations("slack", 5);

        verify(channelStore).readChannelDescriptors("slack", 0, 5);
    }

    @Test
    void listChannelIntegrations_handlesException() {
        when(channelStore.readChannelDescriptors(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("channel error"));

        String result = tools.listChannelIntegrations(null, null);

        assertTrue(result.contains("error"));
    }

    @Test
    void readChannelIntegration_success() throws IOException {
        var config = new ChannelIntegrationConfiguration();
        when(channelStore.getCurrentVersion(RESOURCE_ID)).thenReturn(2);
        when(channelStore.readChannel(RESOURCE_ID, 2)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{\"resourceId\":\"test\"}");

        String result = tools.readChannelIntegration(RESOURCE_ID, null);

        assertNotNull(result);
        verify(channelStore).readChannel(RESOURCE_ID, 2);
    }

    @Test
    void readChannelIntegration_withVersion() throws IOException {
        var config = new ChannelIntegrationConfiguration();
        when(channelStore.readChannel(RESOURCE_ID, 3)).thenReturn(config);
        when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools.readChannelIntegration(RESOURCE_ID, 3);

        verify(channelStore).readChannel(RESOURCE_ID, 3);
    }

    @Test
    void readChannelIntegration_nullId_returnsError() {
        String result = tools.readChannelIntegration(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void readChannelIntegration_handlesException() {
        when(channelStore.getCurrentVersion(any())).thenThrow(new RuntimeException("read error"));

        String result = tools.readChannelIntegration(RESOURCE_ID, null);

        assertTrue(result.contains("error"));
    }

    @Test
    void createChannelIntegration_success() throws IOException {
        var config = new ChannelIntegrationConfiguration();
        config.setName("Slack Channel");
        config.setChannelType("slack");
        config.setTargets(List.of());
        when(jsonSerialization.deserialize("{}", ChannelIntegrationConfiguration.class)).thenReturn(config);
        when(channelStore.createChannel(config))
                .thenReturn(Response.created(URI.create("/channelstore/channels/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createChannelIntegration("{}");

        assertNotNull(result);
        verify(channelStore).createChannel(config);
    }

    @Test
    void createChannelIntegration_nullConfig_returnsError() {
        String result = tools.createChannelIntegration(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void createChannelIntegration_handlesException() throws IOException {
        when(jsonSerialization.deserialize(anyString(), eq(ChannelIntegrationConfiguration.class)))
                .thenThrow(new RuntimeException("parse error"));

        String result = tools.createChannelIntegration("{}");

        assertTrue(result.contains("error"));
    }

    @Test
    void updateChannelIntegration_success() throws IOException {
        var config = new ChannelIntegrationConfiguration();
        when(jsonSerialization.deserialize("{}", ChannelIntegrationConfiguration.class)).thenReturn(config);
        when(channelStore.updateChannel(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location", "/channelstore/channels/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateChannelIntegration(RESOURCE_ID, 1, "{}");

        assertNotNull(result);
        verify(channelStore).updateChannel(RESOURCE_ID, 1, config);
    }

    @Test
    void updateChannelIntegration_nullId_returnsError() {
        String result = tools.updateChannelIntegration(null, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void updateChannelIntegration_nullConfig_returnsError() {
        String result = tools.updateChannelIntegration(RESOURCE_ID, 1, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateChannelIntegration_handlesException() throws IOException {
        when(jsonSerialization.deserialize(anyString(), eq(ChannelIntegrationConfiguration.class)))
                .thenThrow(new RuntimeException("update error"));

        String result = tools.updateChannelIntegration(RESOURCE_ID, 1, "{}");

        assertTrue(result.contains("error"));
    }

    @Test
    void deleteChannelIntegration_success() throws IOException {
        when(channelStore.deleteChannel(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteChannelIntegration(RESOURCE_ID, 1, false);

        assertNotNull(result);
        verify(channelStore).deleteChannel(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteChannelIntegration_permanent() throws IOException {
        when(channelStore.deleteChannel(RESOURCE_ID, 2, true)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteChannelIntegration(RESOURCE_ID, 2, true);

        verify(channelStore).deleteChannel(RESOURCE_ID, 2, true);
    }

    @Test
    void deleteChannelIntegration_nullId_returnsError() {
        String result = tools.deleteChannelIntegration(null, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void deleteChannelIntegration_nullVersionAndPermanent_defaults() throws IOException {
        when(channelStore.deleteChannel(RESOURCE_ID, 1, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteChannelIntegration(RESOURCE_ID, null, null);

        verify(channelStore).deleteChannel(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteChannelIntegration_handlesException() {
        when(channelStore.deleteChannel(any(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("delete error"));

        String result = tools.deleteChannelIntegration(RESOURCE_ID, 1, false);

        assertTrue(result.contains("error"));
    }

    // ==================== applyAgentChanges — edge cases ====================

    @Test
    void applyAgentChanges_blankAgentId_returnsError() {
        String result = tools.applyAgentChanges("  ", 1, "[{}]", false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void applyAgentChanges_nullMappings_returnsError() {
        String result = tools.applyAgentChanges(AGENT_ID, 1, null, false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceMappings is required"));
    }

    @Test
    void applyAgentChanges_blankMappings_returnsError() {
        String result = tools.applyAgentChanges(AGENT_ID, 1, "  ", false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceMappings is required"));
    }

    @Test
    void applyAgentChanges_redeployFails_includesError() throws IOException {
        // Set up Agent with 1 workflow that changes
        var agentConfig = new AgentConfiguration();
        var wfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
        agentConfig.setWorkflows(List.of(wfUri));
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        var ext = new WorkflowConfiguration.WorkflowStep();
        ext.setType(URI.create("eddi://ai.labs.llm"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.llm/llmstore/llms/lc1?version=1");
        ext.setConfig(configMap);
        var wfConfig = new WorkflowConfiguration();
        wfConfig.setWorkflowSteps(new ArrayList<>(List.of(ext)));
        when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

        when(workflowStore.updateWorkflow(eq("wf1"), eq(1), any()))
                .thenReturn(Response.ok().header("Location", "/workflowstore/workflows/wf1?version=2").build());
        when(agentStore.updateAgent(eq(AGENT_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location", "/agentstore/agents/" + AGENT_ID + "?version=2").build());

        // Redeploy fails
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy timeout"));

        String mappingsJson = "[{\"oldUri\":\"eddi://ai.labs.llm/llmstore/llms/lc1?version=1\",\"newUri\":\"eddi://ai.labs.llm/llmstore/llms/lc1?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://ai.labs.llm/llmstore/llms/lc1?version=1",
                "newUri", "eddi://ai.labs.llm/llmstore/llms/lc1?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"redeployed\":false}");

        String result = tools.applyAgentChanges(AGENT_ID, 1, mappingsJson, true, "production");

        // Should still return success with redeployed=false
        assertNotNull(result);
    }

    // ==================== createAgentTrigger — edge cases ====================

    @Test
    void createAgentTrigger_blankConfig_returnsError() {
        String result = tools.createAgentTrigger("  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void createAgentTrigger_missingIntentInConfig_returnsError() throws IOException {
        var config = new ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration();
        config.setIntent(null);
        when(jsonSerialization.deserialize(anyString(), eq(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class)))
                .thenReturn(config);

        String result = tools.createAgentTrigger("{\"agentDeployments\":[]}");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    @Test
    void createAgentTrigger_handlesException() throws IOException {
        when(jsonSerialization.deserialize(anyString(), eq(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class)))
                .thenThrow(new RuntimeException("parse error"));

        String result = tools.createAgentTrigger("{\"bad json\"}");

        assertTrue(result.contains("error"));
    }

    // ==================== updateAgentTrigger — edge cases ====================

    @Test
    void updateAgentTrigger_blankIntent_returnsError() {
        String result = tools.updateAgentTrigger("  ", "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    @Test
    void updateAgentTrigger_nullConfig_returnsError() {
        String result = tools.updateAgentTrigger("support", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateAgentTrigger_blankConfig_returnsError() {
        String result = tools.updateAgentTrigger("support", "  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateAgentTrigger_handlesException() throws IOException {
        when(jsonSerialization.deserialize(anyString(), eq(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class)))
                .thenThrow(new RuntimeException("update error"));

        String result = tools.updateAgentTrigger("support", "{}");

        assertTrue(result.contains("error"));
    }

    // ==================== deleteAgentTrigger — edge cases ====================

    @Test
    void deleteAgentTrigger_blankIntent_returnsError() {
        String result = tools.deleteAgentTrigger("  ");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    @Test
    void deleteAgentTrigger_handlesException() {
        when(agentTriggerStore.deleteAgentTrigger("support"))
                .thenThrow(new RuntimeException("delete error"));

        String result = tools.deleteAgentTrigger("support");

        assertTrue(result.contains("error"));
    }
}
