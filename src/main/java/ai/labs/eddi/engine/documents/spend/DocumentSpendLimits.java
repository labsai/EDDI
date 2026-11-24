/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import java.util.OptionalDouble;

/**
 * The operator's cumulative daily ceilings on document spend
 * ({@code eddi.documents.spend.*}).
 *
 * @param maxPerUserPerDayUsd
 *            dollars one user may spend on document work per UTC day; empty =
 *            no ceiling
 * @param maxPerTeamPerDayUsd
 *            dollars one team space may spend per UTC day; empty = no ceiling
 * @param retentionDays
 *            how many UTC days of buckets are kept, today included (at least 1)
 */
public record DocumentSpendLimits(OptionalDouble maxPerUserPerDayUsd, OptionalDouble maxPerTeamPerDayUsd, int retentionDays) {

    public static final String MAX_PER_USER_PER_DAY = "eddi.documents.spend.max-per-user-per-day-usd";
    public static final String MAX_PER_TEAM_PER_DAY = "eddi.documents.spend.max-per-team-per-day-usd";
    public static final String RETENTION_DAYS = "eddi.documents.spend.retention-days";

    /** The design's default retention (§14). */
    public static final int DEFAULT_RETENTION_DAYS = 35;

    private static final DocumentSpendLimits DEFAULTS = new DocumentSpendLimits(OptionalDouble.empty(), OptionalDouble.empty(),
            DEFAULT_RETENTION_DAYS);

    public DocumentSpendLimits {
        maxPerUserPerDayUsd = ceiling(maxPerUserPerDayUsd);
        maxPerTeamPerDayUsd = ceiling(maxPerTeamPerDayUsd);
        retentionDays = Math.max(1, retentionDays);
    }

    /** No ceiling, the default retention. */
    public static DocumentSpendLimits defaults() {
        return DEFAULTS;
    }

    /**
     * A negative or non-finite value means "no ceiling", as a negative
     * {@code maxMonthlyCostUsd} does for the tenant budget; {@code 0} refuses all
     * document work, priced or not (the spend so far already equals it).
     */
    private static OptionalDouble ceiling(OptionalDouble value) {
        if (value == null || value.isEmpty()) {
            return OptionalDouble.empty();
        }
        double usd = value.getAsDouble();
        return Double.isFinite(usd) && usd >= 0.0 ? value : OptionalDouble.empty();
    }
}
