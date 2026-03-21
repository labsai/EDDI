package ai.labs.eddi.configs.schedule.model;

import java.time.Instant;

/**
 * Immutable log entry for a single schedule fire attempt.
 * <p>
 * Provides full observability: when it fired, how long it took,
 * what it cost, and which conversation was created.
 *
 * @param id              auto-generated UUID
 * @param scheduleId      FK to the schedule
 * @param fireId          idempotency key (scheduleId + fireTime)
 * @param fireTime        when the fire was due
 * @param startedAt       when execution started
 * @param completedAt     when execution completed (null if failed)
 * @param status          COMPLETED | FAILED | DEAD_LETTERED
 * @param instanceId      which cluster instance executed
 * @param conversationId  resulting conversation ID
 * @param errorMessage    null on success
 * @param attemptNumber   which retry attempt (1-based)
 * @param cost            LLM cost of this fire
 * @author ginccc
 * @since 6.0.0
 */
public record ScheduleFireLog(
        String id,
        String scheduleId,
        String fireId,
        Instant fireTime,
        Instant startedAt,
        Instant completedAt,
        String status,
        String instanceId,
        String conversationId,
        String errorMessage,
        int attemptNumber,
        double cost
) {
}
