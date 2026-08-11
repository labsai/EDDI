/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime task list for task-oriented group conversations. Embedded in
 * {@link GroupConversation} — not a separate collection.
 * <p>
 * Tracks tasks from planning through execution to verification, with
 * dependency-aware queries and validated status transitions.
 *
 * @author ginccc
 */
public class SharedTaskList {

    private List<TaskItem> tasks = new ArrayList<>();

    /**
     * A single task in the shared task list.
     *
     * @param id
     *            unique task identifier (UUID)
     * @param subject
     *            short title
     * @param description
     *            detailed task description
     * @param status
     *            current status
     * @param assignedAgentId
     *            agent or group responsible
     * @param assignedDisplayName
     *            human-readable agent name
     * @param dependsOnIds
     *            task IDs that must complete first
     * @param result
     *            filled on completion
     * @param verificationNote
     *            filled during VERIFY phase
     * @param verified
     *            true if passed verification
     * @param priority
     *            0 = highest
     * @param createdAt
     *            when this task was created
     * @param completedAt
     *            when this task was completed
     */
    public record TaskItem(
            String id,
            String subject,
            String description,
            TaskStatus status,
            String assignedAgentId,
            String assignedDisplayName,
            List<String> dependsOnIds,
            String result,
            String verificationNote,
            boolean verified,
            int priority,
            Instant createdAt,
            Instant completedAt,
            String createdByAgentId) {

        /**
         * Convenience constructor for creating a new pending task, authored by config
         * or the PLAN phase rather than by a member.
         */
        public TaskItem(String subject, String description, int priority) {
            this(UUID.randomUUID().toString(), subject, description,
                    TaskStatus.PENDING, null, null, List.of(),
                    null, null, false, priority, Instant.now(), null, null);
        }

        /**
         * Backward-compatible constructor without {@code createdByAgentId} (I5), for
         * the ~30 positional call sites that predate it. Attribution defaults to
         * {@code null}, which reads as "not filed by a member" — the correct meaning
         * for every one of those sites.
         */
        public TaskItem(String id, String subject, String description, TaskStatus status, String assignedAgentId,
                String assignedDisplayName, List<String> dependsOnIds, String result, String verificationNote,
                boolean verified, int priority, Instant createdAt, Instant completedAt) {
            this(id, subject, description, status, assignedAgentId, assignedDisplayName, dependsOnIds,
                    result, verificationNote, verified, priority, createdAt, completedAt, null);
        }
    }

    /**
     * Task lifecycle states.
     * <p>
     * {@code AWAITING_APPROVAL} is the HITL (Human-in-the-Loop) approval gate with
     * full lifecycle support: {@link #submitForApproval} transitions into it, and
     * {@link #approveTask}, {@link #rejectTask}, and
     * {@link #resetFromAnyToAssigned} transition out of it. {@code BLOCKED} remains
     * reserved (recognized by {@link #failTask} as non-terminal) for
     * dependency/resource blocking.
     */
    public enum TaskStatus {
        PENDING, ASSIGNED, IN_PROGRESS, COMPLETED, VERIFIED, FAILED,
        /** Reserved — task blocked by an unmet dependency or resource. */
        BLOCKED,
        /** HITL gate — task requires human approval before proceeding. */
        AWAITING_APPROVAL
    }

    // --- Query methods ---

    /**
     * Tasks whose dependencies are all COMPLETED or VERIFIED and that are ready for
     * execution (status is PENDING or ASSIGNED).
     */
    public synchronized List<TaskItem> findExecutableTasks() {
        return tasks.stream()
                .filter(t -> t.status() == TaskStatus.PENDING || t.status() == TaskStatus.ASSIGNED)
                .filter(t -> t.dependsOnIds().isEmpty() || allDependenciesSatisfied(t))
                .toList();
    }

    /**
     * Tasks assigned to a specific agent.
     */
    public synchronized List<TaskItem> findTasksForAgent(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        return tasks.stream()
                .filter(t -> agentId.equals(t.assignedAgentId()))
                .toList();
    }

