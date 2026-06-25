/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
            Instant completedAt) {

        /**
         * Convenience constructor for creating a new pending task.
         */
        public TaskItem(String subject, String description, int priority) {
            this(UUID.randomUUID().toString(), subject, description,
                    TaskStatus.PENDING, null, null, List.of(),
                    null, null, false, priority, Instant.now(), null);
        }
    }

    /**
     * Task lifecycle states.
     */
    public enum TaskStatus {
        PENDING, ASSIGNED, IN_PROGRESS, COMPLETED, VERIFIED, FAILED, BLOCKED, AWAITING_APPROVAL
    }

    // --- Query methods ---

    /**
     * Tasks whose dependencies are all COMPLETED or VERIFIED and that are ready for
     * execution (status is PENDING or ASSIGNED).
     */
    public List<TaskItem> findExecutableTasks() {
        return tasks.stream()
                .filter(t -> t.status() == TaskStatus.PENDING || t.status() == TaskStatus.ASSIGNED)
                .filter(t -> t.dependsOnIds().isEmpty() || allDependenciesSatisfied(t))
                .toList();
    }

    /**
     * Tasks assigned to a specific agent.
     */
    public List<TaskItem> findTasksForAgent(String agentId) {
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
    public List<String> detectCycles() {
        // Simple DFS-based cycle detection
        List<String> visited = new ArrayList<>();
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
    public List<TaskItem> all() {
        return List.copyOf(tasks);
    }

    /**
     * Returns the number of tasks.
     */
    public int size() {
        return tasks.size();
    }

    /**
     * Whether the task list is empty.
     */
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Find a task by ID, or null if not found.
     */
    public TaskItem findById(String taskId) {
        return tasks.stream()
                .filter(t -> t.id().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    // --- Mutation methods ---

    /**
     * Add a task to the list. Returns the added task.
     */
    public TaskItem addTask(TaskItem task) {
        tasks.add(task);
        return task;
    }

    /**
     * Assign a task to an agent. Transitions PENDING → ASSIGNED.
     *
     * @throws IllegalStateException
     *             if the task is not in PENDING status
     */
    public TaskItem assignTask(String taskId, String agentId, String displayName) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.PENDING, "assign");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.ASSIGNED, agentId, displayName,
                existing.dependsOnIds(), existing.result(),
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), existing.completedAt());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Start a task. Transitions ASSIGNED → IN_PROGRESS.
     *
     * @throws IllegalStateException
     *             if the task is not in ASSIGNED status
     */
    public TaskItem startTask(String taskId) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.ASSIGNED, "start");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.IN_PROGRESS, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), existing.result(),
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), existing.completedAt());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Complete a task with a result. Transitions IN_PROGRESS → COMPLETED.
     *
     * @throws IllegalStateException
     *             if the task is not in IN_PROGRESS status
     */
    public TaskItem completeTask(String taskId, String result) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.IN_PROGRESS, "complete");
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                TaskStatus.COMPLETED, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), result,
                existing.verificationNote(), existing.verified(),
                existing.priority(), existing.createdAt(), Instant.now());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Verify a task. Transitions COMPLETED → VERIFIED (if passed) or FAILED.
     */
    public TaskItem verifyTask(String taskId, boolean passed, String note) {
        TaskItem existing = requireTask(taskId);
        requireStatus(existing, TaskStatus.COMPLETED, "verify");
        TaskStatus newStatus = passed ? TaskStatus.VERIFIED : TaskStatus.FAILED;
        TaskItem updated = new TaskItem(
                existing.id(), existing.subject(), existing.description(),
                newStatus, existing.assignedAgentId(), existing.assignedDisplayName(),
                existing.dependsOnIds(), existing.result(),
                note, passed,
                existing.priority(), existing.createdAt(), existing.completedAt());
        replaceTask(taskId, updated);
        return updated;
    }

    /**
     * Mark a task as failed. Any non-terminal status (not VERIFIED, not FAILED) can
     * transition to FAILED.
     */
    public TaskItem failTask(String taskId, String reason) {
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
                existing.priority(), existing.createdAt(), Instant.now());
        replaceTask(taskId, updated);
        return updated;
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

    private void replaceTask(String taskId, TaskItem replacement) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId)) {
                tasks.set(i, replacement);
                return;
            }
        }
    }

    private List<String> dfs(String taskId, List<String> visited, List<String> recursionStack) {
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

    public List<TaskItem> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskItem> tasks) {
        this.tasks = tasks != null ? tasks : new ArrayList<>();
    }
}
