/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Log injection (CWE-117) regression tests for {@link RestAgentAdministration}.
 *
 * <p>
 * {@code agentId} is a path parameter of every endpoint here, and the schedule
 * name and id the lifecycle hooks quote are author-supplied strings out of the
 * schedule store. A CR/LF in any of them closes the real record and lets the
 * remainder read as a second line the server wrote itself — for instance a
 * forged {@code [SCHEDULE] Auto-enabled} line for an agent nobody deployed.
 * </p>
 *
 * <p>
 * These assert the CONTRACT, not the call: drive a forged record through the
 * real path and require that nothing carrying a record boundary reached the
 * log. Removing any {@code sanitize(...)} on the pinned line fails them.
 * </p>
 *
 * <p>
 * Where a line lives inside the runtime Callable, the endpoint call itself
 * stays OUTSIDE the capture window and only the captured Callable runs inside
 * it, so each test can only be satisfied by the line it names.
 * </p>
 */
@DisplayName("RestAgentAdministration — log injection (CWE-117)")
class RestAgentAdministrationLogInjectionTest {

    private static final Deployment.Environment ENV = Deployment.Environment.test;

    /** The Agent id as an attacker supplies it on the deploy/undeploy path. */
    private static final String POISONED_AGENT_ID = "aabbccddeeff112233445566" + FORGED_RECORD;

    private IRuntime runtime;
    private IAgentFactory agentFactory;
    private IScheduleStore scheduleStore;
    private RestAgentAdministration admin;

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        agentFactory = mock(IAgentFactory.class);
        scheduleStore = mock(IScheduleStore.class);
        var conversationMemoryStore = mock(IConversationMemoryStore.class);
        var deploymentStore = mock(IDeploymentStore.class);
        var tenantQuotaService = mock(TenantQuotaService.class);

