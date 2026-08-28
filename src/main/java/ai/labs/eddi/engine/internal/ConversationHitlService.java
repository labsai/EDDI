/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.runtime.ExecutionAbandonedException;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.event.Event;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * The human-in-the-loop half of {@code ConversationService} (R3 step 1): cancel
 * and resume a paused turn, validate the human's per-tool decisions, guard
 * against a resume that makes no progress, schedule and clear approval
 * timeouts, resolve the effective approval policy, and write the compliance
 * audit trail for all of it. Extracted as a pure move — no behavior change.
 * <p>
 * It was the back ~43% of the class and had almost nothing to do with the
 * front: {@code ConversationService} is fundamentally "run a turn", and this is
 * "a turn stopped, and a person is deciding what happens next". The two share
 * conversation memory and the coordinator, and little else.
 * <p>
 * <b>Resume re-enters the facade on purpose.</b> Applying a verdict eventually
 * means running the rest of the turn, and that has to be the same
 * {@code say}/step machinery a normal turn uses — a second, resume-shaped copy
 * of turn execution is how post-approval behaviour drifts from ordinary
 * behaviour without anyone noticing. Hence the back-reference to
 * {@link ConversationService} for the seven facade members this cluster calls
 * ({@code say}, {@code getAgent}, {@code createPropertiesHandler},
 * {@code storeConversationMemoryIfState}, {@code cacheConversationState},
 * {@code waitForExecutionFinishOrTimeout}, {@code logConversationError}) plus
 * {@code rejectIfShuttingDown}. Same pattern and the same safety argument as
 * {@code GroupHitlCoordinator} in R1: the constructor only stores it, never
 * calls through it before the facade's own construction completes.
 * <p>
 * {@code inFlightConversations} and the metric counters are shared by
 * reference, not owned — the facade registers the gauges and both halves write
 * the same map.
 */
class ConversationHitlService {

    private static final Logger LOGGER = Logger.getLogger(ConversationHitlService.class);

    private final ConversationService conversationService;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationCoordinator conversationCoordinator;
    private final IRuntime runtime;
    private final IContextLogger contextLogger;
    private final CallerIdentityContext callerIdentityContext;
    private final AuditLedgerService auditLedgerService;
    private final IScheduleStore scheduleStore;
    private final IAgentStore agentStore;
    private final IJsonSerialization jsonSerialization;
    private final Event<HitlResumeCompletedEvent> hitlResumeCompletedEvent;
    private final Counter counterHitlPause;
    private final Counter counterHitlResume;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, IConversationMemory> inFlightConversations;

    ConversationHitlService(ConversationService conversationService,
            IConversationMemoryStore conversationMemoryStore, IConversationCoordinator conversationCoordinator,
            IRuntime runtime, IContextLogger contextLogger, CallerIdentityContext callerIdentityContext,
            AuditLedgerService auditLedgerService, IScheduleStore scheduleStore, IAgentStore agentStore,
            IJsonSerialization jsonSerialization, Event<HitlResumeCompletedEvent> hitlResumeCompletedEvent,
            Counter counterHitlPause, Counter counterHitlResume, MeterRegistry meterRegistry,
            ConcurrentHashMap<String, IConversationMemory> inFlightConversations) {
        this.conversationService = conversationService;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.runtime = runtime;
        this.contextLogger = contextLogger;
        this.callerIdentityContext = callerIdentityContext;
        this.auditLedgerService = auditLedgerService;
        this.scheduleStore = scheduleStore;
        this.agentStore = agentStore;
        this.jsonSerialization = jsonSerialization;
        this.hitlResumeCompletedEvent = hitlResumeCompletedEvent;
        this.counterHitlPause = counterHitlPause;
        this.counterHitlResume = counterHitlResume;
        this.meterRegistry = meterRegistry;
        this.inFlightConversations = inFlightConversations;
    }

