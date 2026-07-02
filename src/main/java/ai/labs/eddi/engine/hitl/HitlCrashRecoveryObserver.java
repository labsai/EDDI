/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final boolean enabled;
    private final boolean recoverInProgress;

    @Inject
    public HitlCrashRecoveryObserver(
            IConversationMemoryStore conversationMemoryStore,
            IGroupConversationStore groupConversationStore,
            IScheduleStore scheduleStore,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.enabled",
                            defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.recover-in-progress",
                            defaultValue = "true") boolean recoverInProgress) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.groupConversationStore = groupConversationStore;
        this.scheduleStore = scheduleStore;
        this.enabled = enabled;
        this.recoverInProgress = recoverInProgress;
    }

    /** Upper bound of paused conversations scanned per recovery pass. */
    private static final int RECOVERY_SCAN_LIMIT = 10_000;

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
                    if (rearmSchedule("hitl-timeout-" + conversationId, "regular", conversationId,
                            summary.getAgentId(), policy, summary.getApprovalTimeout(),
                            summary.getPausedAt(),
                            () -> conversationMemoryStore.getConversationState(conversationId) == ConversationState.AWAITING_HUMAN)) {
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
                            HitlTimeoutPolicy policy = parsePolicy(snapshot.getHitlTimeoutPolicy());
                            if (policy != HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                                rearmSchedule("hitl-timeout-" + conversationId, "regular", conversationId,
                                        snapshot.getAgentId(), policy, snapshot.getHitlApprovalTimeout(),
                                        snapshot.getHitlPausedAt(),
                                        () -> conversationMemoryStore.getConversationState(conversationId) == ConversationState.AWAITING_HUMAN);
                            }
                        }
                    } else {
                        // No bookmark: unknown interrupted execution — unlock say().
                        if (conversationMemoryStore.compareAndSetState(conversationId,
                                ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED)) {
                            LOGGER.warnf("Recovered stuck IN_PROGRESS conversation to EXECUTION_INTERRUPTED: %s",
                                    conversationId);
                            count++;
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
                    HitlTimeoutPolicy policy = parsePolicy(gc.getHitlTimeoutPolicy());
                    if (policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                        continue;
                    }
                    if (rearmSchedule("hitl-timeout-group-" + gc.getId(), "group", gc.getId(),
                            null, policy, gc.getHitlApprovalTimeout(), gc.getPausedAt(),
                            () -> {
                                try {
                                    return groupConversationStore.read(gc.getId()).getState() == GroupConversationState.AWAITING_APPROVAL;
                                } catch (Exception e) {
                                    return true; // cannot verify — keep the schedule
                                }
                            })) {
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
                    "hitlType", "hitl_timeout",
                    "policy", policy.name(),
                    "surface", surface,
                    "conversationId", conversationId));
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
