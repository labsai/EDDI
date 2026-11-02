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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HitlAutonomyMaintenanceService")
class HitlAutonomyMaintenanceServiceTest {

    private IAuditSampleStore sampleStore;
    private IApprovalDecisionStore decisionStore;
    private IDeploymentStore deploymentStore;
    private ApprovalBackfillService backfill;
    private OversightMetrics metrics;
    private HitlAutonomyMaintenanceService service;

    @BeforeEach
    void setUp() throws Exception {
        sampleStore = mock(IAuditSampleStore.class);
        decisionStore = mock(IApprovalDecisionStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        backfill = mock(ApprovalBackfillService.class);
        metrics = mock(OversightMetrics.class);
        List<DeploymentInfo> infos = new ArrayList<>();
        for (String id : List.of("a", "b", "c", "d", "e")) {
            for (int v : List.of(1, 2)) {
                var info = new DeploymentInfo();
                info.setAgentId(id);
                info.setAgentVersion(v);
                infos.add(info);
            }
        }
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(infos);
        when(sampleStore.expireOverdue(any())).thenReturn(3L);
        when(backfill.rebuild(any(), any())).thenReturn(new ApprovalBackfillService.Result(0, 0, false));
        var settings = new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), true, Duration.ofDays(180), Duration.ofHours(1),
                Duration.ofSeconds(15), 2);
        service = new HitlAutonomyMaintenanceService(sampleStore, decisionStore, new InMemoryAutonomyStateStore(), deploymentStore,
                mock(OversightConfigLookup.class), mock(AutonomyService.class), backfill, settings, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("evaluates deployed agents round-robin, the newest deployed version each, at most max-agents-per-run")
    void roundRobin() {
        assertEquals(List.of(Map.entry("a", 2), Map.entry("b", 2)), service.nextAgents());
        assertEquals(List.of("c", "d"), service.nextAgents().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("e", "a"), service.nextAgents().stream().map(Map.Entry::getKey).toList(), "wraps around");
    }

    @Test
    @DisplayName("expires overdue samples, applies retention, and runs the first-start rebuild exactly once")
    void run() {
        var first = service.run();
        var second = service.run();

        assertEquals(3, first.expired());
        verify(metrics, times(2)).samplesExpired(3L);
        verify(decisionStore, times(2)).deleteDecidedBefore(NOW.minus(Duration.ofDays(400)));
        verify(sampleStore, times(2)).deleteSampledBefore(NOW.minus(Duration.ofDays(180)));
        assertTrue(first.backfilled());
        assertFalse(second.backfilled());
        verify(backfill, times(1)).rebuild(NOW.minus(Duration.ofDays(90)), NOW);
    }
}
