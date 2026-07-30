/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
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
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
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
 * iterated on the member's own thread. Both hand-off sites are covered: the
 * parallel phase and the single-member follow-up.</li>
 * <li><b>C6</b> — the {@code maxTurns} budget was enforced with a
 * check-then-act on an {@code AtomicInteger} shared by N parallel agent
 * threads.</li>
 * <li><b>C8</b> — the parallel-phase timeout was applied serially, so N hanging
 * members cost N × timeout instead of one timeout; the single batch deadline
 * that replaced it must still cover one member's full attempt envelope, or the
 * orchestrator cancels members that are inside their own budget and their
 * configured retry never lands.</li>
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
                new SimpleMeterRegistry(), null, null, null, null, null, new CallerIdentityContext(null, null), "default", 3);

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

        doAnswer(inv -> {
            handlerOf(inv.getArgument(8)).onComplete(snapshot("contribution"));
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        // Park all 8 agent threads on a barrier at the last point the production code
        // reaches BEFORE the contended budget check: the onSpeakerComplete callback of
        // their first task. All 8 are released in the same instant and nothing but the
        // loop back-edge stands between that release and the check for turn 9, so the
        // adversarial interleaving is forced rather than hoped for. (The single-shot
        // latch this test used before was reached during turn 1 and stood open by the
        // time the threads got to the turn-2 check: they drifted apart across
        // completeTask, and a split check-then-act budget would pass unnoticed.)
        var atContendedCheck = new CyclicBarrier(memberCount);
        var completions = new AtomicInteger();
        var barrierFailure = new AtomicReference<Throwable>();
        var listener = new GroupDiscussionEventListener() {
            @Override
            public void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
                // First turn of each member only: the increments 1..8 all happen before
                // any thread is released, so a completion numbered > 8 belongs to the
                // single winner of the race — which must not wait for seven threads
                // that have already broken out of the loop.
                if (completions.incrementAndGet() <= memberCount) {
                    try {
                        atContendedCheck.await(30, TimeUnit.SECONDS);
                    } catch (Throwable t) {
                        barrierFailure.compareAndSet(null, t);
                    }
                }
            }
        };

        var turnCounter = new AtomicInteger(0);
        invoke(executionPhaseMethod(), gc, config(members), members,
                phase(PhaseType.EXECUTE, TurnOrder.PARALLEL), protocol(30), QUESTION, 0, listener,
                turnCounter, maxTurns);

        assertNull(barrierFailure.get(), () -> "the 8 threads never met at the contended check: " + barrierFailure.get());
        long completed = gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED).count();
        assertEquals((long) maxTurns, completed, "only the turns the budget allows may run");
        assertEquals((long) (2 * memberCount - maxTurns), gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.ASSIGNED).count(),
                "the remaining tasks stay ASSIGNED — untouched, not half-executed");
        assertEquals(0L, gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.IN_PROGRESS).count(),
                "a thread that loses the budget race must not have started a task — the check and "
                        + "startTask are fused under the task-list monitor");
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
                () -> "the batch must still honour its deadline (2s member budget + 1s grace), took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 6000,
                () -> "5 hanging members must not each restart the budget (10s serial), took " + elapsedMs + "ms");
        assertEquals(5L, gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.SKIPPED).count(),
                "every hanging member is recorded as SKIPPED exactly once");
    }

    @Test
    @Timeout(120)
    @DisplayName("C8: the batch deadline covers the retries onAgentFailure=RETRY promises")
    void parallelPhase_batchDeadline_coversTheMemberRetryEnvelope() throws Exception {
        var gc = groupConversation("gc-batch-retry");

        // 1s per attempt, RETRY with maxRetries=2 → the member is allowed three
        // attempts, so the batch budget must be 3 × 1s (+ grace), not 1 × 1s. The
        // member burns two full attempt timeouts before answering on the third, i.e.
        // it finishes ~1s AFTER a one-attempt batch deadline however the orchestrator
        // and the member thread interleave — sized at one attempt, the orchestrator
        // cancels the batch and the retried answer is replaced by a SKIPPED "unknown".
        var protocol = new ProtocolConfig(1, ProtocolConfig.MemberFailurePolicy.RETRY, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);

        var attempts = new AtomicInteger();
        doAnswer(inv -> {
            // Attempts 1 and 2 never answer: the member runs into its OWN timeout and
            // takes executeAgentTurn's RETRY branch. Attempt 3 answers immediately.
            if (attempts.incrementAndGet() >= 3) {
                handlerOf(inv.getArgument(8)).onComplete(snapshot("retried answer"));
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        invoke(parallelPhaseMethod(), gc, config(List.of(member(0))), List.of(member(0)),
                phase(PhaseType.OPINION, TurnOrder.PARALLEL), protocol, QUESTION, 0, null,
                new AtomicInteger(0), 50);

        assertEquals(3, attempts.get(), "the member must get all three attempts its failure policy grants");
        assertEquals(1, gc.getTranscript().size(), "one speaker, one transcript entry");
        TranscriptEntry entry = gc.getTranscript().get(0);
        assertEquals("retried answer", entry.content(),
                "the retried contribution must survive — a batch deadline sized for a single attempt "
                        + "cancels the member before its configured retry can complete");
        assertEquals("agent-0", entry.speakerAgentId(),
                "the entry belongs to the member that answered, not to the orchestrator's \"unknown\" timeout entry");
        assertEquals(TranscriptEntryType.OPINION, entry.type(), "a completed retry is a contribution, not a SKIP");
    }

    @Test
    @DisplayName("C8: the batch budget is one member's attempt envelope, never the batch size")
    void parallelBatchBudget_isDerivedFromOneMembersAttemptEnvelope() {
        // The grace is max(1s, 10% of the per-attempt budget) — a flat second is a
        // large share of a 2s timeout and a rounding error against a 180s one, and
        // setup cost does not shrink just because the timeout is short. Whenever the
        // grace is too small the orchestrator wins the race again and the member's
        // own RETRY/ABORT/attributed-SKIP handling is unreachable, which is the whole
        // defect this budget exists to avoid.
        //
        // SKIP / ABORT never retry: one attempt plus the grace (10 -> max(1, 1) = 1).
        assertEquals(11L, GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(10, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
        assertEquals(11L, GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(10, ProtocolConfig.MemberFailurePolicy.ABORT, 2,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
        // RETRY: maxRetries + 1 attempts, so the batch cannot cut a retry short.
        assertEquals(31L, GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(10, ProtocolConfig.MemberFailurePolicy.RETRY, 2,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
        // A short timeout keeps the 1s FLOOR rather than 10% of 2s.
        assertEquals(3L, GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(2, ProtocolConfig.MemberFailurePolicy.SKIP, 0,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
        // Unset values fall back to the same defaults executeAgentTurn applies:
        // 180s per attempt, 2 retries -> 540 + ceil(18) = 558.
        assertEquals(558L, GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(0, ProtocolConfig.MemberFailurePolicy.RETRY, 0,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
        // The grace itself: floor below 10s, proportional above it.
        assertEquals(1L, GroupConversationService.parallelBatchGraceSeconds(2));
        assertEquals(1L, GroupConversationService.parallelBatchGraceSeconds(10));
        assertEquals(18L, GroupConversationService.parallelBatchGraceSeconds(180));
        // An absurd config is capped instead of overflowing the deadline into the past.
        assertEquals(TimeUnit.HOURS.toSeconds(24), GroupConversationService.parallelBatchBudgetSeconds(
                new ProtocolConfig(Integer.MAX_VALUE, ProtocolConfig.MemberFailurePolicy.RETRY, Integer.MAX_VALUE,
                        ProtocolConfig.MemberUnavailablePolicy.SKIP)));
    }

    // =================================================================
    // C2 — the follow-up hand-off must snapshot too
    // =================================================================

    @Test
    @Timeout(60)
    @DisplayName("C2: a follow-up to one member publishes a transcript snapshot, never the live list")
    @SuppressWarnings("unchecked")
    void followUpWithMember_publishesTranscriptSnapshot_notTheLiveList() throws Exception {
        var gc = groupConversation("gc-followup");
        gc.setState(GroupConversationState.COMPLETED);
        gc.setMemberConversationIds(Map.of("agent-0", "conv-followup"));
        gc.addMemberDisplayName("agent-0", "Agent 0");
        for (int i = 0; i < 5; i++) {
            gc.getTranscript().add(transcriptEntry("seed-" + i));
        }

        doReturn(gc).when(conversationStore).read("gc-followup");
        doReturn(true).when(conversationStore).compareAndSetState("gc-followup",
                GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS);

        var liveListPublished = new AtomicInteger(0);
        var handedOver = new AtomicReference<List<TranscriptEntry>>();
        doAnswer(inv -> {
            InputData inputData = inv.getArgument(6);
            Object published = inputData.getContext().get("groupTranscript").getValue();
            if (published == gc.getTranscript()) {
                liveListPublished.incrementAndGet();
            }
            handedOver.set((List<TranscriptEntry>) published);
            handlerOf(inv.getArgument(8)).onComplete(snapshot("follow-up answer"));
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        service.followUpWithMember("gc-followup", "agent-0", "And what about X?");

        assertEquals(0, liveListPublished.get(),
                "the live synchronized transcript must never be handed to the member conversation by reference — "
                        + "it is serialised on the member's thread while this one keeps appending");
        assertNotNull(handedOver.get(), "the member must have received a groupTranscript");
        assertEquals(6, handedOver.get().size(),
                "the member sees the transcript as of its turn: 5 seed entries + the follow-up question");
        assertEquals(7, gc.getTranscript().size(),
                "the live transcript grew by the agent's answer afterwards — proof the member did not hold it");
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
