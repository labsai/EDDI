/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.engine.internal.TaskListParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The task records of a task-force discussion as one reader may be shown them.
 * A task's text is agent-authored: its subject and description come from the
 * planner's {@code PLAN} entry or a member's task tool, its result from the
 * assignee's {@code TASK_RESULT} entry, its verification note from the
 * verifier's {@code VERIFICATION} entry. The records themselves carry no
 * signature, so for a reader that requires peer verification each field is
 * shown only when the entry it comes from is one the reader trusts (see
 * {@link GroupSigningGuard.View#trusts}), and is {@link #WITHHELD} otherwise.
 * <ul>
 * <li>A <b>result</b> of an agent's task is shown when a trusted entry of the
 * assignee holds exactly that text. So a result whose entry is unsigned, or a
 * result edited in the store, is not; the reader's own results included when it
 * signs, while one that does not sign trusts its own entries as stored. A
 * result an approver replaced is the reviewer's text, which no agent wrote, and
 * is shown as a reviewer's synthesis edit is.</li>
 * <li>A <b>subject and description</b> are withheld when an untrusted
 * {@code PLAN} entry parses to them and no trusted one does, or when another
 * agent's task tool wrote them and that agent does not sign verifiably (see
 * {@link GroupSigningGuard.View#vouchesFor}). The engine records a tool-filed
 * task under the agent whose turn called the tool, so the task is that agent's
 * text exactly as its signed entries are; a store edit to it is not detected,
 * the same boundary as a configured task's text. Configured tasks and the
 * user's own are not agent-authored.</li>
 * <li>A <b>verification note</b> is withheld when an untrusted
 * {@code VERIFICATION} entry holds it and no trusted agent entry does.</li>
 * </ul>
 * For a reader that does not verify, every record is shown as stored.
 *
 * @author ginccc
 */
final class TaskRecordView {

    /** What a reader is shown in place of task text it may not take as written. */
    static final String WITHHELD = "[withheld: this text could not be verified]";

    private static final Set<String> ENGINE_SPEAKERS = Set.of("user", "system");

    private final GroupSigningGuard.View view;
    private final String readerId;
    private final Predicate<String> isHuman;
    private final Predicate<String> editedByReviewer;
    /** Trusted content by speaker id, built on first use. */
    private Map<String, Set<String>> trustedContents;
    private Set<List<String>> trustedPlans;
    private Set<List<String>> untrustedPlans;

    /**
     * @param isHuman
     *            whether an assignee id is a HUMAN member, whose result is not
     *            agent-authored
     * @param editedByReviewer
     *            whether a task id's result was replaced by an approver
     *            ({@code SharedTaskList.reviewerEdits}), which makes it the
     *            reviewer's text rather than the assignee's
     */
    TaskRecordView(GroupSigningGuard.View view, String readerId, Predicate<String> isHuman, Predicate<String> editedByReviewer) {
        this.view = view;
        this.readerId = readerId;
        this.isHuman = isHuman;
        this.editedByReviewer = editedByReviewer;
    }

    /**
     * The task records of {@code gc} as {@code view}'s reader may be shown them.
     * {@code config} decides which assignees are human; {@code null} treats none as
     * human. A result an approver replaced ({@code SharedTaskList.reviewerEdits})
     * is the reviewer's text.
     */
    static TaskRecordView of(GroupSigningGuard.View view, String readerId, GroupConversation gc, AgentGroupConfiguration config) {
        SharedTaskList taskList = gc.getTaskList();
        return new TaskRecordView(view, readerId, assignee -> GroupHumanTasks.isHumanAssignee(config, assignee),
                taskId -> taskList != null && taskList.getReviewerEdits().containsKey(taskId));
    }

    /** Whether the reader is shown every record as stored. */
    boolean showsEverything() {
        return !view.verifying();
    }

    /** Whether the reader may be shown {@code task}'s subject and description. */
    boolean showsTask(TaskItem task) {
        if (showsEverything() || task == null) {
            return true;
        }
        if (task.createdByAgentId() != null) {
            return task.createdByAgentId().equals(readerId) || view.vouchesFor(task.createdByAgentId());
        }
        List<String> parsed = List.of(Objects.toString(task.subject(), ""), Objects.toString(task.description(), ""));
        indexPlans();
        return !untrustedPlans.contains(parsed) || trustedPlans.contains(parsed);
    }

    /** {@code task} with every text field the reader may not be shown withheld. */
    TaskItem seen(TaskItem task) {
        if (showsEverything() || task == null) {
            return task;
        }
        boolean showsTask = showsTask(task);
        return new TaskItem(task.id(), showsTask ? task.subject() : WITHHELD, showsTask ? task.description() : WITHHELD, task.status(),
                task.assignedAgentId(), task.assignedDisplayName(), task.dependsOnIds(), result(task), note(task), task.verified(),
                task.priority(), task.createdAt(), task.completedAt(), task.createdByAgentId(), task.bet());
    }

    /**
     * Whether the reader may judge {@code task}'s work: it may be shown both the
     * task and its result. A verifier is given only such tasks.
     */
    boolean showsWork(TaskItem task) {
        return showsEverything() || task == null || showsTask(task) && showsResult(task);
    }

    private String result(TaskItem task) {
        return showsResult(task) ? task.result() : WITHHELD;
    }

    private boolean showsResult(TaskItem task) {
        String result = task.result();
        String assignee = task.assignedAgentId();
        if (result == null || assignee == null || isHuman.test(assignee) || editedByReviewer.test(task.id())) {
            return true;
        }
        return trustedContents().getOrDefault(assignee, Set.of()).contains(result);
    }

    private String note(TaskItem task) {
        String note = task.verificationNote();
        if (note == null || note.isBlank()) {
            return note;
        }
        // Traced by its text without surrounding whitespace: the verifier's readable
        // summary is trimmed, so feedback ending in whitespace is not in it verbatim.
        String text = note.strip();
        boolean fromUntrustedEntry = false;
        List<TranscriptEntry> stored = view.stored();
        for (TranscriptEntry entry : stored) {
            if (entry == null || entry.type() != TranscriptEntryType.VERIFICATION || entry.content() == null
                    || !entry.content().contains(text)) {
                continue;
            }
            if (!view.trusts(entry)) {
                fromUntrustedEntry = true;
            } else if (entry.speakerAgentId() != null && !ENGINE_SPEAKERS.contains(entry.speakerAgentId())) {
                // An agent's own entry vouches for it; an engine note quoting it does not.
                return note;
            }
        }
        return fromUntrustedEntry ? WITHHELD : note;
    }

    private Map<String, Set<String>> trustedContents() {
        if (trustedContents == null) {
            trustedContents = new HashMap<>();
            for (TranscriptEntry entry : view.stored()) {
                if (entry != null && entry.content() != null && entry.speakerAgentId() != null && view.trusts(entry)) {
                    trustedContents.computeIfAbsent(entry.speakerAgentId(), id -> new HashSet<>()).add(entry.content());
                }
            }
        }
        return trustedContents;
    }

    /**
     * Parses every {@code PLAN} entry as the planning phase did, so a task can be
     * traced to the plan it came from.
     */
    private void indexPlans() {
        if (untrustedPlans != null) {
            return;
        }
        trustedPlans = new HashSet<>();
        untrustedPlans = new HashSet<>();
        for (TranscriptEntry entry : view.stored()) {
            if (entry == null || entry.type() != TranscriptEntryType.PLAN || entry.content() == null) {
                continue;
            }
            Set<List<String>> into = view.trusts(entry) ? trustedPlans : untrustedPlans;
            for (TaskListParser.ParsedTask task : TaskListParser.parse(entry.content(), List.of())) {
                into.add(List.of(Objects.toString(task.subject(), ""), Objects.toString(task.description(), "")));
            }
        }
    }
}
