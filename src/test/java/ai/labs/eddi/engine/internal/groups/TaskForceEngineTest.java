/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.AssignmentMode;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link TaskForceEngine}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 6) refactor.
 * Covers the self-contained methods (task assignment resolution, member lookup,
 * verification JSON parsing/formatting, task input building) directly. The
 * EXECUTE-phase wave loop — cooperative cancellation, lock ordering, wave
 * timeout/abort — is already thoroughly exercised via reflection through the
 * facade's delegators by {@code GroupConversationServiceConcurrencyTest} and
 * {@code GroupConversationServiceHitlCoverage3Test}, re-verified green against
 * this class post-extraction rather than duplicated here.
 *
 * @author tests
 */
class TaskForceEngineTest {

    private static final String AGENT_A = "agent-a";
    private static final String AGENT_B = "agent-b";
    private static final String MODERATOR = "mod-agent";

    private ITemplatingEngine templatingEngine;
    private IJsonSerialization jsonSerialization;
    private MemberTurnExecutor memberTurnExecutor;

    private TaskForceEngine engine() {
        templatingEngine = mock(ITemplatingEngine.class);
        jsonSerialization = mock(IJsonSerialization.class);
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        // As for a member that does not sign: the entry comes back as it went in.
        when(memberTurnExecutor.sign(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        return new TaskForceEngine(memberTurnExecutor, templatingEngine, jsonSerialization,
                Executors.newVirtualThreadPerTaskExecutor(), new CallerIdentityContext(null, null),
                new ConcurrentHashMap<>(), 180, 5);
    }

    private GroupMember member(String id) {
        return new GroupMember(id, id, 1, null);
    }

    // =================================================================
    // resolveTaskAssignment
    // =================================================================

    @Test
    void resolveTaskAssignment_all_roundRobinsAcrossNonModeratorMembers() {
        var members = List.of(member(MODERATOR), member(AGENT_A), member(AGENT_B));
        var engine = engine();

        assertEquals(AGENT_A, engine.resolveTaskAssignment("ALL", members, MODERATOR, 0));
        assertEquals(AGENT_B, engine.resolveTaskAssignment("ALL", members, MODERATOR, 1));
        assertEquals(AGENT_A, engine.resolveTaskAssignment("ALL", members, MODERATOR, 2)); // wraps around
    }

    /**
     * W8: configured tasks used to become a task list only inside the PLAN phase,
     * so a CUSTOM group with an EXECUTE phase and no PLAN silently executed
     * nothing. PLAN and EXECUTE now share this materialization.
     */
    @Test
    void materializeConfiguredTasks_buildsListWithDependenciesAndAssignments() {
        var config = new AgentGroupConfiguration();
        config.setModeratorAgentId(MODERATOR);
        config.setMembers(List.of(member(MODERATOR), member(AGENT_A), member(AGENT_B)));
        config.setTasks(List.of(new TaskDefinition("Research", "gather facts", "ALL", List.of(), 1),
                new TaskDefinition("Write", "write it up", "ALL", List.of("Research"), 2)));
        var gc = new GroupConversation();
        var phase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL", TurnOrder.PARALLEL, ContextScope.TASK_ONLY, false, null, 1, false);

        engine().materializeConfiguredTasks(gc, config, 0, phase);

        List<TaskItem> tasks = gc.getTaskList().all();
        assertEquals(2, tasks.size());
        TaskItem research = tasks.stream().filter(t -> t.subject().equals("Research")).findFirst().orElseThrow();
        TaskItem write = tasks.stream().filter(t -> t.subject().equals("Write")).findFirst().orElseThrow();
        assertEquals(List.of(research.id()), write.dependsOnIds());
        assertEquals(AGENT_A, research.assignedAgentId());
        assertEquals(AGENT_B, write.assignedAgentId());
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.PLAN));
    }

