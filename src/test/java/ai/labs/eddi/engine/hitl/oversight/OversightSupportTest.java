/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.ThirdPartyIdentifiers;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.PATTERN;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("oversight support classes")
class OversightSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TARGET = Pattern.compile("/agentstore/agents/([0-9a-f]{24})");
    private static final String ID = "0123456789abcdef01234567";
    private static final String STORED = "{\"oversight\":{\"sampledAudit\":{\"rules\":[{\"match\":\"a\",\"rate\":0.1}]}}}";

    @Nested
    @DisplayName("OversightWriteGuard")
    class WriteGuard {

        private String refusal(String uri, String body, OversightWriteGuard.StoredHitlConfig stored) {
            return OversightWriteGuard.refusal(uri, body, TARGET, stored);
        }

        private OversightWriteGuard.StoredHitlConfig stored(String json) {
            return agentId -> json == null ? null : MAPPER.readTree(json);
        }

        @Test
        @DisplayName("no URI means no refusal")
        void nullUri() {
            assertNull(OversightWriteGuard.refusal(null, "{}", TARGET, stored(STORED)));
        }

        @Test
        @DisplayName("writes under the oversight API paths are refused, other analytics paths are not")
        void apiPaths() {
            for (String uri : List.of("/hitl/samples", "/hitl/samples/s1", "/hitl/autonomy/a/apply", "/hitl/analytics/rebuild",
                    "/hitl/analytics/rebuild?from=x", "/x/hitl/samples#frag")) {
                assertEquals(OversightWriteGuard.OVERSIGHT_API_REASON, refusal(uri, null, null), uri);
            }
            assertNull(refusal("/hitl/analytics/summary", null, null));
            assertNull(refusal("/hitl/samplesX", null, null));
        }

        @Test
        @DisplayName("without an agent-store target, a lookup or a body there is nothing to compare")
        void nothingToCompare() {
            assertNull(OversightWriteGuard.refusal("/agentstore/agents/" + ID, "{}", null, stored(STORED)));
            assertNull(refusal("/somewhere/else", "{}", stored(STORED)));
            assertNull(refusal("/agentstore/agents/" + ID, "{}", null));
            assertNull(refusal("/agentstore/agents/" + ID, null, stored(STORED)));
            assertNull(refusal("/agentstore/agents/" + ID, "   ", stored(STORED)));
        }

        @Test
        @DisplayName("a body that is not a JSON object is not this guard's concern")
        void notAnObject() {
            assertNull(refusal("/agentstore/agents/" + ID, "{not json", stored(STORED)));
            assertNull(refusal("/agentstore/agents/" + ID, "[1,2]", stored(STORED)));
            assertNull(refusal("/agentstore/agents/" + ID, "\"text\"", stored(STORED)));
            assertNull(refusal("/agentstore/agents/" + ID, "null", stored(STORED)));
        }

        @Test
        @DisplayName("an unchanged oversight block passes, a changed or removed one is refused")
        void comparison() {
            String same = "{\"hitlConfig\":" + STORED + "}";
            String changed = "{\"hitlConfig\":{\"oversight\":{\"sampledAudit\":{\"rules\":[{\"match\":\"a\",\"rate\":0.0}]}}}}";
            String removed = "{\"hitlConfig\":{\"toolApprovals\":{}}}";
            String uri = "/agentstore/agents/" + ID;

            assertNull(refusal(uri, same, stored(STORED)));
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, refusal(uri, changed, stored(STORED)));
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, refusal(uri, removed, stored(STORED)));
        }

        @Test
        @DisplayName("an agent with no stored oversight may stay without, but not gain one")
        void nothingStored() {
            String uri = "/agentstore/agents/" + ID;
            String without = "{\"hitlConfig\":{\"toolApprovals\":{}}}";
            String withNull = "{\"hitlConfig\":{\"oversight\":null}}";
            String gains = "{\"hitlConfig\":" + STORED + "}";

            assertNull(refusal(uri, without, stored(null)), "no stored hitlConfig at all");
            assertNull(refusal(uri, without, stored("{\"toolApprovals\":{}}")), "stored hitlConfig without oversight");
            assertNull(refusal(uri, withNull, stored("{\"oversight\":null}")));
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, refusal(uri, gains, stored(null)));
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, refusal(uri, gains, stored("{\"toolApprovals\":{}}")));
        }

        @Test
        @DisplayName("when the stored config cannot be read, a body naming oversight is refused and one without it is not")
        void unreadableStored() {
            OversightWriteGuard.StoredHitlConfig failing = agentId -> {
                throw new IllegalStateException("db down");
            };
            String uri = "/agentstore/agents/" + ID;

            assertNull(refusal(uri, "{\"hitlConfig\":{\"toolApprovals\":{}}}", failing));
            assertNull(refusal(uri, "{\"hitlConfig\":{\"oversight\":null}}", failing));
            assertNull(refusal(uri, "{}", failing));
            assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, refusal(uri, "{\"hitlConfig\":" + STORED + "}", failing));
        }
    }

    @Nested
    @DisplayName("OversightGdprParticipant")
    class Gdpr {

        private final IApprovalDecisionStore decisions = mock(IApprovalDecisionStore.class);
        private final IAuditSampleStore samples = mock(IAuditSampleStore.class);
        private final InMemoryAutonomyStateStore states = new InMemoryAutonomyStateStore();
        private final AuditLedgerService ledger = mock(AuditLedgerService.class);
        private final OversightGdprParticipant participant = new OversightGdprParticipant(decisions, samples, states, ledger);

        private Recommendation rec(String appliedBy, String dismissedBy) {
            return new Recommendation("r", Recommendation.Kind.PROMOTE, Recommendation.Status.APPLIED, NOW, 3, null, PATTERN, true, null, List.of(),
                    false, appliedBy, NOW, 4, dismissedBy, NOW, "n");
        }

        private Suspension suspension(String by, String lifted) {
            return new Suspension(true, PATTERN, NOW, Suspension.Trigger.MANUAL, null, null, by, lifted, NOW, "x");
        }

        @Test
        @DisplayName("it is named hitlOversight")
        void name() {
            assertEquals("hitlOversight", participant.name());
        }

        @Test
        @DisplayName("erasure counts every store and pseudonymizes only the states the user touched")
        void erase() {
            when(ledger.pseudonymOf("alice")).thenReturn("pseudo-1");
            when(samples.deleteByUser("alice")).thenReturn(2L);
            when(samples.pseudonymizeReviewer("alice", "pseudo-1")).thenReturn(1L);
            when(decisions.eraseUser("alice", "pseudo-1")).thenReturn(4L);
            states.save(AutonomyState.initial(AGENT, "applied").withRecommendation(rec("alice", null)), 0);
            states.save(AutonomyState.initial(AGENT, "dismissed").withRecommendation(rec("bob", "alice")), 0);
            states.save(AutonomyState.initial(AGENT, "suspended").withSuspension(suspension("alice", null)), 0);
            states.save(AutonomyState.initial(AGENT, "lifted").withSuspension(suspension("bob", "alice")), 0);
            states.save(AutonomyState.initial(AGENT, "others").withRecommendation(rec("bob", "carol")).withSuspension(suspension("bob", "carol")),
                    0);
            states.save(AutonomyState.initial(AGENT, "empty"), 0);

            long changed = participant.erase("alice");

            assertEquals(2 + 1 + 4 + 4, changed);
            assertEquals("pseudo-1", states.read(AGENT, "applied").orElseThrow().recommendation().appliedBy());
            assertEquals("pseudo-1", states.read(AGENT, "dismissed").orElseThrow().recommendation().dismissedBy());
            assertEquals("bob", states.read(AGENT, "dismissed").orElseThrow().recommendation().appliedBy());
            assertEquals("pseudo-1", states.read(AGENT, "suspended").orElseThrow().suspension().suspendedBy());
            assertEquals("pseudo-1", states.read(AGENT, "lifted").orElseThrow().suspension().liftedBy());
            assertEquals("carol", states.read(AGENT, "others").orElseThrow().suspension().liftedBy());
        }

        @Test
        @DisplayName("a state whose write loses the compare-and-set is not counted")
        void eraseLostWrite() {
            IAutonomyStateStore store = mock(IAutonomyStateStore.class);
            AutonomyState state = AutonomyState.initial(AGENT, "applied").withRecommendation(rec("alice", null));
            when(store.list(any(), anyInt())).thenReturn(List.of(state));
            when(store.save(any(), anyLong())).thenReturn(Optional.empty());
            when(ledger.pseudonymOf("alice")).thenReturn("pseudo-1");

            assertEquals(0, new OversightGdprParticipant(decisions, samples, store, ledger).erase("alice"));
        }

        @Test
        @DisplayName("export is null when the user has no data, and lists only the non-empty parts")
        void export() {
            assertNull(participant.export("alice"));

            when(samples.readByUser(eq("alice"), anyInt())).thenReturn(List.of(sample("s1")));
            @SuppressWarnings("unchecked")
            Map<String, Object> onlySamples = (Map<String, Object>) participant.export("alice");
            assertEquals(List.of("auditSamples"), List.copyOf(onlySamples.keySet()));

            when(samples.readByUser(eq("alice"), anyInt())).thenReturn(List.of());
            ApprovalDecision d = decision("APPROVED", NOW);
            when(decisions.readByUser(eq("alice"), anyInt())).thenReturn(List.of(d));
            @SuppressWarnings("unchecked")
            Map<String, Object> onlyDecisions = (Map<String, Object>) participant.export("alice");
            assertEquals(List.of("approvalDecisions"), List.copyOf(onlyDecisions.keySet()));

            when(samples.readByUser(eq("alice"), anyInt())).thenReturn(List.of(sample("s1")));
            @SuppressWarnings("unchecked")
            Map<String, Object> both = (Map<String, Object>) participant.export("alice");
            assertEquals(2, both.size());
        }

        @Test
        @DisplayName("export masks other people — approver, reviewer, conversation owner — and keeps the subject's own id")
        void exportMasksThirdParties() {
            // user-1 owns the conversation; alice approved in it and reviewed its sample
            ApprovalDecision byAlice = decision("APPROVED", NOW);
            ApprovalDecision bySystem = decision("REJECTED", NOW, "system:timeout", ApprovalDecision.DeciderKind.SYSTEM, "TIMEOUT", null);
            when(decisions.readByUser(eq("user-1"), anyInt())).thenReturn(List.of(byAlice, bySystem));
            when(samples.readByUser(eq("user-1"), anyInt()))
                    .thenReturn(List.of(sample("s1").reviewed(AuditSample.Status.OK, null, null, "alice", NOW)));

            @SuppressWarnings("unchecked")
            Map<String, Object> owner = (Map<String, Object>) participant.export("user-1");
            @SuppressWarnings("unchecked")
            List<ApprovalDecision> ownerDecisions = (List<ApprovalDecision>) owner.get("approvalDecisions");
            assertEquals("user-1", ownerDecisions.get(0).userId());
            assertEquals(ThirdPartyIdentifiers.OTHER_PERSON, ownerDecisions.get(0).decidedBy());
            assertEquals("system:timeout", ownerDecisions.get(1).decidedBy(), "a system principal names no person");
            @SuppressWarnings("unchecked")
            List<AuditSample> ownerSamples = (List<AuditSample>) owner.get("auditSamples");
            assertEquals("user-1", ownerSamples.get(0).userId());
            assertEquals(ThirdPartyIdentifiers.OTHER_PERSON, ownerSamples.get(0).reviewedBy());

            // alice's own export of the decision she made: her id stays, the owner's goes
            when(decisions.readByUser(eq("alice"), anyInt())).thenReturn(List.of(byAlice));
            @SuppressWarnings("unchecked")
            Map<String, Object> approver = (Map<String, Object>) participant.export("alice");
            @SuppressWarnings("unchecked")
            List<ApprovalDecision> approverDecisions = (List<ApprovalDecision>) approver.get("approvalDecisions");
            assertEquals("alice", approverDecisions.get(0).decidedBy());
            assertEquals(ThirdPartyIdentifiers.OTHER_PERSON, approverDecisions.get(0).userId());
        }

        @Test
        @DisplayName("pseudonymize returns the same state when the user appears nowhere")
        void pseudonymizeUntouched() {
            AutonomyState state = AutonomyState.initial(AGENT, CATEGORY);
            assertSame(state, OversightGdprParticipant.pseudonymize(state, "alice", "p"));
            AutonomyState other = state.withRecommendation(rec("bob", "carol")).withSuspension(suspension("bob", null));
            assertSame(other, OversightGdprParticipant.pseudonymize(other, "alice", "p"));
        }
    }

    @Nested
    @DisplayName("OversightConfigLookup")
    class Lookup {

        private final AgentConfigurationCache cache = mock(AgentConfigurationCache.class);
        private final IAgentStore agentStore = mock(IAgentStore.class);
        private final OversightConfigLookup lookup = new OversightConfigLookup(cache, agentStore);

        private AgentConfiguration agentWithOversight() {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setOversight(new OversightConfig());
            var agent = new AgentConfiguration();
            agent.setHitlConfig(hitl);
            return agent;
        }

        @Test
        @DisplayName("an explicit version is read straight from the cache; a missing agent id reads nothing")
        void explicitVersion() throws Exception {
            AgentConfiguration agent = agentWithOversight();
            when(cache.read(AGENT, 2)).thenReturn(agent);

            assertSame(agent, lookup.agent(AGENT, 2));
            assertSame(agent.getHitlConfig(), lookup.hitlConfig(AGENT, 2));
            assertSame(agent.getHitlConfig().getOversight(), lookup.oversight(AGENT, 2));
            assertNull(lookup.agent(null, 2));
            assertNull(lookup.hitlConfig(null, null));
            assertNull(lookup.oversight(null, null));
        }

        @Test
        @DisplayName("no version means the newest stored one")
        void newestVersion() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getVersion()).thenReturn(7);
            when(agentStore.getCurrentResourceId(AGENT)).thenReturn(resourceId);
            AgentConfiguration agent = agentWithOversight();
            when(cache.read(AGENT, 7)).thenReturn(agent);

            assertSame(agent, lookup.agent(AGENT, null));
            assertEquals(7, lookup.currentVersion(AGENT));
        }

        @Test
        @DisplayName("an unknown agent, an unreadable one, or one without hitlConfig or oversight yields null, never an exception")
        void nulls() throws Exception {
            when(agentStore.getCurrentResourceId("ghost")).thenReturn(null);
            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> lookup.currentVersion("ghost"));
            assertNull(lookup.agent("ghost", null));

            when(cache.read(AGENT, 1)).thenThrow(new IllegalStateException("db down"));
            assertNull(lookup.agent(AGENT, 1));

            when(cache.read(AGENT, 2)).thenReturn(new AgentConfiguration());
            assertNull(lookup.hitlConfig(AGENT, 2));
            assertNull(lookup.oversight(AGENT, 2));

            var noOversight = new AgentConfiguration();
            noOversight.setHitlConfig(new AgentConfiguration.HitlConfig());
            when(cache.read(AGENT, 3)).thenReturn(noOversight);
            assertNotNull(lookup.hitlConfig(AGENT, 3));
            assertNull(lookup.oversight(AGENT, 3));
        }
    }

    @Nested
    @DisplayName("OversightLedger")
    class Ledger {

        @Test
        @DisplayName("it writes nothing without a ledger or with a disabled one")
        void disabled() {
            assertDoesNotThrow(() -> new OversightLedger(null).write(OversightLedger.LIFTED, null, AGENT, 1, "bob", Map.of()));
            var service = mock(AuditLedgerService.class);
            when(service.isEnabled()).thenReturn(false);
            new OversightLedger(service).write(OversightLedger.LIFTED, null, AGENT, 1, "bob", Map.of());
            verify(service, never()).submit(any());
        }

        @Test
        @DisplayName("an entry carries the actor, the details and the task id, and a failing submit is swallowed")
        void writes() {
            var service = mock(AuditLedgerService.class);
            when(service.isEnabled()).thenReturn(true);
            var ledger = new OversightLedger(service);

            ledger.write(OversightLedger.PROMOTION, "conv-1", AGENT, 4, "carol", Map.of("category", CATEGORY));

            var captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(service).submit(captor.capture());
            AuditEntry entry = captor.getValue();
            assertEquals(OversightLedger.PROMOTION, entry.taskId());
            assertEquals("hitl", entry.taskType());
            assertEquals("carol", entry.userId());
            assertEquals("conv-1", entry.conversationId());
            assertEquals(4, entry.agentVersion());
            assertEquals("carol", entry.output().get("actor"));
            assertEquals(CATEGORY, entry.output().get("category"));

            when(service.submit(any())).thenThrow(new IllegalStateException("queue gone"));
            assertDoesNotThrow(() -> ledger.write(OversightLedger.DEMOTION, null, AGENT, null, "bob", Map.of()));
        }
    }

    @Nested
    @DisplayName("OversightMetrics")
    class MetricsTest {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final IAuditSampleStore samples = mock(IAuditSampleStore.class);
        private final IAutonomyStateStore states = mock(IAutonomyStateStore.class);
        private final OversightMetrics metrics = new OversightMetrics(registry, samples, states);

        @Test
        @DisplayName("the gauges read NaN until a refresh, then the store counts; a failing refresh goes back to NaN")
        void gauges() {
            metrics.registerGauges();
            assertTrue(Double.isNaN(registry.get("eddi.audit.sample.pending").gauge().value()));

            when(samples.count(any(SampleFilter.class))).thenReturn(5L);
            when(states.countSuspended()).thenReturn(2L);
            metrics.refresh();
            assertEquals(5.0, registry.get("eddi.audit.sample.pending").gauge().value());
            assertEquals(2.0, registry.get("eddi.autonomy.suspension.active").gauge().value());

            when(samples.count(any(SampleFilter.class))).thenThrow(new IllegalStateException("down"));
            metrics.refresh();
            assertTrue(Double.isNaN(registry.get("eddi.audit.sample.pending").gauge().value()));
            metrics.refresh();
            assertTrue(Double.isNaN(registry.get("eddi.autonomy.suspension.active").gauge().value()), "a second failure stays NaN");
        }

        @Test
        @DisplayName("a decision's latency is timed with unknown tags when absent, and a negative one counts as zero")
        void decisionLatency() {
            metrics.decisionProjected("regular", "TOOL_CALL", null);
            assertTrue(registry.find("eddi.hitl.decision.latency").timers().isEmpty());

            metrics.decisionProjected(null, null, 1500L);
            metrics.decisionProjected("regular", "TOOL_CALL", -5L);

            var unknown = registry.get("eddi.hitl.decision.latency").tag("surface", "unknown").tag("pause_type", "unknown").timer();
            assertEquals(1, unknown.count());
            assertEquals(1.5, unknown.totalTime(TimeUnit.SECONDS));
            var known = registry.get("eddi.hitl.decision.latency").tag("surface", "regular").timer();
            assertEquals(0.0, known.totalTime(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("the counters count what they are named for")
        void counters() {
            metrics.projectionFailed();
            metrics.sample("capped");
            metrics.sampleReviewed("OK");
            metrics.samplesExpired(0);
            metrics.samplesExpired(3);
            metrics.recommendation("PROMOTE");
            metrics.promotionApplied();
            metrics.demotion("MANUAL");
            assertDoesNotThrow(OversightMetrics::suspensionGated);

            assertEquals(1.0, registry.get("eddi.hitl.decision.projection.failures").counter().count());
            assertEquals(1.0, registry.get("eddi.audit.sample").tag("outcome", "capped").counter().count());
            assertEquals(1.0, registry.get("eddi.audit.sample.review").tag("verdict", "OK").counter().count());
            assertEquals(3.0, registry.get("eddi.audit.sample.expired").counter().count());
            assertEquals(1.0, registry.get("eddi.autonomy.recommendation").tag("kind", "PROMOTE").counter().count());
            assertEquals(1.0, registry.get("eddi.autonomy.promotion.applied").counter().count());
            assertEquals(1.0, registry.get("eddi.autonomy.demotion").tag("trigger", "MANUAL").counter().count());
        }
    }

    @Test
    @DisplayName("the default settings are the documented ones, and explicit values are read back")
    void settings() {
        OversightSettings defaults = OversightSettings.defaults();
        assertEquals(Duration.ofDays(400), defaults.analyticsRetention());
        assertEquals(Duration.ofDays(90), defaults.initialBackfill());
        assertTrue(defaults.samplesEnabled());
        assertEquals(Duration.ofDays(180), defaults.samplesRetention());
        assertEquals(Duration.ofHours(1), defaults.maintenanceInterval());
        assertEquals(Duration.ofSeconds(15), defaults.suspensionCacheTtl());
        assertEquals(500, defaults.maxAgentsPerRun());
        assertNotNull(new OversightSettings());
    }

    @Test
    @DisplayName("json helpers round-trip a record and refuse malformed input")
    void json() throws Exception {
        AuditSample original = sample("s1");
        AuditSample copy = OversightJson.read(OversightJson.write(original), AuditSample.class);
        assertEquals(original, copy);
        JsonNode tree = MAPPER.readTree(OversightJson.write(original));
        assertEquals("s1", tree.get("id").asText());
        assertNull(OversightJson.copy(null, Map.class));
        assertThrows(RuntimeException.class, () -> OversightJson.read("{broken", AuditSample.class));
        assertEquals(Instant.parse("2026-11-02T12:00:00Z"), copy.sampledAt());
    }
}
