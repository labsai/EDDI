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
     * <p>
     * Enabling always clears the failure state — {@code fireStatus} back to
     * PENDING, {@code failCount} to 0, {@code nextRetryAt} cleared — whether or not
     * a {@code nextFire} is supplied. Gating that reset on a non-null nextFire left
     * a re-enabled schedule stuck in FAILED/DEAD_LETTERED and therefore
     * unclaimable.
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

    /**
     * Atomically record the conversation a {@code conversationStrategy=persistent}
     * schedule reuses across fires. Writes that ONE field and nothing else.
     * <p>
     * This exists because the fire path must never write a whole schedule back. The
     * object a fire holds is the copy {@link #findDueSchedules} returned
     * <em>before</em> {@link #tryClaim} ran, so its {@code fireStatus} is still
     * PENDING and its claim columns are still empty. Persisting it with
     * {@link #updateSchedule} un-claimed the row mid-fire, with {@code nextFire}
     * still in the past — the next poll (15s by default) then re-claimed and
     * re-fired a schedule that was still running, pushing a second turn into the
     * very same persistent conversation and billing the LLM twice.
     *
     * @param scheduleId
     *            the schedule to update
     * @param conversationId
     *            the persistent conversation id to store
     */
    void setPersistentConversationId(String scheduleId, String conversationId) throws IResourceStore.ResourceStoreException;

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
        List<ScheduleConfiguration> scanned = readAllSchedules(ERASURE_SCAN_LIMIT);
        for (ScheduleConfiguration schedule : scanned) {
            if (userId.equals(schedule.getUserId())) {
                deleteSchedule(schedule.getId());
                deleted++;
            }
        }
        // A full page means the scan hit its ceiling and there may be schedules it
        // never looked at. Returning a count here would report a complete erasure
        // that is not one — the worst outcome available, because the caller records
        // the request as satisfied and the remaining schedules keep firing under the
        // erased user's id. A backend holding more schedules than this must override
        // the method with an indexed delete (PostgresScheduleStore does).
        if (scanned.size() >= ERASURE_SCAN_LIMIT) {
            throw new IResourceStore.ResourceStoreException(
                    "Erasure incomplete: the portable scan reached its limit of " + ERASURE_SCAN_LIMIT
                            + " schedules, so schedules belonging to this user may remain. "
                            + "This backend must override deleteSchedulesByUserId with an indexed delete.");
        }
        return deleted;
    }

    /** Bound for the portable {@link #deleteSchedulesByUserId} scan. */
    int ERASURE_SCAN_LIMIT = 10_000;

    List<ScheduleConfiguration> readAllSchedules(int limit) throws IResourceStore.ResourceStoreException;

    /**
     * One page of schedules, ordered deterministically (newest created first, ties
     * broken by id) so that paging through them cannot skip or repeat a row.
     * <p>
     * Without an offset the listing surface was a single hard-capped page: a
     * deployment holding more schedules than the cap showed an arbitrary, unordered
     * subset, and the schedules outside it could not be found, disabled or deleted
     * through the list at all. HITL timeouts, per-user dream schedules and team
     * cadences are all created programmatically, so the cap is reachable without
     * anyone creating a schedule by hand.
     *
     * @param limit
     *            maximum rows to return
     * @param offset
     *            rows to skip; 0 for the first page
     */
    List<ScheduleConfiguration> readAllSchedules(int limit, int offset) throws IResourceStore.ResourceStoreException;

    List<ScheduleConfiguration> readSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException;

    /**
     * Paged, deterministically ordered variant — see
     * {@link #readAllSchedules(int, int)}.
     */
    List<ScheduleConfiguration> readSchedulesByAgentId(String agentId, int limit, int offset) throws IResourceStore.ResourceStoreException;

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
     * The idempotency key {@link #tryClaim} persists for a claim.
     * <p>
     * Single source of truth for the formula. {@code tryClaim} returns only a
     * boolean, so the poller has to reconstruct the value it just wrote in order to
     * correlate the fire log and the agent context with the claimed row — and it
     * did so by re-typing the expression, in a third place. Any edit to one copy
     * silently broke that correlation.
     */
    static String fireIdOf(String scheduleId, Instant claimedAt) {
        return scheduleId + "_" + claimedAt;
    }

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

    /**
     * Delete the fire logs of a schedule. Called whenever the schedule itself is
     * deleted, so a removed agent, an erased user or a resolved HITL pause does not
     * leave orphaned fire logs (each carrying a conversationId) behind forever.
     *
     * @return number of fire logs deleted
     */
    int deleteFireLogsByScheduleId(String scheduleId) throws IResourceStore.ResourceStoreException;

    /**
     * Delete fire logs that started before {@code cutoff} — the retention sweep.
     * <p>
     * Nothing used to prune this collection: a 60-second heartbeat writes ~525,600
     * rows a year, per schedule, and {@code readFailedFireLogs} then scans an
     * ever-growing table. AGENTS.md §4.7 ("Unbounded growth") asks for a
     * configurable cap; {@code eddi.schedule.fire-log-retention} is it.
     *
     * @return number of fire logs deleted
     */
    int deleteFireLogsOlderThan(Instant cutoff) throws IResourceStore.ResourceStoreException;
}
