/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.hitl.oversight.InMemoryAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalBackfillService;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HitlAutonomyMaintenanceService branches")
class HitlAutonomyMaintenanceBranchesTest {

    private IAuditSampleStore sampleStore;
    private IApprovalDecisionStore decisionStore;
    private IDeploymentStore deploymentStore;
    private ApprovalBackfillService backfill;
    private OversightMetrics metrics;
    private OversightConfigLookup configLookup;
    private AutonomyService autonomy;
    private InMemoryAutonomyStateStore stateStore;

    @BeforeEach
    void setUp() {
        sampleStore = mock(IAuditSampleStore.class);
        decisionStore = mock(IApprovalDecisionStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        backfill = mock(ApprovalBackfillService.class);
        metrics = mock(OversightMetrics.class);
        configLookup = mock(OversightConfigLookup.class);
        autonomy = mock(AutonomyService.class);
        stateStore = new InMemoryAutonomyStateStore();
    }

    private HitlAutonomyMaintenanceService service(Duration backfillSpan, int maxAgents, IAutonomyStateStore store) {
        var settings = new OversightSettings(Duration.ofDays(400), backfillSpan, true, Duration.ofDays(180), Duration.ofHours(1),
                Duration.ofSeconds(15), maxAgents);
        return new HitlAutonomyMaintenanceService(sampleStore, decisionStore, store, deploymentStore, configLookup, autonomy, backfill, settings,
                metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DeploymentInfo info(String agentId, Integer version) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    @Test
    @DisplayName("deployments without an agent id or version are skipped, and an unreadable deployment store yields no agents")
    void nextAgentsFilters() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(info(null, 1), info("a", null), info("b", 1), info("b", 3), info("b", 2)));
        var service = service(Duration.ofDays(90), 10, stateStore);

        assertEquals(List.of(Map.entry("b", 3)), service.nextAgents());

        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenThrow(new IllegalStateException("db down"));
        assertTrue(service.nextAgents().isEmpty());
    }

    @Test
    @DisplayName("a limit of zero still evaluates one agent per run, and an empty deployment list resets the cursor")
    void minimumOne() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(info("a", 1), info("b", 1)));
        var service = service(Duration.ofDays(90), 0, stateStore);

        assertEquals("a", service.nextAgents().get(0).getKey());
        assertEquals("b", service.nextAgents().get(0).getKey());
        assertEquals("a", service.nextAgents().get(0).getKey());

        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        assertTrue(service.nextAgents().isEmpty());
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(info("a", 1), info("b", 1)));
        assertEquals("a", service.nextAgents().get(0).getKey(), "starting over after an empty run");
    }

    @Test
    @DisplayName("a failing evaluation is skipped and not counted; the deployed version wins when it is newer than the stored one")
    void evaluationFailures() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(info("a", 5), info("b", 1)));
        when(configLookup.currentVersion("a")).thenReturn(2);
        when(configLookup.currentVersion("b")).thenReturn(1);
        when(autonomy.evaluateAgent(eq("a"), eq(5), any())).thenReturn(2);
        when(autonomy.evaluateAgent(eq("b"), eq(1), any())).thenThrow(new IllegalStateException("boom"));

        var result = service(Duration.ofDays(90), 10, stateStore).run();

        assertEquals(1, result.agentsEvaluated());
        assertEquals(2, result.recommendationsOpened());
        verify(autonomy).evaluateAgent(eq("a"), eq(5), any());
    }

    @Test
    @DisplayName("the first-start rebuild is off for a missing, zero or negative span")
    void backfillDisabled() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        for (Duration span : new Duration[]{null, Duration.ZERO, Duration.ofDays(-1)}) {
            assertFalse(service(span, 10, stateStore).run().backfilled());
        }
        verify(backfill, never()).rebuild(any(), any());
        assertTrue(stateStore.read(AutonomyState.SYSTEM_AGENT_ID, HitlAutonomyMaintenanceService.BACKFILL_MARKER).isEmpty());
    }

    @Test
    @DisplayName("a marker already present, a lost claim, or a rebuild that is already running mean no rebuild now")
    void backfillSkipped() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());

        stateStore.save(AutonomyState.initial(AutonomyState.SYSTEM_AGENT_ID, HitlAutonomyMaintenanceService.BACKFILL_MARKER), 0);
        assertFalse(service(Duration.ofDays(90), 10, stateStore).run().backfilled(), "marker present");

        IAutonomyStateStore lostClaim = mock(IAutonomyStateStore.class);
        when(lostClaim.read(any(), any())).thenReturn(Optional.empty());
        when(lostClaim.save(any(), anyLong())).thenReturn(Optional.empty());
        assertFalse(service(Duration.ofDays(90), 10, lostClaim).run().backfilled(), "another node claimed it first");

        when(backfill.rebuild(any(), any())).thenReturn(null);
        assertFalse(service(Duration.ofDays(90), 10, new InMemoryAutonomyStateStore()).run().backfilled(), "a rebuild is already running");
        verify(backfill, times(1)).rebuild(any(), any());
    }

    @Test
    @DisplayName("a result is empty only when nothing was expired, opened, deleted or rebuilt")
    void resultIsEmpty() {
        assertTrue(new HitlAutonomyMaintenanceService.Result(0, 5, 0, 0, 0, false).isEmpty(), "evaluating alone is not a change");
        assertFalse(new HitlAutonomyMaintenanceService.Result(1, 0, 0, 0, 0, false).isEmpty());
        assertFalse(new HitlAutonomyMaintenanceService.Result(0, 0, 1, 0, 0, false).isEmpty());
        assertFalse(new HitlAutonomyMaintenanceService.Result(0, 0, 0, 1, 0, false).isEmpty());
        assertFalse(new HitlAutonomyMaintenanceService.Result(0, 0, 0, 0, 1, false).isEmpty());
        assertFalse(new HitlAutonomyMaintenanceService.Result(0, 0, 0, 0, 0, true).isEmpty());
    }
}
