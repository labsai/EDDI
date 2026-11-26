/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancelledException;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancel reaches the work (K2) and nested groups inherit their parent's cancel
 * and turn budget (K8).
 */
class DiscussionCancellationTest {

    @Test
    void cancelHooks_hearEveryCancel_andAHookAddedLateRunsAtOnce() {
        var token = new DiscussionControlToken();
        List<ControlSignal> heard = new ArrayList<>();
        Runnable unlink = token.onCancel(heard::add);

        token.setSignal(ControlSignal.CONTINUE);
        token.setSignal(ControlSignal.CANCEL_GRACEFUL, "alice");
        unlink.run();
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);

        assertEquals(List.of(ControlSignal.CANCEL_GRACEFUL), heard);
        List<ControlSignal> late = new ArrayList<>();
        token.onCancel(late::add);
        assertEquals(List.of(ControlSignal.CANCEL_IMMEDIATE), late);
    }

    @Test
    void checkpoint_throwsOnlyOnceCancelled() {
        var token = new DiscussionControlToken();
        DiscussionCancellation.checkpoint(null);
        DiscussionCancellation.checkpoint(token);

        token.setSignal(ControlSignal.CANCEL_GRACEFUL);

        assertThrows(MemberTurnCancelledException.class, () -> DiscussionCancellation.checkpoint(token));
    }

    @Test
    void link_releasesTheTurnOnImmediateOnly() {
        var token = new DiscussionControlToken();
        var graceful = DiscussionCancellation.link(token);
        var awaited = new CompletableFuture<String>();
        graceful.cancellation().register(awaited);

        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        assertFalse(awaited.isDone(), "a graceful cancel lets the running turn finish");

        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
        assertTrue(awaited.isCompletedExceptionally(), "an immediate cancel releases it");
        assertTrue(graceful.cancellation().stopsInFlight(), "and stops the member's own turn");
        graceful.close();

        assertNull(DiscussionCancellation.link(null).cancellation());
    }

    // --- nested GROUP members (K8) ---

    private static DiscussionPhase opinion() {
        return new DiscussionPhase("P", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private static ProtocolConfig protocol() {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP);
    }

    private static MemberTurnExecutor executor(GroupConversationService service) {
        return new MemberTurnExecutor(Mockito.mock(IConversationService.class), Mockito.mock(IAgentFactory.class),
                new GroupSigningGuard(null, null, "default"), new GroupContextBuilder(null), service,
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);
    }

    private static GroupConversation parent() {
        var gc = new GroupConversation();
        gc.setId("gc-parent");
        gc.setGroupId("group-parent");
        return gc;
    }

    private static final GroupMember SUB_GROUP = new GroupMember("sub-group", "Sub", 1, null, MemberType.GROUP);

    @Test
    void nestedGroup_inheritsTheRemainingTurns_andChargesItsOwnBack() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        when(service.controlTokenOf("gc-parent")).thenReturn(token);
        var child = new GroupConversation();
        child.setId("gc-child");
        child.setSynthesizedAnswer("child answer");
        child.bindLegTurnBudget(new AtomicInteger(7), 10);
        when(service.discussNested(eq("sub-group"), any(), any(), anyInt(), any(), any(), any(), eq("gc-parent"), any(), any()))
                .thenReturn(child);
        var gc = parent();
        var parentCounter = new AtomicInteger(30);
        gc.bindLegTurnBudget(parentCounter, 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals("child answer", entry.content());
        verify(service).discussNested(eq("sub-group"), any(), any(), anyInt(), any(), any(), any(), eq("gc-parent"), Mockito.same(token),
                eq(20));
        assertEquals(37, parentCounter.get(), "the child's turns count against the parent");
    }

    @Test
    void nestedGroup_withNoTurnLeft_startsNoChild() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var gc = parent();
        gc.bindLegTurnBudget(new AtomicInteger(50), 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals(MemberTurnExecutor.REASON_TURN_BUDGET, entry.errorReason());
        verify(service, never()).discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nestedGroup_parentCancelled_isACancelNotAFailedTurn() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        when(service.controlTokenOf("gc-parent")).thenReturn(token);

        var thrown = assertThrows(MemberTurnCancelledException.class,
                () -> executor(service).executeAgentTurn(SUB_GROUP, parent(), "input", protocol(), 0, opinion(), null, null));

        assertSame(MemberTurnCancelledException.class, thrown.getClass());
        verify(service, never()).discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    /** S1: two nested members of one parallel batch share what remains. */
    @Test
    void parallelSiblings_shareTheRemainingTurns_andNeverPassTheCap() {
        var gc = parent();
        var counter = new AtomicInteger(2);
        gc.bindLegTurnBudget(counter, 10);
        gc.shareLegTurns(2);

        int first = gc.claimLegTurns();
        int second = gc.claimLegTurns();

        assertEquals(4, first, "half of the 8 that remain");
        assertEquals(4, second, "the last unclaimed sibling receives what remains, not half of it again");
        assertTrue(counter.get() <= 10, "the claims never pass the cap: the synthesis turn stays reserved");
        gc.settleLegTurns(first, 1);
        assertEquals(7, counter.get(), "an unused claim is given back");
    }

    /** S2: a child that throws is charged its whole claim. */
    @Test
    void nestedGroup_thatThrows_isStillChargedToTheParent() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        when(service.discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("child blew up"));
        var gc = parent();
        var counter = new AtomicInteger(40);
        gc.bindLegTurnBudget(counter, 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals(GroupConversation.TurnOutcome.FAILED, entry.outcome());
        assertEquals(50, counter.get(), "its claim of the remaining 10 turns stays spent");
    }

    /**
     * S8: a cancelled child, or one that did not answer, is no answer of its
     * member.
     */
    @Test
    void nestedGroup_cancelledOrUnanswered_isNotAnAnswer() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var child = new GroupConversation();
        child.setId("gc-child");
        child.setState(GroupConversation.GroupConversationState.CANCELLED);
        child.getTranscript().add(new GroupConversation.TranscriptEntry("x", "X", "partial", 0, "P", TranscriptEntryType.OPINION,
                Instant.now(), null, null));
        when(service.discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(child);

        var cancelled = executor(service).executeAgentTurn(SUB_GROUP, parent(), "input", protocol(), 0, opinion(), null, null);
        assertEquals(GroupConversation.TurnOutcome.SKIPPED, cancelled.outcome());
        assertEquals(MemberTurnExecutor.REASON_SUB_GROUP_CANCELLED, cancelled.errorReason());

        child.setState(GroupConversation.GroupConversationState.COMPLETED);
        child.setOutcome(GroupConversation.DiscussionOutcome.NO_ANSWER);
        var unanswered = executor(service).executeAgentTurn(SUB_GROUP, parent(), "input", protocol(), 0, opinion(), null, null);
        assertEquals(GroupConversation.TurnOutcome.FAILED, unanswered.outcome());
        assertNull(unanswered.content(), "its partial entries are not passed off as the member's answer");
    }

    /**
     * A subgroup deleted since the parent saved is refused before it ran: its claim
     * is refunded.
     */
    @Test
    void nestedGroup_thatIsMissing_refundsItsClaim() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        when(service.discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new GroupConversationService.NestedGroupNotFoundException("Group not found."));
        var gc = parent();
        var counter = new AtomicInteger(40);
        gc.bindLegTurnBudget(counter, 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals(GroupConversation.TurnOutcome.FAILED, entry.outcome());
        assertEquals(40, counter.get(), "nothing ran, so the next healthy member still has its turns");
    }

    /**
     * The parent was cancelled while the child ran: the child's spend is still
     * booked.
     */
    @Test
    void nestedGroup_parentCancelledAfterChildSpent_stillBooksTheSpend() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        when(service.controlTokenOf("gc-parent")).thenReturn(token);
        var child = new GroupConversation();
        child.setId("gc-child");
        child.setSynthesizedAnswer("child answer");
        child.setTotalCost(0.5);
        when(service.discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            return child;
        });
        var gc = parent();

        assertThrows(MemberTurnCancelledException.class,
                () -> executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null));

        assertEquals(0.5, gc.getTotalCost(), 1e-12, "the child's cost is on the parent's books");
        verify(service).chargeTeamBudget(gc);
    }

    /**
     * An unsupported child pause is released even when the parent was cancelled
     * meanwhile.
     */
    @Test
    void nestedGroup_strandedPause_isCancelledEvenWhenTheParentIsCancelled() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        when(service.controlTokenOf("gc-parent")).thenReturn(token);
        var child = new GroupConversation();
        child.setId("gc-child");
        child.setState(GroupConversation.GroupConversationState.AWAITING_APPROVAL);
        when(service.discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            return child;
        });

        assertThrows(MemberTurnCancelledException.class,
                () -> executor(service).executeAgentTurn(SUB_GROUP, parent(), "input", protocol(), 0, opinion(), null, null));

        verify(service).cancelDiscussion("gc-child", ControlSignal.CANCEL_GRACEFUL);
    }

    // --- a member's tool pause, auto-rejected by the group ---

    private static final GroupMember AGENT = new GroupMember("agent-a", "Agent A", 1, null);

    private static SimpleConversationMemorySnapshot toolPausedSnapshot() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("TOOL_CALL");
        return snapshot;
    }

    private static SimpleConversationMemorySnapshot erroredSnapshot() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ERROR);
        return snapshot;
    }

    private static ProtocolConfig protocol(MemberFailurePolicy policy) {
        return new ProtocolConfig(60, policy, 2, MemberUnavailablePolicy.SKIP);
    }

    /**
     * Runs a turn whose member pauses on a tool call and whose resumed run then
     * ends in ERROR.
     */
    private static TranscriptEntry turnWhoseResumeErrors(MemberFailurePolicy policy, IConversationService conversationService) throws Exception {
        var agentFactory = Mockito.mock(IAgentFactory.class);
        when(agentFactory.getLatestReadyAgent(any(), eq("agent-a"))).thenReturn(Mockito.mock(IAgent.class));
        var service = Mockito.mock(GroupConversationService.class);
        when(conversationService.startConversation(any(), eq("agent-a"), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-a", null));
        doAnswer(inv -> {
            inv.getArgument(8, IConversationService.ConversationResponseHandler.class).onComplete(toolPausedSnapshot());
            return null;
        }).when(conversationService).say(any(), eq("agent-a"), eq("conv-a"), any(), any(), any(), any(), anyBoolean(), any());
        doAnswer(inv -> {
            inv.getArgument(2, IConversationService.ConversationResponseHandler.class).onComplete(erroredSnapshot());
            return null;
        }).when(conversationService).resumeConversation(eq("conv-a"), any(), any(), any(), any(), any());
        var executor = new MemberTurnExecutor(conversationService, agentFactory, new GroupSigningGuard(null, null, "default"),
                new GroupContextBuilder(null), service, new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);
        return executor.executeAgentTurn(AGENT, parent(), "input", protocol(policy), 0, opinion(), null, null);
    }

    @Test
    void toolPauseResumeThatErrors_underSkip_isAContentlessFailedEntry() throws Exception {
        var entry = turnWhoseResumeErrors(MemberFailurePolicy.SKIP, Mockito.mock(IConversationService.class));

        assertEquals(GroupConversation.TurnOutcome.FAILED, entry.outcome());
        assertNull(entry.content());
    }

    @Test
    void toolPauseResumeThatErrors_underAbort_stopsThePhase() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);

        assertThrows(IGroupConversationService.GroupDiscussionException.class,
                () -> turnWhoseResumeErrors(MemberFailurePolicy.ABORT, conversationService));

        // The rejected tool call is not replayed to give ABORT its say.
        verify(conversationService, Mockito.times(1)).resumeConversation(eq("conv-a"), any(), any(), any(), any(), any());
    }

    private static MemberTurnExecutor executorOver(IConversationService conversationService) {
        return new MemberTurnExecutor(conversationService, Mockito.mock(IAgentFactory.class), new GroupSigningGuard(null, null, "default"),
                new GroupContextBuilder(null), Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);
    }

    /**
     * Runs the resume on its own thread and returns once that thread is blocked in
     * the resume wait, which it registers before blocking.
     */
    private static Thread startResumeAndAwaitTheWait(Runnable resume, CountDownLatch resumeStarted, List<Throwable> thrown)
            throws InterruptedException {
        var worker = new Thread(() -> {
            try {
                resume.run();
            } catch (Throwable t) {
                thrown.add(t);
            }
        });
        worker.start();
        assertTrue(resumeStarted.await(10, TimeUnit.SECONDS), "the resume was started");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.TIMED_WAITING, worker.getState(), "the worker is blocked in the resume wait");
        return worker;
    }

    @Test
    void toolPauseResumeWait_isReleasedByAnImmediateCancel() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var resumeStarted = new CountDownLatch(1);
        // The resumed run never answers.
        doAnswer(inv -> {
            resumeStarted.countDown();
            return null;
        }).when(conversationService).resumeConversation(eq("conv-a"), any(), any(), any(), any(), any());
        var token = new DiscussionControlToken();
        var link = DiscussionCancellation.link(token);
        var executor = executorOver(conversationService);
        List<Throwable> thrown = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        var worker = startResumeAndAwaitTheWait(() -> executor.tryResolveMemberToolPause(AGENT, parent(), "conv-a", 60, 0, opinion(),
                TranscriptEntryType.OPINION, null, "agent-a", link.cancellation()), resumeStarted, thrown);
        // Signalled while the response is still pending.
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
        worker.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(worker.isAlive(), "released at once, not at the 60 s member timeout");
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 20);
        assertEquals(1, thrown.size());
        assertInstanceOf(MemberTurnCancelledException.class, thrown.getFirst());
        verify(conversationService).cancelConversation("conv-a", ControlSignal.CANCEL_IMMEDIATE, "system:group");
        link.close();
    }

    /**
     * Under RETRY a timed-out attempt is drained before the retry; an immediate
     * cancel during that drain releases it instead of waiting the drain out.
     */
    @Test
    void retryDrain_isReleasedByAnImmediateCancel() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var agentFactory = Mockito.mock(IAgentFactory.class);
        when(agentFactory.getLatestReadyAgent(any(), eq("agent-a"))).thenReturn(Mockito.mock(IAgent.class));
        when(conversationService.startConversation(any(), eq("agent-a"), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-a", null));
        // The member never answers: the first attempt times out.
        var drainStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            drainStarted.countDown();
            return null;
        }).when(conversationService).cancelConversation("conv-a", ControlSignal.CANCEL_GRACEFUL, "system:group");
        var executor = new MemberTurnExecutor(conversationService, agentFactory, new GroupSigningGuard(null, null, "default"),
                new GroupContextBuilder(null), Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);
        var token = new DiscussionControlToken();
        var link = DiscussionCancellation.link(token);
        var retryTwice = new ProtocolConfig(4, MemberFailurePolicy.RETRY, 1, MemberUnavailablePolicy.SKIP);
        List<Object> outcome = new CopyOnWriteArrayList<>();

        var worker = new Thread(() -> {
            try {
                outcome.add(executor.executeAgentTurn(AGENT, parent(), "input", retryTwice, 0, opinion(), null, null, link.cancellation()));
            } catch (Throwable t) {
                outcome.add(t);
            }
        });
        worker.start();
        assertTrue(drainStarted.await(20, TimeUnit.SECONDS), "the first attempt timed out and its drain began");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        long cancelledAt = System.nanoTime();
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
        worker.join(TimeUnit.SECONDS.toMillis(20));

        assertFalse(worker.isAlive());
        assertTrue((System.nanoTime() - cancelledAt) / 1_000_000L < 2_000, "released by the cancel, not at the end of the 4 s drain");
        assertEquals(1, outcome.size());
        assertInstanceOf(MemberTurnCancelledException.class, outcome.getFirst(), "a cancel is not a timeout: " + outcome);
        link.close();
    }

    @Test
    void toolPauseResume_cancelledBeforeItStarted_startsNoNewTurn() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var token = new DiscussionControlToken();
        var link = DiscussionCancellation.link(token);
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);

        assertThrows(MemberTurnCancelledException.class, () -> executorOver(conversationService).tryResolveMemberToolPause(AGENT, parent(),
                "conv-a", 60, 0, opinion(), TranscriptEntryType.OPINION, null, "agent-a", link.cancellation()));

        verify(conversationService, never()).resumeConversation(any(), any(), any(), any(), any(), any());
        // The committed pause is not left behind.
        verify(conversationService).cancelConversation("conv-a", ControlSignal.CANCEL_GRACEFUL, "system:group");
        link.close();
    }

    @Test
    void toolPauseResume_cancelledWhileItRan_stillBooksItsSpendAndAgents() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var resumeStarted = new CountDownLatch(1);
        var handler = new AtomicReference<IConversationService.ConversationResponseHandler>();
        doAnswer(inv -> {
            handler.set(inv.getArgument(2, IConversationService.ConversationResponseHandler.class));
            resumeStarted.countDown();
            return null;
        }).when(conversationService).resumeConversation(eq("conv-a"), any(), any(), any(), any(), any());
        var token = new DiscussionControlToken();
        var link = DiscussionCancellation.link(token);
        var gc = parent();
        List<Throwable> thrown = new CopyOnWriteArrayList<>();

        var worker = startResumeAndAwaitTheWait(() -> executorOver(conversationService).tryResolveMemberToolPause(AGENT, gc, "conv-a", 60,
                0, opinion(), TranscriptEntryType.OPINION, null, "agent-a", link.cancellation()), resumeStarted, thrown);
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(worker.isAlive());
        assertInstanceOf(MemberTurnCancelledException.class, thrown.getFirst());

        // The resumed run finishes after the cancel released the wait.
        var late = new SimpleConversationMemorySnapshot();
        late.setConversationId("conv-a");
        late.setConversationState(ConversationState.READY);
        late.setConversationCost(0.25);
        handler.get().onComplete(late);

        assertEquals(0.25, gc.getTotalCost(), 1e-12, "the resumed run's spend is on the group's books");
        link.close();
    }

    @Test
    void legCap_aChildNeverGetsMoreThanItsParentLeft() {
        assertEquals(50, TurnBudget.legCap(0, null));
        assertEquals(12, TurnBudget.legCap(0, 12));
        assertEquals(5, TurnBudget.legCap(5, 12));
        assertEquals(0, TurnBudget.legCap(5, -3));
    }
}