    @Test
    void resolveTaskAssignment_null_treatedAsAll() {
        var members = List.of(member(MODERATOR), member(AGENT_A));
        assertEquals(AGENT_A, engine().resolveTaskAssignment(null, members, MODERATOR, 0));
    }

    @Test
    void resolveTaskAssignment_roleReference_matchesMemberRole() {
        var editor = new GroupMember(AGENT_A, "A", 1, "EDITOR");
        var members = List.of(member(MODERATOR), editor);
        assertEquals(AGENT_A, engine().resolveTaskAssignment("ROLE:EDITOR", members, MODERATOR, 0));
    }

    @Test
    void resolveTaskAssignment_roleReference_noMatch_returnsNull() {
        var members = List.of(member(MODERATOR), member(AGENT_A));
        assertNull(engine().resolveTaskAssignment("ROLE:NOBODY", members, MODERATOR, 0));
    }

    @Test
    void resolveTaskAssignment_allWithNoEligibleMembers_fallsBackToFirstMember() {
        var members = List.of(member(MODERATOR));
        assertEquals(MODERATOR, engine().resolveTaskAssignment("ALL", members, MODERATOR, 0));
    }

    // =================================================================
    // findMember / findMemberIncludingDynamic
    // =================================================================

    @Test
    void findMember_present_returnsMatch() {
        var members = List.of(member(AGENT_A), member(AGENT_B));
        assertEquals(AGENT_B, engine().findMember(members, AGENT_B).agentId());
    }

    @Test
    void findMember_absent_returnsNull() {
        assertNull(engine().findMember(List.of(member(AGENT_A)), "unknown"));
    }

    @Test
    void findMember_nullAgentId_returnsNull() {
        assertNull(engine().findMember(List.of(member(AGENT_A)), null));
    }

    @Test
    void findMemberIncludingDynamic_fallsBackToDynamicMembers() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getDynamicMembers().add(member(AGENT_B));

        var found = engine().findMemberIncludingDynamic(List.of(member(AGENT_A)), gc, AGENT_B);