    /**
     * Check for circular dependencies. Returns the cycle path if found, or an empty
     * list if the dependency graph is acyclic.
     */
    public synchronized List<String> detectCycles() {
        // Simple DFS-based cycle detection
        Set<String> visited = new HashSet<>();
        List<String> recursionStack = new ArrayList<>();

        for (TaskItem task : tasks) {
            List<String> cycle = dfs(task.id(), visited, recursionStack);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    /**
     * All tasks regardless of status.
     */
    public synchronized List<TaskItem> all() {
        return List.copyOf(tasks);
    }

    /**
     * Returns the number of tasks.
     */
    public synchronized int size() {
        return tasks.size();
    }

    /**
     * Whether the task list is empty.
     */
    public synchronized boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Find a task by ID, or null if not found.
     */
    public synchronized TaskItem findById(String taskId) {
        if (taskId == null) {
            return null;
        }
        return tasks.stream()
                .filter(t -> t.id().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Caps on member-authored task text (I5). A subject is an index entry other
     * members scan; a description is the brief. Both are bounded because an LLM
     * with a write tool will otherwise paste an entire analysis into one, and the
     * task list is loaded, re-serialized and re-sent to every subsequent turn.
     */
    public static final int MAX_AGENT_TASK_SUBJECT_LENGTH = 200;
    public static final int MAX_AGENT_TASK_DESCRIPTION_LENGTH = 4000;

    // --- Mutation methods ---

    /**
     * Add a task to the list. Returns the added task.
     */
    public synchronized TaskItem addTask(TaskItem task) {
        tasks.add(task);
        return task;
    }

    /**
     * Either the task that was filed, or the reason it was refused (I5).
     * <p>
     * A rejection is not an exception because the caller is an LLM tool: the
     * {@code reason} is written to be read by the model that just tried, so it can
     * fix its own call rather than retry the same one.
     */
    public record AddTaskResult(TaskItem task, String rejectionReason) {

        public boolean accepted() {
            return task != null;
        }

        static AddTaskResult rejected(String reason) {
            return new AddTaskResult(null, reason);
        }
    }

    /**
     * Files a member-authored task, validating and inserting <em>atomically</em>
     * (I5).
     * <p>
     * The whole check-then-act sequence holds this object's monitor for the same
     * reason every other mutator here does, and then some: two speakers in a
     * PARALLEL phase can call this concurrently, and validating outside the lock
     * would let both pass a duplicate-subject check, or both pass a cycle check
     * that only the pair of them together violates. Cycle detection in particular
     * has to see the candidate already inserted — so the insert happens first and
     * is rolled back if it created a cycle, which is only sound while nobody else
     * can observe the intermediate state.
     *
     * @param dependsOnSubjects
     *            dependencies named by <em>subject</em>, not id — an LLM filing a
     *            task has read the list as text and has no reason to know internal
     *            ids. Unknown subjects are refused rather than dropped: silently
     *            filing a task with its dependency missing schedules it
     *            immediately, which is the opposite of what was asked for
     */
    public synchronized AddTaskResult addAgentTask(String subject, String description, List<String> dependsOnSubjects,
                                                   int priority, String createdByAgentId) {
        return addAgentTask(subject, description, dependsOnSubjects, priority, createdByAgentId, null, null, 0);
    }

    /**
     * As above, filing the task already assigned when the caller named an owner
     * (I5's {@code assignToRole}).
     * <p>
     * Assigning here rather than with a follow-up {@link #assignTask} call is not
     * tidiness: between an insert and a separate assign the task is PENDING and
     * unowned, so {@code findExecutableTasks()} can hand it to a concurrent
     * execution wave, which then assigns it to whoever the loop picks — silently
     * discarding the owner the filing agent asked for. One lock, one visible state.
     *
     * @param assignedAgentId
     *            resolved owner; the caller must resolve one, because the EXECUTE
     *            wave only schedules tasks that already have an assignee
     * @param maxAgentAddedTasks
     *            per-discussion cap on agent-filed tasks, enforced here under the
     *            same monitor as the insert; {@code 0} disables it (the
     *            non-agent-authored path)
     */
    public synchronized AddTaskResult addAgentTask(String subject, String description, List<String> dependsOnSubjects,
                                                   int priority, String createdByAgentId, String assignedAgentId,
                                                   String assignedDisplayName, int maxAgentAddedTasks) {

        // The per-discussion cap belongs INSIDE this monitor. Counting outside it let
        // every speaker of a PARALLEL phase read the same under-limit count and all
        // pass together — 5 concurrent callers against a cap of 20 with 19 filed
        // produced 24. The cap exists precisely as the unbounded-growth guard for an
        // LLM that can call this in a loop, so an advisory one is no guard at all.
        if (maxAgentAddedTasks > 0 && createdByAgentId != null
                && tasks.stream().filter(t -> t.createdByAgentId() != null).count() >= maxAgentAddedTasks) {
            return AddTaskResult.rejected(
                    "The task list is full for this discussion (%d agent-filed tasks). Finish existing tasks instead of adding more."
                            .formatted(maxAgentAddedTasks));
        }
        if (subject == null || subject.isBlank()) {
            return AddTaskResult.rejected("A task needs a subject.");
        }
        String trimmedSubject = subject.trim();
        if (trimmedSubject.length() > MAX_AGENT_TASK_SUBJECT_LENGTH) {
            return AddTaskResult.rejected("Subject is too long (%d chars, max %d). Put the detail in the description."
                    .formatted(trimmedSubject.length(), MAX_AGENT_TASK_SUBJECT_LENGTH));
        }
        String trimmedDescription = description != null ? description.trim() : "";
        if (trimmedDescription.length() > MAX_AGENT_TASK_DESCRIPTION_LENGTH) {
            return AddTaskResult.rejected("Description is too long (%d chars, max %d)."
                    .formatted(trimmedDescription.length(), MAX_AGENT_TASK_DESCRIPTION_LENGTH));
        }
        if (tasks.stream().anyMatch(t -> t.subject() != null && t.subject().equalsIgnoreCase(trimmedSubject))) {
            return AddTaskResult.rejected("A task with the subject \"%s\" already exists. Add to that one instead of duplicating it."
                    .formatted(trimmedSubject));
        }

        List<String> dependsOnIds = new ArrayList<>();
        if (dependsOnSubjects != null) {
            for (String dependency : dependsOnSubjects) {
                if (dependency == null || dependency.isBlank()) {
                    continue;
                }
                String wanted = dependency.trim();
                TaskItem match = tasks.stream()
                        .filter(t -> t.subject() != null && t.subject().equalsIgnoreCase(wanted))
                        .findFirst().orElse(null);
                if (match == null) {
                    return AddTaskResult.rejected("No task named \"%s\" to depend on. Use listGroupTasks to see the exact subjects."
                            .formatted(wanted));
                }
                dependsOnIds.add(match.id());
            }
        }

        var filed = new TaskItem(UUID.randomUUID().toString(), trimmedSubject, trimmedDescription,
                assignedAgentId != null ? TaskStatus.ASSIGNED : TaskStatus.PENDING,
                assignedAgentId, assignedDisplayName, List.copyOf(dependsOnIds), null, null, false,
                priority, Instant.now(), null, createdByAgentId);
        tasks.add(filed);

        // Cycles are only visible once the candidate is in the graph. Rolling back
        // is safe here and nowhere else: no other thread can have observed the
        // insert, because they would need this monitor to look. detectCycles() is
        // itself synchronized; Java monitors are reentrant, so this is one lock
        // acquisition, not two.
        if (!detectCycles().isEmpty()) {
            tasks.remove(filed);
            return AddTaskResult.rejected("That would create a circular dependency. Check what \"%s\" depends on."
                    .formatted(trimmedSubject));
        }
        return new AddTaskResult(filed, null);
    }

    /**
     * Assign a task to an agent. Transitions PENDING → ASSIGNED.
     *
     * @throws IllegalStateException
     *             if the task is not in PENDING status
     */
    public synchronized TaskItem assignTask(String taskId, String agentId, String displayName) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.PENDING, "assign");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.ASSIGNED, agentId, displayName,
                existing.dependsOnIds(), existing.result(),
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), existing.completedAt(), existing.createdByAgentId());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Start a task. Transitions ASSIGNED → IN_PROGRESS.
     *
     * @throws IllegalStateException
     *             if the task is not in ASSIGNED status
     */
    public synchronized TaskItem startTask(String taskId) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.ASSIGNED, "start");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.IN_PROGRESS, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), existing.result(),
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), existing.completedAt(), existing.createdByAgentId());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Complete a task with a result. Transitions IN_PROGRESS → COMPLETED.
     *
     * @throws IllegalStateException
     *             if the task is not in IN_PROGRESS status
     */
    public synchronized TaskItem completeTask(String taskId, String result) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.IN_PROGRESS, "complete");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.COMPLETED, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), result,
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), Instant.now(), existing.createdByAgentId());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Verify a task. Transitions COMPLETED → VERIFIED (if passed) or FAILED.
     */
    public synchronized TaskItem verifyTask(String taskId, boolean passed, String note) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.COMPLETED, "verify");
        TaskStatus newStatus = passed ? TaskStatus.VERIFIED : TaskStatus.FAILED;
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                newStatus, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), existing.result(),
                note, passed,
                existing.priority(), existing.createdAt(), existing.completedAt(), existing.createdByAgentId());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Mark a task as failed. Any non-terminal status (not VERIFIED, not FAILED) can
     * transition to FAILED.
     */
    public synchronized TaskItem failTask(String taskId, String reason) {
        TaskItem existing = requireTask(taskId);
        if (existing.status() == TaskStatus.VERIFIED || existing.status() == TaskStatus.FAILED) {
            throw new IllegalStateException(
                    "Cannot fail task '%s' — already in terminal status: %s"
                            .formatted(taskId, existing.status()));
        }
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.FAILED, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), existing.result(),
                reason, false,
                existing.priority(), existing.createdAt(), Instant.now(), existing.createdByAgentId());
        replaceTask(taskId, updated);
        return updated;
    }

    // --- HITL task lifecycle ---

    /** IN_PROGRESS → AWAITING_APPROVAL, preserving the agent's answer in result. */
    public synchronized TaskItem submitForApproval(String taskId, String result) {
        TaskItem t = requireTask(taskId);
        if (t.status() != TaskStatus.IN_PROGRESS)
            throw new IllegalStateException("submitForApproval '%s': expected IN_PROGRESS but was %s".formatted(taskId, t.status()));
        TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.AWAITING_APPROVAL,
                t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), result,
                t.verificationNote(), t.verified(), t.priority(), t.createdAt(), t.completedAt(), t.createdByAgentId());
        replaceTask(taskId, u);
        return u;
    }

    /** AWAITING_APPROVAL → COMPLETED (result already stored). */
    public synchronized TaskItem approveTask(String taskId) {
        TaskItem t = requireTask(taskId);
        if (t.status() != TaskStatus.AWAITING_APPROVAL)
            throw new IllegalStateException("approveTask '%s': expected AWAITING_APPROVAL but was %s".formatted(taskId, t.status()));
        TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.COMPLETED,
                t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
                t.verificationNote(), t.verified(), t.priority(), t.createdAt(), Instant.now(), t.createdByAgentId());
        replaceTask(taskId, u);
        return u;
    }

    /** AWAITING_APPROVAL → FAILED. */
    public synchronized TaskItem rejectTask(String taskId, String rejectionNote) {
        TaskItem t = requireTask(taskId);
        if (t.status() != TaskStatus.AWAITING_APPROVAL)
            throw new IllegalStateException("rejectTask '%s': expected AWAITING_APPROVAL but was %s".formatted(taskId, t.status()));
        TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.FAILED,
                t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
                rejectionNote, false, t.priority(), t.createdAt(), Instant.now(), t.createdByAgentId());
        replaceTask(taskId, u);
        return u;
    }

    /** IN_PROGRESS → ASSIGNED. */
    public synchronized TaskItem resetToAssigned(String taskId) {
        TaskItem t = requireTask(taskId);
        if (t.status() != TaskStatus.IN_PROGRESS)
            throw new IllegalStateException("resetToAssigned '%s': expected IN_PROGRESS but was %s".formatted(taskId, t.status()));
        TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.ASSIGNED,
                t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), t.result(),
                t.verificationNote(), t.verified(), t.priority(), t.createdAt(), t.completedAt(), t.createdByAgentId());
        replaceTask(taskId, u);
        return u;
    }

    /**
     * AWAITING_APPROVAL / IN_PROGRESS / FAILED → ASSIGNED. Used by the RETRY
     * rejection policy to re-queue tasks for another attempt. Clears the prior
     * result but stores the reviewer's feedback in {@code verificationNote} so the
     * re-executing agent knows why the first attempt was rejected. ASSIGNED is a
     * no-op; any other status (PENDING, COMPLETED, VERIFIED) throws.
     */
    public synchronized TaskItem resetFromAnyToAssigned(String taskId, String reviewerFeedback) {
        TaskItem t = requireTask(taskId);
        if (t.status() == TaskStatus.ASSIGNED) {
            return t; // already assignable — no-op
        }
        if (t.status() != TaskStatus.AWAITING_APPROVAL
                && t.status() != TaskStatus.IN_PROGRESS
                && t.status() != TaskStatus.FAILED) {
            throw new IllegalStateException(
                    "resetFromAnyToAssigned '%s': expected AWAITING_APPROVAL/IN_PROGRESS/FAILED but was %s"
                            .formatted(taskId, t.status()));
        }
        TaskItem u = new TaskItem(t.id(), t.subject(), t.description(), TaskStatus.ASSIGNED,
                t.assignedAgentId(), t.assignedDisplayName(), t.dependsOnIds(), null,
                reviewerFeedback, false, t.priority(), t.createdAt(), null, t.createdByAgentId());
        replaceTask(taskId, u);
        return u;
    }

    /** Post-join detection query for the per-task gate (Invariant 4). */
    public synchronized boolean hasAwaitingApproval() {
        return all().stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_APPROVAL);
    }

    // --- Internal helpers ---

    private boolean allDependenciesSatisfied(TaskItem task) {
        return task.dependsOnIds().stream().allMatch(depId -> {
            TaskItem dep = findById(depId);
            return dep != null && (dep.status() == TaskStatus.COMPLETED || dep.status() == TaskStatus.VERIFIED);
        });
    }

    private TaskItem requireTask(String taskId) {
        TaskItem task = findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return task;
    }

    private void requireStatus(TaskItem task, TaskStatus expected, String operation) {
        if (task.status() != expected) {
            throw new IllegalStateException(
                    "Cannot %s task '%s' — expected status %s but was %s"
                            .formatted(operation, task.id(), expected, task.status()));
        }
    }

    /**
     * Replace a task with an updated version (same ID). Used for updating task
     * metadata (e.g., adding dependency IDs) without changing status.
     *
     * @throws IllegalArgumentException
     *             if no task with the given ID exists
     */
    public synchronized void updateTask(TaskItem replacement) {
        boolean found = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(replacement.id())) {
                tasks.set(i, replacement);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Task not found: " + replacement.id());
        }
    }

    private void replaceTask(String taskId, TaskItem replacement) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId)) {
                tasks.set(i, replacement);
                return;
            }
        }
    }

    private List<String> dfs(String taskId, Set<String> visited, List<String> recursionStack) {
        if (recursionStack.contains(taskId)) {
            // Found a cycle — return the path
            List<String> cycle = new ArrayList<>(recursionStack.subList(recursionStack.indexOf(taskId), recursionStack.size()));
            cycle.add(taskId);
            return cycle;
        }
        if (visited.contains(taskId)) {
            return List.of();
        }

        visited.add(taskId);
        recursionStack.add(taskId);

        TaskItem task = findById(taskId);
        if (task != null) {
            for (String depId : task.dependsOnIds()) {
                List<String> cycle = dfs(depId, visited, recursionStack);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }

        recursionStack.remove(taskId);
        return List.of();
    }

    // --- Getters/Setters for serialization ---

    public synchronized List<TaskItem> getTasks() {
        return new ArrayList<>(tasks);
    }

    public synchronized void setTasks(List<TaskItem> tasks) {
        this.tasks = tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
    }

    // ==================== I18 — bid awards (CNP-lite) ====================

    /**
     * The winning bid a BID-mode task was awarded on (I18) — per-task metadata,
     * deliberately not a global {@code DecisionRecord}: an award is a scheduling
     * fact about one task, not the discussion's conclusion.
     *
     * @param agentId
     *            the winner
     * @param confidence
     *            the winning self-assessed confidence (0..1, clamped at parse)
     * @param estimatedComplexity
     *            the bidder's XS/S/M/L estimate, verbatim
     * @param rationale
     *            why the bidder claimed the task — quoted by dashboards
     */
    public record AwardedBid(String agentId, double confidence, String estimatedComplexity, String rationale) {
    }

    /** taskId → the bid it was awarded on. Only BID-mode tasks ever appear here. */
    private Map<String, AwardedBid> awardedBids = new ConcurrentHashMap<>();

    public Map<String, AwardedBid> getAwardedBids() {
        return awardedBids;
    }

    public void setAwardedBids(Map<String, AwardedBid> awardedBids) {
        this.awardedBids = awardedBids != null ? new ConcurrentHashMap<>(awardedBids) : new ConcurrentHashMap<>();
    }

    /** Records the award a task's assignment came from (I18). */
    public synchronized void recordAwardedBid(String taskId, AwardedBid bid) {
        if (taskId != null && bid != null) {
            awardedBids.put(taskId, bid);
        }
    }
}
