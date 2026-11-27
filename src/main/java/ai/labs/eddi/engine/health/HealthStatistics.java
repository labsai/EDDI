/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.report.HealthReports.Interval;

import java.util.List;

/**
 * The statistics behind Agent Health's numbers — pure functions, no state.
 * <ul>
 * <li><b>Intervals</b>: the Wilson score interval at 95 % for rates (it stays
 * inside 0..1 and behaves at small {@code n} and at 0 % / 100 %, where the
 * normal approximation collapses to a point); a normal-approximation interval
 * for a mean whose variance is known (Apdex).</li>
 * <li><b>Significance of a change</b> against the baseline period, all
 * two-sided at 95 % ({@code |z| ≥ 1.96}): rates by the pooled two-proportion
 * z-test; means with a known variance by Welch's z (large-sample) test; counts
 * of two equally long periods by the conditional Poisson test
 * {@code (a − b) / √(a + b)}. A measure with no variance to test against (a
 * percentile from buckets, a cost per session) is never called
 * significant.</li>
 * <li><b>Percentiles</b> from fixed duration buckets, by linear interpolation
 * inside the bucket — approximate by construction, and labelled so.</li>
 * </ul>
 */
public final class HealthStatistics {

    /** Two-sided 95 %. */
    public static final double Z = 1.959963984540054;

    private HealthStatistics() {
    }

    /**
     * The Wilson score interval of {@code successes} out of {@code n}; null for n =
     * 0.
     */
    public static Interval wilson(double successes, long n) {
        if (n <= 0) {
            return null;
        }
        double p = Math.max(0.0, Math.min(1.0, successes / n));
        double z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denominator;
        double margin = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new Interval(Math.max(0.0, centre - margin), Math.min(1.0, centre + margin));
    }

    /** A 95 % normal-approximation interval of a mean; null for n = 0. */
    public static Interval normal(double mean, double variance, long n) {
        if (n <= 0) {
            return null;
        }
        double margin = Z * Math.sqrt(Math.max(0.0, variance) / n);
        return new Interval(mean - margin, mean + margin);
    }

    /**
     * Whether rate {@code x1/n1} differs from {@code x2/n2} — the pooled
     * two-proportion z-test. False when either side is empty or both rates are 0 or
     * 1 (no variance).
     */
    public static boolean significantProportions(double x1, long n1, double x2, long n2) {
        if (n1 <= 0 || n2 <= 0) {
            return false;
        }
        double p1 = x1 / n1;
        double p2 = x2 / n2;
        double pooled = (x1 + x2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        return se > 0 && Math.abs(p1 - p2) / se >= Z;
    }

    /** Whether two means with known variances differ — Welch's z (large-sample). */
    public static boolean significantMeans(double mean1, double variance1, long n1, double mean2, double variance2, long n2) {
        if (n1 <= 1 || n2 <= 1) {
            return false;
        }
        double se = Math.sqrt(Math.max(0.0, variance1) / n1 + Math.max(0.0, variance2) / n2);
        return se > 0 && Math.abs(mean1 - mean2) / se >= Z;
    }

    /**
     * Whether two counts of equally long periods differ — the conditional Poisson
     * test.
     */
    public static boolean significantCounts(long a, long b) {
        return significantCounts(a, b, 1.0);
    }

    /**
     * Whether count {@code a} (over an exposure {@code ratio} times that of
     * {@code b}) differs from what {@code b} predicts — the conditional (binomial)
     * test of two Poisson rates: given {@code n = a + b}, {@code a} is binomial
     * with {@code p = ratio / (1 + ratio)} under no change.
     */
    public static boolean significantCounts(long a, long b, double ratio) {
        long n = a + b;
        if (n <= 0 || !(ratio > 0) || Double.isInfinite(ratio)) {
            return false;
        }
        double p = ratio / (1 + ratio);
        double se = Math.sqrt(n * p * (1 - p));
        return se > 0 && Math.abs(a - n * p) / se >= Z;
    }

    /**
     * The {@code q}-quantile (0..1) of durations counted in buckets with the given
     * inclusive upper bounds (one more bucket than bounds: the overflow), by linear
     * interpolation inside the bucket the rank falls into; the overflow bucket
     * answers its lower bound. Null when nothing was counted.
     */
    public static Double percentile(List<Long> buckets, long[] bounds, double q) {
        long total = 0;
        for (Long count : buckets) {
            total += count == null ? 0 : count;
        }
        if (total == 0) {
            return null;
        }
        double rank = q * total;
        long seen = 0;
        for (int i = 0; i < buckets.size(); i++) {
            long count = buckets.get(i) == null ? 0 : buckets.get(i);
            if (count > 0 && seen + count >= rank) {
                if (i >= bounds.length) {
                    return (double) bounds[bounds.length - 1];
                }
                double lower = i == 0 ? 0.0 : bounds[i - 1];
                double upper = bounds[i];
                double within = (rank - seen) / count;
                return lower + (upper - lower) * Math.max(0.0, Math.min(1.0, within));
            }
            seen += count;
        }
        return (double) bounds[bounds.length - 1];
    }
}
