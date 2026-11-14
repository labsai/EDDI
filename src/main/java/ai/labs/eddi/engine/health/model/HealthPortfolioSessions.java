/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

/**
 * One agent's traffic for the portfolio, counted in one pass over the turn rows
 * of the baseline and the current window: distinct conversations with a turn in
 * each window, over the interactive origins and over every listed origin.
 * <p>
 * A count is {@code null} when it cannot be made: its window reaches past the
 * turn-row retention, whose rows are gone. A {@code 0} is a counted zero.
 */
public record HealthPortfolioSessions(Long interactiveNow, Long allNow, Long interactiveBefore, Long allBefore) {

    /** Counted, nothing found. */
    public static final HealthPortfolioSessions NONE = new HealthPortfolioSessions(0L, 0L, 0L, 0L);

    /** Neither window can be counted. */
    public static final HealthPortfolioSessions UNCOUNTED = new HealthPortfolioSessions(null, null, null, null);

    /** All four counted. */
    public HealthPortfolioSessions(long interactiveNow, long allNow, long interactiveBefore, long allBefore) {
        this(Long.valueOf(interactiveNow), Long.valueOf(allNow), Long.valueOf(interactiveBefore), Long.valueOf(allBefore));
    }

    /** These counts with the baseline marked as not countable. */
    public HealthPortfolioSessions withoutBaseline() {
        return new HealthPortfolioSessions(interactiveNow, allNow, null, null);
    }
}
