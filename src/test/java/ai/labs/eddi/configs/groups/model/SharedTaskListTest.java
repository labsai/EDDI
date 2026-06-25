/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SharedTaskList} — status transitions, dependency
 * queries, agent filtering, and cycle detection.
 *
 * @author ginccc
 */
class SharedTaskListTest {

    private SharedTaskList list;

    @BeforeEach
    void setUp() {
        list = new SharedTaskList();
    }

    // --- 1. Basic add ---

    @Test
    void addTask_generatesId() {
        var task = list.addTask(new SharedTaskList.TaskItem("Write report", "Detailed report", 1));

        assertNotNull(task.id(), "Task ID must be generated");
        assertEquals("Write report", task.subject());
        assertEquals(SharedTaskList.TaskStatus.PENDING, task.status());
    }

    // --- 2–6. Happy-path transitions ---

    @Test
    void assignTask_pendingToAssigned() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        var assigned = list.assignTask(task.id(), "agent-1", "Agent One");

        assertEquals(SharedTaskList.TaskStatus.ASSIGNED, assigned.status());
        assertEquals("agent-1", assigned.assignedAgentId());
        assertEquals("Agent One", assigned.assignedDisplayName());
    }

    @Test
    void startTask_assignedToInProgress() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");

        var started = list.startTask(task.id());

        assertEquals(SharedTaskList.TaskStatus.IN_PROGRESS, started.status());
    }

    @Test
    void completeTask_setsResult() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());

        var completed = list.completeTask(task.id(), "All done successfully");

        assertEquals(SharedTaskList.TaskStatus.COMPLETED, completed.status());
        assertEquals("All done successfully", completed.result());
        assertNotNull(completed.completedAt(), "completedAt must be set");
    }

    @Test
    void verifyTask_passed() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());
        list.completeTask(task.id(), "result");

        var verified = list.verifyTask(task.id(), true, "Looks good");

        assertEquals(SharedTaskList.TaskStatus.VERIFIED, verified.status());
        assertTrue(verified.verified());
        assertEquals("Looks good", verified.verificationNote());
    }

    @Test
    void verifyTask_failed() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());
        list.completeTask(task.id(), "result");

        var failed = list.verifyTask(task.id(), false, "Insufficient quality");

        assertEquals(SharedTaskList.TaskStatus.FAILED, failed.status());
        assertFalse(failed.verified());
        assertEquals("Insufficient quality", failed.verificationNote());
    }

    // --- 7–9. failTask from various states ---

    @Test
    void failTask_fromPending() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        var failed = list.failTask(task.id(), "No longer needed");

        assertEquals(SharedTaskList.TaskStatus.FAILED, failed.status());
        assertEquals("No longer needed", failed.verificationNote());
    }

    @Test
    void failTask_fromAssigned() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");

        var failed = list.failTask(task.id(), "Agent unavailable");

        assertEquals(SharedTaskList.TaskStatus.FAILED, failed.status());
    }

    @Test
    void failTask_fromInProgress() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());

        var failed = list.failTask(task.id(), "Runtime error");

        assertEquals(SharedTaskList.TaskStatus.FAILED, failed.status());
    }

    // --- 10–12. Invalid transitions ---

    @Test
    void invalidTransition_completingPending() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        assertThrows(IllegalStateException.class,
                () -> list.completeTask(task.id(), "result"),
                "Completing a PENDING task must throw");
    }

    @Test
    void invalidTransition_startingCompleted() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());
        list.completeTask(task.id(), "done");

        assertThrows(IllegalStateException.class,
                () -> list.startTask(task.id()),
                "Starting a COMPLETED task must throw");
    }

    @Test
    void invalidTransition_failVerified() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent One");
        list.startTask(task.id());
        list.completeTask(task.id(), "done");
        list.verifyTask(task.id(), true, "OK");

        assertThrows(IllegalStateException.class,
                () -> list.failTask(task.id(), "too late"),
                "Failing a VERIFIED task must throw");
    }

    // --- 13–15. findExecutableTasks ---

    @Test
    void findExecutableTasks_noDeps() {
        list.addTask(new SharedTaskList.TaskItem("Task 1", "desc", 0));
        list.addTask(new SharedTaskList.TaskItem("Task 2", "desc", 1));
        var task3 = list.addTask(new SharedTaskList.TaskItem("Task 3", "desc", 2));
        list.assignTask(task3.id(), "agent-1", "Agent One");

        // Also assign task 1 to have a mix of PENDING and ASSIGNED
        var task1 = list.findById(list.all().getFirst().id());
        list.assignTask(task1.id(), "agent-2", "Agent Two");

        var executable = list.findExecutableTasks();

        assertEquals(3, executable.size(),
                "All 3 tasks (1 PENDING + 2 ASSIGNED, no deps) should be executable");
    }

    @Test
    void findExecutableTasks_withDeps_allSatisfied() {
        var taskA = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        // Task B depends on Task A
        var taskBItem = new SharedTaskList.TaskItem(
                UUID.randomUUID().toString(), "Task B", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(taskA.id()), null, null, false, 1, Instant.now(), null);
        list.addTask(taskBItem);

        // Complete Task A through the full lifecycle
        list.assignTask(taskA.id(), "agent-1", "Agent One");
        list.startTask(taskA.id());
        list.completeTask(taskA.id(), "done");

        var executable = list.findExecutableTasks();

        // Task A is COMPLETED (not executable), Task B's deps are satisfied →
        // executable
        assertEquals(1, executable.size());
        assertEquals("Task B", executable.getFirst().subject());
    }

    @Test
    void findExecutableTasks_withDeps_unsatisfied() {
        var taskA = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        // Task B depends on Task A, which is still PENDING
        var taskBItem = new SharedTaskList.TaskItem(
                UUID.randomUUID().toString(), "Task B", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(taskA.id()), null, null, false, 1, Instant.now(), null);
        list.addTask(taskBItem);

        var executable = list.findExecutableTasks();

        // Task A is executable (PENDING, no deps), Task B is blocked
        assertEquals(1, executable.size());
        assertEquals("Task A", executable.getFirst().subject());
    }

    // --- 16. findTasksForAgent ---

    @Test
    void findTasksForAgent() {
        var t1 = list.addTask(new SharedTaskList.TaskItem("Task 1", "desc", 0));
        var t2 = list.addTask(new SharedTaskList.TaskItem("Task 2", "desc", 1));
        var t3 = list.addTask(new SharedTaskList.TaskItem("Task 3", "desc", 2));

        list.assignTask(t1.id(), "agent-alpha", "Alpha");
        list.assignTask(t2.id(), "agent-beta", "Beta");
        list.assignTask(t3.id(), "agent-alpha", "Alpha");

        var alphaTasks = list.findTasksForAgent("agent-alpha");
        var betaTasks = list.findTasksForAgent("agent-beta");

        assertEquals(2, alphaTasks.size(), "agent-alpha should have 2 tasks");
        assertEquals(1, betaTasks.size(), "agent-beta should have 1 task");
        assertTrue(alphaTasks.stream().allMatch(t -> "agent-alpha".equals(t.assignedAgentId())));
    }

    // --- 17–18. Cycle detection ---

    @Test
    void detectCycles_noCycle() {
        var taskA = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));

        var taskBItem = new SharedTaskList.TaskItem(
                UUID.randomUUID().toString(), "Task B", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(taskA.id()), null, null, false, 1, Instant.now(), null);
        var taskB = list.addTask(taskBItem);

        var taskCItem = new SharedTaskList.TaskItem(
                UUID.randomUUID().toString(), "Task C", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(taskB.id()), null, null, false, 2, Instant.now(), null);
        list.addTask(taskCItem);

        var cycles = list.detectCycles();

        assertTrue(cycles.isEmpty(), "A→B→C (linear chain) should have no cycles");
    }

    @Test
    void detectCycles_simpleCycle() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();

        // Task A depends on Task B
        var taskAItem = new SharedTaskList.TaskItem(
                idA, "Task A", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(idB), null, null, false, 0, Instant.now(), null);

        // Task B depends on Task A → cycle
        var taskBItem = new SharedTaskList.TaskItem(
                idB, "Task B", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(idA), null, null, false, 1, Instant.now(), null);

        list.addTask(taskAItem);
        list.addTask(taskBItem);

        var cycles = list.detectCycles();

        assertFalse(cycles.isEmpty(), "A↔B mutual dependency must be detected as a cycle");
    }
}
