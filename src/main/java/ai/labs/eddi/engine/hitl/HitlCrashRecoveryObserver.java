/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Startup observer that REPAIRS HITL state after a crash or restart — it never
 * destroys a legitimately paused conversation.
 *
 * <p>
 * Recovery actions:
 * <ul>
 * <li><b>Paused conversations with a finite timeout policy</b>
 * (AUTO_APPROVE/AUTO_REJECT/ABORT): the one-shot timeout schedule may have been
 * lost in a crash window. The schedule is idempotently re-armed at the original
 * due time ({@code pausedAt + approvalTimeout}), or shortly after startup if
 * that time has already passed — the configured policy then applies through the
 * normal {@code HitlTimeoutHandler} machinery.</li>
 * <li><b>Paused conversations with WAIT_INDEFINITELY (or no) policy</b>: left
 * untouched. Waiting is exactly what the designer asked for.</li>
 * <li><b>Regular conversations stuck IN_PROGRESS with an intact HITL
 * bookmark</b>: a pod died between the resume CAS and the resume execution. The
 * pause is restored (IN_PROGRESS → AWAITING_HUMAN via CAS) so the approval can
 * be re-issued, and the timeout schedule is re-armed.</li>
 * <li><b>Regular conversations stuck IN_PROGRESS without a bookmark</b>:
 * transitioned to EXECUTION_INTERRUPTED via CAS (unlocks say()).</li>
 * </ul>
 *
 * <p>
 * Group discussions that are IN_PROGRESS are never touched — unlike the regular
 * surface, a group discussion legitimately persists IN_PROGRESS for its whole
 * (potentially long) run, so a crashed leg cannot be distinguished from an
 * active one on another pod.
 *
 * <p>
 * <b>Cluster caveat:</b> during a rolling restart, another pod may be actively
 * resuming a conversation this observer sees as stuck IN_PROGRESS. The restore
 * is CAS-guarded and the active resume's final store wins, but a brief window
 * exists in which a second resume could be accepted. Set
 * {@code eddi.hitl.crash-recovery.recover-in-progress=false} in multi-pod
 * deployments if this risk is unacceptable.
 *
 * <p>
 * Config: {@code eddi.hitl.crash-recovery.enabled} (default {@code true}),
 * {@code eddi.hitl.crash-recovery.recover-in-progress} (default {@code true}).
 *
 * @since 6.0.2
 */
@ApplicationScoped
public class HitlCrashRecoveryObserver {

    private static final Logger LOGGER = Logger.getLogger("ai.labs.eddi.HITL_RECOVERY");

    /** Minimum delay before a re-armed, already-overdue timeout fires. */
    private static final Duration REARM_GRACE = Duration.ofMinutes(2);

    private final IConversationMemoryStore conversationMemoryStore;
    private final IGroupConversationStore groupConversationStore;
    private final IScheduleStore scheduleStore;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final boolean enabled;
    private final boolean recoverInProgress;
    private final Optional<Duration> pendingMaxAge;

