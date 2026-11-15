/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.HandoffPolicies;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.HandoffOrigin;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.InboxItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Intake;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human review of the intake inbox, plus the one backlog delete handoff makes
 * necessary.
 * <p>
 * <b>This is the only door from the inbox to the backlog.</b> A REVIEW-mode
 * handoff sits in {@code handoffs.inbox}, in no task list, so no cadence can
 * pull it; accepting it here copies it onto the backlog as a PENDING task and
 * stamps its origin. Authorization (EDIT on the receiving group) is the
 * caller's: the REST resource, and later the MCP tools, check it before calling
 * these methods, which is why they are public and free of any web types.
 * <p>
 * Every decision writes an audit entry with the shared HITL vocabulary
 * ({@code approvalCategory = team_handoff_intake}, outcome
 * {@code APPROVED | APPROVED_EDITED | REJECTED}, {@code reasonCode}) so the
 * approval analytics need no special case for it.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamHandoffIntake {

    private static final Logger LOGGER = Logger.getLogger(TeamHandoffIntake.class);

    static final String APPROVAL_CATEGORY = "team_handoff_intake";
    static final String AUDIT_TASK_ID = "team.handoff.intake";
    static final int MAX_NOTE_CHARS = 500;
    static final int MAX_REASON_CODE_CHARS = 64;

    /** How a call ended. */
    public enum Status {
        OK, NOT_FOUND, BACKLOG_FULL, DUPLICATE, INVALID, CONFLICT, UNAVAILABLE
    }

    /**
     * @param task
     *            the backlog task an accept created, else {@code null}
     * @param origin
     *            the origin it now carries (accept) or carried (decline)
     */
    public record Result(Status status, String message, TaskItem task, HandoffOrigin origin) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * Optional edits an accepting human makes. Any field that differs from what was
     * filed makes the decision {@code APPROVED_EDITED}.
     */
    public record Edits(String subject, String description, Integer priority, String note) {
    }

    /** The inbox and the origins of backlog tasks, for review screens. */
    public record View(List<InboxItem> inbox, Map<String, HandoffOrigin> origins) {
    }

    private final IGroupWorkspaceStore workspaceStore;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Inject
    public TeamHandoffIntake(IGroupWorkspaceStore workspaceStore, AuditLedgerService auditLedgerService, MeterRegistry meterRegistry) {
        this(workspaceStore, auditLedgerService, meterRegistry, Clock.systemUTC());
    }

    TeamHandoffIntake(IGroupWorkspaceStore workspaceStore, AuditLedgerService auditLedgerService, MeterRegistry meterRegistry, Clock clock) {
        this.workspaceStore = workspaceStore;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** The inbox and origins of {@code groupId}'s workspace. */
    public View view(String groupId) throws IResourceStore.ResourceStoreException {
        GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
        return new View(workspace.getHandoffs().getInbox(), workspace.getHandoffs().getOrigins());
    }

    /**
     * Accepts inbox item {@code handoffId}: moves it onto the backlog as PENDING,
     * optionally edited.
     *
     * @param userId
     *            the acting human, for the audit entry only
     */
    public Result accept(String groupId, String handoffId, Edits edits, String userId) {
        try {
            for (int attempt = 0; attempt < TeamHandoffService.MAX_CAS_ATTEMPTS; attempt++) {
                GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
                WorkspaceHandoffs handoffs = workspace.getHandoffs();
                InboxItem item = handoffs.findInbox(handoffId);
                if (item == null) {
                    return new Result(Status.NOT_FOUND, "No pending handoff with that id (it may already have been decided).", null, null);
                }
                String subject = item.subject();
                String description = item.description();
                int priority = item.priority();
                List<String> edited = new ArrayList<>();
                if (edits != null) {
                    if (edits.subject() != null) {
                        String next = TeamHandoffService.cleanSubject(edits.subject());
                        if (next.isBlank() || next.length() > SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH) {
                            return new Result(Status.INVALID,
                                    "The subject must be 1-" + SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH + " characters.", null,
                                    null);
                        }
                        if (!next.equals(subject)) {
                            subject = next;
                            edited.add("subject");
                        }
                    }
                    if (edits.description() != null) {
                        String next = TeamHandoffService.cleanDescription(edits.description());
                        if (next.length() > HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS) {
                            return new Result(Status.INVALID,
                                    "The description must be at most " + HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS + " characters.",
                                    null, null);
                        }
                        if (!next.equals(description)) {
                            description = next;
                            edited.add("description");
                        }
                    }
                    if (edits.priority() != null) {
                        int next = Math.max(0, Math.min(HandoffPolicies.MAX_PRIORITY, edits.priority()));
                        if (next != priority) {
                            priority = next;
                            edited.add("priority");
                        }
                    }
                }
                SharedTaskList backlog = workspace.getBacklog();
                if (backlog.size() >= GroupWorkspace.MAX_BACKLOG_SIZE) {
                    return new Result(Status.BACKLOG_FULL,
                            "The backlog already holds " + GroupWorkspace.MAX_BACKLOG_SIZE + " tasks — delete finished tasks before accepting more.",
                            null,
                            null);
                }
                final String finalSubject = subject;
                if (backlog.getTasks().stream().anyMatch(t -> finalSubject.equalsIgnoreCase(t.subject()))) {
                    return new Result(Status.DUPLICATE, "A backlog task with that subject already exists — edit the subject to accept it.", null,
                            null);
                }
                Instant now = clock.instant();
                Intake decided = edited.isEmpty() ? Intake.APPROVED : Intake.APPROVED_EDITED;
                TaskItem task = backlog.addTask(new TaskItem(subject, description, priority, item.bet()));
                HandoffOrigin origin = item.origin().reviewed(decided, now);
                handoffs.putOrigin(task.id(), origin);
                handoffs.removeInbox(handoffId);
                if (workspaceStore.casRevision(workspace)) {
                    auditDecision(groupId, handoffId, origin, decided == Intake.APPROVED ? "APPROVED" : "APPROVED_EDITED", null,
                            edits != null ? edits.note() : null,
                            edited, userId, task.id());
                    count(decided == Intake.APPROVED ? "approved" : "approved_edited");
                    return new Result(Status.OK, "Accepted onto the backlog.", task, origin);
                }
            }
            return new Result(Status.CONFLICT, "The workspace is being modified concurrently — retry.", null, null);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Accepting handoff %s for group %s failed: %s", LogSanitizer.sanitize(handoffId), LogSanitizer.sanitize(groupId),
                    e.getMessage());
            return new Result(Status.UNAVAILABLE, "The workspace could not be written right now.", null, null);
        }
    }

    /**
     * Declines inbox item {@code handoffId}: removes it and remembers it, so the
     * same handoff is not filed again for the decline-memory window.
     */
    public Result decline(String groupId, String handoffId, String reasonCode, String note, String userId) {
        try {
            for (int attempt = 0; attempt < TeamHandoffService.MAX_CAS_ATTEMPTS; attempt++) {
                GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
                WorkspaceHandoffs handoffs = workspace.getHandoffs();
                InboxItem item = handoffs.findInbox(handoffId);
                if (item == null) {
                    return new Result(Status.NOT_FOUND, "No pending handoff with that id (it may already have been decided).", null, null);
                }
                handoffs.removeInbox(handoffId);
                if (item.origin() != null && item.origin().dedupeKey() != null) {
                    handoffs.tombstone(item.origin().dedupeKey(), clock.instant());
                }
                if (workspaceStore.casRevision(workspace)) {
                    String reason = reasonCode != null ? abbreviate(reasonCode, MAX_REASON_CODE_CHARS) : null;
                    auditDecision(groupId, handoffId, item.origin(), "REJECTED", reason, note, List.of(), userId, null);
                    count("rejected");
                    return new Result(Status.OK, "Declined.", null, item.origin());
                }
            }
            return new Result(Status.CONFLICT, "The workspace is being modified concurrently — retry.", null, null);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Declining handoff %s for group %s failed: %s", LogSanitizer.sanitize(handoffId), LogSanitizer.sanitize(groupId),
                    e.getMessage());
            return new Result(Status.UNAVAILABLE, "The workspace could not be written right now.", null, null);
        }
    }

    /**
     * Removes a backlog task (and its handoff origin). Handoff makes the 200-task
     * ceiling reachable, and the backlog has no other way to shrink.
     * <p>
     * Refused while a running cadence discussion has the task pulled, which would
     * otherwise strand the writeback that matches outcomes to tasks. Tasks that
     * depended on the removed one lose that dependency edge rather than waiting for
     * a task that no longer exists.
     */
    public Result deleteBacklogTask(String groupId, String taskId, String userId) {
        try {
            for (int attempt = 0; attempt < TeamHandoffService.MAX_CAS_ATTEMPTS; attempt++) {
                GroupWorkspace workspace = workspaceStore.readOrCreate(groupId);
                SharedTaskList backlog = workspace.getBacklog();
                TaskItem task = backlog.findById(taskId);
                if (task == null) {
                    return new Result(Status.NOT_FOUND, "No backlog task with that id.", null, null);
                }
                if (!GroupWorkspace.NO_RUNNING_DISCUSSION.equals(workspace.getRunningDiscussionId())
                        && workspace.getPulledTaskIds().contains(taskId)) {
                    return new Result(Status.CONFLICT,
                            "A cadence discussion is working this task right now — delete it after the discussion finishes.", null,
                            null);
                }
                List<TaskItem> remaining = new ArrayList<>();
                for (TaskItem other : backlog.getTasks()) {
                    if (other.id().equals(taskId)) {
                        continue;
                    }
                    if (other.dependsOnIds().contains(taskId)) {
                        List<String> deps = new ArrayList<>(other.dependsOnIds());
                        deps.remove(taskId);
                        other = new TaskItem(other.id(), other.subject(), other.description(), other.status(), other.assignedAgentId(),
                                other.assignedDisplayName(), deps, other.result(), other.verificationNote(), other.verified(), other.priority(),
                                other.createdAt(),
                                other.completedAt(), other.createdByAgentId(), other.bet());
                    }
                    remaining.add(other);
                }
                backlog.setTasks(remaining);
                HandoffOrigin origin = workspace.getHandoffs().removeOrigin(taskId);
                workspace.getPulledTaskIds().remove(taskId);
                if (workspaceStore.casRevision(workspace)) {
                    auditBacklogDelete(groupId, task, origin, userId);
                    return new Result(Status.OK, "Deleted.", task, origin);
                }
            }
            return new Result(Status.CONFLICT, "The workspace is being modified concurrently — retry.", null, null);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Deleting backlog task %s of group %s failed: %s", LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(groupId),
                    e.getMessage());
            return new Result(Status.UNAVAILABLE, "The workspace could not be written right now.", null, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi_team_handoff_intake_total", "outcome", outcome).increment();
        }
    }

    private void auditDecision(String groupId, String handoffId, HandoffOrigin origin, String outcome, String reasonCode, String note,
                               List<String> editedFields, String userId, String taskId) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("approvalCategory", APPROVAL_CATEGORY);
            detail.put("outcome", outcome);
            detail.put("reasonCode", reasonCode);
            detail.put("handoffId", handoffId);
            detail.put("targetGroupId", groupId);
            detail.put("taskId", taskId);
            detail.put("editedFields", editedFields);
            detail.put("note", note != null ? abbreviate(note, MAX_NOTE_CHARS) : null);
            if (origin != null) {
                detail.put("sourceKey", origin.sourceKey());
                detail.put("hop", origin.hop());
                detail.put("trigger", origin.trigger() != null ? origin.trigger().name() : null);
                detail.put("dedupeKey", origin.dedupeKey());
            }
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), origin != null ? origin.sourceConversationId() : null,
                    origin != null ? origin.sourceId() : groupId, null, userId, null, -1, AUDIT_TASK_ID, "group", -1, 0L, Map.of(), detail, null,
                    null,
                    List.of(), 0.0, clock.instant(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit handoff intake audit entry: %s", e.getMessage());
        }
    }

    private void auditBacklogDelete(String groupId, TaskItem task, HandoffOrigin origin, String userId) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("groupId", groupId);
            detail.put("taskId", task.id());
            detail.put("subject", task.subject());
            detail.put("status", task.status().name());
            detail.put("handedOff", origin != null);
            auditLedgerService
                    .submit(new AuditEntry(UUID.randomUUID().toString(), null, groupId, null, userId, null, -1, "team.backlog.delete", "group", -1,
                            0L, Map.of(), detail, null, null, List.of(), 0.0, clock.instant(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit backlog delete audit entry: %s", e.getMessage());
        }
    }

    private static String abbreviate(String value, int max) {
        String single = value.replaceAll("\\p{Cntrl}", " ").strip();
        return single.length() <= max ? single : single.substring(0, max);
    }
}
