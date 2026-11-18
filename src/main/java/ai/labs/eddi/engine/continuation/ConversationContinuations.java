/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

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
import ai.labs.eddi.engine.security.ClientContextGuard;
import ai.labs.eddi.engine.userevents.UserEvent;
import ai.labs.eddi.engine.userevents.UserEventAudience;
import ai.labs.eddi.engine.userevents.UserEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * ({@link #recover}). Every decision gets an id of its own, stored in the step
 * ({@value #STEP_DECISION}) and in the record; the schedule's id is derived
 * from the conversation, the continuation number and that decision id
 * ({@link #scheduleId}) and written with
 * {@link IScheduleStore#createScheduleIfAbsent}, so the original arm, a retry
 * of it and any number of sweeping nodes write one row between them, while a
 * later decision of the same number (a turn that decides again before the
 * continuation ran) gets a row of its own. The record is marked handled once
 * its schedule is known to exist, removed when the conversation is ended or
 * cancelled ({@link #disarm}), and replaced by the next committed turn, which
 * derives it from its own step.
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
    /**
     * Step key: the id of this step's decision to continue — what binds the outbox
     * record and the schedule to the step that decided, so a second decision of the
     * same number (or an undo back to an earlier one) is never mistaken for it.
     */
    public static final String STEP_DECISION = "continuation:decision";

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
    /** Metadata key: the id of the decision the schedule arms. */
    public static final String METADATA_DECISION_KEY = "continuationDecision";

    /**
     * What {@link #disarm} returns when the stored outbox record could not be
     * removed: the schedules are deleted, but the recovery sweep could still arm
     * the decision while the conversation stays READY.
     */
    public static final int WITHDRAWAL_FAILED = -1;

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

    /**
     * Tells the owner's tabs when a job's last turn ran ({@code GET /me/events}).
     * Field-injected so the constructors keep their signatures; {@code null} in
     * tests that build this class by hand, which then announce nothing.
     */
    @Inject
    UserEventPublisher userEventPublisher;

    /**
     * Whether {@link ContinuationRecoverySweep} runs — then a schedule write that
     * failed does not end the job, because the sweep arms the next turn from the
     * stored decision. Field-injected for the same reason as the publisher;
     * {@code false} in tests that build this class by hand.
     */
    @ConfigProperty(name = "eddi.continuation.recovery.enabled", defaultValue = "true")
    boolean recoveryEnabled;

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
        String decisionId = UUID.randomUUID().toString();
        var scheduled = new Data<Object>(STEP_NEXT, next);
        scheduled.setPublic(false);
        step.storeData(scheduled);
        var decision = new Data<Object>(STEP_DECISION, decisionId);
        decision.setPublic(false);
        step.storeData(decision);
        memory.setPendingContinuation(new PendingContinuation(next, policy.maxContinuations(), policy.input(),
                environment != null ? environment.toString() : null, clock.millis(), decisionId));
        return next;
    }

    /** {@link #decide(IConversationMemory, Environment)} without an environment. */
    public Integer decide(IConversationMemory memory) {
        return decide(memory, null);
    }

    /**
     * The outbox record to store with the memory's current step: the one
     * {@link #decide} recorded while the step still carries the matching
     * {@value #STEP_NEXT} and {@value #STEP_DECISION}, {@code null} when the step
     * did not decide to continue. The decision id, not the number, is what binds
     * the two: after an undo, a record of the undone turn's decision never rides
     * along with an earlier step that decided the same number. Called by the
     * snapshot conversion on every write, which is what removes a record once a
     * later step is committed.
     * <p>
     * Only {@link #decide} creates a record. A memory whose record was removed (a
     * cancel, an end, a sweep that found the conversation not READY) keeps it
     * removed on a re-store of the same step: rebuilding it from the step would
     * hand the sweep a job that was stopped.
     */
    public static PendingContinuation pendingOf(IConversationMemory memory) {
        IConversationStep step = memory != null ? memory.getCurrentStep() : null;
        IData<Object> next = step != null ? step.getData(STEP_NEXT) : null;
        if (next == null || !(next.getResult() instanceof Number number)) {
            return null;
        }
        PendingContinuation recorded = memory.getPendingContinuation();
        return recorded != null && recorded.getNumber() != null && recorded.getNumber() == number.intValue()
                && Objects.equals(recorded.getDecisionId(), decisionOf(step)) ? recorded : null;
    }

    /** The decision id the step stored with its {@value #STEP_NEXT}, if any. */
    private static String decisionOf(IConversationStep step) {
        IData<Object> decision = step.getData(STEP_DECISION);
        return decision != null && decision.getResult() instanceof String id ? id : null;
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
     * counted ({@code schedule_failed}); the committed turn stands, and the
     * recovery sweep arms the schedule from the stored decision.
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
                announceFinished(conversationId, memory, step);
            }
            return false;
        }
        ContinuationPolicy policy = memory.getContinuationPolicy();
        String decisionId = decisionOf(step);
        if (memory.isCancelled()) {
            // A cancel that signalled this turn found no schedule to withdraw yet; the
            // job it cancelled must not queue its next turn now — nor leave the stored
            // outbox record behind for the recovery sweep to arm.
            clearPending(conversationId, number.intValue(), decisionId);
            refuse(REASON_INTERRUPTED, conversationId, "the turn was cancelled before its continuation was armed");
            return false;
        }
        pruneUsedUp(conversationId);
        try {
            var schedule = schedule(conversationId, memory.getAgentId(), memory.getUserId(),
                    environment != null ? environment.toString() : null, number.intValue(), policy.maxContinuations(), policy.input(),
                    decisionId);
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
            if (recoveryWillArm(memory, number.intValue())) {
                // Not the end of the job: the decision is stored with the committed step,
                // and the recovery sweep arms the next turn from it. Announcing
                // job.finished now would be followed by the job carrying on.
                LOGGER.infof("Continuation %d of conversation %s is left to the recovery sweep", number.intValue(), sanitize(conversationId));
            } else {
                // Nothing will fire the next turn: the job stops here as surely as at its
                // limit, so its owner hears that it ended, and why. The decision is
                // retired first, so a sweep switched on later cannot arm a job its
                // owner was told had finished. A decision that could not be retired is
                // still armable, so its end is not announced: telling the owner the job
                // finished and then running it would be the worse of the two.
                if (tryClearPending(conversationId, number.intValue(), decisionId) != null) {
                    announceFinished(conversationId, memory, REASON_SCHEDULE_FAILED);
                } else {
                    LOGGER.warnf("Continuation %d of conversation %s could neither be armed nor retired; its end is not announced, "
                            + "since a recovery sweep could still arm it", number.intValue(), sanitize(conversationId));
                }
            }
            return false;
        }
    }

    /**
     * Whether the recovery sweep will arm this continuation after a failed write:
     * the sweep runs, the record can be stored and read back, and the memory holds
     * the unhandled record of exactly this decision.
     */
    private boolean recoveryWillArm(IConversationMemory memory, int number) {
        PendingContinuation pending = memory.getPendingContinuation();
        return recoveryEnabled && memoryStore != null && pending != null && pending.getNumber() != null && pending.getNumber() == number
                && pending.getDecidedAt() != null;
    }

    /**
     * The job ended: this continuation turn committed and asks for no successor —
     * it finished its work, reached its limit or its cost ceiling. Raises
     * {@code job.finished} for the conversation's owner, with the refusal reason as
     * its state when there is one ({@code limit}, {@code cost_ceiling}, …) and
     * {@code done} otherwise. A next turn that was decided but whose schedule could
     * not be written ends the job too, with state {@code schedule_failed}, and its
     * record is removed — unless the recovery sweep will arm it
     * ({@link #recoveryWillArm}). Never throws.
     */
    private void announceFinished(String conversationId, IConversationMemory memory, IWritableConversationStep step) {
        String reason;
        try {
            IData<Object> refused = step.getData(STEP_REFUSED);
            reason = refused != null && refused.getResult() instanceof String r && !r.isBlank() ? r : null;
        } catch (RuntimeException e) {
            reason = null;
        }
        announceFinished(conversationId, memory, reason != null ? reason : "done");
    }

    private void announceFinished(String conversationId, IConversationMemory memory, String state) {
        UserEventPublisher publisher = userEventPublisher;
        if (publisher == null) {
            return;
        }
        try {
            publisher.publish(UserEvent.of(UserEvent.KIND_JOB_FINISHED).withSurface(UserEvent.SURFACE_REGULAR)
                    .withConversation(conversationId, memory.getAgentId()).withJob(UserEvent.JOB_CONTINUATION, null).withState(state),
                    UserEventAudience.principal(memory.getUserId()));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not announce the end of the job of conversation %s: %s", sanitize(conversationId), e.getMessage());
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
     * The one-shot schedule of continuation {@code number}, due now, under the id
     * derived from its decision ({@link #scheduleId}).
     */
    private ScheduleConfiguration schedule(String conversationId, String agentId, String userId, String environment, int number,
                                           Integer max, String input, String decisionId) {
        Instant now = clock.instant();
        var schedule = new ScheduleConfiguration();
        schedule.setId(scheduleId(conversationId, number, decisionId));
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
        schedule.setMetadata(metadata(conversationId, number, max != null ? max : 0, decisionId));
        return schedule;
    }

    /**
     * The id of the schedule of one decision to run continuation {@code number}: a
     * name-based UUID of the conversation, the number and the decision id. Every
     * writer of that decision derives the same id, and
     * {@link IScheduleStore#createScheduleIfAbsent} lets only the first one write
     * the row. A later decision of the same number has another id, so it never
     * mistakes the row of an earlier one — still firing, spent or dead-lettered —
     * for its own; the fire's once-only check ({@link #ranIn}) still runs the
     * number once.
     */
    public static String scheduleId(String conversationId, int number, String decisionId) {
        String name = NAME_PREFIX + conversationId + ":" + number + (decisionId != null ? ":" + decisionId : "");
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Writes the schedule, retrying a failed write a few times with a short pause.
     * The turn is already committed with its decision, so a write that fails for a
     * moment would otherwise stop the job; a write that keeps failing is reported
     * by the caller as {@code schedule_failed}. A row that is already there — the
     * sweep was faster, or an attempt that reported a failure did land — counts as
     * written: its id names this very decision.
     */
    private void createWithRetry(ScheduleConfiguration schedule) throws Exception {
        for (int attempt = 1;; attempt++) {
            try {
                scheduleStore.createScheduleIfAbsent(schedule);
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

    /** {@link #metadata(String, int, int)} of one decision. */
    public static Map<String, Object> metadata(String conversationId, int number, int max, String decisionId) {
        Map<String, Object> metadata = metadata(conversationId, number, max);
        if (decisionId != null) {
            metadata.put(METADATA_DECISION_KEY, decisionId);
        }
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
     * @return how many pending continuations were withdrawn, or
     *         {@link #WITHDRAWAL_FAILED} when the stored outbox record could not be
     *         removed — the rows are deleted all the same, but while the
     *         conversation stays READY the recovery sweep could arm the decision
     *         again, so a cancel must not report success
     */
    public int disarm(String conversationId) {
        if (conversationId == null || scheduleStore == null) {
            return 0;
        }
        // The outbox record goes FIRST: a sweep that armed from it a moment ago either
        // wrote its row before this point (the delete below takes it) or finds the
        // record gone when it confirms, and withdraws its own row (see recover).
        Boolean recordCleared = tryClearPending(conversationId, null, null);
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
            if (recordCleared == null) {
                return WITHDRAWAL_FAILED;
            }
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
     * Removes the stored outbox record, retrying a failed write like the arm does.
     * Never throws. A record that still cannot be removed is armed by the sweep
     * later if the conversation is still READY — which a cancel between two turns
     * leaves it — so that is logged at ERROR.
     *
     * @param expectedNumber
     *            remove it only while it names this continuation; {@code null}
     *            removes whatever is there
     * @param expectedDecisionId
     *            with {@code expectedNumber}: remove it only while it names this
     *            decision
     * @return whether an unhandled record was removed
     */
    private boolean clearPending(String conversationId, Integer expectedNumber, String expectedDecisionId) {
        return Boolean.TRUE.equals(tryClearPending(conversationId, expectedNumber, expectedDecisionId));
    }

    /**
     * {@link #clearPending}, telling a removal that failed ({@code null}) from a
     * record that was not there ({@code false}).
     */
    private Boolean tryClearPending(String conversationId, Integer expectedNumber, String expectedDecisionId) {
        if (memoryStore == null) {
            return false;
        }
        for (int attempt = 1;; attempt++) {
            try {
                return memoryStore.clearPendingContinuation(conversationId, expectedNumber, expectedDecisionId);
            } catch (Exception e) {
                if (attempt >= ARM_ATTEMPTS) {
                    LOGGER.errorf("Could not clear the pending continuation record of conversation %s after %d attempts (%s); while the "
                            + "conversation stays READY the recovery sweep may still arm it", sanitize(conversationId), ARM_ATTEMPTS, e.toString());
                    return null;
                }
                LOGGER.warnf("Clearing the pending continuation record of conversation %s failed (attempt %d of %d), retrying: %s",
                        sanitize(conversationId), attempt, ARM_ATTEMPTS, e.toString());
                try {
                    Thread.sleep(ARM_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
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
     * <li>The schedule is written under the id derived from the record's decision
     * with {@link IScheduleStore#createScheduleIfAbsent}: when the original arm or
     * another node already wrote it, nothing is written.</li>
     * <li>The record is then marked handled
     * ({@link IConversationMemoryStore#markPendingContinuationHandled}), which
     * takes it out of the sweep's index. For a READY conversation, sweeps only ever
     * mark it (step 1 removes the record of one that is not READY); only a cancel,
     * an end or a newer turn removes or replaces it. Both the mark and the removal
     * match the decision id, not only the number, so a later decision of the same
     * number is never marked or removed in this one's name. So when the record no
     * longer names this decision, the continuation was withdrawn or superseded
     * while this ran, and a row this call wrote is withdrawn again. A cancel
     * removes the record before it deletes the rows, so between them every
     * interleaving ends without a live row; two sweeps write one row and both keep
     * it.</li>
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
        String decisionId = pending.getDecisionId();
        if (candidate.state() != ConversationState.READY) {
            memoryStore.clearPendingContinuation(conversationId, number, decisionId);
            LOGGER.infof("Pending continuation %d of conversation %s dropped: the conversation is %s", number, sanitize(conversationId),
                    candidate.state());
            return Recovery.NOT_READY;
        }
        var schedule = schedule(conversationId, candidate.agentId(), candidate.userId(), pending.getEnvironment(), number,
                pending.getMaxContinuations(), pending.getInput(), decisionId);
        boolean written = scheduleStore.createScheduleIfAbsent(schedule);
        // Marks, never removes: another sweep that finds the row this one wrote marks
        // the record too, and must not look like a cancel to this one.
        boolean stillPending = memoryStore.markPendingContinuationHandled(conversationId, number, decisionId);
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
