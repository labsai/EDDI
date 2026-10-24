/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E2 {@code maxReworks}: a task that fails VERIFY goes back to its member with
 * the verifier's feedback, inside the VERIFY phase, bounded per task, by
 * {@code maxTurns} and by the cost ceiling; an unparseable verdict still
 * auto-verifies, now counted.
 */
@DisplayName("TaskForceEngine — rework within a run (E2)")
class TaskForceEngineReworkTest {

    private static final String WORKER = "worker";
    private static final String MODERATOR = "moderator";
    private static final String SUBJECT = "Write the report";

    private MemberTurnExecutor memberTurnExecutor;
    private TaskForceEngine engine;
    private SimpleMeterRegistry meters;
    /** Verdicts the verifier gives, in order; the last one repeats. */
    private final Deque<String> verdicts = new ConcurrentLinkedDeque<>();
    /** Inputs the worker received for each re-execution. */
    private final List<String> workerInputs = new ArrayList<>();
    private int verifierCalls;

    @BeforeEach
    void setUp() throws Exception {
        meters = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meters);
        var templatingEngine = mock(ITemplatingEngine.class);
        when(templatingEngine.processTemplate(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any())).thenAnswer(inv -> {
            GroupMember member = inv.getArgument(0);
            verifierCalls++;
            String verdict = verdicts.size() > 1 ? verdicts.poll() : verdicts.peek();
            return entry(member.agentId(), verdict);
        });
        when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any())).thenAnswer(inv -> {
            GroupMember member = inv.getArgument(0);
            synchronized (workerInputs) {
                workerInputs.add(inv.getArgument(2));
            }
            return entry(member.agentId(), "attempt " + workerInputs.size());
        });
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        engine = new TaskForceEngine(memberTurnExecutor, templatingEngine, new JsonSerialization(mapper),
                Executors.newVirtualThreadPerTaskExecutor(), new CallerIdentityContext(null, null), new ConcurrentHashMap<>(), 180, 5);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(meters);
    }

    private static TranscriptEntry entry(String agentId, String content) {
        return new TranscriptEntry(agentId, agentId, content, 2, "Verify", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    private static String verdict(boolean passed, String feedback) {
        return "[{\"subject\":\"" + SUBJECT + "\",\"passed\":" + passed + ",\"feedback\":\"" + feedback + "\"}]";
    }

    private static AgentGroupConfiguration config(int maxReworks) {
        var config = new AgentGroupConfiguration();
        config.setStyle(DiscussionStyle.TASK_FORCE);
        config.setModeratorAgentId(MODERATOR);
        config.setMembers(List.of(new GroupMember(MODERATOR, MODERATOR, 0, "MODERATOR"), new GroupMember(WORKER, WORKER, 1, null)));
        config.setPhases(List.of(new DiscussionPhase("Plan", PhaseType.PLAN), new DiscussionPhase("Execute", PhaseType.EXECUTE),
                new DiscussionPhase("Verify", PhaseType.VERIFY)));
        config.setMaxReworks(maxReworks);
        return config;
    }

    private static ProtocolConfig protocol(int maxTurns, Double ceiling) {
        return new ProtocolConfig(180, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, maxTurns, ceiling, CostPolicy.SYNTHESIZE_NOW);
    }

    private static GroupConversation completed() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setTaskList(new SharedTaskList());
        var task = new TaskItem(SUBJECT, "desc", 1);
        gc.getTaskList().addTask(task);
        gc.getTaskList().assignTask(task.id(), WORKER, WORKER);
        gc.getTaskList().startTask(task.id());
        gc.getTaskList().completeTask(task.id(), "first attempt");
        return gc;
    }

    private void verify(GroupConversation gc, AgentGroupConfiguration config, ProtocolConfig protocol, AtomicInteger turns, int maxTurns)
            throws Exception {
        var verifier = List.of(new GroupMember(MODERATOR, MODERATOR, 0, "MODERATOR"));
        engine.executeTaskVerificationPhase(gc, config, verifier, config.getPhases().get(2), protocol, "the question", 2, null, turns, maxTurns);
    }

    private static TaskItem task(GroupConversation gc) {
        return gc.getTaskList().all().getFirst();
    }

    private double meter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("defaults unchanged")
    class Defaults {

        @Test
        @DisplayName("maxReworks 0: a failed verification is final, nobody is asked again")
        void zeroIsFinal() throws Exception {
            verdicts.add(verdict(false, "missing sources"));
            var gc = completed();

            verify(gc, config(0), protocol(50, null), new AtomicInteger(), 50);

            assertEquals(TaskStatus.FAILED, task(gc).status());
            assertEquals(1, verifierCalls);
            assertTrue(workerInputs.isEmpty());
            assertEquals(0, gc.getTaskList().reworkCount(task(gc).id()));
        }

        @Test
        @DisplayName("a passing verdict needs no rework")
        void passIsVerified() throws Exception {
            verdicts.add(verdict(true, "good"));
            var gc = completed();

            verify(gc, config(2), protocol(50, null), new AtomicInteger(), 50);

            assertEquals(TaskStatus.VERIFIED, task(gc).status());
            assertEquals(1, verifierCalls);
        }
    }

    @Nested
    @DisplayName("rework")
    class Rework {

        @Test
        @DisplayName("a failed task goes back with the verifier's feedback and is verified again")
        void reworkThenPass() throws Exception {
            verdicts.add(verdict(false, "missing sources"));
            verdicts.add(verdict(true, "now complete"));
            var gc = completed();

            verify(gc, config(1), protocol(50, null), new AtomicInteger(), 50);

            assertEquals(TaskStatus.VERIFIED, task(gc).status());
            assertEquals(1, workerInputs.size());
            assertTrue(workerInputs.getFirst().contains("missing sources"), "the feedback reaches the member: " + workerInputs.getFirst());
            assertEquals(2, verifierCalls);
            assertEquals(1, gc.getTaskList().reworkCount(task(gc).id()));
            assertEquals(1.0, meter(TaskForceEngine.METER_TASK_REWORKS));
            assertTrue(gc.getTranscript().stream().anyMatch(e -> e.content() != null && e.content().startsWith("Returned for rework (1/1)")));
        }

        @Test
        @DisplayName("the bound: a task that keeps failing is reworked maxReworks times, then stays FAILED")
        void boundHolds() throws Exception {
            verdicts.add(verdict(false, "still wrong"));
            var gc = completed();

            verify(gc, config(2), protocol(50, null), new AtomicInteger(), 50);

            assertEquals(TaskStatus.FAILED, task(gc).status());
            assertEquals(2, workerInputs.size());
            assertEquals(3, verifierCalls);
            assertEquals(2, gc.getTaskList().reworkCount(task(gc).id()));
        }

        @Test
        @DisplayName("maxTurns: no room for an attempt and a verdict — the task stays FAILED")
        void maxTurnsStopsRework() throws Exception {
            verdicts.add(verdict(false, "missing sources"));
            var gc = completed();
            var turns = new AtomicInteger(8);

            verify(gc, config(2), protocol(10, null), turns, 10);

            assertEquals(TaskStatus.FAILED, task(gc).status());
            assertTrue(workerInputs.isEmpty());
            assertEquals(0, gc.getTaskList().reworkCount(task(gc).id()));
            assertTrue(gc.getTranscript().stream().anyMatch(e -> e.content() != null && e.content().startsWith("Rework stopped")));
        }

        @Test
        @DisplayName("maxTurns: exactly enough room for one attempt and its verdict still reworks")
        void maxTurnsExactFit() throws Exception {
            verdicts.add(verdict(false, "missing sources"));
            verdicts.add(verdict(true, "ok"));
            var gc = completed();
            var turns = new AtomicInteger(7);

            verify(gc, config(2), protocol(10, null), turns, 10);

            assertEquals(TaskStatus.VERIFIED, task(gc).status());
            assertEquals(1, workerInputs.size());
        }

        @Test
        @DisplayName("the cost ceiling reached during VERIFY stops the rework")
        void costCeilingStopsRework() throws Exception {
            var gc = completed();
            // doAnswer: a when(...) re-stub would call the setUp answer with null arguments
            doAnswer(inv -> {
                verifierCalls++;
                gc.setTotalCost(5.0);
                return entry(MODERATOR, verdict(false, "missing sources"));
            }).when(memberTurnExecutor).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any());

            verify(gc, config(2), protocol(50, 1.0), new AtomicInteger(), 50);

            assertEquals(TaskStatus.FAILED, task(gc).status());
            assertTrue(workerInputs.isEmpty());
        }

        @Test
        @DisplayName("not applied when the EXECUTE phase gates each task for human approval")
        void taskApprovalGateDisablesRework() throws Exception {
            verdicts.add(verdict(false, "missing sources"));
            var gc = completed();
            var config = config(2);
            var hitl = new AgentGroupConfiguration.HitlConfig();
            hitl.setGranularity(HitlGranularity.TASK);
            config.setHitlConfig(hitl);
            config.setPhases(List.of(new DiscussionPhase("Plan", PhaseType.PLAN),
                    new DiscussionPhase("Execute", PhaseType.EXECUTE, "ALL", AgentGroupConfiguration.TurnOrder.PARALLEL,
                            AgentGroupConfiguration.ContextScope.TASK_ONLY, false, null, 1, true),
                    new DiscussionPhase("Verify", PhaseType.VERIFY)));

            verify(gc, config, protocol(50, null), new AtomicInteger(), 50);

            assertEquals(TaskStatus.FAILED, task(gc).status());
            assertTrue(workerInputs.isEmpty());
        }
    }

    @Test
    @DisplayName("an unparseable verdict keeps today's fail-open auto-verify, and is counted")
    void unparseableIsCountedAndFailsOpen() throws Exception {
        verdicts.add("I think it is fine overall.");
        var gc = completed();

        verify(gc, config(2), protocol(50, null), new AtomicInteger(), 50);

        assertEquals(TaskStatus.VERIFIED, task(gc).status());
        assertEquals(1.0, meter(TaskForceEngine.METER_VERIFY_UNPARSEABLE));
        assertTrue(workerInputs.isEmpty());
    }

    @Test
    @DisplayName("the EXECUTE phase a rework runs is the nearest one before VERIFY")
    void executePhaseLookup() {
        var gc = new GroupConversation();
        var config = config(1);
        assertEquals("Execute", TaskForceEngine.executePhaseBefore(gc, config, 2).name());
        assertEquals(PhaseType.EXECUTE, TaskForceEngine.executePhaseBefore(gc, config, 0).type(), "no earlier EXECUTE: a default one");
    }

    @Test
    @DisplayName("rework counts survive the store's JSON round trip; a legacy document has none")
    void reworkCountsRoundTrip() throws Exception {
        var gc = completed();
        gc.getTaskList().verifyTask(task(gc).id(), false, "missing sources");
        gc.getTaskList().reworkTask(task(gc).id(), "missing sources");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var restored = mapper.readValue(mapper.writeValueAsString(gc.getTaskList()), SharedTaskList.class);
        var legacy = mapper.readValue("{\"tasks\":[]}", SharedTaskList.class);

        assertEquals(1, restored.reworkCount(task(gc).id()));
        assertEquals(TaskStatus.ASSIGNED, restored.findById(task(gc).id()).status());
        assertEquals("missing sources", restored.findById(task(gc).id()).verificationNote());
        assertEquals(0, legacy.reworkCount("anything"));
    }

    @Test
    @DisplayName("save time: maxReworks is 0..5; a legacy group config reads 0")
    void saveTimeBounds() throws Exception {
        assertTrue(AgentGroupStore.memberAndLimitProblems(config(6)).stream().anyMatch(p -> p.startsWith("maxReworks")));
        assertTrue(AgentGroupStore.memberAndLimitProblems(config(-1)).stream().anyMatch(p -> p.startsWith("maxReworks")));
        assertTrue(AgentGroupStore.memberAndLimitProblems(config(5)).stream().noneMatch(p -> p.startsWith("maxReworks")));
        var legacy = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue("{\"name\":\"g\",\"style\":\"TASK_FORCE\"}", AgentGroupConfiguration.class);
        assertEquals(0, legacy.getMaxReworks());
    }

    @Test
    @DisplayName("only a FAILED task can be reworked")
    void reworkNeedsFailed() {
        var gc = completed();
        assertThrows(IllegalStateException.class, () -> gc.getTaskList().reworkTask(task(gc).id(), "x"));
    }
}
