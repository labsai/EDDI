/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import java.util.List;
import java.util.Map;

/**
 * The approval statistics of one bucket (a category, an agent, a day…).
 * <p>
 * Rates are over {@code humanDecisions} — what humans approved, edited or
 * rejected; system decisions and aborts are counted in {@code byOutcome} but
 * not in the rates. Latency percentiles are approximate, at the resolution of
 * {@link LatencyHistogram}.
 *
 * @param key
 *            the bucket ({@code "all"} when not grouped)
 * @param samples
 *            sampled-audit counts of the bucket; null when the grouping has no
 *            sample equivalent (group, channel, agent version)
 * @since 6.7.0
 */
public record ApprovalSummary(String key, long total, Map<String, Long> byOutcome, long humanDecisions, Double uneditedApprovalRate,
        Double editRate, Double rejectionRate, List<CodeCount> topReasonCodes, Long latencyP50Ms, Long latencyP90Ms,
        List<Long> latencyHistogram, Double timeoutShare, Map<String, Long> channelMix, long legacy, SampleStats samples) {

    /** A reason code and how often it was given. */
    public record CodeCount(String code, long count) {
    }

    /**
     * Sampled-audit counts.
     *
     * @param expiredShare
     *            expired / (reviewed + expired) — the oversight-capacity signal
     */
    public record SampleStats(long sampled, long pending, long reviewed, long flagged, long expired, Double expiredShare,
            List<CodeCount> flaggedReasonCodes) {
    }

    /** The summaries of a query. */
    public record Result(String groupBy, boolean truncated, List<ApprovalSummary> buckets) {
    }
}
