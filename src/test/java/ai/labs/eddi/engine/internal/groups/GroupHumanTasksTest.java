/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.HitlConfig;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.internal.TaskListParser;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HUMAN members as task-force executors: agents run first, then one human task
 * per pause; the answer completes, gates or fails the task; round-robin and
 * rework never pick a human.
 */
@DisplayName("Humans as task-force executors")
class GroupHumanTasksTest {

    private static final String LEAD = "lead";
    private static final String AGENT = "worker";
    private static final String HUMAN = "hannah@acme.com";

    private TaskForceEngine engine;
    private final List<String> agentRuns = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var templating = mock(ITemplatingEngine.class);
        when(templating.processTemplate(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var members = mock(MemberTurnExecutor.class);
        when(members.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any())).thenAnswer(inv -> {
            GroupMember member = inv.getArgument(0);
            synchronized (agentRuns) {
                agentRuns.add(member.agentId());
            }
            return new TranscriptEntry(member.agentId(), member.displayName(), "done by " + member.agentId(), 1, "Execute",
                    TranscriptEntryType.TASK_RESULT, Instant.now(), null, null);
        });
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        engine = new TaskForceEngine(members, templating, new JsonSerialization(mapper), Executors.newVirtualThreadPerTaskExecutor(),
                new CallerIdentityContext(null, null), new ConcurrentHashMap<>(), 180, 5);
    }

    private static AgentGroupConfiguration config() {
        var c = new AgentGroupConfiguration();
        c.setStyle(DiscussionStyle.TASK_FORCE);
        c.setModeratorAgentId(LEAD);
        c.setMembers(List.of(new GroupMember(LEAD, "Lead", 0, "MODERATOR"), new GroupMember(AGENT, "Worker", 1, null),
                new GroupMember(HUMAN, "Hannah", 2, "LEGAL", MemberType.HUMAN)));
        return c;
    }

    private static final DiscussionPhase EXECUTE = new DiscussionPhase("Execute", PhaseType.EXECUTE);

    private static ProtocolConfig protocol() {
        return new ProtocolConfig(180, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 20, null, CostPolicy.SYNTHESIZE_NOW);
    }

