/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.internal.groups.TaskForceEngine;
import dev.langchain4j.agent.tool.P;
import jakarta.enterprise.inject.Vetoed;
import dev.langchain4j.agent.tool.Tool;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Lets a member file work it discovers mid-discussion (I5).
 * <p>
 * The shared task list is otherwise written only by the PLAN phase and by
 * config, so a finding that arrives during EXECUTE — a missing migration, an
 * untested edge case, a dependency nobody planned for — could only be described
 * in prose and hoped for. Filing it converts TASK_FORCE from push to pull: the
 * wave loop already re-queries {@code findExecutableTasks()} every wave, so a
 * task filed now is picked up next wave with no scheduler changes.
 * <p>
 * <b>Two tools, deliberately.</b> There is no claim or complete tool: the wave
 * loop owns every task-state <em>transition</em>, and a second writer racing it
 * would corrupt the state machine that decides what runs next.
 * <p>
 * Initial assignment is the one exception, and it has to be: the EXECUTE wave
 * only schedules tasks that already have an assignee, and nothing outside the
 * PLAN phase ever assigns one. A task filed without an owner would never run —
 * so every filed task is given one here, through the same resolver the PLAN
 * phase uses.
 * <p>
 * <b>Mutates the live discussion, never the store.</b> The loop persists the
 * whole {@link GroupConversation} document after each phase, so a tool writing
 * through its own store call would be silently clobbered by the loop's next
 * stale-snapshot write. Resolving the live instance through
 * {@link LiveDiscussionRegistry} (F1) and mutating it in place is what makes
 * the write survive — and is why a discussion that is paused or finished (and
 * so unregistered) refuses the write rather than pretending to accept it.
 * <p>
 * Every refusal is a sentence addressed to the model that just called, because
 * that is who reads it. "The task list is full" with the cap and a suggested
 * next action gets a useful second attempt; a stack trace or a bare {@code
 * false} gets the same call again.
 *
 * <p>
 * Constructed per-turn with runtime values — NOT a CDI bean. {@code @Vetoed} is
 * load-bearing: the langchain4j extension registers {@code @Tool}-bearing
 * classes as beans, and Arc then tries to inject this constructor's
 * {@code String}s and {@link GroupTaskConfig}, which are not beans. That is a
 * <em>deployment</em> failure — the application does not start — so no unit
 * test can catch it. Same reason {@code ReadAttachmentTool} and
 * {@code DiscoverToolsTool} carry it.
 *
 * @author ginccc
 */
@Vetoed
public class GroupTaskTools {

    private static final Logger LOGGER = Logger.getLogger(GroupTaskTools.class);

    private final LiveDiscussionRegistry registry;
    private final String groupConversationId;
    private final GroupTaskConfig config;
    private final String agentId;
    private final List<GroupMember> members;
    private final String moderatorAgentId;

    /**
     * Counts only what THIS turn filed. A plain field is correct scoping because
     * the whole tool set is rebuilt per turn by {@code buildToolSetup}; it is
     * atomic because one turn's tool loop can issue several calls and, in a
     * PARALLEL phase, several members run at once against their own instances.
     */
    private final AtomicInteger addedThisTurn = new AtomicInteger();

    public GroupTaskTools(LiveDiscussionRegistry registry, String groupConversationId, GroupTaskConfig config, String agentId,
            List<GroupMember> members, String moderatorAgentId) {
        this.registry = registry;
        this.groupConversationId = groupConversationId;
        this.config = config;
        this.agentId = agentId;
        this.members = members != null ? List.copyOf(members) : List.of();
        this.moderatorAgentId = moderatorAgentId;
    }

