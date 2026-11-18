/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.alerts.TurnOutcome;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationDocumentSize;
import ai.labs.eddi.engine.memory.ConversationTooLargeException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IDescribedTask;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.ILeaseAwareTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.security.CallerIdentity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;

/**
 * Running one conversation turn (R3 step 2): dispatch the step onto the
 * coordinator, guard its execution, wait for it within the agent timeout, and
 * load / store / state-cache the conversation memory around it. Extracted from
 * {@code ConversationService} as a pure move — no behavior change.
 * <p>
 * This is the other half of the split {@link ConversationHitlService} started.
 * With the HITL cluster gone, what was left in the facade was two things
 * wearing one name: the public {@code IConversationService} surface (start,
 * say, read, undo/redo, access checks) and the machinery that actually executes
 * a turn. This class is the machinery; the facade is the surface.
 * <p>
 * <b>The processing gauge and its release token are the delicate part.</b>
 * {@code ProcessingTurn} increments {@code processingConversationCount} on
 * construction and releases exactly once, and every path out of a turn — normal
 * completion, timeout, abandonment, a pre-submission throw — has to release it
 * or the gauge drifts upward forever and the graceful-shutdown drain waits on
 * turns that already finished. Both the token type and {@code releaseTurn} stay
 * on the facade, shared by reference, because the facade's own
 * {@code say}/{@code sayStreaming} entry points create the token before this
 * class ever sees it.
 * <p>
 * Holds a back-reference to {@link ConversationService} for the members that
 * remain facade responsibilities: {@code getAgent},
 * {@code createPropertiesHandler}, {@code recordMetrics},
 * {@code validateParams} and {@code releaseTurn}. Constructor only stores it —
 * same argument as {@code ConversationHitlService} and, before it,
 * {@code GroupHitlCoordinator} in R1.
 */
class ConversationStepRunner {

    private static final Logger LOGGER = Logger.getLogger(ConversationStepRunner.class);

    private final ConversationService conversationService;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IRuntime runtime;
    private final ICache<String, ConversationState> conversationStateCache;
    private final ConcurrentHashMap<String, IConversationMemory> inFlightConversations;
    private final int agentTimeout;

    /**
     * Turns the dispatcher has given up on (watchdog timeout, interrupted wait)
     * whose body is still running — "zombies". Exposed as the
     * {@code eddi_conversation_abandoned_running} gauge. Normally 0: EDDI's own
     * model path honours the interrupt. A value that stays up means some layer
     * swallows it and threads are held by turns nobody will read.
     */
    final AtomicInteger abandonedTurnsRunning = new AtomicInteger();

    ConversationStepRunner(ConversationService conversationService,
            IConversationMemoryStore conversationMemoryStore,
            IConversationDescriptorStore conversationDescriptorStore, IRuntime runtime, ICache<String, ConversationState> conversationStateCache,
            ConcurrentHashMap<String, IConversationMemory> inFlightConversations, int agentTimeout) {
        this.conversationService = conversationService;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.runtime = runtime;
        this.conversationStateCache = conversationStateCache;
        this.inFlightConversations = inFlightConversations;
        this.agentTimeout = agentTimeout;
        Cache<String, Long> fences = Caffeine.newBuilder().maximumSize(RAISED_FENCES_MAX)
                .expireAfterWrite(raisedFenceRetention(agentTimeout)).build();
        this.raisedFences = fences.asMap();
    }

    /**
     * Builds one turn's pipeline callable over a given memory: attaches the
     * per-turn collaborators (audit collector, event sink), constructs the
     * {@code Conversation} and wraps the call in the conversation's resolution
     * principal.
     * <p>
     * A builder rather than a finished callable, because the callable is bound to
     * the memory it was built over — the {@code Conversation} captures its longTerm
     * baseline from it at construction — and a queued turn may have to be rebuilt
     * over a fresher memory when it finally runs (see {@link #reloadIfSuperseded}).
     */
    @FunctionalInterface
    interface TurnBuilder {
        Callable<Void> build(IConversationMemory memory) throws Exception;
    }

