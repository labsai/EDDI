/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

/**
 * Who used an agent over a period, counted from the turn rows themselves —
 * distinct people and conversations cannot be added up from daily rows.
 * Discarded rows are left out. Until sessions exist (H3), a session is a
 * conversation.
 *
 * @param sessions
 *            distinct conversations with a turn in the period
 * @param engagedSessions
 *            those with a turn carrying a person's input after the agent's
 *            first output — judged on the conversation's turns inside the
 *            period
 * @param turns
 *            their turns
 * @param distinctUsers
 *            distinct user ids among them
 * @param returningUsers
 *            of those, the users who also had a turn in the look-back before
 *            the period (bounded by the turn-row retention)
 */
public record HealthUsage(long sessions, long engagedSessions, long turns, long distinctUsers, long returningUsers) {

    public static final HealthUsage EMPTY = new HealthUsage(0, 0, 0, 0, 0);
}
