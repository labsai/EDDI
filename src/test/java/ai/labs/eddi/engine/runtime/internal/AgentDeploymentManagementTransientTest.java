/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.mongo.DeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.DownSharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.TransientDeployments;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1: deployments made with {@code autoDeploy=false} are recorded cluster-wide
 * ({@link TransientDeployments}), reconciled by the sweep, and an event older
 * than the record is dropped.
 */
@DisplayName("AgentDeploymentManagement — autoDeploy=false deployments in cluster mode")
class AgentDeploymentManagementTransientTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private AgentDeploymentManagement management;
    private TransientDeployments transients;
    private final AtomicLong kvClock = new AtomicLong(T0.toEpochMilli());
    /** What this node serves, as the agent factory reports it. */
    private final List<IAgent> served = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
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
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(true);
        management.clusterConfig = config;
        transients = new TransientDeployments(new InMemorySharedKv("DEPLOYMENTS", Duration.ofMinutes(10), kvClock::get),
                Duration.ofMinutes(10), "n1");
        management.transientDeployments = transients;
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        when(agentFactory.getAllDeployedAgents(Environment.production)).thenAnswer(inv -> List.copyOf(served));
        when(agentFactory.getAllDeployedAgents(Environment.test)).thenReturn(List.of());
        when(agentFactory.getAgent(any(), any(), any())).thenAnswer(inv -> served.stream()
                .filter(a -> a.getAgentId().equals(inv.getArgument(1)) && a.getAgentVersion().equals(inv.getArgument(2))).findFirst()
                .orElse(null));
        doAnswer(inv -> {
            served.add(agent(inv.getArgument(1), inv.getArgument(2)));
            return null;
        }).when(agentFactory).deployAgent(any(), any(), any(), any());
        doAnswer(inv -> {
            served.removeIf(a -> a.getAgentId().equals(inv.getArgument(1)) && a.getAgentVersion().equals(inv.getArgument(2)));
            return null;
        }).when(agentFactory).undeployAgent(any(), any(), any());
    }

    private static IAgent agent(String id, Integer version) {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentId()).thenReturn(id);
        when(agent.getAgentVersion()).thenReturn(version);
        return agent;
    }

    private void sweepAt(Instant at) {
        kvClock.set(at.toEpochMilli());
        management.clock = Clock.fixed(at, ZoneOffset.UTC);
        management.checkDeployments();
    }

    private static ClusterEvent event(String status, int version) {
        return new ClusterEvent(1, "e", ClusterEvent.DEPLOYMENT_CHANGED, "n2", "b2", 0L, null,
                Map.of("env", "production", "agentId", "agent7", "version", version, "status", status));
    }

    @Test
    @DisplayName("a node that joins later deploys an autoDeploy=false agent from the shared record")
    void lateJoinerDeploysFromTheRecord() {
        transients.markDeployed("production", "agent7", 3);

        sweepAt(T0);

        verify(agentFactory).deployAgent(Environment.production, "agent7", 3, null);
    }

    @Test
    @DisplayName("a lost undeploy event is applied by the sweep from the tombstone")
    void lostUndeployIsReconciled() {
        transients.markDeployed("production", "agent7", 3);
        sweepAt(T0);
        assertEquals(1, served.size());

        // Undeployed on another node; its event never arrives here.
        transients.markUndeployed("production", "agent7", 3);
        sweepAt(T0.plusSeconds(10));

        verify(agentFactory).undeployAgent(Environment.production, "agent7", 3);
        assertTrue(served.isEmpty());
    }

    @Test
    @DisplayName("a transient deploy event that arrives after the undeploy (outbox reorder) is dropped")
    void staleTransientDeployEventIsDropped() {
        transients.markDeployed("production", "agent7", 3);
        transients.markUndeployed("production", "agent7", 3);

        management.onRemoteDeploymentChange(event(DeploymentStore.TRANSIENT, 3));

        verify(agentFactory, never()).deployAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an undeploy event that arrives after a redeploy (outbox reorder) is dropped")
    void staleUndeployEventIsDropped() {
        transients.markDeployed("production", "agent7", 3);
        management.onRemoteDeploymentChange(event(DeploymentStore.TRANSIENT, 3));
        assertEquals(1, served.size());

        // The undeploy happened first, then the redeploy; the undeploy event arrives
        // last.
        management.onRemoteDeploymentChange(event("undeployed", 3));

        verify(agentFactory, never()).undeployAgent(any(), any(), any());
        assertEquals(1, served.size());
    }

    @Test
    @DisplayName("an undeploy event that is current is applied at once")
    void currentUndeployIsApplied() {
        transients.markDeployed("production", "agent7", 3);
        management.onRemoteDeploymentChange(event(DeploymentStore.TRANSIENT, 3));
        transients.markUndeployed("production", "agent7", 3);

        management.onRemoteDeploymentChange(event("undeployed", 3));

        verify(agentFactory).undeployAgent(Environment.production, "agent7", 3);
    }

    @Test
    @DisplayName("a recorded redeploy makes an older undeploy event stale too")
    void recordedRedeployMakesUndeployStale() throws Exception {
        served.add(agent("agent7", 3));
        DeploymentInfo record = new DeploymentInfo();
        record.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        when(deploymentStore.getDeploymentInfo(eq("production"), eq("agent7"), eq(3))).thenReturn(record);

        management.onRemoteDeploymentChange(event("undeployed", 3));

        verify(agentFactory, never()).undeployAgent(any(), any(), any());
    }

    @Test
    @DisplayName("a served deployment keeps its record alive, and a refresh never overwrites a tombstone")
    void servedRecordIsRefreshed() {
        transients.markDeployed("production", "agent7", 3);
        sweepAt(T0);
        // Past a third of the TTL: the sweep rewrites the record.
        sweepAt(T0.plus(Duration.ofMinutes(4)));
        sweepAt(T0.plus(Duration.ofMinutes(8)));
        sweepAt(T0.plus(Duration.ofMinutes(12)));
        assertTrue(transients.read("production", "agent7", 3).isPresent(), "a served record must not expire");

        transients.markUndeployed("production", "agent7", 3);
        sweepAt(T0.plus(Duration.ofMinutes(20)));
        assertEquals(TransientDeployments.State.UNDEPLOYED, transients.read("production", "agent7", 3).orElseThrow().state());
    }

    @Test
    @DisplayName("an unreadable bucket undeploys nothing")
    void unreadableBucketChangesNothing() {
        served.add(agent("agent7", 3));
        management.noteUnrecordedDeployment(Environment.production, "agent7", 3);
        management.transientDeployments = new TransientDeployments(new DownSharedKv(), Duration.ofMinutes(10),
                "n1");

        sweepAt(T0);
        sweepAt(T0.plusSeconds(10));
        sweepAt(T0.plusSeconds(20));

        verify(agentFactory, never()).undeployAgent(any(), any(), any());
    }
}