    // --- HITL lifecycle ---
    public IConversationService.CancelOutcome cancelConversation(String conversationId,
                                                                 ControlSignal mode,
                                                                 String cancelledBy)
            throws ResourceStoreException {
        ConversationState currentState = conversationMemoryStore.getConversationState(conversationId);
        if (currentState == null) {
            return IConversationService.CancelOutcome.NOT_FOUND;
        }

        // MAJOR-3: Delete stale HITL timeout schedule before cancel
        deleteHitlTimeoutSchedule(conversationId);

        // #2: signal an in-flight execution on this pod. The pipeline checks the
        // flag at task boundaries and stops before the next lifecycle task.
        // CANCEL_IMMEDIATE currently degrades to graceful semantics on the
        // regular surface (no per-conversation future handle to interrupt).
        var inFlightMemory = inFlightConversations.get(conversationId);
        if (inFlightMemory != null) {
            inFlightMemory.setCancelled(true);
            LOGGER.infof("Signalled in-flight cancellation (%s) for conversation %s", mode, conversationId);
        }

        boolean pauseCancelled = conversationMemoryStore.compareAndSetState(conversationId,
                ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
        boolean changed = pauseCancelled;
        if (!changed) {
            changed = conversationMemoryStore.compareAndSetState(conversationId,
                    ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
        }
        if (changed) {
            conversationService.cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            if (pauseCancelled) {
                // A pending human approval was terminally resolved outside resume:
                // audit the cancellation (EU AI Act — cancels are decisions too), G5
                // notify channel observers with a null verdict + terminal snapshot so
                // the originating surface (Slack, …) renders the outcome, then remove
                // the stale bookmark (it would otherwise round-trip forever and
                // confuse approval-status + crash recovery). The event is fired BEFORE
                // the bookmark clear so any observer that inspects the reason still
                // sees it, and the snapshot already carries the terminal state.
                auditHitlCancellation(conversationId, mode, cancelledBy);
                fireHitlResumeCompletedTerminal(conversationId, cancelledBy);
                try {
                    conversationMemoryStore.clearHitlBookmark(conversationId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to clear HITL bookmark on cancel of %s: %s", conversationId, e.getMessage());
                }
            }
            return IConversationService.CancelOutcome.CANCELLED;
        }
        if (inFlightMemory != null) {
            // Nothing persisted to flip, but a running pipeline was signalled —
            // it will stop at the next task boundary and persist its own state.
            return IConversationService.CancelOutcome.CANCELLED;
        }
        // READY / ENDED / ERROR / EXECUTION_INTERRUPTED: nothing paused, nothing
        // running. Use endConversation to close a READY conversation.
        return IConversationService.CancelOutcome.NOTHING_TO_CANCEL;
    }

    /**
     * Sentinel for the DELIBERATE agent-not-deployed resume rejection, which has
     * already restored the pause + dropped the in-flight entry before throwing.
     * Being an {@link IllegalStateException} it still maps to REST 409, but its
     * distinct type lets the resume catch re-throw it WITHOUT a second (double)
     * restore, while any OTHER IllegalStateException (e.g. one bubbling out of
     * continueConversation) falls through to the restore-and-wrap path (review
     * carve-out narrowing).
     */
    static final class AgentNotDeployedForResumeException extends IllegalStateException {
        AgentNotDeployedForResumeException(String message) {
            super(message);
        }
    }
    public void resumeConversation(String conversationId,
                                   HitlDecision decision,
                                   ConversationResponseHandler handler)
            throws ResourceStoreException, ResourceNotFoundException {
        // See resumeConversation's @throws IllegalArgumentException javadoc
        // (IConversationService) for why this is checked here rather than trusted
        // from each caller.
        if (decision == null || decision.getVerdict() == null) {
            throw new IllegalArgumentException("decision.verdict is required (APPROVED or REJECTED)");
        }
        // B3: a resume enqueues a FULL turn through the same coordinator the shutdown
        // drain is waiting on, so admitting one during the drain both extends the
        // drain and risks the turn being SIGKILLed halfway. Rejected here, BEFORE the
        // AWAITING_HUMAN->IN_PROGRESS CAS, so there is no state to roll back: the
        // conversation stays paused and the approval can be resumed on another node
        // (or after the restart) with nothing lost.
        conversationService.rejectIfShuttingDown();
        // #7: distinguish "unknown conversation" (404) from "wrong state" (409)
        // — compareAndSetState returns false for both.
        if (conversationMemoryStore.getConversationState(conversationId) == null) {
            throw new ResourceNotFoundException("Conversation not found: " + conversationId);
        }
        // Task 7: per-tool-call verdicts (toolDecisions) MUST be validated BEFORE the
        // AWAITING_HUMAN->IN_PROGRESS CAS below — a malformed body must never consume
        // the pause (mirrors GroupConversationService#resumeDiscussion's
        // validate-before-mutate precedent for taskApprovals: IllegalArgumentException
        // maps to REST 400 at the adapter). This is the ONLY pre-CAS snapshot read on
        // this path (the 404 check above uses the cheaper getConversationState), and
        // it is skipped entirely when toolDecisions is absent so the overwhelmingly
        // common plain-verdict resume incurs no extra load.
        if (decision != null && decision.getToolDecisions() != null && !decision.getToolDecisions().isEmpty()) {
            var preCasSnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (preCasSnapshot != null) {
                validateToolDecisions(decision, preCasSnapshot);
            }
        }
        if (!conversationMemoryStore.compareAndSetState(conversationId,
                ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS)) {
            ConversationState current = conversationMemoryStore.getConversationState(conversationId);
            // IllegalStateException = wrong-state conflict (REST: 409);
            // ResourceStoreException = infrastructure failure (REST: 500)
            throw new IllegalStateException(
                    "Conversation is not in AWAITING_HUMAN state (current: " + current
                            + ") — it may have been resumed, cancelled, or timed out already");
        }
        conversationService.cacheConversationState(conversationId, ConversationState.IN_PROGRESS);

        // From here on the pause's STATE has been consumed
        // (AWAITING_HUMAN->IN_PROGRESS):
        // EVERY failure path must restore it, or the approval is wedged IN_PROGRESS.
        // The timeout schedule is deliberately NOT deleted yet (see below): a failure
        // before that point — a transient snapshot-load hiccup or an undeployed agent —
        // then leaves the original finite-policy timeout armed, so it still fires on
        // time instead of silently degrading to wait-forever until the next restart.
        final IConversationMemory memory;
        final String agentId;
        final Integer agentVersion;
        final Environment environment;
        // Task 10 — capture the PRE-resume tool-pause identity BEFORE the async resume
        // mutates the live memory. Used for (a) the argsDigest audit detail (the batch
        // the human/timeout decision applies to) and (b) the no-progress guard's
        // same-turn same-fingerprint comparison in onComplete.
        final boolean prePauseWasToolCall;
        final String prePauseFingerprint;
        final int prePauseAutoApproveCount;
        // The pending batch the DECISION applies to — captured here so the audit reads
        // it deterministically even though the async resume may mutate the live memory.
        final PendingToolCallBatch prePauseBatch;
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot == null) {
                throw new ResourceNotFoundException("Conversation not found: " + conversationId);
            }
            // Wave 0, F6: refuse/migrate before anything else reads a bookmark
            // field. A refusal here (IllegalStateException) falls into the catch
            // (Exception e) below, which restores the pause exactly like any other
            // pre-conversion failure on this path.
            snapshot = ConversationSchemaMigrations.prepareForResume(snapshot);
            memory = convertConversationMemorySnapshot(snapshot);
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            agentId = snapshot.getAgentId();
            agentVersion = snapshot.getAgentVersion();
            environment = snapshot.getEnvironment();
            prePauseWasToolCall = ConversationPauseException.PauseOrigin.TOOL_CALL.name().equals(memory.getHitlPauseType());
            prePauseBatch = memory.getHitlPendingToolCalls();
            prePauseFingerprint = prePauseBatch != null ? prePauseBatch.getFingerprint() : null;
            prePauseAutoApproveCount = prePauseBatch != null ? prePauseBatch.getAutoApproveCount() : 0;
        } catch (ResourceNotFoundException e) {
            throw e; // genuinely deleted — nothing to restore into
        } catch (Exception e) {
            // transient store failure loading the snapshot — restore the pause. No
            // re-arm needed: the timeout schedule is deleted only after this load
            // succeeds, so it is still armed here and the finite policy still fires.
            restorePauseAfterFailedResume(conversationId, null, false);
            throw new ResourceStoreException("Failed to load conversation for resume: " + e.getLocalizedMessage(), e);
        }

        // #3: register the live memory IMMEDIATELY after the CAS so a concurrent
        // cancel can signal this resume before its callable starts. Removed in
        // guardedResume's finally, or in the failure paths below.
        inFlightConversations.put(conversationId, memory);

        try {
            IAgent agent = conversationService.getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                // #7: a transient deployment issue must not destroy the pending
                // approval — restore the pause instead of flipping to ERROR. No
                // re-arm needed: the delete happens only below (after this check), so
                // the original timeout schedule is still armed and the finite policy
                // continues to fire on time; redeploy + retry works either way.
                inFlightConversations.remove(conversationId, memory);
                restorePauseAfterFailedResume(conversationId, memory, false);
                throw new AgentNotDeployedForResumeException("Agent not deployed for resume (agentId=" + agentId + ", version=" + agentVersion
                        + ") — the conversation remains AWAITING_HUMAN; redeploy the agent and retry");
            }

            // MAJOR-3: now that the snapshot has loaded and the agent is confirmed
            // deployed — i.e. we are committed to executing the resume — disarm the
            // stale timeout schedule. Deferring the delete to here (rather than right
            // after the CAS) means a pre-execution failure above leaves the original
            // finite-policy timeout armed, so an AUTO_REJECT/AUTO_APPROVE/ABORT policy
            // is never silently dropped by a transient load hiccup. The AWAITING_HUMAN
            // ->IN_PROGRESS CAS already prevents the timeout from firing concurrently
            // (its own CAS would fail), so nothing races on this window.
            deleteHitlTimeoutSchedule(conversationId);

            // #15/4c: Set the audit collector (same as say path) so resume
            // operations are recorded in the audit ledger.
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                memory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            // Carry the agent-level tool-approval config onto memory BEFORE the resumed
            // pipeline runs — parity with the say path. The resumed tool loop resolves
            // its effective gate from it, and Task 10's no-progress guard reads
            // onNoProgress / maxAutoApprovalsPerTurn from it in onComplete.
            populateToolApprovalsConfig(memory);

            IConversation conversation = agent.continueConversation(memory,
                    conversationService.createPropertiesHandler(memory.getUserId(), agent.getUserMemoryConfig()),
                    handler != null ? returnMemory -> {
                        var memorySnapshot = convertSimpleConversationMemorySnapshot(returnMemory, false, true, List.of());
                        memorySnapshot.setEnvironment(environment);
                        conversationService.cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        handler.onComplete(memorySnapshot);
                    } : null);

            Map<String, String> loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, memory.getUserId());

            // The resume is itself an authenticated request, so the pipeline it
            // continues should run as the person who approved — captured here, on
            // the request thread, for the same reason as in the say path. Falls back
            // to the thread binding for an internally driven resume, which has no
            // request to capture from.
            final CallerIdentity resumeCallerIdentity = callerIdentityContext.captureOrCurrent();

            Callable<Void> resumeCallable = () -> {
                // #3: a cancel or a terminal end may have landed between the CAS
                // and this execution (flag on the registered memory, or DB-only in
                // the tiny pre-registration window). Abort without persisting —
                // an accepted resume must never resurrect a cancelled or ENDED
                // conversation.
                ConversationState persistedNow = conversationMemoryStore.getConversationState(conversationId);
                if (memory.isCancelled()
                        || persistedNow == ConversationState.EXECUTION_INTERRUPTED
                        || persistedNow == ConversationState.ENDED) {
                    memory.setCancelled(true);
                    LOGGER.infof("Resume of conversation %s aborted: cancelled/ended before execution (state=%s)",
                            conversationId, persistedNow);
                    return null;
                }
                try {
                    conversation.resume(decision);
                } catch (Exception e) {
                    LOGGER.error("Error during conversation resume: " + conversationId, e);
                    memory.setConversationState(ConversationState.ERROR);
                }
                return null;
            };

            // #2: persistence lives in onComplete — BaseRuntime routes callables
            // that complete after a watchdog cancellation to onFailure, so a
            // zombie resume can never overwrite state written after its timeout.
            IRuntime.IFinishedExecution<Void> resumeFinished = new IRuntime.IFinishedExecution<>() {
                public void onComplete(Void result) {
                    if (memory.isCancelled()) {
                        // aborted before execution — the cancel path owns the state
                        return;
                    }
                    try {
                        // #6: keep the timeout-policy bookmark populated on re-pause
                        boolean rePaused = memory.getConversationState() == ConversationState.AWAITING_HUMAN;
                        if (rePaused) {
                            populateHitlTimeoutBookmark(memory);
                        }
                        // Task 10 — no-progress guard: a TOOL_CALL pause that RE-PAUSES in
                        // the same turn with an IDENTICAL fingerprint after a system
                        // decision is a wedged auto-approval loop. Resolve the carried
                        // autoApproveCount and, once the budget is spent, apply onNoProgress
                        // (WAIT_FOR_HUMAN demotes / AUTO_REJECT resumes reject-all / ABORT
                        // cancels). Runs BEFORE the persist so a demotion is reflected in the
                        // stored bookmark and the schedule below.
                        NoProgressOutcome noProgress = NoProgressOutcome.NONE;
                        if (rePaused && prePauseWasToolCall) {
                            noProgress = evaluateAndApplyNoProgressGuard(conversationId, agentId, agentVersion,
                                    memory, environment, decision, prePauseFingerprint, prePauseAutoApproveCount);
                            if (noProgress == NoProgressOutcome.ABORT) {
                                // Cancel owns the terminal state — do NOT persist a re-pause.
                                cancelConversation(conversationId,
                                        ControlSignal.CANCEL_GRACEFUL,
                                        "system:no-progress");
                                return;
                            }
                        }
                        // Persist ONLY while we still own IN_PROGRESS — the state the
                        // AWAITING_HUMAN->IN_PROGRESS CAS set at resume start. The
                        // up-front isCancelled() check above narrows but does not close
                        // the race with a concurrent end/cancel: a terminal write can
                        // land between that check and this store, and an unconditional
                        // full-document replace would then overwrite ENDED/
                        // EXECUTION_INTERRUPTED with READY — resurrecting a terminated
                        // conversation that then accepts new conversationService.say() input. The
                        // atomic
                        // compare-and-store lets the terminal writer win; when it does,
                        // discard the resumed outcome (no persist, no schedule/notify).
                        boolean persisted = conversationService.storeConversationMemoryIfState(memory, environment, ConversationState.IN_PROGRESS);
                        if (!persisted) {
                            LOGGER.infof("Resume of conversation %s not persisted: a concurrent end/cancel moved it off "
                                    + "IN_PROGRESS — discarding the resumed outcome so the terminal state wins", conversationId);
                            return;
                        }
                        conversationService.cacheConversationState(conversationId, memory.getConversationState());
                        // Task 10 — AUTO_REJECT no-progress: the re-pause is now durably
                        // persisted (AWAITING_HUMAN); break the loop with a reject-all resume
                        // through the SAME resume path (journal-bearing, no shortcut). The
                        // coordinator serializes this follow-up after the current callable.
                        if (noProgress == NoProgressOutcome.RESUME_REJECT_ALL) {
                            counterHitlPause.increment();
                            counterToolPause(prePauseWasToolCall);
                            issueNoProgressRejectAllResume(conversationId);
                            return;
                        }
                        // #3 (schedule) + metric: a re-pause is a pause
                        if (memory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                            counterHitlPause.increment();
                            counterToolPause(prePauseWasToolCall);
                            scheduleHitlTimeout(conversationId, memory);
                        } else {
                            // Resume settled to a non-paused outcome — notify channel
                            // observers (Slack, …) so the originating surface can push
                            // the continuation without polling. Fired async and
                            // best-effort: a failing observer must never affect the
                            // persisted resume above.
                            fireHitlResumeCompleted(conversationId, environment, memory, decision);
                        }
                    } catch (ResourceStoreException e) {
                        conversationService.logConversationError(loggingContext, conversationId, e);
                    }
                }
                public void onFailure(Throwable t) {
                    // See the say path's onFailure: only the DEDICATED abandonment type
                    // means "the watchdog already recorded the outcome". A genuine
                    // InterruptedException from the body is a real failure and falls
                    // through to logConversationError.
                    if (t instanceof ExecutionAbandonedException || t instanceof LifecycleException.LifecycleInterruptedException) {
                        // watchdog timeout / stale completion — EXECUTION_INTERRUPTED
                        // was already persisted by the watchdog; discard this result
                        contextLogger.setLoggingContext(loggingContext);
                        LOGGER.warnf("Resume execution of conversation %s interrupted/discarded: %s",
                                conversationId, t.getMessage());
                    } else {
                        conversationService.logConversationError(loggingContext, conversationId, t);
                    }
                }
            };

            // #4: guard the resume with the same watchdog the say path uses — a
            // hung LLM call or crashed executor must not leave the conversation
            // stuck IN_PROGRESS forever.
            IDiscardableTask guardedResume = new IDiscardableTask() {
                public Void call() {
                    try {
                        // The resume runs as the caller who approved: the identity was
                        // captured on the REST request thread above, because this body
                        // already runs on a pool thread with no request context.
                        // Both bindings, because the approver drives the request but the
                        // conversation owner owns the credentials: the CallerIdentity is
                        // what audit and ${caller:token} must see, while a PER_USER
                        // credential the resumed turn spends belongs to the user who
                        // asked — never to the administrator who approved on their
                        // behalf.
                        conversationService.waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                                runtime.submitCallable(withConversationPrincipal(memory,
                                        callerIdentityContext.withIdentity(resumeCallerIdentity, resumeCallable)),
                                        resumeFinished, null));
                    } finally {
                        // value-conditional: never evict a newer execution's registration
                        inFlightConversations.remove(conversationId, memory);
                    }
                    return null;
                }

                /**
                 * The coordinator dropped the resume without running it. The pause's STATE was
                 * already consumed by the AWAITING_HUMAN->IN_PROGRESS CAS above, so without
                 * this the conversation is wedged IN_PROGRESS forever with nothing left to move
                 * it. Same rollback the synchronous submit-rejection path performs.
                 */
                public void onDiscarded(Throwable cause) {
                    contextLogger.setLoggingContext(loggingContext);
                    LOGGER.errorf(cause, "Queued resume of conversation %s was dropped before it ran — "
                            + "restoring the pause so the approval is not wedged IN_PROGRESS", conversationId);
                    inFlightConversations.remove(conversationId, memory);
                    restorePauseAfterFailedResume(conversationId, memory, true);
                }
            };

