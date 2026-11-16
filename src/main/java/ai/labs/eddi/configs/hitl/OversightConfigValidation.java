/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.engine.hitl.review.ReasonCodes;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Save-time validation of {@code hitlConfig.oversight} (sampled audit and
 * earned-autonomy policy). Called from {@link HitlConfigValidation#validate},
 * so it runs on every agent save, on ZIP import and in agent setup. Every
 * refusal names the field path and the rule it broke.
 *
 * @since 6.7.0
 */
public final class OversightConfigValidation {

    /** A sample or policy category: printable, no spaces, at most 128 chars. */
    public static final Pattern CATEGORY = Pattern.compile("^[A-Za-z0-9_.:/*{}\\-]{1,128}$");
    public static final int MAX_SAMPLE_RULES = 50;
    public static final int MAX_POLICY_CATEGORIES = 200;
    public static final int MAX_SAMPLES_PER_DAY_CEILING = 1000;
    public static final Duration MIN_REVIEW_WITHIN = Duration.ofHours(1);
    public static final Duration MAX_REVIEW_WITHIN = Duration.ofDays(30);
    public static final int MIN_PROMOTE_AFTER = 5;
    public static final int MAX_PROMOTE_AFTER = 10_000;
    public static final Duration MIN_WINDOW = Duration.ofDays(1);
    public static final Duration MAX_WINDOW = Duration.ofDays(365);
    public static final int MAX_DEMOTE_ON_FLAGGED_SAMPLES = 100;
    public static final double MIN_PROMOTED_SAMPLE_RATE = 0.01;
    public static final Duration MAX_COOLDOWN = Duration.ofDays(365);
    /**
     * Channels a decision may be counted as evidence from. {@code TIMEOUT} and
     * {@code SYSTEM} are decisions nobody made, so they are refused outright.
     */
    public static final Set<String> EVIDENCE_CHANNELS = Set.of("REST", "MANAGER", "SLACK", "MCP");

    private OversightConfigValidation() {
    }

    /**
     * Validates {@code hitlConfig.oversight}; no-op when absent.
     *
     * @param reasonCodes
     *            the agent's configured reason-code vocabulary
     *            ({@code hitlConfig.review.reasonCodes}), or null for the built-in
     *            one
     */
    public static void validate(OversightConfig oversight, Map<String, String> reasonCodes, String fieldPath) {
        if (oversight == null) {
            return;
        }
        validateSampledAudit(oversight.getSampledAudit(), fieldPath + ".sampledAudit");
        validateAutonomyPolicy(oversight.getAutonomyPolicy(), reasonCodes, fieldPath + ".autonomyPolicy");
    }

    static void validateSampledAudit(SampledAuditConfig audit, String fieldPath) {
        if (audit == null) {
            return;
        }
        List<SampledAuditConfig.Rule> rules = audit.getRules();
        if (rules != null) {
            if (rules.size() > MAX_SAMPLE_RULES) {
                throw new IllegalArgumentException(fieldPath + ".rules has " + rules.size() + " entries; at most " + MAX_SAMPLE_RULES);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rules.size(); i++) {
                String rulePath = fieldPath + ".rules[" + i + "]";
                SampledAuditConfig.Rule rule = rules.get(i);
                if (rule == null) {
                    throw new IllegalArgumentException(rulePath + " must not be null");
                }
                Optional<String> error = ToolApprovalPatterns.validate(rule.getMatch());
                if (error.isPresent()) {
                    throw new IllegalArgumentException(rulePath + ".match: " + error.get());
                }
                if (!seen.add(rule.getMatch())) {
                    throw new IllegalArgumentException("duplicate match '" + rule.getMatch() + "' in " + fieldPath + ".rules");
                }
                Double rate = rule.getRate();
                if (rate == null || rate.isNaN() || rate < 0.0 || rate > 1.0) {
                    throw new IllegalArgumentException(rulePath + ".rate is required and must be between 0.0 and 1.0, got " + rate);
                }
                if (rule.getCategory() != null) {
                    validateCategory(rule.getCategory(), rulePath + ".category");
                }
            }
        }
        Integer cap = audit.getMaxSamplesPerCategoryPerDay();
        if (cap != null && (cap < 1 || cap > MAX_SAMPLES_PER_DAY_CEILING)) {
            throw new IllegalArgumentException(
                    fieldPath + ".maxSamplesPerCategoryPerDay must be between 1 and " + MAX_SAMPLES_PER_DAY_CEILING + ", got " + cap);
        }
        validateDuration(audit.getReviewWithin(), MIN_REVIEW_WITHIN, MAX_REVIEW_WITHIN, fieldPath + ".reviewWithin", "PT1H", "P30D");
    }

    static void validateAutonomyPolicy(AutonomyPolicyConfig policy, Map<String, String> reasonCodes, String fieldPath) {
        if (policy == null) {
            return;
        }
        validateCategoryPolicy(policy.getDefaults(), reasonCodes, fieldPath + ".defaults");
        Map<String, AutonomyPolicyConfig.CategoryPolicy> categories = policy.getCategories();
        if (categories == null) {
            return;
        }
        if (categories.size() > MAX_POLICY_CATEGORIES) {
            throw new IllegalArgumentException(
                    fieldPath + ".categories has " + categories.size() + " entries; at most " + MAX_POLICY_CATEGORIES);
        }
        for (var entry : categories.entrySet()) {
            String category = entry.getKey();
            validateCategory(category, fieldPath + ".categories key");
            validateCategoryPolicy(entry.getValue(), reasonCodes, fieldPath + ".categories['" + category + "']");
        }
    }

    private static void validateCategoryPolicy(AutonomyPolicyConfig.CategoryPolicy p, Map<String, String> reasonCodes, String path) {
        if (p == null) {
            return;
        }
        if (p.getPromoteAfter() != null && (p.getPromoteAfter() < MIN_PROMOTE_AFTER || p.getPromoteAfter() > MAX_PROMOTE_AFTER)) {
            throw new IllegalArgumentException(path + ".promoteAfter must be between " + MIN_PROMOTE_AFTER + " and " + MAX_PROMOTE_AFTER
                    + ", got " + p.getPromoteAfter());
        }
        if (p.getMinUneditedRate() != null && (p.getMinUneditedRate().isNaN() || p.getMinUneditedRate() < 0 || p.getMinUneditedRate() > 1)) {
            throw new IllegalArgumentException(path + ".minUneditedRate must be between 0.0 and 1.0, got " + p.getMinUneditedRate());
        }
        validateDuration(p.getWindow(), MIN_WINDOW, MAX_WINDOW, path + ".window", "P1D", "P365D");
        if (p.getDemoteOnReasonCodes() != null) {
            for (int i = 0; i < p.getDemoteOnReasonCodes().size(); i++) {
                String code = p.getDemoteOnReasonCodes().get(i);
                if (code == null) {
                    throw new IllegalArgumentException(path + ".demoteOnReasonCodes[" + i + "] must not be null");
                }
                ReasonCodes.require(code, reasonCodes, path + ".demoteOnReasonCodes[" + i + "]");
            }
        }
        Integer flagged = p.getDemoteOnFlaggedSamples();
        if (flagged != null && (flagged < 0 || flagged > MAX_DEMOTE_ON_FLAGGED_SAMPLES)) {
            throw new IllegalArgumentException(
                    path + ".demoteOnFlaggedSamples must be between 0 and " + MAX_DEMOTE_ON_FLAGGED_SAMPLES + ", got " + flagged);
        }
        Double rate = p.getPromotedSampleRate();
        if (rate != null && (rate.isNaN() || rate < MIN_PROMOTED_SAMPLE_RATE || rate > 1.0)) {
            throw new IllegalArgumentException(path + ".promotedSampleRate must be between " + MIN_PROMOTED_SAMPLE_RATE
                    + " and 1.0, got " + rate + " — a promoted category is always sampled");
        }
        validateDuration(p.getCooldownAfterDemotion(), Duration.ZERO, MAX_COOLDOWN, path + ".cooldownAfterDemotion", "P0D", "P365D");
        if (p.getEvidenceChannels() != null) {
            if (p.getEvidenceChannels().isEmpty()) {
                throw new IllegalArgumentException(path + ".evidenceChannels is empty: no decision could ever count");
            }
            for (String channel : p.getEvidenceChannels()) {
                String upper = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
                if (!EVIDENCE_CHANNELS.contains(upper)) {
                    throw new IllegalArgumentException(path + ".evidenceChannels has '" + channel + "'; allowed are " + EVIDENCE_CHANNELS
                            + " (timeouts and system decisions are never evidence)");
                }
            }
        }
    }

    private static void validateCategory(String category, String fieldPath) {
        if (category == null || !CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException(fieldPath + " '" + category
                    + "' is not a valid category: letters, digits and _ . : / * { } -, 1 to 128 characters");
        }
    }

    private static void validateDuration(String value, Duration min, Duration max, String fieldPath, String minText, String maxText) {
        if (value == null) {
            return;
        }
        Duration parsed;
        try {
            parsed = Duration.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldPath + " '" + value + "' is not an ISO-8601 duration (e.g. \"P3D\", \"PT12H\")");
        }
        if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldPath + " must be between " + minText + " and " + maxText + ", got " + value);
        }
    }
}
