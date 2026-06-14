/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;

/**
 * Pluggable executor for schedules that don't need an agent conversation.
 * <p>
 * When a schedule's {@link ScheduleConfiguration#getDirectExecutionType()} is
 * set, the {@link ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor}
 * dispatches directly to the matching {@code DirectScheduleExecutor} instead of
 * starting a conversation with an agent. This is useful for operational tasks
 * like ingestion, cleanup, and reindexing that should piggyback on the robust
 * scheduling infrastructure (cluster-safe claiming, cron, retries, fire logs)
 * without requiring a deployed agent.
 * <p>
 * Implementations are registered via CDI and discovered by their
 * {@link #getType()} return value.
 *
 * @since 6.0.3
 */
public interface DirectScheduleExecutor {

    /**
     * Returns the execution type identifier.
     * <p>
     * Must match the {@code directExecutionType} field on
     * {@link ScheduleConfiguration}. Examples: "ingestion", "cleanup".
     *
     * @return the execution type identifier
     */
    String getType();

    /**
     * Execute the scheduled task directly.
     * <p>
     * Implementations should:
     * <ol>
     * <li>Read any necessary configuration from {@code schedule.getMetadata()}</li>
     * <li>Perform the operation (ingest, cleanup, etc.)</li>
     * <li>Log progress via the injected Logger</li>
     * <li>Throw on failure (the scheduler will handle retries)</li>
     * </ol>
     *
     * @param schedule
     *            the schedule to execute
     * @throws Exception
     *             if execution fails
     */
    void execute(ScheduleConfiguration schedule) throws Exception;
}
