/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * What a delete of a conversation's turn rows removed.
 *
 * @param deleted
 *            the rows (and discard markers) deleted
 * @param agentDays
 *            the (agent, day) pairs of the deleted rows — whose daily rows must
 *            be recomputed — or {@code null} when they could not be read (the
 *            rows were deleted all the same)
 */
public record HealthDeletedRows(long deleted, Map<String, Set<LocalDate>> agentDays) {

    /** Whether the days of the deleted rows are known. */
    public boolean daysKnown() {
        return agentDays != null;
    }
}
