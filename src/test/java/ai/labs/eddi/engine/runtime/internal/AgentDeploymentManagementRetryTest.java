/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.AgentsReadiness;
import ai.labs.eddi.engine.runtime.internal.readiness.AgentsReadinessHealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deployment that ends in ERROR is retried, and it is visible while it is.
 *
 * <p>
 * {@code deployAgent} reports a workflow that cannot be built by leaving the
 * agent in ERROR and returning normally. The sweep used to record such a
 * deployment as handled, so no later sweep looked at it again: on a first boot
 * against an EDDI 5 database every agent ended in ERROR and stayed there after
 * the cause was fixed under the running instance, while readiness reported UP
 * and nothing said anything was wrong.
 * </p>
 */
@DisplayName("AgentDeploymentManagement — a deployment in ERROR is retried and reported")
class AgentDeploymentManagementRetryTest {

    private static final Instant START = Instant.parse("2026-09-29T12:00:00Z");

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private AgentsReadiness readiness;
    private AgentDeploymentManagement management;
    private Instant now;
    private IAgent agent;
    private V6QuteMigration qute;
    private IMigrationManager migrationManager;

    @BeforeEach
    void setUp() throws Exception {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        readiness = new AgentsReadiness();
        var runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));

        qute = mock(V6QuteMigration.class);
        migrationManager = mock(IMigrationManager.class);
        management = new AgentDeploymentManagement(deploymentStore, agentFactory, mock(IAgentStore.class), readiness,
                mock(IConversationMemoryStore.class), mock(IDocumentDescriptorStore.class), migrationManager,
                mock(V6RenameMigration.class), qute, mock(ChannelConnectorMigration.class),
                mock(WorkspaceAccessIndexMigration.class), runtime, mock(IWorkflowStore.class), mock(IRuleSetStore.class), 30);
        now = START;
        management.clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };

        agent = mock(IAgent.class);
        when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(agent);
    }

    private void deployed(DeploymentInfo... infos) throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(infos));
    }

    private static DeploymentInfo info(String agentId, int version) {
        var info = new DeploymentInfo();
        info.setEnvironment(Environment.production);
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    private void sweepAt(Duration sinceStart) {
        now = START.plus(sinceStart);
        management.checkDeployments();
    }

    @Test
    @DisplayName("an agent in ERROR on the first attempt is retried after the backoff and ends READY without a restart")
    void errorThenReadyIsRetried() throws Exception {
        deployed(info("agent-a", 1));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR, Deployment.Status.READY);

        sweepAt(Duration.ZERO);
        assertEquals(List.of("production/agent-a/1"), readiness.getAgentsInError());

        sweepAt(Duration.ofSeconds(5));
        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent-a", 1, null);

        sweepAt(Duration.ofSeconds(10));
        verify(agentFactory, times(2)).deployAgent(Environment.production, "agent-a", 1, null);
        assertEquals(List.of(), readiness.getAgentsInError());

        sweepAt(Duration.ofSeconds(60));
        verify(agentFactory, times(2)).deployAgent(Environment.production, "agent-a", 1, null);
    }

    @Test
    @DisplayName("an agent that stays in ERROR is retried with a doubling delay, capped, not on every sweep")
    void persistentErrorBacksOff() throws Exception {
        deployed(info("agent-a", 1));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR);

        List<Long> attemptsAt = new ArrayList<>();
        int calls = 0;
        for (int second = 0; second <= 1800; second += 10) {
            sweepAt(Duration.ofSeconds(second));
            int after = mockingDetails(agentFactory).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("deployAgent")).toList().size();
            if (after > calls) {
                attemptsAt.add((long) second);
                calls = after;
            }
        }

        // 10 s, 20 s, 40 s, … doubling from the first failure, then every 5 minutes.
        assertEquals(List.of(0L, 10L, 30L, 70L, 150L, 310L, 610L, 910L, 1210L, 1510L), attemptsAt);
        assertEquals(List.of("production/agent-a/1"), readiness.getAgentsInError());
    }

    @Test
    @DisplayName("a failing deployment that is undeployed meanwhile is no longer reported")
    void undeployedFailureIsForgotten() throws Exception {
        deployed(info("agent-a", 1), info("agent-b", 1));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR);

        sweepAt(Duration.ZERO);
        assertEquals(List.of("production/agent-a/1", "production/agent-b/1"), readiness.getAgentsInError());

        deployed(info("agent-b", 1));
        sweepAt(Duration.ofSeconds(5));
        assertEquals(List.of("production/agent-b/1"), readiness.getAgentsInError());
    }

    @Test
    @DisplayName("an exception from deployAgent is retried with the same backoff")
    void exceptionIsRetried() throws Exception {
        deployed(info("agent-a", 1));
        doThrow(new IllegalStateException("store unavailable")).doNothing().when(agentFactory)
                .deployAgent(any(), anyString(), anyInt(), any());
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);

        sweepAt(Duration.ZERO);
        sweepAt(Duration.ofSeconds(5));
        verify(agentFactory, times(1)).deployAgent(any(), anyString(), anyInt(), any());
        sweepAt(Duration.ofSeconds(10));
        verify(agentFactory, times(2)).deployAgent(any(), anyString(), anyInt(), any());
        assertEquals(List.of(), readiness.getAgentsInError());
    }

    @Test
    @DisplayName("readiness stays UP with agents in ERROR, and says which ones")
    void readinessReportsAgentsInError() throws Exception {
        deployed(info("agent-a", 1), info("agent-b", 2));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.ERROR);
        readiness.setAgentsReadiness(true);

        sweepAt(Duration.ZERO);
        HealthCheckResponse response = new AgentsReadinessHealthCheck(readiness).call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertTrue(response.getData().isPresent());
        assertEquals(2L, response.getData().get().get("agentsInErrorCount"));
        assertFalse(response.getData().get().containsKey("agentsInError"),
                "/q/health is unauthenticated: the check reports how many agents are in ERROR, not which");
        assertEquals(List.of("production/agent-a/1", "production/agent-b/2"), readiness.getAgentsInError());
    }

    @Test
    @DisplayName("a deployment whose outcome is not READY yet is neither recorded as done nor reported as failed")
    void unconfirmedOutcomeIsRetriedSilently() throws Exception {
        deployed(info("agent-a", 1));
        when(agentFactory.getAgent(any(), anyString(), anyInt())).thenReturn(null, agent);
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);

        sweepAt(Duration.ZERO);
        assertEquals(List.of(), readiness.getAgentsInError());

        sweepAt(Duration.ofSeconds(1));
        verify(agentFactory, times(2)).deployAgent(Environment.production, "agent-a", 1, null);

        sweepAt(Duration.ofSeconds(2));
        verify(agentFactory, times(2)).deployAgent(Environment.production, "agent-a", 1, null);
    }

    /**
     * A scheduled tick that fires while the startup thread is still converting
     * templates must deploy nothing: an agent built then keeps its Thymeleaf
     * templates, is READY, and is never redeployed.
     */
    @Test
    @DisplayName("the sweep deploys nothing while the startup migrations after the rename are still running")
    void sweepWaitsForEveryStartupMigration() throws Exception {
        deployed(info("agent-a", 1));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
        List<Integer> deploysDuringQute = new ArrayList<>();
        doAnswer(invocation -> {
            management.checkDeployments(); // a scheduled tick landing mid-migration
            deploysDuringQute.add(mockingDetails(agentFactory).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("deployAgent")).toList().size());
            return null;
        }).when(qute).runIfNeeded();
        doAnswer(invocation -> {
            ((IMigrationManager.IMigrationFinished) invocation.getArgument(0)).onComplete();
            return null;
        }).when(migrationManager).startMigrationIfFirstTimeRun(any());

        management.autoDeployAgents();

        assertEquals(List.of(0), deploysDuringQute);
        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent-a", 1, null);
        assertTrue(readiness.isAgentsReady());
    }

    @Test
    @DisplayName("a startup migration that throws past its guard does not park the sweep for good")
    void sweepIsReleasedWhenStartupThrows() throws Exception {
        deployed(info("agent-a", 1));
        when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
        doThrow(new IllegalStateException("migration manager failed")).when(migrationManager).startMigrationIfFirstTimeRun(any());

        assertThrows(IllegalStateException.class, () -> management.autoDeployAgents());
        assertFalse(readiness.isAgentsReady());
        sweepAt(Duration.ZERO);

        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent-a", 1, null);
        assertTrue(readiness.isAgentsReady(), "the first completed sweep grants the readiness the startup path never reached");
    }

    @Test
    @DisplayName("readiness is DOWN before the startup deployment, whatever the agents' state")
    void readinessDownBeforeStartup() {
        HealthCheckResponse response = new AgentsReadinessHealthCheck(readiness).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(0L, response.getData().get().get("agentsInErrorCount"));
    }
}
