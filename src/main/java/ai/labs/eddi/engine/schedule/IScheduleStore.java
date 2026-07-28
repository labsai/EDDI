/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.IResourceStore;

import java.time.Instant;
import java.util.List;

/**
 * Store interface for schedule configurations and fire logs. Implementations
 * must provide atomic CAS (compare-and-swap) claiming so exactly one instance
 * owns a schedule per fire. Note this yields <strong>at-least-once</strong>
 * delivery, not exactly-once: an expired lease may be stolen (see
 * {@link #tryClaim}) while a wedged original fire can still commit, so fire
 * targets must be idempotent.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IScheduleStore {

    // --- CRUD ---

    String createSchedule(ScheduleConfiguration schedule) throws IResourceStore.ResourceStoreException;

    ScheduleConfiguration readSchedule(String scheduleId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void updateSchedule(String scheduleId, ScheduleConfiguration schedule)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /**
     * Atomic field-level update to enable/disable a schedule. Avoids
     * read-then-write races.
     *
     * @param scheduleId
     *            the schedule to update
     * @param enabled
     *            new enabled state
     * @param nextFire
     *            recomputed nextFire (only applied when enabling), may be null
     */
    void setScheduleEnabled(String scheduleId, boolean enabled, Instant nextFire)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void deleteSchedule(String scheduleId) throws IResourceStore.ResourceStoreException;

    /**
     * Delete all schedules for a Agent (cascade delete).
     *
     * @return number of deleted schedules
     */
    int deleteSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException;

    /**
     * Delete all schedules with the given name. Used to clean up HITL timeout
     * schedules when a conversation is resumed or cancelled.
     *
     * @return number of deleted schedules
     */
    int deleteSchedulesByName(String name) throws IResourceStore.ResourceStoreException;

    /**
     * Delete all schedules owned by a user (GDPR Art. 17 erasure).
     * <p>
     * A schedule carries the {@code userId} it fires conversations as. Left behind,
     * it keeps starting new conversations under an erased user's identity —
     * recreating the very data the erasure just removed, indefinitely.
     * <p>
     * The default implementation is a portable scan-and-delete over
     * {@link #readAllSchedules}. It is correct only for a backend that actually
     * persists {@code userId} — Postgres did not, so the scan compared against a
     * column that did not exist and erased nothing while reporting success. A
     * backend must therefore either persist the field or override this method;
     * those with an index on {@code userId} should override it with a single bulk
     * delete regardless, since a deployment holding more schedules than the scan
     * limit would otherwise erase only part of the user's data. The scan is bounded
     * by {@link #ERASURE_SCAN_LIMIT} — an explicit number rather than a "no limit"
     * sentinel, because the backends disagree on what 0 means ({@code LIMIT 0}
     * returns nothing on PostgreSQL, everything on MongoDB), and an erasure that
     * silently deletes nothing is the worst possible failure here.
     *
     * @param userId
     *            the user whose schedules to delete
     * @return number of deleted schedules
     */
    default int deleteSchedulesByUserId(String userId) throws IResourceStore.ResourceStoreException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        int deleted = 0;
        for (ScheduleConfiguration schedule : readAllSchedules(ERASURE_SCAN_LIMIT)) {
            if (userId.equals(schedule.getUserId())) {
                deleteSchedule(schedule.getId());
                deleted++;
            }
        }
        return deleted;
    }

    /** Bound for the portable {@link #deleteSchedulesByUserId} scan. */
    int ERASURE_SCAN_LIMIT = 10_000;

    List<ScheduleConfiguration> readAllSchedules(int limit) throws IResourceStore.ResourceStoreException;

    List<ScheduleConfiguration> readSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException;

    // --- Polling & Claiming ---

    /**
     * Find schedules that are due to fire. Returns schedules where: - enabled =
     * true - nextFire <= now - fireStatus = PENDING, OR (fireStatus = CLAIMED AND
     * claimedAt <= leaseExpiry) - OR (fireStatus = FAILED AND nextRetryAt <= now
     * AND failCount < maxRetries)
     *
     * @param now
     *            current time
     * @param leaseExpiry
     *            cutoff for expired leases (now - leaseTimeout)
     * @param maxRetries
     *            maximum retry attempts before dead-lettering
     * @return list of due schedules
     */
    List<ScheduleConfiguration> findDueSchedules(Instant now, Instant leaseExpiry, int maxRetries) throws IResourceStore.ResourceStoreException;

    /**
     * Atomically claim a schedule for this instance. Uses CAS (compare-and-swap):
     * succeeds only if the schedule is still in a claimable state — PENDING, a
     * retryable FAILED (retryAt <= now), or a CLAIMED row whose lease has expired
     * (claimedAt <= leaseExpiry). The lease-expired CLAIMED case lets a crashed or
     * wedged instance's schedule be reclaimed; it MUST match what
     * {@link #findDueSchedules} returns, or such rows are fetched every poll but
     * never re-fired.
     *
     * @param scheduleId
     *            schedule to claim
     * @param instanceId
     *            this instance's unique identifier
     * @param now
     *            current time
     * @param leaseExpiry
     *            cutoff for stealing an expired lease (now - leaseTimeout); a
     *            CLAIMED row with claimedAt <= this is reclaimable
     * @return true if this instance successfully claimed the schedule
     */
    boolean tryClaim(String scheduleId, String instanceId, Instant now, Instant leaseExpiry) throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule fire as completed. Resets fire state and sets nextFire. If
     * nextFire is null (one-shot schedule), the schedule is disabled.
     */
    void markCompleted(String scheduleId, Instant nextFire) throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule fire as failed. Increments failCount and sets nextRetryAt.
     */
    void markFailed(String scheduleId, Instant nextRetryAt) throws IResourceStore.ResourceStoreException;

    /**
     * Mark a schedule as dead-lettered (retries exhausted).
     */
    void markDeadLettered(String scheduleId) throws IResourceStore.ResourceStoreException;

    /**
     * Re-queue a dead-lettered schedule for another attempt.
     */
    void requeueDeadLetter(String scheduleId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    // --- Fire Log ---

    void logFire(ScheduleFireLog fireLog) throws IResourceStore.ResourceStoreException;

    List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) throws IResourceStore.ResourceStoreException;

    List<ScheduleFireLog> readFailedFireLogs(int limit) throws IResourceStore.ResourceStoreException;
}
