/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpAdminTools — MCP tools for Agent administration.
 */
class McpAdminToolsTest {

    private static final String AGENT_ID = "test-agent-id";

    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore AgentStore;
    private IRestWorkflowStore WorkflowStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private IJsonSerialization jsonSerialization;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        agentAdmin = mock(IRestAgentAdministration.class);
        AgentStore = mock(IRestAgentStore.class);
        WorkflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        jsonSerialization = mock(IJsonSerialization.class);

        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(AgentStore);
        when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(WorkflowStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);

        var scheduleStore = mock(IScheduleStore.class);
        var scheduleFireExecutor = mock(ScheduleFireExecutor.class);
        var schedulePollerService = mock(SchedulePollerService.class);

        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        var mockIdentity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        lenient().when(mockIdentity.isAnonymous()).thenReturn(true);
        tools = new McpAdminTools(restInterfaceFactory, agentAdmin, jsonSerialization, scheduleStore, scheduleFireExecutor, schedulePollerService,
                mockIdentity, false);
    }

    // --- deployAgent ---

    @Test
    void deployAgent_success() throws IOException {
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 1, true, true)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        String result = tools.deployAgent(AGENT_ID, 1, "production");

        assertNotNull(result);
        verify(agentAdmin).deployAgent(Environment.production, AGENT_ID, 1, true, true);
    }

    @Test
    void deployAgent_defaultsToProduction() throws IOException {
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 2, true, true)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        tools.deployAgent(AGENT_ID, 2, null);

        verify(agentAdmin).deployAgent(Environment.production, AGENT_ID, 2, true, true);
    }

    @Test
    void deployAgent_handlesException() {
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean())).thenThrow(new RuntimeException("Deploy failed"));

        String result = tools.deployAgent(AGENT_ID, 1, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Failed to deploy agent"));
    }

    // --- undeployAgent ---

    @Test
    void undeployAgent_success() throws IOException {
        when(agentAdmin.undeployAgent(Environment.production, AGENT_ID, 1, false, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        String result = tools.undeployAgent(AGENT_ID, 1, "production", false);

        assertNotNull(result);
        verify(agentAdmin).undeployAgent(Environment.production, AGENT_ID, 1, false, false);
    }

    @Test
    void undeployAgent_withEndConversations() throws IOException {
        when(agentAdmin.undeployAgent(Environment.production, AGENT_ID, 1, true, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        tools.undeployAgent(AGENT_ID, 1, null, true);

        verify(agentAdmin).undeployAgent(Environment.production, AGENT_ID, 1, true, false);
    }

    // --- getDeploymentStatus ---

    @Test
    void getDeploymentStatus_returnsStatus() throws IOException {
        var status = new AgentDeploymentStatus(Environment.production, AGENT_ID, 1, Status.READY, null);
        when(agentAdmin.getDeploymentStatus(Environment.production, AGENT_ID, 1, "json")).thenReturn(Response.ok(status).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"READY\"}");

        String result = tools.getDeploymentStatus(AGENT_ID, 1, "production");

        assertNotNull(result);
        verify(agentAdmin).getDeploymentStatus(Environment.production, AGENT_ID, 1, "json");
    }

    @Test
    void getDeploymentStatus_nullEntity_returnsFallback() throws IOException {
        when(agentAdmin.getDeploymentStatus(Environment.production, AGENT_ID, 1, "json")).thenReturn(Response.noContent().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"status_check\",\"httpStatus\":204}");

        String result = tools.getDeploymentStatus(AGENT_ID, 1, "production");

        assertNotNull(result);
        // Should not throw on null entity
    }

    // --- listWorkflows ---

    @Test
    void listWorkflows_returnsDescriptors() throws IOException {
        when(WorkflowStore.readWorkflowDescriptors("", 0, 20)).thenReturn(List.of(new DocumentDescriptor()));
        when(jsonSerialization.serialize(any())).thenReturn("[{\"name\":\"TestPkg\"}]");

        String result = tools.listWorkflows(null, null);

        assertNotNull(result);
        verify(WorkflowStore).readWorkflowDescriptors("", 0, 20);
    }

    @Test
    void listWorkflows_withFilterAndLimit() throws IOException {
        when(WorkflowStore.readWorkflowDescriptors("greetings", 0, 10)).thenReturn(Collections.emptyList());
        when(jsonSerialization.serialize(any())).thenReturn("[]");

        tools.listWorkflows("greetings", 10);

        verify(WorkflowStore).readWorkflowDescriptors("greetings", 0, 10);
    }

    // --- createAgent ---

    @Test
    void createAgent_createsAndPatchesDescriptor() throws IOException {
        when(AgentStore.createAgent(any(AgentConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/agentstore/agents/" + AGENT_ID + "?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\",\"agentId\":\"test-agent-id\",\"name\":\"My Agent\"}");

        String result = tools.createAgent("My Agent", "Test description", null);

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(AgentStore).createAgent(any(AgentConfiguration.class));

        // Verify descriptor was patched with name and description
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PatchInstruction<DocumentDescriptor>> patchCaptor = ArgumentCaptor.forClass(PatchInstruction.class);
        verify(descriptorStore).patchDescriptor(eq(AGENT_ID), eq(1), patchCaptor.capture());

        var patch = patchCaptor.getValue();
        assertEquals(PatchInstruction.PatchOperation.SET, patch.getOperation());
        assertEquals("My Agent", patch.getDocument().getName());
        assertEquals("Test description", patch.getDocument().getDescription());
    }

    @Test
    void createAgent_withWorkflowUris() throws IOException {
        when(AgentStore.createAgent(any(AgentConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/agentstore/agents/" + AGENT_ID + "?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        tools.createAgent("Agent", null, "eddi://ai.labs.workflow/workflowstore/workflows/pkg1?version=1");

        ArgumentCaptor<AgentConfiguration> configCaptor = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(AgentStore).createAgent(configCaptor.capture());
        assertEquals(1, configCaptor.getValue().getWorkflows().size());
    }

    @Test
    void createAgent_descriptorPatchFailure_stillReturnsSuccess() throws IOException {
        when(AgentStore.createAgent(any(AgentConfiguration.class)))
                .thenReturn(Response.created(java.net.URI.create("/agentstore/agents/" + AGENT_ID + "?version=1")).build());
        doThrow(new RuntimeException("Patch failed")).when(descriptorStore).patchDescriptor(any(), anyInt(), any());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createAgent("My Agent", null, null);

        // Agent creation should still be reported as successful
        assertNotNull(result);
        assertTrue(result.contains("created"));
    }

    // --- deleteAgent ---

    @Test
    void deleteAgent_success() throws IOException {
        when(AgentStore.deleteAgent(AGENT_ID, 1, false, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteAgent(AGENT_ID, 1, false, false);

        assertNotNull(result);
        verify(AgentStore).deleteAgent(AGENT_ID, 1, false, false);
    }

    @Test
    void deleteAgent_permanentCascade() throws IOException {
        when(AgentStore.deleteAgent(AGENT_ID, 2, true, true)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteAgent(AGENT_ID, 2, true, true);

        verify(AgentStore).deleteAgent(AGENT_ID, 2, true, true);
    }

    @Test
    void deleteAgent_handlesException() {
        when(AgentStore.deleteAgent(any(), anyInt(), anyBoolean(), anyBoolean())).thenThrow(new RuntimeException("Not found"));

        String result = tools.deleteAgent(AGENT_ID, 1, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Not found"));
    }

    // --- createAgent validation ---

    @Test
    void createAgent_blankName_returnsError() {
        String result = tools.createAgent(null, "desc", null);
        assertTrue(result.contains("error"));
    }

    @Test
    void createAgent_emptyName_returnsError() {
        String result = tools.createAgent("  ", "desc", null);
        assertTrue(result.contains("error"));
    }

    // --- updateAgent ---

    @Test
    void updateAgent_blankAgentId_returnsError() {
        String result = tools.updateAgent(null, 1, "name", null, null, null);
        assertTrue(result.contains("error"));
    }

    @Test
    void updateAgent_emptyAgentId_returnsError() {
        String result = tools.updateAgent("  ", 1, "name", null, null, null);
        assertTrue(result.contains("error"));
    }

    @Test
    void updateAgent_withNameAndDescription_patchesDescriptor() throws IOException {
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateAgent(AGENT_ID, 1, "New Name", "New Desc", null, null);

        assertNotNull(result);
        verify(descriptorStore).patchDescriptor(eq(AGENT_ID), eq(1), any());
    }

    @Test
    void updateAgent_withRedeploy_callsDeployAgent() throws IOException {
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        tools.updateAgent(AGENT_ID, 1, null, null, true, "production");

        verify(agentAdmin).deployAgent(any(), eq(AGENT_ID), eq(1), eq(true), eq(true));
    }

    @Test
    void updateAgent_redeployFails_stillReturnsResult() throws IOException {
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy failed"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\",\"redeployed\":false}");

        String result = tools.updateAgent(AGENT_ID, 1, null, null, true, null);

        assertNotNull(result);
    }

    @Test
    void updateAgent_handlesException() {
        doThrow(new RuntimeException("patch fail")).when(descriptorStore).patchDescriptor(any(), anyInt(), any());

        String result = tools.updateAgent(AGENT_ID, 1, "name", null, null, null);

        assertTrue(result.contains("error"));
    }

    // --- readWorkflow ---

    @Test
    void readWorkflow_blankId_returnsError() {
        String result = tools.readWorkflow(null, 1);
        assertTrue(result.contains("error"));
    }

    @Test
    void readWorkflow_emptyId_returnsError() {
        String result = tools.readWorkflow("  ", 1);
        assertTrue(result.contains("error"));
    }

    // --- readResource ---

    @Test
    void readResource_blankType_returnsError() {
        String result = tools.readResource(null, "id", 1);
        assertTrue(result.contains("error"));
    }

    @Test
    void readResource_blankId_returnsError() {
        String result = tools.readResource("behavior", null, 1);
        assertTrue(result.contains("error"));
    }

    @Test
    void readResource_emptyId_returnsError() {
        String result = tools.readResource("behavior", "  ", 1);
        assertTrue(result.contains("error"));
    }

    // --- updateResource ---

    @Test
    void updateResource_blankType_returnsError() {
        String result = tools.updateResource(null, "id", 1, "{}");
        assertTrue(result.contains("error"));
    }

    @Test
    void updateResource_blankId_returnsError() {
        String result = tools.updateResource("behavior", null, 1, "{}");
        assertTrue(result.contains("error"));
    }

    @Test
    void updateResource_blankConfig_returnsError() {
        String result = tools.updateResource("behavior", "id", 1, null);
        assertTrue(result.contains("error"));
    }

    // --- createResource ---

    @Test
    void createResource_blankType_returnsError() {
        String result = tools.createResource(null, "{}");
        assertTrue(result.contains("error"));
    }

    @Test
    void createResource_blankConfig_returnsError() {
        String result = tools.createResource("behavior", null);
        assertTrue(result.contains("error"));
    }

    // --- deleteResource ---

    @Test
    void deleteResource_blankType_returnsError() {
        String result = tools.deleteResource(null, "id", 1, false);
        assertTrue(result.contains("error"));
    }

    @Test
    void deleteResource_blankId_returnsError() {
        String result = tools.deleteResource("behavior", null, 1, false);
        assertTrue(result.contains("error"));
    }

    // --- applyAgentChanges ---

    @Test
    void applyAgentChanges_blankAgentId_returnsError() {
        String result = tools.applyAgentChanges(null, 1, "[{}]", false, null);
        assertTrue(result.contains("error"));
    }

    @Test
    void applyAgentChanges_blankMappings_returnsError() {
        String result = tools.applyAgentChanges(AGENT_ID, 1, null, false, null);
        assertTrue(result.contains("error"));
    }

    // --- listAgentResources ---

    @Test
    void listAgentResources_blankAgentId_returnsError() {
        String result = tools.listAgentResources(null, 1);
        assertTrue(result.contains("error"));
    }

    @Test
    void listAgentResources_emptyAgentId_returnsError() {
        String result = tools.listAgentResources("  ", 1);
        assertTrue(result.contains("error"));
    }

    // --- Agent Trigger CRUD validation ---

    @Test
    void createAgentTrigger_blankConfig_returnsError() {
        String result = tools.createAgentTrigger(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void createAgentTrigger_emptyConfig_returnsError() {
        String result = tools.createAgentTrigger("  ");
        assertTrue(result.contains("error"));
    }

    @Test
    void updateAgentTrigger_blankIntent_returnsError() {
        String result = tools.updateAgentTrigger(null, "{}");
        assertTrue(result.contains("error"));
    }

    @Test
    void updateAgentTrigger_blankConfig_returnsError() {
        String result = tools.updateAgentTrigger("intent1", null);
        assertTrue(result.contains("error"));
    }

    @Test
    void deleteAgentTrigger_blankIntent_returnsError() {
        String result = tools.deleteAgentTrigger(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void deleteAgentTrigger_emptyIntent_returnsError() {
        String result = tools.deleteAgentTrigger("  ");
        assertTrue(result.contains("error"));
    }

    // --- Schedule management validation ---

    @Test
    void deleteSchedule_blankId_returnsError() {
        String result = tools.deleteSchedule(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void deleteSchedule_emptyId_returnsError() {
        String result = tools.deleteSchedule("  ");
        assertTrue(result.contains("error"));
    }

    @Test
    void fireScheduleNow_blankId_returnsError() {
        String result = tools.fireScheduleNow(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void retryFailedSchedule_blankId_returnsError() {
        String result = tools.retryFailedSchedule(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void readSchedule_blankId_returnsError() {
        String result = tools.readSchedule(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void readSchedule_emptyId_returnsError() {
        String result = tools.readSchedule("  ");
        assertTrue(result.contains("error"));
    }

    // --- Channel integration validation ---

    @Test
    void readChannelIntegration_blankResourceId_returnsError() {
        String result = tools.readChannelIntegration(null, 1);
        assertTrue(result.contains("error"));
    }

    @Test
    void createChannelIntegration_blankConfig_returnsError() {
        String result = tools.createChannelIntegration(null);
        assertTrue(result.contains("error"));
    }

    @Test
    void updateChannelIntegration_blankResourceId_returnsError() {
        String result = tools.updateChannelIntegration(null, 1, "{}");
        assertTrue(result.contains("error"));
    }

    @Test
    void updateChannelIntegration_blankConfig_returnsError() {
        String result = tools.updateChannelIntegration("id", 1, null);
        assertTrue(result.contains("error"));
    }

    @Test
    void deleteChannelIntegration_blankResourceId_returnsError() {
        String result = tools.deleteChannelIntegration(null, 1, false);
        assertTrue(result.contains("error"));
    }

    // --- null defaults ---

    @Test
    void deployAgent_nullVersion_defaultsTo1() throws IOException {
        when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deployed\"}");

        tools.deployAgent(AGENT_ID, null, "production");

        verify(agentAdmin).deployAgent(any(), eq(AGENT_ID), eq(1), eq(true), eq(true));
    }

    @Test
    void undeployAgent_nullVersion_defaultsTo1() throws IOException {
        when(agentAdmin.undeployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"undeployed\"}");

        tools.undeployAgent(AGENT_ID, null, "production", null);

        verify(agentAdmin).undeployAgent(any(), eq(AGENT_ID), eq(1), eq(false), eq(false));
    }

    @Test
    void deleteAgent_nullBooleans_defaultToFalse() throws IOException {
        when(AgentStore.deleteAgent(AGENT_ID, 1, false, false)).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        tools.deleteAgent(AGENT_ID, 1, null, null);

        verify(AgentStore).deleteAgent(AGENT_ID, 1, false, false);
    }

    // --- createSchedule validation ---

    @Test
    void createSchedule_blankAgentId_returnsError() {
        String result = tools.createSchedule(null, null, null, null, null, "name", null, null, null, null);
        assertTrue(result.contains("error"));
    }

    @Test
    void createSchedule_blankName_returnsError() {
        String result = tools.createSchedule(AGENT_ID, null, null, null, null, null, null, null, null, null);
        assertTrue(result.contains("error"));
    }
}
