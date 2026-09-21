/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.HitlTimeoutHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScheduleFireExecutor}.
 */
class ScheduleFireExecutorTest {

    private IConversationService conversationService;
    private IScheduleStore scheduleStore;
    private HitlTimeoutHandler hitlTimeoutHandler;
    private DreamService dreamService;
    private TeamCadenceService teamCadenceService;
    private ToolCostTracker toolCostTracker;
    private ScheduleFireExecutor executor;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        scheduleStore = mock(IScheduleStore.class);
        hitlTimeoutHandler = mock(HitlTimeoutHandler.class);
        dreamService = mock(DreamService.class);
        teamCadenceService = mock(TeamCadenceService.class);
        toolCostTracker = mock(ToolCostTracker.class);

        executor = new ScheduleFireExecutor();
        // Inject mocks via reflection (field injection)
        setField(executor, "conversationService", conversationService);
        setField(executor, "scheduleStore", scheduleStore);
        setField(executor, "hitlTimeoutHandler", hitlTimeoutHandler);
        setField(executor, "dreamService", dreamService);
        setField(executor, "teamCadenceService", teamCadenceService);
        setField(executor, "toolCostTracker", toolCostTracker);
    }

    /**
     * The outcome of a fire has to come from the SNAPSHOT, not from the latch.
     * <p>
     * The latch is counted down from {@code Conversation.runStep}'s finally block,
     * which also runs on the failure branch, and {@code ConversationService.say}
     * swallows the {@code LifecycleException} — so "the handler was called" only
     * ever meant "the turn was attempted". Reporting COMPLETED there made every
     * in-pipeline failure (LLM outage, tool error, unresolvable workflow config)
     * look like a green fire: failCount never incremented, backoff never applied,
     * nothing was ever dead-lettered, and the deadlettered counter operators are
     * told to alert on stayed flat while a nightly agent errored for a month.
     */
    @Test
    void fire_conversationEndedInError_isRecordedFailedNotCompleted() throws Exception {
        var schedule = makeCronSchedule("sched-err", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-err", null));

        var errored = new SimpleConversationMemorySnapshot();
        errored.setConversationState(ConversationState.ERROR);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(errored);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertNotNull(result.errorMessage(), "a failed fire must record why, or the fire log says nothing went wrong");

        ArgumentCaptor<ScheduleFireLog> logged = ArgumentCaptor.forClass(ScheduleFireLog.class);
        verify(scheduleStore).logFire(logged.capture());
        assertEquals(FireStatus.FAILED.name(), logged.getValue().status());
    }

    /**
     * A SKIPPED turn is not a successful fire.
     * <p>
     * {@code ConversationResponseHandler.onSkipped} defaults to {@code onComplete},
     * so a single-method handler cannot tell them apart — and {@code onSkipped}
     * means the coordinator DROPPED the input without consuming it, because the
     * conversation was already IN_PROGRESS or AWAITING_HUMAN when the queued turn
     * ran. That is the ordinary case for {@code conversationStrategy=persistent}
     * (the default for every HEARTBEAT) while a previous fire is still executing,
     * or while a human is chatting in the same conversation. Recorded COMPLETED,
     * the poller re-armed the schedule and cleared failCount: the message the
     * schedule existed to send was lost, with a green fire log and a null
     * errorMessage.
     * <p>
     * It is not a FAILED fire either — that was the over-correction. A skip is its
     * own outcome: visible in the fire log with a reason, but never fed to the
     * retry/dead-letter machine, because a persistent heartbeat is skipped on every
     * fire for as long as its conversation is paused and a HITL approval left open
     * for ~21 minutes would otherwise dead-letter it. See
     * {@code SchedulePollerService.onFireSkipped}.
     */
    @Test
    void fire_turnSkippedBecauseTheConversationWasBusy_isRecordedSkipped() throws Exception {
        var schedule = makeCronSchedule("sched-skip", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-skip", null));

        var busy = new SimpleConversationMemorySnapshot();
        busy.setConversationState(ConversationState.IN_PROGRESS);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onSkipped(busy);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.SKIPPED.name(), result.status(), "a dropped turn is neither a success nor a failure");
        assertNotEquals(FireStatus.COMPLETED.name(), result.status(), "a dropped turn must not re-arm the schedule as a success");
        assertNotEquals(FireStatus.FAILED.name(), result.status(), "a dropped turn must not enter the retry/dead-letter machine");
        assertNotNull(result.errorMessage(), "a skipped fire must say why, or nothing distinguishes it from a green one");
        assertTrue(result.errorMessage().contains("skipped"), "the reason must name the skip: " + result.errorMessage());
    }

    /** The same, for a conversation already paused on a human approval. */
    @Test
    void fire_turnSkippedBecauseTheConversationAwaitsAHuman_isRecordedSkipped() throws Exception {
        var schedule = makeCronSchedule("sched-paused", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-paused", null));

        var paused = new SimpleConversationMemorySnapshot();
        paused.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onSkipped(paused);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertEquals(FireStatus.SKIPPED.name(), executor.fire(schedule, "instance-1", 1).status());
    }

    /**
     * The case the handler-based skip detection above cannot see.
     * <p>
     * {@code ConversationService.say} fast-fails a conversation that is ALREADY
     * persisted {@code AWAITING_HUMAN} by throwing
     * {@code ConversationAwaitingApprovalException} BEFORE the response handler is
     * wired, so {@code onSkipped} is never called on this road. The handler branch
     * only covers the race where the pause commits after this say loaded the
     * memory; for a {@code conversationStrategy=persistent} heartbeat sitting on an
     * open approval, the throw is the STEADY state and every single fire takes it.
     * <p>
     * Caught by the broad {@code catch (Exception e)} it was recorded FAILED, which
     * incremented failCount, applied backoff and dead-lettered the schedule — the
     * exact regression SKIPPED exists to prevent, and the opposite of what
     * {@code docs/scheduling.md} promises operators.
     */
    @Test
    void fire_sayRejectsThePausedConversation_isRecordedSkippedNotFailed() throws Exception {
        var schedule = makeCronSchedule("sched-paused-throw", "persistent");
        schedule.setPersistentConversationId("conv-paused");

        doThrow(new IConversationService.ConversationAwaitingApprovalException(
                "Conversation is awaiting human approval")).when(conversationService)
                .say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.SKIPPED.name(), result.status(),
                "a conversation paused on an approval rejected the input — nothing ran, but nothing broke");
        assertNotEquals(FireStatus.FAILED.name(), result.status(),
                "recorded FAILED it enters the retry/backoff machine and dead-letters the heartbeat");
        assertNotNull(result.errorMessage(), "a skipped fire must say why");
        assertTrue(result.errorMessage().contains("skipped"), "the reason must name the skip: " + result.errorMessage());

        ArgumentCaptor<ScheduleFireLog> logged = ArgumentCaptor.forClass(ScheduleFireLog.class);
        verify(scheduleStore).logFire(logged.capture());
        assertEquals(FireStatus.SKIPPED.name(), logged.getValue().status());
    }

    /**
     * A turn cut short mid-pipeline did not do the schedule's work either, so it
     * belongs with ERROR rather than with success. Only ERROR used to be rejected.
     */
    @Test
    void fire_conversationExecutionInterrupted_isRecordedFailed() throws Exception {
        var schedule = makeCronSchedule("sched-interrupted", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-interrupted", null));

        var interrupted = new SimpleConversationMemorySnapshot();
        interrupted.setConversationState(ConversationState.EXECUTION_INTERRUPTED);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(interrupted);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertEquals(FireStatus.FAILED.name(), executor.fire(schedule, "instance-1", 1).status());
    }

    /**
     * A turn that paused for a human approval of its OWN accord still ran: the
     * input was consumed and the pipeline executed, so it stays COMPLETED. That
     * distinction is exactly what {@code onSkipped} carries, and it is why the
     * handler has to implement both methods rather than inspect the state alone.
     */
    @Test
    void fire_turnThatPausedItselfForApproval_isStillCompleted() throws Exception {
        var schedule = makeCronSchedule("sched-hitl", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-hitl", null));

        var awaiting = new SimpleConversationMemorySnapshot();
        awaiting.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(awaiting);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertEquals(FireStatus.COMPLETED.name(), executor.fire(schedule, "instance-1", 1).status());
    }

    @Test
    void fire_conversationEndedReady_isStillCompleted() throws Exception {
        var schedule = makeCronSchedule("sched-ok", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-ok", null));

        var ready = new SimpleConversationMemorySnapshot();
        ready.setConversationState(ConversationState.READY);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(ready);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertEquals(FireStatus.COMPLETED.name(), executor.fire(schedule, "instance-1", 1).status());
    }

    /**
     * The persistent strategy must record its conversation with a single-field
     * write. Calling {@code updateSchedule} here persisted the PRE-claim copy of
     * the schedule — fireStatus still PENDING, claim columns empty, nextFire still
     * the past due time — which un-claimed the row in the middle of its own fire.
     * The next poll then claimed it again and pushed a second concurrent turn into
     * this very same persistent conversation.
     */
    @Test
    void fire_persistentStrategy_recordsConversationWithoutRewritingTheWholeSchedule() throws Exception {
        var schedule = makeCronSchedule("sched-persist", "persistent");
        schedule.setPersistentConversationId(null);
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-new", null));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        executor.fire(schedule, "instance-1", 1);

        verify(scheduleStore).setPersistentConversationId("sched-persist", "conv-new");
        verify(scheduleStore, never()).updateSchedule(anyString(), any());
    }

    /**
     * The fire log's cost was hard-wired to 0.0 on the conversation path, so the
     * number shown to operators (and read by Dream) meant nothing. It is a DELTA: a
     * persistent schedule reuses one conversation across every fire, so the running
     * total would otherwise attribute the whole history to a single fire.
     */
    @Test
    void fire_recordsTheCostThisFireAdded_notTheConversationTotal() throws Exception {
        var schedule = makeCronSchedule("sched-cost", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-cost", null));

        var before = new ToolCostTracker.ConversationCostMetrics("conv-cost");
        before.addToolCost("websearch", 2.0);
        var after = new ToolCostTracker.ConversationCostMetrics("conv-cost");
        after.addToolCost("websearch", 2.0);
        after.addToolCost("websearch", 0.5);
        when(toolCostTracker.getConversationCosts("conv-cost")).thenReturn(before, after);

        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertEquals(0.5, executor.fire(schedule, "instance-1", 1).cost(), 1e-9);
    }

    /**
     * The wait was a hard-coded five minutes while {@code lease-timeout} — the
     * window after which another instance may reclaim the schedule — was already
     * configurable, so a deployment that raised the lease still had its fires
     * abandoned at five minutes with no way to change it. The bound now comes from
     * {@code eddi.schedule.fire-timeout}.
     * <p>
     * The {@code @Timeout} is load-bearing: against the hard-coded constant this
     * test does not fail with a wrong value, it blocks for five minutes.
     */
    @Test
    @Timeout(30)
    void fire_waitIsBoundedByTheConfiguredFireTimeout_notAHardCodedFiveMinutes() throws Exception {
        var schedule = makeCronSchedule("sched-timeout", "new");
        setField(executor, "fireTimeout", Duration.ofMillis(50));
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-timeout", null));
        // The response handler is never invoked: the turn is still running when the
        // configured bound elapses.
        doNothing().when(conversationService)
                .say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("PT0.05S"),
                "the configured bound must be the one enforced, and the message must name it: " + result.errorMessage());
    }

    /**
     * An absent or unconvertible {@code eddi.schedule.fire-timeout} must fall back
     * to the same five minutes the constant used, not NPE the fire. A fire that
     * dies on its own configuration lookup is recorded FAILED and retried, so one
     * typo in a properties file would take every scheduled agent in the deployment
     * down without saying why.
     */
    @Test
    void fire_nullFireTimeout_fallsBackToTheDefaultInsteadOfFailingTheFire() throws Exception {
        var schedule = makeCronSchedule("sched-null-timeout", "new");
        setField(executor, "fireTimeout", null);
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-null-timeout", null));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-null-timeout", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status(),
                "a null timeout must resolve to the default, not throw inside the fire");
        assertNull(result.errorMessage());
    }

    @Test
    void fire_cron_newStrategy_createsConversation() throws Exception {
        var schedule = makeCronSchedule("sched-1", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-1", null));

        // say() calls the response handler immediately
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertNotNull(result);
        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertEquals("conv-1", result.conversationId());
        assertEquals(1, result.attemptNumber());
        verify(scheduleStore).logFire(any());
    }

    @Test
    void fire_cron_persistentStrategy_reusesConversation() throws Exception {
        var schedule = makeCronSchedule("sched-2", "persistent");
        schedule.setPersistentConversationId("existing-conv");

        // Mock readConversation to succeed (conversation exists)
        when(conversationService.readConversation(any(), any(), eq("existing-conv"), anyBoolean(), anyBoolean(), any())).thenReturn(null);

        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), eq("existing-conv"), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertEquals("existing-conv", result.conversationId());
        // Should NOT create a new conversation
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void fire_heartbeat_defaultsPersistentStrategy() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-1", null); // null strategy → defaults to persistent
        schedule.setPersistentConversationId("hb-conv");

        when(conversationService.readConversation(any(), any(), eq("hb-conv"), anyBoolean(), anyBoolean(), any())).thenReturn(null);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), eq("hb-conv"), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals("hb-conv", result.conversationId());
    }

    @Test
    void fire_heartbeat_defaultsMessageToHeartbeat() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-2", "persistent");
        schedule.setMessage(null); // no message set
        schedule.setPersistentConversationId("hb-conv");

        when(conversationService.readConversation(any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(null);

        var inputCaptor = ArgumentCaptor.forClass(InputData.class);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(), anyBoolean(), any());

        executor.fire(schedule, "instance-1", 1);

        assertEquals("heartbeat", inputCaptor.getValue().getInput());
    }

    @Test
    void fire_injectsScheduleContext() throws Exception {
        var schedule = makeCronSchedule("sched-3", "new");
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-3", null));

        var inputCaptor = ArgumentCaptor.forClass(InputData.class);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(), anyBoolean(), any());

        executor.fire(schedule, "instance-1", 1);

        var ctx = inputCaptor.getValue().getContext();
        assertNotNull(ctx.get("schedule"));
        assertNotNull(ctx.get("userId"));
    }

    @Test
    void fire_heartbeat_injectsHeartbeatTriggerContext() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-3", "new");
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-hb", null));

        var inputCaptor = ArgumentCaptor.forClass(InputData.class);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(), anyBoolean(), any());

        executor.fire(schedule, "instance-1", 1);

        var ctx = inputCaptor.getValue().getContext();
        var scheduleCtx = ctx.get("schedule");
        assertNotNull(scheduleCtx);
        var data = (Map<?, ?>) scheduleCtx.getValue();
        assertEquals("heartbeat", data.get("trigger"));
        assertEquals("HEARTBEAT", data.get("triggerType"));
    }

    @Test
    void fire_exceptionResultsInFailedStatus() throws Exception {
        var schedule = makeCronSchedule("sched-err", "new");
        when(conversationService.startConversation(any(), any(), any(), any())).thenThrow(new RuntimeException("Agent not deployed"));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 3);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Agent not deployed"));
        assertEquals(3, result.attemptNumber());
    }

    /**
     * Finding B2 — {@code fire()} waits on a
     * {@link java.util.concurrent.CountDownLatch} inside a broad
     * {@code catch (Exception)}. {@code latch.await} CLEARS the thread's interrupt
     * status before it throws, so without an explicit restore the poller thread's
     * shutdown signal is swallowed and it keeps firing further schedules while the
     * executor is shutting down.
     */
    @Test
    @Timeout(10)
    void fire_interruptedWhileWaiting_restoresInterruptFlagAndLogsFailed() throws Exception {
        var schedule = makeCronSchedule("sched-interrupt", "new");
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-int", null));

        // Never completes the response handler; interrupts the caller instead, so the
        // latch.await() below throws InterruptedException immediately (and clears the
        // flag) rather than blocking for its 5-minute budget.
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertFalse(Thread.currentThread().isInterrupted(), "precondition: flag starts clear");
        try {
            ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

            assertTrue(Thread.currentThread().isInterrupted(),
                    "fire() must re-assert the interrupt flag that latch.await consumed, otherwise the "
                            + "poller keeps firing schedules through shutdown");
            // The attempt still has to be recorded — restoring the flag must not
            // short-circuit the fire log.
            assertEquals(FireStatus.FAILED.name(), result.status());
            assertTrue(result.errorMessage().startsWith("InterruptedException"),
                    "expected the interrupt to be recorded, got: " + result.errorMessage());
            verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
        } finally {
            // Never let the flag leak into the next test on this thread.
            Thread.interrupted();
        }
    }

    /**
     * B2, ordering half. Restoring the interrupt flag is only safe AFTER the fire
     * log has been written. The synchronous MongoDB driver checks out a connection
     * with {@code lockInterruptibly()} and aborts with
     * {@code MongoInterruptedException} when the calling thread's flag is already
     * set, so a restore placed inside the catch block makes {@code logFire} throw
     * on exactly the interrupt it exists to record — the FAILED attempt then
     * vanishes (the local catch swallows the store failure) and the poller can
     * never see it. A plain Mockito mock is interrupt-insensitive by construction,
     * so this stub reproduces that sensitivity explicitly.
     */
    @Test
    @Timeout(10)
    void fire_interrupted_writesFireLogBeforeRestoringTheFlag() throws Exception {
        var schedule = makeCronSchedule("sched-interrupt-order", "new");
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-int", null));
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        List<ScheduleFireLog> persisted = interruptSensitiveFireLogStore();

        assertFalse(Thread.currentThread().isInterrupted(), "precondition: flag starts clear");
        try {
            ScheduleFireLog result = executor.fire(schedule, "instance-1", 4);

            assertEquals(1, persisted.size(),
                    "the FAILED attempt must reach an interrupt-sensitive store — restoring the flag before "
                            + "logFire() aborts the very write the interrupt path exists to perform");
            assertEquals(FireStatus.FAILED.name(), persisted.get(0).status());
            assertEquals(4, persisted.get(0).attemptNumber());
            assertEquals(FireStatus.FAILED.name(), result.status());
            // ...and the cancellation signal still reaches the caller afterwards.
            assertTrue(Thread.currentThread().isInterrupted(),
                    "fire() must still re-assert the interrupt flag that latch.await consumed");
        } finally {
            Thread.interrupted();
        }
    }

    /** Same ordering guarantee on the Dream fast-path's sibling catch. */
    @Test
    @Timeout(10)
    void fire_dreamScheduleInterrupted_writesFireLogBeforeRestoringTheFlag() throws Exception {
        var schedule = makeDreamSchedule("sched-dream-interrupt-order", "user-9");
        schedule.setAgentVersion(1);
        when(dreamService.processScheduledFire(any(), any(), any())).thenAnswer(inv -> {
            Thread.interrupted();
            throw new InterruptedException("consolidation interrupted");
        });

        List<ScheduleFireLog> persisted = interruptSensitiveFireLogStore();

        assertFalse(Thread.currentThread().isInterrupted(), "precondition: flag starts clear");
        try {
            ScheduleFireLog result = executor.fire(schedule, "instance-1", 2);

            assertEquals(1, persisted.size(),
                    "the Dream fast-path must log the FAILED attempt before re-asserting the interrupt flag");
            assertEquals(FireStatus.FAILED.name(), persisted.get(0).status());
            assertEquals(2, persisted.get(0).attemptNumber());
            assertEquals(FireStatus.FAILED.name(), result.status());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the Dream fast-path must still re-assert the interrupt flag its catch consumed");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Stubs {@code logFire} the way the synchronous MongoDB driver behaves: a write
     * attempted while the calling thread's interrupt flag is set aborts instead of
     * persisting.
     *
     * @return the live list of fire logs that actually made it to the store
     */
    private List<ScheduleFireLog> interruptSensitiveFireLogStore() throws Exception {
        List<ScheduleFireLog> persisted = new ArrayList<>();
        doAnswer(inv -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("MongoInterruptedException: interrupted on connection checkout");
            }
            persisted.add(inv.getArgument(0));
            return null;
        }).when(scheduleStore).logFire(any());
        return persisted;
    }

    @Test
    void fire_logsFireAttemptEvenOnFailure() throws Exception {
        var schedule = makeCronSchedule("sched-err2", "new");
        when(conversationService.startConversation(any(), any(), any(), any())).thenThrow(new RuntimeException("fail"));

        executor.fire(schedule, "instance-1", 1);

        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
    }

    @Test
    void fire_invalidEnvironment_defaultsToProduction() throws Exception {
        var schedule = makeCronSchedule("sched-env", "new");
        schedule.setEnvironment("nonsense");
        when(conversationService.startConversation(eq(Environment.production), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-env", null));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(eq(Environment.production), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status());
    }

    // --- HITL timeout fast-path (finding 22: the finite-timeout leg glue) ---

    @Test
    void fire_hitlTimeout_delegatesToHandlerAndLogsCompleted() throws Exception {
        var schedule = makeHitlTimeoutSchedule("sched-hitl", "conv-paused", "AUTO_REJECT");

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        // The metadata key ('hitlType') and value ('hitl_timeout') route into the
        // handler — a rename on either side would break this and the timeout would
        // silently never fire.
        var mdCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hitlTimeoutHandler).handleTimeout(mdCaptor.capture());
        assertEquals("hitl_timeout", mdCaptor.getValue().get("hitlType"));
        assertEquals("conv-paused", mdCaptor.getValue().get("conversationId"));

        // The normal say()-based path is NOT taken for a HITL timeout fire.
        verify(conversationService, never()).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());

        // A fire log is written for observability parity with the normal path.
        assertNotNull(result);
        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertEquals("conv-paused", result.conversationId());
        assertEquals(1, result.attemptNumber());
        assertNull(result.errorMessage());
        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.COMPLETED.name())));
    }

    @Test
    void fire_hitlTimeout_handlerThrows_logsFailedWithoutPropagating() throws Exception {
        var schedule = makeHitlTimeoutSchedule("sched-hitl-err", "conv-boom", "ABORT");
        doThrow(new RuntimeException("handler blew up")).when(hitlTimeoutHandler).handleTimeout(any());

        // Exception isolation: a throwing handler must not propagate out of fire()
        // (the poller would otherwise treat the whole batch as failed).
        ScheduleFireLog result = executor.fire(schedule, "instance-9", 2);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("handler blew up"),
                "error must carry the handler failure, got: " + result.errorMessage());
        assertEquals(2, result.attemptNumber());
        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
    }

    @Test
    void fire_hitlTimeout_fireLogFailure_isSwallowed() throws Exception {
        var schedule = makeHitlTimeoutSchedule("sched-hitl-log", "conv-log", "AUTO_APPROVE");
        doThrow(new RuntimeException("log store down")).when(scheduleStore).logFire(any());

        // Even a fire-log write failure must not escape fire() — the timeout was
        // already handled; losing the log entry is not fatal.
        assertDoesNotThrow(() -> executor.fire(schedule, "instance-1", 1));
        verify(hitlTimeoutHandler).handleTimeout(any());
    }

    // --- Dream consolidation dispatch (finding I1) ---

    /**
     * The conversation path blocks on a 5-minute {@code CountDownLatch}; if the
     * Dream fast-path ever stops short-circuiting, these tests would hang rather
     * than fail, hence the timeouts.
     */
    @Test
    @Timeout(10)
    void fire_dreamSchedule_dispatchesToDreamServiceInsteadOfSayingAnything() throws Exception {
        var schedule = makeDreamSchedule("sched-dream-1", "user-42");
        schedule.setAgentVersion(3);
        when(dreamService.processScheduledFire("agent-1", 3, "user-42"))
                .thenReturn(new DreamService.DreamResult("user-42", 4, 1, 2, 120L, 0.0125, null));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertNull(result.errorMessage());
        assertEquals(0.0125, result.cost(), 1e-9);
        assertNull(result.conversationId());
        verify(dreamService).processScheduledFire("agent-1", 3, "user-42");
        // A dream cycle is maintenance, not a conversation turn
        verifyNoInteractions(conversationService);
        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.COMPLETED.name())));
    }

    @Test
    @Timeout(10)
    void fire_dreamSchedule_passesUserIdThroughUndefaulted() throws Exception {
        // No userId on the schedule: it must reach DreamService as-is so the
        // rejection is loud, rather than being defaulted to "system:scheduler".
        var schedule = makeDreamSchedule("sched-dream-2", null);
        when(dreamService.processScheduledFire(any(), any(), any()))
                .thenReturn(new DreamService.DreamResult(null, 0, 0, 0, 1L, 0.0, "no userId"));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertEquals("no userId", result.errorMessage());
        verify(dreamService).processScheduledFire("agent-1", 0, null);
    }

    @Test
    @Timeout(10)
    void fire_dreamSchedule_failedCycle_marksFireFailedSoItRetries() throws Exception {
        var schedule = makeDreamSchedule("sched-dream-3", "user-7");
        when(dreamService.processScheduledFire(any(), any(), any()))
                .thenReturn(new DreamService.DreamResult("user-7", 0, 0, 0, 5L, 0.0,
                        "Memory consolidation LLM call failed (anthropic/claude): 401 Unauthorized"));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 2);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertTrue(result.errorMessage().contains("401 Unauthorized"),
                "the cause must reach the fire log, got: " + result.errorMessage());
        assertEquals(2, result.attemptNumber());
        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
    }

    @Test
    @Timeout(10)
    void fire_dreamSchedule_serviceThrows_logsFailedWithoutPropagating() throws Exception {
        var schedule = makeDreamSchedule("sched-dream-4", "user-7");
        when(dreamService.processScheduledFire(any(), any(), any()))
                .thenThrow(new RuntimeException("store exploded"));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertTrue(result.errorMessage().contains("store exploded"),
                "error must carry the cause, got: " + result.errorMessage());
        verify(scheduleStore).logFire(any());
    }

    @Test
    @Timeout(10)
    void fire_nonDreamSchedule_doesNotReachDreamService() throws Exception {
        var schedule = makeCronSchedule("sched-plain", "new");
        when(conversationService.startConversation(any(), eq("agent-1"), eq("system:scheduler"), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-1", null));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        executor.fire(schedule, "instance-1", 1);

        verifyNoInteractions(dreamService);
    }

    // --- Helpers ---

    /**
     * B2 again, on the OTHER broad catch. {@code fire()} restores the interrupt
     * flag with an explicit comment; the Dream fast-path added by this PR has an
     * equally broad {@code catch (Exception)} and did not. A blocking call inside
     * consolidation that throws InterruptedException CLEARS the flag, so swallowing
     * it leaves the poller thread firing further schedules through a shutdown — the
     * exact failure B2 was raised for, reachable by the second of two sibling
     * catches in the same class.
     */
    @Test
    @Timeout(10)
    void fire_dreamScheduleInterrupted_restoresInterruptFlagAndLogsFailed() throws Exception {
        var schedule = makeDreamSchedule("sched-dream-interrupt", "user-9");
        schedule.setAgentVersion(1);

        // Mimic a blocking call inside consolidation being interrupted: the flag is
        // consumed by the throw, exactly as latch.await() does on the conversation
        // path.
        when(dreamService.processScheduledFire(any(), any(), any())).thenAnswer(inv -> {
            Thread.interrupted();
            throw new InterruptedException("consolidation interrupted");
        });

        assertFalse(Thread.currentThread().isInterrupted(), "precondition: flag starts clear");
        try {
            ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

            assertTrue(Thread.currentThread().isInterrupted(),
                    "the Dream fast-path must re-assert the interrupt flag its catch consumed, or the poller "
                            + "keeps firing schedules through shutdown");
            assertEquals(FireStatus.FAILED.name(), result.status());
            assertTrue(result.errorMessage().startsWith("InterruptedException"),
                    "expected the interrupt to be recorded, got: " + result.errorMessage());
            verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
        } finally {
            Thread.interrupted();
        }
    }

    private static ScheduleConfiguration makeTeamCadenceSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("team-cadence-group-1");
        s.setTriggerType(TriggerType.CRON);
        s.setCronExpression("0 9 * * 1");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.CLAIMED);
        s.setNextFire(Instant.now().minusSeconds(1));
        s.setMetadata(Map.of(
                TeamCadenceService.METADATA_TYPE_KEY, TeamCadenceService.METADATA_TYPE_CADENCE,
                TeamCadenceService.METADATA_GROUP_ID_KEY, "group-1",
                TeamCadenceService.METADATA_CADENCE_ID_KEY, "cadence-1"));
        return s;
    }

    @Test
    @Timeout(10)
    void fire_teamCadenceSchedule_dispatchesToTheCadenceService() throws Exception {
        var schedule = makeTeamCadenceSchedule("sched-cadence-1");
        when(teamCadenceService.processScheduledFire(any()))
                .thenReturn(new TeamCadenceService.CadenceResult("group-1", "cadence-1", "gc-1", 3, null, null));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertEquals("gc-1", result.conversationId(), "the fire log records WHICH discussion the cadence started");
        verify(teamCadenceService).processScheduledFire(schedule.getMetadata());
        // A cadence pull is orchestration, not a conversation turn.
        verifyNoInteractions(conversationService);
    }

    @Test
    @Timeout(10)
    void fire_teamCadenceSchedule_skipIsCompleted_failureRetries() throws Exception {
        var skipped = makeTeamCadenceSchedule("sched-cadence-2");
        when(teamCadenceService.processScheduledFire(any()))
                .thenReturn(new TeamCadenceService.CadenceResult("group-1", "cadence-1", null, 0,
                        "No executable backlog tasks", null));
        assertEquals(FireStatus.COMPLETED.name(), executor.fire(skipped, "instance-1", 1).status(),
                "a deliberate skip is a successful fire, not a retryable failure");

        var failing = makeTeamCadenceSchedule("sched-cadence-3");
        when(teamCadenceService.processScheduledFire(any()))
                .thenReturn(new TeamCadenceService.CadenceResult("group-1", "cadence-1", null, 0, null,
                        "No workspace exists for group group-1"));
        ScheduleFireLog failed = executor.fire(failing, "instance-1", 2);
        assertEquals(FireStatus.FAILED.name(), failed.status(), "a real failure must retry and dead-letter");
        assertTrue(failed.errorMessage().contains("No workspace"), failed.errorMessage());
    }

    /**
     * The fire log is a record of what happened, not a precondition for it. A store
     * that cannot write it must not turn a cadence pull that already ran into a
     * FAILED fire — the poller would then re-fire it, pulling the same backlog
     * tasks into a second discussion. The outcome the caller sees is still the
     * cadence's own.
     */
    @Test
    @Timeout(10)
    void fire_teamCadence_fireLogFailure_doesNotChangeTheOutcome() throws Exception {
        var schedule = makeTeamCadenceSchedule("sched-cadence-4");
        when(teamCadenceService.processScheduledFire(any()))
                .thenReturn(new TeamCadenceService.CadenceResult("group-1", "cadence-1", "gc-9", 2, null, null));
        doThrow(new RuntimeException("db down")).when(scheduleStore).logFire(any());

        ScheduleFireLog result = assertDoesNotThrow(() -> executor.fire(schedule, "instance-1", 1));

        assertEquals(FireStatus.COMPLETED.name(), result.status());
        assertEquals("gc-9", result.conversationId());
        verify(scheduleStore).logFire(any());
    }

    private static ScheduleConfiguration makeDreamSchedule(String id, String userId) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("dream-agent-1");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 3 * * *");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setUserId(userId);
        s.setFireStatus(FireStatus.CLAIMED);
        s.setNextFire(Instant.now().minusSeconds(1));
        s.setMetadata(Map.of(
                DreamService.METADATA_TYPE_KEY, DreamService.METADATA_TYPE_CONSOLIDATION));
        return s;
    }

    private static ScheduleConfiguration makeHitlTimeoutSchedule(String id, String conversationId, String policy) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("hitl-timeout-" + conversationId);
        s.setAgentId("agent-1");
        s.setEnvironment("production");
        s.setFireStatus(FireStatus.CLAIMED);
        s.setNextFire(Instant.now().minusSeconds(1));
        // The producer (ConversationService.scheduleHitlTimeout) sets exactly these
        // keys.
        s.setMetadata(Map.of(
                "hitlType", "hitl_timeout",
                "policy", policy,
                "surface", "regular",
                "conversationId", conversationId));
        return s;
    }

    private static ScheduleConfiguration makeCronSchedule(String id, String strategy) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Schedule");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 9 * * *");
        s.setMessage("Good morning");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setUserId("system:scheduler");
        s.setConversationStrategy(strategy);
        s.setFireStatus(FireStatus.CLAIMED);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static ScheduleConfiguration makeHeartbeatSchedule(String id, String strategy) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Heartbeat");
        s.setTriggerType(TriggerType.HEARTBEAT);
        s.setAgentId("agent-1");
        s.setHeartbeatIntervalSeconds(300L);
        s.setMessage("check");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setUserId("system:scheduler");
        s.setConversationStrategy(strategy);
        s.setFireStatus(FireStatus.CLAIMED);
        s.setNextFire(Instant.now().minusSeconds(60));
        return s;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + fieldName, e);
        }
    }
}
