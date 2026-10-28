/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.RatingSummary.TagCount;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the two rating stores share after the database has done its part: the
 * order of buckets and the top-tag cut, so the two backends cannot disagree on
 * them.
 */
public final class RatingStoreSupport {

    /** The bucket key of a rating with no agent version. */
    public static final String UNKNOWN_VERSION_BUCKET = "unknown";
    /** The bucket key of a rating with no channel. */
    public static final String UNKNOWN_CHANNEL_BUCKET = "other";

    private RatingStoreSupport() {
    }

    /**
     * Orders buckets ascending by key — numerically for agent versions (so 10 comes
     * after 9), as strings otherwise — and guarantees the single bucket of
     * {@link RatingGroupBy#NONE} even when nothing matched.
     */
    public static List<RatingSummary> finish(List<RatingSummary> buckets, RatingGroupBy groupBy) {
        List<RatingSummary> sorted = new ArrayList<>(buckets);
        if (groupBy == RatingGroupBy.NONE) {
            if (sorted.isEmpty()) {
                return List.of(RatingSummary.empty(RatingGroupBy.ALL_BUCKET));
            }
            return sorted;
        }
        Comparator<RatingSummary> order = groupBy == RatingGroupBy.AGENT_VERSION
                ? Comparator.comparingLong((RatingSummary s) -> versionOrder(s.bucket())).thenComparing(RatingSummary::bucket)
                : Comparator.comparing(RatingSummary::bucket, Comparator.nullsLast(Comparator.naturalOrder()));
        sorted.sort(order);
        return sorted;
    }

    private static long versionOrder(String bucket) {
        try {
            return Long.parseLong(bucket);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Groups {@code (bucket, tag, count)} rows into each bucket's top
     * {@link RatingSummary#TOP_TAGS}, most used first, ties by tag name.
     */
    public static Map<String, List<TagCount>> topTags(List<BucketTagCount> rows) {
        Map<String, List<TagCount>> byBucket = new LinkedHashMap<>();
        for (BucketTagCount row : rows) {
            byBucket.computeIfAbsent(row.bucket(), k -> new ArrayList<>()).add(new TagCount(row.tag(), row.count()));
        }
        Map<String, List<TagCount>> top = new LinkedHashMap<>();
        byBucket.forEach((bucket, tags) -> {
            tags.sort(Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::tag));
            top.put(bucket, List.copyOf(tags.subList(0, Math.min(RatingSummary.TOP_TAGS, tags.size()))));
        });
        return top;
    }

    /** One {@code (bucket, tag, count)} row of a tag aggregation. */
    public record BucketTagCount(String bucket, String tag, long count) {
    }
}