    /**
     * Draft (agent) → Sign-off (human) → Publish (agent, depends on the sign-off).
     */
    private static GroupConversation board() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        var list = new SharedTaskList();
        var draft = list.addTask(new TaskItem("Draft", "write it", 1));
        list.assignTask(draft.id(), AGENT, "Worker");
        var signOff = list.addTask(new TaskItem("Sign-off", "legal review", 0));
        list.assignTask(signOff.id(), HUMAN, "Hannah");
        var publish = list.addTask(new TaskItem("Publish", "ship it", 2));
        list.updateTask(new TaskItem(publish.id(), publish.subject(), publish.description(), TaskStatus.PENDING, null, null,
                List.of(signOff.id()), null, null, false, publish.priority(), publish.createdAt(), null));
        list.assignTask(publish.id(), AGENT, "Worker");
        gc.setTaskList(list);
        return gc;
    }

    private static TaskItem byName(GroupConversation gc, String subject) {
        return gc.getTaskList().all().stream().filter(t -> t.subject().equals(subject)).findFirst().orElseThrow();
    }

    private PhaseExecutionEngine.HumanTurnRequired runUntilPause(GroupConversation gc, AgentGroupConfiguration config) {
        return assertThrows(PhaseExecutionEngine.HumanTurnRequired.class, () -> engine.executeTaskExecutionPhase(gc, config,
                EXECUTE, protocol(), "Launch?", 1, null, new AtomicInteger(), 20));
    }

    private static PendingHumanInput pendingFor(PhaseExecutionEngine.HumanTurnRequired turn) {
        return new PendingHumanInput(turn.member().agentId(), turn.member().displayName(), 1, 0, -1, "TASK_RESULT", turn.renderedPrompt(),
                "SKIP_TURN", Instant.now(), turn.detail());
    }

    @Nested
    @DisplayName("the wave loop")
    class WaveLoop {

        @Test
        @DisplayName("runs the agents first, then pauses for the human's task — never putting a human in a wave")
        void agentsFirstThenHuman() {
            var gc = board();

            var turn = runUntilPause(gc, config());

            assertEquals(List.of(AGENT), agentRuns, "only the agent's executable task ran");
            assertEquals(HUMAN, turn.member().agentId());
            assertEquals(byName(gc, "Sign-off").id(), turn.detail().taskId());
            assertTrue(turn.detail().isTask());
            assertTrue(turn.renderedPrompt().contains("YOUR TASK"), "the same task input an agent gets: " + turn.renderedPrompt());
            assertEquals(TaskStatus.ASSIGNED, byName(gc, "Sign-off").status(), "the task waits ASSIGNED while paused");
            assertEquals(TaskStatus.ASSIGNED, byName(gc, "Publish").status(), "its dependent waits");
        }

        @Test
        @DisplayName("after the answer, the dependent task runs")
        void dependentsRunAfterAnswer() throws Exception {
            var gc = board();
            var config = config();
            var turn = runUntilPause(gc, config);

            GroupHumanTasks.applyResult(gc, config, EXECUTE, pendingFor(turn), "Approved with edits", null);
            engine.executeTaskExecutionPhase(gc, config, EXECUTE, protocol(), "Launch?", 1, null, new AtomicInteger(), 20);

            assertEquals(TaskStatus.COMPLETED, byName(gc, "Sign-off").status());
            assertEquals("Approved with edits", byName(gc, "Sign-off").result());
            assertEquals(TaskStatus.COMPLETED, byName(gc, "Publish").status());
            assertEquals(List.of(AGENT, AGENT), agentRuns);
        }

        @Test
        @DisplayName("a VERIFY rework re-run never pauses for a human")
        void reworkDoesNotPause() throws Exception {
            var gc = board();

            engine.executeTaskExecutionPhase(gc, config(), EXECUTE, protocol(), "Launch?", 1, null, new AtomicInteger(), 20,
                    false);

            assertEquals(TaskStatus.ASSIGNED, byName(gc, "Sign-off").status());
        }

        @Test
        @DisplayName("with the turn budget spent, there is no human pause either")
        void budgetSpent() throws Exception {
            var gc = board();
            gc.getTaskList().all().stream().filter(t -> t.subject().equals("Draft")).forEach(t -> {
                gc.getTaskList().startTask(t.id());
                gc.getTaskList().completeTask(t.id(), "x");
            });

            engine.executeTaskExecutionPhase(gc, config(), EXECUTE, protocol(), "Launch?", 1, null, new AtomicInteger(20), 20);

            assertEquals(TaskStatus.ASSIGNED, byName(gc, "Sign-off").status());
        }
    }

    @Nested
    @DisplayName("the answer")
    class Answer {

        @Test
        @DisplayName("a TASK-granularity gated phase submits the human's result for approval")
        void gated() {
            var gc = board();
            var config = config();
            var hitl = new HitlConfig();
            hitl.setGranularity(HitlGranularity.TASK);
            config.setHitlConfig(hitl);
            var gatedPhase = new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL", null, null, false, null, 1, true);
            var turn = runUntilPause(gc, config);

            GroupHumanTasks.applyResult(gc, config, gatedPhase, pendingFor(turn), "Signed", null);

            assertEquals(TaskStatus.AWAITING_APPROVAL, byName(gc, "Sign-off").status());
        }

        @Test
        @DisplayName("a timeout fails the task, and a rollback restores it")
        void timeoutAndRollback() {
            var gc = board();
            var config = config();
            var turn = runUntilPause(gc, config);

            TaskItem before = GroupHumanTasks.applyResult(gc, config, EXECUTE, pendingFor(turn), null, "No response from Hannah within PT4H");

            assertEquals(TaskStatus.FAILED, byName(gc, "Sign-off").status());
            assertEquals("No response from Hannah within PT4H", byName(gc, "Sign-off").verificationNote());
            GroupHumanTasks.restore(gc, before);
            assertEquals(TaskStatus.ASSIGNED, byName(gc, "Sign-off").status());
        }

        @Test
        @DisplayName("a speaking turn or a tie-break leaves the task list alone")
        void otherKinds() {
            var gc = board();
            var plain = new PendingHumanInput(HUMAN, "Hannah", 1, 0, 0, "OPINION", "p", "SKIP_TURN", Instant.now());
            var tie = new PendingHumanInput(HUMAN, "Hannah", 1, 0, -1, "HUMAN_INPUT", "p", "SKIP_TURN", Instant.now(),
                    HumanRequestDetail.tieBreak(List.of("A", "B"), 0, "NO_DECISION"));

            assertNull(GroupHumanTasks.applyResult(gc, config(), EXECUTE, plain, "x", null));
            assertNull(GroupHumanTasks.applyResult(gc, config(), EXECUTE, tie, "x", null));
        }
    }

    @Nested
    @DisplayName("assignment")
    class Assignment {

        @Test
        @DisplayName("round-robin never picks a human")
        void roundRobin() {
            var members = config().getMembers();
            for (int i = 0; i < 6; i++) {
                assertTrue(!HUMAN.equals(TaskListParser.roundRobinAssign(i, members)));
                assertTrue(!HUMAN.equals(TaskForceEngine.resolveAssignee("ALL", members, LEAD, i)));
            }
            assertNull(TaskListParser.roundRobinAssign(0, List.of(new GroupMember(HUMAN, "H", 1, null, MemberType.HUMAN))));
        }

        @Test
        @DisplayName("an explicit role or principal reaches a human")
        void explicit() {
            var members = config().getMembers();
            assertEquals(HUMAN, TaskForceEngine.resolveAssignee("ROLE:LEGAL", members, LEAD, 0));
            assertEquals(HUMAN, TaskForceEngine.resolveAssignee(HUMAN, members, LEAD, 0));
        }

        @Test
        @DisplayName("a rework round skips human tasks, noting each once")
        void reworkSkipsHumans() {
            var gc = board();
            var noted = new HashSet<String>();
            var tasks = gc.getTaskList().all();

            var agents = GroupHumanTasks.withoutHumanAssignees(gc, config(), EXECUTE, 2, tasks, noted);
            GroupHumanTasks.withoutHumanAssignees(gc, config(), EXECUTE, 2, tasks, noted);

            assertEquals(2, agents.size());
            assertTrue(agents.stream().noneMatch(t -> HUMAN.equals(t.assignedAgentId())));
            assertEquals(1, gc.getTranscript().stream().filter(e -> e.content() != null && e.content().startsWith("Not reworked")).count());
        }

        @Test
        @DisplayName("the next human task is the highest-priority executable one")
        void priority() {
            var gc = board();
            var second = gc.getTaskList().addTask(new TaskItem("Second look", "again", 5));
            gc.getTaskList().assignTask(second.id(), HUMAN, "Hannah");

            assertEquals(byName(gc, "Sign-off").id(), GroupHumanTasks.nextHumanTask(config(), gc.getTaskList()).orElseThrow().id());
        }
    }
}
