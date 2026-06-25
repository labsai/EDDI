/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Regression tests for TASK_FORCE code review fixes. Each test targets a
 * specific bug fix to prevent regressions:
 * <ul>
 * <li>C1: {@code addTask→updateTask} duplicate task bug</li>
 * <li>H3: Round-robin with moderator exclusion</li>
 * <li>H4: Verification JSON parser</li>
 * <li>H6: {@code handleTaskFailure} error propagation</li>
 * <li>M1: {@code setMemberConversationIds} ConcurrentHashMap</li>
 * </ul>
 *
 * @author ginccc
 */
@DisplayName("GroupConversationService — TASK_FORCE Regression Tests")
class GroupConversationServiceTaskForceTest {

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;

    private GroupConversationService service;

    private static final List<GroupMember> MEMBERS = List.of(
            new GroupMember("agent-1", "Analyst", 0, "RESEARCHER"),
            new GroupMember("agent-2", "Writer", 1, "AUTHOR"),
            new GroupMember("mod-agent", "Moderator", 2, "MODERATOR"));

    @BeforeEach
    void setUp() {
        openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), null, null, null, "default", 3);
    }

    // =================================================================
    // H3: resolveTaskAssignment — moderator exclusion + round-robin
    // =================================================================

    @Nested
    @DisplayName("resolveTaskAssignment (H3 fix)")
    class ResolveTaskAssignmentTests {

        private Method resolveMethod;

        @BeforeEach
        void setUp() throws Exception {
            resolveMethod = GroupConversationService.class.getDeclaredMethod(
                    "resolveTaskAssignment", String.class, List.class, String.class, int.class);
            resolveMethod.setAccessible(true);
        }

        private String invoke(String assignToRole, List<GroupMember> members,
                              String moderatorAgentId, int taskIndex)
                throws Exception {
            return (String) resolveMethod.invoke(service, assignToRole, members, moderatorAgentId, taskIndex);
        }

        @Test
        @DisplayName("ALL role round-robins across non-moderator members")
        void allRole_excludesModerator_roundRobins() throws Exception {
            // With moderator "mod-agent", only agent-1 and agent-2 are eligible
            String first = invoke("ALL", MEMBERS, "mod-agent", 0);
            String second = invoke("ALL", MEMBERS, "mod-agent", 1);
            String third = invoke("ALL", MEMBERS, "mod-agent", 2);

            assertEquals("agent-1", first);
            assertEquals("agent-2", second);
            assertEquals("agent-1", third, "Should wrap around to agent-1");
        }

        @Test
        @DisplayName("null role behaves like ALL")
        void nullRole_behavesLikeAll() throws Exception {
            String result = invoke(null, MEMBERS, "mod-agent", 0);
            assertEquals("agent-1", result);
        }

        @Test
        @DisplayName("ALL role with null moderator includes everyone")
        void allRole_nullModerator_includesAll() throws Exception {
            // With null moderator, equals(null) returns false for all → all eligible
            String first = invoke("ALL", MEMBERS, null, 0);
            String second = invoke("ALL", MEMBERS, null, 1);
            String third = invoke("ALL", MEMBERS, null, 2);

            assertEquals("agent-1", first);
            assertEquals("agent-2", second);
            assertEquals("mod-agent", third);
        }

        @Test
        @DisplayName("ROLE:RESEARCHER resolves to matching member")
        void rolePrefix_matchesByRole() throws Exception {
            String result = invoke("ROLE:RESEARCHER", MEMBERS, "mod-agent", 0);
            assertEquals("agent-1", result);
        }

        @Test
        @DisplayName("ROLE:NONEXISTENT returns null")
        void rolePrefix_noMatch_returnsNull() throws Exception {
            String result = invoke("ROLE:NONEXISTENT", MEMBERS, "mod-agent", 0);
            assertNull(result);
        }

        @Test
        @DisplayName("Direct agentId reference resolves")
        void directAgentId_resolves() throws Exception {
            String result = invoke("agent-2", MEMBERS, "mod-agent", 0);
            assertEquals("agent-2", result);
        }

        @Test
        @DisplayName("Empty members returns null")
        void emptyMembers_returnsNull() throws Exception {
            String result = invoke("ALL", List.of(), "mod-agent", 0);
            assertNull(result);
        }
    }

    // =================================================================
    // H4: tryParseVerificationJson — dedicated JSON parser
    // =================================================================

    @Nested
    @DisplayName("tryParseVerificationJson (H4 fix)")
    class VerificationJsonParserTests {

        private Method parseMethod;

        @BeforeEach
        void setUp() throws Exception {
            parseMethod = GroupConversationService.class.getDeclaredMethod(
                    "tryParseVerificationJson", GroupConversation.class, List.class,
                    String.class, GroupDiscussionEventListener.class);
            parseMethod.setAccessible(true);
        }

        private boolean invoke(GroupConversation gc, List<TaskItem> completedTasks,
                               String content)
                throws Exception {
            return (boolean) parseMethod.invoke(service, gc, completedTasks, content, null);
        }

        private GroupConversation createGcWithTaskList() {
            var gc = new GroupConversation();
            gc.setTaskList(new SharedTaskList());
            gc.setTranscript(new ArrayList<>());
            return gc;
        }

        @Test
        @DisplayName("Valid JSON with passed=true verifies task")
        void validJson_passedTrue_verifiesTask() throws Exception {
            var gc = createGcWithTaskList();
            var task = gc.getTaskList().addTask(new TaskItem("Write report", "desc", 0));
            gc.getTaskList().assignTask(task.id(), "agent-1", "A1");
            gc.getTaskList().startTask(task.id());
            gc.getTaskList().completeTask(task.id(), "done");

            List<TaskItem> completed = List.of(gc.getTaskList().findById(task.id()));
            String json = "[{\"subject\": \"Write report\", \"passed\": true, \"feedback\": \"Looks good\"}]";
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of(Map.of("subject", "Write report", "passed", true, "feedback", "Looks good")));

            boolean result = invoke(gc, completed, json);

            assertTrue(result, "Should return true for successful verification");
            assertEquals(TaskStatus.VERIFIED, gc.getTaskList().findById(task.id()).status());
        }

        @Test
        @DisplayName("Valid JSON with passed=false marks verification with note")
        void validJson_passedFalse_verifiesWithFail() throws Exception {
            var gc = createGcWithTaskList();
            var task = gc.getTaskList().addTask(new TaskItem("Write report", "desc", 0));
            gc.getTaskList().assignTask(task.id(), "agent-1", "A1");
            gc.getTaskList().startTask(task.id());
            gc.getTaskList().completeTask(task.id(), "done");

            List<TaskItem> completed = List.of(gc.getTaskList().findById(task.id()));
            String json = "[{\"subject\": \"Write report\", \"passed\": false, \"feedback\": \"Needs work\"}]";
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of(Map.of("subject", "Write report", "passed", false, "feedback", "Needs work")));

            boolean result = invoke(gc, completed, json);

            assertTrue(result, "Should return true even for failed verification");
            assertEquals(TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());
            assertFalse(gc.getTaskList().findById(task.id()).verified());
        }

        @Test
        @DisplayName("Content without JSON brackets returns false")
        void noJsonBrackets_returnsFalse() throws Exception {
            var gc = createGcWithTaskList();
            boolean result = invoke(gc, List.of(), "Just some plain text without brackets");

            assertFalse(result);
        }

        @Test
        @DisplayName("Empty JSON array returns false")
        void emptyJsonArray_returnsFalse() throws Exception {
            var gc = createGcWithTaskList();
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of());

            boolean result = invoke(gc, List.of(), "[]");

            assertFalse(result);
        }

        @Test
        @DisplayName("Subject matching is case-insensitive")
        void subjectMatching_caseInsensitive() throws Exception {
            var gc = createGcWithTaskList();
            var task = gc.getTaskList().addTask(new TaskItem("Write Report", "desc", 0));
            gc.getTaskList().assignTask(task.id(), "agent-1", "A1");
            gc.getTaskList().startTask(task.id());
            gc.getTaskList().completeTask(task.id(), "done");

            List<TaskItem> completed = List.of(gc.getTaskList().findById(task.id()));
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of(Map.of("subject", "write report", "passed", true)));

            boolean result = invoke(gc, completed, "[{\"subject\":\"write report\",\"passed\":true}]");

            assertTrue(result, "Case-insensitive subject match should work");
            assertEquals(TaskStatus.VERIFIED, gc.getTaskList().findById(task.id()).status());
        }

        @Test
        @DisplayName("Deserialization exception returns false gracefully")
        void deserializationError_returnsFalse() throws Exception {
            var gc = createGcWithTaskList();
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenThrow(new RuntimeException("Bad JSON"));

            boolean result = invoke(gc, List.of(), "[broken json]");

            assertFalse(result, "Should return false on deserialization failure");
        }
    }

    // =================================================================
    // H6: handleTaskFailure — error propagation
    // =================================================================

    @Nested
    @DisplayName("handleTaskFailure (H6 fix)")
    class HandleTaskFailureTests {

        private Method handleMethod;

        @BeforeEach
        void setUp() throws Exception {
            handleMethod = GroupConversationService.class.getDeclaredMethod(
                    "handleTaskFailure",
                    GroupConversation.class, TaskItem.class, GroupMember.class,
                    String.class, int.class, DiscussionPhase.class,
                    GroupDiscussionEventListener.class,
                    List.class, GroupDiscussionException.class);
            handleMethod.setAccessible(true);
        }

        @Test
        @DisplayName("Marks task as FAILED, adds transcript entry, collects error")
        void basicFailure_marksAndRecords() throws Exception {
            var gc = new GroupConversation();
            gc.setTaskList(new SharedTaskList());
            gc.setTranscript(Collections.synchronizedList(new ArrayList<>()));

            var task = gc.getTaskList().addTask(new TaskItem("Task A", "desc", 0));
            gc.getTaskList().assignTask(task.id(), "agent-1", "Agent One");
            gc.getTaskList().startTask(task.id());

            // Refresh task reference after state change
            task = gc.getTaskList().findById(task.id());

            var member = new GroupMember("agent-1", "Agent One", 0, "MEMBER");
            var phase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL",
                    AgentGroupConfiguration.TurnOrder.PARALLEL, AgentGroupConfiguration.ContextScope.TASK_ONLY,
                    false, null, 1);
            var errors = new ArrayList<GroupDiscussionException>();
            var ex = new GroupDiscussionException("LLM timeout");

            handleMethod.invoke(service, gc, task, member, "LLM timeout", 1, phase, null, errors, ex);

            // Task should be FAILED
            assertEquals(TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status());

            // Transcript should have an error entry
            assertEquals(1, gc.getTranscript().size());
            assertTrue(gc.getTranscript().getFirst().content().contains("[ERROR]"));
            assertEquals(TranscriptEntryType.TASK_RESULT, gc.getTranscript().getFirst().type());

            // Error should be collected
            assertEquals(1, errors.size());
            assertSame(ex, errors.getFirst());
        }

        @Test
        @DisplayName("Already terminal task is handled gracefully (no crash)")
        void alreadyTerminalTask_doesNotCrash() throws Exception {
            var gc = new GroupConversation();
            gc.setTaskList(new SharedTaskList());
            gc.setTranscript(Collections.synchronizedList(new ArrayList<>()));

            var task = gc.getTaskList().addTask(new TaskItem("Task A", "desc", 0));
            gc.getTaskList().failTask(task.id(), "already failed");

            var failedTask = gc.getTaskList().findById(task.id());

            var member = new GroupMember("agent-1", "Agent One", 0, "MEMBER");
            var phase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL",
                    AgentGroupConfiguration.TurnOrder.PARALLEL, AgentGroupConfiguration.ContextScope.TASK_ONLY,
                    false, null, 1);
            var errors = new ArrayList<GroupDiscussionException>();
            var ex = new GroupDiscussionException("Second failure");

            // Should NOT throw even though task is already FAILED
            assertDoesNotThrow(() -> handleMethod.invoke(service, gc, failedTask, member, "Second failure", 1, phase, null, errors, ex));

            // Error still collected
            assertEquals(1, errors.size());
            // Transcript entry still added
            assertEquals(1, gc.getTranscript().size());
        }
    }

    // =================================================================
    // M1: setMemberConversationIds — ConcurrentHashMap defensive wrap
    // =================================================================

    @Nested
    @DisplayName("setMemberConversationIds (M1 fix)")
    class MemberConversationIdsTests {

        @Test
        @DisplayName("Setter wraps plain HashMap into ConcurrentHashMap")
        void setter_wrapsInConcurrentHashMap() {
            var gc = new GroupConversation();
            gc.setMemberConversationIds(new java.util.LinkedHashMap<>(
                    Map.of("agent-1", "conv-1")));

            assertTrue(gc.getMemberConversationIds() instanceof java.util.concurrent.ConcurrentHashMap,
                    "Must be ConcurrentHashMap after setter");
            assertEquals("conv-1", gc.getMemberConversationIds().get("agent-1"));
        }

        @Test
        @DisplayName("Setter with null creates empty ConcurrentHashMap")
        void setter_nullCreatesEmptyConcurrentHashMap() {
            var gc = new GroupConversation();
            gc.setMemberConversationIds(null);

            assertNotNull(gc.getMemberConversationIds());
            assertTrue(gc.getMemberConversationIds() instanceof java.util.concurrent.ConcurrentHashMap);
            assertTrue(gc.getMemberConversationIds().isEmpty());
        }
    }
}
