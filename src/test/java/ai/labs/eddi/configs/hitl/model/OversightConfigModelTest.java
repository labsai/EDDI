/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("oversight config model")
class OversightConfigModelTest {

    private static AutonomyPolicyConfig policy(AutonomyPolicyConfig.CategoryPolicy defaults, String category,
                                               AutonomyPolicyConfig.CategoryPolicy own) {
        var config = new AutonomyPolicyConfig();
        config.setDefaults(defaults);
        Map<String, AutonomyPolicyConfig.CategoryPolicy> categories = new HashMap<>();
        categories.put(category, own);
        config.setCategories(categories);
        return config;
    }

    @Test
    @DisplayName("a category the policy does not name resolves to null: null category, no map, or a missing key")
    void unnamedCategory() {
        var config = new AutonomyPolicyConfig();
        assertNull(config.resolve("c"), "no categories at all");
        assertNull(config.resolve(null));
        config.setCategories(Map.of("a", new AutonomyPolicyConfig.CategoryPolicy()));
        assertNull(config.resolve("b"));
        assertNull(config.resolve(null));
    }

    @Test
    @DisplayName("a category named with a null policy resolves to the built-in defaults")
    void builtInDefaults() {
        AutonomyPolicyConfig.Resolved resolved = policy(null, "c", null).resolve("c");

        assertEquals(AutonomyPolicyConfig.DEFAULT_PROMOTE_AFTER, resolved.promoteAfter());
        assertEquals(AutonomyPolicyConfig.DEFAULT_MIN_UNEDITED_RATE, resolved.minUneditedRate());
        assertEquals(AutonomyPolicyConfig.DEFAULT_WINDOW, resolved.window());
        assertEquals(List.of(), resolved.demoteOnReasonCodes());
        assertEquals(AutonomyPolicyConfig.DEFAULT_DEMOTE_ON_FLAGGED_SAMPLES, resolved.demoteOnFlaggedSamples());
        assertEquals(AutonomyPolicyConfig.DEFAULT_PROMOTED_SAMPLE_RATE, resolved.promotedSampleRate());
        assertEquals(AutonomyPolicyConfig.DEFAULT_COOLDOWN_AFTER_DEMOTION, resolved.cooldownAfterDemotion());
        assertEquals(AutonomyPolicyConfig.DEFAULT_EVIDENCE_CHANNELS, resolved.evidenceChannels());
    }

    @Test
    @DisplayName("the category overrides the defaults block, which overrides the built-in values, field by field")
    void layering() {
        var defaults = new AutonomyPolicyConfig.CategoryPolicy();
        defaults.setPromoteAfter(50);
        defaults.setMinUneditedRate(0.5);
        defaults.setWindow("P7D");
        defaults.setDemoteOnReasonCodes(List.of("A"));
        defaults.setDemoteOnFlaggedSamples(3);
        defaults.setPromotedSampleRate(0.2);
        defaults.setCooldownAfterDemotion("PT2H");
        defaults.setEvidenceChannels(List.of("mcp"));
        var own = new AutonomyPolicyConfig.CategoryPolicy();
        own.setPromoteAfter(10);
        own.setWindow("  P2D  ");

        AutonomyPolicyConfig.Resolved resolved = policy(defaults, "c", own).resolve("c");

        assertEquals(10, resolved.promoteAfter(), "own value wins");
        assertEquals(0.5, resolved.minUneditedRate(), "default block fills the gap");
        assertEquals(Duration.ofDays(2), resolved.window(), "a padded duration still parses");
        assertEquals(List.of("A"), resolved.demoteOnReasonCodes());
        assertEquals(3, resolved.demoteOnFlaggedSamples());
        assertEquals(0.2, resolved.promotedSampleRate());
        assertEquals(Duration.ofHours(2), resolved.cooldownAfterDemotion());
        assertEquals(List.of("MCP"), resolved.evidenceChannels());
    }

    @Test
    @DisplayName("a blank or unparsable duration falls back: own blank to the defaults block, both blank or invalid to the built-in value")
    void durationFallbacks() {
        var defaults = new AutonomyPolicyConfig.CategoryPolicy();
        defaults.setWindow("P9D");
        var own = new AutonomyPolicyConfig.CategoryPolicy();
        own.setWindow("   ");
        own.setCooldownAfterDemotion("not-a-duration");

        AutonomyPolicyConfig.Resolved resolved = policy(defaults, "c", own).resolve("c");
        assertEquals(Duration.ofDays(9), resolved.window());
        assertEquals(AutonomyPolicyConfig.DEFAULT_COOLDOWN_AFTER_DEMOTION, resolved.cooldownAfterDemotion());

        var blankBoth = new AutonomyPolicyConfig.CategoryPolicy();
        blankBoth.setWindow("");
        assertEquals(AutonomyPolicyConfig.DEFAULT_WINDOW, policy(blankBoth, "c", null).resolve("c").window());
    }