            try {
                conversationCoordinator.submitInOrder(conversationId, guardedResume);
            } catch (RuntimeException e) {
                // #5: coordinator saturation (RejectedExecutionException) or any
                // submit failure — the callable will never run; restore the pause.
                inFlightConversations.remove(conversationId, memory);
                restorePauseAfterFailedResume(conversationId, memory, true);
                throw new ResourceStoreException("Failed to enqueue resume for conversation " + conversationId
                        + ": " + e.getLocalizedMessage() + " — the conversation remains AWAITING_HUMAN; retry later", e);
            }

            // Only count and audit resumes that were actually accepted (#15,
            // metric drift): rolled-back attempts must not pollute the audit
            // trail or the counter.
            counterHitlResume.increment();
            auditHitlDecision(conversationId, agentId, agentVersion, memory.getUserId(), environment, decision,
                    prePauseWasToolCall, prePauseBatch);
            // Task 10 — tool-resume metric, tagged by aggregate verdict.
            if (prePauseWasToolCall) {
                recordToolResumeMetric(decision, prePauseBatch);
            }
        } catch (AgentNotDeployedForResumeException e) {
            // The ONLY IllegalStateException that already removed the in-flight entry
            // and restored the pause above — re-throw as-is (REST 409) WITHOUT a
            // double restore. Any OTHER ISE (e.g. one bubbling out of
            // continueConversation) is NOT this type and falls through to the
            // restore-and-wrap path below, so an unexpected ISE never strands the
            // pause IN_PROGRESS (review carve-out narrowing).
            throw e;
        } catch (ServiceException | InstantiationException | IllegalAccessException | RuntimeException e) {
            // #7 + review: transient OR unexpected failures anywhere between the CAS
            // and submitInOrder (e.g. an unchecked exception — including an unexpected
            // IllegalStateException — from continueConversation) must restore the pause
            // and drop the in-flight registration — otherwise the conversation is left
            // stuck IN_PROGRESS with a leaked registry entry.
            inFlightConversations.remove(conversationId, memory);
            restorePauseAfterFailedResume(conversationId, memory, true);
            throw new ResourceStoreException("Failed to resume conversation: " + e.getLocalizedMessage(), e);
        }
    }

    /**
     * Binds the resumed turn's {@link ResolutionPrincipal} — the conversation's own
     * owner and the provenance persisted with it — around the work that continues
     * the pipeline.
     * <p>
     * Both halves come from the STORED memory, never from the resume request: a
     * resume proves who approved, and says nothing about who owns the conversation
     * being approved. Deriving here from the request identity would resolve a
     * PER_USER credential against the approver, and refusing to bind at all would
     * fail every PER_USER resolution on the resume path — the two ways this turn
     * can be wrong.
     * <p>
     * {@code ResolutionPrincipalContext} restores the previous binding in a
     * {@code finally}, the same way {@code CallerIdentityContext} does around the
     * identity it wraps here — pool threads are reused across conversations.
     * <p>
     * Read off the facade rather than held as a field because the context is
     * CDI-field-injected and this collaborator is constructed inside the facade's
     * constructor, before injection has run. A {@code null} context means the
     * facade was built outside CDI (as the direct-construction unit tests do);
     * nothing is bound and every PER_USER resolution fails closed, which is the
     * safe direction.
     */
    private Callable<Void> withConversationPrincipal(IConversationMemory memory, Callable<Void> work) {
        ResolutionPrincipalContext principalContext = conversationService.resolutionPrincipalContext;
        if (principalContext == null) {
            return work;
        }
        return principalContext.withPrincipal(
                new ResolutionPrincipal(memory.getUserId(), memory.getResolutionProvenance()), work);
    }

    /**
     * Task 7: validates {@link HitlDecision#getToolDecisions()} against the pending
     * TOOL_CALL batch BEFORE the resume CAS runs. Throws
     * {@link IllegalArgumentException} (the same type
     * {@code GroupConversationService#resumeDiscussion} uses for its taskApprovals
     * validate-before-mutate precedent — {@code RestAgentEngine} maps it to REST
     * 400) on the first violation found; callers must not have mutated any state
     * yet when this is invoked.
     * <p>
     * Semantics: calls not listed in {@code toolDecisions} inherit the top-level
     * {@link HitlDecision#getVerdict()} — they are not required to appear here.
     */
    void validateToolDecisions(HitlDecision decision, ConversationMemorySnapshot snapshot) {
        Map<String, ToolCallDecision> toolDecisions = decision.getToolDecisions();

        if (!"TOOL_CALL".equals(snapshot.getHitlPauseType())) {
            throw new IllegalArgumentException("toolDecisions is only valid for tool-call pauses");
        }

        PendingToolCallBatch batch = snapshot.getHitlPendingToolCalls();
        List<PendingToolCallBatch.PendingToolCall> pendingCalls = batch != null && batch.getCalls() != null
                ? batch.getCalls()
                : List.of();
        Map<String, PendingToolCallBatch.PendingToolCall> pendingById = new LinkedHashMap<>();
        for (var call : pendingCalls) {
            pendingById.put(call.getCallId(), call);
        }

        // Top-level REJECTED + any per-call APPROVED is contradictory (mirrors the
        // group taskApprovals semantics) — checked up front so it fails regardless
        // of per-call iteration order.
        if (decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
            boolean anyPerCallApproved = toolDecisions.values().stream()
                    .anyMatch(d -> d.getVerdict() == HitlDecision.HitlVerdict.APPROVED);
            if (anyPerCallApproved) {
                throw new IllegalArgumentException(
                        "top-level verdict is REJECTED but toolDecisions contains an APPROVED call; "
                                + "set the top-level verdict to APPROVED to mix per-call outcomes");
            }
        }

        for (var entry : toolDecisions.entrySet()) {
            String callId = entry.getKey();
            ToolCallDecision toolDecision = entry.getValue();

            var pendingCall = pendingById.get(callId);
            if (pendingCall == null) {
                throw new IllegalArgumentException(
                        "no pending tool call '" + callId + "'; pending: " + pendingById.keySet());
            }

            if (toolDecision.getVerdict() == null) {
                throw new IllegalArgumentException(
                        "toolDecisions['" + callId + "'].verdict is required (APPROVED or REJECTED)");
            }

            if (toolDecision.getNote() != null && toolDecision.getNote().length() > ToolCallDecision.MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException(
                        "toolDecisions['" + callId + "'].note exceeds the maximum length of "
                                + ToolCallDecision.MAX_NOTE_LENGTH + " characters");
            }

            String amendedArguments = toolDecision.getAmendedArguments();
            if (amendedArguments != null) {
                if (toolDecision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments is only valid for an APPROVED call");
                }
                if (pendingCall.isArgsTruncated()) {
                    throw new IllegalArgumentException(
                            "call '" + callId + "' was truncated at pause time and cannot be amended; "
                                    + "approve or reject it as-is");
                }
                if (amendedArguments.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments exceeds the maximum size of "
                                    + PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES + " bytes");
                }
                Object parsed;
                try {
                    parsed = jsonSerialization.deserialize(amendedArguments);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments is not valid JSON: " + e.getMessage());
                }
                if (!(parsed instanceof Map)) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments must be a JSON object");
                }
            }
        }
    }

    /**
     * Default max consecutive system auto-approvals per turn (mirrors
     * ToolApprovalsConfig doc).
     */
    static final int DEFAULT_MAX_AUTO_APPROVALS_PER_TURN = ToolApprovalsConfig.DEFAULT_MAX_AUTO_APPROVALS_PER_TURN;

    /**
     * Outcome of the no-progress guard, driving the onComplete persistence branch.
     */
    enum NoProgressOutcome {
        /** No wedged loop detected — proceed with the normal re-pause. */
        NONE,
        /**
         * WAIT_FOR_HUMAN: the re-pause was demoted to WAIT_INDEFINITELY (no schedule).
         */
        DEMOTED_WAIT,
        /**
         * AUTO_REJECT: persist the re-pause, then resume reject-all
         * (system:no-progress).
         */
        RESUME_REJECT_ALL,
        /** ABORT: cancel the conversation via the existing cancel path. */
        ABORT
    }

    /**
     * Task 10 — no-progress guard. Runs in the resume onComplete AFTER a TOOL_CALL
     * pause has RE-PAUSED in the same turn. Carries the {@code autoApproveCount}
     * across the re-pause and, once the auto-approval budget is spent, applies the
     * configured {@code onNoProgress} policy. A HUMAN decision (decidedBy not
     * {@code system:*}) resets the counter to 0 (group fresh-budget convention).
     * <p>
     * For DEMOTED_WAIT it mutates the live memory bookmark IN PLACE (policy →
     * WAIT_INDEFINITELY) so the subsequent persist + schedule see the demotion; for
     * RESUME_REJECT_ALL / ABORT it records the guard (metric + audit) and returns
     * the outcome for the caller to enact. The new batch's autoApproveCount is
     * always updated so it is persisted with the re-pause.
     */
    NoProgressOutcome evaluateAndApplyNoProgressGuard(String conversationId, String agentId,
                                                      Integer agentVersion, IConversationMemory memory, Environment environment,
                                                      HitlDecision decision,
                                                      String prePauseFingerprint, int prePauseAutoApproveCount) {
        PendingToolCallBatch newBatch = memory.getHitlPendingToolCalls();
        if (newBatch == null) {
            return NoProgressOutcome.NONE;
        }
        boolean systemDecision = decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:");
        boolean sameFingerprint = prePauseFingerprint != null
                && prePauseFingerprint.equals(newBatch.getFingerprint());

        if (!systemDecision) {
            // A human broke into the loop — reset the fresh budget.
            newBatch.setAutoApproveCount(0);
            return NoProgressOutcome.NONE;
        }
        if (!sameFingerprint) {
            // Progress was made (a different batch) — the loop counter does not carry.
            newBatch.setAutoApproveCount(0);
            return NoProgressOutcome.NONE;
        }

        // System decision + identical fingerprint → a wedged loop. Carry + increment.
        int carried = prePauseAutoApproveCount + 1;
        newBatch.setAutoApproveCount(carried);

        // Fix #1: resolve max-auto-approvals + onNoProgress from the config that gated
        // the RE-PAUSE batch (task-scoped override when present), falling back to the
        // agent-level default for a legacy/null-field batch.
        ToolApprovalsConfig noProgressCfg = newBatch.getEffectiveToolApprovals() != null
                ? newBatch.getEffectiveToolApprovals()
                : memory.getAgentToolApprovalsConfig();
        int maxAutoApprovals = resolveMaxAutoApprovals(noProgressCfg);
        boolean hardThreshold = carried >= 2;
        boolean capReached = carried >= maxAutoApprovals;
        boolean budgetSpent = hardThreshold || capReached;
        if (!budgetSpent) {
            // Still within budget — allow the re-pause to proceed with the carried count.
            return NoProgressOutcome.NONE;
        }

        // The auto-approval CAP being reached is its own guard signal (distinct from
        // the
        // fixed >=2 no-progress threshold) — record it so operators can tell a
        // config-limited stop from the built-in loop breaker.
        if (capReached) {
            recordGuard("auto_approve_cap");
            auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                    "auto_approve_cap", newBatch.getFingerprint(), "system:no-progress");
        }

        String onNoProgress = resolveOnNoProgress(noProgressCfg);
        switch (onNoProgress) {
            case "AUTO_REJECT" -> {
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.RESUME_REJECT_ALL;
            }
            case "ABORT" -> {
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.ABORT;
            }
            default -> {
                // WAIT_FOR_HUMAN (default): demote the new pause so no finite timeout can
                // auto-decide it again — a human MUST break the loop.
                memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.DEMOTED_WAIT;
            }
        }
    }

    /**
     * Effective max consecutive system auto-approvals per turn (default 2, clamped
     * 0..10).
     */
    static int resolveMaxAutoApprovals(ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getMaxAutoApprovalsPerTurn() == null) {
            return DEFAULT_MAX_AUTO_APPROVALS_PER_TURN;
        }
        return Math.max(0, Math.min(10, cfg.getMaxAutoApprovalsPerTurn()));
    }

    /** Effective onNoProgress policy (WAIT_FOR_HUMAN default). */
    static String resolveOnNoProgress(ToolApprovalsConfig cfg) {
        if (cfg == null || isNullOrEmpty(cfg.getOnNoProgress())) {
            return "WAIT_FOR_HUMAN";
        }
        return cfg.getOnNoProgress();
    }

    /**
     * Issues the reject-all follow-up resume that breaks a no-progress loop. Routed
     * through the standard {@link #resumeConversation} path (journal-bearing — no
     * shortcut) with {@code decidedBy=system:no-progress}; the coordinator
     * serializes it after the current callable. Best-effort: a failure is logged,
     * leaving the (now WAIT_INDEFINITELY, since we could not break it) pause for a
     * human.
     */
    void issueNoProgressRejectAllResume(String conversationId) {
        var reject = new HitlDecision();
        reject.setVerdict(HitlDecision.HitlVerdict.REJECTED);
        reject.setDecidedBy("system:no-progress");
        reject.setNote("Automatic reject-all: repeated identical tool-call pause made no progress");
        try {
            resumeConversation(conversationId, reject, null);
        } catch (Exception e) {
            LOGGER.warnf("No-progress reject-all resume failed for %s: %s — the pause remains for a human",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Increments the tool-pause metric alongside the generic pause counter (Task
     * 10).
     */
    void counterToolPause(boolean toolCallPause) {
        if (toolCallPause) {
            meterRegistry.counter("eddi_hitl_tool_pause_count").increment();
        }
    }

    /** Task 10 — increments the per-guard metric tagged by guard name. */
    void recordGuard(String guard) {
        meterRegistry.counter("eddi_hitl_tool_guard_count", "guard", guard).increment();
    }

    /**
     * Task 10 — writes a per-guard audit-ledger entry ({@code hitl.tool.<guard>})
     * recording the guard name, the fingerprint (for no_progress), and
     * {@code decidedBy}. Mirrors {@link #auditHitlDecision}'s HMAC/context; the raw
     * arguments are NEVER included (only the fingerprint).
     */
    void auditGuard(String conversationId, String agentId, Integer agentVersion, String userId,
                    Environment environment, String guard, String fingerprint, String decidedBy) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("guard", guard);
            detail.put("decidedBy", decidedBy != null ? decidedBy : "unknown");
            detail.put("automated", decidedBy != null && decidedBy.startsWith("system:"));
            if (fingerprint != null) {
                detail.put("fingerprint", fingerprint);
            }
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), conversationId, agentId, agentVersion, userId,
                    environment != null ? environment.toString() : null, -1,
                    "hitl.tool." + guard, "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL tool-guard audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry for a HITL decision. Covers both
     * human decisions and automated timeout decisions
     * ({@code decidedBy=system:timeout}).
     */
    void auditHitlDecision(String conversationId, String agentId, Integer agentVersion,
                           String userId, Environment environment,
                           HitlDecision decision,
                           boolean toolCallPause, PendingToolCallBatch pendingBatch) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", decision.getVerdict() != null ? decision.getVerdict().name() : "UNKNOWN");
            detail.put("decidedBy", decision.getDecidedBy() != null ? decision.getDecidedBy() : "unknown");
            detail.put("automated", decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:"));
            if (decision.getNote() != null) {
                detail.put("note", decision.getNote());
            }
            // Task 10 — TOOL_CALL pauses carry a per-call decision summary. Each entry
            // records the callId, resolved verdict, whether the args were amended, and
            // the tool name — plus a SHA-256 argsDigest so the ledger can prove WHICH
            // arguments were approved WITHOUT ever storing the raw (possibly sensitive)
            // arguments.
            if (toolCallPause) {
                detail.put("pauseType", ConversationPauseException.PauseOrigin.TOOL_CALL.name());
                detail.put("toolDecisions", buildToolDecisionSummary(decision, pendingBatch));
            }
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), conversationId, agentId, agentVersion, userId,
                    environment != null ? environment.toString() : null, -1,
                    "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Task 10 — builds the {@code toolDecisions} audit summary for a TOOL_CALL
     * resume: one entry per gated call with {@code callId}, resolved
     * {@code verdict}, {@code amended} flag, {@code toolName}, and a per-call
     * SHA-256 {@code argsDigest}. The digest is over the raw arguments the decision
     * applies to (the amended arguments when a per-call amendment was supplied,
     * otherwise the persisted raw args) so the ledger can bind a decision to
     * exactly the arguments approved — the raw arguments themselves are NEVER
     * written.
     */
    List<Map<String, Object>> buildToolDecisionSummary(
                                                       HitlDecision decision,
                                                       PendingToolCallBatch pendingBatch) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (pendingBatch == null || pendingBatch.getCalls() == null) {
            return summary;
        }
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null
                ? decision.getToolDecisions()
                : Map.of();
        for (PendingToolCallBatch.PendingToolCall call : pendingBatch.getCalls()) {
            ToolCallDecision cd = perCall.get(call.getCallId());
            var verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : decision.getVerdict();
            String amended = cd != null ? cd.getAmendedArguments() : null;
            String argsForDigest = amended != null ? amended : call.getArgumentsRaw();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("callId", call.getCallId());
            entry.put("verdict", verdict != null ? verdict.name() : "UNKNOWN");
            entry.put("amended", amended != null);
            entry.put("toolName", call.getToolName());
            entry.put("argsDigest", sha256Hex(argsForDigest != null ? argsForDigest : ""));
            summary.add(entry);
        }
        return summary;
    }

    /** SHA-256 hex of the given string (lower-case, 64 chars). */
    static String sha256Hex(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Aggregate verdict for the tool-resume metric: approved | rejected | mixed.
     */
    void recordToolResumeMetric(HitlDecision decision,
                                PendingToolCallBatch pendingBatch) {
        String verdict = aggregateVerdict(decision, pendingBatch);
        meterRegistry.counter("eddi_hitl_tool_resume_count", "verdict", verdict).increment();
    }

    /**
     * Resolves the aggregate verdict across all gated calls: {@code approved} when
     * every call resolves APPROVED, {@code rejected} when every call resolves
     * REJECTED, {@code mixed} otherwise (per-call verdicts diverge).
     */
    static String aggregateVerdict(HitlDecision decision,
                                   PendingToolCallBatch pendingBatch) {
        var top = decision.getVerdict();
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null
                ? decision.getToolDecisions()
                : Map.of();
        if ((pendingBatch == null || pendingBatch.getCalls() == null || pendingBatch.getCalls().isEmpty())
                || perCall.isEmpty()) {
            return top == HitlDecision.HitlVerdict.REJECTED ? "rejected" : "approved";
        }
        boolean anyApproved = false;
        boolean anyRejected = false;
        for (PendingToolCallBatch.PendingToolCall call : pendingBatch.getCalls()) {
            ToolCallDecision cd = perCall.get(call.getCallId());
            var v = cd != null && cd.getVerdict() != null ? cd.getVerdict() : top;
            if (v == HitlDecision.HitlVerdict.REJECTED) {
                anyRejected = true;
            } else {
                anyApproved = true;
            }
        }
        if (anyApproved && anyRejected) {
            return "mixed";
        }
        return anyRejected ? "rejected" : "approved";
    }

    /**
     * Fires {@link HitlResumeCompletedEvent} asynchronously after a resume has been
     * persisted to a non-paused state. Building the snapshot and firing the event
     * are wrapped so any failure here (serialization, no observers, …) is logged
     * and swallowed — the resume itself has already succeeded and must not be
     * affected by delivery-side concerns.
     */
    void fireHitlResumeCompleted(String conversationId, Environment environment,
                                 IConversationMemory memory,
                                 HitlDecision decision) {
        try {
            var snapshot = convertSimpleConversationMemorySnapshot(memory, false, true, List.of());
            snapshot.setEnvironment(environment);
            hitlResumeCompletedEvent.fireAsync(new HitlResumeCompletedEvent(
                    conversationId,
                    decision != null ? decision.getVerdict() : null,
                    decision != null ? decision.getDecidedBy() : null,
                    snapshot));
        } catch (Exception e) {
            LOGGER.warnf("Failed to fire HITL resume-completed event for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * G5: fires {@link HitlResumeCompletedEvent} with a {@code null} verdict and
     * the TERMINAL conversation snapshot after a pending approval is resolved
     * WITHOUT a human decision — cancel/ABORT/end/timeout-abort. Channel observers
     * (Slack, …) render these outcomes on the originating surface just like a
     * resume; the null verdict distinguishes a cancellation/end from an
     * APPROVE/REJECT. Best-effort and fully isolated: any failure loading the
     * snapshot, converting it, or firing is logged and swallowed — the terminal
     * state has already been persisted and must not be affected by delivery-side
     * concerns.
     */
    void fireHitlResumeCompletedTerminal(String conversationId, String decidedBy) {
        try {
            var stored = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (stored == null) {
                return; // deleted concurrently — nothing to render
            }
            var memory = convertConversationMemorySnapshot(stored);
            // The column is the source of truth for the state; reflect the terminal
            // state the caller just persisted so observers never see a stale pause.
            var terminalState = conversationMemoryStore.getConversationState(conversationId);
            if (terminalState != null) {
                memory.setConversationState(terminalState);
            }
            var snapshot = convertSimpleConversationMemorySnapshot(memory, false, true, List.of());
            snapshot.setEnvironment(stored.getEnvironment());
            hitlResumeCompletedEvent.fireAsync(new HitlResumeCompletedEvent(
                    conversationId, null, decidedBy, snapshot));
        } catch (Exception e) {
            LOGGER.warnf("Failed to fire terminal HITL resume-completed event for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Audits the termination of a pending human approval by cancel/ABORT — cancels
     * are HITL decisions too and must be attributable in the oversight trail.
     */
    void auditHitlCancellation(String conversationId, ControlSignal mode,
                               String cancelledBy) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", "CANCELLED");
            detail.put("mode", mode != null ? mode.name() : "CANCEL_GRACEFUL");
            detail.put("decidedBy", cancelledBy != null ? cancelledBy : "unknown");
            detail.put("automated", cancelledBy != null && cancelledBy.startsWith("system:"));
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), conversationId, null, null, null,
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL cancel audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Rolls a failed resume back to AWAITING_HUMAN so the pending approval survives
     * transient failures (undeployed agent, service errors, coordinator
     * saturation). Optionally re-arms the timeout schedule the resume attempt
     * deleted — callers pass {@code rearmSchedule=false} when re-arming would loop
     * (undeployed agent) or the bookmark is unavailable (crash recovery re-arms at
     * the next restart instead).
     */
    void restorePauseAfterFailedResume(String conversationId, IConversationMemory memory, boolean rearmSchedule) {
        try {
            boolean restored = conversationMemoryStore.compareAndSetState(conversationId,
                    ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            if (restored) {
                conversationService.cacheConversationState(conversationId, ConversationState.AWAITING_HUMAN);
                if (rearmSchedule && memory != null) {
                    scheduleHitlTimeout(conversationId, memory);
                } else {
                    LOGGER.warnf("Pause restored for %s without re-arming the timeout schedule — "
                            + "a finite policy resumes after the next restart (crash recovery) or a manual decision",
                            conversationId);
                }
                LOGGER.warnf("Resume of conversation %s failed — pause restored (AWAITING_HUMAN)", conversationId);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to restore pause after failed resume: %s", conversationId);
        }
    }

    /**
     * Copies the agent's HITL timeout config into the memory bookmark so
     * approval-status / pending-approvals report the effective policy and crash
     * recovery can distinguish WAIT_INDEFINITELY pauses from ones whose timeout
     * schedule may have been lost. Reads the config at the conversation's PINNED
     * agentVersion (falling back to the current version only if the pinned one no
     * longer exists) — editing a draft config must not change the runtime behavior
     * of conversations pinned to older versions. Absent config is normalized to
     * WAIT_INDEFINITELY (the default).
     */
    void populateHitlTimeoutBookmark(IConversationMemory memory) {
        try {
            memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            AgentConfiguration agentConfig = readAgentConfigPinned(memory.getAgentId(), memory.getAgentVersion());
            if (agentConfig == null)
                return;
            AgentConfiguration.HitlConfig hitlConfig = agentConfig.getHitlConfig();
            if (hitlConfig == null)
                return;
            // Designer-supplied pause reason answers "what am I approving?" in the
            // approval inbox; the generic reason set at pause time stays otherwise.
            // Scope: apply the agent-level override ONLY for a rule pause (null/RULE
            // pause type). A TOOL_CALL pause keeps its tool-specific reason (built at
            // gate time, including the gated tool names) — the agent-level generic
            // reason would erase that context.
            String pauseType = memory.getHitlPauseType();
            boolean isToolCallPause = ConversationPauseException.PauseOrigin.TOOL_CALL.name().equals(pauseType);
            if (!isToolCallPause && !isNullOrEmpty(hitlConfig.getPauseReason())) {
                memory.setHitlPauseReason(hitlConfig.getPauseReason());
            }
            if (isToolCallPause) {
                // Tool-pause timeout policy has its OWN scoping (Task 10): a TOOL_CALL
                // pause resolves from toolApprovals when it carries an explicit override,
                // otherwise it inherits the outer hitlConfig — EXCEPT an inherited
                // AUTO_APPROVE is demoted to WAIT_INDEFINITELY. Auto-approving a gated
                // tool call on a silent timeout is exactly the un-reviewed side effect
                // the gate exists to prevent, so an inherited AUTO_APPROVE (which the
                // designer set for RULE pauses, warned about at save time in Task 3) must
                // NOT auto-execute the tool. An EXPLICIT toolApprovals.timeoutPolicy=
                // AUTO_APPROVE is honored — the designer opted in at the tool level.
                applyEffectiveToolTimeoutPolicy(memory, hitlConfig);
                return;
            }
            if (hitlConfig.getTimeoutPolicy() == null)
                return;
            memory.setHitlTimeoutPolicy(hitlConfig.getTimeoutPolicy());
            memory.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } catch (Exception e) {
            LOGGER.warnf("Could not populate HITL timeout bookmark for %s: %s",
                    sanitize(memory.getConversationId()), sanitize(e.getMessage()));
        }
    }

    /**
     * Task 10 — resolves the EFFECTIVE tool-pause timeout policy and writes it into
     * the memory bookmark. Precedence:
     * <ol>
     * <li>the governing {@code toolApprovals.rules} entry's {@code timeoutPolicy} —
     * the most specific statement there is, resolved at gate time and carried on
     * the batch (iteration 1);</li>
     * <li>an explicit {@code toolApprovals.timeoutPolicy} (with its own
     * {@code approvalTimeout}) wins verbatim — an explicit AUTO_APPROVE is
     * honored;</li>
     * <li>otherwise inherit the outer {@code hitlConfig.timeoutPolicy}/
     * {@code approvalTimeout}, but demote an inherited {@code AUTO_APPROVE} to
     * {@code WAIT_INDEFINITELY} so a silent timeout never auto-executes a gated
     * tool call.</li>
     * </ol>
     * The timeout resolves down the same chain independently, so a rule that states
     * only a policy still inherits a duration.
     * <p>
     * Absent config on every level leaves the default WAIT_INDEFINITELY already set
     * by the caller.
     */
    void applyEffectiveToolTimeoutPolicy(IConversationMemory memory, AgentConfiguration.HitlConfig hitlConfig) {
        // Fix #1: the TOOL-LEVEL source is the config that ACTUALLY gated the paused
        // batch — the task-level override rides on the batch (persisted by
        // AgentOrchestrator.buildPendingBatch). Prefer it; fall back to the
        // agent-level hitlConfig.toolApprovals for a legacy batch (null field) or a
        // null batch. The inherit-from-outer fallback and the AUTO_APPROVE-demotion
        // below still read the OUTER hitlConfig — Task 10 semantics unchanged.
        ToolApprovalsConfig toolApprovals = effectiveToolApprovals(memory, hitlConfig);
        PendingToolCallBatch pendingBatch = memory.getHitlPendingToolCalls();
        ToolApprovalsConfig.ApprovalRule rule = pendingBatch != null ? pendingBatch.getEffectiveRule() : null;
        // The duration resolves down its own chain, independently of which branch
        // decides the policy: rule → toolApprovals → outer hitlConfig. Computed once
        // rather than repeated per branch — three identical copies would drift, and a
        // rule stating only "AUTO_REJECT" must still inherit a duration or the policy
        // never fires.
        //
        // Blank-aware on purpose: HitlConfigValidation treats a whitespace-only
        // approvalTimeout as ABSENT (isBlank) when deciding whether a finite rule may
        // inherit the enclosing duration, so it saves cleanly. If resolution here
        // treated it as PRESENT (isEmpty), it would win the chain, Duration.parse
        // would throw inside scheduleHitlTimeout, no schedule would be armed, and the
        // finite policy would silently degrade to wait-forever while the bookmark
        // still reported the finite policy name.
        String effectiveTimeout = firstNonBlank(
                rule != null ? rule.getApprovalTimeout() : null,
                toolApprovals != null ? toolApprovals.getApprovalTimeout() : null,
                hitlConfig.getApprovalTimeout());

        HitlTimeoutPolicy effectivePolicy;
        if (rule != null && rule.getTimeoutPolicy() != null) {
            // Most specific statement in the config — honored verbatim, AUTO_APPROVE
            // included: naming one endpoint and giving it a policy is as explicit as a
            // designer can be.
            effectivePolicy = rule.getTimeoutPolicy();
        } else if (toolApprovals != null && toolApprovals.getTimeoutPolicy() != null) {
            // Explicit tool-level override — honored verbatim (AUTO_APPROVE included).
            effectivePolicy = toolApprovals.getTimeoutPolicy();
        } else {
            // Inherit the outer policy, demoting AUTO_APPROVE to WAIT_INDEFINITELY.
            effectivePolicy = hitlConfig.getTimeoutPolicy();
            if (effectivePolicy == HitlTimeoutPolicy.AUTO_APPROVE) {
                effectivePolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
            }
        }
        if (effectivePolicy == null) {
            effectivePolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }
        memory.setHitlTimeoutPolicy(effectivePolicy);
        memory.setHitlApprovalTimeout(effectiveTimeout);
    }

    /**
     * First value that is neither null nor blank, or null. Blank-aware because
     * {@code RuntimeUtilities.isNullOrEmpty} is not: a whitespace-only duration
     * saves as "absent" and must resolve as absent too.
     */
    static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Fix #1: resolves the TASK-SCOPED effective tool-approval config for a paused
     * batch. Returns the config the batch carries (persisted at gate time — the
     * task-level override when the paused task set one, else the agent-level
     * default). Falls back to the agent-level {@code hitlConfig.toolApprovals} when
     * the batch is null (never populated) or carries a null effective config (a
     * legacy pre-fix batch) — so those paths resolve EXACTLY as before the fix.
     */
    static ToolApprovalsConfig effectiveToolApprovals(
                                                      IConversationMemory memory,
                                                      AgentConfiguration.HitlConfig hitlConfig) {
        PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
        if (batch != null && batch.getEffectiveToolApprovals() != null) {
            return batch.getEffectiveToolApprovals();
        }
        return hitlConfig != null ? hitlConfig.getToolApprovals() : null;
    }

    /**
     * Carries the agent-level {@code hitlConfig.toolApprovals} config onto the live
     * memory as a transient carrier (never persisted) so the tool-approval gate in
     * {@code LlmTask}/{@code AgentOrchestrator} can resolve its effective config
     * before the pipeline runs. Reads the PINNED agent version (parity with
     * {@link #populateHitlTimeoutBookmark}). Absent config leaves the carrier null
     * (gate inert — byte-identical to the pre-HITL path).
     */
    void populateToolApprovalsConfig(IConversationMemory memory) {
        try {
            var lookup = lookupAgentConfigPinned(memory.getAgentId(), memory.getAgentVersion());
            // A FAILED read is not "no policy". Collapsing the two left the carrier
            // null, which makes the gate wholly inert — so a store blip while
            // resuming became a window in which every write executed unapproved.
            // Fail closed on not-knowing; see ToolApprovalsConfig#UNDETERMINED.
            if (lookup.readFailed()) {
                LOGGER.warnf("Could not read the tool-approval policy for %s — gating every tool call until it can be read",
                        sanitize(memory.getConversationId()));
                memory.setAgentToolApprovalsConfig(ToolApprovalsConfig.UNDETERMINED);
                return;
            }
            AgentConfiguration agentConfig = lookup.config();
            if (agentConfig == null || agentConfig.getHitlConfig() == null) {
                memory.setAgentToolApprovalsConfig(null);
                return;
            }
            memory.setAgentToolApprovalsConfig(agentConfig.getHitlConfig().getToolApprovals());
        } catch (Exception e) {
            // Same reasoning: this catch used to leave the carrier untouched (null on
            // a fresh memory), which is the fail-open again by a different route.
            LOGGER.warnf("Could not populate tool-approval config for %s: %s — gating every tool call",
                    sanitize(memory.getConversationId()), sanitize(e.getMessage()));
            memory.setAgentToolApprovalsConfig(ToolApprovalsConfig.UNDETERMINED);
        }
    }

    /** Reads the agent config at the pinned version, falling back to the latest. */
    AgentConfiguration readAgentConfigPinned(String agentId, Integer agentVersion) {
        return lookupAgentConfigPinned(agentId, agentVersion).config();
    }

    /**
     * An agent-config read, together with whether it actually FAILED.
     * <p>
     * {@link #readAgentConfigPinned} collapses both outcomes to {@code null}, which
     * is fine for callers that only want a best-effort config. It is not fine for
     * the approval gate: there, "no config" means run ungated, and "could not read"
     * has to mean the opposite. See {@link #populateToolApprovalsConfig}.
     *
     * @param readFailed
     *            true when the store ERRORED, at either step. An agent (or a pinned
     *            version) that genuinely does not exist yields
     *            {@code (config, false)} — absence is an answer, not a failure to
     *            obtain one.
     */
    record AgentConfigLookup(AgentConfiguration config, boolean readFailed) {
    }

    AgentConfigLookup lookupAgentConfigPinned(String agentId, Integer agentVersion) {
        try {
            if (agentVersion != null && agentVersion > 0) {
                return new AgentConfigLookup(agentStore.read(agentId, agentVersion), false);
            }
        } catch (IResourceStore.ResourceNotFoundException pinnedGone) {
            // The pinned VERSION is genuinely absent (deleted, or never existed).
            // That is an answer, so falling back to the latest is legitimate.
            LOGGER.debugf("Pinned agent config %s v%s does not exist, falling back to latest", agentId, agentVersion);
        } catch (Exception pinnedError) {
            // Anything else means the store could not answer for the PINNED version.
            // Falling through to the latest would then silently swap in a DIFFERENT
            // version's policy — and a later version may have relaxed the gate the
            // conversation is pinned to. Report the failure instead; the caller that
            // cares (populateToolApprovalsConfig) fails closed on it.
            LOGGER.warnf("Could not read the pinned agent config %s v%s: %s",
                    sanitize(agentId), agentVersion, sanitize(pinnedError.getMessage()));
            return new AgentConfigLookup(null, true);
        }
        try {
            IResourceStore.IResourceId currentId = agentStore.getCurrentResourceId(agentId);
            return new AgentConfigLookup(currentId != null ? agentStore.read(agentId, currentId.getVersion()) : null, false);
        } catch (IResourceStore.ResourceNotFoundException absent) {
            // The agent itself is gone — again an answer, not a store failure.
            LOGGER.debugf("Agent config %s does not exist", agentId);
            return new AgentConfigLookup(null, false);
        } catch (Exception e) {
            LOGGER.warnf("Could not read agent config %s: %s", sanitize(agentId), sanitize(e.getMessage()));
            return new AgentConfigLookup(null, true);
        }
    }
    public ConversationMemorySnapshot getConversationMemorySnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Conversation not found: " + conversationId);
        }
        return snapshot;
    }
    public List<PendingApprovalSummary> listPendingApprovals(int limit)
            throws ResourceStoreException {
        // #17: bounded, projection-based listing — never deserializes full
        // conversation documents on the Mongo backend.
        return conversationMemoryStore.findPendingApprovalSummaries(Math.max(1, Math.min(limit, 1000)));
    }
    public List<PendingApprovalSummary> listPendingApprovals(String ownerUserId, int limit)
            throws ResourceStoreException {
        // Owner filter is pushed into the query so the limit applies AFTER the
        // restriction — a non-admin inbox can't be starved by others' backlog.
        return conversationMemoryStore.findPendingApprovalSummaries(ownerUserId, Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * Minimum delay before a past-due re-armed timeout fires (mirrors crash
     * recovery).
     */
    static final Duration HITL_REARM_GRACE = Duration.ofMinutes(2);

    /**
     * Creates a one-shot schedule that fires the {@link HitlTimeoutHandler} when
     * the configured approval timeout expires. Reads the policy from the memory's
     * HITL BOOKMARK (populated by {@link #populateHitlTimeoutBookmark} just before
     * — single config resolution per pause, and bookmark and schedule can never
     * diverge). No-ops without a finite policy + timeout.
     * <p>
     * G7: the deadline is anchored to the ORIGINAL pause time ({@code pausedAt +
     * timeout}) when the bookmark carries a pausedAt — so a
     * restore-after-failed-resume re-arms at the same absolute due time
     * approval-status reports, instead of silently extending it by another full
     * timeout. A past-due deadline is clamped to {@code now + grace} (mirrors crash
     * recovery's rearmSchedule). A fresh pause has pausedAt ≈ now, so this reduces
     * to now + timeout.
     */
    void scheduleHitlTimeout(String conversationId, IConversationMemory memory) {
        try {
            String timeoutStr = memory.getHitlApprovalTimeout();
            HitlTimeoutPolicy policy = memory.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank() || policy == null
                    || policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                return;
            }

            Duration timeout = Duration.parse(timeoutStr);
            Instant pausedAt = memory.getHitlPausedAt();
            Instant now = Instant.now();
            Instant fireAt = pausedAt != null ? pausedAt.plus(timeout) : now.plus(timeout);
            // Clamp ONLY a past-due deadline (a restore-after-failed-resume or crash
            // recovery re-arm whose original pausedAt+timeout already elapsed) up to a
            // small grace window. A FRESH pause has pausedAt ≈ now, so fireAt is in the
            // future and must be honored as configured — clamping it to the grace floor
            // would silently raise any approvalTimeout shorter than the grace (e.g.
            // PT30S, which HitlConfigValidation explicitly permits) to 2 minutes.
            if (fireAt.isBefore(now)) {
                fireAt = now.plus(HITL_REARM_GRACE);
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(HitlSchedules.regularTimeoutScheduleName(conversationId));
            schedule.setAgentId(memory.getAgentId());
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setEnabled(true);
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    HitlSchedules.METADATA_TYPE_KEY, HitlSchedules.METADATA_TYPE_TIMEOUT,
                    HitlSchedules.METADATA_POLICY_KEY, policy.name(),
                    HitlSchedules.METADATA_SURFACE_KEY, HitlSchedules.SURFACE_REGULAR,
                    HitlSchedules.METADATA_CONVERSATION_ID_KEY, conversationId));
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Scheduled HITL timeout for conversation %s at %s (policy: %s)",
                    sanitize(conversationId), fireAt, policy.name());
        } catch (Exception e) {
            LOGGER.warnf("Failed to schedule HITL timeout for conversation %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }

    /**
     * Deletes any existing HITL timeout schedule for the given conversation. Called
     * on resume and cancel to prevent stale fires and duplicate schedules.
     */
    void deleteHitlTimeoutSchedule(String conversationId) {
        try {
            int deleted = scheduleStore.deleteSchedulesByName(HitlSchedules.regularTimeoutScheduleName(conversationId));
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d HITL timeout schedule(s) for conversation %s", deleted, sanitize(conversationId));
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete HITL timeout schedule for conversation %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }
}
