/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.model;

import java.time.Instant;

/**
 * Immutable log entry for a single schedule fire attempt.
 * <p>
 * Provides full observability: when it fired, how long it took, what it cost,
 * and which conversation was created.
 *
 * @param id
 *            auto-generated UUID
 * @param scheduleId
 *            FK to the schedule
 * @param fireId
 *            idempotency key (scheduleId + fireTime)
 * @param fireTime
 *            when the fire was due
 * @param startedAt
 *            when execution started
 * @param completedAt
 *            when execution completed (null if failed)
 * @param status
 *            COMPLETED | FAILED | SKIPPED | DEAD_LETTERED. SKIPPED means the
 *            coordinator dropped the turn without consuming the input (the
 *            conversation was busy or awaiting a human): logged and counted,
 *            but never fed to the retry/dead-letter machine
 * @param instanceId
 *            which cluster instance executed
 * @param conversationId
 *            resulting conversation ID
 * @param errorMessage
 *            null on success
 * @param attemptNumber
 *            which retry attempt (1-based)
 * @param cost
 *            spend attributed to this fire, in USD. What it counts depends on
 *            the fire path, and the two are NOT the same quantity: a
 *            conversation fire reports the {@code ToolCostTracker} delta for
 *            the turn — <em>tool</em> spend only, so a schedule whose agent
 *            calls the LLM without tools reports {@code 0.0} — while the Dream
 *            consolidation fast-path reports its own estimated LLM cost.
 *            Compare like with like before drawing conclusions from a fire log.
 * @author ginccc
 * @since 6.0.0
 */
public record ScheduleFireLog(String id, String scheduleId, String fireId, Instant fireTime, Instant startedAt, Instant completedAt, String status,
        String instanceId, String conversationId, String errorMessage, int attemptNumber, double cost) {
}
