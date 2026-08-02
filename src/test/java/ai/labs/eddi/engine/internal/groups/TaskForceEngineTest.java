/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

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

    private TaskForceEngine engine() {
        templatingEngine = mock(ITemplatingEngine.class);
        jsonSerialization = mock(IJsonSerialization.class);
        return new TaskForceEngine(mock(MemberTurnExecutor.class), templatingEngine, jsonSerialization,
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
}
