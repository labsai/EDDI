/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.hitl.HitlRejectionPolicy;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.Counter;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * HITL (human-in-the-loop) lifecycle for group discussions: pause commit,
 * no-progress detection, cancel-signal races, timeout scheduling, and the
 * cancel/resume public entry points plus their compliance audit trail.
 * <p>
 * Unites two regions of {@code GroupConversationService} that were textually
 * non-adjacent but mutually dependent — the pause/cancel-race helpers
 * ({@code executeDiscussion} calls into) and the {@code cancelDiscussion}/
 * {@code resumeDiscussion} public surface — into one cohesive class. Extracted
 * (Wave R, R1 step 7) as a pure move — no behavior change; every lock ordering,
 * CAS pattern, and race-window comment carries over unchanged.
 * <p>
 * Holds a back-reference to the concrete {@link GroupConversationService} for
 * three calls that stay on the facade: {@code executeDiscussion} (the phase
 * loop {@code resumeDiscussion} re-enters), {@code resolvePhases}, and
 * {@code cleanupEphemeralAgents}. Same safety argument as
 * {@link MemberTurnExecutor}'s self-reference — this class's constructor only
 * stores it, never invokes a method on it before the facade's own constructor
 * (and therefore full field initialization) completes.
 * <p>
 * {@code activeTokens} and the virtual-thread {@link ExecutorService} are
 * shared by reference, not owned — {@code GroupConversationService} and its
 * other collaborators (e.g. {@link TaskForceEngine}) read/write the same map,
 * and the facade keeps the {@code @PreDestroy} shutdown hook for the executor.
 */
public class GroupHitlCoordinator {

    private static final Logger LOGGER = Logger.getLogger(GroupHitlCoordinator.class);

    /**
     * Minimum delay before a past-due re-armed group timeout fires (mirrors crash
     * recovery).
     */
    private static final Duration GROUP_HITL_REARM_GRACE = Duration.ofMinutes(2);

    private final IAgentGroupStore groupStore;
    private final IGroupConversationStore conversationStore;
    private final IScheduleStore scheduleStore;
    private final AuditLedgerService auditLedgerService;
    private final GroupSigningGuard signingGuard;
    private final ConcurrentHashMap<String, DiscussionControlToken> activeTokens;
    private final ExecutorService executorService;
    private final CallerIdentityContext callerIdentityContext;
    private final GroupConversationService groupConversationService;
    private final Counter counterGroupHitlPause;
    private final Counter counterGroupHitlResume;
    private final Counter counterGroupFailure;

    public GroupHitlCoordinator(IAgentGroupStore groupStore, IGroupConversationStore conversationStore,
            IScheduleStore scheduleStore, AuditLedgerService auditLedgerService, GroupSigningGuard signingGuard,
            ConcurrentHashMap<String, DiscussionControlToken> activeTokens, ExecutorService executorService,
            CallerIdentityContext callerIdentityContext, GroupConversationService groupConversationService,
            Counter counterGroupHitlPause, Counter counterGroupHitlResume, Counter counterGroupFailure) {
        this.groupStore = groupStore;
        this.conversationStore = conversationStore;
        this.scheduleStore = scheduleStore;
        this.auditLedgerService = auditLedgerService;
        this.signingGuard = signingGuard;
        this.activeTokens = activeTokens;
        this.executorService = executorService;
        this.callerIdentityContext = callerIdentityContext;
        this.groupConversationService = groupConversationService;
        this.counterGroupHitlPause = counterGroupHitlPause;
        this.counterGroupHitlResume = counterGroupHitlResume;
        this.counterGroupFailure = counterGroupFailure;
    }

    // =================================================================
    // Pause commit, no-progress guard, cancel-signal races
    // =================================================================

    /**
     * Fires the cancelled event so SSE subscribers see the terminal state and can
     * close their streams (previously CancelledEvent existed but was never emitted,
     * leaving /discuss/stream clients hanging after a cancel).
     */
    public void notifyCancelled(GroupConversation gc, GroupDiscussionEventListener listener) {
        if (listener != null) {
            listener.onCancelled(new GroupConversationEventSink.CancelledEvent(
                    "Discussion cancelled", gc.getUserId()));
        }
    }

    /**
     * Cross-pod terminal-override check for a phase boundary (#27/#45). Group
     * control is per-pod (activeTokens is process-local), so a cancel/ABORT landing
     * on another pod flips only the persisted state — the running leg never sees it
     * and its next whole-document write would resurrect the running state and
     * clobber concurrent transcript writes. Re-reads the persisted state at the
     * boundary: if another writer moved it to a terminal state, the leg stops and
     * honors it (notifying the listener on a cancel). Best-effort: a store read
     * failure keeps the leg running (the local token path still applies).
     *
     * @return true if the persisted state is terminal and this leg should stop
     */
    public boolean persistedTerminalOverride(GroupConversation gc, GroupDiscussionEventListener listener) {
        try {
            var persistedState = conversationStore.read(gc.getId()).getState();
            if (persistedState == GroupConversationState.CANCELLED
                    || persistedState == GroupConversationState.FAILED
                    || persistedState == GroupConversationState.COMPLETED
                    // CLOSED is terminal too: without it, a leg that keeps running past a
                    // concurrent close would fall through to the unconditional whole-document
                    // write below and RESURRECT the closed conversation (its member
                    // conversations are already ended and its ephemeral agents deleted).
                    || persistedState == GroupConversationState.CLOSED) {
                // Align the in-memory state with the terminal value another pod/writer
                // committed so executeDiscussion's finally makes the correct ephemeral-
                // agent cleanup decision — this leg's gc is otherwise still a running
                // state (IN_PROGRESS/SYNTHESIZING) and cleanup would be skipped. (CLOSED
                // is deliberately NOT in the finally's cleanup set — close already
                // reclaimed the agents.)
                gc.setState(persistedState);
                LOGGER.infof("Group discussion %s was moved to %s elsewhere — stopping this leg at the phase boundary",
                        gc.getId(), persistedState);
                if (persistedState == GroupConversationState.CANCELLED) {
                    notifyCancelled(gc, listener);
                }
                return true;
            }
        } catch (Exception e) {
            LOGGER.debugf("Phase-boundary persisted-state re-check failed for %s: %s (continuing)",
                    gc.getId(), e.getMessage());
        }
        return false;
    }

