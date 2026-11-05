/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.HandoffOrigin;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.InboxItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Intake;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Edits;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamHandoffIntakeTest {

    static final String GROUP = "team-group";
    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    HandoffFakes.WorkspaceStore workspaces;
    AuditLedgerService audit;
    MeterRegistry meters;
    TeamHandoffIntake intake;

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new HandoffFakes.WorkspaceStore();
        audit = mock(AuditLedgerService.class);
        when(audit.isEnabled()).thenReturn(true);
        meters = new SimpleMeterRegistry();
        intake = new TeamHandoffIntake(workspaces, audit, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static HandoffOrigin origin(String id) {
        return new HandoffOrigin(id, SourceKind.AGENT, "agent-1", 2, "conv-1", null, Trigger.TOOL, null, null, null, 1, List.of("agent:agent-1"),
                "dk-" + id,
                NOW.minusSeconds(60), Intake.PENDING, null);
    }

    void inbox(String id, String subject) throws Exception {
        workspaces.mutate(GROUP, ws -> ws.getHandoffs().addInbox(new InboxItem(id, subject, "original description", 5, origin(id))));
    }

    GroupWorkspace workspace() throws Exception {
        return workspaces.readOrCreate(GROUP);
    }

    // ------------------------------------------------------------------ accept

    @Test
    void acceptingMovesTheItemOntoTheBacklogWithItsOrigin() throws Exception {
        inbox("h-1", "Warm lead");
        var result = intake.accept(GROUP, "h-1", null, "reviewer-1");
        assertEquals(Status.OK, result.status());
        GroupWorkspace ws = workspace();
        assertEquals(0, ws.getHandoffs().inboxSize());
        TaskItem task = ws.getBacklog().getTasks().get(0);
        assertEquals("Warm lead", task.subject());
        assertEquals("original description", task.description());
        assertEquals("PENDING", task.status().name());
        HandoffOrigin stored = ws.getHandoffs().originOf(task.id());
        assertEquals(Intake.APPROVED, stored.intake());
        assertEquals(NOW, stored.reviewedAt());
        assertEquals("agent:agent-1", stored.sourceKey());
        assertFalse(ws.getBacklog().findExecutableTasks().isEmpty(), "only now can a cadence pull it");
    }

    @Test
    void anEditedAcceptIsRecordedAsApprovedEdited() throws Exception {
        inbox("h-1", "Warm lead");
        var result = intake.accept(GROUP, "h-1", new Edits("Warm lead (Acme)", null, 9, "added the account name"), "reviewer-1");
        assertEquals(Status.OK, result.status());
        TaskItem task = workspace().getBacklog().getTasks().get(0);
        assertEquals("Warm lead (Acme)", task.subject());
        assertEquals(9, task.priority());
        assertEquals("original description", task.description());
        assertEquals(Intake.APPROVED_EDITED, workspace().getHandoffs().originOf(task.id()).intake());
        var entry = lastAudit();
        assertEquals("APPROVED_EDITED", entry.output().get("outcome"));
        assertEquals(List.of("subject", "priority"), entry.output().get("editedFields"));
    }

    @Test
    void editsThatChangeNothingAreStillApprovedUnedited() throws Exception {
        inbox("h-1", "Warm lead");
        intake.accept(GROUP, "h-1", new Edits("Warm lead", "original description", 5, null), "r");
        assertEquals("APPROVED", lastAudit().output().get("outcome"));
    }

    @Test
    void acceptWritesTheSharedAuditVocabularyAndCountsIt() throws Exception {
        inbox("h-1", "Warm lead");
        intake.accept(GROUP, "h-1", null, "reviewer-1");
        AuditEntry entry = lastAudit();
        assertEquals("team.handoff.intake", entry.taskId());
        assertEquals("reviewer-1", entry.userId());
        assertEquals("conv-1", entry.conversationId());
        assertEquals("agent-1", entry.agentId());
        assertEquals("team_handoff_intake", entry.output().get("approvalCategory"));
        assertEquals("APPROVED", entry.output().get("outcome"));
        assertEquals(GROUP, entry.output().get("targetGroupId"));
        assertEquals(1.0, meters.get("eddi_team_handoff_intake_total").tag("outcome", "approved").counter().count());
    }

    @Test
    void anUnknownIdIsNotFoundAndWritesNothing() throws Exception {
        var result = intake.accept(GROUP, "nope", null, "r");
        assertEquals(Status.NOT_FOUND, result.status());
        verify(audit, never()).submit(any());
    }

    @Test
    void acceptingTwiceDecidesOnce() throws Exception {
        inbox("h-1", "Warm lead");
        assertEquals(Status.OK, intake.accept(GROUP, "h-1", null, "r").status());
        assertEquals(Status.NOT_FOUND, intake.accept(GROUP, "h-1", null, "r").status());
        assertEquals(1, workspace().getBacklog().size());
    }

    @Test
    void aFullBacklogRefusesAndKeepsTheItemPending() throws Exception {
        workspaces.mutate(GROUP, ws -> {
            for (int i = 0; i < GroupWorkspace.MAX_BACKLOG_SIZE; i++) {
                ws.getBacklog().addTask(new TaskItem("t" + i, "", 1));
            }
        });
        inbox("h-1", "Warm lead");
        assertEquals(Status.BACKLOG_FULL, intake.accept(GROUP, "h-1", null, "r").status());
        assertEquals(1, workspace().getHandoffs().inboxSize());
    }

    @Test
    void aDuplicateSubjectNeedsAnEditedSubject() throws Exception {
        workspaces.mutate(GROUP, ws -> ws.getBacklog().addTask(new TaskItem("warm LEAD", "", 1)));
        inbox("h-1", "Warm lead");
        assertEquals(Status.DUPLICATE, intake.accept(GROUP, "h-1", null, "r").status());
        assertEquals(1, workspace().getHandoffs().inboxSize());
        assertEquals(Status.OK, intake.accept(GROUP, "h-1", new Edits("Warm lead - Acme", null, null, null), "r").status());
    }

    @Test
    void invalidEditsAreRejected() throws Exception {
        inbox("h-1", "Warm lead");
        assertEquals(Status.INVALID, intake.accept(GROUP, "h-1", new Edits("   ", null, null, null), "r").status());
        assertEquals(Status.INVALID, intake.accept(GROUP, "h-1", new Edits("s".repeat(201), null, null, null), "r").status());
        assertEquals(Status.INVALID, intake.accept(GROUP, "h-1", new Edits(null, "d".repeat(4001), null, null), "r").status());
        assertEquals(1, workspace().getHandoffs().inboxSize());
    }

    @Test
    void editedTextGetsTheSameHygieneAsFiledText() throws Exception {
        inbox("h-1", "Warm lead");
        intake.accept(GROUP, "h-1", new Edits("Line\none" + new String(Character.toChars(0x202E)), null, null, null), "r");
        assertEquals("Line one", workspace().getBacklog().getTasks().get(0).subject());
    }

    @Test
    void aLostCasIsRetriedAndAnItemAnotherReviewerDecidedMeanwhileIsNotFound() throws Exception {
        inbox("h-1", "Warm lead");
        workspaces.beforeNextCas = () -> {
            try {
                workspaces.mutate(GROUP, ws -> ws.getHandoffs().removeInbox("h-1"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(Status.NOT_FOUND, intake.accept(GROUP, "h-1", null, "r").status());
        assertEquals(0, workspace().getBacklog().size(), "the second reviewer must not add a copy");
    }

    @Test
    void aStoreFailureIsUnavailableNotAnException() throws Exception {
        inbox("h-1", "Warm lead");
        workspaces.failWrites = true;
        assertEquals(Status.UNAVAILABLE, intake.accept(GROUP, "h-1", null, "r").status());
        workspaces.failWrites = false;
        assertEquals(1, workspace().getHandoffs().inboxSize());
    }

    // ----------------------------------------------------------------- decline

    @Test
    void decliningRemovesTheItemRemembersItAndAudits() throws Exception {
        inbox("h-1", "Warm lead");
        var result = intake.decline(GROUP, "h-1", "out_of_scope", "not ours", "reviewer-2");
        assertEquals(Status.OK, result.status());
        GroupWorkspace ws = workspace();
        assertEquals(0, ws.getHandoffs().inboxSize());
        assertEquals(0, ws.getBacklog().size());
        assertTrue(ws.getHandoffs().isDeclinedSince("dk-h-1", NOW.minusSeconds(5)));
        AuditEntry entry = lastAudit();
        assertEquals("REJECTED", entry.output().get("outcome"));
        assertEquals("out_of_scope", entry.output().get("reasonCode"));
        assertEquals("team_handoff_intake", entry.output().get("approvalCategory"));
        assertEquals("not ours", entry.output().get("note"));
        assertEquals(1.0, meters.get("eddi_team_handoff_intake_total").tag("outcome", "rejected").counter().count());
    }

    @Test
    void decliningAnUnknownIdIsNotFound() {
        assertEquals(Status.NOT_FOUND, intake.decline(GROUP, "nope", null, null, "r").status());
    }

    @Test
    void aLongNoteAndReasonAreBoundedInTheAudit() throws Exception {
        inbox("h-1", "Warm lead");
        intake.decline(GROUP, "h-1", "r".repeat(500), "n".repeat(5000), "r");
        AuditEntry entry = lastAudit();
        assertEquals(TeamHandoffIntake.MAX_REASON_CODE_CHARS, ((String) entry.output().get("reasonCode")).length());
        assertEquals(TeamHandoffIntake.MAX_NOTE_CHARS, ((String) entry.output().get("note")).length());
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deletingATaskDropsItsOriginAndTheDependencyEdgesToIt() throws Exception {
        inbox("h-1", "Warm lead");
        intake.accept(GROUP, "h-1", null, "r");
        String taskId = workspace().getBacklog().getTasks().get(0).id();
        workspaces.mutate(GROUP, ws -> {
            var dependent = new TaskItem("child", "", 1);
            ws.getBacklog().addTask(new TaskItem(dependent.id(), "child", "", dependent.status(), null, null, List.of(taskId), null, null, false, 1,
                    dependent.createdAt(), null, null));
        });
        var result = intake.deleteBacklogTask(GROUP, taskId, "r");
        assertEquals(Status.OK, result.status());
        GroupWorkspace ws = workspace();
        assertEquals(1, ws.getBacklog().size());
        assertNull(ws.getHandoffs().originOf(taskId));
        assertTrue(ws.getBacklog().getTasks().get(0).dependsOnIds().isEmpty(), "a dependency on a deleted task would wait forever");
    }

    @Test
    void aTaskThatARunningDiscussionHasPulledCannotBeDeleted() throws Exception {
        workspaces.mutate(GROUP, ws -> {
            TaskItem t = ws.getBacklog().addTask(new TaskItem("busy", "", 1));
            ws.setRunningDiscussionId("gc-9");
            ws.setPulledTaskIds(List.of(t.id()));
        });
        String taskId = workspace().getBacklog().getTasks().get(0).id();
        assertEquals(Status.CONFLICT, intake.deleteBacklogTask(GROUP, taskId, "r").status());
        assertEquals(1, workspace().getBacklog().size());
    }

    @Test
    void deletingAnUnknownTaskIsNotFound() {
        assertEquals(Status.NOT_FOUND, intake.deleteBacklogTask(GROUP, "nope", "r").status());
    }

    @Test
    void viewReturnsInboxAndOrigins() throws Exception {
        inbox("h-1", "Warm lead");
        var view = intake.view(GROUP);
        assertEquals(1, view.inbox().size());
        assertTrue(view.origins().isEmpty());
    }

    private AuditEntry lastAudit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, atLeastOnce()).submit(captor.capture());
        return captor.getValue();
    }
}
