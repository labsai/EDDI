package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.IResourceStore;

import java.time.Instant;
import java.util.List;

/**
 * Store interface for schedule configurations and fire logs.
 * Implementations must provide atomic CAS (compare-and-swap) claiming
 * to ensure exactly-once execution in clustered deployments.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IScheduleStore {

    // --- CRUD ---

    String createSchedule(ScheduleConfiguration schedule) throws IResourceStore.ResourceStoreException;

    ScheduleConfiguration readSchedule(String scheduleId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateSchedule(String scheduleId, ScheduleConfiguration schedule)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /**
     * Atomic field-level update to enable/disable a schedule.
     * Avoids read-then-write races.
     *
     * @param scheduleId the schedule to update
     * @param enabled    new enabled state
     * @param nextFire   recomputed nextFire (only applied when enabling), may be null
     */
    void setScheduleEnabled(String scheduleId, boolean enabled, Instant nextFire)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void deleteSchedule(String scheduleId) throws IResourceStore.ResourceStoreException;

    /**
     * Delete all schedules for a bot (cascade delete).
     *
     * @return number of deleted schedules
     */
    int deleteSchedulesByBotId(String botId) throws IResourceStore.ResourceStoreException;

    List<ScheduleConfiguration> readAllSchedules(int limit) throws IResourceStore.ResourceStoreException;

    List<ScheduleConfiguration> readSchedulesByBotId(String botId) throws IResourceStore.ResourceStoreException;

    // --- Polling & Claiming ---

    /**
     * Find schedules that are due to fire.
     * Returns schedules where:
     * - enabled = true
     * - nextFire <= now
     * - fireStatus = PENDING, OR (fireStatus = CLAIMED AND claimedAt <= leaseExpiry)
     * - OR (fireStatus = FAILED AND nextRetryAt <= now AND failCount < maxRetries)
     *
     * @param now          current time
     * @param leaseExpiry  cutoff for expired leases (now - leaseTimeout)
     * @param maxRetries   maximum retry attempts before dead-lettering
     * @return list of due schedules
     */
    List<ScheduleConfiguration> findDueSchedules(Instant now, Instant leaseExpiry, int maxRetries)
            throws IResourceStore.ResourceStoreException;

    /**
     * Atomically claim a schedule for this instance.
     * Uses CAS (compare-and-swap): only succeeds if the schedule is still
     * in a claimable state (PENDING or retryable FAILED with retryAt <= now).
     *
     * @param scheduleId schedule to claim
     * @param instanceId this instance's unique identifier
     * @param now        current time
     * @return true if this instance successfully claimed the schedule
     */
    boolean tryClaim(String scheduleId, String instanceId, Instant now)
            throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule fire as completed. Resets fire state and sets nextFire.
     * If nextFire is null (one-shot schedule), the schedule is disabled.
     */
    void markCompleted(String scheduleId, Instant nextFire)
            throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule fire as failed. Increments failCount and sets nextRetryAt.
     */
    void markFailed(String scheduleId, Instant nextRetryAt)
            throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule as dead-lettered (retries exhausted).
     */
    void markDeadLettered(String scheduleId)
            throws IResourceStore.ResourceStoreException;

    /**
     * Re-queue a dead-lettered schedule for another attempt.
     */
    void requeueDeadLetter(String scheduleId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    // --- Fire Log ---

    void logFire(ScheduleFireLog fireLog) throws IResourceStore.ResourceStoreException;

    List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) throws IResourceStore.ResourceStoreException;

    List<ScheduleFireLog> readFailedFireLogs(int limit) throws IResourceStore.ResourceStoreException;
}
