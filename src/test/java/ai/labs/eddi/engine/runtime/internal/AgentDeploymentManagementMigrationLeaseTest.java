/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup document migrations run under a cluster lease, so replicas that
 * start together do not each run them (the channel connector migration creates
 * a configuration per channel and would create it once per node).
 */
@DisplayName("AgentDeploymentManagement — document migrations under the cluster lease")
class AgentDeploymentManagementMigrationLeaseTest {

    private final IDeploymentStore deploymentStore = mock(IDeploymentStore.class);
    private final IMigrationManager migrationManager = mock(IMigrationManager.class);
    private final V6RenameMigration v6RenameMigration = mock(V6RenameMigration.class);
    private final V6QuteMigration v6QuteMigration = mock(V6QuteMigration.class);
    private final ChannelConnectorMigration channelConnectorMigration = mock(ChannelConnectorMigration.class);
    private final WorkspaceAccessIndexMigration workspaceAccessIndexMigration = mock(WorkspaceAccessIndexMigration.class);
    private final IConversationLeaseManager leaseManager = mock(IConversationLeaseManager.class);
    private final LeaseHandle lease = mock(LeaseHandle.class);
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() throws Exception {
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
        when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());
        doAnswer(inv -> {
            ((IMigrationManager.IMigrationFinished) inv.getArgument(0)).onComplete();
            return null;
        }).when(migrationManager).startMigrationIfFirstTimeRun(any());
        management = new AgentDeploymentManagement(deploymentStore, mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IAgentsReadiness.class), mock(IConversationMemoryStore.class), mock(IDocumentDescriptorStore.class),
                migrationManager, v6RenameMigration, v6QuteMigration, channelConnectorMigration, workspaceAccessIndexMigration,
                runtime, mock(IWorkflowStore.class), mock(IRuleSetStore.class), 30);
        management.leaseManager = leaseManager;
        management.documentMigrationsLeaseWait = Duration.ofSeconds(5);
    }

    @Test
    @DisplayName("the lease acquired: the migrations run while it is held, then it is released")
    void acquiredLeaseRunsTheMigrationsAndReleasesIt() {
        when(leaseManager.acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any()))
                .thenReturn(CompletableFuture.completedFuture(lease));

        management.autoDeployAgents();

        InOrder order = inOrder(leaseManager, v6QuteMigration, channelConnectorMigration, workspaceAccessIndexMigration);
        order.verify(leaseManager).acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any());
        order.verify(v6QuteMigration).runIfNeeded();
        order.verify(channelConnectorMigration).runIfNeeded();
        order.verify(workspaceAccessIndexMigration).runIfNeeded();
        order.verify(leaseManager).release(lease);
    }

    @Test
    @DisplayName("another node keeps the lease past the wait: this node skips the migrations and still deploys")
    void leaseHeldElsewhereSkipsTheMigrations() throws Exception {
        when(leaseManager.acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, "node-b", "held by node-b")));

        management.autoDeployAgents();

        verify(v6QuteMigration, never()).runIfNeeded();
        verify(channelConnectorMigration, never()).runIfNeeded();
        verify(workspaceAccessIndexMigration, never()).runIfNeeded();
        verify(leaseManager, never()).release(any());
        // Startup is not blocked: the legacy migration and the deployment sweep still
        // run.
        verify(migrationManager).startMigrationIfFirstTimeRun(any());
        verify(deploymentStore).readDeploymentInfos(deployed);
    }

    @Test
    @DisplayName("NATS unreachable under the reject policy: the migrations are skipped, not run unguarded")
    void degradedRejectSkipsTheMigrations() {
        when(leaseManager.acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new LeaseUnavailableException(LeaseUnavailableException.Reason.DEGRADED, null, "NATS is unreachable")));

        management.autoDeployAgents();

        verify(channelConnectorMigration, never()).runIfNeeded();
    }

    @Test
    @DisplayName("a lease that never answers is given up after the bounded wait")
    void unansweredLeaseIsAbandonedAfterTheWait() {
        management.documentMigrationsLeaseWait = Duration.ofMillis(10);
        var neverAnswered = new CompletableFuture<LeaseHandle>();
        when(leaseManager.acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any())).thenReturn(neverAnswered);

        management.autoDeployAgents();

        verify(channelConnectorMigration, never()).runIfNeeded();
        // Completed by the give-up, so a grant arriving later is handed straight back.
        assertTrue(neverAnswered.isDone());
    }

    @Test
    @DisplayName("a migration that throws past its guard still releases the lease")
    void failureInsideTheMigrationsReleasesTheLease() {
        when(leaseManager.acquireKey(eq(AgentDeploymentManagement.DOCUMENT_MIGRATIONS_LEASE), any()))
                .thenReturn(CompletableFuture.completedFuture(lease));
        doThrow(new Error("migration blew up")).when(workspaceAccessIndexMigration).runIfNeeded();

        try {
            management.autoDeployAgents();
        } catch (Error expected) {
            // autoDeployAgents' own finally runs; the lease must be released before it
        }

        verify(leaseManager).release(lease);
    }

    @Test
    @DisplayName("without a lease manager (plain construction) the migrations run as before")
    void noLeaseManagerRunsTheMigrations() {
        management.leaseManager = null;

        assertDoesNotThrow(() -> management.autoDeployAgents());

        verify(channelConnectorMigration).runIfNeeded();
    }
}
