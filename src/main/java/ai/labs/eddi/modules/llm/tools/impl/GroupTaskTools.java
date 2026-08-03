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
 * loop owns every task-state transition, and a second writer racing it would
 * corrupt the state machine that decides what runs next. Assignment belongs to
 * the loop for the same reason — a filed task is PENDING, and the loop assigns
 * it like any other.
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
 * @author ginccc
 */
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
                                       + "Omit to let the team assign it.",
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
        long alreadyFiledByAgents = taskList.all().stream().filter(t -> t.createdByAgentId() != null).count();
        if (alreadyFiledByAgents >= config.maxAgentAddedTasksPerDiscussion()) {
            return "The task list is full for this discussion (%d agent-filed tasks). Finish existing tasks instead of adding more."
                    .formatted(config.maxAgentAddedTasksPerDiscussion());
        }

        String assignedAgentId = null;
        String assignedDisplayName = null;
        if (assignToRole != null && !assignToRole.isBlank() && !"ALL".equalsIgnoreCase(assignToRole.trim())) {
            // "ALL" and omission both mean "no preference", and are deliberately NOT
            // round-robined here even though the shared resolver would: round-robin
            // keys off a task INDEX the loop assigns, and an agent-filed task has no
            // position in the planned list. Leaving it PENDING lets the loop place it
            // the same way it places every other unowned task.
            final String resolved = TaskForceEngine.resolveAssignee(assignToRole.trim(), members, moderatorAgentId, 0);
            if (resolved == null) {
                return "Nobody here matches \"%s\". %s".formatted(assignToRole.trim(), rosterHint());
            }
            assignedAgentId = resolved;
            assignedDisplayName = members.stream()
                    .filter(m -> resolved.equals(m.agentId()))
                    .map(GroupMember::displayName).findFirst().orElse(resolved);
        }

        var result = taskList.addAgentTask(subject, description, dependsOnSubjects,
                priority != null ? priority : 0, agentId, assignedAgentId, assignedDisplayName);
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
        return result.task().assignedDisplayName() != null
                ? "Filed \"%s\" for %s.".formatted(result.task().subject(), result.task().assignedDisplayName())
                : "Filed \"%s\". The team will pick it up in a later round.".formatted(result.task().subject());
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
