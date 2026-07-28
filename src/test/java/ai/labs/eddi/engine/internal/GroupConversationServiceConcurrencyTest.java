/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Concurrency regression tests for {@link GroupConversationService}. Each test
 * forces the interleaving it needs with latches/barriers instead of sleeping.
 * <ul>
 * <li><b>C1/C7</b> — {@code CompletableFuture.cancel(true)} does not interrupt
 * a {@code runAsync} body, so a "cancelled" member turn used to keep writing to
 * the group document after the orchestrator had persisted it — and could flip a
 * task back to IN_PROGRESS behind the reset sweep, stranding it forever.</li>
 * <li><b>C2</b> — the live {@code Collections.synchronizedList} transcript was
 * published by reference into every member conversation's context and then
 * iterated on the member's own thread.</li>
 * <li><b>C6</b> — the {@code maxTurns} budget was enforced with a
 * check-then-act on an {@code AtomicInteger} shared by N parallel agent
 * threads.</li>
 * <li><b>C8</b> — the parallel-phase timeout was applied serially, so N hanging
 * members cost N × timeout instead of one timeout.</li>
 * </ul>
 */
@DisplayName("GroupConversationService — concurrency regressions")
class GroupConversationServiceConcurrencyTest {

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
    @Mock
    private IAgent agent;

    private GroupConversationService service;

