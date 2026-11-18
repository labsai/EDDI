/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rest.ResourceInUseGuard;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an agent with open conversations, through the real
 * {@link ResourceInUseGuard} the container injects (M6 of the 2026-11-18
 * review): nothing irreversible happens before every check that can still
 * refuse the delete has passed.
 */
@DisplayName("RestAgentStore — delete with open conversations")
class RestAgentStoreDeleteGuardTest {

    private static final String AGENT_ID = "aabbccddee1122334455";

    private IAgentStore agentStore;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore conversations;
    private IRestConversationStore restConversations;
    private RestAgentStore store;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        conversations = mock(IConversationMemoryStore.class);
        restConversations = mock(IRestConversationStore.class);
        var accessGuard = mock(ResourceAccessGuard.class);
        var guard = new ResourceInUseGuard(agentStore, mock(IWorkflowStore.class), deploymentStore, mock(IDocumentDescriptorStore.class),
                conversations, restConversations, accessGuard);
        var agentFactory = mock(IAgentFactory.class);
        when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of());
        store = new RestAgentStore(agentStore, mock(IRestWorkflowStore.class), mock(IDocumentDescriptorStore.class),
                mock(IJsonSchemaCreator.class), mock(IScheduleStore.class), mock(CapabilityRegistryService.class), deploymentStore,
                accessGuard, mock(AgentSigningService.class), agentFactory, "default", mock(AgentCascadeService.class), guard);

        // Version 1 is deployed and has an active conversation.
        var deployed = new DeploymentInfo();
        deployed.setAgentId(AGENT_ID);
        deployed.setAgentVersion(1);
        deployed.setEnvironment(Deployment.Environment.production);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed));
        when(conversations.getActiveConversationCount(AGENT_ID, 1)).thenReturn(1L);
        when(conversations.loadOpenConversationActivity(AGENT_ID, 1, null, 500))
                .thenReturn(List.of(new ConversationActivitySummary("c1", ConversationState.READY, AGENT_ID, 1, null)));
        when(conversations.loadOpenConversationActivity(AGENT_ID, 1, "c1", 500)).thenReturn(List.of());
        when(restConversations.endActiveConversations(any(), anyString())).thenReturn(Response.ok().build());
    }

    private static IResourceStore.IResourceId version(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return AGENT_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    @Test
    @DisplayName("a stale version is refused with 409 before a single conversation is ended (fails with the old ordering)")
    void staleVersionEndsNothing() throws Exception {
        // The agent moved on to v2; v1 is still the deployed one.
        when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(version(2));

        var refused = assertThrows(WebApplicationException.class, () -> store.deleteAgent(AGENT_ID, 1, false, false, true));

        assertEquals(409, refused.getResponse().getStatus());
        verify(restConversations, never()).endActiveConversations(any(), anyString());
        verify(agentStore, never()).delete(anyString(), anyInt());
    }

    @Test
    @DisplayName("without endAllActiveConversations: 409, nothing ended, nothing deleted")
    void refusedWithoutOverride() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(version(1));

        var refused = assertThrows(WebApplicationException.class, () -> store.deleteAgent(AGENT_ID, 1, false, false));

        assertEquals(409, refused.getResponse().getStatus());
        verify(restConversations, never()).endActiveConversations(any(), anyString());
        verify(agentStore, never()).delete(anyString(), anyInt());
    }

    @Test
    @DisplayName("with endAllActiveConversations on the live version: the conversations end, then the agent is deleted")
    void endsThenDeletes() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(version(1));

        assertEquals(200, store.deleteAgent(AGENT_ID, 1, false, false, true).getStatus());

        InOrder order = inOrder(restConversations, agentStore);
        order.verify(restConversations).endActiveConversations(any(), eq(IConversationService.END_REASON_AGENT_DELETED));
        order.verify(agentStore).delete(AGENT_ID, 1);
    }
}
