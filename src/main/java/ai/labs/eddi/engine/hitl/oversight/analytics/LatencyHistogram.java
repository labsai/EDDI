/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decision latencies in fixed, roughly logarithmic buckets, and the median and
 * p90 interpolated from them. Approximate by design — at bucket resolution — so
 * percentiles never need the raw values, and the same buckets work for any
 * database (MongoDB 6 has no percentile accumulator).
 *
 * @since 6.7.0
 */
public final class LatencyHistogram {

    /** Upper bounds (exclusive) of the buckets in ms; the last bucket is open. */
    public static final List<Long> BOUNDS_MS = List.of(1_000L, 5_000L, 15_000L, 60_000L, 300_000L, 900_000L, 3_600_000L,
            14_400_000L, 86_400_000L, 259_200_000L);

    private final long[] counts = new long[BOUNDS_MS.size() + 1];
    private long total;

    /** Adds one latency; negative values count as 0, null is ignored. */
    public void add(Long latencyMs) {
        if (latencyMs == null) {
            return;
        }
        long value = Math.max(0L, latencyMs);
        int bucket = BOUNDS_MS.size();
        for (int i = 0; i < BOUNDS_MS.size(); i++) {
            if (value < BOUNDS_MS.get(i)) {
                bucket = i;
                break;
            }
        }
        counts[bucket]++;
        total++;
    }

    /** How many latencies were added. */
    public long total() {
        return total;
    }

    /** The bucket counts, in {@link #BOUNDS_MS} order plus the open bucket. */
    public List<Long> counts() {
        List<Long> list = new ArrayList<>(counts.length);
        for (long c : counts) {
            list.add(c);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * The approximate {@code p}-quantile in ms, linear within its bucket; null when
     * empty. A quantile in the open bucket is reported as its lower bound.
     *
     * @param p
     *            0..1
     */
    public Long percentile(double p) {
        if (total == 0) {
            return null;
        }
        double rank = Math.max(0.0, Math.min(1.0, p)) * total;
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            long inBucket = counts[i];
            if (inBucket == 0) {
                continue;
            }
            if (cumulative + inBucket >= rank) {
                long lower = i == 0 ? 0L : BOUNDS_MS.get(i - 1);
                if (i == BOUNDS_MS.size()) {
                    return lower;
                }
                long upper = BOUNDS_MS.get(i);
                double position = (rank - cumulative) / inBucket;
                return Math.round(lower + position * (upper - lower));
            }
            cumulative += inBucket;
        }
        return BOUNDS_MS.getLast();
    }
}
