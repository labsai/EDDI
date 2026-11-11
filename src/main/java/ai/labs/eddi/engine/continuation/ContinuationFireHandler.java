/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Turns a claimed continuation schedule into the next turn of its conversation
 * (see {@link ConversationContinuations}). Called by
 * {@code ScheduleFireExecutor} under the poller's cluster-wide claim.
 * <p>
 * <b>At-least-once in, once out.</b> The schedule machinery delivers at least
 * once: a node that dies after the turn ran but before the fire was recorded
 * leaves the claim to be stolen, and the schedule fires again. Two guards keep
 * that from running the turn twice:
 * <ol>
 * <li>Before anything runs, the stored conversation is checked for a step
 * marked with this continuation's number ({@code continuation:number}) — the
 * marker the turn itself wrote before it was persisted. Found: the fire
 * completes and nothing runs.</li>
 * <li>The turn carries an automatic {@code Idempotency-Key}
 * ({@value #IDEMPOTENCY_KEY_PREFIX}{@code <n>}). Two fires that both pass the
 * first check — possible only while the first one's turn is still running —
 * meet in the {@code Idempotency-Key} machinery: on one node the duplicate is
 * answered with the running turn's outcome; on another, the conversation lease
 * serializes them and the duplicate, rebuilt over the newer document, finds the
 * key recorded and is dropped without running.</li>
 * </ol>
 * <p>
 * <b>What stops a continuation.</b> Answered {@code COMPLETED} (the schedule is
 * done, nothing is retried) and counted as refused: the conversation ended,
 * waits for a human, no longer exists, or is restricted (GDPR Art. 18); its
 * owner may no longer {@code USE} the agent (enforced workspaces); the
 * continuation already ran; the continuation turn was interrupted (a cancel or
 * the watchdog — the chain stops). Answered {@code FAILED} — retried with the
 * schedule backoff and finally dead-lettered, like any lost turn — when it
 * could not run for a reason that may pass: the agent is not deployed, a quota
 * or capacity refusal, a store failure, a turn that failed, a conversation busy
 * with another turn.
 * <p>
 * The turn runs with no caller identity, like every scheduled turn:
 * {@code ${caller:token}} fails closed in it.
 */
@ApplicationScoped
public class ContinuationFireHandler {

    private static final Logger LOGGER = Logger.getLogger(ContinuationFireHandler.class);

    /**
     * How often, and how far apart, a fire looks for its turn in the stored
     * conversation after the reply arrived: about two seconds in all, enough for
     * the persist that follows the reply.
     */
    static final int COMMIT_CHECK_ATTEMPTS = 20;
    static final long COMMIT_CHECK_INTERVAL_MS = 100;

    /** Prefix of the automatic {@code Idempotency-Key} of a continuation turn. */
    public static final String IDEMPOTENCY_KEY_PREFIX = "eddi-continuation-";

    /** The outcome of a fire: a {@link FireStatus} name and, when not run, why. */
    public record Result(String status, String error) {
        static Result completed() {
            return new Result(FireStatus.COMPLETED.name(), null);
        }

        static Result stopped(String reason) {
            return new Result(FireStatus.COMPLETED.name(), "Continuation not run: " + reason);
        }

        static Result failed(String error) {
            return new Result(FireStatus.FAILED.name(), error);
        }
    }

    private final IConversationService conversationService;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ConversationContinuations continuations;

    /**
     * The owner's {@code USE} re-check ({@link #refusalForAccess}). Field-injected
     * so the tests that build this handler with its constructor keep working; null
     * there, where no re-check runs.
     */
    @Inject
    ResourceAccessGuard resourceAccessGuard;

    /** Where the owner's team subjects come from; null in the same tests. */
    @Inject
    UserDirectory userDirectory;

    @Inject
    public ContinuationFireHandler(IConversationService conversationService, IConversationMemoryStore conversationMemoryStore,
            ConversationContinuations continuations) {
        this.conversationService = conversationService;
        this.conversationMemoryStore = conversationMemoryStore;
        this.continuations = continuations;
    }

    /**
     * Runs the continuation the schedule metadata names, waiting at most
     * {@code timeout} for the turn. Never throws.
     *
     * @param scheduleUserId
     *            the schedule's {@code userId}, which the engine sets to the
     *            conversation's owner when it arms a continuation; a fire whose
     *            schedule names someone else runs nothing
     * @param input
     *            the agent designer's {@code continuationInput} (the schedule's
     *            message), {@code null} for an empty input
     */
    public Result fire(Map<String, Object> metadata, String scheduleUserId, String input, Duration timeout) {
        String conversationId = metadata != null ? asString(metadata.get(ConversationContinuations.METADATA_CONVERSATION_ID_KEY)) : null;
        Integer number = metadata != null ? asInt(metadata.get(ConversationContinuations.METADATA_NUMBER_KEY)) : null;
        Integer max = metadata != null ? asInt(metadata.get(ConversationContinuations.METADATA_MAX_KEY)) : null;
        if (conversationId == null || number == null || number < 1) {
            return Result.stopped("the schedule names no conversation or continuation number");
        }
        // Absent on a chain booked before the marker existed: that chain is not
        // re-checked (see refusalForAccess).
        Boolean bookedByAdmin = metadata.get(ConversationContinuations.METADATA_BOOKED_BY_ADMIN_KEY) instanceof Boolean b ? b : null;
        try {
            Result refused = refusalBeforeRun(conversationId, number, scheduleUserId, bookedByAdmin);
            if (refused != null) {
                return refused;
            }
            return run(conversationId, number, max, scheduleUserId, input, timeout, bookedByAdmin);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted while waiting for continuation " + number);
        } catch (Exception e) {
            return outcomeOf(conversationId, number, e);
        }
    }

    /**
     * The checks that need no turn: the conversation is gone, belongs to someone
     * other than the schedule's user, ended, paused, or already ran this
     * continuation. {@code null} when the turn may run.
     */
    private Result refusalBeforeRun(String conversationId, int number, String scheduleUserId, Boolean bookedByAdmin)
            throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            snapshot = null;
        }
        if (snapshot == null) {
            return stop(ConversationContinuations.REASON_GONE, conversationId, "the conversation no longer exists");
        }
        if (!Objects.equals(snapshot.getUserId(), scheduleUserId)) {
            // Defence in depth behind the REST refusal of the marker: whatever wrote
            // this schedule, it does not run a turn in a conversation it does not own.
            return stop(ConversationContinuations.REASON_OWNER_MISMATCH, conversationId,
                    "the schedule's user does not own the conversation");
        }
        if (ConversationContinuations.ranIn(snapshot, number)) {
            return stop(ConversationContinuations.REASON_DUPLICATE, conversationId, "continuation " + number + " already ran");
        }
        Result revoked = refusalForAccess(conversationId, snapshot, bookedByAdmin);
        if (revoked != null) {
            return revoked;
        }
        return refusalForState(conversationId, snapshot.getConversationState());
    }

    /**
     * The {@code USE} re-check every other scheduled fire gets
     * ({@code ScheduleFireExecutor#creatorUseCheck}), asked for the conversation's
     * owner: continuations are routed past that check because the engine, not a
     * person, created their schedule — which used to mean nobody re-checked at all,
     * so a user whose access to the agent was revoked still had the rest of the
     * chain run on it.
     * <p>
     * The owner's team subjects are the ones their most recent sign-in listed in
     * the user directory, so a share with one of their teams still counts. With no
     * directory record of them there is nothing to decide a team share by, and the
     * fire is left alone — the same rule {@code creatorUseCheck} applies to a
     * schedule without an access snapshot. Denied: the chain stops
     * ({@link ConversationContinuations#REASON_ACCESS_REVOKED}). A descriptor store
     * that cannot answer fails the fire, so it is retried rather than run
     * unchecked. With workspaces off everything is admitted, as everywhere else.
     * <p>
     * A chain an administrator booked in their own conversation is admitted, as
     * {@code creatorAdmin} admits a schedule: the schedule records it
     * ({@link ConversationContinuations#METADATA_BOOKED_BY_ADMIN_KEY}). A chain
     * booked before that marker existed carries none and is not re-checked —
     * stopping it would break long jobs already running.
     */
    private Result refusalForAccess(String conversationId, ConversationMemorySnapshot snapshot, Boolean bookedByAdmin) {
        String owner = snapshot.getUserId();
        String agentId = snapshot.getAgentId();
        if (bookedByAdmin == null || resourceAccessGuard == null || owner == null || owner.isBlank() || agentId == null || agentId.isBlank()) {
            return null;
        }
        List<String> teams = ownerTeams(owner);
        if (teams == null) {
            return null;
        }
        ResourceAccessGuard.UseCheck check = resourceAccessGuard.checkPrincipalUse(agentId, owner, teams, bookedByAdmin);
        if (check == ResourceAccessGuard.UseCheck.DENIED) {
            return stop(ConversationContinuations.REASON_ACCESS_REVOKED, conversationId,
                    "the conversation's owner may no longer use its agent");
        }
        if (check == ResourceAccessGuard.UseCheck.UNKNOWN) {
            return Result.failed("Could not verify that the conversation's owner may still use its agent");
        }
        return null;
    }

    /**
     * The owner's team subjects at their last sign-in, or {@code null} when the
     * directory has no record of them. Only consulted under enforced workspaces
     * (with them off {@code checkPrincipalUse} admits everything first).
     */
    private List<String> ownerTeams(String owner) {
        if (!resourceAccessGuard.settings().isEnforcing()) {
            return List.of();
        }
        if (userDirectory == null || !userDirectory.isActive()) {
            return null;
        }
        DirectoryUser seen = userDirectory.lookup(List.of(owner)).get(owner);
        return seen != null ? seen.teams() : null;
    }

    private Result refusalForState(String conversationId, ConversationState state) {
        if (state == ConversationState.ENDED) {
            return stop(ConversationContinuations.REASON_ENDED, conversationId, "the conversation has ended");
        }
        if (state == ConversationState.AWAITING_HUMAN) {
            return stop(ConversationContinuations.REASON_AWAITING_HUMAN, conversationId,
                    "the conversation is awaiting a human decision; a resumed turn may continue it again");
        }
        return null;
    }

    private Result run(String conversationId, int number, Integer max, String scheduleUserId, String input, Duration timeout,
                       Boolean bookedByAdmin)
            throws Exception {
        var latch = new CountDownLatch(1);
        var outcome = new AtomicReference<SimpleConversationMemorySnapshot>();
        var skipped = new AtomicBoolean(false);
        conversationService.say(conversationId, false, true, List.of(), inputData(number, max, input, bookedByAdmin), false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        outcome.set(snapshot);
                        latch.countDown();
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        skipped.set(true);
                        outcome.set(snapshot);
                        latch.countDown();
                    }
                });
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // Retried: by then the turn has either been recorded (the retry is a no-op)
            // or it has not run (the retry runs it).
            return Result.failed("Continuation " + number + " did not finish within " + timeout);
        }
        ConversationState state = outcome.get() != null ? outcome.get().getConversationState() : null;
        if (skipped.get()) {
            return afterSkip(conversationId, number, scheduleUserId, state, bookedByAdmin);
        }
        if (state == ConversationState.ERROR) {
            return Result.failed("Continuation " + number + " failed (state ERROR)");
        }
        if (state == ConversationState.EXECUTION_INTERRUPTED) {
            return stop(ConversationContinuations.REASON_INTERRUPTED, conversationId,
                    "continuation " + number + " was interrupted; the chain stops");
        }
        // The reply reaches this handler before the turn is stored (renderOutput runs
        // inside the pipeline; the persist follows). A store that then fails would
        // leave a fire recorded COMPLETED for a turn that never committed — and,
        // since the successor is armed only after a successful store, a stopped
        // chain. Confirm the turn's marker reached the stored conversation; if it did
        // not, fail so the schedule retries (the marker and the Idempotency-Key keep
        // the retry to one run if the store was merely slow).
        if (!committed(conversationId, number)) {
            return Result.failed("Continuation " + number + " answered, but its turn was not found in the stored conversation; retried");
        }
        continuations.countRun();
        LOGGER.infof("Continuation %d of conversation %s ran (state %s)", number, sanitize(conversationId), state);
        return Result.completed();
    }

    /**
     * Whether continuation {@code number}'s marker is in the stored conversation,
     * re-checked briefly: the turn's store can still be in progress when its reply
     * arrives.
     */
    private boolean committed(String conversationId, int number) throws InterruptedException, IResourceStore.ResourceStoreException {
        for (int attempt = 0; attempt < COMMIT_CHECK_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                Thread.sleep(COMMIT_CHECK_INTERVAL_MS);
            }
            ConversationMemorySnapshot snapshot;
            try {
                snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            } catch (IResourceStore.ResourceNotFoundException e) {
                return false;
            }
            if (snapshot != null && ConversationContinuations.ranIn(snapshot, number)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A skipped turn did not consume its input: a duplicate that found the key
     * recorded, or a conversation that was paused or busy when the turn's lease
     * came. The stored document decides which.
     */
    private Result afterSkip(String conversationId, int number, String scheduleUserId, ConversationState skippedIn,
                             Boolean bookedByAdmin)
            throws IResourceStore.ResourceStoreException {
        Result refused = refusalBeforeRun(conversationId, number, scheduleUserId, bookedByAdmin);
        if (refused != null) {
            return refused;
        }
        return Result.failed("Continuation " + number + " was skipped: the conversation was in state " + skippedIn);
    }

    private Result outcomeOf(String conversationId, int number, Exception e) {
        if (e instanceof IConversationService.ConversationEndedException) {
            return stop(ConversationContinuations.REASON_ENDED, conversationId, "the conversation has ended");
        }
        if (e instanceof IConversationService.ConversationAwaitingApprovalException) {
            return refusalForState(conversationId, ConversationState.AWAITING_HUMAN);
        }
        if (e instanceof IConversationService.ConversationNotFoundException || e instanceof IResourceStore.ResourceNotFoundException) {
            return stop(ConversationContinuations.REASON_GONE, conversationId, "the conversation no longer exists");
        }
        if (e instanceof ProcessingRestrictedException) {
            return stop(ConversationContinuations.REASON_RESTRICTED, conversationId, "processing is restricted for the user (GDPR Art. 18)");
        }
        if (e instanceof IConversationService.AgentNotReadyException) {
            // Not dropped: an undeploy also disables the schedule, and a redeploy
            // re-enables it — this is the window in between, or a node still deploying.
            continuations.refuse(ConversationContinuations.REASON_UNDEPLOYED, conversationId, e.getMessage());
            return Result.failed("Continuation " + number + " not run: " + e.getMessage());
        }
        LOGGER.warnf("Continuation %d of conversation %s could not run: %s", number, sanitize(conversationId), e.toString());
        return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private Result stop(String reason, String conversationId, String detail) {
        continuations.refuse(reason, conversationId, detail);
        return Result.stopped(detail);
    }

    /**
     * The input of continuation {@code number}: the designer's literal input, the
     * reserved {@code selfContinuation} context, the engine-only continuation
     * number, and the automatic {@code Idempotency-Key}.
     */
    public static InputData inputData(int number, Integer max, String input) {
        return inputData(number, max, input, null);
    }

    /**
     * {@link #inputData(int, Integer, String)}, carrying the chain's
     * booked-by-administrator marker on to the turn, which hands it to the next
     * continuation it books ({@code ConversationContinuations#bookedByAdmin}).
     */
    public static InputData inputData(int number, Integer max, String input, Boolean bookedByAdmin) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("number", number);
        if (max != null) {
            marker.put("max", max);
        }
        Map<String, Context> context = new HashMap<>();
        context.put(ConversationContinuations.CONTEXT_KEY, new Context(Context.ContextType.object, marker));
        var inputData = new InputData(input != null ? input : "", context);
        inputData.setContinuationNumber(number);
        inputData.setContinuationBookedByAdmin(bookedByAdmin);
        inputData.setIdempotencyKey(IDEMPOTENCY_KEY_PREFIX + number);
        return inputData;
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }
}
