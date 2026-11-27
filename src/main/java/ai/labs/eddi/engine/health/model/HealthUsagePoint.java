/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;

/**
 * {@link HealthUsage} of one day (and one version or origin, when grouped) — a
 * point of an adoption series. Engagement is judged on the conversation's turns
 * of that day.
 *
 * @param group
 *            the version (as text) or origin of the group, {@code null} when
 *            not grouped
 */
public record HealthUsagePoint(LocalDate day, String group, long sessions, long engagedSessions, long turns, long distinctUsers) {
}
