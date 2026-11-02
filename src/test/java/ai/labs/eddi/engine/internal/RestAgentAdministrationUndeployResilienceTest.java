/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.rest.RestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undeploy against the real {@link RestConversationStore}: the path that ends
 * every open conversation of a version must never load one in full, and a
 * multi-version undeploy must carry on past a version that fails.
 */
@DisplayName("RestAgentAdministration.undeployAgent — resilience")
class RestAgentAdministrationUndeployResilienceTest {

    private static final Deployment.Environment ENV = Deployment.Environment.production;
    private static final String AGENT = "a1a1a1a1a1a1a1a1a1a1a1a1";

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationService conversationService;
    private RestAgentAdministration admin;
    private IDocumentDescriptorStore agentDescriptors;
    private IDeploymentStore deploymentStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationService = mock(IConversationService.class);
        deploymentStore = mock(IDeploymentStore.class);
        var runtime = mock(IRuntime.class);
        var accessGuard = mock(ConversationAccessGuard.class);
        when(accessGuard.callerActor(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var attachments = mock(Instance.class);
        when(attachments.isResolvable()).thenReturn(false);
        when(runtime.submitCallable(any(Callable.class), any())).thenAnswer(invocation -> {
            ((Callable<Void>) invocation.getArgument(0)).call();
            return CompletableFuture.completedFuture(null);
        });
        when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of());

        var restConversationStore = new RestConversationStore(mock(IDocumentDescriptorStore.class), conversationDescriptorStore,
                conversationMemoryStore, conversationService, mock(IUserMemoryStore.class), runtime, accessGuard,
                mock(ResourceAccessGuard.class), 365, 90, attachments);
        agentDescriptors = mock(IDocumentDescriptorStore.class);
        var currentId = mock(IResourceStore.IResourceId.class);
        when(currentId.getVersion()).thenReturn(3);
        when(agentDescriptors.getCurrentResourceId(AGENT)).thenReturn(currentId);
        admin = new RestAgentAdministration(runtime, agentFactory, mock(IAgentStore.class), deploymentStore, conversationMemoryStore,
                restConversationStore, agentDescriptors, mock(IDeploymentListener.class), mock(IScheduleStore.class),
                mock(TenantQuotaService.class), mock(ResourceAccessGuard.class));
    }

    @Test
    @DisplayName("a bulk undeploy of an agent whose documents are gone is a 404 only when the deployment records were actually read")
    void unreadableDeploymentRecordsAreNotNotFound() throws Exception {
        when(agentDescriptors.getCurrentResourceId(AGENT)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        // Records readable and empty: nothing is deployed, so 404.
        Response none = admin.undeployAgent(ENV, AGENT, 2, false, true);
        assertEquals(404, none.getStatus());

        // Records unreadable: "none found" is not what the lookup said.
        when(deploymentStore.readDeploymentInfos(any())).thenThrow(new IResourceStore.ResourceStoreException("db down"));
        assertThrows(InternalServerErrorException.class, () -> admin.undeployAgent(ENV, AGENT, 2, false, true));
    }

    @Test
    @DisplayName("a version above the highest one a PARTIAL lookup found is the lookup's failure (500), above a complete one it is a 400")
    void versionAboveAPartialCeilingIsNotABadRequest() throws Exception {
        when(agentDescriptors.getCurrentResourceId(AGENT)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        var running = mock(IAgent.class);
        when(running.getAgentId()).thenReturn(AGENT);
        when(running.getAgentVersion()).thenReturn(2);
        when(running.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of(running));

        // Both lookups answered: the highest deployed version is 2, so 5 does not
        // exist.
        assertEquals(400, admin.undeployAgent(ENV, AGENT, 5, false, true).getStatus());

        // The deployment records could not be read: version 5 may exist, so it is not a
        // 400.
        when(deploymentStore.readDeploymentInfos(any())).thenThrow(new IResourceStore.ResourceStoreException("db down"));
        assertThrows(InternalServerErrorException.class, () -> admin.undeployAgent(ENV, AGENT, 5, false, true));
    }

    private void openConversation(int version, String conversationId) throws Exception {
        when(conversationMemoryStore.getActiveConversationCount(AGENT, version)).thenReturn(1L);
        when(conversationMemoryStore.loadOpenConversationActivity(eq(AGENT), eq(version), isNull(), anyInt()))
                .thenReturn(List.of(new ConversationActivitySummary(conversationId, ConversationState.READY, AGENT, version, null)));
        var descriptor = new ConversationDescriptor();
        descriptor.setLastModifiedOn(new Date());
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT + "?version=" + version));
        when(conversationDescriptorStore.readDescriptor(conversationId, 0)).thenReturn(descriptor);
        when(conversationMemoryStore.getConversationState(conversationId)).thenReturn(ConversationState.READY);
    }

