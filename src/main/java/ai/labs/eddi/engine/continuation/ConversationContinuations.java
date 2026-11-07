/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingContinuation;
import ai.labs.eddi.engine.memory.model.PendingContinuationCandidate;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.security.ClientContextGuard;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Self-continuation: a turn that emits the reserved
 * {@link IConversation#CONTINUE_CONVERSATION} action queues the next turn of
 * the same conversation, without user input, once it has been committed. A job
 * of many model calls becomes many ordinary turns — each traced, audited,
 * bounded by the turn deadline and persisted — and its phases are behaviour
 * rules routing on a property. The conversation is the job log.
 * <p>
 * <b>Three steps, at three moments of a turn.</b>
 * <ol>
 * <li>{@link #recordContinuationTurn} — from the turn's completion callback,
 * before the persist, exactly where the {@code Idempotency-Key} is recorded: a
 * continuation turn marks its step with its number
 * ({@value #STEP_NUMBER}).</li>
 * <li>{@link #decide} — still before the persist: whether the turn asked to
 * continue and may. The answer is written to the step — the number it scheduled
 * ({@value #STEP_NEXT}) or why it did not ({@value #STEP_REFUSED}) — so it is
 * part of the committed turn.</li>
 * <li>{@link #armIfDecided} — only after the turn was committed: a one-shot
 * schedule on the cluster-aware schedule machinery, which the poller claims and
 * {@code ContinuationFireHandler} turns into a turn.</li>
 * </ol>
 * Nothing is armed for a turn that paused, failed, was cancelled, lost its
 * lease or could not be stored: those never reach step 3.
 * <p>
 * <b>Crash recovery (an outbox).</b> Step 2 also leaves a
 * {@link PendingContinuation} on the memory, and the snapshot conversion writes
 * it at the top level of the stored conversation in the same document write as
 * the step. A node that dies between the commit and step 3 therefore leaves the
 * decision behind, and {@link ContinuationRecoverySweep} arms it from there
 * ({@link #recover}). The schedule's id is derived from the conversation and
 * the continuation number ({@link #scheduleId}) and written with
 * {@link IScheduleStore#createScheduleIfAbsent}, so the original arm, a retry
 * of it and any number of sweeping nodes write one row between them. The record
 * is marked handled once its schedule is known to exist, removed when the
 * conversation is ended or cancelled ({@link #disarm}), and replaced by the
 * next committed turn, which derives it from its own step.
 * <p>
 * <b>Why a schedule and not the conversation coordinator's queue.</b> The
 * coordinator's queue is in memory: a continuation queued there dies with the
 * node. A one-shot schedule is a row in the database — it survives a restart,
 * exactly one node claims it (CAS, lease, fencing token), a failed fire is
 * retried with backoff and finally dead-lettered with its fire log, and the
 * turn it starts still goes through {@code say} and therefore through the
 * coordinator, the conversation lease, quotas and audit like any other. The
 * price is the poll interval ({@code eddi.schedule.poll-interval}, 15 s)
 * between two continuation turns.
 * <p>
 * <b>Bounds.</b> {@code maxContinuations} counts over the whole conversation:
 * the number of steps that carry {@value #STEP_NUMBER}. A turn that asks once
 * the count is reached is refused ({@code limit}). The conversation's cost
 * ceiling ({@code maxCostPerConversationUsd}) is checked before anything is
 * armed and again by every model call of the continuation turns, because it
 * sums {@code audit:cost} over every step.
 * <p>
 * Stateless; everything per conversation lives in its memory.
 */
@ApplicationScoped
public class ConversationContinuations {

    private static final Logger LOGGER = Logger.getLogger(ConversationContinuations.class);

    /** Attempts at writing a continuation's schedule after its turn committed. */
    static final int ARM_ATTEMPTS = 3;
    /** Pause before each retry of that write, times the attempt number. */
    static final long ARM_RETRY_BACKOFF_MS = 100;

    /** Step key: the number of the continuation this turn ran as. */
    public static final String STEP_NUMBER = "continuation:number";
    /** Step key: the number of the continuation this turn asked for and got. */
    public static final String STEP_NEXT = "continuation:next";
    /** Step key: why this turn's {@code CONTINUE_CONVERSATION} was ignored. */
    public static final String STEP_REFUSED = "continuation:refused";

    /** Context key of a continuation turn, reserved for the engine. */
    public static final String CONTEXT_KEY = ClientContextGuard.KEY_SELF_CONTINUATION;

    /** Metadata key marking a continuation schedule. */
    public static final String METADATA_TYPE_KEY = "continuationType";
    /** Metadata value of {@link #METADATA_TYPE_KEY}. */
    public static final String METADATA_TYPE_VALUE = "conversation_continuation";
    /** Metadata key: the conversation to continue. */
    public static final String METADATA_CONVERSATION_ID_KEY = "conversationId";
    /** Metadata key: the number of the continuation the schedule runs. */
    public static final String METADATA_NUMBER_KEY = "continuationNumber";
    /** Metadata key: the agent's {@code maxContinuations} when it was armed. */
    public static final String METADATA_MAX_KEY = "maxContinuations";

    private static final String NAME_PREFIX = "continuation-";

    static final String METRIC_SCHEDULED = "eddi.continuation.scheduled";
    static final String METRIC_RUN = "eddi.continuation.run";
    static final String METRIC_REFUSED = "eddi.continuation.refused";
    static final String METRIC_RECOVERED = "eddi.continuation.recovered";

    /** The agent does not set {@code maxContinuations}. */
    public static final String REASON_DISABLED = "disabled";
    /** The conversation has used its {@code maxContinuations}. */
    public static final String REASON_LIMIT = "limit";
    /** The conversation's cost ceiling is reached. */
    public static final String REASON_COST_CEILING = "cost_ceiling";
    /** The turn did not settle READY (failed, ended, interrupted). */
    public static final String REASON_NOT_SETTLED = "not_settled";
    /** A group member's turn: the group orchestrates its members' turns. */
    public static final String REASON_GROUP_MEMBER = "group_member";
    /** The schedule could not be written. */
    public static final String REASON_SCHEDULE_FAILED = "schedule_failed";
    /** At fire time: the conversation ended in between. */
    public static final String REASON_ENDED = "ended";
    /** At fire time: the conversation is waiting for a human decision. */
    public static final String REASON_AWAITING_HUMAN = "awaiting_human";
    /** At fire time: the conversation no longer exists (deleted, erased). */
    public static final String REASON_GONE = "gone";
    /** At fire time: GDPR Art. 18 restriction of processing. */
    public static final String REASON_RESTRICTED = "restricted";
    /** At fire time: the agent version is not deployed (retried). */
    public static final String REASON_UNDEPLOYED = "undeployed";
    /**
     * The continuation turn was interrupted (cancel, watchdog): the chain stops.
     */
    public static final String REASON_INTERRUPTED = "interrupted";
    /**
     * At fire time: the schedule's {@code userId} is not the conversation's owner.
     * The engine stamps the owner on every continuation it arms, so a mismatch is a
     * schedule the engine did not mint for this conversation; it never runs.
     */
    public static final String REASON_OWNER_MISMATCH = "owner_mismatch";
    /** At fire time: this continuation already ran (re-delivered schedule). */
    public static final String REASON_DUPLICATE = "duplicate";

    private final IScheduleStore scheduleStore;
    /**
     * Where the outbox record is cleared; {@code null} in tests that do not care.
     */
    private final IConversationMemoryStore memoryStore;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Inject
    public ConversationContinuations(IScheduleStore scheduleStore, IConversationMemoryStore memoryStore, MeterRegistry meterRegistry) {
        this(scheduleStore, memoryStore, meterRegistry, Clock.systemUTC());
    }

    /** Without the memory store: the outbox record is never cleared (tests). */
    public ConversationContinuations(IScheduleStore scheduleStore, MeterRegistry meterRegistry) {
        this(scheduleStore, null, meterRegistry, Clock.systemUTC());
    }

    ConversationContinuations(IScheduleStore scheduleStore, MeterRegistry meterRegistry, Clock clock) {
        this(scheduleStore, null, meterRegistry, clock);
    }

    ConversationContinuations(IScheduleStore scheduleStore, IConversationMemoryStore memoryStore, MeterRegistry meterRegistry, Clock clock) {
        this.scheduleStore = scheduleStore;
        this.memoryStore = memoryStore;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // --- step 1: the continuation turn marks itself
    // -------------------------------

    /**
     * Marks the current step as continuation {@code inputData.continuationNumber} —
     * called before the turn is persisted, and only for a turn that settled (a
     * failed or interrupted one is not marked, so its retry runs it again). The
     * marker is written by the engine, never taken from client data: it is what
     * makes a re-delivered continuation a no-op and what {@code maxContinuations}
     * counts.
     */
    public static void recordContinuationTurn(IWritableConversationStep step, InputData inputData) {
        if (step == null || inputData == null || inputData.getContinuationNumber() == null) {
            return;
        }
        var marker = new Data<Object>(STEP_NUMBER, inputData.getContinuationNumber());
        marker.setPublic(false);
        step.storeData(marker);
    }

    // --- step 2: decide, before the persist
    // ---------------------------------------

    /**
     * Whether the turn that just ran asked to continue, and may. Writes the outcome
     * to the current step — {@value #STEP_NEXT} with the number to run, or
     * {@value #STEP_REFUSED} with the reason — so it is stored with the turn. A
     * refusal is logged at WARN and counted as
     * {@code eddi.continuation.refused{reason}}.
     * <p>
     * An approved continuation is also recorded on the memory as its
     * {@link PendingContinuation} — the outbox record that is stored with the turn
     * and that the recovery sweep arms from if this node never reaches
     * {@link #armIfDecided}.
     *
     * @param environment
     *            the environment the turn runs in, recorded for a recovered arm
     * @return the number of the continuation to arm after the commit, or
     *         {@code null} when there is none
     */
    public Integer decide(IConversationMemory memory, Environment environment) {
        IWritableConversationStep step = memory != null ? memory.getCurrentStep() : null;
        if (memory != null) {
            // Never carry a record decided by an earlier step into this turn's write.
            memory.setPendingContinuation(null);
        }
        if (step == null || !requested(step)) {
            return null;
        }
        ContinuationPolicy policy = memory.getContinuationPolicy();
        int used = usedContinuations(memory);
        if (memory.isCancelled() && policy != null && policy.enabled()) {
            // A cancel that reached the turn after its pipeline finished: the job is
            // stopped, so its decision must not reach the store, where the recovery
            // sweep would arm it.
            var refused = new Data<Object>(STEP_REFUSED, REASON_INTERRUPTED);
            refused.setPublic(false);
            step.storeData(refused);
            refuse(REASON_INTERRUPTED, memory.getConversationId(), "the turn was cancelled before it was stored");
            return null;
        }
        String reason = refusalReason(memory, step, policy, used);
        if (reason != null) {
            var refused = new Data<Object>(STEP_REFUSED, reason);
            refused.setPublic(false);
            step.storeData(refused);
            refuse(reason, memory.getConversationId(), detail(reason, policy, used));
            return null;
        }
        int next = used + 1;
        var scheduled = new Data<Object>(STEP_NEXT, next);
        scheduled.setPublic(false);
        step.storeData(scheduled);
        memory.setPendingContinuation(new PendingContinuation(next, policy.maxContinuations(), policy.input(),
                environment != null ? environment.toString() : null, clock.millis()));
        return next;
    }

    /** {@link #decide(IConversationMemory, Environment)} without an environment. */
    public Integer decide(IConversationMemory memory) {
        return decide(memory, null);
    }

    /**
     * The outbox record to store with the memory's current step: the one
     * {@link #decide} recorded while the step still carries the matching
     * {@value #STEP_NEXT}, {@code null} when the step did not decide to continue.
     * Called by the snapshot conversion on every write, which is what removes a
     * record once a later step is committed.
     * <p>
     * A memory loaded without the record (a projection that left it out) whose
     * current step did decide is given one rebuilt from the agent's policy, so a
     * re-store of that step never drops a decision the sweep could still arm.
     */
    public static PendingContinuation pendingOf(IConversationMemory memory) {
        IConversationStep step = memory != null ? memory.getCurrentStep() : null;
        IData<Object> next = step != null ? step.getData(STEP_NEXT) : null;
        if (next == null || !(next.getResult() instanceof Number number)) {
            return null;
        }
        PendingContinuation recorded = memory.getPendingContinuation();
        if (recorded != null && recorded.getNumber() != null && recorded.getNumber() == number.intValue()) {
            return recorded;
        }
        ContinuationPolicy policy = memory.getContinuationPolicy();
        if (policy == null || !policy.enabled()) {
            return null;
        }
        long decidedAt = next.getTimestamp() != null ? next.getTimestamp().getTime() : System.currentTimeMillis();
        return new PendingContinuation(number.intValue(), policy.maxContinuations(), policy.input(), null, decidedAt);
    }

    private static String refusalReason(IConversationMemory memory, IConversationStep step, ContinuationPolicy policy, int used) {
        if (policy == null || !policy.enabled()) {
            return REASON_DISABLED;
        }
        if (memory.getConversationState() != ConversationState.READY) {
            return REASON_NOT_SETTLED;
        }
        if (step.getData(MemoryKeys.CONTEXT_PREFIX + ":" + ClientContextGuard.KEY_GROUP_CONVERSATION_ID) != null) {
            return REASON_GROUP_MEMBER;
        }
        if (used >= policy.maxContinuations()) {
            return REASON_LIMIT;
        }
        OptionalDouble ceiling = memory.getConversationCostCeilingUsd();
        if (ceiling != null && ceiling.isPresent() && Double.isFinite(ceiling.getAsDouble()) && ceiling.getAsDouble() >= 0
                && conversationCost(memory) >= ceiling.getAsDouble()) {
            return REASON_COST_CEILING;
        }
        return null;
    }

    private static String detail(String reason, ContinuationPolicy policy, int used) {
        return switch (reason) {
            case REASON_DISABLED -> "the agent does not set maxContinuations";
            case REASON_LIMIT -> "the conversation has used " + used + " of its " + policy.maxContinuations() + " continuations";
            case REASON_COST_CEILING -> "the conversation's cost ceiling (maxCostPerConversationUsd) is reached";
            case REASON_NOT_SETTLED -> "the turn did not settle READY";
            case REASON_GROUP_MEMBER -> "a group member's turn cannot continue itself; the group orchestrates its members";
            default -> reason;
        };
    }

    /** Whether the current step's actions contain the reserved action. */
    static boolean requested(IConversationStep step) {
        IData<List<String>> actions = step.getLatestData(MemoryKeys.ACTIONS);
        List<String> result = actions != null ? actions.getResult() : null;
        return result != null && result.contains(IConversation.CONTINUE_CONVERSATION);
    }

    /**
     * How many continuation turns the conversation has run: steps carrying the
     * marker.
     */
    public static int usedContinuations(IConversationMemory memory) {
        var steps = memory.getAllSteps();
        if (steps == null) {
            return 0;
        }
        int count = 0;
        for (IData<Object> marker : steps.<Object>getExactDataPerStep(STEP_NUMBER)) {
            if (marker != null && marker.getResult() instanceof Number) {
                count++;
            }
        }
        return count;
    }

    /**
     * The sum of every step's {@code audit:cost} — what the cost ceiling compares.
     */
    static double conversationCost(IConversationMemory memory) {
        var steps = memory.getAllSteps();
        if (steps == null) {
            return 0.0;
        }
        double total = 0.0;
        for (IData<Object> cost : steps.<Object>getExactDataPerStep(MemoryKeys.AUDIT_COST)) {
            if (cost != null && cost.getResult() instanceof Number n && Double.isFinite(n.doubleValue())) {
                total += n.doubleValue();
            }
        }
        return total;
    }

    // --- step 3: arm, after the commit
    // --------------------------------------------

    /**
     * Arms the continuation {@link #decide} wrote to the current step — called only
     * once the turn has been committed. A one-shot schedule due now: the next poll
     * claims it. Never throws: a schedule that cannot be written is logged and
     * counted ({@code schedule_failed}); the committed turn stands.
     * <p>
     * <b>Spent rows.</b> A fired one-shot is disabled, not deleted, so every
     * continuation that ran would stay behind under the conversation's schedule
     * name, with its fire log. Before writing the next schedule — and on the last
     * continuation turn of a job, which writes none — the used-up ones are pruned
     * ({@link IScheduleStore#deleteUsedUpOneShotsByName}). The row of the
     * continuation that is running now is still claimed and is kept, so a
     * conversation holds at most its latest spent continuation and the pending one.
     * A prune that fails is logged and never stops the arm.
     *
     * @return whether a schedule was written
     */
    public boolean armIfDecided(String conversationId, IConversationMemory memory, Environment environment) {
        IWritableConversationStep step = memory != null ? memory.getCurrentStep() : null;
        if (conversationId == null || step == null) {
            return false;
        }
        IData<Object> next = step.getData(STEP_NEXT);
        if (next == null || !(next.getResult() instanceof Number number)) {
            if (step.getData(STEP_NUMBER) != null) {
                // The last continuation turn of a job arms nothing: prune here, or the
                // continuation before it would stay behind for good.
                pruneUsedUp(conversationId);
            }
            return false;
        }
        ContinuationPolicy policy = memory.getContinuationPolicy();
        if (memory.isCancelled()) {
            // A cancel that signalled this turn found no schedule to withdraw yet; the
            // job it cancelled must not queue its next turn now — nor leave the stored
            // outbox record behind for the recovery sweep to arm.
            clearPending(conversationId, number.intValue());
            refuse(REASON_INTERRUPTED, conversationId, "the turn was cancelled before its continuation was armed");
            return false;
        }
        pruneUsedUp(conversationId);
        try {
            var schedule = schedule(conversationId, memory.getAgentId(), memory.getUserId(),
                    environment != null ? environment.toString() : null, number.intValue(), policy.maxContinuations(), policy.input());
            PendingContinuation recorded = memory.getPendingContinuation();
            createWithRetry(schedule, recorded != null && recorded.getDecidedAt() != null ? recorded.getDecidedAt() : clock.millis());
            if (memory.isCancelled()) {
                // The cancel landed while the schedule was being written: its disarm
                // ran before the row existed, so withdraw the row here.
                disarm(conversationId);
                refuse(REASON_INTERRUPTED, conversationId, "the turn was cancelled while its continuation was armed");
                return false;
            }
            meterRegistry.counter(METRIC_SCHEDULED).increment();
            LOGGER.infof("Continuation %d of %d scheduled for conversation %s", number.intValue(), policy.maxContinuations(),
                    sanitize(conversationId));
            return true;
        } catch (Exception e) {
            refuse(REASON_SCHEDULE_FAILED, conversationId, "the schedule could not be written: " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the conversation's used-up continuation schedules, with their fire
     * logs. Never throws: a failure is logged and the arm goes on.
     */
    private void pruneUsedUp(String conversationId) {
        try {
            scheduleStore.deleteUsedUpOneShotsByName(scheduleName(conversationId));
        } catch (Exception e) {
            LOGGER.warnf("Could not prune the used-up continuations of conversation %s: %s", sanitize(conversationId), e.toString());
        }
    }

    /**
     * The one-shot schedule of continuation {@code number}, due now, under its
     * derived id ({@link #scheduleId}).
     */
    private ScheduleConfiguration schedule(String conversationId, String agentId, String userId, String environment, int number,
                                           Integer max, String input) {
        Instant now = clock.instant();
        var schedule = new ScheduleConfiguration();
        schedule.setId(scheduleId(conversationId, number));
        schedule.setName(scheduleName(conversationId));
        schedule.setAgentId(agentId);
        schedule.setEnvironment(environment);
        // Erasure deletes a user's schedules by userId: a pending continuation of an
        // erased user goes with them.
        schedule.setUserId(userId);
        schedule.setMessage(input != null ? input : "");
        schedule.setOneTimeAt(now.toString());
        schedule.setNextFire(now);
        schedule.setEnabled(true);
        schedule.setCreatedAt(now);
        schedule.setMetadata(metadata(conversationId, number, max != null ? max : 0));
        return schedule;
    }

    /**
     * The id of continuation {@code number}'s schedule: a name-based UUID of the
     * conversation and the number. Every writer of that continuation derives the
     * same id, and {@link IScheduleStore#createScheduleIfAbsent} lets only the
     * first one write the row.
     */
    public static String scheduleId(String conversationId, int number) {
        return UUID.nameUUIDFromBytes((NAME_PREFIX + conversationId + ":" + number).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Writes the schedule, retrying a failed write a few times with a short pause.
     * The turn is already committed with its decision, so a write that fails for a
     * moment would otherwise stop the job; a write that keeps failing is reported
     * by the caller as {@code schedule_failed}. A row that is already there — the
     * sweep was faster, or an attempt that reported a failure did land — counts as
     * written: it is the same continuation.
     */
    private void createWithRetry(ScheduleConfiguration schedule, long decidedAt) throws Exception {
        for (int attempt = 1;; attempt++) {
            try {
                writeIfAbsent(schedule, decidedAt);
                return;
            } catch (Exception e) {
                if (attempt >= ARM_ATTEMPTS) {
                    throw e;
                }
                LOGGER.warnf("Continuation schedule write failed (attempt %d of %d), retrying: %s", attempt, ARM_ATTEMPTS, e.toString());
                try {
                    Thread.sleep(ARM_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Writes the continuation's schedule under its derived id unless a live one is
     * there. A row with the same id that is <em>not</em> live — dead-lettered, or
     * spent by a fire that did not run the turn (the conversation was paused) — and
     * that predates this decision belongs to an earlier attempt at the same number:
     * the new decision replaces it, exactly as it would have written a fresh row
     * before ids were derived. A row created after the decision (the original arm
     * of the very decision the sweep is recovering) is never replaced.
     *
     * @return whether this call wrote the row
     */
    private boolean writeIfAbsent(ScheduleConfiguration schedule, long decidedAt) throws Exception {
        if (scheduleStore.createScheduleIfAbsent(schedule)) {
            return true;
        }
        ScheduleConfiguration existing;
        try {
            existing = scheduleStore.readSchedule(schedule.getId());
        } catch (IResourceStore.ResourceNotFoundException gone) {
            return scheduleStore.createScheduleIfAbsent(schedule);
        }
        if (existing == null || isLive(existing) || existing.getCreatedAt() == null
                || !existing.getCreatedAt().isBefore(Instant.ofEpochMilli(decidedAt))) {
            return false;
        }
        LOGGER.infof("Replacing the spent schedule of continuation %s (status %s) with the new decision", sanitize(schedule.getId()),
                existing.getFireStatus());
        scheduleStore.deleteSchedule(existing.getId());
        return scheduleStore.createScheduleIfAbsent(schedule);
    }

    /**
     * Enabled and not dead-lettered: the schedule will still fire (or is firing).
     */
    private static boolean isLive(ScheduleConfiguration schedule) {
        return schedule.isEnabled() && schedule.getFireStatus() != FireStatus.DEAD_LETTERED;
    }

    public static Map<String, Object> metadata(String conversationId, int number, int max) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_TYPE_KEY, METADATA_TYPE_VALUE);
        metadata.put(METADATA_CONVERSATION_ID_KEY, conversationId);
        metadata.put(METADATA_NUMBER_KEY, number);
        metadata.put(METADATA_MAX_KEY, max);
        return metadata;
    }

    /**
     * Deletes every continuation schedule of the conversation — on end and cancel,
     * so a stopped job does not start again. Never throws.
     * <p>
     * A continuation that already ran stays behind as a disabled one-shot under the
     * same name ({@code markCompleted} disables a fired one-shot rather than
     * deleting it), so the rows deleted here are not what was pending. The count
     * returned is the pending ones only: a cancel of a job that has finished must
     * find nothing to cancel, not report the job's spent schedules as cancelled.
     *
     * @return how many pending continuations were withdrawn
     */
    public int disarm(String conversationId) {
        if (conversationId == null || scheduleStore == null) {
            return 0;
        }
        // The outbox record goes FIRST: a sweep that armed from it a moment ago either
        // wrote its row before this point (the delete below takes it) or finds the
        // record gone when it confirms, and withdraws its own row (see recover).
        boolean recordCleared = clearPending(conversationId, null);
        String name = scheduleName(conversationId);
        // The count is only for the report; if it fails the delete must still run, or a
        // cancelled job stays armed. An unknown count reports 0 withdrawn, never the
        // deleted-row count, which includes spent one-shots.
        int pending = 0;
        try {
            pending = scheduleStore.countPendingSchedulesByName(name);
        } catch (Exception e) {
            LOGGER.warnf("Could not count the pending continuation of conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
        try {
            int deleted = scheduleStore.deleteSchedulesByName(name);
            int withdrawn = Math.min(pending, deleted);
            if (withdrawn == 0 && recordCleared) {
                // A decided continuation whose arm was lost to a crash is still pending:
                // the recovery sweep would have armed it. Withdrawing it is withdrawing one.
                withdrawn = 1;
            }
            if (withdrawn > 0) {
                LOGGER.infof("Withdrew %d pending continuation(s) of conversation %s", withdrawn, sanitize(conversationId));
            }
            return withdrawn;
        } catch (Exception e) {
            LOGGER.warnf("Could not withdraw the pending continuation of conversation %s: %s", sanitize(conversationId), e.getMessage());
            return 0;
        }
    }

    /**
     * Removes the stored outbox record. Never throws: a record that could not be
     * removed is armed by the sweep later only if the conversation is still READY.
     *
     * @return whether a record was removed
     */
    private boolean clearPending(String conversationId, Integer expectedNumber) {
        if (memoryStore == null) {
            return false;
        }
        try {
            return memoryStore.clearPendingContinuation(conversationId, expectedNumber);
        } catch (Exception e) {
            LOGGER.warnf("Could not clear the pending continuation record of conversation %s: %s", sanitize(conversationId), e.toString());
            return false;
        }
    }

    // --- crash recovery: arm from the outbox
    // --------------------------------------

    /** What {@link #recover} did with a candidate. */
    public enum Recovery {
        /** The continuation's schedule was missing and has been written. */
        RECOVERED,
        /** The schedule existed: the original arm (or another sweep) wrote it. */
        ALREADY_ARMED,
        /** The conversation is no longer READY (ended, paused): nothing armed. */
        NOT_READY,
        /**
         * Between the read and the arm the record was removed or replaced (a cancel, an
         * end, a newer turn): the row this sweep wrote was withdrawn.
         */
        SUPERSEDED
    }

    /**
     * Arms the continuation a stored outbox record names, if its schedule does not
     * exist — the recovery of a turn whose node died between its commit and
     * {@link #armIfDecided}. Called by {@link ContinuationRecoverySweep} for
     * records older than its grace period.
     * <ol>
     * <li>A conversation that is not READY any more (ended, paused for a human) is
     * not revived: its record is removed.</li>
     * <li>The schedule is written under its derived id with
     * {@link IScheduleStore#createScheduleIfAbsent}: when the original arm or
     * another node already wrote it, nothing is written.</li>
     * <li>The record is then marked handled
     * ({@link IConversationMemoryStore#markPendingContinuationHandled}), which
     * takes it out of the sweep's index. Sweeps only ever mark it; only a cancel,
     * an end or a newer turn removes or replaces it. So when the record no longer
     * names this continuation, the continuation was withdrawn or superseded while
     * this ran, and a row this call wrote is withdrawn again. A cancel removes the
     * record before it deletes the rows, so between them every interleaving ends
     * without a live row; two sweeps write one row and both keep it.</li>
     * </ol>
     * The decision itself — {@code maxContinuations}, the cost ceiling, a group
     * member's turn — was taken by the turn and is not taken again; everything the
     * fire checks (a duplicate, the owner, an ended, paused, erased or restricted
     * conversation) still applies when the recovered schedule fires.
     *
     * @throws Exception
     *             when the schedule store or the memory store fails; the record
     *             stays and the next sweep tries again
     */
    public Recovery recover(PendingContinuationCandidate candidate) throws Exception {
        String conversationId = candidate.conversationId();
        PendingContinuation pending = candidate.pending();
        int number = pending.getNumber();
        if (candidate.state() != ConversationState.READY) {
            memoryStore.clearPendingContinuation(conversationId, number);
            LOGGER.infof("Pending continuation %d of conversation %s dropped: the conversation is %s", number, sanitize(conversationId),
                    candidate.state());
            return Recovery.NOT_READY;
        }
        var schedule = schedule(conversationId, candidate.agentId(), candidate.userId(), pending.getEnvironment(), number,
                pending.getMaxContinuations(), pending.getInput());
        boolean written = writeIfAbsent(schedule, pending.getDecidedAt() != null ? pending.getDecidedAt() : 0L);
        // Marks, never removes: another sweep that finds the row this one wrote marks
        // the record too, and must not look like a cancel to this one.
        boolean stillPending = memoryStore.markPendingContinuationHandled(conversationId, number);
        if (written && !stillPending) {
            scheduleStore.deleteSchedule(schedule.getId());
            LOGGER.infof("Recovered continuation %d of conversation %s withdrawn: the conversation moved on meanwhile", number,
                    sanitize(conversationId));
            return Recovery.SUPERSEDED;
        }
        if (!written) {
            return Recovery.ALREADY_ARMED;
        }
        meterRegistry.counter(METRIC_RECOVERED).increment();
        LOGGER.warnf("Continuation %d of conversation %s was decided but never armed (the node stopped after the commit); armed it now",
                number, sanitize(conversationId));
        return Recovery.RECOVERED;
    }

    // --- shared with the fire handler
    // ---------------------------------------------

    /** The name of every continuation schedule of a conversation. */
    public static String scheduleName(String conversationId) {
        return NAME_PREFIX + conversationId;
    }

    /** Whether the schedule metadata marks a continuation. */
    public static boolean isContinuationSchedule(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_VALUE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * Whether a step of the stored conversation already ran continuation
     * {@code number} — the once-only check of a fire. Every step is scanned, not
     * only the newest ones: unlike an {@code Idempotency-Key}, the marker never
     * expires.
     */
    public static boolean ranIn(ConversationMemorySnapshot snapshot, int number) {
        List<ConversationStepSnapshot> steps = snapshot != null ? snapshot.getConversationSteps() : null;
        if (steps == null) {
            return false;
        }
        for (ConversationStepSnapshot step : steps) {
            if (step == null || step.getWorkflows() == null) {
                continue;
            }
            for (WorkflowRunSnapshot workflow : step.getWorkflows()) {
                if (workflow == null || workflow.getLifecycleTasks() == null) {
                    continue;
                }
                for (ResultSnapshot result : workflow.getLifecycleTasks()) {
                    if (result != null && STEP_NUMBER.equals(result.getKey()) && result.getResult() instanceof Number n
                            && n.intValue() == number) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Count a continuation turn that ran. */
    void countRun() {
        meterRegistry.counter(METRIC_RUN).increment();
    }

    /** Count and log a continuation that was not scheduled or not run. */
    void refuse(String reason, String conversationId, String detail) {
        meterRegistry.counter(METRIC_REFUSED, "reason", reason).increment();
        LOGGER.warnf("CONTINUE_CONVERSATION ignored for conversation %s (%s): %s", sanitize(conversationId), reason, detail);
    }
}
