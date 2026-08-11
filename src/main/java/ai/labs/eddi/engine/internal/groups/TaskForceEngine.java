/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancellation;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancelledException;
import ai.labs.eddi.engine.internal.TaskListParser;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Runs the TASK_FORCE-style task-oriented phases (PLAN, EXECUTE, VERIFY), which
 * operate on a group's shared task list rather than iterating speakers over a
 * common question. Extracted from {@code GroupConversationService} (Wave R, R1
 * step 6) as a pure move — no behavior change.
 * <p>
 * <b>Concurrency contracts preserved verbatim</b> (pinned by
 * {@code GroupConversationServiceConcurrencyTest}):
 * <ul>
 * <li>Lock order is always {@code taskList} → {@code transcript}.</li>
 * <li>{@link #recordTaskFailure} (the document-mutating half of a task failure)
 * must run under the caller's {@code taskList} monitor;
 * {@link #notifyTaskFailure} (the SSE emission) must run outside it — the split
 * exists so an SSE sink is never invoked while holding the monitor.</li>
 * <li>{@link #resetStrandedInProgressTasks} scans and resets under the same
 * {@code taskList} monitor {@link SharedTaskList}'s own synchronized methods
 * use, so the sweep is a compare-and-set against live state, not a stale
 * snapshot.</li>
 * </ul>
 * Shares the facade's virtual-thread {@link ExecutorService} and
 * {@code activeTokens} map by reference (not ownership) — see
 * {@link PhaseExecutionEngine}'s Javadoc for why.
 *
 * @author ginccc
 */
public class TaskForceEngine {

    private static final Logger LOGGER = Logger.getLogger(TaskForceEngine.class);

    private final MemberTurnExecutor memberTurnExecutor;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final ExecutorService executorService;
    private final CallerIdentityContext callerIdentityContext;
    private final Map<String, DiscussionControlToken> activeTokens;
    private final int defaultAgentTimeoutSeconds;
    private final int memberTurnCancelDrainSeconds;

    public TaskForceEngine(MemberTurnExecutor memberTurnExecutor, ITemplatingEngine templatingEngine,
            IJsonSerialization jsonSerialization, ExecutorService executorService, CallerIdentityContext callerIdentityContext,
            Map<String, DiscussionControlToken> activeTokens, int defaultAgentTimeoutSeconds, int memberTurnCancelDrainSeconds) {
        this.memberTurnExecutor = memberTurnExecutor;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.executorService = executorService;
        this.callerIdentityContext = callerIdentityContext;
        this.activeTokens = activeTokens;
        this.defaultAgentTimeoutSeconds = defaultAgentTimeoutSeconds;
        this.memberTurnCancelDrainSeconds = memberTurnCancelDrainSeconds;
    }

    /**
     * Dispatches task-oriented phases (PLAN, EXECUTE, VERIFY) to their specific
     * handlers. These phases are structurally different from debate phases — they
     * operate on a shared task list rather than iterating speakers with a common
     * question.
     */
    public void executeTaskPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                 DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                 GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        switch (phase.type()) {
            case PLAN -> executeTaskPlanPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            case EXECUTE -> executeTaskExecutionPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            case VERIFY -> executeTaskVerificationPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            default -> LOGGER.warnf("Unexpected phase type %s routed to executeTaskPhase", phase.type());
        }
    }

    /**
     * PLAN phase: Decompose the goal into tasks. If pre-configured tasks exist in
     * the group config, uses those directly (skipping LLM planning). Otherwise, the
     * moderator agent decomposes the goal via its pipeline and the output is parsed
     * with three-tier fallback (JSON → Markdown → single task).
     */
    private void executeTaskPlanPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                      DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                      GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        // I1: LLM planning is a paid moderator turn, so it gets the same pre-turn
        // gate as EXECUTE and VERIFY. Checked before the pre-configured branch too —
        // that branch is free, but letting it run under a blown budget would build a
        // task list the EXECUTE phase is then gated out of ever running.
        if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
            return;
        }

        if (gc.getTaskList() == null) {
            gc.setTaskList(new SharedTaskList());
        }

        boolean preConfigured = config.getTasks() != null && !config.getTasks().isEmpty();

        if (preConfigured) {
            // Config-driven tasks — skip LLM planning
            // First pass: create all TaskItems
            List<TaskItem> createdItems = new ArrayList<>();
            for (TaskDefinition td : config.getTasks()) {
                TaskItem task = new TaskItem(td.subject(), td.description(), td.priority());
                gc.getTaskList().addTask(task);
                createdItems.add(task);
            }

            // Second pass: resolve dependsOn subjects to task IDs
            for (int i = 0; i < config.getTasks().size(); i++) {
                TaskDefinition td = config.getTasks().get(i);
                TaskItem original = createdItems.get(i);
                if (td.dependsOn() != null && !td.dependsOn().isEmpty()) {
                    List<String> resolvedDepIds = td.dependsOn().stream()
                            .map(depSubject -> createdItems.stream()
                                    .filter(ci -> ci.subject().equalsIgnoreCase(depSubject))
                                    .map(TaskItem::id)
                                    .findFirst().orElse(null))
                            .filter(Objects::nonNull)
                            .toList();
                    if (!resolvedDepIds.isEmpty()) {
                        // Replace with dependency-aware TaskItem
                        TaskItem withDeps = new TaskItem(
                                original.id(), original.subject(), original.description(),
                                original.status(), original.assignedAgentId(), original.assignedDisplayName(),
                                resolvedDepIds, original.result(), original.verificationNote(),
                                original.verified(), original.priority(), original.createdAt(), original.completedAt());
                        gc.getTaskList().updateTask(withDeps); // replace with dependency-aware version
                    }
                }
            }

            // Third pass: resolve assignments with round-robin for "ALL"
            for (int i = 0; i < createdItems.size(); i++) {
                TaskItem task = createdItems.get(i);
                TaskDefinition td = config.getTasks().get(i);
                // I18: BID-mode tasks are deliberately left unassigned — the
                // EXECUTE wave announces them and awards by bid; assigning here
                // would preempt the auction with the planner's guess.
                if (TaskBidEngine.effectiveMode(td, config) == AgentGroupConfiguration.AssignmentMode.BID) {
                    LOGGER.debugf("Task '%s' is BID-mode — left for the execution wave's auction", task.subject());
                    continue;
                }
                String assignedAgentId = resolveTaskAssignment(
                        td.assignToRole(), config.getMembers(), config.getModeratorAgentId(), i);
                if (assignedAgentId != null) {
                    GroupMember assignedMember = findMember(config.getMembers(), assignedAgentId);
                    String displayName = assignedMember != null ? assignedMember.displayName() : assignedAgentId;
                    gc.getTaskList().assignTask(task.id(), assignedAgentId, displayName);
                } else {
                    LOGGER.warnf("Could not resolve assignment for task '%s' with role '%s'",
                            task.subject(), td.assignToRole());
                }
            }

            gc.getTranscript().add(new TranscriptEntry(
                    "system", "System",
                    "Pre-configured task plan: " + config.getTasks().size() + " tasks",
                    phaseIdx, phase.name(), TranscriptEntryType.PLAN,
                    Instant.now(), null, null));

        } else {
            // LLM-driven planning via moderator
            if (speakers.isEmpty()) {
                throw new GroupDiscussionException("PLAN phase requires a moderator but no speakers resolved");
            }

            GroupMember planner = speakers.getFirst();
            turnCounter.incrementAndGet();

            if (listener != null) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(planner.agentId(), planner.displayName(), phaseIdx, phase.name()));
            }

            // Build planning input with member info
            String planTemplate = DiscussionStylePresets.defaultTemplate(PhaseType.PLAN);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("question", question);
            data.put("displayName", planner.displayName());
            List<Map<String, Object>> memberList = config.getMembers().stream()
                    .filter(m -> !m.agentId().equals(planner.agentId()) || config.getMembers().size() == 1)
                    .map(m -> {
                        Map<String, Object> md = new LinkedHashMap<>();
                        md.put("agentId", m.agentId());
                        md.put("displayName", m.displayName());
                        md.put("capabilities", m.role() != null ? m.role() : "");
                        return md;
                    }).collect(Collectors.toList());
            data.put("members", memberList);

            String planInput;
            try {
                planInput = templatingEngine.processTemplate(planTemplate, data, ITemplatingEngine.TemplateMode.TEXT);
            } catch (ITemplatingEngine.TemplateEngineException e) {
                planInput = "Decompose this goal into tasks for your team: " + question;
            }

            TranscriptEntry planEntry = memberTurnExecutor.executeAgentTurn(planner, gc, planInput, protocol, phaseIdx, phase, null, listener);
            gc.getTranscript().add(planEntry);

            if (listener != null) {
                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                        planner.agentId(), planner.displayName(), planEntry.content(), phaseIdx, phase.name()));
            }

            // Parse the plan output
            List<TaskListParser.ParsedTask> parsedTasks = TaskListParser.parse(planEntry.content(), config.getMembers());

            for (int i = 0; i < parsedTasks.size(); i++) {
                TaskListParser.ParsedTask pt = parsedTasks.get(i);
                TaskItem task = new TaskItem(pt.subject(), pt.description(), pt.priority());
                gc.getTaskList().addTask(task);

                // I18: under a BID-mode default, planned tasks stay unassigned for
                // the execution wave's auction (the planner cannot know members'
                // actual fit or load — that is the point of bidding).
                if (TaskBidEngine.effectiveMode(null, config) == AgentGroupConfiguration.AssignmentMode.BID) {
                    LOGGER.debugf("Planned task '%s' is BID-mode — left for the execution wave's auction", pt.subject());
                    continue;
                }

                // Resolve assignment — null-safe (C4 fix)
                String agentId = TaskListParser.resolveAgent(pt.assignedTo(), config.getMembers());
                if (agentId == null) {
                    agentId = TaskListParser.roundRobinAssign(i, config.getMembers());
                    LOGGER.debugf("Could not resolve assignee '%s', round-robin assigning to %s", pt.assignedTo(), agentId);
                }
                if (agentId != null) {
                    GroupMember member = findMember(config.getMembers(), agentId);
                    String displayName = member != null ? member.displayName() : agentId;
                    gc.getTaskList().assignTask(task.id(), agentId, displayName);
                } else {
                    LOGGER.warnf("Task '%s' has no assignable agent, will be skipped during execution", pt.subject());
                }
            }
        }

        // Validate no circular dependencies (covers both pre-configured and LLM-planned
        // paths)
        List<String> cycles = gc.getTaskList().detectCycles();
        if (!cycles.isEmpty()) {
            throw new GroupDiscussionException(
                    "Circular task dependencies detected: " + String.join(" → ", cycles));
        }

        // Emit task plan event
        if (listener != null) {
            List<GroupConversationEventSink.TaskSummary> summaries = gc.getTaskList().all().stream()
                    .map(t -> new GroupConversationEventSink.TaskSummary(t.id(), t.subject(), t.assignedDisplayName(), t.priority()))
                    .toList();
            listener.onTaskPlanCreated(new GroupConversationEventSink.TaskPlanCreatedEvent(summaries, preConfigured));
        }
    }

    /**
     * EXECUTE phase: Run each assigned task by sending it to the responsible
     * agent's pipeline. Tasks for different agents execute in parallel; tasks for
     * the same agent execute sequentially within a single CompletableFuture.
     */
    public void executeTaskExecutionPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                          DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                          GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        if (gc.getTaskList() == null || gc.getTaskList().isEmpty()) {
            LOGGER.warn("EXECUTE phase: no tasks to execute");
            return;
        }

        // Resolve HITL TASK-level flag locally (not available from executeDiscussion
        // scope)
        boolean taskLevelHitl = config.getHitlConfig() != null
                && config.getHitlConfig().getGranularity() == HitlGranularity.TASK;

        // Note: unlike executeParallelPhase, no transcript snapshot is needed here
        // because agents receive task-specific input via buildTaskExecutionInput(),
        // not transcript context.

        List<GroupDiscussionException> errors = Collections.synchronizedList(new ArrayList<>());
        int maxWaves = 100; // safety cap to prevent infinite loops
        final SharedTaskList taskList = gc.getTaskList();

        // Wave loop: re-query executable tasks after each wave completes.
        // Tasks that become executable when their dependencies finish are picked up
        // in the next wave. This handles dependsOn chains across any depth.
        for (int wave = 0; wave < maxWaves; wave++) {
            // NEW-3: Check control token at top of wave loop
            var token = activeTokens.get(gc.getId());
            if (token != null && token.isCancelled()) {
                LOGGER.infof("EXECUTE wave loop cancelled via control token at wave %d", wave);
                break;
            }

            // I1: checked before each wave — a wave already in flight may still
            // overshoot the ceiling, which is accepted (see GroupCostLedger's Javadoc).
            if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
                break;
            }

            // I18: announce-bid-award for this wave's unassigned BID-mode tasks —
            // runs before the grouping below so awarded tasks join the same wave.
            runBidRoundIfNeeded(gc, config, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);

            Map<String, List<TaskItem>> tasksByAgent = gc.getTaskList().findExecutableTasks().stream()
                    .filter(t -> t.assignedAgentId() != null)
                    .collect(Collectors.groupingBy(TaskItem::assignedAgentId));

            if (tasksByAgent.isEmpty()) {
                if (wave == 0) {
                    LOGGER.warn("EXECUTE phase: no assigned tasks found");
                }
                break; // no more executable tasks — all waves complete
            }

            LOGGER.debugf("EXECUTE phase wave %d: %d agents, %d tasks",
                    wave + 1, tasksByAgent.size(),
                    tasksByAgent.values().stream().mapToInt(List::size).sum());

            // Cooperative cancellation for this wave's member turns: cancel(true) on
            // the futures below does NOT interrupt their bodies, so aborting the wave
            // has to signal through this token instead.
            var cancellation = new MemberTurnCancellation();

            // Execute agents in parallel, tasks per agent sequentially
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            // Task workers are a further fan-out of their own; without this every
            // task wave loses ${caller:...}.
            final var waveCaller = callerIdentityContext.captureOrCurrent();

            for (Map.Entry<String, List<TaskItem>> agentEntry : tasksByAgent.entrySet()) {
                String agentId = agentEntry.getKey();
                List<TaskItem> agentTasks = agentEntry.getValue();
                GroupMember member = findMemberIncludingDynamic(config.getMembers(), gc, agentId);

                if (member == null) {
                    LOGGER.warnf("Task assigned to unknown agent '%s', skipping", agentId);
                    continue;
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(callerIdentityContext.withIdentity(waveCaller, () -> {
                    for (TaskItem task : agentTasks) {
                        try {
                            // Claim the turn budget and the task itself under the task-list
                            // monitor, together with the cancellation check. Atomically,
                            // because (a) N agent threads racing a check-then-act on the
                            // counter would overshoot maxTurns by up to N-1 LLM calls and
                            // (b) a task must never flip to IN_PROGRESS after an aborting
                            // orchestrator swept the list — that would strand it forever.
                            synchronized (taskList) {
                                if (cancellation.isCancelled() || !reserveTurn(turnCounter, maxTurns)) {
                                    break;
                                }
                                taskList.startTask(task.id());
                            }

                            if (listener != null) {
                                listener.onSpeakerStart(new GroupConversationEventSink.SpeakerStartEvent(
                                        member.agentId(), member.displayName(), phaseIdx, phase.name()));
                            }

                            // Build task-specific input
                            String taskInput = buildTaskExecutionInput(task, question, phase, gc);
                            TranscriptEntry entry = memberTurnExecutor.executeAgentTurn(member, gc, taskInput, protocol, phaseIdx, phase, null,
                                    listener, cancellation);

                            // The orchestrator owns the group document from the moment it
                            // cancels this wave: publish the result only if the wave is
                            // still live, again under the task-list monitor so the reset
                            // sweep cannot interleave. Lock order is always taskList →
                            // transcript.
                            synchronized (taskList) {
                                if (cancellation.isCancelled()) {
                                    break;
                                }
                                synchronized (gc.getTranscript()) {
                                    gc.getTranscript().add(entry);
                                }

                                // HITL TASK-level: submit for approval only when BOTH
                                // taskLevelHitl AND this phase requires approval. Otherwise
                                // auto-complete. Without this check, TASK_FORCE phases
                                // (requiresApproval=false) strand tasks in AWAITING_APPROVAL.
                                if (taskLevelHitl && phase.requiresApproval()) {
                                    taskList.submitForApproval(task.id(), entry.content());
                                } else {
                                    taskList.completeTask(task.id(), entry.content());
                                }
                            }

                            if (listener != null) {
                                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                                        member.agentId(), member.displayName(), entry.content(), phaseIdx, phase.name()));
                            }

                        } catch (MemberTurnCancelledException e) {
                            // Wave aborted while this turn was waiting — leave the group
                            // document alone; the reset sweep reclaims the task.
                            break;
                        } catch (GroupDiscussionException e) {
                            // Quota errors are non-retryable — abort all tasks immediately.
                            // Checked before the cancellation guard so a quota breach is still
                            // reported even when the wave is already unwinding.
                            if (e.getCause() instanceof QuotaExceededException) {
                                errors.add(e);
                                return; // exit the entire agent's CompletableFuture
                            }
                            // The check AND the write go under the monitor, exactly like the
                            // success path above.
                            //
                            // The tempting argument for reading the token unlocked is that
                            // abortWave is cancel() THEN allOf(futures).get(...), so the reset
                            // sweep cannot begin until every worker has returned. That argument
                            // does NOT hold: the join is bounded by
                            // memberTurnCancelDrainSeconds and proceeds to
                            // resetStrandedInProgressTasks on timeout — and that timeout is
                            // reachable, because a member parked in tryResolveMemberToolPause
                            // or in a nested GROUP discuss() never observes the token (see
                            // memberTurnCancelDrainSeconds). A late failure write can
                            // therefore race the sweep, marking a task FAILED that the sweep
                            // just reset and appending an error entry to a finished wave.
                            // Only the monitor orders the two. Lock order: taskList -> transcript.
                            boolean recorded;
                            synchronized (taskList) {
                                recorded = !cancellation.isCancelled();
                                if (recorded) {
                                    recordTaskFailure(gc, task, member, e.getMessage(), phaseIdx, phase, errors, e);
                                }
                            }
                            if (!recorded) {
                                break; // no writes after the orchestrator gave up on this wave
                            }
                            // Outside the monitor: the listener is an SSE sink, and holding
                            // taskList across a client write would stall sibling workers. The
                            // success path emits its event outside the lock for the same reason.
                            notifyTaskFailure(listener, member, e.getMessage(), phaseIdx, phase);
                            if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
                                break;
                            }
                        } catch (IllegalStateException e) {
                            // H5 fix: catch status transition errors (e.g., double completion)
                            LOGGER.warnf("Task state error for '%s': %s", task.subject(), e.getMessage());
                            boolean recorded;
                            synchronized (taskList) { // see the reasoning above
                                recorded = !cancellation.isCancelled();
                                if (recorded) {
                                    recordTaskFailure(gc, task, member, e.getMessage(), phaseIdx, phase, errors,
                                            new GroupDiscussionException(e.getMessage(), e));
                                }
                            }
                            if (!recorded) {
                                break;
                            }
                            notifyTaskFailure(listener, member, e.getMessage(), phaseIdx, phase);
                        }
                    }
                }), executorService);
                futures.add(future);
            }

            // Wait for this wave. The budget is per-member-turn × the longest task
            // chain one agent holds, and the per-turn figure comes from
            // parallelBatchBudgetSeconds — NOT from a bare agentTimeoutSeconds.
            //
            // A bare timeout was wrong in both directions. It ignored retries, so
            // under onAgentFailure=RETRY a member legitimately consuming
            // timeout × (maxRetries + 1) blew a deadline sized for one attempt and
            // the wave aborted mid-flight; and it carried no setup grace, so even
            // with one task and no retries the orchestrator's clock (armed at
            // dispatch) could expire while the member was still inside its own
            // budget, because a member turn reaches responseFuture.get() only after
            // agent lookup, conversation start and attachment grants. That is the
            // exact defect PARALLEL_BATCH_GRACE_FLOOR_SECONDS exists to prevent, and
            // both the debate batch (PhaseExecutionEngine) and the bid round below
            // already size themselves this way.
            int maxTasksPerAgent = tasksByAgent.values().stream().mapToInt(List::size).max().orElse(1);
            long waveBudgetSeconds = waveBudgetSeconds(protocol, maxTasksPerAgent);
            try {
                var allOf = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
                // NEW-3: Register the blocking future so IMMEDIATE cancel can interrupt.
                // Re-check the signal AFTER registering: a CANCEL_IMMEDIATE that landed
                // while this future was being built cancelled only the previous handle,
                // so cancel it here too — otherwise the wave blocks in get() until the
                // timeout despite the cancel already having been requested.
                if (token != null) {
                    token.setActiveFuture(allOf);
                    if (token.isCancelled()) {
                        allOf.cancel(true);
                    }
                }
                allOf.get(waveBudgetSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOGGER.warnf("Task execution timed out for group %s (wave %d)",
                        LogSanitizer.sanitize(gc.getGroupId()), wave + 1);
                abortWave(gc, futures, cancellation, "wave timeout");
                break;
            } catch (CancellationException e) {
                // R2: CANCEL_IMMEDIATE fires allOf.cancel(true) → CancellationException.
                // allOf.cancel does not propagate to the source futures — and cancelling
                // those would not stop their bodies either — so abort cooperatively.
                LOGGER.infof("Wave cancelled via CANCEL_IMMEDIATE for group %s (wave %d)",
                        LogSanitizer.sanitize(gc.getGroupId()), wave + 1);
                abortWave(gc, futures, cancellation, "wave cancellation");
                break;
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.warnf("Task execution error for group %s: %s",
                        LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(e.getMessage()));
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                abortWave(gc, futures, cancellation, "wave error");
                break;
            }

            // Quota errors always abort, regardless of onAgentFailure policy
            for (GroupDiscussionException error : errors) {
                if (error.getCause() instanceof QuotaExceededException) {
                    throw error;
                }
            }

            // If ABORT policy and there were errors, stop further waves
            if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT && !errors.isEmpty()) {
                throw errors.getFirst();
            }

            if (turnCounter.get() >= maxTurns) {
                break;
            }
        }

        // Final error propagation after all waves (ABORT policy)
        if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT && !errors.isEmpty()) {
            throw errors.getFirst();
        }
    }

    /**
     * Wall-clock budget one EXECUTE wave gets before the orchestrator gives up on
     * the agents still running.
     * <p>
     * Derived from {@link GroupConversationService#parallelBatchBudgetSeconds} —
     * the per-member-turn figure that already accounts for retries and setup grace
     * — multiplied by the longest task chain any single agent holds, since one
     * agent runs its own tasks sequentially.
     * <p>
     * Extracted so the derivation is assertable without timing a real wave. It was
     * previously an inline {@code agentTimeoutSeconds × maxTasksPerAgent}, which
     * under-budgeted a RETRY wave by a factor of {@code maxRetries + 1} and carried
     * no setup grace at all.
     *
     * @param maxTasksPerAgent
     *            the largest number of tasks assigned to one agent in this wave;
     *            values below 1 are treated as 1
     */
    static long waveBudgetSeconds(ProtocolConfig protocol, int maxTasksPerAgent) {
        long perTurn = GroupConversationService.parallelBatchBudgetSeconds(protocol);
        return perTurn * Math.max(1, maxTasksPerAgent);
    }

    /**
     * Atomically reserve one turn from the shared budget.
     * <p>
     * A check-then-act ({@code turnCounter.get() >= maxTurns} followed by
     * {@code incrementAndGet()}) lets all N member threads of a parallel wave pass
     * the check on the last remaining turn and overshoot {@code maxTurns} by up to
     * N-1 LLM calls. The CAS loop below hands out at most {@code maxTurns} turns in
     * total, no matter how many threads race for them.
     * <p>
     * Callers of this method already hold the {@code taskList} monitor (see
     * {@link #executeTaskExecutionPhase}), so this is not itself a source of the
     * race it prevents — it exists because {@code AtomicInteger} alone cannot make
     * "check budget, then claim a task" atomic across threads.
     *
     * @return {@code true} if a turn was reserved, {@code false} if the budget is
     *         exhausted
     */
    public static boolean reserveTurn(AtomicInteger turnCounter, int maxTurns) {
        while (true) {
            int current = turnCounter.get();
            if (current >= maxTurns) {
                return false;
            }
            if (turnCounter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Aborts a wave of member turns and reclaims what they left behind.
     * <p>
     * Order matters: signal cooperative cancellation first (the futures' own
     * {@code cancel(true)} would not stop their bodies), then give the member
     * threads a bounded moment to unwind, and only then sweep the task list.
     * Sweeping while a member thread is still running is what strands a task
     * permanently IN_PROGRESS — the thread flips it after the sweep has passed it.
     */
    private void abortWave(GroupConversation gc, List<CompletableFuture<Void>> futures,
                           MemberTurnCancellation cancellation, String cause) {
        cancellation.cancel();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(memberTurnCancelDrainSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException | CancellationException e) {
            // A turn that has not reached its next await point yet still cannot write:
            // every write is gated on the cancellation token under the task-list monitor.
            LOGGER.debugf("Member turns of group %s did not unwind within %ds after %s",
                    LogSanitizer.sanitize(gc.getId()), memberTurnCancelDrainSeconds, cause);
        }
        resetStrandedInProgressTasks(gc, cause);
    }

    /**
     * Resets tasks stranded IN_PROGRESS by an aborted wave back to ASSIGNED.
     * Without this, a TASK-level pause committed after the abort persists tasks
     * that {@code findExecutableTasks} can never pick up again — they and their
     * dependents would silently never execute after resume (F11).
     * <p>
     * The scan and the resets run under the task list's own monitor (the same one
     * {@link SharedTaskList}'s synchronized methods use), so the sweep is a
     * compare-and-set on each task's live state rather than on a stale snapshot: a
     * member turn can neither start a task in the middle of the sweep nor complete
     * one between the scan and the reset.
     */
    public void resetStrandedInProgressTasks(GroupConversation gc, String cause) {
        final SharedTaskList taskList = gc.getTaskList();
        if (taskList == null) {
            return;
        }
        synchronized (taskList) {
            for (TaskItem task : taskList.all()) {
                if (task.status() != TaskStatus.IN_PROGRESS) {
                    continue;
                }
                try {
                    taskList.resetToAssigned(task.id());
                    LOGGER.infof("Reset stranded task '%s' to ASSIGNED after %s", task.id(), cause);
                } catch (Exception ex) {
                    LOGGER.warnf("Failed to reset task '%s': %s", task.id(), ex.getMessage());
                }
            }
        }
    }

    /**
     * VERIFY phase: The moderator reviews all completed tasks and provides
     * pass/fail assessments. Results are parsed and applied to the task list.
     */
    public void executeTaskVerificationPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                             DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                             GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        // I1: VERIFY runs a paid verifier turn, so it needs the same pre-turn gate
        // the EXECUTE wave loop has — otherwise a discussion whose budget is already
        // blown still pays for verification.
        if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
            return;
        }

        if (gc.getTaskList() == null || gc.getTaskList().isEmpty()) {
            LOGGER.warn("VERIFY phase: no tasks to verify");
            return;
        }

        List<TaskItem> completedTasks = gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED)
                .toList();

        if (completedTasks.isEmpty()) {
            LOGGER.warn("VERIFY phase: no completed tasks to verify");
            gc.getTranscript().add(new TranscriptEntry(
                    "system", "System", "No completed tasks to verify",
                    phaseIdx, phase.name(), TranscriptEntryType.VERIFICATION,
                    Instant.now(), null, null));
            return;
        }

        if (speakers.isEmpty()) {
            LOGGER.warn("VERIFY phase: no verifier available");
            return;
        }

        GroupMember verifier = speakers.getFirst();
        turnCounter.incrementAndGet();

        if (listener != null) {
            listener.onSpeakerStart(
                    new GroupConversationEventSink.SpeakerStartEvent(verifier.agentId(), verifier.displayName(), phaseIdx, phase.name()));
        }

        // Build verification input
        String verifyTemplate = DiscussionStylePresets.defaultTemplate(PhaseType.VERIFY);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("displayName", verifier.displayName());
        List<Map<String, Object>> taskData = completedTasks.stream().map(t -> {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("subject", t.subject());
            td.put("description", t.description());
            td.put("assignedDisplayName", t.assignedDisplayName());
            td.put("result", t.result() != null ? t.result() : "(no result)");
            return td;
        }).collect(Collectors.toList());
        data.put("completedTasks", taskData);

        String verifyInput;
        try {
            verifyInput = templatingEngine.processTemplate(verifyTemplate, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            verifyInput = "Review the task results and provide pass/fail for each task.";
        }

        TranscriptEntry verifyEntry = memberTurnExecutor.executeAgentTurn(verifier, gc, verifyInput, protocol, phaseIdx, phase, null, listener);

        // Parse verification results — same three-tier fallback
        parseAndApplyVerification(gc, completedTasks, verifyEntry.content(), listener);

        // Replace raw JSON with a human-readable summary for the transcript.
        // The JSON was needed for parseAndApplyVerification above; users should
        // see formatted pass/fail results, not raw JSON.
        String formattedContent = formatVerificationForDisplay(verifyEntry.content());
        TranscriptEntry displayEntry = new TranscriptEntry(
                verifyEntry.speakerAgentId(), verifyEntry.speakerDisplayName(),
                formattedContent, verifyEntry.phaseIndex(), verifyEntry.phaseName(),
                verifyEntry.type(), verifyEntry.timestamp(), verifyEntry.errorReason(),
                verifyEntry.targetAgentId());
        gc.getTranscript().add(displayEntry);

        if (listener != null) {
            listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                    verifier.agentId(), verifier.displayName(), formattedContent, phaseIdx, phase.name()));
        }
    }

    /**
     * Builds the input message for a task execution phase, respecting the
     * configured context scope.
     */
    public String buildTaskExecutionInput(TaskItem task, String question, DiscussionPhase phase, GroupConversation gc) {
        String template = DiscussionStylePresets.defaultTemplate(PhaseType.EXECUTE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("taskSubject", task.subject());
        data.put("taskDescription", task.description());

        // Add dependency results if scope is TASK_WITH_DEPS
        if (phase.contextScope() == ContextScope.TASK_WITH_DEPS && gc.getTaskList() != null) {
            List<Map<String, Object>> depResults = task.dependsOnIds().stream()
                    .map(depId -> gc.getTaskList().findById(depId))
                    .filter(dep -> dep != null && dep.result() != null)
                    .map(dep -> {
                        Map<String, Object> dr = new LinkedHashMap<>();
                        dr.put("subject", dep.subject());
                        dr.put("result", dep.result());
                        return dr;
                    }).collect(Collectors.toList());
            if (!depResults.isEmpty()) {
                data.put("dependencyResults", depResults);
            }
        }

        String input;
        try {
            input = templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for task execution, using plain text: %s", e.getMessage());
            input = "Task: " + task.subject() + "\n" + task.description();
        }
        // RETRY rejection policy: surface the reviewer's rejection feedback so the
        // re-executing agent addresses it instead of reproducing the same output
        if (task.verificationNote() != null && !task.verificationNote().isBlank()) {
            input += "\n\nReviewer feedback on the previous attempt (address this): " + task.verificationNote();
        }
        return input;
    }

    /**
     * Parses verification output and applies pass/fail to the task list. Falls back
     * to marking all tasks as passed if parsing fails (safe default).
     */
    public void parseAndApplyVerification(GroupConversation gc, List<TaskItem> completedTasks,
                                          String verifyContent, GroupDiscussionEventListener listener) {
        // H4 fix: dedicated verification parser that reads 'passed' boolean from JSON
        try {
            if (verifyContent != null && verifyContent.contains("[")) {
                if (tryParseVerificationJson(gc, completedTasks, verifyContent, listener)) {
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Failed to parse verification output, marking all as passed: %s", e.getMessage());
        }

        // Fallback: mark all still-completed tasks as verified (safe default).
        //
        // Status is re-read live rather than taken from `completedTasks`, which is a
        // snapshot taken before the verification phase ran, and TaskItem is
        // immutable — so a task the JSON pass already moved to VERIFIED/FAILED still
        // reads COMPLETED here. tryParseVerificationJson can verify several tasks and
        // *then* throw (a verifier LLM repeating the same subject twice is enough:
        // the second match re-verifies an already-VERIFIED task), its catch returns
        // false, and control lands in this loop with those tasks already terminal.
        // Re-verifying them trips verifyTask's requireStatus(COMPLETED) guard, and
        // since this loop sits outside the try above, that IllegalStateException
        // escapes parseAndApplyVerification and executeTaskVerificationPhase — losing
        // the verifier's transcript entry and its onSpeakerComplete event entirely.
        for (TaskItem task : completedTasks) {
            TaskItem live = gc.getTaskList().findById(task.id());
            if (live == null || live.status() != TaskStatus.COMPLETED) {
                continue; // already verified/failed by the JSON pass, or gone
            }
            gc.getTaskList().verifyTask(live.id(), true, "Auto-verified (verification parse failed)");
            if (listener != null) {
                listener.onTaskVerified(new GroupConversationEventSink.TaskVerifiedEvent(
                        live.id(), live.subject(), true, "Auto-verified"));
            }
        }
    }

    /**
     * Attempts to parse verification results from JSON. The expected schema is:
     * {@code [{"subject": "...", "passed": true, "feedback": "..."}]}
     *
     * @return true if parsing succeeded and at least one task was verified
     */
    @SuppressWarnings("unchecked")
    public boolean tryParseVerificationJson(GroupConversation gc, List<TaskItem> completedTasks,
                                            String content, GroupDiscussionEventListener listener) {
        try {
            // Extract JSON array from content (may be wrapped in markdown fences)
            int jsonStart = content.indexOf('[');
            int jsonEnd = content.lastIndexOf(']');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return false;
            }
            String json = content.substring(jsonStart, jsonEnd + 1);

            var items = jsonSerialization.deserialize(json, List.class);
            if (items == null || items.isEmpty()) {
                return false;
            }

            boolean anyVerified = false;
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    // Test the value, not the key: a JSON "subject": null satisfies
                    // containsKey, and String.valueOf then yields the literal "null" —
                    // which is not null, so the guard below waves it through to a task
                    // match that can never succeed, silently dropping the verification.
                    String subject = stringOrNull(map.get("subject"));
                    // Read 'passed' boolean directly from JSON
                    boolean passed = true; // default to passed
                    if (map.containsKey("passed")) {
                        Object passedVal = map.get("passed");
                        passed = Boolean.TRUE.equals(passedVal) || "true".equalsIgnoreCase(String.valueOf(passedVal));
                    }
                    String feedback = stringOrNull(map.get("feedback"));

                    if (subject != null) {
                        for (TaskItem task : completedTasks) {
                            if (!task.subject().equalsIgnoreCase(subject)) {
                                continue;
                            }
                            // Live status, not the snapshot's: `completedTasks` was
                            // captured before this phase, TaskItem is immutable, and a
                            // verifier repeating the same subject twice would otherwise
                            // re-verify an already-VERIFIED task and trip verifyTask's
                            // requireStatus guard — aborting the whole parse mid-way and
                            // dropping every verdict after it.
                            TaskItem live = gc.getTaskList().findById(task.id());
                            if (live == null || live.status() != TaskStatus.COMPLETED) {
                                break;
                            }
                            gc.getTaskList().verifyTask(live.id(), passed, feedback);
                            if (listener != null) {
                                listener.onTaskVerified(new GroupConversationEventSink.TaskVerifiedEvent(
                                        live.id(), live.subject(), passed, feedback));
                            }
                            anyVerified = true;
                            break;
                        }
                    }
                }
            }
            return anyVerified;
        } catch (Exception e) {
            LOGGER.debugf("Verification JSON parse failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * A map value as a string, or {@code null} when the key is absent <em>or</em>
     * explicitly null.
     * <p>
     * {@code containsKey} is true for a JSON {@code "subject": null}, and
     * {@code String.valueOf} turns that into the literal four-character string
     * "null" — which then passes every {@code != null} guard and reaches users as a
     * task named "null". Deserialized LLM output is exactly where that happens.
     *
     * @param value
     *            a value read from a deserialized JSON map
     * @return its string form, or null
     */
    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Converts raw verification output (typically JSON) into a human-readable
     * summary suitable for display in the UI. Falls back to the raw content if
     * formatting fails.
     */
    @SuppressWarnings("unchecked")
    public String formatVerificationForDisplay(String rawContent) {
        if (rawContent == null || !rawContent.contains("[")) {
            return rawContent;
        }

        try {
            int jsonStart = rawContent.indexOf('[');
            int jsonEnd = rawContent.lastIndexOf(']');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return rawContent;
            }
            String json = rawContent.substring(jsonStart, jsonEnd + 1);
            var items = jsonSerialization.deserialize(json, List.class);
            if (items == null || items.isEmpty()) {
                return rawContent;
            }

            var sb = new StringBuilder("## Task Verification Results\n\n");
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    // Test the value, not the key — otherwise a JSON "subject": null
                    // renders to the user as the literal word "null" instead of
                    // falling back to "Unknown Task".
                    String subject = Objects.requireNonNullElse(stringOrNull(map.get("subject")), "Unknown Task");
                    boolean passed = true;
                    if (map.containsKey("passed")) {
                        Object passedVal = map.get("passed");
                        passed = Boolean.TRUE.equals(passedVal) || "true".equalsIgnoreCase(String.valueOf(passedVal));
                    }
                    String feedback = Objects.requireNonNullElse(stringOrNull(map.get("feedback")), "");

                    sb.append(passed ? "✅" : "❌").append(" **").append(subject).append("**: ");
                    sb.append(passed ? "Passed" : "Failed").append("\n");
                    if (!feedback.isBlank()) {
                        sb.append(feedback).append("\n");
                    }
                    sb.append("\n");
                }
            }

            // Append any text outside the JSON (e.g. "Overall Assessment: ...")
            String afterJson = rawContent.substring(jsonEnd + 1).trim();
            // Strip markdown code fence closing if present
            if (afterJson.startsWith("```")) {
                afterJson = afterJson.substring(3).trim();
            }
            if (!afterJson.isBlank()) {
                sb.append(afterJson);
            }

            return sb.toString().trim();
        } catch (Exception e) {
            LOGGER.debugf("Failed to format verification for display: %s", e.getMessage());
            return rawContent;
        }
    }

    // --- I18: announce-bid-award (CNP-lite) ---

    /**
     * Runs one blind, parallel bid round over this wave's unassigned BID-mode tasks
     * (I18). Eligible members receive the announced batch with NO transcript and NO
     * peer bids (blindness is what makes the self-assessed confidences comparable);
     * each task goes to the highest confidence, deterministic tie-break by speaking
     * order; a task nobody bid on falls back to the ROLE path — an auction must
     * never stall a wave.
     * <p>
     * Skips itself (with a log — a silent cap reads as coverage) when it cannot
     * beat its own overhead: fewer than {@link TaskBidEngine#MIN_BIDDERS} eligible
     * members or {@link TaskBidEngine#MIN_TASKS} unassigned tasks, or a turn budget
     * too small for one bid turn per member.
     */
    void runBidRoundIfNeeded(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                             ProtocolConfig protocol, String question, int phaseIdx,
                             GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns) {
        SharedTaskList taskList = gc.getTaskList();
        List<TaskItem> unassigned = taskList.findExecutableTasks().stream()
                .filter(t -> t.assignedAgentId() == null)
                .filter(t -> TaskBidEngine.effectiveMode(taskDefinitionFor(config, t), config) == AgentGroupConfiguration.AssignmentMode.BID)
                .toList();
        if (unassigned.isEmpty()) {
            return;
        }
        List<GroupMember> bidders = config.getMembers().stream()
                .filter(m -> m != null && m.memberType() == AgentGroupConfiguration.MemberType.AGENT)
                .filter(m -> !m.agentId().equals(config.getModeratorAgentId()))
                .toList();

        if (!TaskBidEngine.auctionWorthwhile(bidders.size(), unassigned.size())) {
            LOGGER.infof("Group %s: skipping bid round (%d bidder(s), %d unassigned task(s) — the auction cannot beat "
                    + "its own overhead); falling back to ROLE assignment",
                    LogSanitizer.sanitize(gc.getGroupId()), bidders.size(), unassigned.size());
            fallbackRoleAssign(gc, config, unassigned);
            return;
        }
        if (maxTurns > 0 && turnCounter.get() + bidders.size() > maxTurns) {
            LOGGER.infof("Group %s: skipping bid round — %d bid turn(s) would exceed the remaining turn budget; "
                    + "falling back to ROLE assignment", LogSanitizer.sanitize(gc.getGroupId()), bidders.size());
            fallbackRoleAssign(gc, config, unassigned);
            return;
        }
        turnCounter.addAndGet(bidders.size());

        var announcedSubjects = unassigned.stream().map(TaskItem::subject).collect(Collectors.toSet());
        var cancellation = new MemberTurnCancellation();
        final var bidCaller = callerIdentityContext.captureOrCurrent();
        List<CompletableFuture<List<TaskBidEngine.Bid>>> futures = bidders.stream()
                .map(member -> CompletableFuture.supplyAsync(callerIdentityContext.withIdentitySupplying(bidCaller, () -> {
                    try {
                        String prompt = TaskBidEngine.buildBidPrompt(unassigned, member, question);
                        TranscriptEntry reply = memberTurnExecutor.executeAgentTurn(member, gc, prompt, protocol,
                                phaseIdx, phase, null, listener, cancellation);
                        if (reply != null && reply.content() != null && !reply.content().isBlank()) {
                            // Recorded as a BID entry — peer-hidden while the phase
                            // runs (F4's blind-bid rule), auditable afterwards.
                            gc.getTranscript().add(new TranscriptEntry(member.agentId(), member.displayName(),
                                    reply.content(), phaseIdx, phase.name(), TranscriptEntryType.BID,
                                    Instant.now(), null, null));
                        }
                        return TaskBidEngine.parseBids(reply != null ? reply.content() : null, member, announcedSubjects);
                    } catch (Exception e) {
                        LOGGER.warnf("Bid turn failed for '%s': %s — casting no bids",
                                LogSanitizer.sanitize(member.agentId()), LogSanitizer.sanitize(e.getMessage()));
                        return List.<TaskBidEngine.Bid>of();
                    }
                }), executorService)).toList();

        List<TaskBidEngine.Bid> allBids = new ArrayList<>();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(GroupConversationService.parallelBatchBudgetSeconds(protocol));
        for (CompletableFuture<List<TaskBidEngine.Bid>> future : futures) {
            try {
                long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
                allBids.addAll(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                cancellation.cancel();
                LOGGER.warnf("Group %s: bid round timed out — late bidders cast no bids",
                        LogSanitizer.sanitize(gc.getGroupId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancellation.cancel();
                break;
            } catch (ExecutionException e) {
                LOGGER.warnf("Group %s: bid future failed: %s",
                        LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(e.getMessage()));
            }
        }

        Map<String, SharedTaskList.AwardedBid> awards = TaskBidEngine.award(unassigned, allBids);
        synchronized (taskList) {
            for (TaskItem task : unassigned) {
                SharedTaskList.AwardedBid winner = awards.get(task.id());
                if (winner != null) {
                    GroupMember member = findMemberIncludingDynamic(config.getMembers(), gc, winner.agentId());
                    taskList.assignTask(task.id(), winner.agentId(),
                            member != null ? member.displayName() : winner.agentId());
                    taskList.recordAwardedBid(task.id(), winner);
                    LOGGER.infof("Group %s: task '%s' awarded to '%s' (confidence %.2f)",
                            LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(task.subject()),
                            LogSanitizer.sanitize(winner.agentId()), winner.confidence());
                }
            }
        }
        // Tasks nobody bid on fall back to ROLE — never stall the wave on silence.
        fallbackRoleAssign(gc, config, unassigned.stream()
                .filter(t -> !awards.containsKey(t.id()))
                .toList());
    }

    /**
     * The TaskDefinition a task was created from, matched by subject; null for
     * planned/filed tasks.
     */
    private TaskDefinition taskDefinitionFor(AgentGroupConfiguration config, TaskItem task) {
        if (config.getTasks() == null) {
            return null;
        }
        return config.getTasks().stream()
                .filter(td -> td.subject().equalsIgnoreCase(task.subject()))
                .findFirst().orElse(null);
    }

    /**
     * ROLE/round-robin assignment for tasks the auction did not (or could not)
     * award.
     */
    private void fallbackRoleAssign(GroupConversation gc, AgentGroupConfiguration config, List<TaskItem> tasks) {
        SharedTaskList taskList = gc.getTaskList();
        synchronized (taskList) {
            for (int i = 0; i < tasks.size(); i++) {
                TaskItem task = tasks.get(i);
                TaskDefinition td = taskDefinitionFor(config, task);
                String agentId = resolveAssignee(td != null ? td.assignToRole() : null,
                        config.getMembers(), config.getModeratorAgentId(), i);
                if (agentId != null) {
                    GroupMember member = findMember(config.getMembers(), agentId);
                    taskList.assignTask(task.id(), agentId, member != null ? member.displayName() : agentId);
                } else {
                    LOGGER.warnf("Group %s: no fallback assignee for task '%s'",
                            LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(task.subject()));
                }
            }
        }
    }

    // --- Task assignment helpers ---

    /**
     * Resolves task assignment. For "ALL" role, uses round-robin across
     * non-moderator members to distribute tasks evenly (H3 fix).
     *
     * @param taskIndex
     *            index of the task in the list, used for round-robin distribution
     */
    public String resolveTaskAssignment(String assignToRole, List<GroupMember> members,
                                        String moderatorAgentId, int taskIndex) {
        return resolveAssignee(assignToRole, members, moderatorAgentId, taskIndex);
    }

    /**
     * The same resolution, reachable without an engine instance (I5).
     * <p>
     * {@code GroupTaskTools} has to answer "who does this role mean?" for an
     * agent-filed task, and it runs in the tool layer where no engine exists. The
     * instance method above stays as the call site every existing caller and
     * characterization test already uses; this is one implementation, not a second
     * one, so the loop's assignment and a filed task's assignment cannot drift
     * apart.
     */
    public static String resolveAssignee(String assignToRole, List<GroupMember> members,
                                         String moderatorAgentId, int taskIndex) {
        if (assignToRole == null || "ALL".equalsIgnoreCase(assignToRole)) {
            // Round-robin across non-moderator members (H3 fix)
            List<GroupMember> eligible = members.stream()
                    .filter(m -> !m.agentId().equals(moderatorAgentId))
                    .toList();
            if (eligible.isEmpty()) {
                return members.isEmpty() ? null : members.getFirst().agentId();
            }
            return eligible.get(taskIndex % eligible.size()).agentId();
        }
        if (assignToRole.toUpperCase().startsWith("ROLE:")) {
            String role = assignToRole.substring(5).trim();
            return members.stream()
                    .filter(m -> role.equalsIgnoreCase(m.role()))
                    .map(GroupMember::agentId)
                    .findFirst()
                    .orElse(null);
        }
        // Direct agentId reference
        return TaskListParser.resolveAgent(assignToRole, members);
    }

    /**
     * The document-mutating half of a task failure: fail the task and append the
     * error entry. Callers MUST hold the task-list monitor so this write is ordered
     * against {@code abortWave}'s reset sweep — see the call sites for why the
     * cancel-then-join ordering does not suffice on its own.
     * <p>
     * Split from {@link #notifyTaskFailure} deliberately: the listener is an SSE
     * sink and must not be invoked while holding the monitor.
     */
    public void recordTaskFailure(GroupConversation gc, TaskItem task, GroupMember member,
                                  String errorMessage, int phaseIdx, DiscussionPhase phase,
                                  List<GroupDiscussionException> errors, GroupDiscussionException ex) {
        try {
            gc.getTaskList().failTask(task.id(), errorMessage);
        } catch (IllegalStateException ise) {
            LOGGER.debugf("Could not fail task '%s' (already terminal): %s", task.id(), ise.getMessage());
        }

        // Add error transcript entry
        synchronized (gc.getTranscript()) {
            gc.getTranscript().add(new TranscriptEntry(
                    member.agentId(), member.displayName(),
                    "[ERROR] Task '%s' failed: %s".formatted(task.subject(), errorMessage),
                    phaseIdx, phase.name(), TranscriptEntryType.TASK_RESULT,
                    Instant.now(), null, null));
        }

        errors.add(ex);
    }

    /**
     * Emit the failure to SSE clients. Called OUTSIDE the task-list monitor.
     */
    private void notifyTaskFailure(GroupDiscussionEventListener listener, GroupMember member,
                                   String errorMessage, int phaseIdx, DiscussionPhase phase) {
        if (listener != null) {
            listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                    member.agentId(), member.displayName(),
                    "[ERROR] " + errorMessage, phaseIdx, phase.name()));
        }
    }

    public GroupMember findMember(List<GroupMember> members, String agentId) {
        if (agentId == null)
            return null;
        return members.stream()
                .filter(m -> agentId.equals(m.agentId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find a member by agentId, searching both static config members and
     * dynamically added members from the conversation.
     */
    public GroupMember findMemberIncludingDynamic(List<GroupMember> configMembers, GroupConversation gc, String agentId) {
        GroupMember member = findMember(configMembers, agentId);
        if (member == null && gc.getDynamicMembers() != null) {
            List<GroupMember> dynamicMembers = gc.getDynamicMembers();
            synchronized (dynamicMembers) {
                member = findMember(dynamicMembers, agentId);
            }
        }
        return member;
    }
}
