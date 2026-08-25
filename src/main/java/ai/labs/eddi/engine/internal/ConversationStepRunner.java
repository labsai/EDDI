/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.caching.ICache;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

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
    }

    IDiscardableTask processConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                                             Map<String, String> loggingContext, Callable<Void> executeConversation,
                                             Consumer<IConversationMemory> skipNotifier, ConversationService.ProcessingTurn processingTurn) {
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
        final Callable<Void> identityBoundExecution = conversationService.callerIdentityContext.withIdentity(
                conversationService.callerIdentityContext.captureOrCurrent(),
                executeConversation);

        return new IDiscardableTask() {
            @Override
            public Void call() {
                try {
                    return runConversationStep(environment, conversationMemory, conversationId, loggingContext,
                            identityBoundExecution, skipNotifier);
                } finally {
                    // C11: the single guaranteed exit point of a turn. The completion
                    // consumer releases first on the happy path, but a watchdog timeout,
                    // a pipeline error or a cancelled inner future never reaches it — and
                    // an entry that is never released leaks into the gauge forever.
                    // release() is one-shot, so releasing twice is a no-op.
                    processingTurn.release();
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
                    LOGGER.errorf(cause, "Queued turn of conversation %s was dropped before it ran — reporting it as "
                            + "skipped; the input was not consumed and can be retried", conversationId);
                    if (skipNotifier != null) {
                        skipNotifier.accept(conversationMemory);
                    }
                } finally {
                    // Belt and braces: release() is one-shot, so this is a no-op when
                    // the skip notifier already released — but a null or throwing
                    // notifier must not leak the reference.
                    processingTurn.release();
                }
            }
        };
    }

    Void runConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                             Map<String, String> loggingContext, Callable<Void> executeConversation,
                             Consumer<IConversationMemory> skipNotifier) {
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
        // EXECUTION_INTERRUPTED is deliberately NOT skipped: unlike ENDED it is a
        // RECOVERABLE marker meaning "the previous turn did not finish" (an
        // agentTimeout watchdog expiry, or HitlCrashRecoveryObserver parking a
        // stuck IN_PROGRESS conversation with the explicit intent to "unlock
        // say()"). A fresh say must run a new turn to self-heal the conversation
        // back to READY — mirroring the pre-HITL behavior where a retry after an
        // interrupt executed normally. Skipping it would strand the conversation's
        // input forever, since nothing else transitions EXECUTION_INTERRUPTED back
        // to READY.
        ConversationState persistedState = conversationMemoryStore.getConversationState(conversationId);
        if (persistedState == ConversationState.AWAITING_HUMAN || persistedState == ConversationState.IN_PROGRESS
                || persistedState == ConversationState.ENDED) {
            conversationMemory.setConversationState(persistedState);
            conversationService.contextLogger.setLoggingContext(loggingContext);
            LOGGER.warnf("Skipping queued turn for conversation %s: persisted state is %s (turn arrived before the state change)",
                    conversationId, persistedState);
            if (skipNotifier != null) {
                skipNotifier.accept(conversationMemory);
            }
            return null;
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
        inFlightConversations.put(conversationId, conversationMemory);
        try {
            // Carry the agent-level tool-approval config onto memory BEFORE the
            // pipeline (LlmTask) runs, so the tool-approval gate can resolve its
            // effective config. Transient — never persisted; re-resolved each turn.
            // Inside the try so an Error here cannot strand the registration above:
            // a stranded entry would keep a finished turn's memory reachable AND
            // make a later cancel signal the wrong (dead) pipeline.
            conversationService.conversationHitlService.populateToolApprovalsConfig(conversationMemory);
            runGuardedConversationStep(loggingContext, conversationId, environment, conversationMemory,
                    executeConversation, memoryStateAtSubmit, persistedState);
        } finally {
            // value-conditional: only the leg that registered this memory may
            // unregister — a plain remove(key) could evict a NEWER execution's
            // entry and defeat its cooperative cancel.
            inFlightConversations.remove(conversationId, conversationMemory);
        }
        return null;
    }

    void runGuardedConversationStep(Map<String, String> loggingContext, String conversationId,
                                    Environment environment, IConversationMemory conversationMemory,
                                    Callable<Void> executeConversation, ConversationState memoryStateAtSubmit,
                                    ConversationState preTurnPersistedState) {
        waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                runtime.submitCallable(executeConversation, new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Void result) {
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
                                ConversationState runningState = conversationMemoryStore.getConversationState(conversationId);
                                if (runningState == ConversationState.READY || runningState == ConversationState.IN_PROGRESS) {
                                    if (conversationMemoryStore.compareAndSetState(conversationId,
                                            runningState, ConversationState.EXECUTION_INTERRUPTED)) {
                                        cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                                    }
                                }
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
                                        conversationMemory, environment, preTurnPersistedState);
                                if (!persisted) {
                                    conversationService.contextLogger.setLoggingContext(loggingContext);
                                    LOGGER.infof("Pause of conversation %s not persisted: a concurrent end/cancel moved "
                                            + "it off %s — discarding the pause outcome so the terminal state wins",
                                            conversationId, preTurnPersistedState);
                                    return;
                                }
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
                                // M1: a durably-committed pause is counted and its timeout armed.
                                conversationService.counterHitlPause.increment();
                                conversationService.conversationHitlService.scheduleHitlTimeout(conversationId, conversationMemory);
                            } else {
                                // Non-pause completion is unchanged: the normal say turn
                                // settles to READY/ENDED/… and persists the full snapshot.
                                storeConversationMemory(conversationMemory, environment);
                            }
                        } catch (ResourceStoreException e) {
                            logConversationError(loggingContext, conversationId, e);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
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
                        if (t instanceof ExecutionAbandonedException || t instanceof LifecycleException.LifecycleInterruptedException) {
                            String errorMessage = "Conversation processing got interrupted! (conversationId=%s)";
                            errorMessage = String.format(errorMessage, conversationId);
                            conversationService.contextLogger.setLoggingContext(loggingContext);
                            LOGGER.warn(errorMessage, t);
                        } else if (t instanceof IConversation.ConversationNotReadyException) {
                            String msg = "Conversation not ready! (conversationId=%s)";
                            msg = String.format(msg, conversationId);
                            conversationService.contextLogger.setLoggingContext(loggingContext);
                            LOGGER.error(msg + "\n" + t.getLocalizedMessage(), t);
                        } else {
                            logConversationError(loggingContext, conversationId, t);
                        }
                    }
                }, null));
    }

    void waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId, Future<Void> future) {
        try {
            future.get(agentTimeout, TimeUnit.SECONDS);
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
            try {
                future.cancel(true);
                ConversationState currentState = conversationMemoryStore.getConversationState(conversationId);
                if (currentState == ConversationState.AWAITING_HUMAN) {
                    return;
                }
                setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                String errorMessage = "Execution of Workflows interrupted or timed out.";
                conversationService.contextLogger.setLoggingContext(loggingContext);
                LOGGER.error(errorMessage, e);
            } finally {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (ExecutionException e) {
            logConversationError(loggingContext, conversationId, e);
        }
    }

    void logConversationError(Map<String, String> loggingContext, String conversationId, Throwable t) {
        setConversationState(conversationId, ConversationState.ERROR);
        String msg = "Error while processing user input (conversationId=%s , conversationState=%s)";
        msg = String.format(msg, conversationId, ConversationState.ERROR);
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

    String storeConversationMemory(IConversationMemory conversationMemory, Environment environment) throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
    }

    /**
     * Persist the full memory snapshot only while the conversation is still in
     * {@code expectedState} — an atomic compare-and-store. Used by the resume path
     * so a resumed outcome cannot clobber an ENDED/EXECUTION_INTERRUPTED state
     * written concurrently by end/cancel.
     */
    boolean storeConversationMemoryIfState(IConversationMemory conversationMemory, Environment environment,
                                           ConversationState expectedState)
            throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshotIfState(memorySnapshot, expectedState);
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
