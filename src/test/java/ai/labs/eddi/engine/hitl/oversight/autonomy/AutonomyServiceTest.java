/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.engine.hitl.oversight.InMemoryAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightLedger;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.PATTERN;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decisions;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.gatedHitl;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AutonomyService")
class AutonomyServiceTest {

    private InMemoryAutonomyStateStore stateStore;
    private IApprovalDecisionStore decisionStore;
    private IAuditSampleStore sampleStore;
    private OversightConfigLookup configLookup;
    private IAgentStore agentStore;
    private IRestAgentStore restAgentStore;
    private AutonomyOverlayService overlay;
    private OversightLedger ledger;
    private OversightMetrics metrics;
    private AutonomyService service;
    private AgentConfiguration.HitlConfig hitl;
    private final List<ApprovalDecision> evidence = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new InMemoryAutonomyStateStore();
        decisionStore = mock(IApprovalDecisionStore.class);
        sampleStore = mock(IAuditSampleStore.class);
        configLookup = mock(OversightConfigLookup.class);
        agentStore = mock(IAgentStore.class);
        restAgentStore = mock(IRestAgentStore.class);
        overlay = mock(AutonomyOverlayService.class);
        ledger = mock(OversightLedger.class);
        metrics = mock(OversightMetrics.class);
        service = new AutonomyService(stateStore, decisionStore, sampleStore, configLookup, agentStore, restAgentStore, overlay, ledger,
                metrics, Clock.fixed(NOW, ZoneOffset.UTC));

