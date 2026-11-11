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
 *            when it was last marked
 */
public record HealthDirtyDay(String agentId, LocalDate day, String token, Instant markedAt) {
}
