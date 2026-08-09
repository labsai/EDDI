/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.MemberStats;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes team cadences (I13): scheduled pulls from a {@link GroupWorkspace}'s
 * backlog into a group discussion, and the writeback of that discussion's
 * outcomes. The scheduling itself rides {@code SchedulePollerService} whole —
 * cluster-wide claim, lease, retry/backoff, dead-lettering and fire logs come
 * free, exactly the DreamService precedent; this class is only the fire
 * handler.
 * <p>
 * <b>The run protocol is crash-proof by construction:</b>
 * <ol>
 * <li><b>Reconcile:</b> if the workspace records a running discussion, read it.
 * Terminal → write its outcomes back and release the claim. Still running (or
 * paused at an HITL gate) → skip this fire; a discussion may legitimately wait
 * on a human for days.</li>
 * <li><b>Pull:</b> top-N executable backlog tasks by priority. Empty → skip
 * (logged) — a cadence with no work must not burn a discussion.</li>
 * <li><b>Claim:</b> conditional store write on {@code runningDiscussionId} —
 * two pods firing concurrently cannot both start (no in-JVM locks).</li>
 * <li><b>Run:</b> the pulled tasks are injected as a runtime copy of
 * {@code config.tasks}; the cadence's dollar ceiling rides the
 * inherited-ceiling slot (dollar-primary, per the Dream precedent).</li>
 * </ol>
 * Writeback therefore happens on the fire AFTER the discussion ends (or on a
 * workspace read — see the REST layer's read-repair), never from inside the
 * discussion thread.
 * <p>
 * <b>Crash recovery rests on the lease, not on the terminal state.</b> This
 * used to claim that "a pod crash mid-discussion loses nothing, because the
 * next fire finds the terminal state and reconciles it". It does not: on a pod
 * crash nothing moves the discussion to a terminal state — no startup sweep
 * touches an IN_PROGRESS {@code GroupConversation}, and
 * {@code HitlCrashRecoveryObserver} handles only the AWAITING_* states — so
 * {@code reconcile} answered "still running" on every subsequent fire and the
 * cadence stayed wedged until a human cancelled the discussion by hand. A
 * non-terminal, non-paused discussion whose own progress heartbeat
 * ({@code lastModified}) has not advanced within
 * {@code eddi.groups.cadence.abandoned-run-lease} is now treated as abandoned:
 * cancelled, its tasks returned to the backlog, and the claim released. See
 * {@code reclaimIfAbandoned} for why the lease is measured on progress rather
 * than on claim age, and why AWAITING_* never expires.
 *
 * @author ginccc
 */
@ApplicationScoped
public class TeamCadenceService {

    private static final Logger LOGGER = Logger.getLogger(TeamCadenceService.class);

    /**
     * Metadata key marking a schedule as cadence-managed — the contract between
     * schedule authors (the workspace REST layer) and the dispatcher
     * ({@code ScheduleFireExecutor}), mirroring {@code DreamService}'s.
     */
    public static final String METADATA_TYPE_KEY = "teamCadenceType";
    /** Metadata value for team-cadence schedules. */
    public static final String METADATA_TYPE_CADENCE = "team_cadence";
    public static final String METADATA_GROUP_ID_KEY = "groupId";
    public static final String METADATA_CADENCE_ID_KEY = "cadenceId";

    /** Fallback identity when a cadence predates {@code createdBy}. */
    public static final String FALLBACK_USER_ID = "system:team-cadence";

    /**
     * States in which a discussion is legitimately waiting on a human and must
     * never be treated as abandoned, however old the claim is.
     */
    private static final Set<GroupConversationState> AWAITING_STATES = EnumSet.of(GroupConversationState.AWAITING_APPROVAL,
            GroupConversationState.AWAITING_HUMAN_INPUT);

    /**
     * Default lease for a non-terminal, non-paused cadence discussion that has
     * stopped advancing. Generous on purpose — it is measured against the
     * discussion's own progress heartbeat, so it only has to outlast the slowest
     * legitimate gap between two phase boundaries, not a whole discussion.
     */
    static final String DEFAULT_ABANDONED_RUN_LEASE = "PT6H";

    private final IGroupWorkspaceStore workspaceStore;
    private final IGroupConversationStore conversationStore;
    private final GroupConversationService groupConversationService;
    private final ITemplatingEngine templatingEngine;
    private final MeterRegistry meterRegistry;
    private final Duration abandonedRunLease;

    private Counter cadenceRunsStarted;
    private Counter cadenceRunsSkipped;
    private Counter cadenceWritebacks;
    private Counter cadenceAbandonedRuns;

    @Inject
    public TeamCadenceService(IGroupWorkspaceStore workspaceStore, IGroupConversationStore conversationStore,
            GroupConversationService groupConversationService, ITemplatingEngine templatingEngine,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.groups.cadence.abandoned-run-lease", defaultValue = DEFAULT_ABANDONED_RUN_LEASE) String abandonedRunLease) {
        this.workspaceStore = workspaceStore;
        this.conversationStore = conversationStore;
        this.groupConversationService = groupConversationService;
        this.templatingEngine = templatingEngine;
        this.meterRegistry = meterRegistry;
        this.abandonedRunLease = parseLease(abandonedRunLease);
    }

    /**
     * An unparseable lease falls back to the default rather than failing startup.
     */
    private static Duration parseLease(String value) {
        try {
            Duration parsed = Duration.parse(value);
            if (!parsed.isNegative() && !parsed.isZero()) {
                return parsed;
            }
            LOGGER.warnf("eddi.groups.cadence.abandoned-run-lease must be positive (was '%s') — using %s",
                    LogSanitizer.sanitize(value), DEFAULT_ABANDONED_RUN_LEASE);
        } catch (Exception e) {
            LOGGER.warnf("eddi.groups.cadence.abandoned-run-lease is not an ISO-8601 duration ('%s') — using %s",
                    LogSanitizer.sanitize(value), DEFAULT_ABANDONED_RUN_LEASE);
        }
        return Duration.parse(DEFAULT_ABANDONED_RUN_LEASE);
    }

    @PostConstruct
    void initMetrics() {
        cadenceRunsStarted = meterRegistry.counter("eddi_team_cadence_runs_started_total");
        cadenceRunsSkipped = meterRegistry.counter("eddi_team_cadence_runs_skipped_total");
        cadenceWritebacks = meterRegistry.counter("eddi_team_cadence_writebacks_total");
        cadenceAbandonedRuns = meterRegistry.counter("eddi_team_cadence_abandoned_runs_total");
    }

    /** True if the given schedule metadata marks a team-cadence schedule. */
    public static boolean isTeamCadenceSchedule(Map<String, Object> metadata) {
        return metadata != null && METADATA_TYPE_CADENCE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * One fire's outcome. {@code error == null} means the fire itself succeeded —
     * including the deliberate skips, which carry a {@code skippedReason} so the
     * fire log says WHY nothing ran.
     */
    public record CadenceResult(String groupId, String cadenceId, String discussionId, int tasksPulled,
            String skippedReason, String error) {

        public boolean isSuccess() {
            return error == null;
        }

        static CadenceResult skipped(String groupId, String cadenceId, String reason) {
            return new CadenceResult(groupId, cadenceId, null, 0, reason, null);
        }

        static CadenceResult failed(String groupId, String cadenceId, String error) {
            return new CadenceResult(groupId, cadenceId, null, 0, null, error);
        }
    }

    /**
     * Handles one schedule fire. Never throws — every failure lands in the result's
     * {@code error}, so the fire log records it and the schedule's retry/backoff
     * machinery decides what happens next.
     */
    public CadenceResult processScheduledFire(Map<String, Object> metadata) {
        String groupId = metadata != null ? String.valueOf(metadata.get(METADATA_GROUP_ID_KEY)) : null;
        String cadenceId = metadata != null ? String.valueOf(metadata.get(METADATA_CADENCE_ID_KEY)) : null;
        if (groupId == null || "null".equals(groupId) || cadenceId == null || "null".equals(cadenceId)) {
            return CadenceResult.failed(groupId, cadenceId, "Schedule metadata names no groupId/cadenceId");
        }
        try {
            GroupWorkspace workspace = workspaceStore.find(groupId);
            if (workspace == null) {
                return CadenceResult.failed(groupId, cadenceId, "No workspace exists for group " + groupId);
            }

            // 1. Reconcile a previous run before anything else.
            if (!reconcile(workspace)) {
                cadenceRunsSkipped.increment();
                return CadenceResult.skipped(groupId, cadenceId,
                        "Previous cadence discussion " + workspace.getRunningDiscussionId() + " is still running");
            }

            Cadence cadence = workspace.getCadences().stream()
                    .filter(c -> cadenceId.equals(c.cadenceId()))
                    .findFirst().orElse(null);
            if (cadence == null) {
                return CadenceResult.failed(groupId, cadenceId,
                        "Cadence " + cadenceId + " no longer exists on the workspace — delete its schedule");
            }

            // 2. Pull: top-N executable backlog tasks, highest priority first.
            List<TaskItem> pulled = workspace.getBacklog().findExecutableTasks().stream()
                    .sorted(Comparator.comparingInt(TaskItem::priority).reversed())
                    .limit(cadence.maxBacklogTasksPerRun())
                    .toList();
            if (pulled.isEmpty()) {
                cadenceRunsSkipped.increment();
                LOGGER.infof("Cadence %s for group %s: no executable backlog tasks — skipping this fire",
                        LogSanitizer.sanitize(cadenceId), LogSanitizer.sanitize(groupId));
                return CadenceResult.skipped(groupId, cadenceId, "No executable backlog tasks");
            }

            // 3. Run — started BEFORE the claim so the claim can carry the real
            // discussion id, then claimed BEFORE any task turns can complete.
            // startCadenceDiscussionAsync creates the conversation synchronously
            // and only then submits the discussion to the executor, so the id
            // exists here while no work has run yet; if the claim then loses,
            // the just-started discussion is cancelled before its first turn.
            String question = renderQuestion(cadence, workspace, pulled);
            String userId = cadence.createdBy() != null && !cadence.createdBy().isBlank()
                    ? cadence.createdBy()
                    : FALLBACK_USER_ID;
            GroupConversation gc = groupConversationService.startCadenceDiscussionAsync(
                    groupId, question, userId, toTaskDefinitions(pulled), cadence.maxCostPerRun(), null);

            // 4. Claim: conditional on the persisted idle marker.
            String before = workspace.getRunningDiscussionId();
            workspace.setRunningDiscussionId(gc.getId());
            workspace.setPulledTaskIds(pulled.stream().map(TaskItem::id).toList());
            for (TaskItem task : pulled) {
                workspace.getBacklog().updateTask(withStatus(task, TaskStatus.IN_PROGRESS));
            }
            boolean claimed;
            try {
                claimed = workspaceStore.casRunningDiscussion(workspace, before);
            } catch (Exception e) {
                // A store failure during the claim leaves a discussion nobody
                // references — cancel it before failing, or it runs unclaimed to
                // completion with outcomes no writeback will ever collect.
                cancelQuietly(gc.getId());
                throw e;
            }
            if (!claimed) {
                // Another pod claimed between our read and our write — cancel the
                // discussion this fire started and stand down.
                LOGGER.infof("Cadence %s for group %s lost the run claim — cancelling its just-started discussion %s",
                        LogSanitizer.sanitize(cadenceId), LogSanitizer.sanitize(groupId), gc.getId());
                cancelQuietly(gc.getId());
                cadenceRunsSkipped.increment();
                return CadenceResult.skipped(groupId, cadenceId, "Lost the run claim to a concurrent fire");
            }

            cadenceRunsStarted.increment();
            LOGGER.infof("Cadence %s for group %s started discussion %s with %d backlog task(s)",
                    LogSanitizer.sanitize(cadenceId), LogSanitizer.sanitize(groupId), gc.getId(), pulled.size());
            return new CadenceResult(groupId, cadenceId, gc.getId(), pulled.size(), null, null);
        } catch (Exception e) {
            LOGGER.errorf(e, "Cadence fire failed for group %s / cadence %s",
                    LogSanitizer.sanitize(groupId), LogSanitizer.sanitize(cadenceId));
            return CadenceResult.failed(groupId, cadenceId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Settles a previous cadence run, if any. Returns {@code true} when the
     * workspace is idle (possibly after writing back a finished discussion),
     * {@code false} when a previous discussion is genuinely still in flight.
     * <p>
     * Public beyond the fire path: the REST layer runs this as read-repair so a
     * human looking at the workspace sees settled metrics, not "running" state for
     * a discussion that ended an hour ago.
     */
    public boolean reconcile(GroupWorkspace workspace) {
        String runningId = workspace.getRunningDiscussionId();
        if (runningId == null || runningId.isEmpty()) {
            return true;
        }
        GroupConversation gc;
        try {
            gc = conversationStore.read(runningId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // PROVABLY gone — release the claim and return the pulled tasks; holding
            // the workspace hostage to a deleted discussion would stall every future
            // fire.
            LOGGER.warnf("Cadence discussion %s for group %s no longer exists — releasing the claim",
                    LogSanitizer.sanitize(runningId), LogSanitizer.sanitize(workspace.getGroupId()));
            return writebackFailure(workspace, null);
        } catch (Exception e) {
            // NOT proof of absence. This used to catch everything and release the
            // claim, so a read failure while the discussion was genuinely running
            // returned its tasks to PENDING and let the next fire start a SECOND
            // discussion on the same backlog. Same distinction
            // AgentDeploymentManagement#isAgentConfigMissing already makes: skip this
            // fire and leave the claim for the next one.
            LOGGER.warnf("Could not read cadence discussion %s for group %s (%s) — skipping this fire and keeping the claim",
                    LogSanitizer.sanitize(runningId), LogSanitizer.sanitize(workspace.getGroupId()), e.getClass().getSimpleName());
            return false;
        }
        return switch (gc.getState()) {
            // A lost settle race means another reconciler (or a fresh claim) got
            // there first — this caller must treat the workspace as busy and let
            // the next fire read fresh state.
            case COMPLETED -> writebackCompleted(workspace, gc);
            case FAILED, CANCELLED -> writebackFailure(workspace, gc);
            // Non-terminal. Still running — unless nothing has touched it for longer
            // than the lease, in which case the pod that owned it is gone.
            default -> reclaimIfAbandoned(workspace, gc);
        };
    }

    /**
     * Releases the claim of a non-terminal discussion that has stopped making
     * progress, or reports "still running" if it has not.
     * <p>
     * <b>Why a lease is needed at all.</b> This class's protocol was documented as
     * crash-proof — "a pod crash mid-discussion loses nothing, because the next
     * fire finds the terminal state and reconciles it". That only holds if
     * something moves the discussion to a terminal state, and on a pod crash
     * nothing does: no startup sweep touches an IN_PROGRESS
     * {@code GroupConversation} ({@code HitlCrashRecoveryObserver} handles only the
     * AWAITING_* states). {@code reconcile} then read IN_PROGRESS and answered
     * "still running" on every future fire, forever. The cadence was wedged until a
     * human cancelled the discussion by hand.
     * <p>
     * <b>Why the lease is measured on {@code lastModified}, not on claim time.</b>
     * A claim-age lease cannot tell a dead pod from a healthy long-running
     * discussion, and reclaiming a live one would orphan its outcomes AND
     * double-schedule its tasks — recreating, by design, the bug fixed above. The
     * discussion loop persists the conversation at every phase boundary, so
     * {@code lastModified} is a free liveness heartbeat: only a discussion that has
     * genuinely stopped advancing expires.
     * <p>
     * <b>AWAITING_* is never reclaimed, at any age.</b> A discussion may
     * legitimately wait on a human for days, and every surface that resolves one —
     * resume, cancel, the timeout policies re-armed at startup — works cross-pod,
     * so a paused discussion on a dead pod still progresses. Only the states that
     * require a live in-process loop can go stale.
     */
    private boolean reclaimIfAbandoned(GroupWorkspace workspace, GroupConversation gc) {
        if (AWAITING_STATES.contains(gc.getState())) {
            return false; // Legitimately waiting on a human — never expires.
        }
        Instant lastProgress = gc.getLastModified();
        if (lastProgress == null || lastProgress.isAfter(Instant.now().minus(abandonedRunLease))) {
            return false; // Still advancing.
        }

        LOGGER.warnf("Cadence discussion %s for group %s has not advanced since %s (lease %s) — treating it as abandoned, "
                + "releasing the claim and returning its tasks to the backlog",
                LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(workspace.getGroupId()), lastProgress, abandonedRunLease);
        cadenceAbandonedRuns.increment();
        // Cancel before releasing: a zombie loop that somehow survives must not keep
        // spending the cadence's budget on work nobody will collect.
        cancelQuietly(gc.getId());
        return writebackFailure(workspace, gc);
    }

    /**
     * Writeback for a COMPLETED cadence discussion: match every pulled backlog task
     * against the discussion's task list by subject — VERIFIED outcomes stay
     * VERIFIED on the backlog (and credit the assignee's stats); anything else
     * returns to PENDING with the reviewer feedback appended to the description,
     * which is the cross-run retry loop.
     */
    boolean writebackCompleted(GroupWorkspace workspace, GroupConversation gc) {
        String settledDiscussionId = workspace.getRunningDiscussionId();
        SharedTaskList discussionTasks = gc.getTaskList();
        for (String pulledId : workspace.getPulledTaskIds()) {
            TaskItem backlogTask = workspace.getBacklog().findById(pulledId);
            if (backlogTask == null) {
                continue;
            }
            TaskItem outcome = discussionTasks == null
                    ? null
                    : discussionTasks.getTasks().stream()
                            .filter(t -> backlogTask.subject().equals(t.subject()))
                            .findFirst().orElse(null);
            if (outcome != null && outcome.verified()) {
                workspace.getBacklog().updateTask(new TaskItem(backlogTask.id(), backlogTask.subject(),
                        backlogTask.description(), TaskStatus.VERIFIED, outcome.assignedAgentId(),
                        outcome.assignedDisplayName(), backlogTask.dependsOnIds(), outcome.result(),
                        outcome.verificationNote(), true, backlogTask.priority(), backlogTask.createdAt(),
                        Instant.now()));
                workspace.getMetrics().setTasksVerified(workspace.getMetrics().getTasksVerified() + 1);
                if (outcome.assignedAgentId() != null) {
                    memberStats(workspace, outcome.assignedAgentId()).setTasksVerified(
                            memberStats(workspace, outcome.assignedAgentId()).getTasksVerified() + 1);
                }
            } else {
                String feedback = outcome != null && outcome.verificationNote() != null
                        ? outcome.verificationNote()
                        : (outcome != null && outcome.result() != null ? outcome.result() : "not completed this run");
                workspace.getBacklog().updateTask(new TaskItem(backlogTask.id(), backlogTask.subject(),
                        appendFeedbackBounded(backlogTask.description(), gc.getId(), feedback),
                        TaskStatus.PENDING, null, null, backlogTask.dependsOnIds(), null, null, false,
                        backlogTask.priority(), backlogTask.createdAt(), null));
                if (outcome != null && outcome.assignedAgentId() != null) {
                    memberStats(workspace, outcome.assignedAgentId()).setTasksFailed(
                            memberStats(workspace, outcome.assignedAgentId()).getTasksFailed() + 1);
                }
            }
        }
        return settle(workspace, gc, settledDiscussionId);
    }

    /**
     * Writeback for a FAILED/CANCELLED (or vanished) discussion: every pulled task
     * returns to PENDING untouched — the work simply did not happen.
     */
    boolean writebackFailure(GroupWorkspace workspace, GroupConversation gc) {
        String settledDiscussionId = workspace.getRunningDiscussionId();
        for (String pulledId : workspace.getPulledTaskIds()) {
            TaskItem backlogTask = workspace.getBacklog().findById(pulledId);
            if (backlogTask != null && backlogTask.status() != TaskStatus.VERIFIED) {
                workspace.getBacklog().updateTask(withStatus(backlogTask, TaskStatus.PENDING));
            }
        }
        return settle(workspace, gc, settledDiscussionId);
    }

    /**
     * @return {@code true} if THIS caller's writeback won — the persisted claim
     *         still named the discussion being settled. {@code false} means a
     *         concurrent reconcile (schedule fire on another pod, or the REST
     *         read-repair) settled it first, or a new run already claimed the
     *         workspace; either way this caller's mutations are stale and MUST die
     *         unwritten — an unconditional write here clobbered a live claim,
     *         orphaning its discussion's outcomes (final-review finding).
     */
    private boolean settle(GroupWorkspace workspace, GroupConversation gc, String settledDiscussionId) {
        var metrics = workspace.getMetrics();
        metrics.setDiscussions(metrics.getDiscussions() + 1);
        metrics.setLastRunAt(Instant.now());
        if (gc != null) {
            metrics.setTotalCost(metrics.getTotalCost() + gc.getTotalCost());
        }
        workspace.setRunningDiscussionId(GroupWorkspace.NO_RUNNING_DISCUSSION);
        workspace.setPulledTaskIds(List.of());
        try {
            if (!workspaceStore.casRunningDiscussion(workspace, settledDiscussionId)) {
                LOGGER.infof("Cadence writeback for group %s lost the settle race on discussion %s — another "
                        + "reconciler settled it (or a new run claimed the workspace); dropping stale mutations",
                        LogSanitizer.sanitize(workspace.getGroupId()), settledDiscussionId);
                return false;
            }
            cadenceWritebacks.increment();
            return true;
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to persist cadence writeback for group %s",
                    LogSanitizer.sanitize(workspace.getGroupId()));
            return false;
        }
    }

    private MemberStats memberStats(GroupWorkspace workspace, String agentId) {
        return workspace.getMetrics().getPerMemberStats().computeIfAbsent(agentId, k -> new MemberStats());
    }

    /** One failed run's feedback slice — enough context, never a whole turn. */
    static final int MAX_FEEDBACK_CHARS = 500;

    /**
     * Bounded feedback append (final-review finding): {@code outcome.result()} is
     * the agent's ENTIRE turn output, and a task failing every run appended it to a
     * PERSISTED description each time — unbounded document growth that also rode
     * into the next run's PLAN/bid prompts. The feedback is capped per run and the
     * accumulated description trimmed from the FRONT (oldest feedback first) to the
     * shared task-description ceiling.
     */
    static String appendFeedbackBounded(String description, String runId, String feedback) {
        String slice = feedback.length() <= MAX_FEEDBACK_CHARS
                ? feedback
                : feedback.substring(0, MAX_FEEDBACK_CHARS) + "…";
        String combined = (description != null ? description : "") + "\n[Cadence run " + runId + "] " + slice;
        int cap = SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH;
        return combined.length() <= cap ? combined : "…" + combined.substring(combined.length() - cap + 1);
    }

    private static TaskItem withStatus(TaskItem task, TaskStatus status) {
        return new TaskItem(task.id(), task.subject(), task.description(), status, task.assignedAgentId(),
                task.assignedDisplayName(), task.dependsOnIds(), task.result(), task.verificationNote(),
                task.verified(), task.priority(), task.createdAt(), task.completedAt());
    }

    /**
     * Pulled backlog items as a runtime task plan. Dependencies are dropped on
     * purpose: {@code findExecutableTasks} already guaranteed every pulled task's
     * dependencies are met, and backlog ids mean nothing inside the discussion.
     */
    private static List<TaskDefinition> toTaskDefinitions(List<TaskItem> pulled) {
        List<TaskDefinition> defs = new ArrayList<>(pulled.size());
        for (TaskItem task : pulled) {
            defs.add(new TaskDefinition(task.subject(), task.description(), "ALL", List.of(), task.priority()));
        }
        return defs;
    }

    /**
     * The discussion question: the cadence's Qute template rendered over a compact
     * backlog summary, or a plain default. A template failure degrades to the
     * default — a cadence must not stop pulling work because its prose template
     * broke.
     */
    private String renderQuestion(Cadence cadence, GroupWorkspace workspace, List<TaskItem> pulled) {
        StringBuilder summary = new StringBuilder("Work the following backlog tasks:\n");
        for (TaskItem task : pulled) {
            summary.append("- ").append(task.subject());
            if (task.description() != null && !task.description().isBlank()) {
                summary.append(": ").append(task.description());
            }
            summary.append('\n');
        }
        if (cadence.inputTemplate() == null || cadence.inputTemplate().isBlank()) {
            return summary.toString();
        }
        try {
            return templatingEngine.processTemplate(cadence.inputTemplate(), Map.of(
                    "backlogSummary", summary.toString(),
                    "groupId", workspace.getGroupId(),
                    "taskCount", pulled.size()));
        } catch (Exception e) {
            LOGGER.warnf("Cadence %s input template failed to render (%s) — using the plain backlog summary",
                    LogSanitizer.sanitize(cadence.cadenceId()), LogSanitizer.sanitize(e.getMessage()));
            return summary.toString();
        }
    }

    private void cancelQuietly(String discussionId) {
        try {
            groupConversationService.cancelDiscussion(discussionId, null);
        } catch (Exception e) {
            LOGGER.warnf("Could not cancel discussion %s after a lost cadence claim: %s", discussionId, e.getMessage());
        }
    }
}
