/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.Cadence;
import ai.labs.eddi.configs.groups.model.GroupWorkspace.MemberStats;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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

    /**
     * How long a cadence run may hold its workspace claim before {@link #reconcile}
     * treats it as stale and reclaims it.
     * <p>
     * A claim is released by writeback when its discussion reaches a terminal
     * state, and an {@code AWAITING_APPROVAL} / {@code AWAITING_HUMAN_INPUT} pause
     * is not one. The default group HITL timeout policy is
     * {@code WAIT_INDEFINITELY}, so one unapproved cadence discussion held the
     * claim forever: every later fire for that group was skipped as "still
     * running", and the tasks it pulled stayed IN_PROGRESS on the backlog. Nothing
     * anywhere reaped it.
     * <p>
     * 24 hours is deliberately generous — an approval that arrives the next
     * business morning must still land on the discussion it belongs to, not on a
     * reclaimed corpse. This is a liveness backstop for a wedged team, not an SLA;
     * an operator who wants a tighter bound should set a finite
     * {@code hitlConfig.timeoutPolicy} on the group, which resolves the pause
     * properly instead of abandoning it.
     */
    static final Duration DEFAULT_CLAIM_TTL = Duration.ofHours(24);

    private final IGroupWorkspaceStore workspaceStore;
    private final IGroupConversationStore conversationStore;
    private final GroupConversationService groupConversationService;
    private final ITemplatingEngine templatingEngine;
    private final MeterRegistry meterRegistry;
    private final Duration claimTtl;

    private Counter cadenceRunsStarted;
    private Counter cadenceRunsSkipped;
    private Counter cadenceWritebacks;
    private Counter cadenceClaimsReclaimed;

    @Inject
    public TeamCadenceService(IGroupWorkspaceStore workspaceStore, IGroupConversationStore conversationStore,
            GroupConversationService groupConversationService, ITemplatingEngine templatingEngine,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.groups.cadence.claim-ttl", defaultValue = "PT24H") Duration claimTtl) {
        this.workspaceStore = workspaceStore;
        this.conversationStore = conversationStore;
        this.groupConversationService = groupConversationService;
        this.templatingEngine = templatingEngine;
        this.meterRegistry = meterRegistry;
        // Non-positive disables reclaiming entirely, for an operator who would rather
        // wedge than risk abandoning a pause.
        this.claimTtl = claimTtl != null ? claimTtl : DEFAULT_CLAIM_TTL;
    }

    @PostConstruct
    void initMetrics() {
        cadenceRunsStarted = meterRegistry.counter("eddi_team_cadence_runs_started_total");
        cadenceRunsSkipped = meterRegistry.counter("eddi_team_cadence_runs_skipped_total");
        cadenceWritebacks = meterRegistry.counter("eddi_team_cadence_writebacks_total");
        cadenceClaimsReclaimed = meterRegistry.counter("eddi_team_cadence_claims_reclaimed_total");
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
            //
            // Read the running id BEFORE reconciling. reconcile() returns false for two
            // different reasons — a discussion genuinely in flight, and a settle CAS this
            // caller lost — and in the second case settle() has already cleared
            // runningDiscussionId on this very object. Reading it afterwards therefore
            // produced "Previous cadence discussion is still running", with an empty id,
            // for precisely the case an operator most needs to tell apart.
            String previousDiscussionId = workspace.getRunningDiscussionId();
            if (!reconcile(workspace)) {
                cadenceRunsSkipped.increment();
                return CadenceResult.skipped(groupId, cadenceId,
                        "Previous cadence discussion " + previousDiscussionId
                                + " still holds the workspace (either still running, or settled concurrently by another reconciler)");
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

            // 3. Prepare — the conversation is created so the claim can carry the
            // real discussion id, but NOTHING runs yet. It used to be created and
            // submitted in one call, so a lost claim could only cancel a leg that
            // might already be inside phase 0 (and a graceful cancel lets the
            // current phase finish): an unclaimed run spent money and worked tasks
            // another run now owned.
            String question = renderQuestion(cadence, workspace, pulled);
            String userId = cadence.createdBy() != null && !cadence.createdBy().isBlank()
                    ? cadence.createdBy()
                    : FALLBACK_USER_ID;
            GroupConversationService.CadenceDiscussion prepared = groupConversationService.prepareCadenceDiscussion(
                    groupId, question, userId, toTaskDefinitions(pulled), cadence.maxCostPerRun(), null);
            GroupConversation gc = prepared.conversation();

            // 4. Claim: a revision-checked write (H14c), retried against a fresh read
            // while the workspace is still idle and every pulled task still waits.
            boolean claimed;
            try {
                claimed = claim(workspace, gc.getId(), pulled);
            } catch (Exception e) {
                // A store failure during the claim leaves a discussion nobody
                // references — retire it before failing, or a later fire could never
                // tell it from a live run.
                prepared.abandon();
                throw e;
            }
            if (!claimed) {
                // Another pod claimed (or the pulled tasks moved) between our read and
                // our write — retire the discussion this fire prepared and stand down.
                LOGGER.infof("Cadence %s for group %s lost the run claim — retiring its unlaunched discussion %s",
                        LogSanitizer.sanitize(cadenceId), LogSanitizer.sanitize(groupId), gc.getId());
                prepared.abandon();
                cadenceRunsSkipped.increment();
                return CadenceResult.skipped(groupId, cadenceId, "Lost the run claim to a concurrent fire");
            }

            // 5. Run — only now, with the claim won.
            prepared.launch();

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
     * Bound on re-read-and-retry for a claim or settle lost to an unrelated write.
     */
    static final int MAX_WRITE_ATTEMPTS = 3;

    /**
     * Claims the workspace for {@code discussionId}. The write is revision-checked,
     * so it also loses to an unrelated concurrent write (a backlog add, a cadence
     * edit) — which is not a lost claim. On such a loss the fresh document is
     * re-checked: still idle, and every pulled task still executable, means the
     * claim is still ours to take and it is re-applied to the fresh copy. Anything
     * else is a genuine loss.
     *
     * @return {@code true} once the claim is persisted
     */
    private boolean claim(GroupWorkspace workspace, String discussionId, List<TaskItem> pulled)
            throws IResourceStore.ResourceStoreException {
        GroupWorkspace current = workspace;
        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                current = workspaceStore.find(workspace.getGroupId());
                if (current == null || !isIdle(current)) {
                    return false;
                }
                var executable = current.getBacklog().findExecutableTasks().stream().map(TaskItem::id).toList();
                if (!pulled.stream().map(TaskItem::id).allMatch(executable::contains)) {
                    return false;
                }
            }
            String before = current.getRunningDiscussionId();
            current.setRunningDiscussionId(discussionId);
            // Stamped with the claim so reconcile can tell a long-running discussion
            // from a wedged one. Set here, next to the id it belongs to, and cleared
            // in settle() alongside it — the two are one fact.
            current.setClaimedAt(Instant.now());
            current.setPulledTaskIds(pulled.stream().map(TaskItem::id).toList());
            for (TaskItem task : pulled) {
                TaskItem fresh = current.getBacklog().findById(task.id());
                current.getBacklog().updateTask(withStatus(fresh != null ? fresh : task, TaskStatus.IN_PROGRESS));
            }
            if (workspaceStore.casRunningDiscussion(current, before)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdle(GroupWorkspace workspace) {
        return workspace.getRunningDiscussionId() == null || workspace.getRunningDiscussionId().isEmpty();
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
            return writebackFailure(workspace, null);
        }
        return switch (gc.getState()) {
            // A lost settle race means another reconciler (or a fresh claim) got
            // there first — this caller must treat the workspace as busy and let
            // the next fire read fresh state.
            case COMPLETED -> writebackCompleted(workspace, gc);
            // REJECTED belongs here, with the other terminal outcomes, and not in
            // the default arm: a human declining a cadence run's recommendation
            // ends it just as finally as a failure. Landing in `reclaimIfStale`
            // instead wedged the whole standing team -- the claim is held, every
            // later fire is skipped as "still running", and the pulled tasks stay
            // IN_PROGRESS on the backlog until claim-ttl (default PT24H) elapses,
            // or forever on a non-positive TTL. This is the only remaining switch
            // over the enum in src/main; keep it exhaustive by hand.
            case FAILED, REJECTED, CANCELLED -> writebackFailure(workspace, gc);
            // IN_PROGRESS, SYNTHESIZING, AWAITING_* — still running, unless the claim
            // has been held past its TTL. See reclaimIfStale.
            default -> reclaimIfStale(workspace, gc);
        };
    }

    /**
     * Releases a claim held past {@link #claimTtl}, cancelling the discussion that
     * held it.
     * <p>
     * Without this a cadence discussion that pauses for an approval nobody gives
     * wedges its team permanently: the pause is not a terminal state, so writeback
     * never runs; the claim is never released; every subsequent fire is skipped as
     * "still running"; and the tasks that run pulled stay IN_PROGRESS on the
     * backlog. The default group HITL policy is {@code WAIT_INDEFINITELY}, so this
     * needs no unusual configuration to happen — one unapproved run is enough.
     * <p>
     * The discussion is cancelled before the claim is released, so a run cannot
     * keep spending against a budget nobody is tracking any more, and its tasks go
     * back to PENDING through the ordinary failure writeback rather than a bespoke
     * path.
     *
     * @return {@code true} when the claim was released and the workspace is idle
     *         again, {@code false} while the run is still legitimately in flight
     */
    private boolean reclaimIfStale(GroupWorkspace workspace, GroupConversation gc) {
        if (claimTtl == null || claimTtl.isZero() || claimTtl.isNegative()) {
            return false;
        }
        Instant claimedAt = workspace.getClaimedAt();
        if (claimedAt == null) {
            // A workspace written before the stamp existed. Reclaiming on a missing
            // timestamp would be a guess; it gets a stamp on its next claim.
            return false;
        }
        if (Instant.now().isBefore(claimedAt.plus(claimTtl))) {
            return false;
        }

        LOGGER.warnf("Cadence claim on group %s has been held since %s (past the %s TTL) by discussion %s in state %s — "
                + "cancelling it and releasing the claim so the team's cadences can fire again",
                LogSanitizer.sanitize(workspace.getGroupId()), claimedAt, claimTtl, gc.getId(), gc.getState());
        cancelQuietly(gc.getId());
        cadenceClaimsReclaimed.increment();
        // The ordinary failure writeback: returns the pulled tasks to PENDING and
        // clears the claim, exactly as a FAILED/CANCELLED discussion would.
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
        return writebackCompleted(workspace, gc, 0);
    }

    private boolean writebackCompleted(GroupWorkspace workspace, GroupConversation gc, int attempt) {
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
        return settle(workspace, gc, settledDiscussionId, attempt, fresh -> writebackCompleted(fresh, gc, attempt + 1));
    }

    /**
     * Writeback for a FAILED/CANCELLED (or vanished) discussion: every pulled task
     * returns to PENDING untouched — the work simply did not happen.
     */
    boolean writebackFailure(GroupWorkspace workspace, GroupConversation gc) {
        return writebackFailure(workspace, gc, 0);
    }

    private boolean writebackFailure(GroupWorkspace workspace, GroupConversation gc, int attempt) {
        String settledDiscussionId = workspace.getRunningDiscussionId();
        for (String pulledId : workspace.getPulledTaskIds()) {
            TaskItem backlogTask = workspace.getBacklog().findById(pulledId);
            if (backlogTask != null && backlogTask.status() != TaskStatus.VERIFIED) {
                workspace.getBacklog().updateTask(withStatus(backlogTask, TaskStatus.PENDING));
            }
        }
        return settle(workspace, gc, settledDiscussionId, attempt, fresh -> writebackFailure(fresh, gc, attempt + 1));
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
    private boolean settle(GroupWorkspace workspace, GroupConversation gc, String settledDiscussionId, int attempt,
                           Predicate<GroupWorkspace> redoOnFresh) {
        var metrics = workspace.getMetrics();
        metrics.setDiscussions(metrics.getDiscussions() + 1);
        metrics.setLastRunAt(Instant.now());
        if (gc != null) {
            metrics.setTotalCost(metrics.getTotalCost() + gc.getTotalCost());
        }
        workspace.setRunningDiscussionId(GroupWorkspace.NO_RUNNING_DISCUSSION);
        workspace.setClaimedAt(null);
        workspace.setPulledTaskIds(List.of());
        try {
            if (!workspaceStore.casRunningDiscussion(workspace, settledDiscussionId)) {
                // H14c: the write is revision-checked, so it also loses to an
                // unrelated write (a backlog add, a cadence edit). If the fresh
                // document still names this discussion, nobody settled it — redo
                // the writeback on the fresh copy instead of dropping it.
                if (attempt + 1 < MAX_WRITE_ATTEMPTS && settledDiscussionId != null && !settledDiscussionId.isEmpty()) {
                    GroupWorkspace fresh = workspaceStore.find(workspace.getGroupId());
                    if (fresh != null && settledDiscussionId.equals(fresh.getRunningDiscussionId())) {
                        return redoOnFresh.test(fresh);
                    }
                }
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
            // Explicitly graceful: the stale-claim reclaim stops a run that did real
            // work, and lets its current phase finish. (A lost claim no longer
            // cancels anything — the run it prepared never launched; see
            // processScheduledFire.)
            groupConversationService.cancelDiscussion(discussionId, ControlSignal.CANCEL_GRACEFUL);
        } catch (Exception e) {
            LOGGER.warnf("Could not cancel discussion %s after an expired cadence claim: %s", discussionId, e.getMessage());
        }
    }
}