        // The quota gate sits ahead of the deploy on the request thread; keep it
        // transparent so these tests reach the lines they are about.
        lenient().when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());
        lenient().when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of());
        lenient().when(tenantQuotaService.checkAgentQuota(any(), anyInt())).thenReturn(QuotaCheckResult.OK);
        // NOT_FOUND, so the deploy Callable actually asks the factory to deploy.
        lenient().when(agentFactory.getAgent(any(), eq(POISONED_AGENT_ID), eq(1))).thenReturn(null);
        lenient().when(conversationMemoryStore.getActiveConversationCount(POISONED_AGENT_ID, 1)).thenReturn(0L);

        admin = new RestAgentAdministration(runtime, agentFactory, mock(IAgentStore.class), deploymentStore,
                conversationMemoryStore, mock(IRestConversationStore.class), mock(IDocumentDescriptorStore.class),
                mock(IDeploymentListener.class), scheduleStore, tenantQuotaService, mock(ResourceAccessGuard.class));
    }

    /**
     * A deploy future whose timed {@code get} fails the way the endpoint sees it.
     */
    @SuppressWarnings("unchecked")
    private Future<Object> deployFutureFailingWith(Exception e) throws Exception {
        Future<Object> deployFuture = mock(Future.class);
        when(deployFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(e);
        when(runtime.submitCallable(any(Callable.class), any())).thenReturn(deployFuture);
        return deployFuture;
    }

    /**
     * Hands the runtime a completed future, so the submitted Callable is ours to
     * run.
     */
    @SuppressWarnings("unchecked")
    private void runtimeAcceptsCallables() {
        when(runtime.submitCallable(any(Callable.class), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    /** The Callable the endpoint submitted, to be run inside the capture window. */
    @SuppressWarnings("unchecked")
    private Callable<Object> submittedCallable() {
        var captor = ArgumentCaptor.forClass(Callable.class);
        verify(runtime).submitCallable(captor.capture(), any());
        return captor.getValue();
    }

    private static ScheduleConfiguration poisonedSchedule(boolean enabled) {
        var schedule = new ScheduleConfiguration();
        schedule.setId("sched-1" + FORGED_RECORD);
        schedule.setName("Nightly report" + FORGED_RECORD);
        schedule.setEnabled(enabled);
        schedule.setNextFire(Instant.now());
        return schedule;
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the deploy-wait timeout WARN")
    void deployWaitTimesOut() throws Exception {
        deployFutureFailingWith(new TimeoutException("still deploying"));

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(() -> admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, true)));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Deployment wait timed out")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.deployAgent's wait-timeout WARN");
    }

    @Test
    @DisplayName("a CR/LF Agent id or failure cause cannot forge a record through the deploy-failed WARN")
    void deployFails() throws Exception {
        // The cause's message is quoted into the line as well, and an agent's own
        // configuration can put a CR/LF there without touching the path.
        deployFutureFailingWith(new ExecutionException(new IllegalStateException("workflow broken" + FORGED_RECORD)));

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(() -> admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, true)));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Deployment failed for Agent")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.deployAgent's deploy-failed WARN");
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the successful-undeploy INFO")
    void undeploySucceeds() {
        runtimeAcceptsCallables();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(() -> admin.undeployAgent(ENV, POISONED_AGENT_ID, 1, false, false)));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Successfully undeployed Agent")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.undeployAgent's success INFO");
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the deployment-error ERROR")
    void deploymentFailsWithServiceException() throws Exception {
        runtimeAcceptsCallables();
        doThrow(new ServiceException("store down")).when(agentFactory)
                .deployAgent(any(), eq(POISONED_AGENT_ID), eq(1), any());

        admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var deployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertThrows(ServiceException.class, deployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Error while deploying agent!")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.throwError's deployment ERROR");
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the deployment-in-progress ERROR")
    void deploymentIsAlreadyInProgress() throws Exception {
        runtimeAcceptsCallables();
        doThrow(new IllegalAccessException("already deploying")).when(agentFactory)
                .deployAgent(any(), eq(POISONED_AGENT_ID), eq(1), any());

        admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var deployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertThrows(WebApplicationException.class, deployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Agent deployment is currently in progress!")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.throwErrorForbidden's in-progress ERROR");
    }

    @Test
    @DisplayName("a CR/LF Agent id, schedule name or schedule id cannot forge a record through the auto-enable INFO")
    void schedulesAutoEnabledOnDeploy() throws Exception {
        runtimeAcceptsCallables();
        when(scheduleStore.readSchedulesByAgentId(POISONED_AGENT_ID)).thenReturn(List.of(poisonedSchedule(false)));

        admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var deployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(deployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Auto-enabled schedule")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.enableSchedulesForAgent's INFO");
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the auto-enable failure WARN")
    void schedulesFailToAutoEnable() throws Exception {
        runtimeAcceptsCallables();
        // A benign message on purpose: this line passes the cause as the record's
        // throwable, not as a parameter, so only the Agent id is under test here.
        when(scheduleStore.readSchedulesByAgentId(POISONED_AGENT_ID))
                .thenThrow(new IllegalStateException("scheduler unreachable"));

        admin.deployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var deployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(deployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Failed to auto-enable schedules for Agent")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.enableSchedulesForAgent's failure WARN");
    }

    @Test
    @DisplayName("a CR/LF Agent id, schedule name or schedule id cannot forge a record through the auto-disable INFO")
    void schedulesAutoDisabledOnUndeploy() throws Exception {
        runtimeAcceptsCallables();
        when(scheduleStore.readSchedulesByAgentId(POISONED_AGENT_ID)).thenReturn(List.of(poisonedSchedule(true)));

        admin.undeployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var undeployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(undeployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Auto-disabled schedule")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.disableSchedulesForAgent's INFO");
    }

    @Test
    @DisplayName("a CR/LF Agent id cannot forge a record through the auto-disable failure WARN")
    void schedulesFailToAutoDisable() throws Exception {
        runtimeAcceptsCallables();
        when(scheduleStore.readSchedulesByAgentId(POISONED_AGENT_ID))
                .thenThrow(new IllegalStateException("scheduler unreachable"));

        admin.undeployAgent(ENV, POISONED_AGENT_ID, 1, false, false);
        var undeployBody = submittedCallable();

        List<String> captured = captureLogsOf(RestAgentAdministration.class,
                () -> assertDoesNotThrow(undeployBody::call));

        assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
        assertTrue(captured.stream().anyMatch(value -> value.contains("Failed to auto-disable schedules for Agent")),
                "the line under test did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "RestAgentAdministration.disableSchedulesForAgent's failure WARN");
    }
}
