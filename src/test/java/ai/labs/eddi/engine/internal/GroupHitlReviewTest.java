/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.hitl.model.GroupHitlReviewConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.EditSummary;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Group approvals — edited task results, synthesis edits and reason codes")
class GroupHitlReviewTest {

    private static final String GROUP_ID = "group-review";
    private static final String GC_ID = "gc-review";

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IScheduleStore scheduleStore;

    private AuditLedgerService ledger;
    private GroupConversationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        service = new GroupConversationService(groupStore, conversationStore, mock(IConversationService.class), mock(IAgentFactory.class),
                mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(), mock(AgentSigningService.class),
                mock(IAgentStore.class), scheduleStore, mock(NonceCacheService.class), ledger, new CallerIdentityContext(null, null), "default", 3);
    }

    private void groupConfig(boolean allowEdits, List<DiscussionPhase> phases) throws Exception {
        var config = new AgentGroupConfiguration();
        config.setName("Review");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember("agent-a", "Agent A", 1, null)));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        var review = new GroupHitlReviewConfig();
        review.setAllowResultEdits(allowEdits);
        review.setPhaseCategories(Map.of("Execute", "directory.submission"));
        hitl.setReview(review);
        config.setHitlConfig(hitl);
        IResourceStore.IResourceId id = new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
        doReturn(id).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
    }

    private GroupConversation taskPause() {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("owner");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Execute");
        gc.setPausedAt(Instant.now().minusSeconds(30));
        gc.setHitlPauseType(GroupConversation.HitlPauseType.TASK);
        gc.setOriginalQuestion("q");
        var taskList = new SharedTaskList();
        taskList.addTask(new SharedTaskList.TaskItem("A", "Do A", 0));
        taskList.addTask(new SharedTaskList.TaskItem("B", "Do B", 0));
        for (var task : taskList.all()) {
            taskList.assignTask(task.id(), "agent-a", "Agent A");
            taskList.startTask(task.id());
            taskList.submitForApproval(task.id(), "Result of " + task.subject());
        }
        gc.setTaskList(taskList);
        return gc;
    }

    private static HitlDecision approved() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("bob");
        return decision;
    }

    private static TaskDecision taskDecision(HitlVerdict verdict, String edit, String note, String reasonCode) {
        var task = new TaskDecision();
        task.setVerdict(verdict);
        task.setEditedResult(edit);
        task.setNote(note);
        task.setReasonCode(reasonCode);
        return task;
    }

    private AuditEntry submitted() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger, atLeastOnce()).submit(captor.capture());
        return captor.getAllValues().stream().filter(e -> HitlApprovalAudit.TASK_ID.equals(e.taskId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an edited result replaces the task's result, is noted, and is audited as APPROVED_EDITED")
    void editedTaskResult() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Execute", PhaseType.EXECUTE)));
        var gc = taskPause();
        doReturn(gc).when(conversationStore).read(GC_ID);
        var tasks = gc.getTaskList().all();
        String a = tasks.get(0).id();
        String b = tasks.get(1).id();
        var request = new GroupApprovalRequest();
        request.setDecision(approved());
        request.setTaskDecisions(Map.of(a, taskDecision(HitlVerdict.APPROVED, "Better result of A", null, "FACTUAL_ERROR"),
                b, taskDecision(HitlVerdict.REJECTED, null, "B is off-topic", "SCOPE")));

        service.resumeDiscussion(GC_ID, request, null);

        var taskA = gc.getTaskList().findById(a);
        assertEquals(TaskStatus.COMPLETED, taskA.status());
        assertEquals("Better result of A", taskA.result(), "dependents and the summary read the edit");
        assertNotNull(gc.getTaskList().getReviewerEdits().get(a));
        assertEquals("bob", gc.getTaskList().getReviewerEdits().get(a).decidedBy());
        assertEquals("B is off-topic", gc.getTaskList().findById(b).verificationNote(), "the per-task note wins");

        List<HitlDecisionUnit> units = HitlApprovalAudit.parse(submitted());
        HitlDecisionUnit unitA = units.stream().filter(u -> a.equals(u.unitId())).findFirst().orElseThrow();
        assertEquals(DecisionOutcome.APPROVED_EDITED, unitA.decisionOutcome());
        assertEquals(EditSummary.Kind.TASK_RESULT, unitA.edit().kind());
        assertEquals("directory.submission", unitA.approvalCategory());
        assertEquals("FACTUAL_ERROR", unitA.reasonCode());
        HitlDecisionUnit unitB = units.stream().filter(u -> b.equals(u.unitId())).findFirst().orElseThrow();
        assertEquals(DecisionOutcome.REJECTED, unitB.decisionOutcome());
        assertEquals("SCOPE", unitB.reasonCode());
        assertEquals("TASK", unitB.pauseType());
        assertNotNull(unitB.latencyMs(), "the pause was captured before it was cleared");
    }

    @Test
    @DisplayName("taskDecisions and taskApprovals together: 400, nothing mutated, no CAS")
    void mutuallyExclusive() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Execute", PhaseType.EXECUTE)));
        var gc = taskPause();
        doReturn(gc).when(conversationStore).read(GC_ID);
        String a = gc.getTaskList().all().get(0).id();
        var request = new GroupApprovalRequest();
        request.setDecision(approved());
        request.setTaskApprovals(Map.of(a, "APPROVED"));
        request.setTaskDecisions(Map.of(a, taskDecision(HitlVerdict.APPROVED, "x", null, null)));

        assertThrows(IllegalArgumentException.class, () -> service.resumeDiscussion(GC_ID, request, null));
        assertTrue(gc.getTaskList().hasAwaitingApproval());
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    @Test
    @DisplayName("an edited result without allowResultEdits: 400 before any mutation")
    void editsNotAllowed() throws Exception {
        groupConfig(false, List.of(new DiscussionPhase("Execute", PhaseType.EXECUTE)));
        var gc = taskPause();
        doReturn(gc).when(conversationStore).read(GC_ID);
        String a = gc.getTaskList().all().get(0).id();
        var request = new GroupApprovalRequest();
        request.setDecision(approved());
        request.setTaskDecisions(Map.of(a, taskDecision(HitlVerdict.APPROVED, "x", null, null)));

        var e = assertThrows(IllegalArgumentException.class, () -> service.resumeDiscussion(GC_ID, request, null));
        assertTrue(e.getMessage().contains("allowResultEdits"));
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    @Test
    @DisplayName("an edited result on a rejected task, or a reason on a plain approval: 400")
    void invalidCombinations() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Execute", PhaseType.EXECUTE)));
        var gc = taskPause();
        doReturn(gc).when(conversationStore).read(GC_ID);
        String a = gc.getTaskList().all().get(0).id();
        var request = new GroupApprovalRequest();
        request.setDecision(approved());
        request.setTaskDecisions(Map.of(a, taskDecision(HitlVerdict.REJECTED, "x", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.resumeDiscussion(GC_ID, request, null));

        request.setTaskDecisions(Map.of(a, taskDecision(HitlVerdict.APPROVED, null, null, "TONE")));
        assertThrows(IllegalArgumentException.class, () -> service.resumeDiscussion(GC_ID, request, null));
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    private GroupConversation phasePause(String phaseName) {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("owner");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName(phaseName);
        gc.setPausedAt(Instant.now());
        gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);
        gc.setOriginalQuestion("q");
        gc.getTranscript().add(new TranscriptEntry("mod", "Moderator", "Option A wins.", 0, phaseName, TranscriptEntryType.SYNTHESIS,
                Instant.now(), null, null));
        return gc;
    }

    @Test
    @DisplayName("a SYNTHESIS edit is appended as the reviewer's latest synthesis; the signed original stays")
    void synthesisEdit() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
        var gc = phasePause("Synthesis");
        doReturn(gc).when(conversationStore).read(GC_ID);
        var decision = approved();
        decision.setEditedAnswer("Option B wins, because of cost.");
        var request = new GroupApprovalRequest();
        request.setDecision(decision);

        service.resumeDiscussion(GC_ID, request, null);

        TranscriptEntry original = gc.getTranscript().get(0);
        assertEquals("Option A wins.", original.content());
        TranscriptEntry edit = gc.getTranscript().stream().filter(e -> "reviewer-edit".equals(e.errorReason())).findFirst().orElseThrow();
        assertEquals(TranscriptEntryType.SYNTHESIS, edit.type());
        assertEquals("hitl:bob", edit.speakerAgentId());
        assertEquals("Option B wins, because of cost.", edit.content());
        HitlDecisionUnit unit = HitlApprovalAudit.parse(submitted()).get(0);
        assertEquals(DecisionOutcome.APPROVED_EDITED, unit.decisionOutcome());
        assertEquals(EditSummary.Kind.SYNTHESIS, unit.edit().kind());
        assertEquals("Synthesis", unit.unitId());
    }

    @Test
    @DisplayName("the original synthesis is the current round's: an earlier round's identical text is still an edit")
    void synthesisFromAnEarlierRoundIsNotTheOriginal() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
        var gc = phasePause("Synthesis");
        // the only synthesis belongs to the previous round; this round produced none
        gc.setRoundStartTranscriptIndex(1);
        doReturn(gc).when(conversationStore).read(GC_ID);
        var decision = approved();
        decision.setEditedAnswer("Option A wins.");
        var request = new GroupApprovalRequest();
        request.setDecision(decision);

        service.resumeDiscussion(GC_ID, request, null);

        assertTrue(gc.getTranscript().stream().anyMatch(e -> "reviewer-edit".equals(e.errorReason())),
                "the reviewer's text is applied even though it equals a previous round's synthesis");
    }

    @Test
    @DisplayName("a resume that fails after the SYNTHESIS edit was appended leaves no reviewer edit behind")
    void failedResumeRemovesTheSynthesisEdit() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
        var gc = phasePause("Synthesis");
        doReturn(gc).when(conversationStore).read(GC_ID);
        // The decision is validated against the real config on the calling thread; the
        // resume work then re-reads a config whose phase at that index has been
        // renamed.
        var original = groupStore.read(GROUP_ID, 1);
        var drifted = new AgentGroupConfiguration();
        drifted.setName("Review");
        drifted.setStyle(DiscussionStyle.CUSTOM);
        drifted.setPhases(List.of(new DiscussionPhase("Renamed", PhaseType.SYNTHESIS)));
        drifted.setMembers(original.getMembers());
        drifted.setHitlConfig(original.getHitlConfig());
        final Thread caller = Thread.currentThread();
        doAnswer(inv -> Thread.currentThread() == caller ? original : drifted).when(groupStore).read(GROUP_ID, 1);
        var decision = approved();
        decision.setEditedAnswer("Option B wins, because of cost.");
        var request = new GroupApprovalRequest();
        request.setDecision(decision);

        service.resumeDiscussion(GC_ID, request, null);

        // The worker adds the ERROR entry first, then drops the edit, then restores the
        // pause: AWAITING_APPROVAL only comes back once the rollback is complete (the
        // CAS left the conversation IN_PROGRESS).
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
            Thread.sleep(25);
        }
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(), "the drift rolled the resume back");
        List<TranscriptEntry> transcript;
        synchronized (gc.getTranscript()) {
            transcript = List.copyOf(gc.getTranscript());
        }
        assertTrue(transcript.stream().anyMatch(e -> e.type() == TranscriptEntryType.ERROR), "the drift is recorded");
        assertTrue(transcript.stream().noneMatch(e -> "reviewer-edit".equals(e.errorReason())),
                "the failed attempt's edit must not survive for the next approval to build on");
    }

    @Test
    @DisplayName("editing the output of a phase that is not a SYNTHESIS: 400")
    void nonSynthesisPhase() throws Exception {
        groupConfig(true, List.of(new DiscussionPhase("Critique", PhaseType.CRITIQUE)));
        var gc = phasePause("Critique");
        doReturn(gc).when(conversationStore).read(GC_ID);
        var decision = approved();
        decision.setEditedAnswer("x");
        var request = new GroupApprovalRequest();
        request.setDecision(decision);

        var e = assertThrows(IllegalArgumentException.class, () -> service.resumeDiscussion(GC_ID, request, null));
        assertTrue(e.getMessage().contains("synthesis"));
        verify(conversationStore, never()).updateIfState(any(), any());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }
}