    @Test
    @DisplayName("evidence channels are trimmed and upper-cased, and blank or null entries dropped")
    void channels() {
        var own = new AutonomyPolicyConfig.CategoryPolicy();
        own.setEvidenceChannels(Arrays.asList(" rest ", null, "  ", "Slack"));

        assertEquals(List.of("REST", "SLACK"), policy(null, "c", own).resolve("c").evidenceChannels());
    }

    @Test
    @DisplayName("every policy field round-trips through its accessor")
    void accessors() {
        var p = new AutonomyPolicyConfig.CategoryPolicy();
        p.setPromoteAfter(7);
        p.setMinUneditedRate(0.75);
        p.setWindow("P1D");
        p.setDemoteOnReasonCodes(List.of("X"));
        p.setDemoteOnFlaggedSamples(2);
        p.setPromotedSampleRate(0.3);
        p.setCooldownAfterDemotion("P3D");
        p.setEvidenceChannels(List.of("REST"));
        assertEquals(7, p.getPromoteAfter());
        assertEquals(0.75, p.getMinUneditedRate());
        assertEquals("P1D", p.getWindow());
        assertEquals(List.of("X"), p.getDemoteOnReasonCodes());
        assertEquals(2, p.getDemoteOnFlaggedSamples());
        assertEquals(0.3, p.getPromotedSampleRate());
        assertEquals("P3D", p.getCooldownAfterDemotion());
        assertEquals(List.of("REST"), p.getEvidenceChannels());

        var config = policy(p, "c", p);
        assertEquals(p, config.getDefaults());
        assertEquals(1, config.getCategories().size());
    }

    @Test
    @DisplayName("sampled audit defaults: cap, review window, includeResult and category")
    void sampledAuditDefaults() {
        var audit = new SampledAuditConfig();
        assertEquals(SampledAuditConfig.DEFAULT_MAX_SAMPLES_PER_CATEGORY_PER_DAY, audit.effectiveMaxSamplesPerCategoryPerDay());
        assertEquals(SampledAuditConfig.DEFAULT_REVIEW_WITHIN, audit.effectiveReviewWithin());
        assertFalse(audit.effectiveIncludeResult());

        audit.setMaxSamplesPerCategoryPerDay(12);
        audit.setReviewWithin("PT6H");
        audit.setIncludeResult(true);
        assertEquals(12, audit.effectiveMaxSamplesPerCategoryPerDay());
        assertEquals(Duration.ofHours(6), audit.effectiveReviewWithin());
        assertTrue(audit.effectiveIncludeResult());
        assertEquals(12, audit.getMaxSamplesPerCategoryPerDay());
        assertEquals("PT6H", audit.getReviewWithin());
        assertTrue(audit.getIncludeResult());

        audit.setReviewWithin("  ");
        assertEquals(SampledAuditConfig.DEFAULT_REVIEW_WITHIN, audit.effectiveReviewWithin());
        audit.setReviewWithin("nonsense");
        assertEquals(SampledAuditConfig.DEFAULT_REVIEW_WITHIN, audit.effectiveReviewWithin());
        audit.setIncludeResult(false);
        assertFalse(audit.effectiveIncludeResult());
    }

    @Test
    @DisplayName("a sampling rule's category defaults to tool:<match> unless a non-blank one is set")
    void ruleCategory() {
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch("http.post:*");
        assertEquals("tool:http.post:*", rule.effectiveCategory());
        rule.setCategory("  ");
        assertEquals("tool:http.post:*", rule.effectiveCategory());
        rule.setCategory("crm.notes");
        assertEquals("crm.notes", rule.effectiveCategory());
        rule.setRate(0.25);
        assertEquals(0.25, rule.getRate());
        assertEquals("http.post:*", rule.getMatch());

        var audit = new SampledAuditConfig();
        audit.setRules(List.of(rule));
        assertEquals(1, audit.getRules().size());
    }
}
