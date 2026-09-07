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
     * {@code excludeHitlTimeouts} is part of the QUERY rather than something the
     * caller filters out afterwards, and that is the whole point of the parameter.
     * The REST layer redacts HITL approval-timeout schedules from non-admins; when
     * it did so after the page came back, {@code limit} and {@code offset} were
     * counted over rows the caller could not see. HITL timeouts are created
     * programmatically — one per paused conversation, in bursts, sorting
     * newest-first — so an editor's first page could legitimately come back short
     * or entirely empty while later pages held their own schedules, and a client
     * following the documented "a full page may be truncated, ask for the next one"
     * rule stopped paging and never saw them.
     *
     * @param limit
     *            maximum rows to return
     * @param offset
     *            rows to skip; 0 for the first page
     * @param excludeHitlTimeouts
     *            when true, rows whose metadata marks them as HITL approval
     *            timeouts are excluded by the query, so limit/offset apply to the
     *            visible set
     */
    List<ScheduleConfiguration> readAllSchedules(int limit, int offset, boolean excludeHitlTimeouts) throws IResourceStore.ResourceStoreException;

    List<ScheduleConfiguration> readSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException;

    /**
     * Paged, deterministically ordered variant — see
     * {@link #readAllSchedules(int, int, boolean)}.
     */
    List<ScheduleConfiguration> readSchedulesByAgentId(String agentId, int limit, int offset, boolean excludeHitlTimeouts)
            throws IResourceStore.ResourceStoreException;

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
     * The fire whose outcome an unfenced transition belongs to cannot be named, so
     * the write applies to whatever claim the row currently holds.
     * <p>
     * Only for callers that hold no claim at all and are not reporting the outcome
     * of a fire — {@code RestScheduleStore.dismissDeadLetter} is the one: it clears
     * a DEAD_LETTERED row, where by definition no fire is running. Every caller
     * that DID fire must pass its {@code fireId}; see
     * {@link #markCompleted(String, String, Instant)}.
     */
    String UNFENCED = null;

    /**
     * Mark a schedule fire as completed. Resets fire state and sets nextFire. If
     * nextFire is null (one-shot schedule), the schedule is disabled.
     *
     * @param expectedFireId
     *            the {@code fireId} of the claim this outcome belongs to (see
     *            {@link #fireIdOf}); the write is a no-op unless the row still
     *            holds exactly that claim. {@link #UNFENCED} skips the check.
     *            <p>
     *            Lease stealing is explicitly supported ({@link #tryClaim} reclaims
     *            a CLAIMED row whose lease expired), so a slow fire A and its
     *            replacement fire B can be in flight at once. Without the fence, A
     *            finishing afterwards writes over B's live claim by schedule id
     *            alone: the row goes back to PENDING with B still running, and the
     *            next poll fires a third copy into the same conversation. Fencing
     *            makes the stale write land nowhere, which is the correct outcome —
     *            B owns the row and will report its own result.
     */
    void markCompleted(String scheduleId, String expectedFireId, Instant nextFire) throws IResourceStore.ResourceStoreException;

    /** @see #markCompleted(String, String, Instant) */
    default void markCompleted(String scheduleId, Instant nextFire) throws IResourceStore.ResourceStoreException {
        markCompleted(scheduleId, UNFENCED, nextFire);
    }

    /**
     * Mark a schedule fire as failed. Increments failCount and sets nextRetryAt.
     *
     * @param expectedFireId
     *            fences the write to the claim that fired — see
     *            {@link #markCompleted(String, String, Instant)}. A stale failure
     *            that landed anyway would increment {@code failCount} against a
     *            healthy running fire and could dead-letter it.
     */
    void markFailed(String scheduleId, String expectedFireId, Instant nextRetryAt) throws IResourceStore.ResourceStoreException;

    /** @see #markFailed(String, String, Instant) */
    default void markFailed(String scheduleId, Instant nextRetryAt) throws IResourceStore.ResourceStoreException {
        markFailed(scheduleId, UNFENCED, nextRetryAt);
    }

    /**
     * Release the claim of a fire that was SKIPPED and re-arm the schedule at
     * {@code nextFire}.
     * <p>
     * Deliberately neither {@link #markCompleted} nor {@link #markFailed}. A
     * skipped turn is one the coordinator dropped without consuming the input,
     * because the conversation was busy or paused on a human approval — so:
     * <ul>
     * <li>it is not a completion: {@code lastFired} must not move and
     * {@code failCount} must not be CLEARED, or a schedule that alternates between
     * failing and being skipped could never reach {@code max-retries};</li>
     * <li>it is not a failure either: {@code failCount} must not be INCREMENTED, or
     * a persistent heartbeat is dead-lettered by any human pause longer than the
     * backoff budget (~21 minutes with the defaults) even though nothing about it
     * is broken.</li>
     * </ul>
     * So this writes exactly two things: the claim is released (fireStatus back to
     * PENDING, claim columns and fireId cleared) and {@code nextFire} is moved to
     * the next cadence. Everything about the retry state is left exactly as the
     * fire found it.
     *
     * @param expectedFireId
     *            fences the write to the claim that fired — see
     *            {@link #markCompleted(String, String, Instant)}
     * @param nextFire
     *            the next cadence, never null — a schedule with nothing to re-arm
     *            to (a one-shot) is not routed here; see
     *            {@code SchedulePollerService.onFireSkipped}
     */
    void markSkipped(String scheduleId, String expectedFireId, Instant nextFire) throws IResourceStore.ResourceStoreException;

    /** @see #markSkipped(String, String, Instant) */
    default void markSkipped(String scheduleId, Instant nextFire) throws IResourceStore.ResourceStoreException {
        markSkipped(scheduleId, UNFENCED, nextFire);
    }

    /**
     * Mark a schedule as dead-lettered (retries exhausted).
     *
     * @param expectedFireId
     *            fences the write to the claim that fired — see
     *            {@link #markCompleted(String, String, Instant)}. It is the other
     *            half of {@code markFailed}: a stale fire whose final failure lands
     *            on the retry ceiling must not be able to dead-letter a row that a
     *            replacement fire now owns, or fencing {@code markFailed} alone
     *            just moves the damage to the last attempt.
     */
    void markDeadLettered(String scheduleId, String expectedFireId) throws IResourceStore.ResourceStoreException;

    /** @see #markDeadLettered(String, String) */
    default void markDeadLettered(String scheduleId) throws IResourceStore.ResourceStoreException {
        markDeadLettered(scheduleId, UNFENCED);
    }

    /**
     * Re-queue a dead-lettered schedule for another attempt.
     */
    void requeueDeadLetter(String scheduleId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    // --- Fire Log ---

    /**
     * Record one fire attempt — <em>if</em> its schedule still exists.
     * <p>
     * A fire log carries the conversationId of the turn it started, and it is only
     * findable by its scheduleId, so a log whose schedule has been deleted is
     * personal data no erasure path can reach again. Implementations must therefore
     * make the write conditional on the schedule, so a fire that is in flight when
     * a cascade delete or GDPR erasure runs cannot commit its log afterwards.
     * Writing no row in that case is the correct outcome, not an error — the fire's
     * own result is unaffected.
     */
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
