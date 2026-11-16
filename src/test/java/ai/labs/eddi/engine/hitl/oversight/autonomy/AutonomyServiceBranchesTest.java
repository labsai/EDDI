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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.oversight.InMemoryAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightLedger;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Evidence;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AutonomyService branches")
class AutonomyServiceBranchesTest {

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
    private AutonomyPolicyConfig.CategoryPolicy policy;
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
        service = serviceOver(stateStore);

        policy = new AutonomyPolicyConfig.CategoryPolicy();
        policy.setDemoteOnReasonCodes(List.of("FALSE_CLAIM"));
        hitl = gatedHitl(policy);
        when(configLookup.hitlConfig(eq(AGENT), any())).thenAnswer(inv -> hitl);
        when(configLookup.agent(eq(AGENT), any())).thenAnswer(inv -> agent());
        when(configLookup.currentVersion(AGENT)).thenReturn(3);
        when(agentStore.read(AGENT, 3)).thenAnswer(inv -> agent());
        when(decisionStore.query(any(), anyInt(), anyInt())).thenAnswer(inv -> List.copyOf(evidence));
        when(restAgentStore.updateAgent(eq(AGENT), eq(3), any(), eq(false))).thenReturn(Response.ok().build());
    }

    private AutonomyService serviceOver(IAutonomyStateStore store) {
        return new AutonomyService(store, decisionStore, sampleStore, configLookup, agentStore, restAgentStore, overlay, ledger, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AgentConfiguration agent() {
        var agent = new AgentConfiguration();
        agent.setHitlConfig(hitl);
        return agent;
    }

    private AutonomyState seed(AutonomyState state) {
        return stateStore.save(state, 0).orElseThrow();
    }

    private static Recommendation rec(Recommendation.Kind kind, Recommendation.Status status, Integer version, String pattern,
                                      boolean applicable, Evidence evidence) {
        return new Recommendation("rec-1", kind, status, NOW, version, evidence, pattern, applicable, null, List.of(), false, null, null, null,
                null, null, null);
    }

    private static Suspension suspension(boolean active) {
        return new Suspension(active, PATTERN, NOW, Suspension.Trigger.MANUAL, null, null, "bob", null, null, "x");
    }

    private AutonomyState openPromotion() {
        evidence.addAll(decisions(30, 0, "APPROVED"));
        assertTrue(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));
        return stateStore.read(AGENT, CATEGORY).orElseThrow();
    }

    private IAutonomyStateStore failingSaves() {
        IAutonomyStateStore spy = spy(stateStore);
        doReturn(Optional.empty()).when(spy).save(any(), anyLong());
        return spy;
    }

    private static String code(WebApplicationException e) {
        return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("error"));
    }

    private ApprovalDecision rejection(String agentId, String reason, boolean legacy, Instant decidedAt) {
        ApprovalDecision base = decision("REJECTED", NOW.minusSeconds(30), "dave", DeciderKind.HUMAN_VERIFIED, "MANAGER", reason);
        return new ApprovalDecision(base.id(), base.decisionId(), 0, base.surface(), base.pauseType(), base.unitKind(), base.unitId(), agentId,
                base.agentVersion(), null, null, null, null, base.category(), null, base.outcome(), reason, null, null, "dave",
                DeciderKind.HUMAN_VERIFIED, "MANAGER", null, decidedAt, null, false, null, legacy);
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("list hides engine rows and reports each state's tier and pattern")
        void listTiers() {
            seed(AutonomyState.initial(AutonomyState.SYSTEM_AGENT_ID, "initial-backfill"));
            seed(AutonomyState.initial(AGENT, "suspended").withSuspension(suspension(true)));
            seed(AutonomyState.initial(AGENT, "promoted")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.APPLIED, 3, "p-promoted", true, null)));
            seed(AutonomyState.initial(AGENT, "ready")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, "p-ready", true, null)));
            seed(AutonomyState.initial(AGENT, "dismissed")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.DISMISSED, 3, "p-dis", true, null)));
            seed(AutonomyState.initial(AGENT, "revert")
                    .withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.OPEN, 3, "p-rev", true, null)));
            seed(AutonomyState.initial(AGENT, "lifted").withSuspension(suspension(false)));
            seed(AutonomyState.initial(AGENT, "plain"));

            Map<String, AutonomyService.CategoryView> byCategory = new HashMap<>();
            service.list(null, 50).forEach(v -> byCategory.put(v.category(), v));

            assertEquals(7, byCategory.size(), "the __system__ marker row is not listed");
            assertEquals("SUSPENDED", byCategory.get("suspended").tier());
            assertEquals(PATTERN, byCategory.get("suspended").pattern());
            assertEquals("PROMOTED", byCategory.get("promoted").tier());
            assertEquals("p-promoted", byCategory.get("promoted").pattern());
            assertEquals("READY", byCategory.get("ready").tier());
            assertEquals("GATED", byCategory.get("dismissed").tier());
            assertEquals("GATED", byCategory.get("revert").tier());
            assertEquals("GATED", byCategory.get("lifted").tier());
            assertEquals(PATTERN, byCategory.get("lifted").pattern(), "a lifted suspension still names its pattern");
            assertEquals("GATED", byCategory.get("plain").tier());
            assertNull(byCategory.get("plain").pattern());
        }

        @Test
        @DisplayName("get on an unnamed category explains why there is no evidence")
        void getUnnamedCategory() {
            AutonomyService.CategoryView view = service.get(AGENT, "tool:other");

            assertEquals("GATED", view.tier());
            assertNull(view.policy());
            assertNull(view.evidence());
            assertEquals("the agent's autonomyPolicy does not name it", view.evidenceStatus());
            assertEquals("other", view.pattern());
        }

        @Test
        @DisplayName("get on a named category evaluates the evidence now, without a stored state")
        void getEvaluates() {
            evidence.addAll(decisions(3, 0, "APPROVED"));

            AutonomyService.CategoryView view = service.get(AGENT, CATEGORY);

            assertEquals(3, view.evidence().n());
            assertEquals("3 of 30 decisions", view.evidenceStatus());
            assertEquals(PATTERN, view.pattern());
            assertEquals(30, view.policy().promoteAfter());
        }

        @Test
        @DisplayName("get on a suspended category shows the suspended pattern")
        void getSuspended() {
            seed(AutonomyState.initial(AGENT, CATEGORY).withSuspension(suspension(true)));

            AutonomyService.CategoryView view = service.get(AGENT, CATEGORY);

            assertEquals("SUSPENDED", view.tier());
            assertEquals(PATTERN, view.pattern());
        }
    }

    @Nested
    @DisplayName("evaluation")
    class Evaluation {

        @Test
        @DisplayName("an agent without hitl, oversight, policy or categories opens nothing")
        void nothingToEvaluate() {
            assertEquals(0, service.evaluateAgent(AGENT, 3, null));
            var noOversight = new AgentConfiguration.HitlConfig();
            assertEquals(0, service.evaluateAgent(AGENT, 3, noOversight));
            var noPolicy = new AgentConfiguration.HitlConfig();
            noPolicy.setOversight(new OversightConfig());
            assertEquals(0, service.evaluateAgent(AGENT, 3, noPolicy));
            var noCategories = new AgentConfiguration.HitlConfig();
            var oversight = new OversightConfig();
            oversight.setAutonomyPolicy(new AutonomyPolicyConfig());
            noCategories.setOversight(oversight);
            assertEquals(0, service.evaluateAgent(AGENT, 3, noCategories));
        }

        @Test
        @DisplayName("evaluateAgent counts the recommendations it opened and survives a category that fails")
        void evaluateAgentCounts() {
            evidence.addAll(decisions(30, 0, "APPROVED"));
            assertEquals(1, service.evaluateAgent(AGENT, 3, hitl));
            assertEquals(0, service.evaluateAgent(AGENT, 3, hitl), "already open");

            when(decisionStore.query(any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("db down"));
            seed(AutonomyState.initial(AGENT, "tool:other"));
            assertEquals(0, service.evaluateAgent(AGENT, 3, hitl), "the failure is logged, not thrown");
        }

        @Test
        @DisplayName("a category the policy does not name is not evaluated")
        void unnamedCategory() {
            assertFalse(service.evaluateCategory(AGENT, 3, hitl, "tool:unknown"));
        }

        @Test
        @DisplayName("an open promotion turns STALE when the evidence stops holding, and nothing is opened")
        void staleWhenEvidenceDrops() {
            openPromotion();
            evidence.clear();

            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));

            assertEquals(Recommendation.Status.STALE, stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation().status());
        }

        @Test
        @DisplayName("an open promotion without a recorded agent version is made stale and replaced")
        void staleWithoutVersion() {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, null, PATTERN, true, null)));
            evidence.addAll(decisions(30, 0, "APPROVED"));

            assertTrue(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));

            Recommendation r = stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation();
            assertEquals(Recommendation.Status.OPEN, r.status());
            assertEquals(3, r.basedOnAgentVersion());
        }

        @Test
        @DisplayName("an open REVERT recommendation is not touched by evaluation of its category")
        void revertNotStaled() {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            evidence.addAll(decisions(30, 0, "APPROVED"));

            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY), "an open recommendation blocks a second one");
            assertEquals(Recommendation.Kind.REVERT, stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation().kind());
        }

        @Test
        @DisplayName("a rule category and a group category get an advisory, non-applicable recommendation")
        void advisoryRecommendations() {
            var autonomy = new AutonomyPolicyConfig();
            autonomy.setCategories(Map.of("rule:agent-1", new AutonomyPolicyConfig.CategoryPolicy(), "group:phase-x",
                    new AutonomyPolicyConfig.CategoryPolicy()));
            var oversight = new OversightConfig();
            oversight.setAutonomyPolicy(autonomy);
            var advisory = new AgentConfiguration.HitlConfig();
            advisory.setOversight(oversight);
            evidence.addAll(decisions(30, 0, "APPROVED"));

            assertEquals(2, service.evaluateAgent(AGENT, 3, advisory));

            Recommendation rule = stateStore.read(AGENT, "rule:agent-1").orElseThrow().recommendation();
            assertFalse(rule.applicable());
            assertNull(rule.pattern());
            assertTrue(rule.changes().get(0).contains("behavior rule"));
            Recommendation group = stateStore.read(AGENT, "group:phase-x").orElseThrow().recommendation();
            assertTrue(group.changes().get(0).contains("group phase"));
            assertEquals("READY", AutonomyService.tierOf(stateStore.read(AGENT, "rule:agent-1").orElseThrow()));
        }

        @Test
        @DisplayName("a write that keeps losing the compare-and-set opens nothing and counts no metric")
        void casNeverWins() {
            evidence.addAll(decisions(30, 0, "APPROVED"));
            AutonomyService contended = serviceOver(failingSaves());

            assertFalse(contended.evaluateCategory(AGENT, 3, hitl, CATEGORY));

            verify(metrics, never()).recommendation(anyString());
        }

        @Test
        @DisplayName("a suspended category cannot get a recommendation, whatever the evidence")
        void suspendedBlocks() {
            seed(AutonomyState.initial(AGENT, CATEGORY).withSuspension(suspension(true)));
            evidence.addAll(decisions(30, 0, "APPROVED"));
            assertFalse(service.evaluateCategory(AGENT, 3, hitl, CATEGORY));
        }
    }

    @Nested
    @DisplayName("decision triggers")
    class DecisionTriggers {

        @Test
        @DisplayName("rows that are not demote evidence change nothing")
        void ignoredRows() {
            service.onDecisions(List.of(decision("APPROVED", NOW), // not a rejection
                    rejection(AGENT, null, false, NOW), // no reason code
                    rejection(null, "FALSE_CLAIM", false, NOW), // no agent
                    rejection(AGENT, "FALSE_CLAIM", true, NOW), // legacy
                    rejection(AGENT, "TONE", false, NOW))); // not a demote code

            assertTrue(stateStore.list(null, 10).isEmpty());
        }

        @Test
        @DisplayName("a rejection of a category the policy does not name is ignored")
        void unnamedCategory() {
            when(configLookup.hitlConfig(eq(AGENT), any())).thenReturn(null);
            service.onDecisions(List.of(rejection(AGENT, "FALSE_CLAIM", false, NOW)));
            assertTrue(stateStore.list(null, 10).isEmpty());
        }

        @Test
        @DisplayName("an older reset is moved forward, a newer one is kept, and a missing decidedAt means now")
        void resetTimes() {
            seed(AutonomyState.initial(AGENT, CATEGORY).withEvidenceResetAt(NOW.minusSeconds(1000)));
            service.onDecisions(List.of(rejection(AGENT, "FALSE_CLAIM", false, NOW.minusSeconds(10))));
            assertEquals(NOW.minusSeconds(10), stateStore.read(AGENT, CATEGORY).orElseThrow().evidenceResetAt());

            service.onDecisions(List.of(rejection(AGENT, "FALSE_CLAIM", false, NOW.minusSeconds(500))));
            assertEquals(NOW.minusSeconds(10), stateStore.read(AGENT, CATEGORY).orElseThrow().evidenceResetAt(), "never moves backwards");

            service.onDecisions(List.of(rejection(AGENT, "FALSE_CLAIM", false, null)));
            assertEquals(NOW, stateStore.read(AGENT, CATEGORY).orElseThrow().evidenceResetAt());
        }

        @Test
        @DisplayName("an open REVERT recommendation survives a rejection that resets the evidence")
        void revertSurvives() {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            service.onDecisions(List.of(rejection(AGENT, "FALSE_CLAIM", false, NOW)));
            assertEquals(Recommendation.Status.OPEN, stateStore.read(AGENT, CATEGORY).orElseThrow().recommendation().status());
        }
    }

    @Nested
    @DisplayName("sample flags")
    class SampleFlags {

        private AuditSample flagged(String reasonCode) {
            return sample("s1").reviewed(AuditSample.Status.FLAGGED, reasonCode, null, "erin", NOW);
        }

        @Test
        @DisplayName("a sample that is not flagged, or has no agent, is ignored")
        void notFlagged() {
            assertTrue(service.onSampleFlagged(sample("s1")).isEmpty());
            AuditSample noAgent = new AuditSample("s2", null, 3, "c", "u", "call", "tool", "http", "post", CATEGORY, PATTERN, PATTERN, "{}", "d",
                    null, NOW, NOW.plusSeconds(60), AuditSample.Status.FLAGGED, "TONE", null, "erin", NOW, 1L);
            assertTrue(service.onSampleFlagged(noAgent).isEmpty());
        }

        @Test
        @DisplayName("with demoteOnFlaggedSamples=0 only a demote reason code suspends, and no count is read")
        void thresholdZero() {
            policy.setDemoteOnFlaggedSamples(0);

            assertTrue(service.onSampleFlagged(flagged("TONE")).isEmpty());
            assertTrue(service.onSampleFlagged(flagged(null)).isEmpty());

            verify(sampleStore, never()).count(any());
        }

        @Test
        @DisplayName("below the threshold nothing happens; at it the category is suspended by SAMPLE_FLAGGED, reading the stored window")
        void thresholdReached() {
            seed(AutonomyState.initial(AGENT, CATEGORY).withEvidenceResetAt(NOW.minusSeconds(60)));
            when(sampleStore.count(any())).thenReturn(0L, 1L);

            assertTrue(service.onSampleFlagged(flagged(null)).isEmpty());
            AutonomyState suspended = service.onSampleFlagged(flagged(null)).orElseThrow();

            assertEquals(Suspension.Trigger.SAMPLE_FLAGGED, suspended.suspension().trigger());
            assertNull(suspended.suspension().reasonCode());
            verify(metrics).demotion("SAMPLE_FLAGGED");
        }

        @Test
        @DisplayName("a sample whose rule pattern is unknown cannot suspend anything")
        void noPattern() {
            AuditSample noPattern = new AuditSample("s3", AGENT, 3, "c", "u", "call", "tool", "http", "post", CATEGORY, null, null, "{}", "d", null,
                    NOW, NOW.plusSeconds(60), AuditSample.Status.FLAGGED, "FALSE_CLAIM", null, "erin", NOW, 1L);

            assertTrue(service.onSampleFlagged(noPattern).isEmpty());
            assertTrue(stateStore.read(AGENT, CATEGORY).isEmpty());
        }

        @Test
        @DisplayName("a suspension still lands when the agent or its version cannot be read; it just has no REVERT recommendation")
        void unreadableAgent() throws Exception {
            when(configLookup.agent(eq(AGENT), any())).thenReturn(null);
            when(configLookup.currentVersion(AGENT)).thenThrow(new IllegalStateException("gone"));

            AutonomyState state = service.onSampleFlagged(flagged("FALSE_CLAIM")).orElseThrow();

            assertTrue(state.suspended());
            assertNull(state.recommendation());
            verify(metrics, never()).recommendation("REVERT");
            verify(ledger).write(eq(OversightLedger.DEMOTION), isNull(), eq(AGENT), isNull(), anyString(), anyMap());
        }

        @Test
        @DisplayName("when the write never lands there is no side effect")
        void writeLost() {
            AutonomyService contended = serviceOver(failingSaves());

            assertTrue(contended.onSampleFlagged(flagged("FALSE_CLAIM")).isEmpty());

            verify(overlay, never()).invalidate(anyString());
            verify(metrics, never()).demotion(anyString());
        }
    }

    @Nested
    @DisplayName("lift, dismiss, suspend")
    class HumanActions {

        @Test
        @DisplayName("a note is required, trimmed and bounded")
        void noteRules() {
            assertEquals("NOTE_REQUIRED",
                    code(assertThrows(WebApplicationException.class, () -> service.suspendManually(AGENT, CATEGORY, null, "bob"))));
            assertEquals("NOTE_TOO_LONG", code(assertThrows(WebApplicationException.class,
                    () -> service.suspendManually(AGENT, CATEGORY, "x".repeat(AutonomyService.MAX_NOTE_LENGTH + 1), "bob"))));
            AutonomyState ok = service.suspendManually(AGENT, CATEGORY, "  " + "y".repeat(AutonomyService.MAX_NOTE_LENGTH) + "  ", "bob");
            assertEquals(AutonomyService.MAX_NOTE_LENGTH, ok.suspension().liftNote().length());
        }

        @Test
        @DisplayName("a manual suspension that loses the write is a 409 CONCURRENT_CHANGE")
        void manualConflict() {
            AutonomyService contended = serviceOver(failingSaves());
            var e = assertThrows(WebApplicationException.class, () -> contended.suspendManually(AGENT, CATEGORY, "looked wrong", "bob"));
            assertEquals(409, e.getResponse().getStatus());
            assertEquals("CONCURRENT_CHANGE", code(e));
        }

        @Test
        @DisplayName("lifting needs an existing, suspended state")
        void liftPreconditions() {
            assertEquals("STATE_NOT_FOUND", code(assertThrows(WebApplicationException.class, () -> service.lift(AGENT, CATEGORY, "ok", "bob"))));
            seed(AutonomyState.initial(AGENT, CATEGORY));
            assertEquals("NOT_SUSPENDED", code(assertThrows(WebApplicationException.class, () -> service.lift(AGENT, CATEGORY, "ok", "bob"))));
            seed(AutonomyState.initial(AGENT, "other").withSuspension(suspension(false)));
            assertEquals("NOT_SUSPENDED", code(assertThrows(WebApplicationException.class, () -> service.lift(AGENT, "other", "ok", "bob"))));
        }

        @Test
        @DisplayName("lifting makes the open REVERT recommendation stale but leaves an open PROMOTE alone")
        void liftRecommendations() {
            service.suspendManually(AGENT, CATEGORY, "looked wrong", "bob");
            AutonomyState lifted = service.lift(AGENT, CATEGORY, "fine", "bob");
            assertEquals(Recommendation.Status.STALE, lifted.recommendation().status());

            seed(AutonomyState.initial(AGENT, "promo").withSuspension(suspension(true))
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            AutonomyState promoLifted = service.lift(AGENT, "promo", "fine", "bob");
            assertEquals(Recommendation.Status.OPEN, promoLifted.recommendation().status());

            seed(AutonomyState.initial(AGENT, "bare").withSuspension(suspension(true)));
            assertNull(service.lift(AGENT, "bare", "fine", "bob").recommendation());
        }

        @Test
        @DisplayName("a suspension lifted by someone else meanwhile is returned as it now is")
        void liftedMeanwhile() {
            IAutonomyStateStore racing = mock(IAutonomyStateStore.class);
            AutonomyState active = AutonomyState.initial(AGENT, CATEGORY).withSuspension(suspension(true));
            AutonomyState gone = AutonomyState.initial(AGENT, CATEGORY).withSuspension(suspension(false));
            when(racing.read(AGENT, CATEGORY)).thenReturn(Optional.of(active), Optional.of(gone));

            AutonomyState result = serviceOver(racing).lift(AGENT, CATEGORY, "fine", "bob");

            assertFalse(result.suspended());
            verify(racing, never()).save(any(), anyLong());
        }

        @Test
        @DisplayName("a lift that loses the write is a 409 and does not touch the overlay")
        void liftConflict() {
            seed(AutonomyState.initial(AGENT, CATEGORY).withSuspension(suspension(true)));
            AutonomyService contended = serviceOver(failingSaves());
            var e = assertThrows(WebApplicationException.class, () -> contended.lift(AGENT, CATEGORY, "fine", "bob"));
            assertEquals("CONCURRENT_CHANGE", code(e));
            verify(overlay, never()).invalidate(anyString());
        }

        @Test
        @DisplayName("dismissing needs a note and an open recommendation with the same id")
        void dismissPreconditions() {
            assertEquals("NOTE_REQUIRED", code(assertThrows(WebApplicationException.class, () -> service.dismiss(AGENT, CATEGORY, "r", " ", "bob"))));
            assertEquals("STATE_NOT_FOUND",
                    code(assertThrows(WebApplicationException.class, () -> service.dismiss(AGENT, CATEGORY, "r", "n", "bob"))));
            seed(AutonomyState.initial(AGENT, CATEGORY));
            assertEquals("RECOMMENDATION_CHANGED",
                    code(assertThrows(WebApplicationException.class, () -> service.dismiss(AGENT, CATEGORY, "r", "n", "bob"))));
            seed(AutonomyState.initial(AGENT, "open")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            assertEquals("RECOMMENDATION_CHANGED",
                    code(assertThrows(WebApplicationException.class, () -> service.dismiss(AGENT, "open", null, "n", "bob"))));
            assertEquals("RECOMMENDATION_CHANGED",
                    code(assertThrows(WebApplicationException.class, () -> service.dismiss(AGENT, "open", "wrong", "n", "bob"))));
        }

        @Test
        @DisplayName("dismissal suppresses by evidence seen plus promoteAfter, defaulting both when unknown")
        void dismissSuppression() {
            // No policy for this category, no evidence on the recommendation: 0 + default
            // 30.
            seed(AutonomyState.initial(AGENT, "tool:other")
                    .withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.OPEN, 3, "other", true, null)));
            assertEquals(30, service.dismiss(AGENT, "tool:other", "rec-1", "n", "bob").suppressUntilCount());

            // A policy with its own promoteAfter and recorded evidence of 12 decisions.
            policy.setPromoteAfter(10);
            seed(AutonomyState.initial(AGENT, CATEGORY).withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, PATTERN,
                    true, new Evidence(12, 12, 0, 0, 1.0, NOW, NOW))));
            AutonomyState dismissed = service.dismiss(AGENT, CATEGORY, "rec-1", "n", "bob");
            assertEquals(22, dismissed.suppressUntilCount());
            assertEquals("bob", dismissed.recommendation().dismissedBy());
            verify(ledger, times(2)).write(eq(OversightLedger.DISMISSAL), isNull(), eq(AGENT), isNull(), eq("bob"), anyMap());
        }

        @Test
        @DisplayName("a dismissal that loses the write is a 409")
        void dismissConflict() {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            AutonomyService contended = serviceOver(failingSaves());
            assertEquals("CONCURRENT_CHANGE",
                    code(assertThrows(WebApplicationException.class, () -> contended.dismiss(AGENT, CATEGORY, "rec-1", "n", "bob"))));
        }

        @Test
        @DisplayName("a ledger entry omits the details that are null")
        void ledgerDetailsOmitNulls() {
            service.suspendManually(AGENT, CATEGORY, "looked wrong", "bob");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            verify(ledger).write(eq(OversightLedger.DEMOTION), isNull(), eq(AGENT), eq(3), eq("bob"), details.capture());
            assertEquals("MANUAL", details.getValue().get("trigger"));
            assertFalse(details.getValue().containsKey("triggerRef"));
            assertFalse(details.getValue().containsKey("reasonCode"));
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("an advisory recommendation cannot be applied")
        void advisory() {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, null, false, null)));
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, "rec-1", 3, "carol"));
            assertEquals("NOT_APPLICABLE", code(e));

            seed(AutonomyState.initial(AGENT, "nopattern")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 3, null, true, null)));
            assertEquals("NOT_APPLICABLE",
                    code(assertThrows(WebApplicationException.class, () -> service.apply(AGENT, "nopattern", "rec-1", 3, "c"))));
        }

        @Test
        @DisplayName("an agent that cannot be read is a 404")
        void unreadableAgent() throws Exception {
            AutonomyState state = openPromotion();
            when(configLookup.currentVersion(AGENT)).thenThrow(new IllegalStateException("gone"));
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
            assertEquals(404, e.getResponse().getStatus());
        }

        @Test
        @DisplayName("the version the human reviewed must be sent and must match the recommendation")
        void versionChecks() {
            AutonomyState state = openPromotion();
            String id = state.recommendation().id();
            assertEquals("AGENT_CHANGED", code(assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, id, null, "c"))));

            seed(AutonomyState.initial(AGENT, "old")
                    .withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, 2, PATTERN, true, null)));
            assertEquals("AGENT_CHANGED", code(assertThrows(WebApplicationException.class, () -> service.apply(AGENT, "old", "rec-1", 3, "c"))));
        }

        @Test
        @DisplayName("a promotion is refused when the policy no longer names the category")
        void policyGone() {
            AutonomyState state = openPromotion();
            hitl = new AgentConfiguration.HitlConfig();
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
            assertEquals("EVIDENCE_CHANGED", code(e));
        }

        @Test
        @DisplayName("a change that would not validate is a 400 INVALID_RESULT and nothing is written")
        void invalidResult() {
            AutonomyState state = openPromotion();
            policy.setPromotedSampleRate(5.0);
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
            assertEquals("INVALID_RESULT", code(e));
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("a REST refusal from the agent store passes through unchanged")
        void restRefusalPassesThrough() {
            AutonomyState state = openPromotion();
            when(restAgentStore.updateAgent(eq(AGENT), eq(3), any(), eq(false))).thenThrow(new ForbiddenException("no"));
            assertThrows(ForbiddenException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
        }

        @Test
        @DisplayName("an unexpected store failure becomes an IllegalStateException")
        void unexpectedFailure() {
            AutonomyState state = openPromotion();
            when(restAgentStore.updateAgent(eq(AGENT), eq(3), any(), eq(false))).thenThrow(new IllegalArgumentException("boom"));
            var e = assertThrows(IllegalStateException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
            assertTrue(e.getMessage().contains("boom"));
        }

        @Test
        @DisplayName("a modified resource while reading the new version is a 409 AGENT_CHANGED")
        void modifiedMeanwhile() throws Exception {
            AutonomyState state = openPromotion();
            when(configLookup.currentVersion(AGENT)).thenReturn(3).thenThrow(new IResourceStore.ResourceModifiedException("raced"));
            var e = assertThrows(WebApplicationException.class, () -> service.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));
            assertEquals("AGENT_CHANGED", code(e));
        }

        @Test
        @DisplayName("a revert lifts the suspension, keeps the evidence reset, and is recorded as a revert")
        void revertApplied() throws Exception {
            AutonomyState suspended = service.suspendManually(AGENT, CATEGORY, "looked wrong", "bob");
            when(configLookup.currentVersion(AGENT)).thenReturn(3, 4);

            AutonomyState applied = service.apply(AGENT, CATEGORY, suspended.recommendation().id(), 3, "carol");

            assertEquals(Recommendation.Status.APPLIED, applied.recommendation().status());
            assertFalse(applied.suspended());
            assertEquals("made permanent in agent version 4", applied.suspension().liftNote());
            assertEquals(NOW, applied.evidenceResetAt(), "the reset from the suspension stays; apply does not set a new one");
            verify(ledger).write(eq(OversightLedger.REVERT), isNull(), eq(AGENT), eq(4), eq("carol"), anyMap());
        }

        @Test
        @DisplayName("a revert whose suspension was lifted meanwhile only records the recommendation")
        void revertWithoutSuspension() throws Exception {
            seed(AutonomyState.initial(AGENT, CATEGORY)
                    .withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.OPEN, 3, PATTERN, true, null)));
            when(configLookup.currentVersion(AGENT)).thenReturn(3, 4);

            AutonomyState applied = service.apply(AGENT, CATEGORY, "rec-1", 3, "carol");

            assertEquals(Recommendation.Status.APPLIED, applied.recommendation().status());
            assertNull(applied.suspension());
        }

        @Test
        @DisplayName("an apply that loses the final write is a 409 and counts nothing")
        void applyLosesWrite() throws Exception {
            AutonomyState state = openPromotion();
            when(configLookup.currentVersion(AGENT)).thenReturn(3, 4);
            AutonomyService contended = serviceOver(failingSaves());

            var e = assertThrows(WebApplicationException.class, () -> contended.apply(AGENT, CATEGORY, state.recommendation().id(), 3, "carol"));

            assertEquals("CONCURRENT_CHANGE", code(e));
            verify(metrics, never()).promotionApplied();
        }
    }

    @Nested
    @DisplayName("statics")
    class Statics {

        @Test
        @DisplayName("policyOf is null for a missing hitl, oversight or policy")
        void policyOf() {
            assertNull(AutonomyService.policyOf(null, CATEGORY));
            assertNull(AutonomyService.policyOf(new AgentConfiguration.HitlConfig(), CATEGORY));
            var noPolicy = new AgentConfiguration.HitlConfig();
            noPolicy.setOversight(new OversightConfig());
            assertNull(AutonomyService.policyOf(noPolicy, CATEGORY));
            assertEquals(30, AutonomyService.policyOf(hitl, CATEGORY).promoteAfter());
        }

        @Test
        @DisplayName("tierOf is GATED for an open REVERT, a stale PROMOTE and a lifted suspension")
        void tierOf() {
            AutonomyState base = AutonomyState.initial(AGENT, CATEGORY);
            assertEquals("GATED", AutonomyService.tierOf(base));
            assertEquals("GATED", AutonomyService.tierOf(
                    base.withRecommendation(rec(Recommendation.Kind.PROMOTE, Recommendation.Status.STALE, 3, null, true, null))));
            assertEquals("GATED", AutonomyService.tierOf(
                    base.withRecommendation(rec(Recommendation.Kind.REVERT, Recommendation.Status.APPLIED, 3, null, true, null))));
            assertEquals("SUSPENDED", AutonomyService.tierOf(base.withSuspension(suspension(true))));
        }
    }
}
