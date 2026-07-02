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
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScheduleFireExecutor}.
 */
class ScheduleFireExecutorTest {

    private IConversationService conversationService;
    private IScheduleStore scheduleStore;
    private ai.labs.eddi.engine.internal.HitlTimeoutHandler hitlTimeoutHandler;
    private ScheduleFireExecutor executor;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        scheduleStore = mock(IScheduleStore.class);
        hitlTimeoutHandler = mock(ai.labs.eddi.engine.internal.HitlTimeoutHandler.class);

        executor = new ScheduleFireExecutor();
        // Inject mocks via reflection (field injection)
        setField(executor, "conversationService", conversationService);
        setField(executor, "scheduleStore", scheduleStore);
        setField(executor, "hitlTimeoutHandler", hitlTimeoutHandler);
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
        var data = (java.util.Map<?, ?>) scheduleCtx.getValue();
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
        var mdCaptor = ArgumentCaptor.forClass(java.util.Map.class);
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

    // --- Helpers ---

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
        s.setMetadata(java.util.Map.of(
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
