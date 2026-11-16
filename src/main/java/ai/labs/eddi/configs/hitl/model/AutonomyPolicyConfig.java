/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code hitlConfig.oversight.autonomyPolicy}: when a gated approval category
 * has earned a recommendation to stop asking, and when a category that stopped
 * asking is suspended again.
 * <p>
 * Absent = analytics only: nothing is recommended, nothing is suspended
 * automatically. Only the categories named in {@link #categories} are
 * evaluated; {@link #defaults} fills the fields a category leaves out.
 * <p>
 * A recommendation is only ever a recommendation: a human applies it, which
 * writes a new agent version. A suspension is automatic and is a runtime
 * overlay on the gate — it never rewrites configuration, and only a human lifts
 * it.
 *
 * @since 6.7.0
 */
public class AutonomyPolicyConfig {

    public static final int DEFAULT_PROMOTE_AFTER = 30;
    public static final double DEFAULT_MIN_UNEDITED_RATE = 0.9;
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    public static final int DEFAULT_DEMOTE_ON_FLAGGED_SAMPLES = 1;
    public static final double DEFAULT_PROMOTED_SAMPLE_RATE = 0.10;
    public static final Duration DEFAULT_COOLDOWN_AFTER_DEMOTION = Duration.ofDays(14);
    /** Channels whose decisions count as evidence when none are configured. */
    public static final List<String> DEFAULT_EVIDENCE_CHANNELS = List.of("MANAGER", "REST", "SLACK");

    private CategoryPolicy defaults;
    private Map<String, CategoryPolicy> categories;

    /**
     * The policy of one category. Every field is optional; an absent one comes from
     * {@link AutonomyPolicyConfig#defaults}, then from the built-in default.
     */
    public static class CategoryPolicy {
        private Integer promoteAfter;
        private Double minUneditedRate;
        private String window;
        private List<String> demoteOnReasonCodes;
        private Integer demoteOnFlaggedSamples;
        private Double promotedSampleRate;
        private String cooldownAfterDemotion;
        private List<String> evidenceChannels;

        public Integer getPromoteAfter() {
            return promoteAfter;
        }

        public void setPromoteAfter(Integer promoteAfter) {
            this.promoteAfter = promoteAfter;
        }

        public Double getMinUneditedRate() {
            return minUneditedRate;
        }

        public void setMinUneditedRate(Double minUneditedRate) {
            this.minUneditedRate = minUneditedRate;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }

        public List<String> getDemoteOnReasonCodes() {
            return demoteOnReasonCodes;
        }

        public void setDemoteOnReasonCodes(List<String> demoteOnReasonCodes) {
            this.demoteOnReasonCodes = demoteOnReasonCodes;
        }

        public Integer getDemoteOnFlaggedSamples() {
            return demoteOnFlaggedSamples;
        }

        public void setDemoteOnFlaggedSamples(Integer demoteOnFlaggedSamples) {
            this.demoteOnFlaggedSamples = demoteOnFlaggedSamples;
        }

        public Double getPromotedSampleRate() {
            return promotedSampleRate;
        }

        public void setPromotedSampleRate(Double promotedSampleRate) {
            this.promotedSampleRate = promotedSampleRate;
        }

        public String getCooldownAfterDemotion() {
            return cooldownAfterDemotion;
        }

        public void setCooldownAfterDemotion(String cooldownAfterDemotion) {
            this.cooldownAfterDemotion = cooldownAfterDemotion;
        }

        public List<String> getEvidenceChannels() {
            return evidenceChannels;
        }

        public void setEvidenceChannels(List<String> evidenceChannels) {
            this.evidenceChannels = evidenceChannels;
        }
    }

    /**
     * The fully resolved policy of one category: every field has a value.
     *
     * @param evidenceChannels
     *            upper-case {@code HitlChannel} names
     */
    public record Resolved(int promoteAfter, double minUneditedRate, Duration window, List<String> demoteOnReasonCodes,
            int demoteOnFlaggedSamples, double promotedSampleRate, Duration cooldownAfterDemotion, List<String> evidenceChannels) {
    }

    public CategoryPolicy getDefaults() {
        return defaults;
    }

    public void setDefaults(CategoryPolicy defaults) {
        this.defaults = defaults;
    }

    public Map<String, CategoryPolicy> getCategories() {
        return categories;
    }

    public void setCategories(Map<String, CategoryPolicy> categories) {
        this.categories = categories;
    }

    /**
     * The resolved policy of a category, or null when the policy does not name it —
     * an unnamed category is never promoted or suspended automatically.
     */
    @JsonIgnore
    public Resolved resolve(String category) {
        if (category == null || categories == null || !categories.containsKey(category)) {
            return null;
        }
        CategoryPolicy own = categories.get(category);
        CategoryPolicy base = defaults;
        return new Resolved(
                pick(own != null ? own.promoteAfter : null, base != null ? base.promoteAfter : null, DEFAULT_PROMOTE_AFTER),
                pick(own != null ? own.minUneditedRate : null, base != null ? base.minUneditedRate : null, DEFAULT_MIN_UNEDITED_RATE),
                duration(own != null ? own.window : null, base != null ? base.window : null, DEFAULT_WINDOW),
                pick(own != null ? own.demoteOnReasonCodes : null, base != null ? base.demoteOnReasonCodes : null, List.of()),
                pick(own != null ? own.demoteOnFlaggedSamples : null, base != null ? base.demoteOnFlaggedSamples : null,
                        DEFAULT_DEMOTE_ON_FLAGGED_SAMPLES),
                pick(own != null ? own.promotedSampleRate : null, base != null ? base.promotedSampleRate : null,
                        DEFAULT_PROMOTED_SAMPLE_RATE),
                duration(own != null ? own.cooldownAfterDemotion : null, base != null ? base.cooldownAfterDemotion : null,
                        DEFAULT_COOLDOWN_AFTER_DEMOTION),
                upper(pick(own != null ? own.evidenceChannels : null, base != null ? base.evidenceChannels : null,
                        DEFAULT_EVIDENCE_CHANNELS)));
    }

    private static <T> T pick(T own, T base, T fallback) {
        if (own != null) {
            return own;
        }
        return base != null ? base : fallback;
    }

    private static Duration duration(String own, String base, Duration fallback) {
        String value = own != null && !own.isBlank() ? own : base;
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Duration.parse(value.trim());
        } catch (RuntimeException invalid) {
            // Refused at save time; a hand-edited document keeps the default.
            return fallback;
        }
    }

    private static List<String> upper(List<String> channels) {
        return channels.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.trim().toUpperCase(Locale.ROOT)).toList();
    }
}
