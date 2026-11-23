/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.engine.security.TurnOrigin;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The origin taint on the team backlog (email plan v3 §5.3): a task a tainted
 * turn files or changes carries it, and nothing that rewrites the task drops
 * it.
 */
@DisplayName("Backlog taint — stamped on every write, sticky through every copy")
class BacklogTaintTest {

    @Test
    @DisplayName("a task filed in a tainted turn is tainted; one filed outside is not")
    void addTaskStampsTheTurn() throws Exception {
        SharedTaskList backlog = new SharedTaskList();
        TaskItem tainted = TurnOrigin.callWith(TurnOrigin.EMAIL, () -> backlog.addTask(new TaskItem("mail", "d", 1)));
        TaskItem clean = backlog.addTask(new TaskItem("person", "d", 1));

        assertEquals(TurnOrigin.EMAIL, backlog.findById(tainted.id()).origin());
        assertEquals(TurnOrigin.EMAIL, tainted.origin(), "the returned task is the stored one");
        assertNull(backlog.findById(clean.id()).origin());
    }

    @Test
    @DisplayName("a member's task tool in a tainted turn files a tainted task")
    void addAgentTaskStampsTheTurn() throws Exception {
        SharedTaskList list = new SharedTaskList();
        var result = TurnOrigin.callWith(TurnOrigin.EMAIL, () -> list.addAgentTask("mail", "d", List.of(), 1, "agent-1"));
        assertEquals(TurnOrigin.EMAIL, list.findById(result.task().id()).origin());
    }

    @Test
    @DisplayName("status transitions and positional copies keep the taint; an untainted write never clears it")
    void stickyThroughCopies() throws Exception {
        SharedTaskList backlog = new SharedTaskList();
        TaskItem task = backlog.addTask(new TaskItem("mail", "d", 1).taintedBy(TurnOrigin.EMAIL));

        backlog.assignTask(task.id(), "agent-1", "Agent");
        backlog.startTask(task.id());
        backlog.completeTask(task.id(), "done");
        assertEquals(TurnOrigin.EMAIL, backlog.findById(task.id()).origin(), "status transitions");

        // The 15-component form every older call site uses carries no origin.
        TaskItem stored = backlog.findById(task.id());
        backlog.updateTask(new TaskItem(stored.id(), stored.subject(), stored.description(), TaskStatus.PENDING, null, null, List.of(), null,
                null, false, stored.priority(), stored.createdAt(), null, null, null));
        assertEquals(TurnOrigin.EMAIL, backlog.findById(task.id()).origin(), "a positional copy");
    }

    /**
     * The record a lifecycle method returns is the one it stored: its own
     * positional copy has no origin, and a caller that persists or forwards the
     * returned record would otherwise drop the taint.
     */
    @Test
    @DisplayName("every lifecycle transition returns the stored record, taint included")
    void transitionsReturnTheStoredRecord() throws Exception {
        SharedTaskList backlog = new SharedTaskList();
        String id = backlog.addTask(new TaskItem("mail", "d", 1).taintedBy(TurnOrigin.EMAIL)).id();

        assertEquals(TurnOrigin.EMAIL, backlog.assignTask(id, "agent-1", "Agent").origin(), "assign");
        assertEquals(TurnOrigin.EMAIL, backlog.startTask(id).origin(), "start");
        assertEquals(TurnOrigin.EMAIL, backlog.resetToAssigned(id).origin(), "resetToAssigned");
        backlog.startTask(id);
        assertEquals(TurnOrigin.EMAIL, backlog.submitForApproval(id, "draft").origin(), "submitForApproval");
        assertEquals(TurnOrigin.EMAIL, backlog.rejectTask(id, "no").origin(), "reject");
        assertEquals(TurnOrigin.EMAIL, backlog.resetFromAnyToAssigned(id, "again").origin(), "resetFromAnyToAssigned");
        backlog.startTask(id);
        backlog.submitForApproval(id, "draft");
        assertEquals(TurnOrigin.EMAIL, backlog.approveTask(id, "alice").origin(), "approve");
        assertEquals(TurnOrigin.EMAIL, backlog.verifyTask(id, true, "ok").origin(), "verify");

        String second = backlog.addTask(new TaskItem("mail 2", "d", 1).taintedBy(TurnOrigin.EMAIL)).id();
        backlog.assignTask(second, "agent-1", "Agent");
        backlog.startTask(second);
        assertEquals(TurnOrigin.EMAIL, backlog.completeTask(second, "done").origin(), "complete");

        String third = backlog.addTask(new TaskItem("mail 3", "d", 1).taintedBy(TurnOrigin.EMAIL)).id();
        assertEquals(TurnOrigin.EMAIL, backlog.failTask(third, "broken").origin(), "fail");

        String fourth = backlog.addTask(new TaskItem("mail 4", "d", 1).taintedBy(TurnOrigin.EMAIL)).id();
        backlog.assignTask(fourth, "agent-1", "Agent");
        backlog.startTask(fourth);
        backlog.submitForApproval(fourth, "draft");
        assertEquals(TurnOrigin.EMAIL, backlog.approveTaskWithEditedResult(fourth, "edited", null).origin(), "approve with an edit");
    }

