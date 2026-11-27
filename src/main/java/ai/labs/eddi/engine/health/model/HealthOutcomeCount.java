/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;

/**
 * Sessions of one agent counted in the database, per combination of outcome,
 * evidence, engagement and provisional flag — and per day and group when asked.
 *
 * @param day
 *            the sessions' end day, {@code null} when not counted per day
 * @param group
 *            the version (as text) or origin, {@code null} when not grouped
 * @param outcome
 *            {@code null} for sessions of an origin that is not evaluated
 * @param turns
 *            the sessions' turns, summed
 */
public record HealthOutcomeCount(LocalDate day, String group, String outcome, String evidence, boolean engaged, boolean provisional,
        long sessions, long turns) {
}
