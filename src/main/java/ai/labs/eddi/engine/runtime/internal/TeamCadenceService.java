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
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
 * discussion thread: a pod crash mid-discussion loses nothing, because the next
 * fire finds the terminal state and reconciles it.
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

    private final IGroupWorkspaceStore workspaceStore;
    private final IGroupConversationStore conversationStore;
    private final GroupConversationService groupConversationService;
    private final ITemplatingEngine templatingEngine;
    private final MeterRegistry meterRegistry;

    private Counter cadenceRunsStarted;
    private Counter cadenceRunsSkipped;
    private Counter cadenceWritebacks;

    @Inject
    public TeamCadenceService(IGroupWorkspaceStore workspaceStore, IGroupConversationStore conversationStore,
            GroupConversationService groupConversationService, ITemplatingEngine templatingEngine,
            MeterRegistry meterRegistry) {
        this.workspaceStore = workspaceStore;
        this.conversationStore = conversationStore;
        this.groupConversationService = groupConversationService;
        this.templatingEngine = templatingEngine;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        cadenceRunsStarted = meterRegistry.counter("eddi_team_cadence_runs_started_total");
        cadenceRunsSkipped = meterRegistry.counter("eddi_team_cadence_runs_skipped_total");
        cadenceWritebacks = meterRegistry.counter("eddi_team_cadence_writebacks_total");
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
            if (!workspaceStore.casRunningDiscussion(workspace, before)) {
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
        } catch (Exception e) {
            // Deleted or unreadable — release the claim and return the pulled
            // tasks; holding the workspace hostage to a vanished discussion would
            // stall every future fire.
            LOGGER.warnf("Cadence discussion %s for group %s is gone (%s) — releasing the claim",
                    runningId, LogSanitizer.sanitize(workspace.getGroupId()), e.getClass().getSimpleName());
            writebackFailure(workspace, null);
            return true;
        }
        return switch (gc.getState()) {
            case COMPLETED -> {
                writebackCompleted(workspace, gc);
                yield true;
            }
            case FAILED, CANCELLED -> {
                writebackFailure(workspace, gc);
                yield true;
            }
            default -> false; // IN_PROGRESS, SYNTHESIZING, AWAITING_* — still running
        };
    }

    /**
     * Writeback for a COMPLETED cadence discussion: match every pulled backlog task
     * against the discussion's task list by subject — VERIFIED outcomes stay
     * VERIFIED on the backlog (and credit the assignee's stats); anything else
     * returns to PENDING with the reviewer feedback appended to the description,
     * which is the cross-run retry loop.
     */
    void writebackCompleted(GroupWorkspace workspace, GroupConversation gc) {
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
                        backlogTask.description() + "\n[Cadence run " + gc.getId() + "] " + feedback,
                        TaskStatus.PENDING, null, null, backlogTask.dependsOnIds(), null, null, false,
                        backlogTask.priority(), backlogTask.createdAt(), null));
                if (outcome != null && outcome.assignedAgentId() != null) {
                    memberStats(workspace, outcome.assignedAgentId()).setTasksFailed(
                            memberStats(workspace, outcome.assignedAgentId()).getTasksFailed() + 1);
                }
            }
        }
        settle(workspace, gc);
    }

    /**
     * Writeback for a FAILED/CANCELLED (or vanished) discussion: every pulled task
     * returns to PENDING untouched — the work simply did not happen.
     */
    void writebackFailure(GroupWorkspace workspace, GroupConversation gc) {
        for (String pulledId : workspace.getPulledTaskIds()) {
            TaskItem backlogTask = workspace.getBacklog().findById(pulledId);
            if (backlogTask != null && backlogTask.status() != TaskStatus.VERIFIED) {
                workspace.getBacklog().updateTask(withStatus(backlogTask, TaskStatus.PENDING));
            }
        }
        settle(workspace, gc);
    }

    private void settle(GroupWorkspace workspace, GroupConversation gc) {
        var metrics = workspace.getMetrics();
        metrics.setDiscussions(metrics.getDiscussions() + 1);
        metrics.setLastRunAt(Instant.now());
        if (gc != null) {
            metrics.setTotalCost(metrics.getTotalCost() + gc.getTotalCost());
        }
        workspace.setRunningDiscussionId(GroupWorkspace.NO_RUNNING_DISCUSSION);
        workspace.setPulledTaskIds(List.of());
        try {
            workspaceStore.update(workspace);
            cadenceWritebacks.increment();
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to persist cadence writeback for group %s",
                    LogSanitizer.sanitize(workspace.getGroupId()));
        }
    }

    private MemberStats memberStats(GroupWorkspace workspace, String agentId) {
        return workspace.getMetrics().getPerMemberStats().computeIfAbsent(agentId, k -> new MemberStats());
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
                    LogSanitizer.sanitize(cadence.cadenceId()), e.getMessage());
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
