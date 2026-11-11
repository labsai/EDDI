/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One (agent, day) whose daily rows must be recomputed.
 *
 * @param token
 *            changes on every mark; the rollup clears the entry only while it
 *            still carries the token it read, so a mark that arrives while the
 *            day is being recomputed keeps the day dirty
 * @param markedAt
 *            when it was last marked — or deferred after a failed rollup, which
 *            moves it to the back of the queue
 * @param attempts
 *            rollups of the entry that failed since it was last marked
 */
public record HealthDirtyDay(String agentId, LocalDate day, String token, Instant markedAt, int attempts) {

    /** A freshly marked entry. */
    public HealthDirtyDay(String agentId, LocalDate day, String token, Instant markedAt) {
        this(agentId, day, token, markedAt, 0);
    }
}