    IDiscardableTask processConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                                             Map<String, String> loggingContext, TurnBuilder turnBuilder, boolean rebuildWhenSuperseded,
                                             Consumer<IConversationMemory> skipNotifier, ConversationService.ProcessingTurn processingTurn)
            throws Exception {
        return processConversationStep(environment, conversationMemory, conversationId, loggingContext, turnBuilder, rebuildWhenSuperseded,
                skipNotifier, processingTurn, null);
    }

    /**
     * A turn the coordinator runs. Besides {@link IDiscardableTask} it is
     * {@link ILeaseAwareTask} — in cluster mode the coordinator hands it the
     * conversation lease, whose fencing token the turn's writes carry — and
     * {@link IDescribedTask}, so a dead letter can be replayed.
     */
    interface TurnTask extends ILeaseAwareTask, IDescribedTask {
    }

    /**
     * @param turnDescriptor
     *            what a dead letter of this turn records so it can be replayed
     *            (agent, environment, user, input, context), or {@code null}
     */
    IDiscardableTask processConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                                             Map<String, String> loggingContext, TurnBuilder turnBuilder, boolean rebuildWhenSuperseded,
                                             Consumer<IConversationMemory> skipNotifier, ConversationService.ProcessingTurn processingTurn,
                                             Map<String, Object> turnDescriptor)
            throws Exception {
        // Built here, on the request thread, so a conversation that cannot run at all
        // (ended, agent still deploying) is refused to the caller synchronously — the
        // same place it was refused before the builder existed.
        final Callable<Void> executeConversation = turnBuilder.build(conversationMemory);
        // Captured here because this method still runs on the REST request
        // thread, where SecurityIdentity resolves. Everything downstream runs on
        // pool threads with no request context, so the identity travels with the
        // callable rather than with the thread.
        // Binding is CallerIdentityContext's job — it owns the clearing semantics
        // that keep a token off the next caller's turn on a pooled thread.
        //
        // captureOrCurrent, not capture: a group discussion calls say() from its own
        // virtual thread, which it has already bound to the caller. There is no
        // request there, so capture() alone would return null — and since a null
        // identity now masks rather than inherits, it would erase the very binding
        // the group dispatcher established.
        //
        // Stays OUTSIDE the returned task: the task body runs on a pool thread, where
        // there is no request to capture from. Neither the C11 release wrapper nor the
        // IDiscardableTask wrapper below changed that — runConversationStep receives
        // the bound callable and hands it to runGuardedConversationStep, which is
        // where it was used before.
        //
        // The captured identity is kept (not just the bound callable) because a turn
        // rebuilt over a reloaded memory on the pool thread needs the same binding.
        final CallerIdentity callerIdentity = conversationService.callerIdentityContext.captureOrCurrent();
        final UnaryOperator<Callable<Void>> bindIdentity = work -> conversationService.callerIdentityContext.withIdentity(callerIdentity,
                work);
        final Callable<Void> identityBoundExecution = bindIdentity.apply(executeConversation);

        return new TurnTask() {
            /**
             * Set by the cluster coordinator before the task runs; null on a single node.
             */
            private volatile LeaseHandle lease;

            /**
             * Why the turn failed, added to its dead letter: {@code reason}
             * ({@code lease-lost}, {@code fenced} or {@code conflict}) and the fencing
             * tokens involved.
             */
            private volatile Map<String, Object> failure;

            /** Whether {@link #call()} was entered: the turn started running. */
            private volatile boolean started;

            @Override
            public void bindLease(LeaseHandle lease) {
                this.lease = lease;
            }

            @Override
            public Map<String, Object> describe() {
                Map<String, Object> why = failure;
                if (turnDescriptor != null && lease != null && !started) {
                    // A9: dead-lettered although it never ran (the runtime refused it after
                    // its lease was granted). Its caller is answered busy, retry (see
                    // onDiscarded), so a replay of this entry would run it a second time on
                    // top of that retry. Kept as a trace, not as something to replay.
                    Map<String, Object> described = new LinkedHashMap<>(turnDescriptor);
                    described.put(DeadLetterEntry.TURN_ANSWERED_RETRY, true);
                    return described;
                }
                if (why == null || turnDescriptor == null) {
                    return turnDescriptor;
                }
                Map<String, Object> described = new LinkedHashMap<>(turnDescriptor);
                described.putAll(why);
                return described;
            }

            @Override
            public Void call() throws Exception {
                started = true;
                try {
                    Void result = runConversationStep(environment, conversationMemory, conversationId, loggingContext,
                            identityBoundExecution,
                            rebuildWhenSuperseded ? memory -> bindIdentity.apply(turnBuilder.build(memory)) : null, skipNotifier, lease,
                            processingTurn);
                    // All three drained before any is acted on, so none outlives this turn and
                    // is picked up by the conversation's next one.
                    TurnLeaseLostException lost = leaseLostTurns.remove(conversationId);
                    ConversationFencedException fenced = fencedTurns.remove(conversationId);
                    ConcurrentConversationModificationException conflicted = conflictedTurns.remove(conversationId);
                    if (lost != null) {
                        // Stopped at a task boundary because this node lost the lease: nothing
                        // was stored. Surfaced so the coordinator dead-letters it with its input —
                        // it must not vanish without a trace.
                        failure = failureDetail(TurnLeaseLostException.REASON, lost.getFence(), null);
                        throw lost;
                    }
                    if (fenced != null) {
                        // Surfaced so the coordinator dead-letters the turn with its input: the
                        // write was refused because another node took the conversation over.
                        failure = failureDetail("fenced", fenced.getToken(), fenced.getStoredFence());
                        throw fenced;
                    }
                    if (conflicted != null && lease != null) {
                        // Cluster mode (A4): the turn's write lost to another writer of the
                        // document (an undo, a degraded-mode turn on another node, a resume).
                        // Logged and counted, its caller told it was not stored — and
                        // dead-lettered with its input as well, so it is not lost without a
                        // trace.
                        failure = failureDetail(DeadLetterEntry.REASON_CONFLICT, lease.fence(), null);
                        throw conflicted;
                    }
                    return result;
                } finally {
                    // C11: the single guaranteed exit point of a turn. The completion
                    // consumer releases first on the happy path, but a watchdog timeout,
                    // a pipeline error or a cancelled inner future never reaches it — and
                    // an entry that is never released leaks into the gauge forever.
                    // release() is one-shot, so releasing twice is a no-op.
                    //
                    // The same applies to a deferred paused-turn delivery: the runtime
                    // callbacks flush it, and this is the backstop for a turn that never
                    // reached either (cancelled before it ran, a runtime that dropped
                    // it) — the caller's response/stream must terminate exactly once.
                    // deliver() is one-shot too.
                    flushDeferredDelivery(processingTurn, conversationMemory);
                    // finish(), not release(): this is where the task ends, so the
                    // in-flight permit is returned here, after the turn was persisted.
                    processingTurn.finish();
                }
            }

            /**
             * The coordinator dropped this turn WITHOUT running it (it could not hand it to
             * the runtime and had no caller left to roll back to). The {@code finally}
             * above therefore never executes, so the release and the caller's completion
             * have to happen here instead — otherwise the in-flight gauge leaks for the
             * JVM's lifetime and the HTTP caller waits for a response that can never
             * arrive.
             * <p>
             * Reported through the SKIPPED contract, which means exactly this: the turn was
             * dropped without consuming the input. The conversation is untouched (no state
             * write) and remains usable, so a retry runs normally.
             */
            @Override
            public void onDiscarded(Throwable cause) {
                try {
                    conversationService.contextLogger.setLoggingContext(loggingContext);
                    if (cause instanceof LeaseUnavailableException busy) {
                        // Cluster mode: another node kept the conversation's lease for the whole
                        // wait (or NATS is down with degraded.turns=reject). Reported as "busy":
                        // REST answers 409 with Retry-After; the input was not consumed.
                        LOGGER.warnf("Turn of conversation %s not run: %s — reporting it as busy; the input was not consumed",
                                sanitize(conversationId), busy.getMessage());
                        conversationMemory.setConversationState(ConversationState.IN_PROGRESS);
                    } else if (lease != null) {
                        // Cluster mode (A9): refused by the runtime after the lease was granted.
                        // The input was not consumed, so the caller is told to retry, and the
                        // dead letter the coordinator wrote for it is marked not replayable
                        // (see describe()), so the retry and a replay cannot both run it.
                        LOGGER.warnf("Turn of conversation %s not run: %s — reporting it as busy; the input was not consumed "
                                + "(its dead letter is kept as a trace only)", sanitize(conversationId), cause.getMessage());
                        conversationMemory.setConversationState(ConversationState.IN_PROGRESS);
                    } else {
                        LOGGER.errorf(cause, "Queued turn of conversation %s was dropped before it ran — reporting it as "
                                + "skipped; the input was not consumed and can be retried", conversationId);
                    }
                    if (skipNotifier != null) {
                        skipNotifier.accept(conversationMemory);
                    }
                } finally {
                    // Belt and braces: release() is one-shot, so this is a no-op when
                    // the skip notifier already released — but a null or throwing
                    // notifier must not leak the reference. finish(): the task never
                    // runs, so this is also where its in-flight permit comes back.
                    processingTurn.finish();
                }
            }
        };
    }

    /**
     * @param rebuildTurn
     *            rebuilds the (identity-bound) turn over a reloaded memory, or
     *            {@code null} for a turn that must not be rebuilt: a rerun, which
     *            re-executes the step the caller SAW. Rebuilt over a newer memory
     *            it would re-execute a different turn's step — its tool calls and
     *            LLM spend included — so a superseded rerun is reported as skipped.
     */
    Void runConversationStep(Environment environment, IConversationMemory submittedMemory, String conversationId,
                             Map<String, String> loggingContext, Callable<Void> submittedExecution, TurnBuilder rebuildTurn,
                             Consumer<IConversationMemory> skipNotifier) {
        return runConversationStep(environment, submittedMemory, conversationId, loggingContext, submittedExecution, rebuildTurn,
                skipNotifier, null, null);
    }

    /**
     * Turns whose final write was refused by the cluster fence, keyed by
     * conversation; drained by the task so the coordinator dead-letters them.
     * <p>
     * Keyed by conversation, not by turn, because only a turn whose dispatcher is
     * still waiting for it writes here (this map, {@link #leaseLostTurns},
     * {@link #conflictedTurns}), and the coordinator starts a conversation's next
     * turn only once that wait has ended. A node does NOT run at most one turn of a
     * conversation at a time: a body the watchdog abandoned can still be running
     * when the next turn starts, which is why its late outcome is inert (see
     * {@link #runGuardedConversationStep}).
     */
    private final Map<String, ConversationFencedException> fencedTurns = new ConcurrentHashMap<>();

    /**
     * Conversations whose running turn was cancelled because its lease was lost:
     * the conversation belongs to whoever holds the lease now, so the cancelled
     * turn must not stamp a state on it (see the cancel branch of the completion
     * callback). Keyed by conversation: written while the turn still holds its
     * lease, so before the next turn of the conversation can start, and cleared
     * when that turn starts.
     */
    private final Set<String> leaseLostConversations = ConcurrentHashMap.newKeySet();

    /**
     * Lease-lost turns whose outcome was discarded, keyed by conversation (see
     * {@link #fencedTurns} for why that is safe); drained by the task, which
     * dead-letters them (see {@code call()}).
     */
    private final Map<String, TurnLeaseLostException> leaseLostTurns = new ConcurrentHashMap<>();

    /**
     * Turns whose write lost the optimistic-concurrency race after their reply had
     * gone out, keyed by conversation; drained by the task, which dead-letters them
     * in cluster mode (see {@code call()}).
     */
    private final Map<String, ConcurrentConversationModificationException> conflictedTurns = new ConcurrentHashMap<>();

    /**
     * Whether the running turn of {@code conversationId} was cancelled because this
     * node lost its lease. The reply path asks before answering, so the client of
     * such a turn is told the message was not processed instead of getting a 200
     * for a turn that is never stored.
     */
    boolean isLeaseLost(String conversationId) {
        return leaseLostConversations.contains(conversationId);
    }

    /**
     * Whether the turn running on {@code memory} lost its lease: marked by the
     * lease manager's callback, or read off the lease itself.
     */
    boolean isLeaseLost(String conversationId, IConversationMemory memory) {
        return memory.isLeaseLost() || isLeaseLost(conversationId);
    }

    private static Map<String, Object> failureDetail(String reason, Long fence, Long storedFence) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (fence != null) {
            detail.put("fence", fence);
        }
        if (storedFence != null) {
            detail.put("storedFence", storedFence);
        }
        // S3: every turn dead-lettered from here (lease lost, fenced, conflict) has
        // told
        // its caller it was not stored and to send it again. A replay on top of that
        // retry would run it twice, so the entry is a trace, not something to replay.
        detail.put(DeadLetterEntry.TURN_ANSWERED_RETRY, true);
        return detail;
    }

    /**
     * A turn stopped because this node lost the conversation's lease while it ran —
     * a partition, a long pause, a NATS outage, or an administrator releasing the
     * lease. Nothing of it was stored; it is dead-lettered with its input.
     */
    static final class TurnLeaseLostException extends RuntimeException {
        static final String REASON = "lease-lost";
        private final Long fence;

        TurnLeaseLostException(String conversationId, Long fence) {
            super(REASON + ": this node lost the lease of conversation '" + conversationId + "' while its turn ran"
                    + (fence != null ? " (fencing token " + fence + ")" : "") + " — the turn was stopped and nothing of it was stored");
            this.fence = fence;
        }

        Long getFence() {
            return fence;
        }
    }

    /** A turn under {@code lease} with no deferred client completion. */
    Void runConversationStep(Environment environment, IConversationMemory submittedMemory, String conversationId,
                             Map<String, String> loggingContext, Callable<Void> submittedExecution, TurnBuilder rebuildTurn,
                             Consumer<IConversationMemory> skipNotifier, LeaseHandle lease) {
        return runConversationStep(environment, submittedMemory, conversationId, loggingContext, submittedExecution, rebuildTurn,
                skipNotifier, lease, null);
    }

    /**
     * @param lease
     *            the cluster lease this turn runs under (cluster mode), or
     *            {@code null} on a single node
     * @param processingTurn
     *            carries the turn's deferred paused-turn delivery (see
     *            {@link #runGuardedConversationStep}); {@code null} where no client
     *            completion is deferred
     */
    Void runConversationStep(Environment environment, IConversationMemory submittedMemory, String conversationId,
                             Map<String, String> loggingContext, Callable<Void> submittedExecution, TurnBuilder rebuildTurn,
                             Consumer<IConversationMemory> skipNotifier, LeaseHandle lease,
                             ConversationService.ProcessingTurn processingTurn) {
        // Caller-gone guard, first because it is the cheapest and saves the most: the
        // caller stopped waiting while the turn was queued (REST: its 408 has been
        // sent), so nobody will read this answer. Running it anyway is what turns an
        // overload into a collapse: every queued turn spends its model call and its
        // writes on a dead request while the live ones wait behind it. The input is
        // not consumed, so a retry runs normally.
        if (processingTurn != null && processingTurn.callerHasGivenUp(System.currentTimeMillis())) {
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.debugf("Skipping queued turn for conversation %s: its caller stopped waiting before it could run",
                    sanitize(conversationId));
            conversationService.countTurnSkippedForCaller();
            if (skipNotifier != null) {
                skipNotifier.accept(submittedMemory);
            }
            return null;
        }

        // Fence first (cluster mode, A3): before this turn reads anything of the
        // conversation — its state, its revision, a reloaded snapshot, the recorded
        // Idempotency-Keys — the document is told this lease has started. A holder
        // whose lease expired earlier can still be writing until then; raised
        // afterwards, its append could land between this turn's read and its run, and
        // this turn would run without that step and overwrite its properties, or run a
        // duplicate Idempotency-Key a second time. A turn that cannot raise the fence,
        // or whose lease is already gone, does not run: it is answered busy (409 +
        // Retry-After) and its input is not consumed.
        if (lease != null && lease.fence() != null && !fenceRaised(conversationId, lease)) {
            return skipAsBusy(submittedMemory, loggingContext, skipNotifier);
        }

        // Queued-say guard: this memory copy was loaded at REST-request time;
        // a previously queued turn may have committed a pause (or a resume may
        // be executing), or the conversation may have been terminally resolved
        // (ENDED via endConversation) in the meantime. Skip the turn entirely —
        // executing it against the stale snapshot would end with a full-document
        // store that silently overwrites the pause (destroying the pending
        // approval and orphaning its timeout schedule) or RESURRECTS a terminated
        // conversation to READY with post-termination side effects. The skip
        // notifier completes the caller's response handler with the persisted
        // state, so the client gets a prompt, honest answer instead of a watchdog
        // timeout.
        //
        // A conversation that no longer exists (deleted, or erased by a GDPR request,
        // while this turn was queued) is skipped the same way and reported as ENDED:
        // the say path only ever runs on a stored conversation, so a missing document
        // means it is gone. Run on the request-time snapshot, the turn would spend its
        // model and tool calls and write the user's long-term properties back after
        // the erasure; only its final persist would fail. ENDED is what REST answers
        // 410 for, and what the clients recover from by starting a new conversation.
        //
        // EXECUTION_INTERRUPTED is deliberately NOT skipped: unlike ENDED it is a
        // RECOVERABLE marker meaning "the previous turn did not finish" (an
        // agentTimeout watchdog expiry, or HitlCrashRecoveryObserver parking a
        // stuck IN_PROGRESS conversation with the explicit intent to "unlock
        // say()"). A fresh say must run a new turn to self-heal the conversation
        // back to READY — mirroring the pre-HITL behavior where a retry after an
        // interrupt executed normally. Skipping it would strand the conversation's
        // input forever, since nothing else transitions EXECUTION_INTERRUPTED back
        // to READY.
        // State and revision in one read (the stale-snapshot guard below needs the
        // revision); a store that cannot answer both at once is asked twice, as before.
        // A store that cannot be read does not run the turn either: it is answered
        // busy, like a fence that cannot be raised. Escaping from here, the failure
        // reached the coordinator, which dead-lettered the turn, and its caller never
        // heard back (REST waited for its 408; a stream got no terminal event at all).
        IConversationMemoryStore.StateAndRevision persisted;
        ConversationState persistedState;
        try {
            persisted = conversationMemoryStore.getStateAndRevision(conversationId);
            persistedState = persisted != null ? persisted.state() : conversationMemoryStore.getConversationState(conversationId);
        } catch (RuntimeException e) {
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.warnf("Turn of conversation %s not run: could not read its state before it started (%s) — reporting it as "
                    + "busy; the input was not consumed", sanitize(conversationId), e.getMessage());
            return skipAsBusy(submittedMemory, loggingContext, skipNotifier);
        }
        boolean gone = persisted != null && persisted.state() == null && persisted.revision() == null;
        ConversationState skipAs = gone ? ConversationState.ENDED : persistedState;
        if (skipAs == ConversationState.AWAITING_HUMAN || skipAs == ConversationState.IN_PROGRESS
                || skipAs == ConversationState.ENDED) {
            submittedMemory.setConversationState(skipAs);
            conversationService.contextLogger.setLoggingContext(loggingContext);
            if (gone) {
                LOGGER.infof("Skipping queued turn for conversation %s: the conversation no longer exists (deleted or erased "
                        + "while the turn was queued) — reporting it as ended", sanitize(conversationId));
            } else {
                LOGGER.warnf("Skipping queued turn for conversation %s: persisted state is %s (turn arrived before the state change)",
                        conversationId, persistedState);
            }
            if (skipNotifier != null) {
                skipNotifier.accept(submittedMemory);
            }
            return null;
        }

        // Stale-snapshot guard (H13a): the memory was loaded at request time, and a
        // turn of the same conversation queued ahead of this one may have committed
        // since. Running on the old snapshot evaluated lastStep rules, the LLM history
        // and this turn's property writes WITHOUT the previous turn, and the append
        // merge then re-applied the stale conversation properties over the ones that
        // turn wrote (last writer wins). A pause raised by such a turn was lost as
        // well: its commit is guarded on the revision it loaded, so it missed and was
        // discarded. Rebuild the turn over the current document instead.
        IConversationMemory conversationMemory = submittedMemory;
        Callable<Void> executeConversation = submittedExecution;
        IConversationMemory reloaded = reloadIfSuperseded(conversationId, submittedMemory, loggingContext,
                persisted != null ? persisted.revision() : null, persisted != null);
        if (reloaded != null && rebuildTurn == null) {
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.infof("Skipping queued rerun of conversation %s: another turn committed while it was queued, so the step "
                    + "it would re-execute is no longer the last one", sanitize(conversationId));
            if (skipNotifier != null) {
                skipNotifier.accept(reloaded);
            }
            return null;
        }
        if (reloaded != null) {
            try {
                executeConversation = rebuildTurn.build(reloaded);
                conversationMemory = reloaded;
            } catch (Exception e) {
                // The reloaded conversation cannot run this turn (it was ended in between,
                // or the agent is no longer available). Report it as skipped — the input
                // was not consumed — rather than running it on a snapshot now known to be
                // superseded.
                conversationService.contextLogger.setLoggingContext(loggingContext);
                LOGGER.warnf("Skipping queued turn for conversation %s: it could not be rebuilt over the current "
                        + "conversation (%s)", sanitize(conversationId), e.getMessage());
                if (skipNotifier != null) {
                    skipNotifier.accept(reloaded);
                }
                return null;
            }
        }

        // Zombie-pause guard: the state loaded WITH the snapshot at request
        // time — on a backend whose snapshot state diverged from the CAS'd
        // state column, this can still claim AWAITING_HUMAN even though the
        // pause was terminally resolved (persistedState above says otherwise).
        // Never execute against, persist, or re-arm a pause this turn did not
        // produce.
        final ConversationState memoryStateAtSubmit = conversationMemory.getConversationState();

        // #2: register the live memory so cancelConversation can signal the
        // running pipeline via setCancelled (checked at task boundaries).
        InFlightRegistration registration = new InFlightRegistration(conversationId, conversationMemory);
        // Assume the turn may still be running until the dispatcher reports otherwise:
        // the abandon branch of the watchdog does store round trips that can throw
        // (a datastore outage is the likeliest reason for a timeout), and a turn whose
        // dispatcher died there must not be unregistered while its body still runs.
        // Releasing as "abandoned" is always safe — a finished or never-started body
        // is unregistered straight away.
        boolean abandoned = true;
        try {
            if (lease != null && lease.fence() != null) {
                // Cluster mode (inside the try for the reason given below): every write of
                // this turn carries the lease's fencing token, so the store refuses it if
                // another node took the conversation over in the meantime; and losing the
                // lease stops the pipeline at the next task boundary.
                // The fence itself was raised before anything was read (see above).
                leaseLostConversations.remove(conversationId);
                conversationMemory.setFenceToken(lease.fence());
                conversationMemory.setLeaseLostCheck(lease::isLost);
                final IConversationMemory leasedMemory = conversationMemory;
                // Abandoned through the same seam as a watchdog timeout (abandonTurn), but
                // aimed at THIS turn's memory rather than looked up by conversation id: the
                // lease callback can fire late, and by then the id may name a newer turn of
                // the conversation.
                // The lease-lost mark goes first so the completion callback, which reads it
                // once it sees the flag, never settles this turn as a plain cancel.
                lease.onLost(() -> {
                    leaseLostConversations.add(conversationId);
                    abandonTurn(leasedMemory);
                });
            } else if (lease == null) {
                // Single node (F2): the turn writes with the fencing token of the document
                // it loaded — or of a fence this node raised over an abandoned write since
                // — so a write the watchdog abandoned can be refused once its caller was
                // told it may not be stored (fenceAbandonedWrite). No extra round trip.
                Long stored = conversationMemory.getStoredFence();
                Long raised = raisedFences.remove(conversationId);
                conversationMemory.setFenceToken(Math.max(stored != null ? stored : 0L, raised != null ? raised : 0L));
            }
            // Carry the agent-level tool-approval config onto memory BEFORE the
            // pipeline (LlmTask) runs, so the tool-approval gate can resolve its
            // effective config. Transient — never persisted; re-resolved each turn.
            // Inside the try so an Error here cannot strand the registration above:
            // a stranded entry would keep a finished turn's memory reachable AND
            // make a later cancel signal the wrong (dead) pipeline.
            conversationService.conversationHitlService.populateToolApprovalsConfig(conversationMemory);
            try {
                abandoned = runGuardedConversationStep(loggingContext, conversationId, environment, conversationMemory,
                        registration.track(executeConversation), memoryStateAtSubmit, persistedState, processingTurn,
                        registration::dispatcherGaveUp);
                if (abandoned && lease == null && processingTurn != null && processingTurn.deferredDelivery.isPending()) {
                    // F2: the watchdog gave up while the turn's write may still be running;
                    // it must not land after its caller is told it may not have been stored
                    // (the delivery follows in the task's finally).
                    fenceAbandonedWrite(conversationId, conversationMemory);
                }
            } catch (RejectedExecutionException e) {
                // The runtime refused the body (its pool is saturated or shutting down), so
                // it never started and nothing of it ran. Answered busy rather than left to
                // escape to the coordinator, which dead-lettered it with no answer for its
                // caller.
                abandoned = false;
                conversationService.contextLogger.setLoggingContext(loggingContext);
                LOGGER.warnf("Turn of conversation %s not run: the runtime refused it (%s) — reporting it as busy; the input "
                        + "was not consumed", sanitize(conversationId), e.getMessage());
                return skipAsBusy(conversationMemory, loggingContext, skipNotifier);
            }
        } finally {
            registration.release(abandoned, processingTurn);
        }
        return null;
    }

    /**
     * Answers a turn that was not run as busy: {@code 409} with {@code Retry-After}
     * on REST, {@code done} with {@code IN_PROGRESS} on a stream. Its input was not
     * consumed, so a retry runs it normally.
     */
    private Void skipAsBusy(IConversationMemory memory, Map<String, String> loggingContext,
                            Consumer<IConversationMemory> skipNotifier) {
        conversationService.contextLogger.setLoggingContext(loggingContext);
        memory.setConversationState(ConversationState.IN_PROGRESS);
        if (skipNotifier != null) {
            skipNotifier.accept(memory);
        }
        return null;
    }

    /**
     * Records a turn's write failure for its task to drain and dead-letter (see
     * {@code call()}), but only while the dispatcher still waits for the turn.
     * <p>
     * The completion callback runs before the turn's future completes, so the
     * watchdog can give up while it is still persisting: past its cancel check,
     * then refused by the fence or by the revision guard once the dispatcher has
     * returned. Recorded then, the failure was drained by the conversation's next
     * turn, which was dead-lettered for a write it never made. The abandoned turn
     * is still logged and counted by the caller.
     */
    private static <E extends Exception> void recordForTheWaitingDispatcher(Map<String, E> turns, String conversationId,
                                                                            E failure, BooleanSupplier dispatcherGaveUp) {
        if (!dispatcherGaveUp.getAsBoolean()) {
            turns.put(conversationId, failure);
        }
    }

    /**
     * Cluster mode: tell the document this turn's lease has started, so a holder
     * whose lease expired earlier (a stalled node, an abandoned pipeline thread)
     * can no longer write — see {@link IConversationMemoryStore#raiseFence}.
     * <p>
     * Must run before the turn reads the conversation. A failure stops the turn
     * before it runs: without the raise, a predecessor that is still writing can
     * slip a step in between this turn's read and its run (A3). The lease is
     * checked on both sides of the raise, so a turn whose lease went while the
     * raise was in flight does not start either.
     *
     * @return {@code true} when the fence is raised and the lease still held
     */
    boolean fenceRaised(String conversationId, LeaseHandle lease) {
        Long fence = lease.fence();
        if (lease.isLost()) {
            LOGGER.warnf("Turn of conversation %s not run: its lease was lost before it started — reporting it as busy; "
                    + "the input was not consumed", sanitize(conversationId));
            return false;
        }
        try {
            conversationMemoryStore.raiseFence(conversationId, fence);
        } catch (Exception e) {
            LOGGER.warnf("Turn of conversation %s not run: could not raise its fence to %d (%s) — reporting it as busy; the "
                    + "input was not consumed", sanitize(conversationId), fence, e.getMessage());
            return false;
        }
        if (lease.isLost()) {
            LOGGER.warnf("Turn of conversation %s not run: its lease was lost while the fence was raised — reporting it as "
                    + "busy; the input was not consumed", sanitize(conversationId));
            return false;
        }
        return true;
    }

    /**
     * Abandons a running turn: raises the memory's cooperative-cancel flag, which
     * the pipeline checks before every lifecycle task and once more before the turn
     * commits its long-term side effects ({@code Conversation.isTurnDiscarded}).
     * <p>
     * The thread interrupt the watchdog also sends is not enough on its own: any
     * lower layer that swallows it (a client library, a
     * {@code Thread.interrupted()} in a retry loop) clears it for good, and the
     * abandoned turn then runs on to completion — model calls, tool calls and
     * user-memory writes included — for an outcome nobody will ever read. The flag
     * cannot be cleared by the work itself.
     * <p>
     * The one place for every "this turn is no longer wanted" signal that does not
     * come from a caller: the agent-timeout watchdog and a lost cluster lease.
     * Idempotent, and safe to call from any thread.
     */
    static void abandonTurn(IConversationMemory memory) {
        if (memory != null) {
            memory.setCancelled(true);
        }
    }

    /**
     * Keeps a turn's live memory registered in {@code inFlightConversations} for as
     * long as its pipeline can still run, so {@code /cancel} and the GDPR
     * {@code stopInFlightWork} sweep can reach it.
     * <p>
     * Normally that is exactly the time the dispatching thread waits for the turn.
     * After a watchdog timeout it is not: the dispatcher gives up and moves on, but
     * a pipeline whose interrupt was swallowed keeps running on its pool thread.
     * Unregistering at that point is what made a timed-out turn unreachable. So an
     * abandoned turn stays registered until its body actually returns, and the body
     * unregisters itself; a body that never started (cancelled while queued) is
     * unregistered by the dispatcher straight away.
     * <p>
     * Removal is value-conditional throughout: only this turn's own entry is
     * removed, never a newer turn's registration of the same conversation.
     */
    private final class InFlightRegistration {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final String conversationId;
        private final IConversationMemory memory;
        private final AtomicInteger bodyState = new AtomicInteger(PENDING);
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile boolean dispatcherGaveUp;
        /**
         * The turn's in-flight permit, handed over when the dispatcher gives up on a
         * body that is still running; returned by {@link #settle()} once that body
         * stops. Written before {@link #dispatcherGaveUp}, so a body that sees the flag
         * sees it.
         */
        private volatile ConversationService.ProcessingTurn heldPermit;

        private InFlightRegistration(String conversationId, IConversationMemory memory) {
            this.conversationId = conversationId;
            this.memory = memory;
            inFlightConversations.put(conversationId, memory);
        }

        /** Wraps the turn body so it can report when it really stops running. */
        private Callable<Void> track(Callable<Void> body) {
            return () -> {
                if (!bodyState.compareAndSet(PENDING, RUNNING)) {
                    // Released before it started: the dispatcher already abandoned it.
                    return null;
                }
                try {
                    return body.call();
                } finally {
                    bodyState.set(FINISHED);
                    if (dispatcherGaveUp) {
                        settle();
                    }
                }
            };
        }

        /**
         * Called once by the dispatching thread when it stops waiting for the turn.
         *
         * @param abandoned
         *            {@code true} when the dispatcher gave up on a turn that may still
         *            be running (watchdog timeout, interrupted wait)
         * @param processingTurn
         *            the turn's token, or {@code null}; an abandoned body that is still
         *            running keeps its in-flight permit until it stops
         */
        private void release(boolean abandoned, ConversationService.ProcessingTurn processingTurn) {
            if (!abandoned) {
                unregister();
                return;
            }
            // Counted before the flag is published, so settle() — reachable only once the
            // flag is set — always has an increment to pair with.
            abandonedTurnsRunning.incrementAndGet();
            // Handed over before the flag is published, for the same reason: whichever side
            // settles must find the permit. The turn task's finish() runs after this on the
            // same thread, and from here on returns only the gauge.
            if (processingTurn != null) {
                processingTurn.handPermitToBody();
                heldPermit = processingTurn;
            }
            // Order matters: publish the flag first, then read the body state. The body
            // does the reverse (state first, then the flag), so at least one side sees
            // the other's write and the entry is never stranded.
            dispatcherGaveUp = true;
            if (bodyState.compareAndSet(PENDING, FINISHED) || bodyState.get() == FINISHED) {
                settle();
            }
            // Otherwise the body is still running and settles when it returns.
        }

        /**
         * Whether the dispatching thread gave up on this turn (see {@link #release}).
         * Published before that thread returns, so before the coordinator can start the
         * conversation's next turn.
         */
        private boolean dispatcherGaveUp() {
            return dispatcherGaveUp;
        }

        /** Once only, from whichever side sees the abandoned body finished last. */
        private void settle() {
            if (settled.compareAndSet(false, true)) {
                unregister();
                abandonedTurnsRunning.decrementAndGet();
                ConversationService.ProcessingTurn permit = heldPermit;
                if (permit != null) {
                    permit.returnPermit();
                }
            }
        }

        private void unregister() {
            inFlightConversations.remove(conversationId, memory);
        }
    }

    /**
     * Runs one turn under the agent-timeout watchdog and persists its outcome.
     * <p>
     * <b>Ordering contract.</b> A turn that ends {@code AWAITING_HUMAN} must not be
     * announced to its client before the pause is durable. The pipeline's
     * completion callback therefore only <i>defers</i> that announcement (see
     * {@code ConversationService#completeTurn} and {@link DeferredDelivery}); this
     * method's {@code onComplete} commits the pause (compare-and-store from the
     * state the turn started in, cancel re-check, counter, timeout schedule) and
     * only then — from a {@code finally}, on every exit — delivers it with the
     * state the store actually holds. {@code onFailure} and the task's own
     * {@code finally} flush a still-pending delivery, and delivery is one-shot, so
     * the client's response / stream terminates exactly once on every path. Every
     * other say turn is deferred the same way (A1): it is answered once its write
     * succeeded, and a turn whose write failed — checked or unchecked exception,
     * size guard, fence, revision conflict — is answered as not stored
     * ({@link TurnNotStored}), never as done.
     * <p>
     * <b>The late outcome of an abandoned turn is inert.</b> Once the watchdog
     * gives up, this method returns, the task ends and the coordinator starts the
     * conversation's next turn, while a body whose interrupt was swallowed keeps
     * running. Its outcome, when it finally arrives, belongs to a turn whose state
     * the watchdog already recorded and whose caller is gone; the conversation now
     * belongs to the next turn. So it only refreshes the state cache: settled as a
     * cancelled turn, it stamped {@code EXECUTION_INTERRUPTED} over the
     * {@code READY} the next turn had committed, and in cluster mode its expired
     * lease dead-lettered the next turn as lease-lost after that turn had answered.
     * An outcome that arrives before the dispatcher gave up is settled as before:
     * no newer turn can exist yet. A persist the callback started before then and
     * that fails afterwards is inert as well: a refusal by the fence or the
     * revision guard is not recorded for the task to dead-letter (see
     * {@link #recordForTheWaitingDispatcher}), and a store failure does not stamp
     * {@code ERROR}.
     *
     * @param dispatcherGaveUp
     *            whether the dispatching thread has stopped waiting for this turn
     * @return {@code true} when the watchdog gave up on the turn (see
     *         {@link #waitForExecutionFinishOrTimeout})
     */
    boolean runGuardedConversationStep(Map<String, String> loggingContext, String conversationId,
                                       Environment environment, IConversationMemory conversationMemory,
                                       Callable<Void> executeConversation, ConversationState memoryStateAtSubmit,
                                       ConversationState preTurnPersistedState, ConversationService.ProcessingTurn processingTurn,
                                       BooleanSupplier dispatcherGaveUp) {
        return waitForExecutionFinishOrTimeout(loggingContext, conversationId, conversationMemory,
                runtime.submitCallable(executeConversation, new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Void result) {
                        // Set only once the pause is durably committed AND not cancel-raced.
                        // Drives what the deferred client completion reports (see finally).
                        boolean pauseCommitted = false;
                        // Set once the turn's own write succeeded (pause or settled turn); a
                        // failure after it is not a failed write.
                        boolean written = false;
                        // Set when the write failed: the caller is told the turn was not stored.
                        TurnError notStored = null;
                        // Set when the stored document still names owed keys that are now written.
                        boolean clearMarkers = false;
                        try {
                            // #2 (say path parity with resume): a concurrent cancel/end
                            // may have signalled this in-flight memory (e.g. a group
                            // handleMemberPause → cancelConversation that lost both
                            // state CAS races because this turn's pause was not yet
                            // persisted). Honor the cooperative-cancel flag: never
                            // persist AWAITING_HUMAN, arm a timeout, or count a pause
                            // nobody wants — that strands the approval. Persist
                            // EXECUTION_INTERRUPTED via CAS from the (non-terminal)
                            // running state so a cross-pod terminal writer (ENDED) still
                            // wins and a resurrected READY never lingers.
                            if (conversationMemory.isCancelled()) {
                                conversationService.contextLogger.setLoggingContext(loggingContext);
                                LOGGER.infof("Turn of conversation %s completed after a cancel signal — "
                                        + "discarding its outcome (no pause persisted/armed)", conversationId);
                                if (dispatcherGaveUp.getAsBoolean()) {
                                    // Abandoned by the watchdog: inert (see the method doc).
                                    refreshCachedState(conversationId);
                                    return;
                                }
                                if (discardLeaseLostTurn(conversationId, conversationMemory)) {
                                    // S2: discarded and dead-lettered — its caller must hear that,
                                    // not get the answer of a turn that is not stored.
                                    notStored = reportTurnNotStored(loggingContext, conversationMemory, TurnNotStored.Reason.FENCED,
                                            "lease lost");
                                    return;
                                }
                                settleCancelledTurn(conversationId);
                                return;
                            }
                            boolean awaitingHuman = conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN;
                            if (awaitingHuman && memoryStateAtSubmit == ConversationState.AWAITING_HUMAN) {
                                // Stale pause carried in from the loaded snapshot —
                                // this turn did not pause (it was skipped/rejected
                                // by the pipeline). Persisting would resurrect a
                                // terminally resolved approval as a zombie and
                                // re-arm its timeout. Drop the result.
                                conversationService.contextLogger.setLoggingContext(loggingContext);
                                LOGGER.warnf("Discarding turn result for conversation %s: snapshot carried a stale "
                                        + "AWAITING_HUMAN state this turn did not produce", conversationId);
                                refreshCachedState(conversationId);
                                return;
                            }
                            // #6: copy the agent's timeout policy into the bookmark
                            // BEFORE persisting so approval-status/pending-approvals
                            // report it and crash recovery can distinguish
                            // WAIT_INDEFINITELY pauses from lost-schedule ones.
                            if (awaitingHuman) {
                                conversationService.conversationHitlService.populateHitlTimeoutBookmark(conversationMemory);
                                // #3 (say-path parity with resume): a fresh pause commits
                                // the FULL AWAITING_HUMAN snapshot. The up-front
                                // isCancelled() check above narrows but does not close the
                                // race with a concurrent end/cancel (REST/admin thread, NOT
                                // serialized by the per-conversation coordinator): a terminal
                                // write (ENDED / EXECUTION_INTERRUPTED) can land between that
                                // check and this store, and an unconditional full-document
                                // replace would then overwrite the terminal state with a
                                // pending approval — resurrecting a terminated conversation
                                // with a live timeout. Persist ONLY while the conversation
                                // still holds the non-terminal state this turn started in —
                                // the say path never CAS's the DB state (Conversation.runStep
                                // sets IN_PROGRESS in-memory only), so the persisted state is
                                // whatever it was when the queued-say guard let this turn
                                // through: READY normally, or ERROR / EXECUTION_INTERRUPTED on
                                // a retry after a failed or interrupted prior turn (both are
                                // legal say-start states the guard deliberately admits). CAS
                                // from that exact baseline so a concurrent end/cancel that
                                // moved the state to a terminal value still wins; on a CAS
                                // miss, discard the pause outcome (no counter, no schedule) so
                                // the terminal state stands — mirrors the resume path's
                                // storeConversationMemoryIfState guard.
                                boolean persisted = storeConversationMemoryIfState(
                                        conversationMemory, environment, preTurnPersistedState, true);
                                if (!persisted) {
                                    conversationService.contextLogger.setLoggingContext(loggingContext);
                                    LOGGER.infof("Pause of conversation %s not persisted: a concurrent writer moved it "
                                            + "off %s or rewrote the document — discarding the pause outcome so the "
                                            + "committed write wins", conversationId, preTurnPersistedState);
                                    // E2: the completion callback cached AWAITING_HUMAN before this
                                    // commit ran. Left there, the state endpoint advertised a pending
                                    // approval the store never held, and /resume then failed.
                                    refreshCachedState(conversationId);
                                    return;
                                }
                                written = true;
                                // M2: close the cancel-during-commit window. A cancel that
                                // signalled setCancelled(true) AFTER the top-of-onComplete
                                // check but BEFORE the store above returned CANCELLED to its
                                // caller via cancelConversation's in-flight-signal branch
                                // WITHOUT flipping our (still-non-terminal) DB state — so the
                                // pause we just committed would strand as a live approval with
                                // an armed timer on a conversation the caller was told is
                                // cancelled. Now that the pause is durable, re-check the flag:
                                // if signalled, convert the committed AWAITING_HUMAN pause to
                                // EXECUTION_INTERRUPTED and skip the counter/schedule so nothing
                                // is armed and no approval is stranded.
                                if (conversationMemory.isCancelled()) {
                                    conversationService.contextLogger.setLoggingContext(loggingContext);
                                    if (conversationMemoryStore.compareAndSetState(conversationId,
                                            ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED)) {
                                        cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                                        try {
                                            conversationMemoryStore.clearHitlBookmark(conversationId);
                                        } catch (Exception e) {
                                            LOGGER.warnf("Failed to clear HITL bookmark on cancel-raced pause of %s: %s",
                                                    conversationId, e.getMessage());
                                        }
                                    }
                                    LOGGER.infof("Cancel raced the pause commit for conversation %s — converted the "
                                            + "committed pause to EXECUTION_INTERRUPTED (no counter, no timeout armed)",
                                            conversationId);
                                    return;
                                }
                                pauseCommitted = true;
                                // M1: a durably-committed pause is counted and its timeout armed.
                                conversationService.counterHitlPause.increment();
                                conversationService.conversationHitlService.scheduleHitlTimeout(conversationId, conversationMemory);
                                conversationService.firePauseCommitted(conversationId, conversationMemory);
                            } else {
                                // Non-pause completion is unchanged: the normal say turn
                                // settles to READY/ENDED/… and persists the full snapshot.
                                // A CONTINUE_CONVERSATION is decided before the store (the
                                // outcome is part of the committed step) and armed only once
                                // the store succeeded: a turn that is not committed — fenced,
                                // conflicted, failed to store — never queues its successor.
                                conversationService.decideContinuation(conversationMemory);
                                // Written, then the turn's long-term memory writes (A5) — the
                                // marker clean-up waits until the caller has its answer (F3).
                                storeOnly(conversationMemory, environment, true);
                                written = true;
                                clearMarkers = runDeferredWork(conversationMemory);
                                conversationService.armContinuation(conversationId, conversationMemory, environment);
                            }
                        } catch (ConversationFencedException e) {
                            // Cluster mode: another node took the conversation over while this
                            // turn ran (this node lost its lease), and has written since. The
                            // turn's write is refused — never retried, never flipped to ERROR,
                            // since the document belongs to the newer turn — and the turn is
                            // dead-lettered with its input (see call()).
                            conversationService.contextLogger.setLoggingContext(loggingContext);
                            LOGGER.errorf("Turn of conversation %s was NOT persisted: %s", sanitize(conversationId), e.getMessage());
                            conversationService.counterFenceRejected();
                            conversationService.forgetUnpersistedTurn(conversationId);
                            recordForTheWaitingDispatcher(fencedTurns, conversationId, e, dispatcherGaveUp);
                            refreshCachedState(conversationId);
                            notStored = reportTurnNotStored(loggingContext, conversationMemory, e);
                        } catch (ConcurrentConversationModificationException e) {
                            // The turn ran, its reply is held back until this persist (see
                            // ConversationService#completeTurn), and the document has since
                            // been rewritten by another writer: its caller is told the turn
                            // was not stored. Report it loudly instead of overwriting the
                            // winner — which is what happened before the revision guard
                            // existed, silently.
                            //
                            // Deliberately NOT routed through logConversationError: that
                            // flips the conversation to ERROR, and the document on disk here
                            // belongs to a writer that succeeded. Breaking a healthy
                            // conversation because THIS turn lost the race would trade one
                            // wrong outcome for another.
                            boolean lostRace = reportStoreConflict(loggingContext, conversationId, e);
                            if (lostRace) {
                                recordForTheWaitingDispatcher(conflictedTurns, conversationId, e, dispatcherGaveUp);
                            }
                            conversationService.forgetUnpersistedTurn(conversationId);
                            refreshCachedState(conversationId);
                            // S8: refused because the conversation was ended meanwhile — that
                            // is the end winning (410), not a race worth retrying (409).
                            notStored = lostRace
                                    ? reportTurnNotStored(loggingContext, conversationMemory, e)
                                    : reportTurnNotStored(loggingContext, conversationMemory, TurnNotStored.Reason.CONVERSATION_ENDED,
                                            e.getClass().getSimpleName());
                        } catch (ResourceStoreException e) {
                            failedWrite(loggingContext, conversationMemory, e, dispatcherGaveUp);
                            notStored = reportTurnNotStored(loggingContext, conversationMemory, e);
                        } catch (RuntimeException e) {
                            if (written) {
                                // Not the write: something after it (scheduling a pause's timeout).
                                // Surfaced through the Future as before.
                                throw e;
                            }
                            // An unchecked failure of the write itself — a driver exception such as
                            // MongoWriteException for a document over 16 MB. Handled exactly like a
                            // checked one: before, it bypassed forgetUnpersistedTurn and reached the
                            // caller only as a log line, after the caller had been answered.
                            failedWrite(loggingContext, conversationMemory, e, dispatcherGaveUp);
                            notStored = reportTurnNotStored(loggingContext, conversationMemory, e);
                        } finally {
                            // Ordering contract: a turn that ended AWAITING_HUMAN tells its
                            // client only NOW, after the pause commit above has succeeded
                            // or failed, so approval-status / pending-approvals / resume
                            // always find the pause the client was told about. A pause that
                            // was not committed is reported with the state the store holds.
                            // Runs on every exit (returns, conflicts, store errors); a no-op
                            // for turns that delivered immediately.
                            //
                            // Every other turn is delivered here as well (A1): a stored turn
                            // with the state it was stored with (no store read), a turn whose
                            // write failed as not stored — never as done — and a discarded
                            // one with the state the store holds.
                            if (!written) {
                                // The turn was not stored: its deferred long-term writes go with it.
                                discardDeferredWork(conversationMemory);
                            }
                            if (notStored != null) {
                                // Counted only when this delivery wins the claim (F5).
                                deliverNotStored(processingTurn, notStored);
                            } else if (processingTurn != null && processingTurn.deferredDelivery.isPending()) {
                                ConversationState delivered;
                                if (pauseCommitted) {
                                    delivered = ConversationState.AWAITING_HUMAN;
                                } else if (written && conversationMemory.getConversationState() != ConversationState.AWAITING_HUMAN) {
                                    delivered = null;
                                } else {
                                    delivered = committedStateOrError(conversationId);
                                }
                                processingTurn.deferredDelivery.deliver(delivered);
                            }
                            if (clearMarkers) {
                                // Bookkeeping after the answer (F3): the turn is stored.
                                clearOwedMarkers(conversationMemory, environment);
                            }
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        try {
                            onFailureInner(t);
                        } finally {
                            // The body completed its pipeline (and deferred its delivery)
                            // before the runtime routed it here: abandoned by the watchdog, or
                            // failed afterwards. This path never stores the turn, so its caller
                            // is told it was not stored (S1), never answered as done.
                            discardDeferredWork(conversationMemory);
                            flushDeferredDelivery(processingTurn, conversationMemory);
                        }
                    }

                    private void onFailureInner(Throwable t) {
                        // C3: an abandoned turn that completed anyway is routed here by
                        // BaseRuntime's abandonment token. It must NOT be flipped to
                        // ERROR — the watchdog already persisted the accurate
                        // EXECUTION_INTERRUPTED (or deliberately left an AWAITING_HUMAN
                        // pause alone), and a late ERROR write from the zombie turn is
                        // exactly the stale overwrite the token exists to prevent.
                        // Mirrors the resume path's onFailure.
                        //
                        // Matched on the DEDICATED abandonment type, not on the bare
                        // InterruptedException: a genuine InterruptedException thrown
                        // by the callable BODY means the turn never finished and no
                        // watchdog recorded anything, so it must take the error path
                        // below and leave an ERROR record — swallowing it at WARN left
                        // the conversation looking untouched after a real failure.
                        //
                        // A turn already flagged cancelled (the watchdog's abandonTurn, a
                        // /cancel, a GDPR stop) takes this branch whatever it failed with:
                        // its failure is the cancellation's own consequence — a model call
                        // aborted by the interrupt surfaces as a plain LifecycleException.
                        // Writing ERROR here raced the watchdog's EXECUTION_INTERRUPTED and
                        // could win. The state is recorded below by settleCancelledTurn,
                        // exactly as for a cancelled turn that completed.
                        if (t instanceof ExecutionAbandonedException || t instanceof LifecycleException.LifecycleInterruptedException
                                || conversationMemory.isCancelled()) {
                            String errorMessage = "Conversation processing got interrupted! (conversationId=%s)";
                            errorMessage = String.format(errorMessage, conversationId);
                            conversationService.contextLogger.setLoggingContext(loggingContext);
                            LOGGER.warn(errorMessage, t);
                            if (dispatcherGaveUp.getAsBoolean()) {
                                // The late outcome of a turn the watchdog abandoned: inert (see the
                                // method doc). The watchdog recorded its state; the next turn of
                                // the conversation may already have committed its own.
                                refreshCachedState(conversationId);
                            } else if (conversationMemory.isCancelled() && !discardLeaseLostTurn(conversationId, conversationMemory)) {
                                // Same settlement as a cancelled turn that completed: the
                                // pipeline's own failure handling may have cached ERROR on its
                                // way out, and for a /cancel or GDPR stop of a say turn nothing
                                // else records a state (the say path never persists IN_PROGRESS).
                                settleCancelledTurn(conversationId);
                            }
                        } else if (t instanceof IConversation.ConversationNotReadyException) {
                            String msg = "Conversation not ready! (conversationId=%s)";
                            msg = String.format(msg, conversationId);
                            conversationService.contextLogger.setLoggingContext(loggingContext);
                            // ": " rather than "\n": the throwable is passed as well, so
                            // %e prints the whole trace anyway, and a newline in a log
                            // MESSAGE is now escaped rather than printed (CWE-117) — it
                            // would render as a literal "\n" in the middle of the line.
                            LOGGER.error(msg + ": " + t.getLocalizedMessage(), t);
                        } else {
                            logConversationError(loggingContext, conversationId, t);
                        }
                    }
                }, null));
    }

    /**
     * The state the store holds for the conversation, or {@code ERROR} when it
     * cannot be read. Used to tell a client the truth about a paused turn whose
     * pause was not committed.
     */
    ConversationState committedStateOrError(String conversationId) {
        try {
            ConversationState state = conversationMemoryStore.getConversationState(conversationId);
            if (state != null) {
                return state;
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the committed state of conversation %s while completing a paused turn: %s",
                    sanitize(conversationId), e.getMessage());
        }
        return ConversationState.ERROR;
    }

    /**
     * A turn's write failed (checked or unchecked): its answer must not be replayed
     * to an {@code Idempotency-Key} retry, and the conversation is marked
     * {@code ERROR} — unless the watchdog had already given up on the turn, whose
     * state it recorded itself.
     */
    private void failedWrite(Map<String, String> loggingContext, IConversationMemory memory, Exception e, BooleanSupplier dispatcherGaveUp) {
        String conversationId = memory.getConversationId();
        conversationService.forgetUnpersistedTurn(conversationId);
        // The turn's outcome is a failure, whatever state its pipeline ended in: the
        // turn-outcome report (alerts, Agent Health) reads it off the memory.
        memory.setConversationState(ConversationState.ERROR);
        if (dispatcherGaveUp.getAsBoolean()) {
            // Abandoned while it persisted: inert (see runGuardedConversationStep). The
            // watchdog recorded its state, and ERROR would land on the next turn's.
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.warnf("Turn of conversation %s, abandoned by the watchdog, was not persisted: %s",
                    sanitize(conversationId), e.getMessage());
            refreshCachedState(conversationId);
        } else {
            logConversationError(loggingContext, conversationId, e);
        }
    }

    /**
     * Runs what the turn deferred until it is stored (A5: its long-term property
     * writes to the user memory store, see
     * {@link IConversationMemory#deferUntilCommitted}) — from the store methods
     * themselves, right after a successful write, so every path that stores a turn
     * (say, the conversation's start turn, resume) runs it and none can forget to.
     * Never throws.
     * <p>
     * The stored document still names the keys the write owes
     * ({@code pendingLongTermWrites}, with the time this conversation changed
     * each). If the write fails, or this node dies before it, the next turn finds
     * them and makes them up — unless another conversation stored a newer value
     * meanwhile ({@code Conversation#isNewerElsewhere}). The stored turn is not
     * reported as failed.
     *
     * @return whether the stored document still names keys that are now written, so
     *         {@link #clearOwedMarkers} should run
     */
    private boolean runDeferredWork(IConversationMemory memory) {
        IConversationMemory.AfterCommit action = memory.takeAfterCommit();
        if (action == null) {
            return false;
        }
        boolean storedOwedMarkers = !memory.getPendingLongTermWrites().isEmpty();
        try {
            action.run();
        } catch (Exception e) {
            conversationService.countLongTermWrite("failed");
            LOGGER.warnf(e, "Turn of conversation %s is stored, but its long-term memory write failed; the keys it owes stay "
                    + "recorded in the conversation and are made up by its next turn", sanitize(memory.getConversationId()));
            return false;
        }
        return storedOwedMarkers && memory.getPendingLongTermWrites().isEmpty() && memory.getConversationId() != null;
    }

    /**
     * Clears the owed long-term markers from the stored document once their write
     * succeeded: a conditional write of the document (same state, same revision).
     * Bookkeeping — a say turn's caller is answered before it (F3). A miss (the
     * conversation changed meanwhile) is benign and neither logged as a conflict
     * nor counted as one: the next turn finds the markers, sees the values already
     * stored and writes nothing. Never throws.
     */
    void clearOwedMarkers(IConversationMemory memory, Environment environment) {
        try {
            var snapshot = convertConversationMemory(memory);
            snapshot.setEnvironment(environment);
            if (conversationMemoryStore.storeConversationMemorySnapshotIfState(snapshot, memory.getConversationState())) {
                memory.setRevision(snapshot.getRevision());
                memory.setPersistedStepCount(snapshot.getPersistedStepCount());
            } else {
                LOGGER.debugf("Owed long-term markers of conversation %s not cleared (the conversation changed)",
                        sanitize(memory.getConversationId()));
            }
        } catch (ResourceStoreException | RuntimeException e) {
            LOGGER.debugf("Owed long-term markers of conversation %s not cleared (%s)", sanitize(memory.getConversationId()),
                    e.getMessage());
        }
    }

    /**
     * A turn that is not stored drops what it deferred until its store — its
     * long-term writes must not outlive it. Counted, so a path that stores a turn
     * without going through the store methods (and so silently drops the writes)
     * shows up as {@code eddi_turn_long_term_writes_total{outcome="discarded"}}
     * rising on turns that did succeed.
     */
    void discardDeferredWork(IConversationMemory memory) {
        if (memory != null && memory.takeAfterCommit() != null) {
            conversationService.countLongTermWrite("discarded");
            LOGGER.debugf("Long-term memory writes of conversation %s dropped with its unstored turn",
                    sanitize(memory.getConversationId()));
        }
    }

    /**
     * Classifies a failed write and logs it with the conversation, the agent and
     * the reason. Not counted here: only the delivery that wins the claim counts
     * (see {@link #deliverNotStored}).
     *
     * @return the error the turn's caller is given instead of its answer
     */
    TurnError reportTurnNotStored(Map<String, String> loggingContext, IConversationMemory memory, Throwable failure) {
        return reportTurnNotStored(loggingContext, memory, TurnNotStored.classify(failure), failure.getClass().getSimpleName());
    }

    /** As above, for a reason that is already known. */
    TurnError reportTurnNotStored(Map<String, String> loggingContext, IConversationMemory memory, TurnNotStored.Reason reason,
                                  String cause) {
        if (loggingContext != null) {
            conversationService.contextLogger.setLoggingContext(loggingContext);
        }
        LOGGER.warnf("Turn of conversation %s (agent %s, version %s) ran but was NOT (or not verifiably) stored: reason=%s (%s) — its "
                + "caller is told so instead of receiving the answer", sanitize(memory.getConversationId()), sanitize(memory.getAgentId()),
                memory.getAgentVersion(), reason.tag(), cause);
        return reason.error();
    }

    /**
     * Claims the turn's delivery for a not-stored answer and counts it
     * ({@code eddi_turn_not_stored_total}) — only if the claim succeeds, so a turn
     * another path already answered (stored, or with a more precise reason) is
     * neither re-answered nor counted (F5). Without a deferred delivery (no client
     * waits) it is counted all the same.
     */
    private void deliverNotStored(ConversationService.ProcessingTurn processingTurn, TurnError error) {
        if (processingTurn == null || processingTurn.deferredDelivery.deliverNotStored(error)) {
            conversationService.countTurnNotStored(TurnNotStored.reasonOf(error));
        }
    }

    /**
     * The backstop delivery of a turn whose persistence step never delivered it:
     * the watchdog gave up while it ran or wrote, the runtime routed it to
     * {@code onFailure}, or it was dropped. Whether it was stored is not known
     * here, so it is never answered as done (S1): it is reported as not stored —
     * {@code fenced} when its lease was lost, otherwise {@code outcome_unknown}.
     * The reason is decided before the claim, and only the claimant reports it.
     */
    private void flushDeferredDelivery(ConversationService.ProcessingTurn processingTurn, IConversationMemory memory) {
        if (processingTurn == null || !processingTurn.deferredDelivery.isPending()) {
            return;
        }
        String conversationId = memory.getConversationId();
        TurnNotStored.Reason reason = leaseLostTurns.containsKey(conversationId) || memory.isLeaseLost()
                ? TurnNotStored.Reason.FENCED
                : TurnNotStored.Reason.OUTCOME_UNKNOWN;
        if (processingTurn.deferredDelivery.deliverNotStored(reason.error())) {
            conversationService.countTurnNotStored(reason);
            reportTurnNotStored(null, memory, reason, "no confirmed write");
        }
    }

    /**
     * Single node (F2): the turn's write is still running although its caller was
     * told it may not be stored — a late write must lose, or it lands next to the
     * caller's retry as a duplicate turn. Every single-node turn writes with the
     * fencing token of the document it loaded (see {@link #runConversationStep}),
     * so raising the document's fence above it makes the store refuse the late
     * write; the conversation's next turn on this node writes with the raised token
     * ({@link #raisedFences}). Only on this path, never on a turn that delivered
     * normally: no extra round trip on the happy path.
     */
    void fenceAbandonedWrite(String conversationId, IConversationMemory live) {
        Long token = live != null ? live.getFenceToken() : null;
        if (token == null) {
            return;
        }
        long raised = token + 1;
        try {
            conversationMemoryStore.raiseFence(conversationId, raised);
            raisedFences.merge(conversationId, raised, Math::max);
        } catch (ResourceStoreException | RuntimeException e) {
            LOGGER.warnf("Could not fence the abandoned write of conversation %s (%s); a late write may still land",
                    sanitize(conversationId), e.getMessage());
        }
    }

    /**
     * Single node: fences raised by {@link #fenceAbandonedWrite}, so the
     * conversation's next turn — loaded before the raise, i.e. already queued —
     * writes with a token the store accepts. Consumed when that turn starts; any
     * later turn loads the raised {@code _fence} from the store and needs no entry.
     * Bounded (see {@link #raisedFenceRetention}), so a conversation abandoned once
     * and never continued does not keep its entry for the life of the JVM.
     */
    private final ConcurrentMap<String, Long> raisedFences;

    /** At most this many conversations with a raised fence are remembered. */
    static final int RAISED_FENCES_MAX = 10_000;

    /** Queued turns per conversation the retention allows for (with headroom). */
    private static final int RAISED_FENCE_QUEUED_TURNS = 25;

    /** Floor of the retention, whatever the agent timeout. */
    private static final Duration RAISED_FENCE_MIN_RETENTION = Duration.ofMinutes(10);

    /**
     * How long a raised fence is remembered: long enough for every turn that was
     * already queued behind the abandoned one to start. A queued turn starts once
     * the turns ahead of it end, and each of those ends at the latest when the
     * watchdog gives up on it ({@code systemRuntime.agentTimeoutInSeconds}). With
     * {@code eddi.conversation.max-queued-turns-per-conversation} at its default of
     * 20, 25 watchdog periods cover a full queue; never below ten minutes. A turn
     * that starts later still loaded the document after the raise, so a forgotten
     * entry costs it nothing.
     */
    static Duration raisedFenceRetention(int agentTimeoutSeconds) {
        Duration byQueue = Duration.ofSeconds(Math.max(0, agentTimeoutSeconds) * (long) RAISED_FENCE_QUEUED_TURNS);
        return byQueue.compareTo(RAISED_FENCE_MIN_RETENTION) > 0 ? byQueue : RAISED_FENCE_MIN_RETENTION;
    }

    /**
     * The fence remembered for {@code conversationId}, or {@code null}; for tests.
     */
    Long rememberedFence(String conversationId) {
        return raisedFences.get(conversationId);
    }
    /**
     * Cluster mode: a turn cancelled because this node lost its lease, whether it
     * then completed or failed. The conversation belongs to whoever holds the lease
     * now (or to its next turn), so this turn stamps no state on it — the
     * {@link #settleCancelledTurn} CAS from the running state would overwrite the
     * state the new holder committed with {@code EXECUTION_INTERRUPTED}. It is
     * recorded instead for the task, which dead-letters it ({@code lease-lost}),
     * and the reply path answers its caller 409.
     *
     * @return {@code true} when the turn lost its lease and was handled here;
     *         {@code false} for every other cancelled turn, which the caller
     *         settles
     */
    private boolean discardLeaseLostTurn(String conversationId, IConversationMemory conversationMemory) {
        // Either the lease manager's lost-callback marked it, or the lease reads as
        // lost on the turn's own memory (it can be lost before the callback arrives —
        // see IConversationMemory#setLeaseLostCheck).
        boolean marked = leaseLostConversations.remove(conversationId);
        if (!marked && !conversationMemory.isLeaseLost()) {
            return false;
        }
        LOGGER.warnf("Turn of conversation %s lost its lease — its outcome is discarded, the conversation state is "
                + "left to the turn that holds it now, and the turn is dead-lettered", sanitize(conversationId));
        leaseLostTurns.put(conversationId, new TurnLeaseLostException(conversationId, conversationMemory.getFenceToken()));
        refreshCachedState(conversationId);
        return true;
    }

    /**
     * Records the state of a turn that ran to its end (completed or failed) after a
     * cancel signal: {@code EXECUTION_INTERRUPTED}, by CAS from the running state
     * so a terminal state another writer committed (ENDED, the watchdog's own
     * EXECUTION_INTERRUPTED) stands, and the state cache is made to agree with the
     * store either way.
     * <p>
     * The watchdog has written the state before this runs; {@code /cancel} and the
     * GDPR stop have not — they only raise the flag of a running say turn, whose
     * persisted state is still READY — so without this a cancelled turn that failed
     * left the conversation READY, and one that completed was handled here already.
     */
    void settleCancelledTurn(String conversationId) {
        try {
            ConversationState runningState = conversationMemoryStore.getConversationState(conversationId);
            if ((runningState == ConversationState.READY || runningState == ConversationState.IN_PROGRESS)
                    && conversationMemoryStore.compareAndSetState(conversationId,
                            runningState, ConversationState.EXECUTION_INTERRUPTED)) {
                cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                return;
            }
        } catch (ResourceStoreException | RuntimeException e) {
            // Not an ERROR for the conversation: the turn was cancelled, and whoever
            // cancelled it got their answer. Leave the store as it is.
            LOGGER.warnf("Could not record EXECUTION_INTERRUPTED for cancelled conversation %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
        // The completion callback may already have cached this turn's own end state;
        // replace it with the persisted one.
        refreshCachedState(conversationId);
    }

    /**
     * Waits for a submitted turn within the agent timeout.
     *
     * @param memory
     *            the turn's live memory; when the wait gives up it is flagged
     *            cancelled (see {@link #abandonTurn}) so the pipeline stops at its
     *            next boundary even when its interrupt is swallowed
     * @return {@code true} when the wait gave up on the turn (timeout or an
     *         interrupted wait) and the turn may still be running; {@code false}
     *         when the turn finished, successfully or not
     */
    boolean waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId,
                                            IConversationMemory memory, Future<Void> future) {
        TurnWait wait = awaitTurn(loggingContext, conversationId, memory, future);
        reportTurnOutcome(memory, wait);
        return wait == TurnWait.TIMED_OUT || wait == TurnWait.INTERRUPTED || wait == TurnWait.ABANDONED_WHILE_PAUSED;
    }

    /** How the wait for a turn ended. */
    enum TurnWait {
        /** The turn finished; its outcome is on the memory. */
        COMPLETED,
        /** The turn failed with an exception the runtime handed back. */
        FAILED,
        /** The agent timeout expired; the turn was abandoned. */
        TIMED_OUT,
        /** The waiting thread was interrupted; the turn was abandoned. */
        INTERRUPTED,
        /**
         * The wait gave up, but a pause had already been committed and was left alone.
         */
        ABANDONED_WHILE_PAUSED
    }

    /**
     * The single point where every turn's outcome is final — the say path and the
     * HITL resume path both wait here, and the runtime runs the turn's completion
     * callbacks before {@code Future.get} returns. Reports a bad outcome as a
     * {@link TurnOutcome} event (the alert hook); a clean turn costs one state
     * check. Reads in-memory data only, and never throws: alerting must not be able
     * to fail or slow a turn.
     */
    void reportTurnOutcome(IConversationMemory memory, TurnWait wait) {
        try {
            ErrorKind kind = classifyTurnOutcome(memory, wait);
            if (kind != null && memory != null) {
                conversationService
                        .reportTurnOutcome(new TurnOutcome(memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(), kind));
            } else if (wait == TurnWait.COMPLETED && memory != null && memory.getConversationState() != ConversationState.AWAITING_HUMAN) {
                // A clean turn: the OUTPUT_MATCH hook checks its cache and, for an agent
                // without such rules, does nothing else.
                conversationService.reportCleanTurn(memory);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not report the outcome of a turn: %s", e.getMessage());
        }
    }

    /**
     * The alert kind of a turn's outcome, or {@code null} for a clean one (or a
     * pause, which is not a failure).
     */
    static ErrorKind classifyTurnOutcome(IConversationMemory memory, TurnWait wait) {
        switch (wait) {
            case TIMED_OUT :
                return ErrorKind.TURN_DEADLINE;
            case INTERRUPTED :
                return ErrorKind.TURN_INTERRUPTED;
            case ABANDONED_WHILE_PAUSED :
                return null;
            default :
                break;
        }
        if (memory == null) {
            return null;
        }
        if (memory.isCancelled()) {
            return ErrorKind.TURN_INTERRUPTED;
        }
        ConversationState state = memory.getConversationState();
        if (wait == TurnWait.FAILED || state == ConversationState.ERROR) {
            return ErrorKind.TURN_ERROR;
        }
        if (state == ConversationState.EXECUTION_INTERRUPTED) {
            return ErrorKind.TURN_INTERRUPTED;
        }
        IConversationMemory.IWritableConversationStep step = memory.getCurrentStep();
        if (step == null) {
            return null;
        }
        List<IData<Object>> refusals = step.getAllData(MemoryKeys.LLM_SPEND_REFUSED_PREFIX);
        if (refusals == null || refusals.isEmpty()) {
            return null;
        }
        boolean ceiling = false;
        for (IData<Object> refusal : refusals) {
            if (refusal != null && refusal.getResult() instanceof Map<?, ?> record
                    && "conversation_ceiling".equals(String.valueOf(record.get("reason")))) {
                ceiling = true;
            }
        }
        return ceiling ? ErrorKind.COST_CEILING : ErrorKind.TENANT_BUDGET;
    }

    private TurnWait awaitTurn(Map<String, String> loggingContext, String conversationId, IConversationMemory memory, Future<Void> future) {
        try {
            future.get(agentTimeout, TimeUnit.SECONDS);
            return TurnWait.COMPLETED;
        } catch (TimeoutException | InterruptedException e) {
            // C3: abandon the turn FIRST — before any further store round trip. The
            // cancel marks BaseRuntime's per-submission abandonment token, which is
            // what suppresses a late onComplete (and therefore its full-snapshot
            // persist). The interrupt flag alone is NOT a safe completion guard: the
            // pipeline consumes it via Thread.interrupted(), which CLEARS it, so a
            // timed-out turn would still be reported complete and would overwrite a
            // newer turn's state. Doing this before reading the persisted state keeps
            // the "already abandoned but not yet flagged" window at ~0 instead of a
            // full DB round trip.
            //
            // Cancelling on the AWAITING_HUMAN path too is deliberate: the watchdog
            // has expired either way, so this turn's outcome must be discarded. Only
            // the STATE write below is skipped there, to avoid overwriting a pause
            // written by another writer with EXECUTION_INTERRUPTED (Invariant 10).
            //
            // B2: Future.get CLEARS the interrupt flag when it throws
            // InterruptedException. The flag is restored in the finally below —
            // deliberately AFTER the store round trips, not before them: a set flag
            // makes the sync Mongo driver abort with MongoInterruptedException, which
            // would skip the very EXECUTION_INTERRUPTED write this branch exists to
            // perform. The finally also covers the AWAITING_HUMAN early return.
            //
            // The cooperative-cancel flag goes up before the interrupt: the interrupt
            // can be swallowed by any layer below, the flag cannot.
            try {
                abandonTurn(memory);
                future.cancel(true);
                ConversationState currentState = conversationMemoryStore.getConversationState(conversationId);
                if (currentState == ConversationState.AWAITING_HUMAN) {
                    return TurnWait.ABANDONED_WHILE_PAUSED;
                }
                // Compare-and-set, never a blind write: the conversation may have been
                // ended while the turn hung (an end only raises the cooperative-cancel
                // flag, which a blocked model call does not see). EXECUTION_INTERRUPTED
                // is auto-recovered on the next say, so overwriting ENDED with it made an
                // ended conversation drivable again — and invisible to the ENDED-only
                // retention sweep.
                transitionUnlessEnded(conversationId, currentState, ConversationState.EXECUTION_INTERRUPTED);
                String errorMessage = "Execution of Workflows interrupted or timed out.";
                conversationService.contextLogger.setLoggingContext(loggingContext);
                LOGGER.error(errorMessage, e);
            } finally {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            return e instanceof TimeoutException ? TurnWait.TIMED_OUT : TurnWait.INTERRUPTED;
        } catch (ExecutionException e) {
            logConversationError(loggingContext, conversationId, e);
            return TurnWait.FAILED;
        }
    }

    /**
     * A turn whose persist lost the optimistic-concurrency race. Counts it on
     * {@code eddi_conversation_store_conflict_count} and logs it at ERROR naming
     * the revision the turn was built on, so the loss is attributable in metrics
     * and in the log instead of being invisible.
     * <p>
     * The conversation state is left alone on purpose — see the call site.
     */
    boolean reportStoreConflict(Map<String, String> loggingContext, String conversationId,
                                ConcurrentConversationModificationException e) {
        conversationService.contextLogger.setLoggingContext(loggingContext);
        ConversationState storedState;
        try {
            storedState = conversationMemoryStore.getConversationState(conversationId);
        } catch (RuntimeException readFailure) {
            // A store blip must not escape the completion callback — the caller still
            // refreshes the cache after this — so report it as the conflict it may be.
            storedState = null;
        }
        if (storedState == ConversationState.ENDED) {
            // E4: the store refuses to write a running turn over a conversation that was
            // ended meanwhile. That is the end winning, not a lost update — nothing to
            // retry, and nothing for the conflict meter.
            LOGGER.infof("Turn of conversation %s was not persisted: the conversation was ended while the turn was "
                    + "running, and the end stands", sanitize(conversationId));
            return false;
        }
        conversationService.counterConversationStoreConflict.increment();
        LOGGER.errorf(e, "Turn of conversation %s was NOT persisted: the conversation document was written by another "
                + "turn, resume, undo/redo or instance while this turn was running (this turn started from revision %d). "
                + "Its caller is told the turn was not stored and should send it again.",
                conversationId, e.getExpectedRevision());
        return true;
    }

    void logConversationError(Map<String, String> loggingContext, String conversationId, Throwable t) {
        // Not over an ENDED conversation, for the reason given in awaitTurn: ERROR is
        // not terminal either, and a blind write resurrected a conversation ended
        // meanwhile.
        ConversationState currentState;
        try {
            currentState = conversationMemoryStore.getConversationState(conversationId);
        } catch (RuntimeException readFailure) {
            currentState = null;
        }
        boolean recorded = transitionUnlessEnded(conversationId, currentState, ConversationState.ERROR);
        // The state the conversation really has now (A13): ERROR only when the CAS
        // above recorded it; otherwise whatever it was (ENDED, a concurrent writer's
        // state, or unknown when it could not be read).
        String msg = "Error while processing user input (conversationId=%s , conversationState=%s)";
        msg = String.format(msg, conversationId, recorded
                ? ConversationState.ERROR
                : (currentState != null ? currentState + " (ERROR not recorded)" : "unknown (ERROR not recorded)"));
        conversationService.contextLogger.setLoggingContext(loggingContext);
        LOGGER.error(msg, t);
    }

    IConversationMemory loadAndValidateConversationMemory(String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, IConversationService.AgentMismatchException {
        var conversationMemory = loadConversationMemory(conversationId);
        checkConversationMemoryNotNull(conversationMemory, conversationId);

        if (!agentId.equals(conversationMemory.getAgentId())) {
            throw new IConversationService.AgentMismatchException("Supplied agentId is incompatible to conversationId");
        }

        return conversationMemory;
    }

    IConversationMemory loadConversationMemory(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return convertConversationMemorySnapshot(conversationMemorySnapshot);
    }

    /**
     * Moves the conversation from {@code observedState} to {@code target} with a
     * compare-and-set, unless it is {@code ENDED} (or unknown). When the CAS loses
     * — the state changed after it was read — the store's value stands and the
     * cache is refreshed from it.
     *
     * @return whether {@code target} was recorded
     */
    boolean transitionUnlessEnded(String conversationId, ConversationState observedState, ConversationState target) {
        if (observedState != null && observedState != ConversationState.ENDED) {
            try {
                if (conversationMemoryStore.compareAndSetState(conversationId, observedState, target)) {
                    cacheConversationState(conversationId, target);
                    return true;
                }
            } catch (ResourceStoreException | RuntimeException e) {
                LOGGER.warnf("Could not record %s for conversation %s: %s", target, sanitize(conversationId), sanitize(e.getMessage()));
            }
        }
        refreshCachedState(conversationId);
        return false;
    }

    void setConversationState(String conversationId, ConversationState conversationState) {
        conversationMemoryStore.setConversationState(conversationId, conversationState);
        cacheConversationState(conversationId, conversationState);
    }

    void cacheConversationState(String conversationId, ConversationState conversationState) {
        if (conversationState == null) {
            // Caffeine rejects a null value with a bare NullPointerException, and
            // ConversationService.getConversationState caches BEFORE its own "no such
            // conversation" check — so a conversation that does not exist produced a
            // 500 from inside the cache instead of reaching the line that throws
            // ConversationNotFoundException. There is also nothing to cache: "not
            // found" is not a state, and caching it would keep it being served for the
            // TTL even after the conversation appeared.
            return;
        }
        conversationStateCache.put(conversationId, conversationState);
    }

    /**
     * The current memory of the conversation when the stored document has moved on
     * from the revision {@code memory} was loaded at, else {@code null} (the memory
     * is current, or it could not be determined — the turn then runs on what it
     * has, as it always did).
     */
    IConversationMemory reloadIfSuperseded(String conversationId, IConversationMemory memory, Map<String, String> loggingContext) {
        return reloadIfSuperseded(conversationId, memory, loggingContext, null, false);
    }

    /**
     * @param knownRevision
     *            the stored revision when the caller has already read it
     * @param revisionKnown
     *            whether {@code knownRevision} was read (it may legitimately be
     *            {@code null}: "unknown"); when {@code false} it is read here
     */
    IConversationMemory reloadIfSuperseded(String conversationId, IConversationMemory memory, Map<String, String> loggingContext,
                                           Long knownRevision, boolean revisionKnown) {
        try {
            Long storedRevision = revisionKnown ? knownRevision : conversationMemoryStore.getRevision(conversationId);
            if (storedRevision == null || storedRevision == memory.getRevision()) {
                return null;
            }
            IConversationMemory reloaded = loadConversationMemory(conversationId);
            if (reloaded == null) {
                return null;
            }
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.debugf("Queued turn of conversation %s was loaded at revision %d; the conversation is now at %d — "
                    + "running it on the current document", sanitize(conversationId), memory.getRevision(), reloaded.getRevision());
            return reloaded;
        } catch (Exception e) {
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.warnf("Could not check whether the queued turn of conversation %s is current (%s) — running it on the "
                    + "memory it was submitted with", sanitize(conversationId), e.getMessage());
            return null;
        }
    }

    /**
     * Re-reads the persisted state into the state cache. The say path's completion
     * callback caches the state the turn ENDED with — from inside the pipeline,
     * before the persist — so whenever the persist is then refused or skipped, the
     * cache holds a state the store never saw (typically AWAITING_HUMAN for a pause
     * that was discarded, which made {@code /resume} fail against a READY
     * conversation while the state endpoint reported a pending approval).
     */
    void refreshCachedState(String conversationId) {
        try {
            ConversationState persisted = conversationMemoryStore.getConversationState(conversationId);
            if (persisted == null) {
                conversationStateCache.remove(conversationId);
            } else {
                conversationStateCache.put(conversationId, persisted);
            }
        } catch (RuntimeException e) {
            // Unknown is better served by the store than by a value known to be wrong.
            conversationStateCache.remove(conversationId);
        }
    }

    /**
     * Stores the turn, then runs what it deferred until it is stored (A5; M1 — the
     * conversation's start turn comes through here too) and clears the owed
     * long-term markers once their write succeeded.
     */
    String storeConversationMemory(IConversationMemory conversationMemory, Environment environment) throws ResourceStoreException {
        String conversationId = storeOnly(conversationMemory, environment, true);
        if (runDeferredWork(conversationMemory)) {
            clearOwedMarkers(conversationMemory, environment);
        }
        return conversationId;
    }

    /**
     * The write itself.
     *
     * @param sizeGuarded
     *            apply {@link #requireWithinDocumentLimit}
     */
    String storeOnly(IConversationMemory conversationMemory, Environment environment, boolean sizeGuarded) throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        if (sizeGuarded) {
            requireWithinDocumentLimit(memorySnapshot);
        }
        var conversationId = conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
        // The store stamped the revision it created. Carry it back so a second write
        // from this same live memory (a start turn that stores and is then stored
        // again, a resume that commits twice) presents the revision it just created
        // rather than the superseded one it loaded.
        conversationMemory.setRevision(memorySnapshot.getRevision());
        // Same for the append baseline, as the store reports it: the step count the
        // document now holds when this memory mirrors it, or UNKNOWN after a merged
        // append (the document then also holds another turn's steps, and the store left
        // the revision on the loaded value so a second write from this memory is
        // refused rather than allowed to erase them).
        conversationMemory.setPersistedStepCount(memorySnapshot.getPersistedStepCount());
        // F1: a conversation created by this write (its start turn) gets its id, so the
        // marker clean-up — and anything else that writes this memory again — can
        // address the document.
        conversationMemory.assignConversationId(conversationId);
        return conversationId;
    }

    /**
     * The size guard (A2): refuses a write whose document would exceed
     * {@code eddi.conversations.max-document-bytes}, before it reaches the database
     * — on both backends, so a conversation behaves the same on MongoDB (whose own
     * limit is 16 MB) and PostgreSQL. The estimate walks the snapshot (see
     * {@link ConversationDocumentSize}); it does not serialise it.
     *
     * @throws ConversationTooLargeException
     *             when the document would be too large; nothing is written
     */
    void requireWithinDocumentLimit(ConversationMemorySnapshot snapshot) throws ConversationTooLargeException {
        long limit = conversationService.maxConversationDocumentBytes;
        if (limit <= 0 || snapshot == null) {
            return;
        }
        long estimated = ConversationDocumentSize.estimate(snapshot);
        if (estimated > limit) {
            throw new ConversationTooLargeException(snapshot.getConversationId(), estimated, limit);
        }
    }

    /**
     * Persist the full memory snapshot only while the conversation is still in
     * {@code expectedState} and still holds the revision this memory was loaded at
     * — an atomic compare-and-store on both. Used by the resume and undo/redo paths
     * so a resumed or undone outcome cannot clobber an ENDED/EXECUTION_INTERRUPTED
     * state written concurrently by end/cancel, nor a step appended concurrently by
     * a say turn.
     * <p>
     * Not size-guarded (S6): undo/redo do not grow the document, and the write of a
     * resumed pause cannot be refused without losing the pause — a conversation
     * that crossed {@code eddi.conversations.max-document-bytes} (an upgrade puts
     * existing ones there) keeps those working; its next say turn is refused at
     * admission instead.
     */
    boolean storeConversationMemoryIfState(IConversationMemory conversationMemory, Environment environment,
                                           ConversationState expectedState)
            throws ResourceStoreException {
        return storeConversationMemoryIfState(conversationMemory, environment, expectedState, false);
    }

    /**
     * @param sizeGuarded
     *            apply {@link #requireWithinDocumentLimit} — for the write of a say
     *            turn (its pause commit), which grows the document
     */
    boolean storeConversationMemoryIfState(IConversationMemory conversationMemory, Environment environment,
                                           ConversationState expectedState, boolean sizeGuarded)
            throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        if (sizeGuarded) {
            requireWithinDocumentLimit(memorySnapshot);
        }
        boolean stored = conversationMemoryStore.storeConversationMemorySnapshotIfState(memorySnapshot, expectedState);
        if (stored) {
            conversationMemory.setRevision(memorySnapshot.getRevision());
            conversationMemory.setPersistedStepCount(memorySnapshot.getPersistedStepCount());
            // A resumed turn's long-term writes (A5).
            if (runDeferredWork(conversationMemory)) {
                clearOwedMarkers(conversationMemory, environment);
            }
        } else {
            diagnoseConditionalStoreMiss(conversationMemory, expectedState);
        }
        return stored;
    }

    /**
     * A conditional store now misses for one of two reasons, and every caller (the
     * say-path pause commit, HITL resume, undo, redo) was written when there was
     * only one: "a concurrent end/cancel moved the state". The other is a revision
     * conflict — another turn, undo or instance rewrote the document while the
     * state stayed the same — which is exactly the lost update this guard exists to
     * catch. Without telling them apart, a revision conflict on these paths would
     * be logged as a benign state handover and never reach
     * {@code eddi_conversation_store_conflict_count}, i.e. it would be silent
     * again.
     * <p>
     * One point-read of the state, only on a miss. If the stored state still equals
     * the expected one, the revision is what failed. (The state can move between
     * the miss and this read; the diagnosis then errs towards "state changed",
     * which is the pre-existing message, never towards inventing a conflict.)
     */
    private void diagnoseConditionalStoreMiss(IConversationMemory conversationMemory, ConversationState expectedState) {
        String conversationId = conversationMemory.getConversationId();
        if (conversationId == null || expectedState == null) {
            return;
        }
        ConversationState storedState = conversationMemoryStore.getConversationState(conversationId);
        if (storedState == expectedState) {
            conversationService.counterConversationStoreConflict.increment();
            LOGGER.warnf("Conditional write of conversation %s was NOT persisted: the state is still %s, but the document "
                    + "was rewritten by another turn, undo/redo, resume or instance since this write loaded revision %d",
                    sanitize(conversationId), expectedState, conversationMemory.getRevision());
        }
    }

    /**
     * @param conversationId
     *            sanitized into the message, because this message does not stay
     *            server-side: {@code RestAgentEngineStreaming} echoes a
     *            {@code ConversationNotFoundException}'s text into an SSE
     *            {@code error} event, and the id is a caller-supplied path
     *            parameter. {@code sanitize} strips ISO control characters and the
     *            Unicode line separators, so neither the event's JSON nor a log
     *            line can be broken by the value. The twin construction site,
     *            {@code ConversationService#requireSnapshot}, already did this.
     */
    static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId) {
        if (conversationMemory == null) {
            String message = "No conversation found with conversationId: %s";
            message = String.format(message, sanitize(conversationId));
            throw new ConversationNotFoundException(message);
        }
    }
}
