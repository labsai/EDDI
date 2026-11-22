/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HITL (Human-in-the-Loop) lifecycle methods on
 * {@link SharedTaskList}: {@code submitForApproval}, {@code approveTask},
 * {@code rejectTask}, {@code resetToAssigned}, {@code resetFromAnyToAssigned},
 * and {@code hasAwaitingApproval}.
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
    // resetFromAnyToAssigned (RETRY re-queue)
    // =========================================================================

    @Nested
    @DisplayName("resetFromAnyToAssigned")
    class ResetFromAnyToAssigned {

        @Test
        @DisplayName("AWAITING_APPROVAL → ASSIGNED: result cleared, feedback stored, verified reset")
        void fromAwaitingApproval() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.submitForApproval(task.id(), "Draft v1");

            var reset = list.resetFromAnyToAssigned(task.id(), "Please add sources");

            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, reset.status());
            assertEquals("agent-1", reset.assignedAgentId(), "Assignment must be preserved for the retry");
            assertNull(reset.result(), "Prior result must be cleared before re-execution");
            assertEquals("Please add sources", reset.verificationNote(),
                    "Reviewer feedback must be stored so the agent knows what to fix");
            assertFalse(reset.verified(), "verified flag must be reset");
            assertNull(reset.completedAt(), "completedAt must be cleared");
        }

        @Test
        @DisplayName("IN_PROGRESS → ASSIGNED")
        void fromInProgress() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());

            var reset = list.resetFromAnyToAssigned(task.id(), "retry");

            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, reset.status());
            assertEquals("retry", reset.verificationNote());
        }

        @Test
        @DisplayName("FAILED → ASSIGNED")
        void fromFailed() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.failTask(task.id(), "boom");

            var reset = list.resetFromAnyToAssigned(task.id(), "try again");

            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, reset.status());
            assertEquals("try again", reset.verificationNote());
        }

        @Test
        @DisplayName("ASSIGNED is a no-op — returns the task unchanged")
        void fromAssignedIsNoOp() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");

            var reset = list.resetFromAnyToAssigned(task.id(), "ignored feedback");

            assertEquals(SharedTaskList.TaskStatus.ASSIGNED, reset.status());
            assertNull(reset.verificationNote(), "No-op must not overwrite state with reviewer feedback");
        }

        @Test
        @DisplayName("throws IllegalStateException from PENDING")
        void fromPending() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));

            assertThrows(IllegalStateException.class,
                    () -> list.resetFromAnyToAssigned(task.id(), "feedback"));
        }

        @Test
        @DisplayName("throws IllegalStateException from COMPLETED")
        void fromCompleted() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");

            assertThrows(IllegalStateException.class,
                    () -> list.resetFromAnyToAssigned(task.id(), "feedback"));
        }

        @Test
        @DisplayName("throws IllegalStateException from VERIFIED")
        void fromVerified() {
            var task = list.addTask(new SharedTaskList.TaskItem("Task", "desc", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");
            list.verifyTask(task.id(), true, "ok");

            assertThrows(IllegalStateException.class,
                    () -> list.resetFromAnyToAssigned(task.id(), "feedback"));
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
    // =========================================================================
    // approvals — the durable marker that a task was approved
    // =========================================================================

    @Nested
    @DisplayName("approvals")
    class Approvals {

        private String awaiting() {
            var task = list.addTask(new SharedTaskList.TaskItem("Post", "d", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.submitForApproval(task.id(), "draft");
            return task.id();
        }

        @Test
        @DisplayName("approveTask records the decider")
        void approveRecordsDecider() {
            String id = awaiting();
            list.approveTask(id, "alice");

            var approval = list.approvalOf(id);
            assertNotNull(approval);
            assertEquals("alice", approval.decidedBy());
            assertFalse(approval.edited());
            assertTrue(approval.byPerson());
            assertNotNull(approval.at());
        }

        @Test
        @DisplayName("a system:* decider is not a person; an unnamed one is")
        void systemDeciderIsNotAPerson() {
            assertFalse(new SharedTaskList.TaskApproval("system:timeout", "t", false).byPerson());
            assertFalse(new SharedTaskList.TaskApproval("system:no-progress", "t", false).byPerson());
            assertTrue(new SharedTaskList.TaskApproval(null, "t", false).byPerson());
            assertTrue(new SharedTaskList.TaskApproval("mcp:ops", "t", false).byPerson());
        }

        @Test
        @DisplayName("an edited approval is recorded as edited, by the editor")
        void editedApprovalRecorded() {
            String id = awaiting();
            list.approveTaskWithEditedResult(id, "better", new SharedTaskList.ReviewerEdit("bob", "t", "sha", 5));

            assertEquals("bob", list.approvalOf(id).decidedBy());
            assertTrue(list.approvalOf(id).edited());
        }

        @Test
        @DisplayName("a task that completes without a gate has no approval, nor does a rejected one")
        void noApprovalWithoutDecision() {
            var task = list.addTask(new SharedTaskList.TaskItem("Plain", "d", 0));
            list.assignTask(task.id(), "agent-1", "Agent One");
            list.startTask(task.id());
            list.completeTask(task.id(), "done");
            assertNull(list.approvalOf(task.id()));

            String rejected = awaiting();
            list.rejectTask(rejected, "no");
            assertNull(list.approvalOf(rejected));
            assertNull(list.approvalOf(null));
        }

        @Test
        @DisplayName("a reset for another attempt clears the approval of the superseded result")
        void resetClearsApproval() {
            String id = awaiting();
            list.approveTask(id, "alice");
            list.verifyTask(id, false, "redo");
            list.resetFromAnyToAssigned(id, "redo");

            assertNull(list.approvalOf(id));
        }

        @Test
        @DisplayName("a task list stored before approvals existed reads back with none, and approvals round-trip")
        void jsonCompatibility() throws Exception {
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String old = """
                    {"tasks":[{"id":"t1","subject":"s","description":"d","status":"COMPLETED","assignedAgentId":"a",
                     "assignedDisplayName":"A","dependsOnIds":[],"result":"r","verificationNote":null,"verified":false,
                     "priority":1,"createdAt":"2026-11-01T10:00:00Z","completedAt":"2026-11-01T11:00:00Z",
                     "createdByAgentId":null}],
                     "awardedBids":{},"reviewerEdits":{},"reworkCounts":{}}
                    """;
            SharedTaskList read = mapper.readValue(old, SharedTaskList.class);
            assertEquals(1, read.getTasks().size());
            assertTrue(read.getApprovals().isEmpty());
            assertNull(read.approvalOf("t1"));

            String id = awaiting();
            list.approveTask(id, "alice");
            SharedTaskList back = mapper.readValue(mapper.writeValueAsString(list), SharedTaskList.class);
            assertEquals(list.approvalOf(id), back.approvalOf(id));
        }
    }
}