    /**
     * Commits an HITL pause to the group conversation — sets AWAITING_APPROVAL,
     * records pause metadata, persists, and fires the SSE event.
     */
    public void commitPause(GroupConversation gc, int phaseIdx,
                            AgentGroupConfiguration.DiscussionPhase phase,
                            String granularity, int currentTurnCount,
                            GroupDiscussionEventListener listener,
                            AgentGroupConfiguration config)
            throws IResourceStore.ResourceStoreException {
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(Instant.now());
        gc.setPausedAtPhaseIndex(phaseIdx);
        gc.setPausedPhaseName(phase.name());
        gc.setPausedTurnCount(currentTurnCount);
        gc.setHitlPauseType(HitlPauseType.valueOf(granularity));
        gc.setHitlPauseReason("Requires human approval (" + granularity + ") — phase: " + phase.name());
        // Phase 6d: Copy timeout config into bookmark for REST visibility (from the
        // already-loaded config — no extra store read)
        if (config != null && config.getHitlConfig() != null) {
            var hitlConfig = config.getHitlConfig();
            gc.setHitlTimeoutPolicy(hitlConfig.getTimeoutPolicy() != null
                    ? hitlConfig.getTimeoutPolicy()
                    : HitlTimeoutPolicy.WAIT_INDEFINITELY);
            gc.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } else {
            gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        }
        conversationStore.update(gc);

        // MAJOR-2: Schedule group timeout if configured
        scheduleGroupHitlTimeout(gc);
        counterGroupHitlPause.increment();

        if (listener != null) {
            listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(
                    phaseIdx, phase.name(), gc.getHitlPauseReason(), granularity));
        }
    }

    /**
     * Fingerprint of the task state at a TASK-granularity pause (#4). Captures the
     * phase index plus every non-terminal task's id and status — deliberately NOT
     * the turn count, so a resume that burns turns without advancing any task
     * produces the SAME fingerprint and is detected as no-progress. Two pauses with
     * equal fingerprints mean nothing changed between them.
     */
    public String taskPauseFingerprint(GroupConversation gc, int phaseIdx) {
        var sb = new StringBuilder("phase=").append(phaseIdx).append(';');
        if (gc.getTaskList() != null) {
            gc.getTaskList().all().stream()
                    .filter(t -> t.status() != SharedTaskList.TaskStatus.COMPLETED
                            && t.status() != SharedTaskList.TaskStatus.VERIFIED
                            && t.status() != SharedTaskList.TaskStatus.FAILED)
                    .map(t -> t.id() + ":" + t.status())
                    .sorted()
                    .forEach(s -> sb.append(s).append(','));
        }
        return sb.toString();
    }

    /**
     * Fails a TASK-granularity discussion that cannot make progress (#4): a resume
     * re-paused at the same phase with an identical task-state fingerprint. Records
     * an actionable transcript entry, fires a terminal SSE event, releases
     * paused-state resources, and persists FAILED. Guarantees the
     * pause→approve→pause loop terminates.
     */
    public void failDiscussionNoProgress(GroupConversation gc, int phaseIdx, DiscussionPhase phase,
                                         GroupDiscussionEventListener listener)
            throws IResourceStore.ResourceStoreException {
        String msg = "Discussion failed: EXECUTE phase '" + phase.name() + "' cannot make progress — "
                + "the same task(s) remained executable across an approval cycle without advancing "
                + "(exhausted turn budget or tasks assigned to an agent that can no longer be resolved). "
                + "Increase protocol.maxTurns, fix the task assignments, or cancel the discussion.";
        LOGGER.warnf("No-progress TASK pause detected for GC %s at phase %d — failing to guarantee termination",
                gc.getId(), phaseIdx);
        gc.getTranscript().add(new TranscriptEntry(
                "system", "System", null, phaseIdx, phase.name(),
                TranscriptEntryType.ERROR, Instant.now(), msg, null));
        gc.setState(GroupConversationState.FAILED);
        gc.setPausedAt(null);
        gc.setHitlLastPauseFingerprint(null);
        gc.setLastModified(Instant.now());
        conversationStore.update(gc);
        counterGroupFailure.increment();
        deleteGroupHitlTimeoutSchedule(gc.getId());
        cleanupAfterTerminalState(gc);
        if (listener != null) {
            listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(msg));
        }
    }

    /**
     * Converts a just-committed pause into a cancellation when a cancel signal
     * landed while the pause was being written. cancelDiscussion saw the live
     * token, signalled it, and reported success — but the running leg had already
     * passed its pre-gate cancel check, so without this the pause would survive a
     * "successful" cancel and the token signal would be dropped by the finally
     * block.
     */
    public void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        convertPauseToCancelIfSignalled(gc, listener, activeTokens.get(gc.getId()));
    }

    /**
     * Removes the control token AND re-checks the removed instance for a cancel
     * signal. A cancel that landed AFTER an in-leg
     * {@code convertPauseToCancelIfSignalled} check but BEFORE this remove would
     * otherwise be dropped with the discarded token, leaving a "cancelled"
     * discussion stuck AWAITING_APPROVAL with an armed timer (cancelDiscussion
     * reported success on the token path and did not touch the DB/schedule).
     * Re-checking the removed instance closes that window; signals arriving after
     * the remove take cancelDiscussion's DB-CAS path instead.
     */
    public void removeTokenAndConvertIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        var removed = activeTokens.remove(gc.getId());
        if (removed != null && removed.isCancelled() && gc.getState() == GroupConversationState.AWAITING_APPROVAL) {
            convertPauseToCancelIfSignalled(gc, listener, removed);
        }
    }

    public void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener,
                                                DiscussionControlToken token) {
        if (token == null || !token.isCancelled()) {
            return;
        }
        // Only the persist itself may revert the in-memory state. Past the commit
        // below, CANCELLED is durable, and reverting memory to AWAITING_APPROVAL
        // would make executeDiscussion's finally block read the wrong state: it
        // skips signingGuard.forgetConversation for AWAITING_APPROVAL (leaking the
        // verification cursor) and only runs cleanupEphemeralAgents for
        // FAILED/CANCELLED — so dynamically created agents would stay deployed.
        // The realistic post-commit thrower is the listener: an SSE sink on a closed
        // stream. It used to sit inside this try.
        try {
            gc.setState(GroupConversationState.CANCELLED);
            gc.setPausedAt(null);
            gc.setLastModified(Instant.now());
            conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        } catch (IResourceStore.ResourceModifiedException e) {
            // Someone else moved the state concurrently (approve/timeout) — restore
            // the in-memory state so the executeDiscussion finally block does not
            // release paused-state resources for a conversation still paused in DB.
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            LOGGER.infof("Pause→cancel conversion for GC %s lost a state race — leaving persisted state", gc.getId());
            return;
        } catch (IGroupConversationStore.GroupConversationGoneException e) {
            // deleted concurrently — nothing left to cancel
            LOGGER.infof("Pause→cancel conversion for GC %s skipped — conversation was deleted", gc.getId());
            return;
        } catch (Exception e) {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            LOGGER.warnf("Failed to convert just-committed pause of GC %s to CANCELLED: %s",
                    gc.getId(), e.getMessage());
            return;
        }

        // Committed. Everything below is best-effort follow-up work that must not
        // change the state the cleanup paths will read. The first two swallow their
        // own exceptions already; the listener is wrapped here for the same reason.
        deleteGroupHitlTimeoutSchedule(gc.getId());
        auditHitlCancellation(gc, token.getSignal());
        LOGGER.infof("Cancel signal landed while pausing GC %s — converted pause to CANCELLED", gc.getId());
        try {
            notifyCancelled(gc, listener);
        } catch (Exception e) {
            LOGGER.warnf("Cancel listener threw for GC %s after CANCELLED was committed — ignoring: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Creates a one-shot schedule for group HITL timeout. Reads the pause bookmark
     * fields already set on the conversation (by commitPause/restoreGroupPause) —
     * NOT the group config, so the schedule always matches what approval-status
     * reports even if the config changed since the pause. No-ops if not configured
     * or WAIT_INDEFINITELY.
     * <p>
     * G7: the deadline is anchored to the ORIGINAL pause time ({@code pausedAt +
     * timeout}) so a restore-after-failed-resume re-arms at the same absolute due
     * time approval-status reports, not now + another full timeout. A past-due
     * deadline is clamped to {@code now + grace} (mirrors crash recovery). A fresh
     * pause has pausedAt ≈ now, so this reduces to now + timeout.
     */
    public void scheduleGroupHitlTimeout(GroupConversation gc) {
        try {
            String timeoutStr = gc.getHitlApprovalTimeout();
            HitlTimeoutPolicy policy = gc.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank()
                    || policy == null
                    || policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                return;
            }

            Duration timeout = Duration.parse(timeoutStr);
            Instant pausedAt = gc.getPausedAt();
            Instant now = Instant.now();
            Instant fireAt = pausedAt != null ? pausedAt.plus(timeout) : now.plus(timeout);
            // Clamp ONLY a past-due deadline (crash recovery / restore-after-failed-
            // resume re-arm) up to the grace window. A FRESH pause has pausedAt ≈ now,
            // so honor its configured timeout as-is — clamping it to the grace floor
            // would silently raise any sub-2min approvalTimeout to 2 minutes (parity
            // with the regular surface's scheduleHitlTimeout fix).
            if (fireAt.isBefore(now)) {
                fireAt = now.plus(GROUP_HITL_REARM_GRACE);
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(HitlSchedules.groupTimeoutScheduleName(gc.getId()));
            schedule.setEnabled(true);
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    HitlSchedules.METADATA_TYPE_KEY, HitlSchedules.METADATA_TYPE_TIMEOUT,
                    HitlSchedules.METADATA_POLICY_KEY, policy.name(),
                    HitlSchedules.METADATA_SURFACE_KEY, HitlSchedules.SURFACE_GROUP,
                    HitlSchedules.METADATA_CONVERSATION_ID_KEY, gc.getId()));
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Scheduled group HITL timeout for %s at %s (policy: %s)",
                    gc.getId(), fireAt, policy);
        } catch (Exception e) {
            LOGGER.warnf("Failed to schedule group HITL timeout for %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    // =================================================================
    // HITL lifecycle — cancel & resume
    // =================================================================

    public boolean cancelDiscussion(String conversationId, ControlSignal mode)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        // #13: decide the cancel path BEFORE deleting the timeout schedule. The old
        // order deleted the schedule unconditionally up front, so a cancel racing a
        // fresh pause (token present, pause just committed) reported success yet
        // left the pause intact — stripped of its finite timeout, silently
        // degrading a bounded policy to WAIT_INDEFINITELY.
        var token = activeTokens.get(conversationId);
        if (token != null) {
            if (mode == ControlSignal.CANCEL_IMMEDIATE) {
                token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
                token.cancelActiveFuture();
            } else {
                token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            }
            // Do NOT delete the schedule here: a running leg has no armed schedule,
            // and if the leg is mid-pause-commit it converts the pause to CANCELLED
            // itself (convertPauseToCancelIfSignalled), deleting the schedule only
            // once the cancel actually wins.
            return true; // in-flight leg signalled — it will persist CANCELLED
        }
        // Not actively running — update DB with a state-CAS (#9): a plain
        // read-modify-write would race a concurrent approve/resume and could
        // resurrect a terminal state.
        var gc = conversationStore.read(conversationId);
        var state = gc.getState();
        // Only cancel from non-terminal states — guard against overwriting COMPLETED,
        // FAILED or CLOSED after a race. CLOSED is irreversible: without it here a
        // cancel
        // would CAS CLOSED → CANCELLED and un-terminalize an already-reclaimed
        // conversation.
        if (state == GroupConversationState.COMPLETED
                || state == GroupConversationState.CANCELLED
                || state == GroupConversationState.FAILED
                || state == GroupConversationState.CLOSED) {
            LOGGER.infof("Cancel skipped: GC %s already in terminal state %s", conversationId, state);
            return false;
        }
        boolean wasPaused = state == GroupConversationState.AWAITING_APPROVAL;
        gc.setState(GroupConversationState.CANCELLED);
        gc.setPausedAt(null); // keep isPaused() consistent with the terminal state
        gc.setLastModified(Instant.now());
        try {
            conversationStore.updateIfState(gc, state);
        } catch (IResourceStore.ResourceModifiedException e) {
            // CAS lost — leave the schedule alone: whoever won the race (a fresh
            // pause / approve / timeout) owns the schedule now. Report 409.
            LOGGER.infof("Cancel of group conversation %s lost a concurrent state race — not overwriting", conversationId);
            return false;
        }
        // Cancel won: delete the timeout schedule only now (MAJOR-3).
        deleteGroupHitlTimeoutSchedule(conversationId);
        if (wasPaused) {
            // Cancelling a pending approval is an HITL decision — audit it, and
            // release resources that were kept alive across the pause.
            auditHitlCancellation(gc, mode);
            cleanupAfterTerminalState(gc);
        }
        return true;
    }

    public GroupConversation resumeDiscussion(String groupConversationId, GroupApprovalRequest request,
                                              GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException,
            IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {

        // Wave 0, F6: refuse/migrate before anything else reads a bookmark field —
        // the state CAS below hasn't happened yet, so a refusal here is a plain
        // throw with nothing to roll back (unlike the single-conversation surface,
        // where the CAS runs before the document loads). Combined into gc's single
        // assignment (rather than reassigning it) so gc stays effectively final for
        // the lambdas later in this method.
        var gc = GroupConversationSchemaMigrations.prepareForResume(conversationStore.read(groupConversationId));
        if (gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
            throw new GroupDiscussionException("Group conversation is not awaiting approval");
        }

        // Apply task-level approvals if present
        // Phase 5a: Load rejection policy from config
        boolean retryOnReject = false;
        try {
            var resId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (resId != null) {
                var config = groupStore.read(gc.getGroupId(), resId.getVersion());
                if (config.getHitlConfig() != null) {
                    retryOnReject = config.getHitlConfig().getOnTaskRejection() == HitlRejectionPolicy.RETRY;
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not load rejection policy for %s, defaulting to FAIL: %s",
                    groupConversationId, e.getMessage());
        }

        // An explicit empty map is treated exactly like an absent map (the
        // approve-all shortcut) — otherwise {} approves nothing and the resumed
        // phase instantly re-pauses.
        boolean hasTaskApprovals = request.getTaskApprovals() != null && !request.getTaskApprovals().isEmpty();
        // #30: a task-level body against a PHASE-paused (or task-list-less)
        // conversation must fail 400 — not be silently ignored and treated as a
        // plain phase approve. Reject before the CAS so the operator sees the error
        // instead of an unexpected full resume.
        if (hasTaskApprovals
                && (gc.getTaskList() == null || gc.getHitlPauseType() == HitlPauseType.PHASE)) {
            throw new IllegalArgumentException(
                    "taskApprovals were provided but this conversation has no task-level approval to apply "
                            + "(it is " + (gc.getHitlPauseType() == HitlPauseType.PHASE
                                    ? "paused at PHASE granularity"
                                    : "paused without a task list")
                            + "); omit taskApprovals to approve the phase");
        }
        if (hasTaskApprovals && gc.getTaskList() != null) {
            // #13: validate the WHOLE map up front — unknown taskIds, tasks not
            // awaiting approval, and unknown decision VALUES must fail as a
            // 400-class error BEFORE any mutation (partial application) and
            // BEFORE the CAS/schedule deletion.
            for (var entry : request.getTaskApprovals().entrySet()) {
                var task = gc.getTaskList().findById(entry.getKey());
                if (task == null) {
                    throw new IllegalArgumentException(
                            "Unknown taskId in taskApprovals: '" + entry.getKey() + "'");
                }
                if (task.status() != SharedTaskList.TaskStatus.AWAITING_APPROVAL) {
                    throw new IllegalArgumentException(
                            "Task '" + entry.getKey() + "' is not awaiting approval (status: " + task.status() + ")");
                }
                String value = entry.getValue();
                if (value == null
                        || (!"APPROVED".equalsIgnoreCase(value) && !"REJECTED".equalsIgnoreCase(value))) {
                    throw new IllegalArgumentException(
                            "Invalid taskApprovals value for '" + entry.getKey()
                                    + "': expected APPROVED or REJECTED (case-insensitive), got '" + value + "'");
                }
            }

            String reviewerNote = request.getDecision() != null && request.getDecision().getNote() != null
                    ? request.getDecision().getNote()
                    : "Rejected by human reviewer";
            for (var entry : request.getTaskApprovals().entrySet()) {
                if ("APPROVED".equalsIgnoreCase(entry.getValue())) {
                    gc.getTaskList().approveTask(entry.getKey());
                } else if (retryOnReject) {
                    // RETRY policy: reset to ASSIGNED with the reviewer's feedback so
                    // the re-executing agent knows what to fix (C-D)
                    gc.getTaskList().resetFromAnyToAssigned(entry.getKey(), reviewerNote);
                    LOGGER.infof("Task '%s' rejected with RETRY policy — reset to ASSIGNED", entry.getKey());
                } else {
                    // FAIL policy (default): permanently reject the task
                    gc.getTaskList().rejectTask(entry.getKey(), reviewerNote);
                }
            }
        }

        // AUTO_APPROVE fix: When TASK granularity + APPROVED verdict + no explicit
        // taskApprovals (e.g., from timeout handler), auto-approve all
        // AWAITING_APPROVAL
        // tasks. Without this, resume re-enters the same TASK phase, tasks are still
        // excluded by findExecutableTasks, and it re-pauses → infinite reschedule loop.
        var decision = request.getDecision();
        if (decision != null
                && decision.getVerdict() == HitlDecision.HitlVerdict.APPROVED
                && !hasTaskApprovals
                && gc.getTaskList() != null
                && gc.getHitlPauseType() == HitlPauseType.TASK) {
            gc.getTaskList().all().stream()
                    .filter(t -> t.status() == SharedTaskList.TaskStatus.AWAITING_APPROVAL)
                    .forEach(t -> gc.getTaskList().approveTask(t.id()));
        }

        // #4: On an EXPLICIT HUMAN approval of a TASK pause, grant a fresh turn
        // budget so the resume can actually drive the remaining executable tasks —
        // the preserved budget (seeded from pausedTurnCount) may already be
        // exhausted, which would otherwise re-pause immediately. AUTO_APPROVE
        // (decidedBy "system:...") deliberately does NOT get a fresh budget: it
        // must terminate via the no-progress fingerprint guard, never run
        // unattended forever. If the fresh budget still yields no task progress,
        // the fingerprint guard fails the discussion on the next pause.
        boolean humanDecision = decision != null
                && (decision.getDecidedBy() == null || !decision.getDecidedBy().startsWith("system:"));
        if (humanDecision
                && decision.getVerdict() == HitlDecision.HitlVerdict.APPROVED
                && gc.getHitlPauseType() == HitlPauseType.TASK
                && gc.getPausedTurnCount() > 0) {
            LOGGER.infof("Human approval of TASK pause for GC %s — granting a fresh turn budget", groupConversationId);
            gc.setPausedTurnCount(0);
        }

        // Apply phase-level decision
        if (decision != null && decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
            gc.setState(GroupConversationState.FAILED);
            gc.setPausedAt(null);
            // MAJOR-4: Use CAS to prevent concurrent approve clobbering reject
            conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
            // Delete timeout schedule only after CAS succeeds (Phase 5e)
            deleteGroupHitlTimeoutSchedule(groupConversationId);
            auditHitlDecision(gc, decision);
            // A rejection is terminal: notify the (SSE) listener so streams close
            // instead of hanging, and release paused-state resources.
            if (listener != null) {
                listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                        gc.getState(), gc.getSynthesizedAnswer()));
            }
            cleanupAfterTerminalState(gc);
            return gc;
        }

        // Save resume point and pause type before clearing
        int resumePhaseIndex = gc.getPausedAtPhaseIndex();
        String pausedPhaseName = gc.getPausedPhaseName(); // saved for config drift guard
        // BLOCKER: read hitlPauseType — TASK pauses mid-phase, so we must re-enter
        // at the SAME phase (findExecutableTasks is idempotent for approved tasks).
        // PHASE pauses after the phase completes, so resume at +1.
        var pauseType = gc.getHitlPauseType();
        // #29: capture the timeout-policy bookmark BEFORE it is nulled below, so a
        // failed resume whose config re-read also fails can restore the ORIGINAL
        // finite policy instead of silently disarming it (persisting null →
        // WAIT_INDEFINITELY). #35: capture the original pausedAt for the same reason
        // — a restore must not shift a re-armed timeout's due time forward.
        final HitlTimeoutPolicy savedTimeoutPolicy = gc.getHitlTimeoutPolicy();
        final String savedApprovalTimeout = gc.getHitlApprovalTimeout();
        final Instant originalPausedAt = gc.getPausedAt();

        // Clear pause state but preserve pausedTurnCount — it seeds turnCounter on
        // resume (M3)
        gc.setPausedAt(null);
        gc.setPausedAtPhaseIndex(-1);
        gc.setPausedPhaseName(null);
        gc.setHitlPauseType(null);
        gc.setHitlPauseReason(null);
        gc.setHitlTimeoutPolicy(null);
        gc.setHitlApprovalTimeout(null);
        gc.setState(GroupConversationState.IN_PROGRESS);

        // Zero-match outcomes are distinguished by the store: a concurrent DELETE
        // surfaces as (unchecked) GroupConversationGoneException → REST 404, a
        // genuine state race as ResourceModifiedException → REST 409.
        conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        // O2: register the control token IMMEDIATELY after the resume CAS — before
        // deleting the schedule and notifying listeners. Otherwise a concurrent
        // cancelDiscussion landing between the CAS and a later put finds no token,
        // takes the DB branch, sees IN_PROGRESS (non-terminal), CAS's to CANCELLED,
        // and returns success — yet the resume then registers a FRESH non-cancelled
        // token and runs a full phase on a discussion the operator was told was
        // cancelled. With the token present here, that cancel takes the SIGNAL path
        // (setSignal) and executeDiscussion's top-of-phase isCancelled() check stops
        // before any member-agent work runs.
        activeTokens.put(gc.getId(), new DiscussionControlToken());
        // Delete timeout schedule only after CAS succeeds (Phase 5e) — if CAS
        // fails, the schedule is preserved so the timeout can still fire.
        deleteGroupHitlTimeoutSchedule(groupConversationId);
        // #35: the metric + audit entry are deliberately NOT written here — they are
        // deferred until the resume is actually enqueued (below), mirroring the
        // regular surface. A submit failure rolls the pause back, so a rolled-back
        // attempt must not pollute the resume metric or the EU-AI-Act audit trail.

        // The resume is committed — tell SSE subscribers the discussion is live
        // again (the stream stays open for the resumed discussion's events).
        if (listener != null) {
            listener.onHitlResume(new GroupConversationEventSink.HitlResumeEvent(
                    decision != null && decision.getVerdict() != null ? decision.getVerdict().name() : "APPROVED",
                    decision != null ? decision.getNote() : null,
                    decision != null ? decision.getDecidedBy() : null));
        }

        // Resume execution in background thread. Use the current run's resumeQuestion
        // (set by continueDiscussion for a continuation round) so the remaining phases
        // re-run with the follow-up question; fall back to originalQuestion for the
        // initial round and legacy documents that predate the field.
        var groupId = gc.getGroupId();
        var question = gc.getResumeQuestion() != null ? gc.getResumeQuestion() : gc.getOriginalQuestion();
        // BLOCKER fix: TASK pauses mid-phase → re-enter at same phase (idempotent).
        // PHASE pauses after phase completes → resume at +1.
        // F2: a speaker-level pause is ALSO mid-phase, like TASK — overridden below
        // rather than folded into the ternary above, so this does not have to name
        // whatever HitlPauseType a future mid-sequential-phase pause (I6) turns out
        // to use. A resumePoint's mere presence already means "mid-phase, re-enter
        // the exact phase it names," independent of pauseType.
        final int startFromPhase = gc.getResumePoint() != null
                ? gc.getResumePoint().phaseIdx()
                : (pauseType == HitlPauseType.TASK ? resumePhaseIndex : resumePhaseIndex + 1);

        // Saved bookmark fields for pause restoration on transient failures.
        // #35: restore with the ORIGINAL pausedAt so a re-armed timeout keeps its
        // due time (pausedAt + approvalTimeout) instead of shifting forward to the
        // resume-attempt instant.
        final Instant savedPausedAt = originalPausedAt != null ? originalPausedAt : Instant.now();
        final int savedPhaseIndex = resumePhaseIndex;
        final String savedPhaseName = pausedPhaseName;
        // F2: captured here (before the resume work runs) rather than re-read from
        // gc inside the lambda below — executeDiscussion clears it as soon as it is
        // consumed, and by the time a retry of the transient-failure branch below
        // re-reads gc, a first attempt may already have cleared it even though that
        // attempt never actually reached executeDiscussion's phase loop.
        final GroupConversation.ResumePoint savedResumePoint = gc.getResumePoint();
        final var savedPauseType = pauseType;

        // O2: the control token is registered right after the resume CAS above (not
        // here) so a cancel racing the window between the CAS and the executor thread
        // reaching executeDiscussion finds a signalable token and takes the SIGNAL
        // path, rather than falling through to the DB branch and being overwritten by
        // the resumed leg's unconditional updates.

        Runnable resumeWork = () -> {
            AgentGroupConfiguration groupConfig;
            List<DiscussionPhase> phases;
            try {
                IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
                if (currentGroupId == null) {
                    throw new IResourceStore.ResourceNotFoundException("Group not found.");
                }
                groupConfig = groupStore.read(groupId, currentGroupId.getVersion());
                phases = groupConversationService.resolvePhases(groupConfig);

                // Phase 5f: Config drift guard — verify the phase at the bookmark
                // still matches what was paused. If the config was edited while the
                // discussion was awaiting approval, the phase list may have shifted.
                // Compare against resumePhaseIndex (the bookmarked phase), not
                // startFromPhase (which is +1 for PHASE pauses). The bookmarked
                // phase MUST exist — if the list shrank below it, that is drift too
                // (a silently-skipped guard would complete a discussion whose gated
                // phases never ran).
                if (savedPhaseName != null) {
                    String actualPhase = savedPhaseIndex < phases.size()
                            ? phases.get(savedPhaseIndex).name()
                            : null;
                    if (!savedPhaseName.equals(actualPhase)) {
                        LOGGER.warnf("Config drift detected for GC %s: expected phase '%s' at index %d but found '%s'",
                                groupConversationId, savedPhaseName, savedPhaseIndex, actualPhase);
                        String driftMessage = "Resume aborted: group config changed while paused (expected phase '"
                                + savedPhaseName + "' at index " + savedPhaseIndex
                                + " but found " + (actualPhase != null ? "'" + actualPhase + "'" : "no phase at that index")
                                + ") — the discussion remains awaiting approval; fix the config and retry, or cancel";
                        gc.getTranscript().add(new TranscriptEntry(
                                "system", "System",
                                driftMessage,
                                savedPhaseIndex, actualPhase != null ? actualPhase : "n/a",
                                TranscriptEntryType.ERROR, Instant.now(), driftMessage, null));
                        // Restore the pause instead of destroying the approval: the
                        // operator can fix the config and approve again, or cancel.
                        restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, groupConfig,
                                savedTimeoutPolicy, savedApprovalTimeout);
                        // A cancel signalled in this window must win over the restore —
                        // remove-and-recheck so a signal racing the remove is not dropped.
                        removeTokenAndConvertIfSignalled(gc, listener);
                        if (listener != null) {
                            listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(driftMessage));
                        }
                        return;
                    }
                }

                // F2: speaker-level bookmark drift guard. A resumePoint targets a
                // specific speaker index within a phase's roster at pause time. If
                // the config changed while paused and that roster is now shorter
                // than or equal to the bookmarked index (members removed, or the
                // phase itself no longer exists), resuming would either skip past
                // the end of the sequential loop or address a stale index — restore
                // the pause instead, mirroring the phase-name drift branch above.
                // Independent of savedPhaseName: a resumePoint's own phaseIdx is the
                // authority for which phase's roster to check.
                if (savedResumePoint != null) {
                    List<GroupMember> currentSpeakers = savedResumePoint.phaseIdx() < phases.size()
                            // I7: the same roster the phase loop will use, recruits
                            // included — resolving against the config alone would
                            // measure a roster the resumed loop no longer has, and
                            // report config drift for a discussion that merely
                            // recruited someone before it paused.
                            ? groupConversationService.resolveParticipants(
                                    phases.get(savedResumePoint.phaseIdx()),
                                    groupConversationService.rosterWithRecruits(groupConfig, gc),
                                    groupConfig.getModeratorAgentId())
                            : List.of();
                    if (savedResumePoint.speakerIdx() >= currentSpeakers.size()) {
                        LOGGER.warnf("Config drift detected for GC %s: resume speaker index %d out of bounds for phase %d (roster size %d)",
                                groupConversationId, savedResumePoint.speakerIdx(), savedResumePoint.phaseIdx(), currentSpeakers.size());
                        String driftMessage = "Resume aborted: group config changed while paused (bookmarked speaker index "
                                + savedResumePoint.speakerIdx() + " no longer exists in phase " + savedResumePoint.phaseIdx()
                                + "'s roster of " + currentSpeakers.size()
                                + " speakers) — the discussion remains awaiting approval; fix the config and retry, or cancel";
                        gc.getTranscript().add(new TranscriptEntry(
                                "system", "System",
                                driftMessage,
                                savedResumePoint.phaseIdx(), savedPhaseName != null ? savedPhaseName : "n/a",
                                TranscriptEntryType.ERROR, Instant.now(), driftMessage, null));
                        restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, groupConfig,
                                savedTimeoutPolicy, savedApprovalTimeout);
                        removeTokenAndConvertIfSignalled(gc, listener);
                        if (listener != null) {
                            listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(driftMessage));
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to resume group discussion: " + groupConversationId, e);
                // Transient failure BEFORE executeDiscussion (store hiccup, config
                // unreadable): restore the pause instead of failing the discussion
                // terminally — symmetric with the regular surface.
                restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null,
                        savedTimeoutPolicy, savedApprovalTimeout);
                // A cancel signalled in this window must win over the restore —
                // remove-and-recheck so a signal racing the remove is not dropped.
                removeTokenAndConvertIfSignalled(gc, listener);
                if (listener != null) {
                    // Curated: never push the raw exception text to an SSE client.
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                            "Resume failed — the discussion remains awaiting approval; retry."));
                }
                return;
            }

            try {
                groupConversationService.executeDiscussion(gc, groupConfig, phases, question, listener, startFromPhase);
            } catch (Exception e) {
                // executeDiscussion already persisted the terminal state (FAILED or
                // CANCELLED) and fired the listener — do NOT restore the pause or
                // fire a second error event here.
                LOGGER.errorf(e, "Resumed group discussion %s failed", groupConversationId);
            }
        };
        try {
            executorService.submit(callerIdentityContext.withIdentity(callerIdentityContext.captureOrCurrent(), resumeWork));
        } catch (RuntimeException e) {
            // Executor saturated/shut down — no thread will run the resume. The CAS
            // above already consumed the pause; restore it so the approval remains
            // actionable instead of leaving an IN_PROGRESS zombie.
            restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null,
                    savedTimeoutPolicy, savedApprovalTimeout);
            // Remove-and-recheck like every other rollback path — a plain remove
            // here dropped a cancel signalled between token registration and this
            // rollback, leaving a "cancelled" discussion stuck AWAITING_APPROVAL.
            removeTokenAndConvertIfSignalled(gc, listener);
            throw new IResourceStore.ResourceStoreException(
                    "Failed to submit resumed group discussion: " + e.getMessage(), e);
        }

        // #35: only NOW — after the resume was actually enqueued — count the resume
        // and write the audit entry, so a rolled-back submit does not pollute the
        // metric or the compliance trail (mirrors the regular surface).
        counterGroupHitlResume.increment();
        auditHitlDecision(gc, decision);

        // The live gc instance is being mutated by the background thread — hand
        // the HTTP layer a freshly-read copy instead (CME-safe serialization).
        try {
            return conversationStore.read(groupConversationId);
        } catch (Exception e) {
            LOGGER.debugf("Could not re-read group conversation %s for the response: %s",
                    groupConversationId, e.getMessage());
            return gc;
        }
    }

    /**
     * Restores a consumed group pause after a failed resume: re-sets the bookmark
     * fields, CAS-flips IN_PROGRESS back to AWAITING_APPROVAL, and re-arms the
     * timeout schedule. The human decision is lost (it was never executed) but the
     * approval remains actionable — a transient failure must not terminally FAIL a
     * multi-agent discussion.
     */
    public void restoreGroupPause(GroupConversation gc, int phaseIndex, String phaseName,
                                  HitlPauseType pauseType, Instant pausedAt,
                                  AgentGroupConfiguration configOrNull,
                                  HitlTimeoutPolicy fallbackTimeoutPolicy, String fallbackApprovalTimeout) {
        try {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(pausedAt);
            gc.setPausedAtPhaseIndex(phaseIndex);
            gc.setPausedPhaseName(phaseName);
            gc.setHitlPauseType(pauseType != null ? pauseType : HitlPauseType.PHASE);
            gc.setHitlPauseReason("Pause restored after failed resume");
            // #29: resumeDiscussion already NULLED the in-memory timeout bookmark
            // before the CAS, so we must re-set it here or the restore persists a
            // disarmed policy. Prefer a fresh config read; if that fails, fall back
            // to the bookmark values captured BEFORE the clear — never leave null,
            // which parsePolicy treats as WAIT_INDEFINITELY (silently disarming a
            // finite AUTO_REJECT/ABORT policy).
            var config = configOrNull;
            if (config == null) {
                try {
                    var resId = groupStore.getCurrentResourceId(gc.getGroupId());
                    config = resId != null ? groupStore.read(gc.getGroupId(), resId.getVersion()) : null;
                } catch (Exception ignored) {
                    // fall through to the captured fallback below
                }
            }
            if (config != null && config.getHitlConfig() != null) {
                gc.setHitlTimeoutPolicy(config.getHitlConfig().getTimeoutPolicy() != null
                        ? config.getHitlConfig().getTimeoutPolicy()
                        : HitlTimeoutPolicy.WAIT_INDEFINITELY);
                gc.setHitlApprovalTimeout(config.getHitlConfig().getApprovalTimeout());
            } else {
                // Config unreadable — preserve the original bookmark so a finite
                // policy is not silently disarmed.
                gc.setHitlTimeoutPolicy(fallbackTimeoutPolicy);
                gc.setHitlApprovalTimeout(fallbackApprovalTimeout);
            }
            conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
            scheduleGroupHitlTimeout(gc);
            LOGGER.warnf("Group resume of %s failed — pause restored (AWAITING_APPROVAL)", gc.getId());
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to restore group pause after failed resume: %s", gc.getId());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry for a group HITL decision (#15,
     * EU AI Act). Covers human and automated timeout decisions.
     */
    private void auditHitlDecision(GroupConversation gc, HitlDecision decision) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled() || decision == null) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", decision.getVerdict() != null ? decision.getVerdict().name() : "UNKNOWN");
            detail.put("decidedBy", decision.getDecidedBy() != null ? decision.getDecidedBy() : "unknown");
            detail.put("automated", decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:"));
            detail.put("surface", "group");
            if (decision.getNote() != null) {
                detail.put("note", decision.getNote());
            }
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(),
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL audit entry for group conversation %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry when a pending approval is
     * cancelled — a human (or timeout policy) decided NOT to let the gated work
     * proceed, which is just as much an HITL decision as approve/reject.
     */
    public void auditHitlCancellation(GroupConversation gc, ControlSignal mode) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", "CANCELLED");
            detail.put("mode", mode != null ? mode.name() : ControlSignal.CANCEL_GRACEFUL.name());
            detail.put("surface", "group");
            detail.put("pauseReason", gc.getHitlPauseReason() != null ? gc.getHitlPauseReason() : "");
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(),
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL cancellation audit entry for group conversation %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Releases resources held across an HITL pause once the conversation reaches a
     * terminal state OUTSIDE the executeDiscussion finally block (cancel-of-paused,
     * REJECTED resume). executeDiscussion skips cleanup while AWAITING_APPROVAL —
     * without this, ephemeral dynamic agents stay deployed and the signing guard's
     * verification cursor (see {@link GroupSigningGuard#forgetConversation}) leaks
     * forever on every paused-then-terminal path.
     */
    public void cleanupAfterTerminalState(GroupConversation gc) {
        signingGuard.forgetConversation(gc.getId());
        try {
            IResourceStore.IResourceId resId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (resId == null) {
                LOGGER.warnf("Terminal cleanup: group config %s not found — ephemeral agents of GC %s not cleaned",
                        gc.getGroupId(), gc.getId());
                return;
            }
            var config = groupStore.read(gc.getGroupId(), resId.getVersion());
            groupConversationService.cleanupEphemeralAgents(gc, config);
        } catch (Exception e) {
            LOGGER.warnf("Terminal cleanup failed for group conversation %s: %s", gc.getId(), e.getMessage());
        }
    }

    /**
     * Deletes any existing HITL timeout schedule for the given group conversation.
     * Called on resume and cancel to prevent stale fires.
     */
    public void deleteGroupHitlTimeoutSchedule(String groupConversationId) {
        try {
            int deleted = scheduleStore.deleteSchedulesByName(
                    HitlSchedules.groupTimeoutScheduleName(groupConversationId));
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d group HITL timeout schedule(s) for %s", deleted, groupConversationId);
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete group HITL timeout schedule for %s: %s",
                    groupConversationId, e.getMessage());
        }
    }
}