    @Test
    @DisplayName("an untainted task that a tainted turn changes becomes tainted")
    void taintedChangeTaints() throws Exception {
        SharedTaskList backlog = new SharedTaskList();
        TaskItem task = backlog.addTask(new TaskItem("person", "d", 1));
        TaskItem stored = backlog.findById(task.id());
        TurnOrigin.callWith(TurnOrigin.EMAIL, () -> {
            backlog.updateTask(new TaskItem(stored.id(), stored.subject(), "rewritten by a mail", stored.status(), null, null, List.of(), null,
                    null, false, stored.priority(), stored.createdAt(), null, null, null));
            return null;
        });
        assertEquals(TurnOrigin.EMAIL, backlog.findById(task.id()).origin());
    }

    @Test
    @DisplayName("a reviewed handoff keeps the taint of the turn that filed it")
    void reviewedHandoffKeepsTaint() {
        var origin = new WorkspaceHandoffs.HandoffOrigin("h-1", WorkspaceHandoffs.SourceKind.AGENT, "agent-1", 1, "conv-1", null,
                WorkspaceHandoffs.Trigger.TOOL, null, null, null, 1, List.of(), "dk", Instant.parse("2026-11-02T10:00:00Z"),
                WorkspaceHandoffs.Intake.PENDING, null, TurnOrigin.EMAIL);
        assertEquals(TurnOrigin.EMAIL, origin.reviewed(WorkspaceHandoffs.Intake.APPROVED, Instant.now()).turnOrigin());
    }

    @Test
    @DisplayName("the taint survives JSON, and an old document without it reads as untainted")
    void json() throws Exception {
        // As the datastores' document builder reads: unknown members (the derived
        // "empty") are ignored.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SharedTaskList backlog = new SharedTaskList();
        TaskItem task = backlog.addTask(new TaskItem("mail", "d", 1).taintedBy(TurnOrigin.EMAIL));
        SharedTaskList back = mapper.readValue(mapper.writeValueAsString(backlog), SharedTaskList.class);
        assertEquals(TurnOrigin.EMAIL, back.findById(task.id()).origin());

        TaskItem old = mapper.readValue("{\"id\":\"t\",\"subject\":\"s\",\"status\":\"PENDING\",\"priority\":1}", TaskItem.class);
        assertNull(old.origin());
    }

    @Test
    @DisplayName("GroupConversation.setOrigin never clears or replaces a set taint")
    void groupConversationOriginSticky() {
        GroupConversation gc = new GroupConversation();
        gc.setOrigin(TurnOrigin.EMAIL);
        gc.setOrigin(null);
        assertEquals(TurnOrigin.EMAIL, gc.getOrigin());
    }
}
