/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.LocalDate;

/**
 * Matched business events of one agent, counted per event type and evidence —
 * and per day when asked.
 *
 * @param day
 *            the UTC day, {@code null} when not counted per day
 * @param valueSum
 *            the sum of the events' {@code value}s
 * @param valued
 *            how many of them carried a value
 */
public record HealthEventCount(LocalDate day, String eventType, String evidence, long events, double valueSum, long valued) {
}
