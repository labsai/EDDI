/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary.CodeCount;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary.SampleStats;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Approval analytics: summaries of the decision facts, joined with the
 * sampled-audit counts, per bucket.
 * <p>
 * Computed at query time over a bounded window (the REST layer refuses more
 * than 365 days) and capped at {@value #MAX_ROWS} rows — a summary over more is
 * marked {@code truncated}. The same numbers on MongoDB and PostgreSQL, because
 * the aggregation is here rather than in two query dialects. Results are cached
 * for 60 seconds.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ApprovalAnalyticsService {

    /** Upper bound of the decision rows one summary reads. */
    public static final int MAX_ROWS = 50_000;
    /** Upper bound of the sample rows one summary reads. */
    public static final int MAX_SAMPLE_ROWS = 20_000;
    static final int TOP_CODES = 5;

    /** How a summary is grouped. */
    public enum GroupBy {
        NONE, CATEGORY, AGENT, GROUP, DAY, WEEK, CHANNEL, AGENT_VERSION;

        /**
         * Parses {@code none|category|agent|group|day|week|channel|agentVersion}; null
         * = NONE.
         */
        public static GroupBy parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "category" -> CATEGORY;
                case "agent" -> AGENT;
                case "group" -> GROUP;
                case "day" -> DAY;
                case "week" -> WEEK;
                case "channel" -> CHANNEL;
                case "agentversion" -> AGENT_VERSION;
                default -> throw new IllegalArgumentException("groupBy must be one of none, category, agent, group, day, week, channel, "
                        + "agentVersion");
            };
        }

        String label() {
            return this == AGENT_VERSION ? "agentVersion" : name().toLowerCase(Locale.ROOT);
        }
    }

    private record CacheKey(DecisionFilter filter, GroupBy groupBy) {
    }

    private final IApprovalDecisionStore decisionStore;
    private final IAuditSampleStore sampleStore;
    private final Cache<CacheKey, ApprovalSummary.Result> cache = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(60)).build();

    @Inject
    public ApprovalAnalyticsService(IApprovalDecisionStore decisionStore, IAuditSampleStore sampleStore) {
        this.decisionStore = decisionStore;
        this.sampleStore = sampleStore;
    }

    /** The summaries of the decisions matching the filter. */
    public ApprovalSummary.Result summarize(DecisionFilter filter, GroupBy groupBy) {
        return cache.get(new CacheKey(filter, groupBy), key -> compute(filter, groupBy));
    }

    /** A page of decision rows; {@code userId} only when {@code includeUserId}. */
    public List<ApprovalDecision> decisions(DecisionFilter filter, int index, int limit, boolean includeUserId) {
        List<ApprovalDecision> rows = decisionStore.query(filter, index, limit);
        if (includeUserId) {
            return rows;
        }
        return rows.stream().map(r -> r.withPersonalFields(null, r.decidedBy())).toList();
    }

    /** How many decision rows match. */
    public long count(DecisionFilter filter) {
        return decisionStore.count(filter);
    }

    ApprovalSummary.Result compute(DecisionFilter filter, GroupBy groupBy) {
        List<ApprovalDecision> rows = decisionStore.query(filter, 0, MAX_ROWS + 1);
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }
        Function<ApprovalDecision, String> decisionKey = keyOf(groupBy);
        Map<String, List<ApprovalDecision>> byKey = new TreeMap<>();
        for (ApprovalDecision row : rows) {
            byKey.computeIfAbsent(decisionKey.apply(row), k -> new ArrayList<>()).add(row);
        }

        Map<String, List<AuditSample>> samplesByKey = null;
        Function<AuditSample, String> sampleKey = sampleKeyOf(groupBy);
        if (sampleKey != null && filter.groupId() == null) {
            samplesByKey = new TreeMap<>();
            for (AuditSample sample : sampleStore.query(new SampleFilter(filter.agentId(), filter.category(), null, filter.from(), filter.to()), 0,
                    MAX_SAMPLE_ROWS)) {
                samplesByKey.computeIfAbsent(sampleKey.apply(sample), k -> new ArrayList<>()).add(sample);
                byKey.putIfAbsent(sampleKey.apply(sample), new ArrayList<>());
            }
        }

        List<ApprovalSummary> buckets = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            SampleStats stats = samplesByKey != null ? sampleStats(samplesByKey.getOrDefault(entry.getKey(), List.of())) : null;
            buckets.add(summarize(entry.getKey(), entry.getValue(), stats));
        }
        if (groupBy == GroupBy.NONE && buckets.isEmpty()) {
            buckets.add(summarize("all", List.of(), samplesByKey != null ? sampleStats(List.of()) : null));
        }
        return new ApprovalSummary.Result(groupBy.label(), truncated, buckets);
    }

    static ApprovalSummary summarize(String key, List<ApprovalDecision> rows, SampleStats samples) {
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        Map<String, Long> channels = new TreeMap<>();
        Map<String, Long> reasons = new TreeMap<>();
        LatencyHistogram histogram = new LatencyHistogram();
        long approved = 0;
        long edited = 0;
        long rejected = 0;
        long timedOut = 0;
        long legacy = 0;
        for (ApprovalDecision row : rows) {
            byOutcome.merge(row.outcome(), 1L, Long::sum);
            channels.merge(row.channel() != null ? row.channel() : "UNKNOWN", 1L, Long::sum);
            if (row.reasonCode() != null) {
                reasons.merge(row.reasonCode(), 1L, Long::sum);
            }
            histogram.add(row.latencyMs());
            if (row.timedOut()) {
                timedOut++;
            }
            if (row.legacy()) {
                legacy++;
            }
            switch (row.outcome() != null ? row.outcome() : "") {
                case "APPROVED" -> approved++;
                case "APPROVED_EDITED" -> edited++;
                case "REJECTED" -> rejected++;
                default -> {
                }
            }
        }
        long human = approved + edited + rejected;
        long total = rows.size();
        return new ApprovalSummary(key, total, byOutcome, human, rate(approved, human), rate(edited, human), rate(rejected, human),
                top(reasons), histogram.percentile(0.5), histogram.percentile(0.9), histogram.counts(), rate(timedOut, total), channels,
                legacy, samples);
    }

    static SampleStats sampleStats(List<AuditSample> samples) {
        long pending = 0;
        long reviewed = 0;
        long flagged = 0;
        long expired = 0;
        Map<String, Long> codes = new TreeMap<>();
        for (AuditSample sample : samples) {
            switch (sample.status()) {
                case PENDING -> pending++;
                case OK -> reviewed++;
                case FLAGGED -> {
                    reviewed++;
                    flagged++;
                    if (sample.reasonCode() != null) {
                        codes.merge(sample.reasonCode(), 1L, Long::sum);
                    }
                }
                case EXPIRED -> expired++;
            }
        }
        return new SampleStats(samples.size(), pending, reviewed, flagged, expired, rate(expired, reviewed + expired), top(codes));
    }

    private static Function<ApprovalDecision, String> keyOf(GroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> row -> "all";
            case CATEGORY -> row -> orUnknown(row.category());
            case AGENT -> row -> orUnknown(row.agentId());
            case GROUP -> row -> orUnknown(row.groupId());
            case DAY -> row -> day(row.decidedAt());
            case WEEK -> row -> week(row.decidedAt());
            case CHANNEL -> row -> orUnknown(row.channel());
            case AGENT_VERSION -> row -> row.agentVersion() != null ? String.valueOf(row.agentVersion()) : "unknown";
        };
    }

    private static Function<AuditSample, String> sampleKeyOf(GroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> s -> "all";
            case CATEGORY -> s -> orUnknown(s.category());
            case AGENT -> s -> orUnknown(s.agentId());
            case DAY -> s -> day(s.sampledAt());
            case WEEK -> s -> week(s.sampledAt());
            default -> null;
        };
    }

    static String day(Instant at) {
        return at == null ? "unknown" : LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    static String week(Instant at) {
        return at == null
                ? "unknown"
                : LocalDate.ofInstant(at, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    private static String orUnknown(String value) {
        return value != null ? value : "unknown";
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : Math.round((double) part / whole * 10_000.0) / 10_000.0;
    }

    private static List<CodeCount> top(Map<String, Long> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey())).limit(TOP_CODES).map(e -> new CodeCount(e.getKey(), e.getValue())).toList();
    }
}
