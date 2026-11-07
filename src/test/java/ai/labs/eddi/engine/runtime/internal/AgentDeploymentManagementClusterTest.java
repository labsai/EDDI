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
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    /** Runs what is submitted on the calling thread, unless a test re-stubs it. */
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        IRuntime runtime = mock(IRuntime.class);
        executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
        when(runtime.getExecutorService()).thenReturn(executor);
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

    private static IAgent registered(String agentId, int version, Deployment.Status status) {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentId()).thenReturn(agentId);
        when(agent.getAgentVersion()).thenReturn(version);
        when(agent.getDeploymentStatus()).thenReturn(status);
        return agent;
    }

    @Test
    @DisplayName("an on-demand deploy deploys the requested agent only, not every agent this node is missing")
    void onDemandDeploysOnlyTheRequestedAgent() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployed("other"), deployed("agent1")));
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of());

        assertEquals("agent1-ready",
                management.awaitClusterDeployment(Environment.production, "agent1", () -> "agent1-ready", Duration.ofSeconds(5)));

        verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        verify(agentFactory, never()).deployAgent(Environment.production, "other", 1, null);
    }

    @Test
    @DisplayName("an on-demand deploy is not held behind a sweep that is deploying other agents")
    void onDemandIsNotHeldBehindARunningSweep() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployed("slow"), deployed("agent1")));
        CountDownLatch sweepDeploying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            sweepDeploying.countDown();
            release.await();
            return null;
        }).when(agentFactory).deployAgent(Environment.production, "slow", 1, null);
        Thread sweep = new Thread(management::checkDeployments, "sweep");
        sweep.start();
        try {
            assertTrue(sweepDeploying.await(5, TimeUnit.SECONDS), "the sweep never started deploying");

            assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> management.awaitClusterDeployment(Environment.production, "agent1", () -> null, Duration.ofMillis(300)));
            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        } finally {
            release.countDown();
            sweep.join(5_000);
        }
    }

    @Test
    @DisplayName("a version whose deployment failed on this node is neither retried nor waited for: the sweep owns its retries")
    void aVersionThatFailedHereIsNotWaitedFor() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        IAgent failed = registered("agent1", 1, Deployment.Status.ERROR);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(failed));

        long start = System.nanoTime();
        assertNull(management.awaitClusterDeployment(Environment.production, "agent1", () -> null, Duration.ofSeconds(5)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos(), "a failed deployment must not cost the full wait");
        verify(agentFactory, never()).deployAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a burst of requests submits one on-demand deploy, not one per request")
    void aBurstSubmitsOneDeploy() throws Exception {
        clustered(true);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of());
        List<Runnable> queued = new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            queued.add(inv.getArgument(0));
            return null;
        });

        for (int i = 0; i < 3; i++) {
            management.awaitClusterDeployment(Environment.production, "agent1", () -> "agent1-ready", Duration.ofSeconds(5));
        }
        assertEquals(1, queued.size(), "the requests that arrived while the deploy was queued must not queue it again");

        queued.get(0).run();
        verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        management.awaitClusterDeployment(Environment.production, "agent1", () -> "agent1-ready", Duration.ofSeconds(5));
        assertEquals(2, queued.size(), "once the deploy has run, a request that still finds nothing deploys again");
    }

    @Test
    @DisplayName("a redeploy recorded on another node is deployed here after this node undeployed the version itself")
    void redeployRecordedElsewhereAfterALocalUndeploy() throws Exception {
        clustered(true);
        IAgent served = registered("agent1", 1, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);
        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent1", 1, null);

        // Undeployed by this node itself (REST, retirement, delete cascade): the
        // registry
        // loses the agent and the record flips, and no event reaches this node.
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of());
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        sweepAt(t0.plusSeconds(10));

        // Deployed again by a request that reached another node.
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        management.clock = Clock.fixed(t0.plusSeconds(20), ZoneOffset.UTC);
        management.onRemoteDeploymentChange(new ClusterEvent(1, "e3", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", 0L, null,
                Map.of("env", "production", "agentId", "agent1", "version", 1, "status", "deployed")));

        verify(agentFactory, times(2)).deployAgent(Environment.production, "agent1", 1, null);
    }

    @Test
    @DisplayName("a deployment the registry still holds READY stays tracked and is not deployed again")
    void aServedDeploymentStaysTracked() throws Exception {
        clustered(true);
        IAgent served = registered("agent1", 1, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");

        sweepAt(t0);
        sweepAt(t0.plusSeconds(10));
        sweepAt(t0.plusSeconds(20));

        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent1", 1, null);
    }

    @Test
    @DisplayName("a registry that cannot be read untracks nothing")
    void anUnreadableRegistryUntracksNothing() throws Exception {
        IAgent served = registered("agent1", 1, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);

        when(agentFactory.getAllDeployedAgents(Environment.production)).thenThrow(new ServiceException("registry unavailable"));
        sweepAt(t0.plusSeconds(10));

        verify(agentFactory, times(1)).deployAgent(Environment.production, "agent1", 1, null);
    }

    @Test
    @DisplayName("an undeploy event is applied at once, not after the sweep that is running")
    void anUndeployEventIsNotHeldBehindARunningSweep() throws Exception {
        clustered(true);
        IAgent served = registered("agent1", 1, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("slow")));
        CountDownLatch sweepDeploying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            sweepDeploying.countDown();
            release.await();
            return null;
        }).when(agentFactory).deployAgent(Environment.production, "slow", 1, null);
        Thread sweep = new Thread(management::checkDeployments, "sweep");
        sweep.start();
        try {
            assertTrue(sweepDeploying.await(5, TimeUnit.SECONDS), "the sweep never started deploying");

            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> management.onRemoteDeploymentChange(new ClusterEvent(1, "e4", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", 0L,
                            null, Map.of("env", "production", "agentId", "agent1", "version", 1, "status", "undeployed"))));
            verify(agentFactory).undeployAgent(Environment.production, "agent1", 1);
        } finally {
            release.countDown();
            sweep.join(5_000);
        }
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
    @Test
    @DisplayName("an unrecorded redeploy whose event overtook the undeploy event is not undeployed again by the sweep")
    void undeployForgetsTheRecordHistory() throws Exception {
        clustered(true);
        IAgent served = mock(IAgent.class);
        when(served.getAgentId()).thenReturn("agent1");
        when(served.getAgentVersion()).thenReturn(1);
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenReturn(List.of(served));
        // Recorded and deployed once ...
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("agent1")));
        Instant t0 = Instant.parse("2026-10-03T10:00:00Z");
        sweepAt(t0);
        // ... redeployed elsewhere with autoDeploy=false, whose event arrives first ...
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        management.noteUnrecordedDeployment(Environment.production, "agent1", 1);
        // ... then the undeploy event of the recorded version; the transient deploy
        // runs again after it.
        management.onRemoteDeploymentChange(new ClusterEvent(1, "e2", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", 0L, null,
                Map.of("env", "production", "agentId", "agent1", "version", 1, "status", "undeployed")));
        verify(agentFactory, times(1)).undeployAgent(Environment.production, "agent1", 1);

        sweepAt(t0.plusSeconds(10));
        sweepAt(t0.plusSeconds(20));
        sweepAt(t0.plusSeconds(30));

        verify(agentFactory, times(1)).undeployAgent(Environment.production, "agent1", 1);
    }
}
