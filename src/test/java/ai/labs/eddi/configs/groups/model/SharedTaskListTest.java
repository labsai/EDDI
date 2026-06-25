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

    // --- Additional edge cases (code review gap coverage) ---

    @Test
    void findById_null_returnsNull() {
        list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        assertNull(list.findById(null));
    }

    @Test
    void findById_nonexistent_returnsNull() {
        list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        assertNull(list.findById("does-not-exist"));
    }

    @Test
    void assignTask_nonexistentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> list.assignTask("nonexistent", "agent-1", "Agent One"));
    }

    @Test
    void startTask_nonexistentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> list.startTask("nonexistent"));
    }

    @Test
    void isAllComplete_allVerified_returnsTrue() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        list.assignTask(task.id(), "a1", "A1");
        list.startTask(task.id());
        list.completeTask(task.id(), "done");
        list.verifyTask(task.id(), true, "ok");

        assertTrue(list.all().stream().allMatch(
                t -> t.status() == SharedTaskList.TaskStatus.VERIFIED
                        || t.status() == SharedTaskList.TaskStatus.COMPLETED));
    }

    @Test
    void isAllComplete_mixedStates_includingFailed() {
        var t1 = list.addTask(new SharedTaskList.TaskItem("T1", "desc", 0));
        var t2 = list.addTask(new SharedTaskList.TaskItem("T2", "desc", 1));

        list.assignTask(t1.id(), "a1", "A1");
        list.startTask(t1.id());
        list.completeTask(t1.id(), "done");

        list.failTask(t2.id(), "too hard");

        assertEquals(SharedTaskList.TaskStatus.COMPLETED, list.findById(t1.id()).status());
        assertEquals(SharedTaskList.TaskStatus.FAILED, list.findById(t2.id()).status());
    }

    @Test
    void findExecutableTasks_satisfiedByVerified() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();

        var taskA = new SharedTaskList.TaskItem(
                idA, "A", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of(), null, null, false, 0, Instant.now(), null);
        var taskB = new SharedTaskList.TaskItem(
                idB, "B", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of(idA), null, null, false, 1, Instant.now(), null);

        list.addTask(taskA);
        list.addTask(taskB);

        // B not executable (A not complete)
        assertEquals(1, list.findExecutableTasks().size());

        // Complete and verify A
        list.assignTask(idA, "agent-1", "Agent One");
        list.startTask(idA);
        list.completeTask(idA, "result");
        list.verifyTask(idA, true, "good");

        // B should now be executable (dependency A is VERIFIED)
        var executableIds = list.findExecutableTasks().stream().map(SharedTaskList.TaskItem::id).toList();
        assertTrue(executableIds.contains(idB), "B should be executable after A is VERIFIED");
    }

    @Test
    void multipleDependencies_allMustBeSatisfied() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();
        String idC = UUID.randomUUID().toString();

        var taskA = new SharedTaskList.TaskItem(idA, "A", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null, List.of(), null, null, false, 0, Instant.now(), null);
        var taskB = new SharedTaskList.TaskItem(idB, "B", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null, List.of(), null, null, false, 0, Instant.now(), null);
        var taskC = new SharedTaskList.TaskItem(idC, "C", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null, List.of(idA, idB), null, null, false, 0, Instant.now(), null);

        list.addTask(taskA);
        list.addTask(taskB);
        list.addTask(taskC);

        // Only A and B executable initially
        assertEquals(2, list.findExecutableTasks().size());

        // Complete A only — C still blocked (B not done)
        list.assignTask(idA, "a1", "A1");
        list.startTask(idA);
        list.completeTask(idA, "done");
        assertFalse(list.findExecutableTasks().stream().anyMatch(t -> t.id().equals(idC)));

        // Complete B — C now executable
        list.assignTask(idB, "a2", "A2");
        list.startTask(idB);
        list.completeTask(idB, "done");
        assertTrue(list.findExecutableTasks().stream().anyMatch(t -> t.id().equals(idC)));
    }

    @Test
    void all_returnsDefensiveCopy() {
        list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        var snapshot = list.all();
        list.addTask(new SharedTaskList.TaskItem("Task B", "desc", 1));

        // snapshot should not reflect the later addition
        assertEquals(1, snapshot.size());
        assertEquals(2, list.all().size());
    }

    @Test
    void detectCycles_selfReferencing() {
        String id = UUID.randomUUID().toString();
        var selfRef = new SharedTaskList.TaskItem(
                id, "Self", "depends on itself",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(id), null, null, false, 0, Instant.now(), null);
        list.addTask(selfRef);

        var cycles = list.detectCycles();
        assertFalse(cycles.isEmpty(), "Self-referencing dependency must be detected");
    }

    @Test
    void concurrentModifications_doNotCorrupt() throws Exception {
        // Add 100 tasks
        for (int i = 0; i < 100; i++) {
            list.addTask(new SharedTaskList.TaskItem("Task " + i, "desc", i));
        }

        // Concurrently assign + start + complete from multiple threads
        var tasks = list.all();
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (var task : tasks) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    list.assignTask(task.id(), "agent-" + task.priority(), "Agent " + task.priority());
                    list.startTask(task.id());
                    list.completeTask(task.id(), "done-" + task.priority());
                } catch (Exception e) {
                    // Some concurrent attempts may get state errors — that's fine
                }
            }));
        }

        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).get(10,
                java.util.concurrent.TimeUnit.SECONDS);

        // No corruption: all tasks should still be accessible
        assertEquals(100, list.size(), "All 100 tasks should survive concurrent access");
    }

    // --- updateTask regression tests (C1 fix: addTask→updateTask for deps) ---

    @Test
    void updateTask_replacesExistingTask() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task A", "desc", 0));
        var updated = new SharedTaskList.TaskItem(
                task.id(), "Task A", "updated desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(), null, null, false, 5, Instant.now(), null);

        list.updateTask(updated);

        assertEquals(1, list.size(), "updateTask must not duplicate");
        assertEquals("updated desc", list.findById(task.id()).description());
        assertEquals(5, list.findById(task.id()).priority());
    }

    @Test
    void updateTask_nonexistentId_throws() {
        var orphan = new SharedTaskList.TaskItem(
                "nonexistent-id", "X", "desc",
                SharedTaskList.TaskStatus.PENDING, null, null,
                List.of(), null, null, false, 0, Instant.now(), null);

        assertThrows(IllegalArgumentException.class, () -> list.updateTask(orphan));
    }

    @Test
    void updateTask_addsDependencies_blocksExecution() {
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();

        var taskA = new SharedTaskList.TaskItem(
                idA, "A", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of(), null, null, false, 0, Instant.now(), null);
        var taskB = new SharedTaskList.TaskItem(
                idB, "B", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of(), null, null, false, 1, Instant.now(), null);

        list.addTask(taskA);
        list.addTask(taskB);

        // Both executable initially (no deps)
        assertEquals(2, list.findExecutableTasks().size());

        // Update B to depend on A
        var taskBWithDep = new SharedTaskList.TaskItem(
                idB, "B", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of(idA), null, null, false, 1, Instant.now(), null);
        list.updateTask(taskBWithDep);

        // Only A should be executable now
        var executable = list.findExecutableTasks();
        assertEquals(1, executable.size(), "Only A should be executable after B gains dep on A");
        assertEquals(idA, executable.getFirst().id());

        // List still has exactly 2 tasks
        assertEquals(2, list.size(), "updateTask must not change list size");
    }

    // --- Additional branch coverage ---

    @Test
    void findTasksForAgent_null_returnsEmpty() {
        list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        assertTrue(list.findTasksForAgent(null).isEmpty());
    }

    @Test
    void assignTask_fromAssigned_throws() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent");

        // Trying to assign again should throw (already ASSIGNED)
        assertThrows(IllegalStateException.class,
                () -> list.assignTask(task.id(), "agent-2", "Agent2"));
    }

    @Test
    void completeTask_nonexistentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> list.completeTask("nonexistent", "result"));
    }

    @Test
    void verifyTask_nonexistentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> list.verifyTask("nonexistent", true, "note"));
    }

    @Test
    void verifyTask_fromPending_throws() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        assertThrows(IllegalStateException.class,
                () -> list.verifyTask(task.id(), true, "note"));
    }

    @Test
    void failTask_nonexistentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> list.failTask("nonexistent", "reason"));
    }

    @Test
    void failTask_fromCompleted() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent");
        list.startTask(task.id());
        list.completeTask(task.id(), "done");

        var failed = list.failTask(task.id(), "actually wrong");
        assertEquals(SharedTaskList.TaskStatus.FAILED, failed.status());
    }

    @Test
    void failTask_fromFailed_throws() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent");
        list.startTask(task.id());
        list.failTask(task.id(), "failed");

        // Already failed — can't fail again
        assertThrows(IllegalStateException.class,
                () -> list.failTask(task.id(), "fail again"));
    }

    @Test
    void setTasks_null_createsEmptyList() {
        list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        assertFalse(list.isEmpty());

        list.setTasks(null);
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    void sizeAndIsEmpty_basicOperations() {
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());

        list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());
    }

    @Test
    void findExecutableTasks_withNonexistentDep_notExecutable() {
        var id = UUID.randomUUID().toString();
        var task = new SharedTaskList.TaskItem(
                id, "Task", "desc", SharedTaskList.TaskStatus.PENDING,
                null, null, List.of("nonexistent-dep-id"), null, null, false, 0, Instant.now(), null);
        list.addTask(task);

        // Task depends on a nonexistent ID — should NOT be executable
        assertTrue(list.findExecutableTasks().isEmpty());
    }

    @Test
    void failTask_fromVerified_throws() {
        var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
        list.assignTask(task.id(), "agent-1", "Agent");
        list.startTask(task.id());
        list.completeTask(task.id(), "done");
        list.verifyTask(task.id(), true, "good");

        // VERIFIED is terminal — can't fail
        assertThrows(IllegalStateException.class,
                () -> list.failTask(task.id(), "no"));
    }
}
