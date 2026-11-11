/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

/**
 * One agent's traffic for the portfolio, counted in one pass over the turn rows
 * of the baseline and the current window: distinct conversations with a turn in
 * each window, over the interactive origins and over every listed origin.
 */
public record HealthPortfolioSessions(long interactiveNow, long allNow, long interactiveBefore, long allBefore) {

    public static final HealthPortfolioSessions NONE = new HealthPortfolioSessions(0, 0, 0, 0);
}