    @Test
    @DisplayName("undeploy with endAllActiveConversations ends the conversations without loading any in full")
    void endAllNeverLoadsAConversationInFull() throws Exception {
        String conversationId = "c1c1c1c1c1c1c1c1c1c1c1c1";
        openConversation(1, conversationId);

        Response response = admin.undeployAgent(ENV, AGENT, 1, true, false);

        assertEquals(202, response.getStatus());
        verify(conversationService).endConversation(eq(conversationId), anyString(), eq(IConversationService.END_REASON_AGENT_VERSION_RETIRED));
        verify(agentFactory).undeployAgent(ENV, AGENT, 1);
        verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(any(), any());
        verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(any());
        // Descriptor reads: the access check and the ENDED mark of ending it — not a
        // third
        // one for a last-interaction time nothing here uses.
        verify(conversationDescriptorStore, times(2)).readDescriptor(conversationId, 0);
    }

    @Test
    @DisplayName("undeployThisAndAllPreviousAgentVersions carries on past a failing version and reports it")
    void continuesPastAFailingVersion() throws Exception {
        doThrow(new IllegalStateException("boom")).when(agentFactory).undeployAgent(ENV, AGENT, 2);

        var failure = assertThrows(InternalServerErrorException.class, () -> admin.undeployAgent(ENV, AGENT, 3, false, true));

        // The versions on either side of the failing one were still taken out of
        // service.
        verify(agentFactory).undeployAgent(ENV, AGENT, 3);
        verify(agentFactory).undeployAgent(ENV, AGENT, 2);
        verify(agentFactory).undeployAgent(ENV, AGENT, 1);
        assertTrue(failure.getMessage().contains("version 2"), "the failure names the version that failed: " + failure.getMessage());
    }

    @Test
    @DisplayName("a version refused for its open conversations does not stop the lower versions; the call answers 409")
    void refusalDoesNotStopTheLowerVersions() throws Exception {
        openConversation(2, "c2c2c2c2c2c2c2c2c2c2c2c2");

        Response response = admin.undeployAgent(ENV, AGENT, 3, false, true);

        assertEquals(409, response.getStatus());
        verify(agentFactory).undeployAgent(ENV, AGENT, 3);
        verify(agentFactory, never()).undeployAgent(ENV, AGENT, 2);
        verify(agentFactory).undeployAgent(ENV, AGENT, 1);
    }

    @Test
    @DisplayName("undeployThisAndAllPreviousAgentVersions above the agent's current version is refused before any work")
    void bulkUndeployBeyondCurrentVersionIsRefused() throws Exception {
        Response response = admin.undeployAgent(ENV, AGENT, Integer.MAX_VALUE, false, true);

        assertEquals(400, response.getStatus());
        verify(agentFactory, never()).undeployAgent(any(), anyString(), anyInt());
        verify(conversationMemoryStore, never()).getActiveConversationCount(anyString(), anyInt());
    }

    @Test
    @DisplayName("undeployThisAndAllPreviousAgentVersions of an agent with no documents and nothing deployed is a 404")
    void bulkUndeployOfUnknownAgentIsNotFound() throws Exception {
        when(agentDescriptors.getCurrentResourceId(AGENT)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        Response response = admin.undeployAgent(ENV, AGENT, 5, false, true);

        assertEquals(404, response.getStatus());
        verify(agentFactory, never()).undeployAgent(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a single-version undeploy still fails on its first error")
    void singleVersionFailsAsBefore() throws Exception {
        doThrow(new IllegalStateException("boom")).when(agentFactory).undeployAgent(ENV, AGENT, 2);

        assertThrows(InternalServerErrorException.class, () -> admin.undeployAgent(ENV, AGENT, 2, false, false));
    }
}
