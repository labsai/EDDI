/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.util.List;

/**
 * {@code hitlConfig.oversight.sampledAudit}: a share of the tool calls the
 * approval gate <em>allowed</em> (exempt or unmatched) is copied, redacted,
 * into a review queue for a human to look at after the fact.
 * <p>
 * Absent = nothing is sampled.
 *
 * @since 6.7.0
 */
public class SampledAuditConfig {

    /** Default for {@link #maxSamplesPerCategoryPerDay}. */
    public static final int DEFAULT_MAX_SAMPLES_PER_CATEGORY_PER_DAY = 50;
    /** Default for {@link #reviewWithin}. */
    public static final Duration DEFAULT_REVIEW_WITHIN = Duration.ofDays(3);

    private List<Rule> rules;
    private Integer maxSamplesPerCategoryPerDay;
    private String reviewWithin;
    private Boolean includeResult;

    /** One sampling rule. */
    public static class Rule {
        private String match;
        private Double rate;
        private String category;

        /** A tool-approval pattern; matched against the calls the gate allowed. */
        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        /** Share of matching calls to sample, 0.0..1.0. */
        public Double getRate() {
            return rate;
        }

        public void setRate(Double rate) {
            this.rate = rate;
        }

        /** The category samples are filed under; {@code tool:<match>} when absent. */
        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        /** {@link #category}, or its default. */
        @JsonIgnore
        public String effectiveCategory() {
            return category != null && !category.isBlank() ? category : "tool:" + match;
        }
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    public Integer getMaxSamplesPerCategoryPerDay() {
        return maxSamplesPerCategoryPerDay;
    }

    public void setMaxSamplesPerCategoryPerDay(Integer maxSamplesPerCategoryPerDay) {
        this.maxSamplesPerCategoryPerDay = maxSamplesPerCategoryPerDay;
    }

    public String getReviewWithin() {
        return reviewWithin;
    }

    public void setReviewWithin(String reviewWithin) {
        this.reviewWithin = reviewWithin;
    }

    public Boolean getIncludeResult() {
        return includeResult;
    }

    public void setIncludeResult(Boolean includeResult) {
        this.includeResult = includeResult;
    }

    @JsonIgnore
    public int effectiveMaxSamplesPerCategoryPerDay() {
        return maxSamplesPerCategoryPerDay != null ? maxSamplesPerCategoryPerDay : DEFAULT_MAX_SAMPLES_PER_CATEGORY_PER_DAY;
    }

    @JsonIgnore
    public Duration effectiveReviewWithin() {
        if (reviewWithin == null || reviewWithin.isBlank()) {
            return DEFAULT_REVIEW_WITHIN;
        }
        try {
            return Duration.parse(reviewWithin.trim());
        } catch (RuntimeException invalid) {
            // Refused at save time; a legacy or hand-edited document keeps the default.
            return DEFAULT_REVIEW_WITHIN;
        }
    }

    @JsonIgnore
    public boolean effectiveIncludeResult() {
        return Boolean.TRUE.equals(includeResult);
    }
}