    @Inject
    public HitlCrashRecoveryObserver(
            IConversationMemoryStore conversationMemoryStore,
            IGroupConversationStore groupConversationStore,
            IScheduleStore scheduleStore,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.enabled",
                            defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.recover-in-progress",
                            defaultValue = "true") boolean recoverInProgress,
            @ConfigProperty(name = "eddi.hitl.pending.max-age") Optional<Duration> pendingMaxAge) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.groupConversationStore = groupConversationStore;
        this.scheduleStore = scheduleStore;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.enabled = enabled;
        this.recoverInProgress = recoverInProgress;
        this.pendingMaxAge = pendingMaxAge;
    }

    /** Upper bound of paused conversations scanned per recovery pass. */
    private static final int RECOVERY_SCAN_LIMIT = 10_000;

    /** Upper bound of paused conversations scanned per retention sweep. */
    private static final int RETENTION_SCAN_LIMIT = 10_000;

    // 'event' is the required CDI observer trigger — the method fires on
    // StartupEvent regardless of whether the payload is read.
    @SuppressWarnings("unused")
    void onStartup(@Observes StartupEvent event) {
        if (!enabled) {
            LOGGER.info("HITL crash recovery disabled via config.");
            return;
        }
        // Run OFF the boot path: a deployment with many paused conversations must
        // not block application readiness on the repair sweep.
        Thread.ofVirtual().name("hitl-crash-recovery").start(this::runRecovery);
    }

    /** Package-private for tests — runs one full recovery pass synchronously. */
    void runRecovery() {
        LOGGER.info("HITL crash recovery running...");
        int rearmedRegular = repairRegularPaused();
        int recoveredInProgress = recoverRegularInProgress();
        int rearmedGroup = repairGroupPaused();

        if (rearmedRegular > 0 || recoveredInProgress > 0 || rearmedGroup > 0) {
            LOGGER.warnf("HITL crash recovery: re-armed %d regular + %d group timeout schedule(s), "
                    + "recovered %d stuck IN_PROGRESS conversation(s).",
                    rearmedRegular, rearmedGroup, recoveredInProgress);
        } else {
            LOGGER.info("HITL crash recovery: nothing to repair.");
        }
    }

    /**
     * Optional retention sweep for abandoned pending approvals. Under the default
     * WAIT_INDEFINITELY policy a pause never expires, so forgotten approvals
     * accumulate without bound. Set {@code eddi.hitl.pending.max-age} (an ISO-8601
     * duration, e.g. {@code P30D}) to auto-cancel pauses older than the threshold.
     * OFF by default (property empty): no pause is ever cancelled by this sweep,
     * preserving the WAIT_INDEFINITELY contract for deployments that want it.
     * <p>
     * Cancellation goes through {@link IConversationService#cancelConversation}
     * (regular surface) and {@link IGroupConversationService#cancelDiscussion}
     * (group surface), so each reaped pause is audited (EU AI Act) and its timeout
     * schedule disarmed — never a raw state write. Both surfaces are swept against
     * the same cutoff; group discussions paused under WAIT_INDEFINITELY would
     * otherwise accumulate forever just like regular pauses.
     * <p>
     * The property is read once at startup. See {@code docs/hitl.md} (owned by a
     * later phase) for the operator-facing description.
     */
    @Scheduled(every = "${eddi.hitl.pending.sweep-interval:6h}", delayed = "5m", identity = "hitl-pending-retention")
    void sweepExpiredPendingApprovals() {
        if (!enabled || pendingMaxAge.isEmpty()) {
            return; // retention disabled — the default
        }
        Duration maxAge = pendingMaxAge.get();
        if (maxAge.isZero() || maxAge.isNegative()) {
            return; // non-positive age is treated as OFF
        }
        Instant cutoff = Instant.now().minus(maxAge);
        int cancelled = 0;
        try {
            var summaries = conversationMemoryStore.findPendingApprovalSummaries(RETENTION_SCAN_LIMIT);
            for (var summary : summaries) {
                Instant pausedAt = summary.getPausedAt();
                if (pausedAt == null || pausedAt.isAfter(cutoff)) {
                    continue; // unknown age or still within retention
                }
                String conversationId = summary.getConversationId();
                try {
                    // cancelConversation is CAS-guarded: it no-ops if the pause was
                    // resumed/cancelled between the scan and here. G6: attribute the
                    // retention-driven cancellation with a system actor so the audit
                    // records decidedBy=system:retention (automated), not "unknown".
                    var outcome = conversationService.cancelConversation(conversationId, ControlSignal.CANCEL_GRACEFUL, "system:retention");
                    if (outcome == IConversationService.CancelOutcome.CANCELLED) {
                        cancelled++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to auto-cancel expired pending approval %s: %s", conversationId, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Error during HITL pending-approval retention sweep: %s", e.getMessage());
        }

        // Group pass: WAIT_INDEFINITELY group discussions paused AWAITING_APPROVAL
        // are never swept by the regular loop above and would accumulate forever.
        // Same cutoff, gated by the same max-age-configured condition (reached only
        // when retention is enabled and positive).
        int cancelledGroups = 0;
        try {
            var pausedGcs = groupConversationStore.findByState(
                    GroupConversationState.AWAITING_APPROVAL, null, RETENTION_SCAN_LIMIT);
            for (GroupConversation gc : pausedGcs) {
                Instant pausedAt = gc.getPausedAt();
                if (pausedAt == null || !pausedAt.isBefore(cutoff)) {
                    continue; // unknown age or still within retention
                }
                String groupConversationId = gc.getId();
                try {
                    // cancelDiscussion is CAS/terminal-guarded: it no-ops if the
                    // discussion was resumed/cancelled between the scan and here.
                    if (groupConversationService.cancelDiscussion(groupConversationId, ControlSignal.CANCEL_GRACEFUL)) {
                        cancelledGroups++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to auto-cancel expired group pending approval %s: %s",
                            groupConversationId, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Error during HITL group pending-approval retention sweep: %s", e.getMessage());
        }

        if (cancelled > 0 || cancelledGroups > 0) {
            LOGGER.warnf("HITL pending-approval retention: auto-cancelled %d regular pause(s) + "
                    + "%d group discussion(s) older than %s", cancelled, cancelledGroups, maxAge);
        }
    }

    private int repairRegularPaused() {
        try {
            // Projected summaries — policy, timeout, pausedAt, and agentId come
            // from the bounded projection query; the (potentially multi-MB) full
            // documents are never loaded, and WAIT_INDEFINITELY pauses are skipped
            // without any further read.
            var summaries = conversationMemoryStore.findPendingApprovalSummaries(RECOVERY_SCAN_LIMIT);
            if (summaries.size() >= RECOVERY_SCAN_LIMIT) {
                LOGGER.warnf("HITL crash recovery: paused-conversation scan hit the %d bound — "
                        + "some pauses may not have been checked this pass", RECOVERY_SCAN_LIMIT);
            }
            int count = 0;
            for (var summary : summaries) {
                String conversationId = summary.getConversationId();
                try {
                    HitlTimeoutPolicy policy = parsePolicy(summary.getTimeoutPolicy());
                    if (policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                        continue; // legitimate indefinite pause — never touch
                    }
                    // Capture the SCANNED pause timestamp: the post-create re-check
                    // must keep the schedule only for THIS pause, not a different one
                    // created after an intervening resume+re-pause.
                    Instant scannedPausedAt = summary.getPausedAt();
                    if (rearmSchedule(HitlSchedules.regularTimeoutScheduleName(conversationId),
                            HitlSchedules.SURFACE_REGULAR, conversationId,
                            summary.getAgentId(), policy, summary.getApprovalTimeout(),
                            scannedPausedAt,
                            () -> regularStillPaused(conversationId, scannedPausedAt))) {
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to repair paused conversation %s: %s", conversationId, e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during regular pause repair: %s", e.getMessage());
            return 0;
        }
    }

    private int recoverRegularInProgress() {
        if (!recoverInProgress) {
            return 0;
        }
        try {
            List<String> inProgressIds = conversationMemoryStore
                    .findConversationIdsByState(ConversationState.IN_PROGRESS);
            int count = 0;
            for (String conversationId : inProgressIds) {
                try {
                    var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
                    if (snapshot == null)
                        continue;

                    if (snapshot.getHitlPausedAt() != null) {
                        // Intact bookmark: a resume was accepted (CAS flipped the
                        // state) but never completed — restore the pause.
                        if (conversationMemoryStore.compareAndSetState(conversationId,
                                ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN)) {
                            LOGGER.warnf("Restored crashed resume to AWAITING_HUMAN: %s (paused at %s)",
                                    conversationId, snapshot.getHitlPausedAt());
                            count++;
                            HitlTimeoutPolicy policy = snapshot.getHitlTimeoutPolicy() != null
                                    ? snapshot.getHitlTimeoutPolicy()
                                    : HitlTimeoutPolicy.WAIT_INDEFINITELY;
                            if (policy != HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                                Instant scannedPausedAt = snapshot.getHitlPausedAt();
                                rearmSchedule(HitlSchedules.regularTimeoutScheduleName(conversationId),
                                        HitlSchedules.SURFACE_REGULAR, conversationId,
                                        snapshot.getAgentId(), policy, snapshot.getHitlApprovalTimeout(),
                                        scannedPausedAt,
                                        () -> regularStillPaused(conversationId, scannedPausedAt));
                            }
                        }
                    } else {
                        // No bookmark: unknown interrupted execution — unlock say().
                        if (conversationMemoryStore.compareAndSetState(conversationId,
                                ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED)) {
                            LOGGER.warnf("Recovered stuck IN_PROGRESS conversation to EXECUTION_INTERRUPTED: %s",
                                    conversationId);
                            count++;
                            // Defensive: hitlPendingToolCalls and hitlPausedAt normally commit in the
                            // SAME document write (pauseConversation sets both), so a batch surviving
                            // without a bookmark should be impossible. But a crash landing exactly
                            // between the gate trip and the pause commit could theoretically leave an
                            // orphaned batch on a conversation that is now neither AWAITING_HUMAN nor
                            // IN_PROGRESS (it just moved to EXECUTION_INTERRUPTED above) — clear it so
                            // it can never poison a future resume's mode detection.
                            if (snapshot.getHitlPendingToolCalls() != null) {
                                LOGGER.warnf("Orphaned hitlPendingToolCalls batch found on non-paused "
                                        + "conversation %s (state now EXECUTION_INTERRUPTED, no hitlPausedAt "
                                        + "bookmark) — clearing stale tool-pause state", conversationId);
                                try {
                                    conversationMemoryStore.clearHitlBookmark(conversationId);
                                } catch (Exception e) {
                                    LOGGER.warnf("Failed to clear orphaned tool-pause state for %s: %s",
                                            conversationId, e.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to recover IN_PROGRESS conversation %s: %s", conversationId, e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during IN_PROGRESS recovery: %s", e.getMessage());
            return 0;
        }
    }

    private int repairGroupPaused() {
        try {
            List<GroupConversation> pausedGcs = groupConversationStore.findByState(GroupConversationState.AWAITING_APPROVAL);
            int count = 0;
            for (GroupConversation gc : pausedGcs) {
                try {
                    // Both HITL bookmarks now store the policy as a typed enum (nulled
                    // only while resumed), so mirror parsePolicy's null → WAIT_INDEFINITELY
                    // default inline. parsePolicy(String) survives only for the
                    // PendingApprovalSummary projection scan above, whose timeoutPolicy is
                    // still a String.
                    HitlTimeoutPolicy policy = gc.getHitlTimeoutPolicy() != null
                            ? gc.getHitlTimeoutPolicy()
                            : HitlTimeoutPolicy.WAIT_INDEFINITELY;
                    if (policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                        continue;
                    }
                    String groupConversationId = gc.getId();
                    Instant scannedPausedAt = gc.getPausedAt();
                    if (rearmSchedule(HitlSchedules.groupTimeoutScheduleName(groupConversationId),
                            HitlSchedules.SURFACE_GROUP, groupConversationId,
                            null, policy, gc.getHitlApprovalTimeout(), scannedPausedAt,
                            () -> groupStillPaused(groupConversationId, scannedPausedAt))) {
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to repair paused group conversation %s: %s", gc.getId(), e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during group pause repair: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * Idempotently replaces the one-shot HITL timeout schedule: deletes any
     * existing schedule of that name and creates a fresh one at the original due
     * time (pausedAt + timeout), or {@link #REARM_GRACE} after startup if the due
     * time already passed. Safe against both lost and still-intact schedules.
     *
     * @param stillPaused
     *            re-checked AFTER the schedule is created — a resume/cancel may
     *            land between the sweep read and the create; the freshly created
     *            schedule is withdrawn if the pause is gone
     * @return true if a schedule was (re-)created and kept
     */
    private boolean rearmSchedule(String scheduleName, String surface, String conversationId,
                                  String agentId, HitlTimeoutPolicy policy, String approvalTimeout,
                                  Instant pausedAt, java.util.function.BooleanSupplier stillPaused) {
        if (approvalTimeout == null || approvalTimeout.isBlank() || pausedAt == null) {
            LOGGER.warnf("Cannot re-arm HITL timeout for %s: missing approvalTimeout/pausedAt in bookmark",
                    conversationId);
            return false;
        }
        Duration timeout;
        try {
            timeout = Duration.parse(approvalTimeout);
        } catch (Exception e) {
            LOGGER.warnf("Cannot re-arm HITL timeout for %s: unparseable approvalTimeout '%s'",
                    conversationId, approvalTimeout);
            return false;
        }

        Instant earliest = Instant.now().plus(REARM_GRACE);
        Instant fireAt = pausedAt.plus(timeout);
        if (fireAt.isBefore(earliest)) {
            fireAt = earliest;
        }

        try {
            scheduleStore.deleteSchedulesByName(scheduleName);

            var schedule = new ScheduleConfiguration();
            schedule.setName(scheduleName);
            schedule.setAgentId(agentId);
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setEnabled(true);
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    HitlSchedules.METADATA_TYPE_KEY, HitlSchedules.METADATA_TYPE_TIMEOUT,
                    HitlSchedules.METADATA_POLICY_KEY, policy.name(),
                    HitlSchedules.METADATA_SURFACE_KEY, surface,
                    HitlSchedules.METADATA_CONVERSATION_ID_KEY, conversationId));
            scheduleStore.createSchedule(schedule);

            // Re-check AFTER creating: a resume/cancel landing between the sweep
            // read and the create would otherwise leave an armed timeout on a
            // conversation that is no longer paused.
            try {
                if (stillPaused != null && !stillPaused.getAsBoolean()) {
                    scheduleStore.deleteSchedulesByName(scheduleName);
                    LOGGER.infof("Withdrew re-armed HITL timeout for %s — no longer paused", conversationId);
                    return false;
                }
            } catch (Exception e) {
                // Cannot verify — keep the schedule; the timeout handler's
                // resume/cancel is CAS-guarded and no-ops on a non-paused state.
                LOGGER.debugf("Post-rearm state re-check failed for %s: %s (keeping schedule)",
                        conversationId, e.getMessage());
            }

            LOGGER.infof("Re-armed HITL timeout for %s (%s) at %s (policy: %s)",
                    conversationId, surface, fireAt, policy);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Failed to re-arm HITL timeout for %s: %s", conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Post-create bookmark re-check for a regular pause. The re-armed schedule is
     * kept only if the conversation is STILL AWAITING_HUMAN <em>and</em> its
     * current pause is the very one the sweep scanned — a resume+re-pause between
     * the scan and now produces a NEW pausedAt, and the schedule computed from the
     * OLD pausedAt/policy must NOT survive onto that new approval. A transient read
     * error keeps the schedule (the timeout handler is CAS-guarded anyway).
     */
    private boolean regularStillPaused(String conversationId, Instant scannedPausedAt) {
        try {
            var current = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (current == null || current.getConversationState() != ConversationState.AWAITING_HUMAN) {
                return false;
            }
            if (scannedPausedAt == null) {
                return true; // scan had no bookmark timestamp — fall back to state-only
            }
            return scannedPausedAt.equals(current.getHitlPausedAt());
        } catch (Exception e) {
            return true; // cannot verify — keep the schedule
        }
    }

    /**
     * Post-create bookmark re-check for a group pause — see
     * {@link #regularStillPaused}. Kept only if the discussion is STILL
     * AWAITING_APPROVAL and its current pausedAt equals the scanned one.
     */
    private boolean groupStillPaused(String groupConversationId, Instant scannedPausedAt) {
        try {
            GroupConversation current = groupConversationStore.read(groupConversationId);
            if (current == null || current.getState() != GroupConversationState.AWAITING_APPROVAL) {
                return false;
            }
            if (scannedPausedAt == null) {
                return true; // scan had no pausedAt — fall back to state-only
            }
            return scannedPausedAt.equals(current.getPausedAt());
        } catch (Exception e) {
            return true; // cannot verify — keep the schedule
        }
    }

    /**
     * Unknown/absent policy strings are treated as WAIT_INDEFINITELY (never
     * destructive).
     */
    private static HitlTimeoutPolicy parsePolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }
        try {
            return HitlTimeoutPolicy.valueOf(policy);
        } catch (IllegalArgumentException e) {
            return HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }
    }
}
