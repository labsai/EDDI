/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregate of one bucket of ratings, computed in the database.
 *
 * @param bucket
 *            the bucket key — see {@link RatingGroupBy}
 * @param count
 *            how many ratings
 * @param avgNormalized
 *            their mean normalized score (0..1), or {@code null} when there are
 *            none
 * @param distribution
 *            how the scores spread
 * @param csat
 *            share of five-star ratings scored 4 or 5, or {@code null} when the
 *            bucket has no five-star rating
 * @param thumbsUpRate
 *            share of thumbs ratings that are up, or {@code null} when the
 *            bucket has no thumbs rating
 * @param commentCount
 *            how many carry a comment
 * @param topTags
 *            the most used reason tags, most used first
 */
public record RatingSummary(String bucket, long count, Double avgNormalized, Distribution distribution, Double csat,
        Double thumbsUpRate, long commentCount, List<TagCount> topTags) {

    /** At most this many tags are reported per bucket. */
    public static final int TOP_TAGS = 10;

    public RatingSummary {
        topTags = topTags == null ? List.of() : List.copyOf(topTags);
    }

    /**
     * Builds a summary from the raw per-bucket sums both stores compute, so the
     * derived rates are computed in exactly one place.
     */
    public static RatingSummary of(String bucket, long count, Double avgNormalized, long thumbsUp, long thumbsDown, long[] stars,
                                   long commentCount, List<TagCount> topTags) {
        Map<Integer, Long> starMap = new LinkedHashMap<>();
        long starTotal = 0;
        for (int i = 0; i < 5; i++) {
            long value = stars == null || stars.length <= i ? 0 : stars[i];
            starMap.put(i + 1, value);
            starTotal += value;
        }
        Double csat = starTotal == 0 ? null : (double) (starMap.get(4) + starMap.get(5)) / starTotal;
        long thumbsTotal = thumbsUp + thumbsDown;
        Double upRate = thumbsTotal == 0 ? null : (double) thumbsUp / thumbsTotal;
        return new RatingSummary(bucket, count, count == 0 ? null : avgNormalized, new Distribution(thumbsUp, thumbsDown, starMap), csat,
                upRate, commentCount, topTags);
    }

    /** An empty bucket. */
    public static RatingSummary empty(String bucket) {
        return of(bucket, 0, null, 0, 0, new long[5], 0, List.of());
    }

    /**
     * @param thumbsUp
     *            thumbs ratings that are up
     * @param thumbsDown
     *            thumbs ratings that are down
     * @param stars
     *            five-star ratings per score, keys 1..5
     */
    public record Distribution(long thumbsUp, long thumbsDown, Map<Integer, Long> stars) {
        public Distribution {
            stars = stars == null ? Map.of() : Map.copyOf(stars);
        }
    }

    /** One reason tag and how often it was picked. */
    public record TagCount(String tag, long count) {
    }
}
