/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
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
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.ClientContextGuard;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

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
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Inject
    public ConversationContinuations(IScheduleStore scheduleStore, MeterRegistry meterRegistry) {
        this(scheduleStore, meterRegistry, Clock.systemUTC());
    }

    ConversationContinuations(IScheduleStore scheduleStore, MeterRegistry meterRegistry, Clock clock) {
        this.scheduleStore = scheduleStore;
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
     *
     * @return the number of the continuation to arm after the commit, or
     *         {@code null} when there is none
     */
    public Integer decide(IConversationMemory memory) {
        IWritableConversationStep step = memory != null ? memory.getCurrentStep() : null;
        if (step == null || !requested(step)) {
            return null;
        }
        ContinuationPolicy policy = memory.getContinuationPolicy();
        int used = usedContinuations(memory);
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
        return next;
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
            // job it cancelled must not queue its next turn now.
            refuse(REASON_INTERRUPTED, conversationId, "the turn was cancelled before its continuation was armed");
            return false;
        }
        pruneUsedUp(conversationId);
        try {
            Instant now = clock.instant();
            var schedule = new ScheduleConfiguration();
            schedule.setName(scheduleName(conversationId));
            schedule.setAgentId(memory.getAgentId());
            schedule.setEnvironment(environment != null ? environment.toString() : null);
            // Erasure deletes a user's schedules by userId: a pending continuation of an
            // erased user goes with them.
            schedule.setUserId(memory.getUserId());
            schedule.setMessage(policy.input());
            schedule.setOneTimeAt(now.toString());
            schedule.setNextFire(now);
            schedule.setEnabled(true);
            schedule.setCreatedAt(now);
            schedule.setMetadata(metadata(conversationId, number.intValue(), policy.maxContinuations()));
            createWithRetry(schedule);
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
     * Writes the schedule, retrying a failed write a few times with a short pause.
     * The turn is already committed with its decision, so a write that fails for a
     * moment would otherwise stop the job; a write that keeps failing is reported
     * by the caller as {@code schedule_failed}.
     */
    private void createWithRetry(ScheduleConfiguration schedule) throws Exception {
        for (int attempt = 1;; attempt++) {
            try {
                scheduleStore.createSchedule(schedule);
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
            if (withdrawn > 0) {
                LOGGER.infof("Withdrew %d pending continuation(s) of conversation %s", withdrawn, sanitize(conversationId));
            }
            return withdrawn;
        } catch (Exception e) {
            LOGGER.warnf("Could not withdraw the pending continuation of conversation %s: %s", sanitize(conversationId), e.getMessage());
            return 0;
        }
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
