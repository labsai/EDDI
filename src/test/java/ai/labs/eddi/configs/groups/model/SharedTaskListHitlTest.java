/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HITL (Human-in-the-Loop) lifecycle methods on
 * {@link SharedTaskList}: {@code submitForApproval}, {@code approveTask},
 * {@code rejectTask}, {@code resetToAssigned}, and {@code hasAwaitingApproval}.
 */
class SharedTaskListHitlTest {

    private SharedTaskList list;

    @BeforeEach
    void setUp() {
        list = new SharedTaskList();
    }

    // =========================================================================
    // submitForApproval
    // =========================================================================

    @Nested
    @DisplayName("submitForApproval")
    class SubmitForApproval {

        @Test
        @DisplayName("IN_PROGRESS → AWAITING_APPROVAL, result preserved")
        void happyPath() {
            var task = list.addTask(new SharedTaskList.TaskItem("Write report", "Detailed report", 1));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());

            var submitted = list.submitForApproval(task.id(), "Draft v1");

            assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, submitted.status());
            assertEquals("Draft v1", submitted.result(), "Result must be preserved");
            assertEquals("agent-1", submitted.assignedAgentId(), "Assignment must be preserved");
        }

        @Test
        @DisplayName("throws IllegalStateException from PENDING")
        void fromPending() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));

            assertThrows(IllegalStateException.class,
                    () -> list.submitForApproval(task.id(), "result"));
        }

        @Test
        @DisplayName("throws IllegalStateException from ASSIGNED")
        void fromAssigned() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");

            assertThrows(IllegalStateException.class,
                    () -> list.submitForApproval(task.id(), "result"));
        }

        @Test
        @DisplayName("throws IllegalStateException from COMPLETED")
        void fromCompleted() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");

            assertThrows(IllegalStateException.class,
                    () -> list.submitForApproval(task.id(), "result"));
        }

        @Test
        @DisplayName("throws IllegalStateException from FAILED")
        void fromFailed() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.failTask(task.id(), "reason");

            assertThrows(IllegalStateException.class,
                    () -> list.submitForApproval(task.id(), "result"));
        }
    }

    // =========================================================================
    // approveTask
    // =========================================================================

    @Nested
    @DisplayName("approveTask")
    class ApproveTask {

        @Test
        @DisplayName("AWAITING_APPROVAL → COMPLETED, completedAt set")
        void happyPath() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.submitForApproval(task.id(), "my result");

            var approved = list.approveTask(task.id());

            assertEquals(SharedTaskList.TaskStatus.COMPLETED, approved.status());
            assertNotNull(approved.completedAt(), "completedAt must be set on approval");
            assertEquals("my result", approved.result(), "Result must be preserved from submission");
        }

        @Test
        @DisplayName("throws IllegalStateException from PENDING")
        void fromPending() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));

            assertThrows(IllegalStateException.class,
                    () -> list.approveTask(task.id()));
        }

        @Test
        @DisplayName("throws IllegalStateException from IN_PROGRESS")
        void fromInProgress() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());

            assertThrows(IllegalStateException.class,
                    () -> list.approveTask(task.id()));
        }
    }

    // =========================================================================
    // rejectTask
    // =========================================================================

    @Nested
    @DisplayName("rejectTask")
    class RejectTask {

        @Test
        @DisplayName("AWAITING_APPROVAL → FAILED, rejectionNote stored")
        void happyPath() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.submitForApproval(task.id(), "draft");

            var rejected = list.rejectTask(task.id(), "Needs more detail");

            assertEquals(SharedTaskList.TaskStatus.FAILED, rejected.status());
            assertEquals("Needs more detail", rejected.verificationNote(),
                    "Rejection note must be stored in verificationNote");
            assertNotNull(rejected.completedAt(), "completedAt must be set on rejection");
        }

        @Test
        @DisplayName("throws IllegalStateException from PENDING")
        void fromPending() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));

            assertThrows(IllegalStateException.class,
                    () -> list.rejectTask(task.id(), "nope"));
        }

        @Test
        @DisplayName("throws IllegalStateException from COMPLETED")
        void fromCompleted() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");

            assertThrows(IllegalStateException.class,
                    () -> list.rejectTask(task.id(), "too late"));
        }
    }

    // =========================================================================
    // resetToAssigned
    // =========================================================================

    @Nested
    @DisplayName("resetToAssigned")
    class ResetToAssigned {

        @Test
        @DisplayName("IN_PROGRESS → ASSIGNED")
        void happyPath() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());

            var reset = list.resetToAssigned(task.id());

            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, reset.status());
            assertEquals("agent-1", reset.assignedAgentId(), "Agent assignment must be preserved");
        }

        @Test
        @DisplayName("throws IllegalStateException from PENDING")
        void fromPending() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));

            assertThrows(IllegalStateException.class,
                    () -> list.resetToAssigned(task.id()));
        }

        @Test
        @DisplayName("throws IllegalStateException from COMPLETED")
        void fromCompleted() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");

            assertThrows(IllegalStateException.class,
                    () -> list.resetToAssigned(task.id()));
        }
    }

    // =========================================================================
    // hasAwaitingApproval
    // =========================================================================

    @Nested
    @DisplayName("hasAwaitingApproval")
    class HasAwaitingApproval {

        @Test
        @DisplayName("returns true when one task is AWAITING_APPROVAL")
        void trueWhenPresent() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());
            list.submitForApproval(task.id(), "result");

            assertTrue(list.hasAwaitingApproval());
        }

        @Test
        @DisplayName("returns false when no tasks are AWAITING_APPROVAL")
        void falseWhenNone() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent");
            list.startTask(task.id());

            assertFalse(list.hasAwaitingApproval());
        }

        @Test
        @DisplayName("returns false on empty list")
        void falseOnEmpty() {
            assertFalse(list.hasAwaitingApproval());
        }
    }

    // =========================================================================
    // Full HITL lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Full HITL lifecycle")
    class FullHitlLifecycle {

        @Test
        @DisplayName("add → assign → start → submitForApproval → approveTask → verifyTask")
        void approvalPath() {
            // add
            var task = list.addTask(new SharedTaskList.TaskItem("Review doc", "Review the document", 1));
            assertEquals(SharedTaskList.TaskStatus.PENDING, task.status());

            // assign
            var assigned = list.assignTask(task.id(), "agent-1", "Agent One");
            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, assigned.status());

            // start
            var started = list.startTask(task.id());
            assertEquals(SharedTaskList.TaskStatus.IN_PROGRESS, started.status());

            // submitForApproval
            var submitted = list.submitForApproval(task.id(), "Document reviewed — LGTM");
            assertEquals(SharedTaskList.TaskStatus.AWAITING_APPROVAL, submitted.status());
            assertTrue(list.hasAwaitingApproval());

            // approveTask
            var approved = list.approveTask(task.id());
            assertEquals(SharedTaskList.TaskStatus.COMPLETED, approved.status());
            assertFalse(list.hasAwaitingApproval());
            assertNotNull(approved.completedAt());

            // verifyTask
            var verified = list.verifyTask(task.id(), true, "Quality confirmed");
            assertEquals(SharedTaskList.TaskStatus.VERIFIED, verified.status());
            assertTrue(verified.verified());
            assertEquals("Quality confirmed", verified.verificationNote());
        }

        @Test
        @DisplayName("add → assign → start → submitForApproval → rejectTask")
        void rejectionPath() {
            // add
            var task = list.addTask(new SharedTaskList.TaskItem("Write summary", "Write executive summary", 0));
            assertEquals(SharedTaskList.TaskStatus.PENDING, task.status());

            // assign
            list.assignTask(task.id(), "agent-2", "Agent Two");

            // start
            list.startTask(task.id());

            // submitForApproval
            list.submitForApproval(task.id(), "First draft");
            assertTrue(list.hasAwaitingApproval());

            // rejectTask
            var rejected = list.rejectTask(task.id(), "Insufficient coverage");
            assertEquals(SharedTaskList.TaskStatus.FAILED, rejected.status());
            assertEquals("Insufficient coverage", rejected.verificationNote());
            assertFalse(list.hasAwaitingApproval());
        }
    }
}