        var policy = new AutonomyPolicyConfig.CategoryPolicy();
        policy.setDemoteOnReasonCodes(List.of("FALSE_CLAIM"));
        hitl = gatedHitl(policy);
        when(configLookup.hitlConfig(eq(AGENT), any())).thenAnswer(inv -> hitl);
        when(configLookup.agent(eq(AGENT), any())).thenAnswer(inv -> agent());
        when(configLookup.currentVersion(AGENT)).thenReturn(3);
        when(agentStore.read(AGENT, 3)).thenAnswer(inv -> agent());
        when(decisionStore.query(any(), anyInt(), anyInt())).thenAnswer(inv -> List.copyOf(evidence));
        when(restAgentStore.updateAgent(eq(AGENT), eq(3), any(), eq(false))).thenReturn(Response.ok().build());
    }

    private AgentConfiguration agent() {
        var agent = new AgentConfiguration();
        agent.setHitlConfig(hitl);
        return agent;
    }

    private AutonomyState open() {
        evidence.addAll(decisions(30, 0, "APPROVED"));
        assertTrue(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));
        return stateStore.read(AGENT, CATEGORY).orElseThrow();
    }

    private static int status(WebApplicationException e) {
        return e.getResponse().getStatus();
    }

    private static String code(WebApplicationException e) {
        return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("error"));
    }

    @Nested
    @DisplayName("evaluation")
    class Evaluation {

        @Test
        @DisplayName("enough evidence opens one PROMOTE recommendation with a valid preview; a second run does not open another")
        void opensOnce() {
            AutonomyState state = open();

            Recommendation r = state.recommendation();
            assertEquals(Recommendation.Kind.PROMOTE, r.kind());
            assertEquals(Recommendation.Status.OPEN, r.status());
            assertEquals(PATTERN, r.pattern());
            assertTrue(r.applicable());
            assertEquals(3, r.basedOnAgentVersion());
            assertNotNull(r.proposedHitlConfig());
            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY), "an open recommendation is not re-opened");
            verify(metrics, times(1)).recommendation("PROMOTE");
        }

        @Test
        @DisplayName("a newer agent version makes an open recommendation STALE")
        void staleOnNewVersion() {
            open();

            service.evaluateCategory(AGENT, 4, hitl, CATEGORY);

            assertEquals(Recommendation.Status.OPEN, stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation().status(),
                    "re-opened against v4 after marking the v3 one stale");
            assertEquals(4, stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation().basedOnAgentVersion());
        }

        @Test
        @DisplayName("a suspended category is not evaluated")
        void suspendedSkipped() {
            stateStore.save(AutonomyState.initial(AGENT, CATEGORY)
                    .withSuspension(new Suspension(true, PATTERN, NOW, Suspension.Trigger.MANUAL, null, null, "bob", null, null, "x")), 0);
            evidence.addAll(decisions(30, 0, "APPROVED"));

            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("writes the change through the agent store, records the promotion and resets the evidence")
        void writesThroughAgentStore() throws Exception {
            AutonomyState state = open();
            when(configLookup.currentVersion(AGENT)).thenReturn(3, 4);

            AutonomyState applied = service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol");

            var written = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(restAgentStore).updateAgent(eq(AGENT), eq(3), written.capture(), eq(false));
            assertTrue(written.getValue().getHitlConfig().getToolApprovals().getExempt().contains(PATTERN));
            assertEquals(Recommendation.Status.APPLIED, applied.recommendation().status());
            assertEquals("carol", applied.recommendation().appliedBy());
            assertEquals(4, applied.recommendation().resultingAgentVersion());
            assertEquals(NOW, applied.evidenceResetAt());
            verify(ledger).write(eq(OversightLedger.PROMOTION), isNull(), eq(AGENT), eq(4), eq("carol"), anyMap());
            verify(metrics).promotionApplied();
        }

        @Test
        @DisplayName("re-checks the evidence now: 409 EVIDENCE_CHANGED when it no longer holds")
        void evidenceRechecked() {
            AutonomyState state = open();
            evidence.add(decision("REJECTED", NOW.minusSeconds(5), "dave", DeciderKind.HUMAN_VERIFIED, "MANAGER", "FALSE_CLAIM"));

            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));

            assertEquals(409, status(e));
            assertEquals("EVIDENCE_CHANGED", code(e));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("409 AGENT_CHANGED when the agent moved past the version the human reviewed")
        void staleAgentVersion() throws Exception {
            AutonomyState state = open();
            when(configLookup.currentVersion(AGENT)).thenReturn(4);

            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));

            assertEquals("AGENT_CHANGED", code(e));
        }

        @Test
        @DisplayName("a revert is refused while a broader exempt entry still covers the pattern, and the suspension stays")
        void revertRefusedWhileCovered() throws Exception {
            AutonomyState suspended = service.onSampleFlagged(sample("s1").reviewed(AuditSample.Status.FLAGGED, "FALSE_CLAIM", null, "erin", NOW))
                    .orElseThrow();
            hitl.getToolApprovals().setExempt(List.of("http.post:/crm/*"));

            var e = assertThrows(WebApplicationException.class,
                    () -> service.apply(AGENT, CATEGORY, suspended.recommendation().id(), 3, "carol"));

            assertEquals("NOT_APPLICABLE", code(e));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), any());
            assertTrue(stateStore.read(AGENT, CATEGORY).orElseThrow().suspended());
        }

        @Test
        @DisplayName("409 RECOMMENDATION_CHANGED for an id that is not the open recommendation")
        void unknownRecommendation() {
            open();
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, "other", 3, "carol"));
            assertEquals("RECOMMENDATION_CHANGED", code(e));
        }
    }

    @Nested
    @DisplayName("demotion")
    class Demotion {

        private AuditSample flagged(String id, String reasonCode) {
            return sample(id).reviewed(AuditSample.Status.FLAGGED, reasonCode, null, "erin", NOW);
        }

        @Test
        @DisplayName("a flagged sample with a demote reason code suspends at once, idempotently, with a REVERT recommendation")
        void demoteCodeSuspends() {
            var first = service.onSampleFlagged(flagged("s1", "FALSE_CLAIM"));
            service.onSampleFlagged(flagged("s2", "FALSE_CLAIM"));

            AutonomyState state = first.orElseThrow();
            assertTrue(state.suspended());
            assertEquals(PATTERN, state.suspension().pattern());
            assertEquals(Suspension.Trigger.REASON_CODE, state.suspension().trigger());
            assertEquals(Recommendation.Kind.REVERT, state.recommendation().kind());
            assertEquals(NOW, state.evidenceResetAt());
            verify(metrics, times(1)).demotion("REASON_CODE");
            verify(ledger, times(1)).write(eq(OversightLedger.DEMOTION), any(), eq(AGENT), any(), anyString(), anyMap());
            verify(overlay).invalidate(AGENT);
        }

        @Test
        @DisplayName("demoteOnFlaggedSamples=2 needs two flagged samples in the window")
        void thresholdOfTwo() {
            var policy = new AutonomyPolicyConfig.CategoryPolicy();
            policy.setDemoteOnFlaggedSamples(2);
            hitl = gatedHitl(policy);
            when(sampleStore.count(any())).thenReturn(1L, 2L);

            assertTrue(service.onSampleFlagged(flagged("s1", "TONE")).isEmpty());
            assertTrue(service.onSampleFlagged(flagged("s2", "TONE")).orElseThrow().suspended());
        }

        @Test
        @DisplayName("a category the policy does not name is never suspended automatically")
        void unnamedCategory() {
            var oversight = new OversightConfig();
            hitl.setOversight(oversight);
            assertTrue(service.onSampleFlagged(flagged("s1", "FALSE_CLAIM")).isEmpty());
        }

        @Test
        @DisplayName("a rejection with a demote code resets the evidence and makes an open promotion stale")
        void rejectionResets() {
            open();
            var rejection = decision("REJECTED", NOW.minusSeconds(30), "dave", DeciderKind.HUMAN_VERIFIED, "MANAGER", "FALSE_CLAIM");

            service.onDecisions(List.of(rejection));

            AutonomyState state = stateStore.read(AGENT, CATEGORY).orElseThrow();
            assertEquals(rejection.decidedAt(), state.evidenceResetAt());
            assertEquals(Recommendation.Status.STALE, state.recommendation().status());
        }
    }

    @Nested
    @DisplayName("lift, dismiss, manual suspend")
    class HumanActions {

        @Test
        @DisplayName("lift requires a note and ungates; it is audited")
        void liftNeedsNote() {
            service.suspendManually(AGENT, CATEGORY, "looked wrong", "bob");

            var e = assertThrows(WebApplicationException.class, () -> service.lift(AGENT, CATEGORY, " ", "bob"));
            assertEquals("NOTE_REQUIRED", code(e));

            AutonomyState lifted = service.lift(AGENT, CATEGORY, "checked, fine", "bob");
            assertFalse(lifted.suspended());
            assertEquals("bob", lifted.suspension().liftedBy());
            verify(ledger).write(eq(OversightLedger.LIFTED), isNull(), eq(AGENT), isNull(), eq("bob"), anyMap());
        }

        @Test
        @DisplayName("dismissing suppresses re-recommendation until promoteAfter more decisions")
        void dismissSuppresses() {
            AutonomyState state = open();

            AutonomyState dismissed = service.dismiss(AGENT, CATEGORY, state.recommendation().id(), "not yet", "carol");

            assertEquals(Recommendation.Status.DISMISSED, dismissed.recommendation().status());
            assertEquals(60, dismissed.suppressUntilCount());
            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));
        }

        @Test
        @DisplayName("only a tool category can be suspended by hand")
        void manualNeedsToolCategory() {
            var e = assertThrows(WebApplicationException.class, () -> service.suspendManually(AGENT, "rule:agent-1", "x", "bob"));
            assertEquals("NOT_A_TOOL_CATEGORY", code(e));
        }
    }
}
