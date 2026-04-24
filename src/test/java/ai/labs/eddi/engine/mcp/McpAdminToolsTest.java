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
}
