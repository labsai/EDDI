/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.rest.RestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationService = mock(IConversationService.class);
        var deploymentStore = mock(IDeploymentStore.class);
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
        admin = new RestAgentAdministration(runtime, agentFactory, mock(IAgentStore.class), deploymentStore, conversationMemoryStore,
                restConversationStore, mock(IDocumentDescriptorStore.class), mock(IDeploymentListener.class), mock(IScheduleStore.class),
                mock(TenantQuotaService.class), mock(ResourceAccessGuard.class));
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
    }

    @Test
    @DisplayName("undeployThisAndAllPreviousAgentVersions carries on past a failing version and reports it")
    void continuesPastAFailingVersion() throws Exception {
        doThrow(new ServiceException("boom")).when(agentFactory).undeployAgent(ENV, AGENT, 2);

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
    @DisplayName("a single-version undeploy still fails on its first error")
    void singleVersionFailsAsBefore() throws Exception {
        doThrow(new ServiceException("boom")).when(agentFactory).undeployAgent(ENV, AGENT, 2);

        assertThrows(InternalServerErrorException.class, () -> admin.undeployAgent(ENV, AGENT, 2, false, false));
    }
}
