/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RestAgentAdministration} — covers the
 * deploy/undeploy Callable bodies, schedule lifecycle hooks, and error handling
 * paths.
 */
class RestAgentAdministrationExtendedTest {

    private IRuntime runtime;
    private IAgentFactory agentFactory;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IRestConversationStore restConversationStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IDeploymentListener deploymentListener;
    private IScheduleStore scheduleStore;
    private RestAgentAdministration admin;

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
        admin = new RestAgentAdministration(runtime, agentFactory, deploymentStore,
                conversationMemoryStore, restConversationStore, documentDescriptorStore,
                deploymentListener, scheduleStore);
    }

    /**
     * Helper: captures the Callable submitted to runtime and executes it, then
     * returns a completed future.
     */
    @SuppressWarnings("unchecked")
    private void captureAndExecuteCallable() throws Exception {
        var captor = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(captor.capture(), any());
        captor.getValue().call();
    }

    // ─── Deploy Callable body tests ─────────────────────────

    @Nested
    @DisplayName("Deploy callable body")
    class DeployCallableBody {

        @Test
        @DisplayName("deploys agent and fires READY event when NOT_FOUND")
        void deploysAndFiresEvent() throws Exception {
            // Setup: agent NOT_FOUND → deploy → set to READY
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of());

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, true, false);

            // Execute the captured callable
            captureAndExecuteCallable();

            verify(agentFactory).deployAgent(eq(Deployment.Environment.test), eq("agent-1"), eq(1), any());
            verify(deploymentListener).onDeploymentEvent(any(DeploymentEvent.class));
        }

        @Test
        @DisplayName("sets deployment info when autoDeploy=true and status becomes READY")
        void setsDeploymentInfoOnAutoDeploy() throws Exception {
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of());

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, true, false);

            // Capture and execute — we need to verify the status callback
            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            // Execute the callable — agentFactory.deployAgent will receive a
            // DeploymentProcess
            doAnswer(invocation -> {
                // Simulate the DeploymentProcess callback with READY
                var callback = invocation.getArgument(3, IAgentFactory.DeploymentProcess.class);
                callback.completed(Deployment.Status.READY);
                return null;
            }).when(agentFactory).deployAgent(any(), anyString(), anyInt(), any());

            captor.getValue().call();

            verify(deploymentStore).setDeploymentInfo(
                    eq("test"), eq("agent-1"), eq(1), eq(DeploymentInfo.DeploymentStatus.deployed));
        }

        @Test
        @DisplayName("skips deploy when agent already exists (not NOT_FOUND/ERROR)")
        void skipsDeployWhenAlreadyDeployed() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(agent);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of());

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, true, false);

            captureAndExecuteCallable();

            // Should NOT call deployAgent since status is READY
            verify(agentFactory, never()).deployAgent(any(), anyString(), anyInt(), any());
            // But should still fire the event
            verify(deploymentListener).onDeploymentEvent(any(DeploymentEvent.class));
        }

        @Test
        @DisplayName("handleDeploymentException fires ERROR event for ServiceException")
        void handlesServiceException() throws Exception {
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            doThrow(new ServiceException("Deploy failed"))
                    .when(agentFactory).deployAgent(any(), anyString(), anyInt(), any());
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            assertThrows(ServiceException.class, () -> captor.getValue().call());
            verify(deploymentListener).onDeploymentEvent(argThat(event -> event.status() == Deployment.Status.ERROR));
        }

        @Test
        @DisplayName("handleDeploymentException fires ERROR event for IllegalAccessException")
        void handlesIllegalAccessException() throws Exception {
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            doThrow(new IllegalAccessException("Deployment locked"))
                    .when(agentFactory).deployAgent(any(), anyString(), anyInt(), any());
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            assertThrows(WebApplicationException.class, () -> captor.getValue().call());
        }
    }

    // ─── Schedule lifecycle hooks ───────────────────────────

    @Nested
    @DisplayName("Schedule lifecycle hooks")
    class ScheduleHooks {

        @Test
        @DisplayName("enableSchedulesForAgent enables disabled schedules")
        void enablesDisabledSchedules() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-1");
            schedule.setName("Daily task");
            schedule.setEnabled(false);
            schedule.setNextFire(Instant.now());

            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of(schedule));
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            captureAndExecuteCallable();

            verify(scheduleStore).setScheduleEnabled(eq("sched-1"), eq(true), any(Instant.class));
        }

        @Test
        @DisplayName("enableSchedulesForAgent uses now() when nextFire is null")
        void usesNowWhenNextFireNull() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-2");
            schedule.setName("Weekly task");
            schedule.setEnabled(false);
            schedule.setNextFire(null);

            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of(schedule));
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            captureAndExecuteCallable();

            verify(scheduleStore).setScheduleEnabled(eq("sched-2"), eq(true), any(Instant.class));
        }

        @Test
        @DisplayName("enableSchedulesForAgent skips already-enabled schedules")
        void skipsAlreadyEnabled() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-3");
            schedule.setEnabled(true);

            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of(schedule));
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            captureAndExecuteCallable();

            verify(scheduleStore, never()).setScheduleEnabled(anyString(), anyBoolean(), any());
        }

        @Test
        @DisplayName("enableSchedulesForAgent handles exception gracefully")
        void enableHandlesException() throws Exception {
            when(agentFactory.getAgent(any(), eq("agent-1"), eq(1))).thenReturn(null);
            when(scheduleStore.readSchedulesByAgentId("agent-1"))
                    .thenThrow(new RuntimeException("Schedule DB error"));
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            admin.deployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            // Should not throw — non-fatal
            assertDoesNotThrow(() -> captureAndExecuteCallable());
        }
    }

    // ─── Undeploy Callable body ─────────────────────────────

    @Nested
    @DisplayName("Undeploy callable body")
    class UndeployCallableBody {

        @Test
        @DisplayName("undeploy sets deployment status to undeployed and disables schedules")
        void undeploysAndDisablesSchedules() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-1");
            schedule.setName("Task");
            schedule.setEnabled(true);
            when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of(schedule));

            admin.undeployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            captureAndExecuteCallable();

            verify(agentFactory).undeployAgent(Deployment.Environment.test, "agent-1", 1);
            verify(deploymentStore).setDeploymentInfo("test", "agent-1", 1,
                    DeploymentInfo.DeploymentStatus.undeployed);
            verify(scheduleStore).setScheduleEnabled("sched-1", false, null);
        }

        @Test
        @DisplayName("undeploy handles ServiceException")
        void handlesServiceException() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            doThrow(new ServiceException("Undeploy failed"))
                    .when(agentFactory).undeployAgent(any(), anyString(), anyInt());

            admin.undeployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            assertThrows(ServiceException.class, () -> captor.getValue().call());
        }

        @Test
        @DisplayName("undeploy handles IllegalAccessException with FORBIDDEN")
        void handlesIllegalAccessException() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            doThrow(new IllegalAccessException("Already in progress"))
                    .when(agentFactory).undeployAgent(any(), anyString(), anyInt());

            admin.undeployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            assertThrows(WebApplicationException.class, () -> captor.getValue().call());
        }

        @Test
        @DisplayName("undeploy handles generic exception with ISE")
        void handlesGenericException() throws Exception {
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);
            when(runtime.submitCallable(any(Callable.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            doThrow(new RuntimeException("Unexpected"))
                    .when(agentFactory).undeployAgent(any(), anyString(), anyInt());

            admin.undeployAgent(Deployment.Environment.test, "agent-1", 1, false, false);

            var captor = ArgumentCaptor.forClass(Callable.class);
            verify(runtime).submitCallable(captor.capture(), any());

            assertThrows(InternalServerErrorException.class, () -> captor.getValue().call());
        }
    }

    // ─── Deploy interrupted path ────────────────────────────

    @Nested
    @DisplayName("Deploy interrupted path")
    class DeployInterruptedPath {

        @Test
        @DisplayName("should include error when future is interrupted")
        void interruptedPath() throws Exception {
            var agent = mock(IAgent.class);
            when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.IN_PROGRESS);
            when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);

            @SuppressWarnings("unchecked")
            Future<Void> interruptedFuture = mock(Future.class);
            when(interruptedFuture.get(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new InterruptedException("interrupted"));
            when(runtime.submitCallable(any(Callable.class), any())).thenReturn(interruptedFuture);

            Response response = admin.deployAgent(
                    Deployment.Environment.test, "agent-1", 1, true, true);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) response.getEntity();
            assertEquals("Deployment was interrupted", body.get("error"));

            // Verify thread interrupt flag was set
            assertTrue(Thread.currentThread().isInterrupted());
            // Clear the interrupt flag
            Thread.interrupted();
        }
    }
}
