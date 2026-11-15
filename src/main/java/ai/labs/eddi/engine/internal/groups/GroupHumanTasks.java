/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * HUMAN members as task-force executors: which tasks are theirs, when the wave
 * loop pauses for one, and what their answer does to the task.
 * <p>
 * <b>Agents first, then one human task per pause.</b> The EXECUTE wave loop
 * leaves HUMAN-assigned tasks out of its waves, so a person never sits inside a
 * wave's future and the wave budget is unchanged. When no agent task is left to
 * run but a human task is executable, the loop raises a
 * {@link PhaseExecutionEngine.HumanTurnRequired} for the highest-priority one —
 * the I6 pause the phase dispatch already commits. The answer completes the
 * task (or submits it for approval under TASK-granularity HITL), and the
 * resumed wave loop runs whatever depended on it.
 * <p>
 * <b>Assignment is explicit only.</b> Round-robin and bids never pick a HUMAN
 * member; a task reaches one through {@code assignToRole}, its principal id, an
 * agent-filed task or the planner naming them.
 */
public final class GroupHumanTasks {

    private static final Logger LOGGER = Logger.getLogger(GroupHumanTasks.class);

    private GroupHumanTasks() {
    }

    /** Whether {@code member} is a HUMAN member. */
    public static boolean isHuman(GroupMember member) {
        return member != null && member.memberType() == MemberType.HUMAN;
    }

    /** Whether {@code agentId} names one of the group's HUMAN members. */
    public static boolean isHumanAssignee(AgentGroupConfiguration config, String agentId) {
        return agentId != null && config != null && config.getMembers() != null
                && config.getMembers().stream().anyMatch(m -> isHuman(m) && agentId.equals(m.agentId()));
    }

    /** The members round-robin may choose from: every member but the HUMAN ones. */
    public static List<GroupMember> nonHuman(List<GroupMember> members) {
        return members == null ? List.of() : members.stream().filter(Objects::nonNull).filter(m -> !isHuman(m)).toList();
    }

    /**
     * The executable task a HUMAN member owes next: lowest priority number first,
     * then creation order.
     */
    public static Optional<TaskItem> nextHumanTask(AgentGroupConfiguration config, SharedTaskList taskList) {
        if (taskList == null) {
            return Optional.empty();
        }
        return taskList.findExecutableTasks().stream()
                .filter(t -> isHumanAssignee(config, t.assignedAgentId()))
                .min(Comparator.comparingInt(TaskItem::priority).thenComparing(TaskItem::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * The pause signal for a human task: the prompt is the same task input an agent
     * would get, and the detail ties the answer to the task.
     */
    public static PhaseExecutionEngine.HumanTurnRequired humanTurnFor(GroupMember member, TaskItem task, String renderedPrompt) {
        var detail = new HumanRequestDetail(HumanRequestDetail.KIND_TASK, List.of(), task.id(), null, null);
        return new PhaseExecutionEngine.HumanTurnRequired(member, -1, renderedPrompt, false, detail);
    }

    /**
     * Applies a human task's answer to its task, before the resolution's
     * compare-and-swap so the persisted document is consistent: a submission
     * completes the task (or submits it for approval when the phase gates tasks), a
     * timeout fails it. A no-op for any other pending input.
     *
     * @return the task as it was before, for {@link #restore} on a rollback — or
     *         {@code null} when nothing was changed
     */
    public static TaskItem applyResult(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                       GroupConversation.PendingHumanInput pending, String content, String skipReason) {
        HumanRequestDetail detail = pending != null ? pending.detail() : null;
        if (detail == null || !detail.isTask() || gc.getTaskList() == null) {
            return null;
        }
        SharedTaskList taskList = gc.getTaskList();
        synchronized (taskList) {
            TaskItem before = taskList.findById(detail.taskId());
            if (before == null || (before.status() != SharedTaskList.TaskStatus.ASSIGNED
                    && before.status() != SharedTaskList.TaskStatus.IN_PROGRESS)) {
                LOGGER.warnf("Group %s: the human task %s is no longer open — recording the answer without changing it",
                        LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(detail.taskId()));
                return null;
            }
            if (skipReason != null) {
                taskList.failTask(before.id(), skipReason);
                return before;
            }
            if (before.status() == SharedTaskList.TaskStatus.ASSIGNED) {
                taskList.startTask(before.id());
            }
            boolean gated = config != null && config.getHitlConfig() != null
                    && config.getHitlConfig().getGranularity() == HitlGranularity.TASK && phase.requiresApproval();
            if (gated) {
                taskList.submitForApproval(before.id(), content);
            } else {
                taskList.completeTask(before.id(), content);
            }
            return before;
        }
    }

    /** Puts a task back as {@link #applyResult} found it. */
    public static void restore(GroupConversation gc, TaskItem before) {
        if (before != null && gc.getTaskList() != null) {
            gc.getTaskList().updateTask(before);
        }
    }

    /**
     * The failed tasks a VERIFY rework may send back, without the HUMAN-assigned
     * ones. Reworking a human task would pause from inside VERIFY, and resuming a
     * VERIFY phase replays the whole verification pass; the task stays FAILED with
     * the verifier's feedback, and a note says why (once per task).
     */
    public static List<TaskItem> withoutHumanAssignees(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                                       int phaseIdx, List<TaskItem> toRework, Set<String> alreadyNoted) {
        List<TaskItem> agents = new ArrayList<>();
        for (TaskItem task : toRework) {
            if (!isHumanAssignee(config, task.assignedAgentId())) {
                agents.add(task);
            } else if (alreadyNoted.add(task.id())) {
                synchronized (gc.getTranscript()) {
                    gc.getTranscript().add(new TranscriptEntry("system", "System",
                            "Not reworked: " + task.subject() + " is assigned to a human ("
                                    + (task.assignedDisplayName() != null ? task.assignedDisplayName() : task.assignedAgentId())
                                    + ") — it stays FAILED with the verifier's feedback",
                            phaseIdx, phase.name(), TranscriptEntryType.VERIFICATION, Instant.now(), null, null));
                }
            }
        }
        return agents;
    }
}