    @Tool("File a new task on the group's shared task list for work you have discovered that nobody planned for. "
            + "The task is picked up by the team in a later round. Use listGroupTasks first to avoid duplicates.")
    public String addGroupTask(
                               @P("Short title, under 200 characters — how the team will refer to this task") String subject,
                               @P("What needs doing, and enough context for whoever picks it up to act without asking") String description,
                               @P(value = "Subjects of existing tasks that must finish first, exactly as listGroupTasks shows them. "
                                       + "Omit if this task can start immediately.",
                                  required = false) List<String> dependsOnSubjects,
                               @P(value = "0 = highest priority. Omit for normal.", required = false) Integer priority,
                               @P(value = "Who should own this: a role as \"ROLE:Reviewer\", or a member's exact name. "
                                       + "Omit to have it assigned to the next member in turn.",
                                  required = false) String assignToRole) {

        GroupConversation gc = liveDiscussion();
        if (gc == null) {
            return "This discussion is no longer accepting new tasks (it has finished or is paused).";
        }
        SharedTaskList taskList = gc.getTaskList();
        if (taskList == null) {
            return "This discussion has no task list to add to.";
        }

        if (addedThisTurn.get() >= config.maxPerTurn()) {
            return "You have already filed %d task(s) this turn, which is the limit. Raise the most important remaining one next round."
                    .formatted(config.maxPerTurn());
        }

        // EVERY filed task gets an owner, including when the caller expressed no
        // preference. An unassigned task is not "assigned later by the loop" — that
        // was wrong: assignTask is only ever called from the PLAN phase, and the
        // EXECUTE wave schedules `findExecutableTasks().filter(assignedAgentId !=
        // null)`. A PENDING unowned task is therefore invisible to the wave forever,
        // so the tool's promise that the team would pick it up was false, and under
        // TASK-granularity HITL the leftover executable task re-paused the phase
        // until the no-progress guard failed the whole discussion.
        //
        // "ALL"/omitted round-robins through the same resolver the PLAN phase uses,
        // indexed by the current task count so successive filings spread across the
        // team rather than piling onto one member.
        boolean noPreference = assignToRole == null || assignToRole.isBlank() || "ALL".equalsIgnoreCase(assignToRole.trim());
        String requested = noPreference ? "ALL" : assignToRole.trim();
        final String resolved = TaskForceEngine.resolveAssignee(requested, members, moderatorAgentId, taskList.size());
        if (resolved == null) {
            return noPreference
                    ? "There is nobody on this team to own a new task."
                    : "Nobody here matches \"%s\". %s".formatted(requested, rosterHint());
        }
        // displayName is nullable and unvalidated (POST /groupstore/groups accepts a
        // member with only an agentId), and Stream.findFirst() THROWS on a null
        // element — so an unnamed assignee turned this tool's guaranteed actionable
        // sentence into an executor exception, and the task was never filed. Every
        // other consumer of displayName in the codebase guards it; this did not.
        String assignedDisplayName = members.stream()
                .filter(m -> resolved.equals(m.agentId()))
                .map(m -> m.displayName() != null ? m.displayName() : m.agentId())
                .findFirst().orElse(resolved);

        var result = taskList.addAgentTask(subject, description, dependsOnSubjects,
                priority != null ? priority : 0, agentId, resolved, assignedDisplayName,
                config.maxAgentAddedTasksPerDiscussion());
        if (!result.accepted()) {
            return result.rejectionReason();
        }

        // Only a genuinely filed task counts against the turn budget — a rejected
        // call has cost the list nothing, and spending the budget on refusals would
        // let one malformed argument silence the rest of the turn.
        addedThisTurn.incrementAndGet();
        LOGGER.infof("Agent '%s' filed task '%s' on group conversation %s (owner: %s)",
                agentId, result.task().subject(), groupConversationId,
                result.task().assignedAgentId() != null ? result.task().assignedAgentId() : "unassigned");
        return "Filed \"%s\" for %s. They pick it up in the next round."
                .formatted(result.task().subject(), result.task().assignedDisplayName());
    }

    @Tool("List the group's shared tasks with their status, so you can see what is already planned before filing new work.")
    public String listGroupTasks() {
        GroupConversation gc = liveDiscussion();
        if (gc == null || gc.getTaskList() == null) {
            return "No task list is available for this discussion.";
        }
        List<TaskItem> tasks = gc.getTaskList().all();
        if (tasks.isEmpty()) {
            return "The task list is empty.";
        }
        var sb = new StringBuilder("Current tasks:\n");
        for (TaskItem t : tasks) {
            sb.append("- \"").append(t.subject()).append("\" [").append(t.status()).append(']');
            if (t.assignedDisplayName() != null) {
                sb.append(" — ").append(t.assignedDisplayName());
            }
            if (t.dependsOnIds() != null && !t.dependsOnIds().isEmpty()) {
                sb.append(" (waits on ").append(t.dependsOnIds().size()).append(" task(s))");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The in-memory instance the discussion loop is holding, or {@code null} once
     * it has been unregistered. Writing to a copy loaded from the store would be
     * overwritten by the loop's next whole-document write, so "not live" has to
     * mean "refuse", not "write somewhere else".
     */
    /**
     * Names the roles and members that exist, so a failed match gets a usable
     * retry.
     */
    private String rosterHint() {
        String roles = members.stream().map(GroupMember::role).filter(r -> r != null && !r.isBlank())
                .distinct().collect(Collectors.joining(", "));
        String names = members.stream().map(GroupMember::displayName).filter(n -> n != null)
                .collect(Collectors.joining(", "));
        if (!roles.isBlank()) {
            return "Roles on this team: %s. Members: %s.".formatted(roles, names);
        }
        return names.isBlank() ? "This team has no members to assign to." : "Members: %s.".formatted(names);
    }

    private GroupConversation liveDiscussion() {
        return registry.get(groupConversationId).orElse(null);
    }
}
