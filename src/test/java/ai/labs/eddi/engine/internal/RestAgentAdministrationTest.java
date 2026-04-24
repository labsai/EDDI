/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentAdministration}.
 */
class RestAgentAdministrationTest {

    private IRuntime runtime;
    private IAgentFactory agentFactory;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IRestConversationStore restConversationStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IDeploymentListener deploymentListener;
    private IScheduleStore scheduleStore;
    private RestAgentAdministration restAgentAdmin;

    @BeforeEach
    void setUp() {
        runtime = mock(IRuntime.class);
        agentFactory = mock(IAgentFactory.class);
        deploymentStore = mock(IDeploymentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        restConversationStore = mock(IRestConversationStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        deploymentListener = mock(IDeploymentListener.class);
        scheduleStore = mock(IScheduleStore.class);
        restAgentAdmin = new RestAgentAdministration(runtime, agentFactory, deploymentStore,
                conversationMemoryStore, restConversationStore, documentDescriptorStore,
                deploymentListener, scheduleStore);
    }

    @Nested
    @DisplayName("deployAgent")
    class DeployAgent {

        @Test
        @DisplayName("should return 202 Accepted when not waiting")
        void returns202WhenNotWaiting() throws Exception {
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(null);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Response response = restAgentAdmin.deployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, false);

            assertEquals(202, response.getStatus());
        }

        @Test
        @DisplayName("should return 200 with status when waiting for completion")
        void returns200WhenWaiting() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Response response = restAgentAdmin.deployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, true);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("READY", body.get("status"));
        }

        @Test
        @DisplayName("should throw InternalServerError on exception")
        void throwsOnException() throws Exception {
            when(agentFactory.getAgent(any(), anyString(), anyInt()))
                    .thenThrow(new ServiceException("DB error"));
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentAdmin.deployAgent(
                            Deployment.Environment.test, "agent-1", 1, true, true));
        }
    }

    @Nested
    @DisplayName("undeployAgent")
    class UndeployAgent {

        @Test
        @DisplayName("should return 202 when no active conversations")
        void returns202WhenNoActiveConversations() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1))
                    .thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Response response = restAgentAdmin.undeployAgent(
                    Deployment.Environment.test, "agent-1", 1, false, false);

            assertEquals(202, response.getStatus());
        }

        @Test
        @DisplayName("should return 409 Conflict when active conversations exist and not forcing")
        void returns409WithActiveConversations() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1))
                    .thenReturn(5L);

            Response response = restAgentAdmin.undeployAgent(
                    Deployment.Environment.test, "agent-1", 1, false, false);

            assertEquals(409, response.getStatus());
        }

        @Test
        @DisplayName("should end conversations and undeploy when force=true")
        void endsConversationsWhenForced() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1))
                    .thenReturn(3L)
                    .thenReturn(0L); // After ending
            when(restConversationStore.getActiveConversations("agent-1", 1))
                    .thenReturn(List.of());
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Response response = restAgentAdmin.undeployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, false);

            assertEquals(202, response.getStatus());
            verify(restConversationStore).endActiveConversations(any());
        }
    }

    @Nested
    @DisplayName("getDeploymentStatus")
    class GetDeploymentStatus {

        @Test
        @DisplayName("should return JSON status by default")
        void returnsJsonStatus() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);

            Response response = restAgentAdmin.getDeploymentStatus(
                    Deployment.Environment.test, "agent-1", 1, null);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return text/plain when format=text")
        void returnsTextStatus() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);

            Response response = restAgentAdmin.getDeploymentStatus(
                    Deployment.Environment.test, "agent-1", 1, "text");

            assertEquals(200, response.getStatus());
            assertEquals("READY", response.getEntity());
        }

        @Test
        @DisplayName("should return NOT_FOUND when agent is null")
        void returnsNotFoundForNullAgent() throws Exception {
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(null);

            Response response = restAgentAdmin.getDeploymentStatus(
                    Deployment.Environment.test, "agent-1", 1, "text");

            assertEquals(200, response.getStatus());
            assertEquals("NOT_FOUND", response.getEntity());
        }
    }

    @Nested
    @DisplayName("getDeploymentStatuses")
    class GetDeploymentStatuses {

        @Test
        @DisplayName("should return empty list when no agents deployed")
        void emptyList() throws Exception {
            when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of());

            var result = restAgentAdmin.getDeploymentStatuses(Deployment.Environment.test);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return sorted list of deployed agents")
        void returnsSortedList() throws Exception {
            var agent1 = mock(IAgent.class);
            when(agent1.getAgentId()).thenReturn("agent-1");
            when(agent1.getAgentVersion()).thenReturn(1);
            when(agent1.getDeploymentStatus()).thenReturn(Deployment.Status.READY);

            var desc1 = new ai.labs.eddi.configs.descriptors.model.DocumentDescriptor();
            desc1.setLastModifiedOn(new java.util.Date(1000));
            when(documentDescriptorStore.readDescriptor("agent-1", 1)).thenReturn(desc1);

            when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of(agent1));

            var result = restAgentAdmin.getDeploymentStatuses(Deployment.Environment.test);

            assertEquals(1, result.size());
            assertEquals("agent-1", result.get(0).getAgentId());
        }

        @Test
        @DisplayName("should throw ISE on ServiceException")
        void throwsOnServiceException() throws Exception {
            when(agentFactory.getAllLatestAgents(any()))
                    .thenThrow(new ServiceException("DB fail"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentAdmin.getDeploymentStatuses(Deployment.Environment.test));
        }
    }

    // --- Deploy wait paths ---

    @Nested
    @DisplayName("deployAgent wait paths")
    class DeployAgentWait {

        @Test
        @DisplayName("should include error when future times out")
        void timeoutPath() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.IN_PROGRESS);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);

            // Use a mock Future that throws TimeoutException from get() —
            // CompletableFuture wraps exceptions in ExecutionException, which
            // would hit the wrong catch block
            @SuppressWarnings("unchecked")
            Future<Void> timeoutFuture = mock(Future.class);
            when(timeoutFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new TimeoutException("timed out"));
            when(runtime.submitCallable(any(Callable.class), any())).thenReturn(timeoutFuture);

            Response response = restAgentAdmin.deployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, true);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertNotNull(body.get("status"));
            assertEquals("Deployment timed out", body.get("error"));
        }

        @Test
        @DisplayName("should include error when future fails with ExecutionException")
        void executionExceptionPath() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);

            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("Deploy failed"));
            when(runtime.submitCallable(any(Callable.class), any())).thenReturn(failed);

            Response response = restAgentAdmin.deployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, true);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ERROR", body.get("status"));
            assertNotNull(body.get("error"));
        }
    }

    // --- Undeploy cascading ---

    @Nested
    @DisplayName("undeployAgent cascading versions")
    class UndeployAgentCascading {

        @Test
        @DisplayName("should undeploy multiple versions when cascading")
        void undeploysMultipleVersions() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount(anyString(), anyInt()))
                    .thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Response response = restAgentAdmin.undeployAgent(
                    Deployment.Environment.test, "agent-1", 3, false, true);

            assertEquals(202, response.getStatus());
            // Should submit 3 undeploy calls (versions 3, 2, 1)
            verify(runtime, times(3)).submitCallable(any(Callable.class), any());
        }

        @Test
        @DisplayName("should throw ISE on generic exception")
        void throwsOnException() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("DB crash"));

            assertThrows(InternalServerErrorException.class,
                    () -> restAgentAdmin.undeployAgent(
                            Deployment.Environment.test, "agent-1", 1, false, false));
        }
    }
}