        assertNotNull(found);
        assertEquals(AGENT_B, found.agentId());
    }

    @Test
    void findMemberIncludingDynamic_notFoundAnywhere_returnsNull() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        assertNull(engine().findMemberIncludingDynamic(List.of(member(AGENT_A)), gc, "unknown"));
    }

    // =================================================================
    // tryParseVerificationJson / formatVerificationForDisplay
    // =================================================================

    @Test
    @SuppressWarnings("unchecked")
    void tryParseVerificationJson_wellFormed_verifiesMatchingTask() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setTaskList(new SharedTaskList());
        var task = new TaskItem("Write the report", "desc", 1);
        gc.getTaskList().addTask(task);
        gc.getTaskList().assignTask(task.id(), AGENT_A, "A");
        gc.getTaskList().startTask(task.id());
        gc.getTaskList().completeTask(task.id(), "done");
        var completedTask = gc.getTaskList().findById(task.id()); // TaskItem is immutable — re-fetch post-completion state

        var engine = engine();
        when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                .thenReturn(List.of(Map.of("subject", "Write the report", "passed", true, "feedback", "Nice job")));

        boolean result = engine.tryParseVerificationJson(gc, List.of(completedTask), "[{\"subject\":\"Write the report\"}]", null);

        assertTrue(result);
        assertTrue(gc.getTaskList().findById(task.id()).verified());
    }

    @Test
    void tryParseVerificationJson_noBrackets_returnsFalseWithoutCallingDeserializer() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        boolean result = engine().tryParseVerificationJson(gc, List.of(), "not json at all", null);

        assertFalse(result);
        verifyNoInteractions(jsonSerialization);
    }

    // =================================================================
    // Stale-snapshot regressions (`completedTasks` is captured before the
    // verification phase runs and TaskItem is immutable, so its status() never
    // reflects a verdict applied during the phase).
    // =================================================================

    /**
     * A verifier LLM repeating the same subject twice used to abort the whole
     * parse: the second match re-verified an already-VERIFIED task, tripping
     * {@code verifyTask}'s {@code requireStatus(COMPLETED)} guard. The
     * {@code catch} then returned false and every verdict after the duplicate was
     * silently dropped. The duplicate must be skipped instead.
     */
    @Test
    @SuppressWarnings("unchecked")
    void tryParseVerificationJson_duplicateSubject_skipsSecondInsteadOfAborting() throws Exception {
        var gc = completedTaskConversation("Write the report");
        var snapshot = List.of(gc.getTaskList().getTasks().get(0));

        var engine = engine();
        when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(
                Map.of("subject", "Write the report", "passed", true, "feedback", "first verdict"),
                Map.of("subject", "Write the report", "passed", false, "feedback", "duplicate")));

        boolean result = engine.tryParseVerificationJson(gc, snapshot, "[]", null);

        assertTrue(result, "the first verdict was applied, so the parse succeeded");
        var live = gc.getTaskList().getTasks().get(0);
        assertTrue(live.verified(), "first verdict stands");
        assertEquals("first verdict", live.verificationNote(), "the duplicate must not overwrite the first verdict");
    }

    /**
     * The fallback loop runs when JSON parsing fails — including when it fails
     * <em>after</em> applying some verdicts. Re-verifying those already-terminal
     * tasks threw {@code IllegalStateException} from outside the enclosing
     * {@code try}, so it escaped {@code executeTaskVerificationPhase} entirely and
     * the verifier's transcript entry and {@code onSpeakerComplete} event were
     * lost. Live status must be re-read.
     */
    @Test
    void parseAndApplyVerification_taskAlreadyVerified_fallbackSkipsItInsteadOfThrowing() {
        var gc = completedTaskConversation("Write the report");
        var snapshot = List.of(gc.getTaskList().getTasks().get(0)); // status()==COMPLETED forever
        // Simulate "the JSON pass verified this one, then blew up".
        gc.getTaskList().verifyTask(snapshot.get(0).id(), true, "verified by the JSON pass");

        var engine = engine();

        assertDoesNotThrow(() -> engine.parseAndApplyVerification(gc, snapshot, "not json at all", null));
        assertEquals("verified by the JSON pass", gc.getTaskList().getTasks().get(0).verificationNote(),
                "the fallback must not overwrite a verdict the JSON pass already applied");
    }

    @Test
    void parseAndApplyVerification_stillCompleted_fallbackAutoVerifies() {
        var gc = completedTaskConversation("Write the report");
        var snapshot = List.of(gc.getTaskList().getTasks().get(0));

        engine().parseAndApplyVerification(gc, snapshot, "not json at all", null);

        var live = gc.getTaskList().getTasks().get(0);
        assertTrue(live.verified());
        assertTrue(live.verificationNote().contains("Auto-verified"));
    }

    /** A conversation holding exactly one task, driven to COMPLETED. */
    private GroupConversation completedTaskConversation(String subject) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setTaskList(new SharedTaskList());
        var task = new TaskItem(subject, "desc", 1);
        gc.getTaskList().addTask(task);
        gc.getTaskList().assignTask(task.id(), AGENT_A, "A");
        gc.getTaskList().startTask(task.id());
        gc.getTaskList().completeTask(task.id(), "done");
        return gc;
    }

    @Test
    void formatVerificationForDisplay_nonJson_returnsRawContentUnchanged() {
        String raw = "just plain text, no brackets";
        assertEquals(raw, engine().formatVerificationForDisplay(raw));
    }

    @Test
    @SuppressWarnings("unchecked")
    void formatVerificationForDisplay_wellFormedJson_rendersPassFailSummary() throws Exception {
        var engine = engine();
        when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                .thenReturn(List.of(Map.of("subject", "Task X", "passed", false, "feedback", "needs work")));

        String result = engine.formatVerificationForDisplay("[{\"subject\":\"Task X\"}]");

        assertTrue(result.contains("Task X"));
        assertTrue(result.contains("Failed"));
        assertTrue(result.contains("needs work"));
    }

    // =================================================================
    // buildTaskExecutionInput
    // =================================================================

    @Test
    void buildTaskExecutionInput_templateFailure_fallsBackToPlainText() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        var task = new TaskItem("Write the report", "cover Q1 results", 1);
        var phase = new DiscussionPhase("EXEC", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1, false);
        var engine = engine();
        when(templatingEngine.processTemplate(any(), any(), any()))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("boom", null));

        String input = engine.buildTaskExecutionInput(task, "Q?", phase, gc);

        assertTrue(input.contains("Write the report"));
        assertTrue(input.contains("cover Q1 results"));
    }

    // =================================================================
    // I18 — announce-bid-award (CNP-lite)
    // =================================================================

    private AgentGroupConfiguration bidConfig(GroupMember... members) {
        var config = new AgentGroupConfiguration();
        config.setName("G");
        config.setMembers(List.of(members));
        config.setModeratorAgentId(MODERATOR);
        config.setTaskListConfig(new GroupTaskConfig(
                false, 20, 3, AssignmentMode.BID));
        return config;
    }

    private GroupConversation gcWithUnassignedTasks(String... subjects) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setTaskList(new SharedTaskList());
        for (String subject : subjects) {
            gc.getTaskList().addTask(new TaskItem(subject, "desc of " + subject, 0));
        }
        return gc;
    }

    private DiscussionPhase executePhase() {
        return new DiscussionPhase("Execution", PhaseType.EXECUTE, "ALL", TurnOrder.PARALLEL,
                ContextScope.TASK_ONLY, false, null, 1, false);
    }

    private ProtocolConfig protocol() {
        return new ProtocolConfig(
                5, MemberFailurePolicy.SKIP, 0,
                MemberUnavailablePolicy.SKIP);
    }

    private GroupConversation.TranscriptEntry bidReply(String agentId, String json) {
        return new TranscriptEntry(agentId, agentId, json, 0, "Execution",
                TranscriptEntryType.TASK_RESULT, Instant.now(), null, null);
    }

    @Test
    void bidRound_awardsToHighestConfidence_andRecordsTheBid() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    GroupMember m = inv.getArgument(0);
                    return AGENT_A.equals(m.agentId())
                            ? bidReply(AGENT_A, "{\"bids\": [{\"subject\": \"Task One\", \"confidence\": 0.9, "
                                    + "\"estimatedComplexity\": \"S\", \"rationale\": \"my specialty\"}]}")
                            : bidReply(AGENT_B, "{\"bids\": [{\"subject\": \"Task One\", \"confidence\": 0.4}, "
                                    + "{\"subject\": \"Task Two\", \"confidence\": 0.7}]}");
                });
        var gc = gcWithUnassignedTasks("Task One", "Task Two");
        var turnCounter = new AtomicInteger(0);

        engine.runBidRoundIfNeeded(gc, bidConfig(member(AGENT_A), member(AGENT_B)), executePhase(), protocol(),
                "Ship it", 0, null, turnCounter, 50);

        var tasks = gc.getTaskList().all();
        var taskOne = tasks.stream().filter(t -> t.subject().equals("Task One")).findFirst().orElseThrow();
        var taskTwo = tasks.stream().filter(t -> t.subject().equals("Task Two")).findFirst().orElseThrow();
        assertEquals(AGENT_A, taskOne.assignedAgentId(), "0.9 beats 0.4");
        assertEquals(AGENT_B, taskTwo.assignedAgentId(), "the only bidder wins");
        var award = gc.getTaskList().getAwardedBids().get(taskOne.id());
        assertEquals(0.9, award.confidence());
        assertEquals("my specialty", award.rationale());
        assertEquals(2, turnCounter.get(), "one bid turn per bidder is counted");
        // Both bid replies are on the transcript as BID entries — peer-hidden
        // while the phase runs (F4), auditable afterwards.
        assertEquals(2, gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.BID).count());
    }

    /**
     * A bid is a member-authored contribution, so its BID entry is signed. As a
     * BID: the signature covers the entry type, so the reply's own signature (made
     * for the turn's type) would not verify, and a peer requiring verification
     * would withhold every bid once the phase completed.
     */
    @Test
    void bidRound_theBidEntryIsSignedAsABid() throws Exception {
        var engine = engine();
        String json = "{\"bids\": [{\"subject\": \"Task One\", \"confidence\": 0.9}]}";
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new TranscriptEntry(AGENT_A, AGENT_A, json, 0, "Execution", TranscriptEntryType.TASK_RESULT, Instant.now(), null,
                        null, "reply-sig", "reply-nonce", 1234L, 2, 1));
        var signedBid = new TranscriptEntry(AGENT_A, AGENT_A, json, 0, "Execution", TranscriptEntryType.BID, Instant.now(), null, null,
                "bid-sig", "bid-nonce", 5678L, 2, 1);
        when(memberTurnExecutor.sign(any(), argThat(e -> e != null && e.type() == TranscriptEntryType.BID))).thenReturn(signedBid);
        var gc = gcWithUnassignedTasks("Task One", "Task Two");

        engine.runBidRoundIfNeeded(gc, bidConfig(member(AGENT_A), member(AGENT_B)), executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);

        var bid = gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.BID).findFirst().orElseThrow();
        assertSame(signedBid, bid);
    }

    @Test
    void bidRound_blindness_thePromptContainsTasksAndNeverPeerBids() throws Exception {
        var engine = engine();
        var inputs = Collections.synchronizedList(new ArrayList<String>());
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    inputs.add(inv.getArgument(2));
                    GroupMember m = inv.getArgument(0);
                    return bidReply(m.agentId(), "{\"bids\": [{\"subject\": \"Task One\", \"confidence\": 0.5, "
                            + "\"rationale\": \"SECRET-RATIONALE-" + m.agentId() + "\"}]}");
                });
        var gc = gcWithUnassignedTasks("Task One", "Task Two");

        engine.runBidRoundIfNeeded(gc, bidConfig(member(AGENT_A), member(AGENT_B)), executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);

        assertEquals(2, inputs.size());
        for (String input : inputs) {
            assertTrue(input.contains("Task One") && input.contains("Task Two"), input);
            assertFalse(input.contains("SECRET-RATIONALE"),
                    "a bid prompt must never contain a peer's bid — blindness is what makes confidences comparable");
            assertFalse(input.contains("confidence\": 0.5"), input);
        }
    }

    @Test
    void bidRound_noBids_fallsBackToRoleAssignment_neverStallsTheWave() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> bidReply(((GroupMember) inv.getArgument(0)).agentId(),
                        "I would rather not commit to anything today."));
        var gc = gcWithUnassignedTasks("Task One", "Task Two");

        engine.runBidRoundIfNeeded(gc, bidConfig(member(AGENT_A), member(AGENT_B)), executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);

        assertTrue(gc.getTaskList().all().stream().allMatch(t -> t.assignedAgentId() != null),
                "every task must still get an owner — an auction never stalls a wave");
        assertTrue(gc.getTaskList().getAwardedBids().isEmpty(), "no awards were fabricated from silence");
    }

    @Test
    void bidRound_skipConditions_singleBidderOrSingleTask_skipsTheAuctionEntirely() throws Exception {
        var engine = engine();
        // One eligible bidder — the winner is predetermined; no LLM calls.
        var gcOneBidder = gcWithUnassignedTasks("Task One", "Task Two");
        engine.runBidRoundIfNeeded(gcOneBidder, bidConfig(member(AGENT_A)), executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);
        verifyNoInteractions(memberTurnExecutor);
        assertTrue(gcOneBidder.getTaskList().all().stream().allMatch(t -> t.assignedAgentId() != null),
                "skipped auctions still assign via ROLE fallback");

        // One task — round-robin costs zero LLM calls.
        var gcOneTask = gcWithUnassignedTasks("Task One");
        engine.runBidRoundIfNeeded(gcOneTask, bidConfig(member(AGENT_A), member(AGENT_B)), executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void bidRound_roleModeTasks_neverAuctioned() throws Exception {
        var engine = engine();
        var config = bidConfig(member(AGENT_A), member(AGENT_B));
        config.setTaskListConfig(new GroupTaskConfig(
                false, 20, 3)); // ROLE default
        var gc = gcWithUnassignedTasks("Task One", "Task Two");

        engine.runBidRoundIfNeeded(gc, config, executePhase(), protocol(),
                "Ship it", 0, null, new AtomicInteger(0), 50);

        verifyNoInteractions(memberTurnExecutor);
        assertTrue(gc.getTaskList().all().stream().allMatch(t -> t.assignedAgentId() == null),
                "ROLE-mode tasks are none of the auction's business — the PLAN path owns them");
    }

    // =================================================================
    // A member turn that produced nothing fails its task
    // =================================================================

    @Test
    void skippedMemberTurn_failsTheTask_andItsDependentNeverRuns() throws Exception {
        var engine = engine();
        var gc = new GroupConversation();
        gc.setId("gc-tf");
        var taskList = new SharedTaskList();
        var research = taskList.addTask(new TaskItem("t-1", "Research", "gather", SharedTaskList.TaskStatus.ASSIGNED, AGENT_A, AGENT_A,
                List.of(), null, null, false, 1, Instant.now(), null));
        var write = taskList.addTask(new TaskItem("t-2", "Write", "write", SharedTaskList.TaskStatus.ASSIGNED, AGENT_A, AGENT_A,
                List.of("t-1"), null, null, false, 2, Instant.now(), null));
        gc.setTaskList(taskList);
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(member(AGENT_A)));
        var phase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL", TurnOrder.PARALLEL, ContextScope.TASK_ONLY, false, null, 1, false);
        var protocol = new ProtocolConfig(30, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP);
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new TranscriptEntry(AGENT_A, AGENT_A, null, 0, "Execute", TranscriptEntryType.SKIPPED,
                        Instant.now(), "timed out", null));

        engine.executeTaskExecutionPhase(gc, config, phase, protocol, "Q", 0, null,
                new AtomicInteger(), 50);

        assertEquals(SharedTaskList.TaskStatus.FAILED, taskList.findById(research.id()).status(), "a skipped turn is not a completed task");
        // Failed by the engine's no-result path, not by SharedTaskList refusing a null
        // completion (which would also end in FAILED, via the state-error handler).
        assertEquals("No result: timed out", taskList.findById(research.id()).verificationNote());
        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.content() != null && e.content().startsWith("[ERROR]")),
                "a skipped turn under SKIP is not reported as a task error");
        assertEquals(SharedTaskList.TaskStatus.ASSIGNED, taskList.findById(write.id()).status(), "its dependent must not run without the output");
        verify(memberTurnExecutor, times(1)).executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void abortPolicy_noResultTurn_stopsTheNextWaveFromRunningTheRemainingTasks() throws Exception {
        var engine = engine();
        var gc = new GroupConversation();
        gc.setId("gc-tf-abort");
        var taskList = new SharedTaskList();
        var first = taskList.addTask(new TaskItem("t-1", "First", "gather", SharedTaskList.TaskStatus.ASSIGNED, AGENT_A, AGENT_A,
                List.of(), null, null, false, 1, Instant.now(), null));
        var second = taskList.addTask(new TaskItem("t-2", "Second", "write", SharedTaskList.TaskStatus.ASSIGNED, AGENT_A, AGENT_A,
                List.of(), null, null, false, 2, Instant.now(), null));
        gc.setTaskList(taskList);
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(member(AGENT_A)));
        var phase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL", TurnOrder.PARALLEL, ContextScope.TASK_ONLY, false, null, 1, false);
        var protocol = new ProtocolConfig(30, MemberFailurePolicy.ABORT, 0, MemberUnavailablePolicy.SKIP);
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new TranscriptEntry(AGENT_A, AGENT_A, null, 0, "Execute", TranscriptEntryType.SKIPPED,
                        Instant.now(), "timed out", null));

        assertThrows(GroupDiscussionException.class, () -> engine.executeTaskExecutionPhase(gc, config, phase,
                protocol, "Q", 0, null, new AtomicInteger(), 50));

        assertEquals(SharedTaskList.TaskStatus.FAILED, taskList.findById(first.id()).status());
        assertEquals(SharedTaskList.TaskStatus.ASSIGNED, taskList.findById(second.id()).status(),
                "ABORT means the remaining tasks are not started in a further wave");
        verify(memberTurnExecutor, times(1)).executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void blankContent_isNoTaskResult() {
        var blank = new TranscriptEntry(AGENT_A, AGENT_A, "   ", 0, "Execute", TranscriptEntryType.OPINION, Instant.now(), null, null);
        var real = new TranscriptEntry(AGENT_A, AGENT_A, "done", 0, "Execute", TranscriptEntryType.OPINION, Instant.now(), null, null);
        assertTrue(TaskForceEngine.hasNoTaskResult(blank));
        assertFalse(TaskForceEngine.hasNoTaskResult(real));
    }

    @Test
    void tryParseVerificationJson_unrelatedObjectArray_isNotARivalToTheVerdictArray() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setTaskList(new SharedTaskList());
        var task = new TaskItem("X", "desc", 1);
        gc.getTaskList().addTask(task);
        gc.getTaskList().assignTask(task.id(), AGENT_A, "A");
        gc.getTaskList().startTask(task.id());
        gc.getTaskList().completeTask(task.id(), "done");
        var completedTask = gc.getTaskList().findById(task.id());
        var engine = engine();
        var mapper = new ObjectMapper();
        when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), List.class));

        boolean result = engine.tryParseVerificationJson(gc, List.of(completedTask),
                "Sources: [{\"title\":\"reference\"}] Verdicts: [{\"subject\":\"X\",\"passed\":false,\"feedback\":\"wrong\"}]", null);

        assertTrue(result, "a Sources list is not a verdict array and must not make the reply ambiguous");
        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
    }

    @Test
    void sharedTaskList_refusesToCompleteATaskWithoutAResult() {
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("t-1", "Research", "gather", SharedTaskList.TaskStatus.IN_PROGRESS, AGENT_A, AGENT_A,
                List.of(), null, null, false, 1, Instant.now(), null));

        assertThrows(IllegalStateException.class, () -> taskList.completeTask("t-1", null));
        assertThrows(IllegalStateException.class, () -> taskList.submitForApproval("t-1", null));
    }

    @Test
    void tryParseVerificationJson_bracketedAsideInProse_stillReadsTheVerdict() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setTaskList(new SharedTaskList());
        var task = new TaskItem("X", "desc", 1);
        gc.getTaskList().addTask(task);
        gc.getTaskList().assignTask(task.id(), AGENT_A, "A");
        gc.getTaskList().startTask(task.id());
        gc.getTaskList().completeTask(task.id(), "done");
        var completedTask = gc.getTaskList().findById(task.id());
        var engine = engine();
        var mapper = new ObjectMapper();
        when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), List.class));

        boolean result = engine.tryParseVerificationJson(gc, List.of(completedTask),
                "Checked [details]: [{\"subject\":\"X\",\"passed\":false,\"feedback\":\"wrong\"}]", null);

        assertTrue(result, "the bracketed aside must not hide the verdict behind it");
        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
    }
}