    private static final String GROUP_ID = "group-concurrency";
    private static final String USER_ID = "user-concurrency";
    private static final String QUESTION = "What should we do?";

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), null, null, null, null, null, "default", 3);

        doReturn(agent).when(agentFactory).getLatestReadyAgent(any(), any());
        var convCounter = new AtomicInteger();
        doAnswer(inv -> new IConversationService.ConversationResult(
                "conv-" + convCounter.incrementAndGet(), URI.create("eddi://conv")))
                .when(conversationService).startConversation(any(), any(), any(), any());
    }

    // =================================================================
    // C1 + C7 — cooperative cancellation and the stranded-task sweep
    // =================================================================

    @Test
    @Timeout(90)
    @DisplayName("C1/C7: a cancelled wave stops member writes and reclaims the task it stranded")
    void cancelledWave_stopsMemberWrites_andReclaimsStrandedTask() throws Exception {
        var gc = groupConversation("gc-cancel-wave");
        var taskList = new SharedTaskList();
        var task = taskList.addTask(new TaskItem("Task A", "do A", 0));
        taskList.assignTask(task.id(), "agent-0", "Agent 0");
        gc.setTaskList(taskList);

        var sayEntered = new CountDownLatch(1);
        var memberThread = new AtomicReference<Thread>();
        // The response never arrives: the member turn parks on its own await point,
        // which is exactly where cooperative cancellation has to release it.
        doAnswer(inv -> {
            memberThread.set(Thread.currentThread());
            sayEntered.countDown();
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        controlTokens().put(gc.getId(), new DiscussionControlToken());
        var cancelFailure = new AtomicReference<Throwable>();
        var canceller = new Thread(() -> {
            try {
                if (sayEntered.await(30, TimeUnit.SECONDS)) {
                    service.cancelDiscussion(gc.getId(), ControlSignal.CANCEL_IMMEDIATE);
                }
            } catch (Throwable t) {
                cancelFailure.compareAndSet(null, t);
            }
        }, "group-canceller");
        canceller.start();

        invoke(executionPhaseMethod(), gc, config(List.of(member(0))), List.of(member(0)),
                phase(PhaseType.EXECUTE, TurnOrder.PARALLEL), protocol(2), QUESTION, 0, null,
                new AtomicInteger(0), 50);

        canceller.join(TimeUnit.SECONDS.toMillis(30));
        assertNull(cancelFailure.get(), () -> "cancel failed: " + cancelFailure.get());

        Thread worker = memberThread.get();
        assertNotNull(worker, "the member turn must have reached the agent call");
        // Wait for the cancelled turn to finish: everything it could still write to the
        // group document happens after this point, so the assertions below are not a
        // race.
        worker.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(worker.isAlive(), "the cancelled member turn must unwind instead of running to completion");

        assertEquals(TaskStatus.ASSIGNED, gc.getTaskList().all().getFirst().status(),
                "the task the cancelled turn started must be reclaimed as ASSIGNED — neither left "
                        + "IN_PROGRESS nor failed by a write that landed after the abort");
        assertTrue(gc.getTranscript().isEmpty(),
                "a cancelled member turn must not append to the transcript after the orchestrator gave up on it");
    }

    // =================================================================
    // C6 — turn budget under contention
    // =================================================================

    @Test
    @Timeout(60)
    @DisplayName("C6: reserveTurn hands out at most maxTurns when 8 threads race for the last turn")
    void reserveTurn_underFullContention_neverOvershootsTheBudget() throws Exception {
        Method reserveTurn = GroupConversationService.class.getDeclaredMethod(
                "reserveTurn", AtomicInteger.class, int.class);
        reserveTurn.setAccessible(true);

        int threads = 8;
        int maxTurns = 3;
        var turnCounter = new AtomicInteger(0);
        var granted = new AtomicInteger(0);
        var failure = new AtomicReference<Throwable>();
        var barrier = new CyclicBarrier(threads);
        var done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    if (Boolean.TRUE.equals(reserveTurn.invoke(null, turnCounter, maxTurns))) {
                        granted.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "reserve-turn-" + i).start();
        }

        assertTrue(done.await(45, TimeUnit.SECONDS), "reservation threads did not finish");
        assertNull(failure.get(), () -> "reservation thread failed: " + failure.get());
        assertEquals(maxTurns, granted.get(),
                "exactly maxTurns turns may be granted, no matter how many threads pass the check together");
        assertEquals(maxTurns, turnCounter.get(), "the shared budget must not be inflated past maxTurns");
    }

    @Test
    @Timeout(90)
    @DisplayName("C6: an execution wave with 8 members racing for the last turn never exceeds maxTurns")
    void executionWave_withEightMembers_neverExceedsMaxTurns() throws Exception {
        int memberCount = 8;
        int maxTurns = memberCount + 1; // the 8 first tasks fit; all 8 then race for turn 9

        var gc = groupConversation("gc-turn-budget");
        var taskList = new SharedTaskList();
        List<GroupMember> members = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            var m = member(i);
            members.add(m);
            for (int t = 0; t < 2; t++) {
                var task = taskList.addTask(new TaskItem("Task " + i + "." + t, "do " + i + "." + t, 0));
                taskList.assignTask(task.id(), m.agentId(), m.displayName());
            }
        }
        gc.setTaskList(taskList);

        // Rendezvous inside the first turn of every member, so all 8 agent threads
        // reach the budget check for their second task at the same moment. Once all
        // 8 have arrived the latch stays open, so later turns pass straight through.
        var rendezvous = new CountDownLatch(memberCount);
        doAnswer(inv -> {
            rendezvous.countDown();
            rendezvous.await(30, TimeUnit.SECONDS);
            handlerOf(inv.getArgument(8)).onComplete(snapshot("contribution"));
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        var turnCounter = new AtomicInteger(0);
        invoke(executionPhaseMethod(), gc, config(members), members,
                phase(PhaseType.EXECUTE, TurnOrder.PARALLEL), protocol(30), QUESTION, 0, null,
                turnCounter, maxTurns);

        long completed = gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED).count();
        assertEquals((long) maxTurns, completed, "only the turns the budget allows may run");
        assertEquals((long) (2 * memberCount - maxTurns), gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.ASSIGNED).count(),
                "the remaining tasks stay ASSIGNED — untouched, not half-executed");
        assertEquals(maxTurns, turnCounter.get(), "the turn counter must land exactly on the budget");
        verify(conversationService, times(maxTurns))
                .say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    // =================================================================
    // C2 — the transcript handed to a member must be a snapshot
    // =================================================================

    @Test
    @Timeout(90)
    @DisplayName("C2: a parallel phase with 8 members publishes a transcript snapshot, never the live list")
    void parallelPhase_publishesTranscriptSnapshot_notTheLiveList() throws Exception {
        var gc = groupConversation("gc-transcript-handoff");
        for (int i = 0; i < 20; i++) {
            gc.getTranscript().add(transcriptEntry("seed-" + i));
        }

        List<GroupMember> members = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            members.add(member(i));
        }

        var liveListPublished = new AtomicInteger(0);
        var concurrentModifications = new AtomicInteger(0);
        var entriesVisited = new AtomicInteger(0);
        var firstMemberStarted = new CountDownLatch(1);

        doAnswer(inv -> {
            InputData inputData = inv.getArgument(6);
            Object published = inputData.getContext().get("groupTranscript").getValue();
            if (published == gc.getTranscript()) {
                liveListPublished.incrementAndGet();
            }
            firstMemberStarted.countDown();

            // Serialising this context is what a member conversation really does with
            // it — on this thread, while the orchestrator appends to the transcript.
            @SuppressWarnings("unchecked")
            List<TranscriptEntry> handedOver = (List<TranscriptEntry>) published;
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            try {
                while (System.nanoTime() < until) {
                    for (TranscriptEntry e : handedOver) {
                        if (e != null) {
                            entriesVisited.incrementAndGet();
                        }
                    }
                }
            } catch (ConcurrentModificationException e) {
                concurrentModifications.incrementAndGet();
            }

            handlerOf(inv.getArgument(8)).onComplete(snapshot("contribution"));
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        var appender = new Thread(() -> {
            try {
                if (!firstMemberStarted.await(30, TimeUnit.SECONDS)) {
                    return;
                }
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(280);
                int i = 0;
                while (System.nanoTime() < until) {
                    gc.getTranscript().add(transcriptEntry("appended-" + i++));
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "transcript-appender");
        appender.start();

        invoke(parallelPhaseMethod(), gc, config(members), members,
                phase(PhaseType.OPINION, TurnOrder.PARALLEL), protocol(60), QUESTION, 0, null,
                new AtomicInteger(0), 50);
        appender.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(0, liveListPublished.get(),
                "the live synchronized transcript must never be handed to a member conversation by reference");
        assertTrue(entriesVisited.get() > 0, "the members must actually have iterated their transcript copy");
        assertEquals(0, concurrentModifications.get(),
                "iterating the transcript handed to a member must not race the orchestrator's appends");
    }

    // =================================================================
    // C8 — one deadline for the whole parallel batch
    // =================================================================

    @Test
    @Timeout(120)
    @DisplayName("C8: 5 hanging members cost one timeout, not five")
    void parallelPhase_appliesOneDeadlineAcrossAllMembers() throws Exception {
        var gc = groupConversation("gc-batch-deadline");
        List<GroupMember> members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(member(i));
        }

        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(members.size());
        // Every member hangs inside the agent call for longer than the batch deadline.
        doAnswer(inv -> {
            entered.countDown();
            release.await(60, TimeUnit.SECONDS);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        long startNanos = System.nanoTime();
        try {
            invoke(parallelPhaseMethod(), gc, config(members), members,
                    phase(PhaseType.OPINION, TurnOrder.PARALLEL), protocol(2), QUESTION, 0, null,
                    new AtomicInteger(0), 50);
        } finally {
            release.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(entered.await(30, TimeUnit.SECONDS), "all members should have been dispatched in parallel");
        assertTrue(elapsedMs >= 1500,
                () -> "the batch must still honour its 2s deadline, took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 6000,
                () -> "5 hanging members must not each restart the 2s budget (10s serial), took " + elapsedMs + "ms");
        assertEquals(5L, gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.SKIPPED).count(),
                "every hanging member is recorded as SKIPPED exactly once");
    }

    // =================================================================
    // Helpers
    // =================================================================

    private GroupConversation groupConversation(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(USER_ID);
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion(QUESTION);
        return gc;
    }

    private GroupMember member(int index) {
        return new GroupMember("agent-" + index, "Agent " + index, index, null);
    }

    private AgentGroupConfiguration config(List<GroupMember> members) {
        var config = new AgentGroupConfiguration();
        config.setName("Concurrency Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setMembers(members);
        return config;
    }

    private DiscussionPhase phase(PhaseType type, TurnOrder turnOrder) {
        return new DiscussionPhase("P-" + type, type, "ALL", turnOrder, ContextScope.FULL, false, null, 1, false);
    }

    private ProtocolConfig protocol(int agentTimeoutSeconds) {
        return new ProtocolConfig(agentTimeoutSeconds, ProtocolConfig.MemberFailurePolicy.SKIP, 0,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
    }

    private TranscriptEntry transcriptEntry(String content) {
        return new TranscriptEntry("agent-0", "Agent 0", content, 0, "P", TranscriptEntryType.OPINION,
                Instant.now(), null, null);
    }

    private SimpleConversationMemorySnapshot snapshot(String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
        return snapshot;
    }

    private IConversationService.ConversationResponseHandler handlerOf(Object argument) {
        return (IConversationService.ConversationResponseHandler) argument;
    }

    @SuppressWarnings("unchecked")
    private Map<String, DiscussionControlToken> controlTokens() throws Exception {
        var field = GroupConversationService.class.getDeclaredField("activeTokens");
        field.setAccessible(true);
        return (Map<String, DiscussionControlToken>) field.get(service);
    }

    private Method executionPhaseMethod() throws NoSuchMethodException {
        return phaseMethod("executeTaskExecutionPhase");
    }

    private Method parallelPhaseMethod() throws NoSuchMethodException {
        return phaseMethod("executeParallelPhase");
    }

    private Method phaseMethod(String name) throws NoSuchMethodException {
        Method m = GroupConversationService.class.getDeclaredMethod(name,
                GroupConversation.class, AgentGroupConfiguration.class, List.class, DiscussionPhase.class,
                ProtocolConfig.class, String.class, int.class, GroupDiscussionEventListener.class,
                AtomicInteger.class, int.class);
        m.setAccessible(true);
        return m;
    }

    private void invoke(Method m, Object... args) throws Exception {
        try {
            m.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
