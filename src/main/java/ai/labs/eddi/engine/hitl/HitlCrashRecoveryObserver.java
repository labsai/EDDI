/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Startup observer that REPAIRS HITL state after a crash or restart — it never
 * destroys a legitimately paused conversation.
 *
 * <p>
 * Recovery actions:
 * <ul>
 * <li><b>Paused conversations with a finite timeout policy</b>
 * (AUTO_APPROVE/AUTO_REJECT/ABORT): the one-shot timeout schedule may have been
 * lost in a crash window. A missing schedule is re-created at the original due
 * time ({@code pausedAt + approvalTimeout}), or shortly after startup if that
 * time has already passed — the configured policy then applies through the
 * normal {@code HitlTimeoutHandler} machinery. A schedule that is still armed
 * is left untouched.</li>
 * <li><b>Paused conversations with WAIT_INDEFINITELY (or no) policy</b>: left
 * untouched. Waiting is exactly what the designer asked for.</li>
 * <li><b>Regular conversations stuck IN_PROGRESS with an intact HITL
 * bookmark</b>: a pod died between the resume CAS and the resume execution. The
 * pause is restored (IN_PROGRESS → AWAITING_HUMAN via CAS) so the approval can
 * be re-issued, and the timeout schedule is re-armed.</li>
 * <li><b>Regular conversations stuck IN_PROGRESS without a bookmark</b>:
 * transitioned to EXECUTION_INTERRUPTED via CAS (unlocks say()).</li>
 * <li><b>Group discussions left IN_PROGRESS or SYNTHESIZING by a crash</b>:
 * cancelled. A group discussion persists a running state for its whole
 * (potentially long) run, so the state alone does not tell a dead leg from a
 * live one; the age does. On a single node every leg this process runs was
 * started or resumed after the JVM started, and each of those transitions
 * stamps {@code lastModified}, so a running discussion last written before the
 * process started belongs to a leg that died with the previous process. Nothing
 * resumes it, and continue/follow-up need COMPLETED, so it used to stay stuck
 * until someone cancelled it by hand.</li>
 * </ul>
 *
 * <p>
 * <b>Cluster mode:</b> with {@code eddi.messaging.type=nats} this observer runs
 * no startup pass; {@code ClusterHitlRecovery} sweeps periodically under leader
 * election instead, and does not sweep running group discussions (a group holds
 * no conversation lease that could prove its leg dead). The caveat below
 * applies to a startup pass only.
 *
 * <p>
 * <b>Rolling-restart caveat:</b> during a rolling restart, another pod may be
 * actively resuming a conversation this observer sees as stuck IN_PROGRESS. The
 * restore is CAS-guarded and the active resume's final store wins, but a brief
 * window exists in which a second resume could be accepted. Worse for groups: a
 * discussion another pod has been running since before this pod started looks
 * interrupted, and is cancelled. Set
 * {@code eddi.hitl.crash-recovery.recover-in-progress=false} in multi-pod
 * deployments without NATS; it turns off both IN_PROGRESS recoveries.
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

    /**
     * Startup pass: minimum delay before a re-armed, already-overdue timeout fires,
     * so a node coming up does not decide pauses before it is ready.
     */
    private static final Duration REARM_GRACE = Duration.ofMinutes(2);

    /**
     * Cluster sweep: an overdue pause whose schedule is missing fires on the next
     * poll. The sweep runs on a live leader, so there is nothing to wait for — and
     * a grace applied on every sweep is what kept pushing deadlines out (C1).
     */
    private static final Duration CLUSTER_REARM_GRACE = Duration.ZERO;

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

    /**
     * The states a group leg persists while it runs — what a crash leaves behind.
     */
    private static final List<GroupConversationState> RUNNING_GROUP_STATES = List.of(
            GroupConversationState.IN_PROGRESS, GroupConversationState.SYNTHESIZING);

    /** Upper bound of paused conversations scanned per retention sweep. */
    private static final int RETENTION_SCAN_LIMIT = 10_000;

    /**
     * Cluster mode switch: when NATS is the messaging type no recovery runs at
     * startup. Every rolling update starts a node, and recovering on boot parked
     * the LIVE turns other nodes were running (their conversations are IN_PROGRESS
     * too). The leader-elected periodic sweep in {@code ClusterHitlRecovery}
     * recovers instead, and only conversations that hold no lease and stayed
     * unchanged for {@code eddi.cluster.hitl-recovery.min-age}. Field-injected;
     * null in tests built with {@code new}.
     */
    @Inject
    ClusterConfig clusterConfig;

    // 'event' is the required CDI observer trigger — the method fires on
    // StartupEvent regardless of whether the payload is read.
    @SuppressWarnings("unused")
    void onStartup(@Observes StartupEvent event) {
        if (!enabled) {
            LOGGER.info("HITL crash recovery disabled via config.");
            return;
        }
        if (clusterConfig != null && clusterConfig.isNats()) {
            LOGGER.info("HITL crash recovery: cluster mode — no startup pass; the elected leader sweeps periodically.");
            return;
        }
        // Run OFF the boot path: a deployment with many paused conversations must
        // not block application readiness on the repair sweep.
        Thread.ofVirtual().name("hitl-crash-recovery").start(this::runRecovery);
    }

    /** Runs one full recovery pass synchronously, against this JVM's start. */
    void runRecovery() {
        runRecovery(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    }

    /**
     * Package-private for tests — one full recovery pass, synchronously.
     *
     * @param processStart
     *            when this process started: a running group discussion last written
     *            before it belongs to a leg that died with an earlier process
     */
    void runRecovery(Instant processStart) {
        LOGGER.info("HITL crash recovery running...");
        int rearmedRegular = repairRegularPaused(REARM_GRACE);
        int recoveredInProgress = recoverRegularInProgress();
        // Two independent sweeps: a failing AWAITING_APPROVAL query must not
        // silently skip the AWAITING_HUMAN_INPUT re-arm (I6), or vice versa.
        int rearmedGroup = repairGroupPaused(REARM_GRACE) + repairGroupHumanPaused(REARM_GRACE);
        int cancelledGroups = cancelInterruptedGroups(processStart);

        if (rearmedRegular > 0 || recoveredInProgress > 0 || rearmedGroup > 0 || cancelledGroups > 0) {
            LOGGER.warnf("HITL crash recovery: re-armed %d regular + %d group timeout schedule(s), "
                    + "recovered %d stuck IN_PROGRESS conversation(s), "
                    + "cancelled %d group discussion(s) interrupted by the restart.",
                    rearmedRegular, rearmedGroup, recoveredInProgress, cancelledGroups);
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
     * The property is read once at startup. See {@code docs/hitl.md} for the
     * operator-facing description.
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
                    // The paused-only cancel, bound to the pause the scan saw: if it was
                    // resumed between the scan and here, the resumed turn must not be
                    // signalled to stop (the full cancel would), and a newer pause is not
                    // the expired one. G6: attribute the retention-driven cancellation
                    // with a system actor so the audit records decidedBy=system:retention
                    // (automated), not "unknown".
                    var outcome = conversationService.cancelPausedConversation(conversationId, "system:retention",
                            HitlDecision.pauseIdOf(pausedAt));
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
                    // Paused-only and bound to the pause this scan found expired: an
                    // approval landing in between resumes the discussion, and a plain
                    // cancelDiscussion would then stop the running work (or end a
                    // later, fresh pause) instead of no-opping.
                    if (groupConversationService.cancelPausedDiscussion(groupConversationId, ControlSignal.CANCEL_GRACEFUL,
                            HitlDecision.pauseIdOf(pausedAt))) {
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

    private int repairRegularPaused(Duration overdueGrace) {
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
                            summary.getAgentId(), policy.name(), summary.getApprovalTimeout(),
                            scannedPausedAt, overdueGrace, false,
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
        return recoverRegularInProgress(id -> true);
    }

    /**
     * Cluster mode: one pass of the leader's sweep — re-arm lost timeout schedules
     * and recover the stuck IN_PROGRESS conversations {@code eligible} accepts.
     * <p>
     * Runs every {@code eddi.cluster.hitl-recovery.interval}, so the re-arm must be
     * a no-op for a pause whose timeout is still armed: it only creates a schedule
     * that is missing (see {@link #rearmSchedule}). Replacing it on every pass
     * pushed any deadline within the grace window out again each time, and no
     * finite timeout ever fired in a cluster (C1).
     *
     * @return the number of conversations repaired or recovered
     */
    public int runClusterRecovery(Predicate<String> eligible) {
        if (!enabled) {
            return 0;
        }
        int repaired = repairRegularPaused(CLUSTER_REARM_GRACE) + repairGroupPaused(CLUSTER_REARM_GRACE)
                + repairGroupHumanPaused(CLUSTER_REARM_GRACE);
        int recovered = recoverRegularInProgress(eligible);
        if (repaired > 0 || recovered > 0) {
            LOGGER.warnf("HITL cluster recovery: re-armed %d schedule(s), recovered %d stuck IN_PROGRESS conversation(s).", repaired,
                    recovered);
        }
        return repaired + recovered;
    }

    private int recoverRegularInProgress(Predicate<String> eligible) {
        if (!recoverInProgress) {
            return 0;
        }
        try {
            List<String> inProgressIds = conversationMemoryStore
                    .findConversationIdsByState(ConversationState.IN_PROGRESS);
            int count = 0;
            for (String conversationId : inProgressIds) {
                if (!eligible.test(conversationId)) {
                    continue;
                }
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
                                // Replace whatever is there: this pause was just restored, so
                                // a row its earlier timeout fire left behind (the fire that
                                // started the lost resume) is not a timeout still to come.
                                rearmSchedule(HitlSchedules.regularTimeoutScheduleName(conversationId),
                                        HitlSchedules.SURFACE_REGULAR, conversationId,
                                        snapshot.getAgentId(), policy.name(), snapshot.getHitlApprovalTimeout(),
                                        scannedPausedAt, REARM_GRACE, true,
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

    /**
     * Cancels the group discussions a crash left running (see the class Javadoc for
     * why a write older than the process start marks a dead leg). Gated like the
     * regular IN_PROGRESS recovery, and the startup pass only:
     * {@link #runClusterRecovery} never calls it.
     * <p>
     * Through {@link IGroupConversationService#cancelDiscussion}, the path a manual
     * cancel of a stored discussion takes: a state CAS (a writer that moved the
     * discussion on since the scan wins), and the HITL timeout schedule removed. A
     * standing team's next cadence fire then sees CANCELLED and returns the pulled
     * backlog tasks to PENDING. Cancelled, not restored: unlike a regular turn, a
     * resumed group leg keeps no bookmark to restore — the approve CAS clears it.
     * Each state is scanned on its own, so a failing query skips only that state.
     */
    private int cancelInterruptedGroups(Instant processStart) {
        if (!recoverInProgress) {
            return 0;
        }
        int count = 0;
        for (GroupConversationState state : RUNNING_GROUP_STATES) {
            try {
                var running = groupConversationStore.findByState(state, null, RECOVERY_SCAN_LIMIT);
                for (GroupConversation gc : running) {
                    Instant lastModified = gc.getLastModified();
                    if (lastModified == null || !lastModified.isBefore(processStart)) {
                        continue; // unknown age, or a leg this process started
                    }
                    String groupConversationId = gc.getId();
                    try {
                        if (groupConversationService.cancelDiscussion(groupConversationId, ControlSignal.CANCEL_GRACEFUL)) {
                            LOGGER.warnf("Cancelled group discussion %s interrupted by a restart (%s, last written %s)",
                                    groupConversationId, state, lastModified);
                            count++;
                        }
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to cancel interrupted group discussion %s: %s",
                                groupConversationId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf("Error during interrupted group discussion recovery (%s): %s", state, e.getMessage());
            }
        }
        return count;
    }

    private int repairGroupPaused(Duration overdueGrace) {
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
                            null, policy.name(), gc.getHitlApprovalTimeout(), scannedPausedAt, overdueGrace, false,
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
     * I6: re-arms the turn timeout of discussions waiting on a HUMAN member. The
     * SKIP_TURN/ABORT policy is bookmarked on the pending record (it is not a
     * {@code HitlTimeoutPolicy}), the duration on the shared bookmark field, and
     * the schedule's surface routes the fire to the human-turn handler.
     */
    private int repairGroupHumanPaused(Duration overdueGrace) {
        try {
            List<GroupConversation> pausedGcs = groupConversationStore.findByState(GroupConversationState.AWAITING_HUMAN_INPUT);
            int count = 0;
            for (GroupConversation gc : pausedGcs) {
                try {
                    var pending = gc.getPendingHumanInput();
                    if (pending == null || gc.getHitlApprovalTimeout() == null || gc.getHitlApprovalTimeout().isBlank()) {
                        continue; // wait-indefinitely turn, or a half-written pause — nothing to arm
                    }
                    String groupConversationId = gc.getId();
                    Instant scannedPausedAt = gc.getPausedAt();
                    if (rearmSchedule(HitlSchedules.groupTimeoutScheduleName(groupConversationId),
                            HitlSchedules.SURFACE_GROUP_HUMAN, groupConversationId,
                            null, pending.onTimeout() != null ? pending.onTimeout() : "SKIP_TURN",
                            gc.getHitlApprovalTimeout(), scannedPausedAt, overdueGrace, false,
                            () -> groupStillHumanPaused(groupConversationId, scannedPausedAt))) {
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to repair human-paused group conversation %s: %s", gc.getId(), e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during group human-pause repair: %s", e.getMessage());
            return 0;
        }
    }

    /** {@code groupStillPaused}'s I6 twin — same pause-identity re-check. */
    private boolean groupStillHumanPaused(String groupConversationId, Instant scannedPausedAt) {
        try {
            var current = groupConversationStore.read(groupConversationId);
            if (current == null || current.getState() != GroupConversationState.AWAITING_HUMAN_INPUT) {
                return false;
            }
            if (scannedPausedAt == null) {
                return true;
            }
            return scannedPausedAt.equals(current.getPausedAt());
        } catch (Exception e) {
            LOGGER.debugf("Human-pause re-check failed for %s: %s", groupConversationId, e.getMessage());
            return true; // keep the schedule; the handler no-ops on a non-paused state
        }
    }

    /** What the schedule store holds for the timeout of one pause. */
    enum TimeoutArming {
        /**
         * A row of this pause that can still fire exists — leave it exactly as it is.
         */
        ARMED,
        /** A row of this pause was switched off by a person — respect that. */
        DISABLED,
        /**
         * Nothing of this pause can fire, but its timeout already fired and completed
         * while the pause still waits — re-armed after {@link #SPENT_REARM_BACKOFF}.
         */
        SPENT,
        /**
         * Nothing of this pause can fire: no row, rows of earlier pauses only, rows
         * that can never fire again (dead-lettered, out of retries, disabled by an
         * undeploy), or rows that could not be read — the schedule is (re-)created.
         */
        MISSING
    }

    /**
     * How long after a completed fire a pause that is still waiting gets its
     * timeout again. The fire handler completes only when the decision was applied
     * or has nothing left to apply to, and ends the pause itself when a decision
     * can never apply; this is the safety net for anything else (a row whose
     * metadata names no usable policy, say) — slow enough that a decision which
     * keeps failing is not retried in a loop.
     */
    static final Duration SPENT_REARM_BACKOFF = Duration.ofHours(1);

    /** Counter: a spent timeout re-armed for a pause that still waits. */
    static final String METRIC_REARMED_SPENT = "eddi_hitl_timeout_rearmed_spent";

    /** Field-injected; null in tests built with {@code new} (nothing counted). */
    @Inject
    MeterRegistry meterRegistry;

    /** Upper bound of rows read per schedule name — normally one or two. */
    private static final int ROWS_PER_NAME = 100;

    /**
     * The scheduler's {@code eddi.schedule.max-retries}: a FAILED row that has used
     * them is never due again. Field-injected; the default in tests built with
     * {@code new}.
     */
    @Inject
    @ConfigProperty(name = "eddi.schedule.max-retries", defaultValue = "5")
    int scheduleMaxRetries = 5;

    /**
     * The arming of the pause {@code pauseId} (paused at {@code pausedAt}), judged
     * over every row stored under the schedule name, so the answer does not depend
     * on which of several rows a store returns first.
     */
    TimeoutArming timeoutArming(List<ScheduleConfiguration> rows, String pauseId, Instant pausedAt) {
        boolean disabled = false;
        boolean spent = false;
        for (ScheduleConfiguration row : rows) {
            if (!HitlSchedules.isHitlTimeout(row.getMetadata())) {
                continue; // not a timeout row at all: it must not pass for this pause's timeout
            }
            if (!ofThisPause(row, pauseId, pausedAt)) {
                continue; // a row of an earlier pause: never armed for this one
            }
            if (canFire(row)) {
                return TimeoutArming.ARMED;
            }
            if (completed(row)) {
                spent = true;
            } else if (!row.isEnabled() && row.getDisabledReason() == null) {
                disabled = true;
            }
        }
        if (disabled) {
            return TimeoutArming.DISABLED;
        }
        return spent ? TimeoutArming.SPENT : TimeoutArming.MISSING;
    }

    /**
     * Whether the poller can still fire the row: enabled — pending, claimed by a
     * fire in progress (on any node; a dead claimer's lease expires and the row is
     * claimed again) or FAILED with retries left — and not dead-lettered. A row an
     * undeploy switched off cannot fire (an older build disabled HITL timeouts with
     * their agent; current builds leave them armed, as the timeout of a pause must
     * apply whatever the deployment does), so it is replaced by an enabled one.
     */
    boolean canFire(ScheduleConfiguration row) {
        if (!row.isEnabled()) {
            return false;
        }
        var status = row.getFireStatus();
        if (status == ScheduleConfiguration.FireStatus.DEAD_LETTERED) {
            return false;
        }
        return status != ScheduleConfiguration.FireStatus.FAILED || row.getFailCount() < scheduleMaxRetries;
    }

    /** What {@code markCompleted} leaves behind for a one-shot that fired. */
    private static boolean completed(ScheduleConfiguration row) {
        return !row.isEnabled() && row.getNextFire() == null && row.getLastFired() != null && row.getDisabledReason() == null;
    }

    /**
     * A row is this pause's when it carries its pause id. A row from an older build
     * carries none: it counts as this pause's while it can fire (it decides
     * whatever pause is current), and once it fired, if it fired after this pause
     * began.
     */
    private boolean ofThisPause(ScheduleConfiguration row, String pauseId, Instant pausedAt) {
        Object value = row.getMetadata() != null ? row.getMetadata().get(HitlSchedules.METADATA_PAUSE_ID_KEY) : null;
        if (value != null) {
            return value.toString().equals(pauseId);
        }
        if (canFire(row)) {
            return true;
        }
        return row.getLastFired() != null && !row.getLastFired().isBefore(pausedAt);
    }

    /** The latest fire among the completed timeout rows of THIS pause. */
    private Instant lastFire(List<ScheduleConfiguration> rows, String pauseId, Instant pausedAt) {
        return rows.stream().filter(r -> HitlSchedules.isHitlTimeout(r.getMetadata()) && ofThisPause(r, pauseId, pausedAt) && completed(r))
                .map(ScheduleConfiguration::getLastFired).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }

    /**
     * Makes sure the pause has a one-shot HITL timeout schedule, creating it only
     * when none that can fire exists (C1). An armed schedule — pending, being
     * fired, or waiting for a retry after a failed fire — is never deleted, moved
     * or re-created, so repeated sweeps cannot push a deadline out. A missing
     * schedule is created at the original due time (pausedAt + timeout), or
     * {@code overdueGrace} from now if that time already passed; the next sweep
     * then finds it armed. A row of this pause a person disabled is left alone; a
     * spent one (its timeout fired and completed while the pause still waits) is
     * re-armed {@link #SPENT_REARM_BACKOFF} after that fire.
     *
     * @param policyName
     *            a {@code HitlTimeoutPolicy} name, or for human-turn schedules (I6)
     *            an {@code OnHumanTimeout} name (SKIP_TURN/ABORT) — the fire
     *            handler branches on the surface before parsing
     * @param overdueGrace
     *            minimum delay before a re-created, already-overdue timeout fires
     * @param replace
     *            replace any existing row unconditionally — for a pause that was
     *            just restored after a lost resume, whose earlier row is spent
     * @param stillPaused
     *            re-checked AFTER the schedule is created — a resume/cancel may
     *            land between the sweep read and the create; the freshly created
     *            schedule is withdrawn if the pause is gone
     * @return true if a schedule was created and kept
     */
    private boolean rearmSchedule(String scheduleName, String surface, String conversationId,
                                  String agentId, String policyName, String approvalTimeout,
                                  Instant pausedAt, Duration overdueGrace, boolean replace,
                                  BooleanSupplier stillPaused) {
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

        String pauseId = HitlDecision.pauseIdOf(pausedAt);
        Instant earliest = Instant.now().plus(overdueGrace);
        Instant fireAt = pausedAt.plus(timeout);
        if (fireAt.isBefore(earliest)) {
            fireAt = earliest;
        }

        boolean spentRearm = false;
        try {
            List<ScheduleConfiguration> rows;
            TimeoutArming arming;
            try {
                rows = scheduleStore.readSchedulesByName(scheduleName, ROWS_PER_NAME);
                arming = timeoutArming(rows, pauseId, pausedAt);
            } catch (Exception e) {
                if (replace) {
                    // The pause was just restored; nothing could be learned about its rows, and
                    // the replacement below is the repair this pass exists for.
                    rows = List.of();
                    arming = TimeoutArming.MISSING;
                } else {
                    // A failed read says nothing about the rows: an armed one must not be
                    // deleted and re-created on a transient error, and a disabled one must
                    // not come back enabled. Nothing is written; the next sweep tries again.
                    // (A row that cannot be decoded cannot be told apart from the rest here,
                    // so it stays until it is repaired by hand: loud, not destructive.)
                    LOGGER.warnf("HITL timeout schedule(s) %s could not be read (%s) — leaving them as they are", scheduleName,
                            e.getMessage());
                    return false;
                }
            }
            if (arming == TimeoutArming.ARMED || arming == TimeoutArming.DISABLED) {
                LOGGER.debugf("HITL timeout for %s (%s) left as it is: %s", conversationId, surface, arming);
                return false;
            }
            if (rows.size() >= ROWS_PER_NAME) {
                // The read stopped at its bound: a current armed or hand-disabled row may be
                // among the rows not seen, so deleting by name could remove it.
                LOGGER.warnf("HITL timeout schedule(s) %s: %d or more rows, none armed for the pause in the part read — "
                        + "not replacing them blind; clean up the stale rows under this name", scheduleName, rows.size());
                return false;
            }
            if (!replace && arming == TimeoutArming.SPENT) {
                Instant lastFired = lastFire(rows, pauseId, pausedAt);
                Instant retryAt = (lastFired != null ? lastFired : Instant.now()).plus(SPENT_REARM_BACKOFF);
                if (retryAt.isAfter(fireAt)) {
                    fireAt = retryAt;
                }
                LOGGER.warnf("HITL timeout for %s (%s) fired at %s but the pause is still waiting — re-arming it for %s",
                        conversationId, surface, lastFired, fireAt);
                spentRearm = true;
            }
            // Clears every row under the name — rows of earlier pauses, rows that can
            // never fire again, spent ones — before the create.
            scheduleStore.deleteSchedulesByName(scheduleName);

            var schedule = new ScheduleConfiguration();
            schedule.setName(scheduleName);
            schedule.setAgentId(agentId);
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setEnabled(true);
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(HitlSchedules.timeoutMetadata(policyName, surface, conversationId, pauseId));
            String createdId = scheduleStore.createSchedule(schedule);

            // Re-check AFTER creating: a resume/cancel landing between the sweep
            // read and the create would otherwise leave an armed timeout on a
            // conversation that is no longer paused.
            try {
                if (stillPaused != null && !stillPaused.getAsBoolean()) {
                    // Only the row this sweep created: a newer pause may already have its own
                    // timeout under the same name, and that one is not ours to remove.
                    if (createdId != null) {
                        scheduleStore.deleteSchedule(createdId);
                    }
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
                    conversationId, surface, fireAt, policyName);
            if (spentRearm && meterRegistry != null) {
                // A pause re-armed this way every hour, forever, shows up here.
                meterRegistry.counter(METRIC_REARMED_SPENT, "surface", surface).increment();
            }
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
