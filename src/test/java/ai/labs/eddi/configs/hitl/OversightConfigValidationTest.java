/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("hitlConfig.oversight validation")
class OversightConfigValidationTest {

    private static SampledAuditConfig.Rule rule(String match, Double rate) {
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch(match);
        rule.setRate(rate);
        return rule;
    }

    private static AgentConfiguration.HitlConfig withAudit(Consumer<SampledAuditConfig> setup) {
        var audit = new SampledAuditConfig();
        audit.setRules(new ArrayList<>(List.of(rule("http.post:/crm/notes", 0.1))));
        setup.accept(audit);
        var oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setOversight(oversight);
        return hitl;
    }

    private static AgentConfiguration.HitlConfig withPolicy(Consumer<AutonomyPolicyConfig.CategoryPolicy> setup) {
        var policy = new AutonomyPolicyConfig.CategoryPolicy();
        setup.accept(policy);
        var autonomy = new AutonomyPolicyConfig();
        autonomy.setCategories(Map.of("tool:http.post:/crm/notes", policy));
        var oversight = new OversightConfig();
        oversight.setAutonomyPolicy(autonomy);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setOversight(oversight);
        return hitl;
    }

    private static String refusal(AgentConfiguration.HitlConfig hitl) {
        return assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl)).getMessage();
    }

    @Test
    @DisplayName("an agent without oversight validates as before; a full example validates")
    void validExamples() {
        assertDoesNotThrow(() -> HitlConfigValidation.validate(new AgentConfiguration.HitlConfig()));
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withAudit(a -> {
            a.setMaxSamplesPerCategoryPerDay(50);
            a.setReviewWithin("P3D");
            a.setIncludeResult(true);
        })));
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withPolicy(p -> {
            p.setPromoteAfter(30);
            p.setMinUneditedRate(0.9);
            p.setWindow("P30D");
            p.setDemoteOnReasonCodes(List.of("FALSE_CLAIM"));
            p.setDemoteOnFlaggedSamples(1);
            p.setPromotedSampleRate(0.1);
            p.setCooldownAfterDemotion("P14D");
            p.setEvidenceChannels(List.of("manager", "SLACK"));
        })));
    }

    @Nested
    @DisplayName("sampledAudit")
    class SampledAudit {

        @ParameterizedTest
        @ValueSource(doubles = {-0.01, 1.01, Double.NaN})
        @DisplayName("rate outside 0..1 is refused, naming the rule")
        void rateRange(double rate) {
            String message = refusal(withAudit(a -> a.getRules().set(0, rule("http.post:/crm/notes", rate))));
            assertTrue(message.contains("hitlConfig.oversight.sampledAudit.rules[0].rate"), message);
        }

        @Test
        @DisplayName("a missing rate and an invalid pattern are refused")
        void requiredFields() {
            assertTrue(refusal(withAudit(a -> a.getRules().set(0, rule("http.post:/x", null)))).contains("rate is required"));
            assertTrue(refusal(withAudit(a -> a.getRules().set(0, rule("", 0.1)))).contains("rules[0].match"));
        }

        @Test
        @DisplayName("duplicate matches and more than 50 rules are refused")
        void duplicatesAndCap() {
            assertTrue(refusal(withAudit(a -> a.getRules().add(rule("http.post:/crm/notes", 0.2)))).contains("duplicate match"));
            String tooMany = refusal(withAudit(a -> {
                for (int i = 0; i < 50; i++) {
                    a.getRules().add(rule("tool_" + i, 0.1));
                }
            }));
            assertTrue(tooMany.contains("at most 50"), tooMany);
        }

        @Test
        @DisplayName("maxSamplesPerCategoryPerDay is 1..1000 and reviewWithin PT1H..P30D")
        void bounds() {
            assertTrue(refusal(withAudit(a -> a.setMaxSamplesPerCategoryPerDay(0))).contains("maxSamplesPerCategoryPerDay"));
            assertTrue(refusal(withAudit(a -> a.setMaxSamplesPerCategoryPerDay(1001))).contains("maxSamplesPerCategoryPerDay"));
            assertTrue(refusal(withAudit(a -> a.setReviewWithin("PT30M"))).contains("reviewWithin"));
            assertTrue(refusal(withAudit(a -> a.setReviewWithin("P31D"))).contains("reviewWithin"));
            assertTrue(refusal(withAudit(a -> a.setReviewWithin("three days"))).contains("ISO-8601"));
        }

        @Test
        @DisplayName("a category with spaces is refused")
        void category() {
            String message = refusal(withAudit(a -> a.getRules().getFirst().setCategory("crm notes")));
            assertTrue(message.contains("rules[0].category"), message);
        }
    }

    @Nested
    @DisplayName("autonomyPolicy")
    class AutonomyPolicy {

        @Test
        @DisplayName("promoteAfter 5..10000, minUneditedRate 0..1, window P1D..P365D")
        void bounds() {
            assertTrue(refusal(withPolicy(p -> p.setPromoteAfter(4))).contains("promoteAfter"));
            assertTrue(refusal(withPolicy(p -> p.setPromoteAfter(10_001))).contains("promoteAfter"));
            assertTrue(refusal(withPolicy(p -> p.setMinUneditedRate(1.1))).contains("minUneditedRate"));
            assertTrue(refusal(withPolicy(p -> p.setWindow("PT12H"))).contains("window"));
            assertTrue(refusal(withPolicy(p -> p.setWindow("P366D"))).contains("window"));
        }

        @Test
        @DisplayName("promotedSampleRate is at least 0.01 — a promoted category is always sampled")
        void promotedSampleRate() {
            assertTrue(refusal(withPolicy(p -> p.setPromotedSampleRate(0.0))).contains("always sampled"));
        }

        @Test
        @DisplayName("demoteOnFlaggedSamples 0..100")
        void flaggedBounds() {
            assertDoesNotThrow(() -> HitlConfigValidation.validate(withPolicy(p -> p.setDemoteOnFlaggedSamples(0))));
            assertTrue(refusal(withPolicy(p -> p.setDemoteOnFlaggedSamples(101))).contains("demoteOnFlaggedSamples"));
        }

        @Test
        @DisplayName("a demote reason code must be in the agent's vocabulary (built-in or configured)")
        void reasonCodes() {
            assertTrue(refusal(withPolicy(p -> p.setDemoteOnReasonCodes(List.of("NOPE")))).contains("not a known reason code"));
            var hitl = withPolicy(p -> p.setDemoteOnReasonCodes(List.of("WRONG_RECIPIENT")));
            var review = new HitlReviewConfig();
            review.setReasonCodes(Map.of("WRONG_RECIPIENT", "Sent to the wrong person"));
            hitl.setReview(review);
            assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
        }

        @Test
        @DisplayName("evidence channels never include TIMEOUT or SYSTEM, and may not be empty")
        void evidenceChannels() {
            assertTrue(refusal(withPolicy(p -> p.setEvidenceChannels(List.of("TIMEOUT")))).contains("never evidence"));
            assertTrue(refusal(withPolicy(p -> p.setEvidenceChannels(List.of()))).contains("is empty"));
        }

        @Test
        @DisplayName("resolve: a named category merges its fields over defaults over built-ins; an unnamed one is null")
        void resolve() {
            var defaults = new AutonomyPolicyConfig.CategoryPolicy();
            defaults.setPromoteAfter(10);
            defaults.setDemoteOnReasonCodes(List.of("TONE"));
            var own = new AutonomyPolicyConfig.CategoryPolicy();
            own.setMinUneditedRate(0.95);
            var policy = new AutonomyPolicyConfig();
            policy.setDefaults(defaults);
            policy.setCategories(Map.of("tool:a", own));

            var resolved = policy.resolve("tool:a");

            assertEquals(10, resolved.promoteAfter());
            assertEquals(0.95, resolved.minUneditedRate());
            assertEquals(List.of("TONE"), resolved.demoteOnReasonCodes());
            assertEquals(Duration.ofDays(30), resolved.window());
            assertEquals(List.of("MANAGER", "REST", "SLACK"), resolved.evidenceChannels());
            assertNull(policy.resolve("tool:other"));
        }
    }
}
