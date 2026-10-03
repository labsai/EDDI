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
import ai.labs.eddi.configs.deployment.mongo.DeploymentStore;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cluster mode, round robin: the first request after a deploy on node A often
 * reaches node B before B's sweep has deployed the agent, and B answered 404. B
 * now deploys on demand, bounded, when the store says the agent is deployed.
 */
@DisplayName("AgentDeploymentManagement — on-demand deployment in cluster mode")
class AgentDeploymentManagementClusterTest {

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() throws Exception {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
        management = new AgentDeploymentManagement(deploymentStore, agentFactory, mock(IAgentStore.class), mock(IAgentsReadiness.class),
                mock(IConversationMemoryStore.class), mock(IDocumentDescriptorStore.class), mock(IMigrationManager.class),
                mock(V6RenameMigration.class), mock(V6QuteMigration.class), mock(ChannelConnectorMigration.class),
                mock(WorkspaceAccessIndexMigration.class), runtime, mock(IWorkflowStore.class), mock(IRuleSetStore.class), 30);
        IAgent ready = mock(IAgent.class);
        when(ready.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
        when(agentFactory.getAgent(any(), any(), any())).thenReturn(ready);
    }

    private void clustered(boolean nats) {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(nats);
        management.clusterConfig = config;
    }

    private static DeploymentInfo deployed(String agentId) {
        var info = new DeploymentInfo();
        info.setEnvironment(Environment.production);
        info.setAgentId(agentId);
        info.setAgentVersion(1);
        return info;
    }

    @Test
    @DisplayName("an agent deployed on another node is deployed here and returned once ready")
    void deploysOnDemand() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        AtomicInteger lookups = new AtomicInteger();

        String found = management.awaitClusterDeployment(Environment.production, "agent1",
                () -> lookups.incrementAndGet() < 3 ? null : "agent1-ready", Duration.ofSeconds(5));

        assertEquals("agent1-ready", found);
        verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        assertTrue(lookups.get() >= 3);
    }

    @Test
    @DisplayName("an agent deployed nowhere answers at once, without deploying anything")
    void notDeployedAnywhere() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("other")));

        long start = System.nanoTime();
        assertNull(management.awaitClusterDeployment(Environment.production, "agent1", () -> "never", Duration.ofSeconds(5)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos(), "a real 404 must not wait");
        verify(agentFactory, never()).deployAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the wait is bounded")
    void boundedWait() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));

        long start = System.nanoTime();
        assertNull(management.awaitClusterDeployment(Environment.production, "agent1", () -> null, Duration.ofMillis(300)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
    }

    @Test
    @DisplayName("an unrecorded (autoDeploy=false) deploy on another node is deployed here too")
    void transientDeployFromAnotherNode() throws Exception {
        clustered(true);
        IRuntime runtime = mock(IRuntime.class);
        ExecutorService inline = mock(ExecutorService.class);
        when(inline.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
        when(runtime.getExecutorService()).thenReturn(inline);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
        management = new AgentDeploymentManagement(deploymentStore, agentFactory, mock(IAgentStore.class), mock(IAgentsReadiness.class),
                mock(IConversationMemoryStore.class), mock(IDocumentDescriptorStore.class), mock(IMigrationManager.class),
                mock(V6RenameMigration.class), mock(V6QuteMigration.class), mock(ChannelConnectorMigration.class),
                mock(WorkspaceAccessIndexMigration.class), runtime, mock(IWorkflowStore.class), mock(IRuleSetStore.class), 30);
        clustered(true);
        when(agentFactory.getAgent(Environment.production, "agent7", 3)).thenReturn(null);

        management.onRemoteDeploymentChange(new ClusterEvent(1, "e1", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", 0L, null,
                Map.of("env", "production", "agentId", "agent7", "version", 3, "status", DeploymentStore.TRANSIENT)));

        verify(agentFactory).deployAgent(Environment.production, "agent7", 3, null);
    }

    private void sweepAt(Instant at) {
        management.clock = Clock.fixed(at, ZoneOffset.UTC);
        management.checkDeployments();
    }

    @Test
    @DisplayName("an agent whose record went away (undeployed elsewhere) is undeployed here after two sweeps 5 s apart")
    void recordGoneIsUndeployed() throws Exception {
        clustered(true);
        IAgent served = mock(IAgent.class);
        when(served.getAgentId()).thenReturn("agent1");
        when(served.getAgentVersion()).thenReturn(1);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);

        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        sweepAt(t0.plusSeconds(10));
        verify(agentFactory, never()).undeployAgent(Environment.production, "agent1", 1);
        sweepAt(t0.plusSeconds(16));
        verify(agentFactory).undeployAgent(Environment.production, "agent1", 1);
    }

    @Test
    @DisplayName("an agent deployed without a record (autoDeploy=false) is never taken for one that lost it")
    void unrecordedDeploymentSurvivesTheSweep() throws Exception {
        clustered(true);
        IAgent served = mock(IAgent.class);
        when(served.getAgentId()).thenReturn("transient");
        when(served.getAgentVersion()).thenReturn(1);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);
        sweepAt(t0.plusSeconds(10));
        sweepAt(t0.plusSeconds(20));
        verify(agentFactory, never()).undeployAgent(any(), any(), any());
    }

    @Test
    @DisplayName("an unrecorded redeploy of a version that was recorded once, then undeployed, is not taken for a lost record")
    void unrecordedRedeployOfAPreviouslyRecordedVersionSurvives() throws Exception {
        clustered(true);
        IAgent served = mock(IAgent.class);
        when(served.getAgentId()).thenReturn("agent1");
        when(served.getAgentVersion()).thenReturn(1);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        // Recorded and deployed once ...
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);
        // ... undeployed (the record flips), then deployed again with autoDeploy=false.
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        management.noteUnrecordedDeployment(Environment.production, "agent1", 1);

        sweepAt(t0.plusSeconds(10));
        sweepAt(t0.plusSeconds(20));
        sweepAt(t0.plusSeconds(30));

        verify(agentFactory, never()).undeployAgent(any(), any(), any());
    }

    @Test
    @DisplayName("single node: nothing to wait for")
    void singleNode() throws Exception {
        clustered(false);
        assertNull(management.awaitClusterDeployment(Environment.production, "agent1", () -> "x", Duration.ofSeconds(5)));
        verify(deploymentStore, never()).readDeploymentInfos(any());
    }
}
