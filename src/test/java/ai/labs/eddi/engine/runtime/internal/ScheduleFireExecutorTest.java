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
    private ScheduleFireExecutor executor;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        scheduleStore = mock(IScheduleStore.class);

        executor = new ScheduleFireExecutor();
        // Inject mocks via reflection (field injection)
        setField(executor, "conversationService", conversationService);
        setField(executor, "scheduleStore", scheduleStore);
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
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                any());

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
        when(conversationService.readConversation(any(), any(), eq("existing-conv"), anyBoolean(), anyBoolean(), any()))
                .thenReturn(null);

        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), eq("existing-conv"), anyBoolean(), anyBoolean(), any(), any(),
                anyBoolean(), any());

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

        when(conversationService.readConversation(any(), any(), eq("hb-conv"), anyBoolean(), anyBoolean(), any()))
                .thenReturn(null);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), eq("hb-conv"), anyBoolean(), anyBoolean(), any(), any(),
                anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals("hb-conv", result.conversationId());
    }

    @Test
    void fire_heartbeat_defaultsMessageToHeartbeat() throws Exception {
        var schedule = makeHeartbeatSchedule("hb-2", "persistent");
        schedule.setMessage(null); // no message set
        schedule.setPersistentConversationId("hb-conv");

        when(conversationService.readConversation(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(null);

        var inputCaptor = ArgumentCaptor.forClass(InputData.class);
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(),
                anyBoolean(), any());

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
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(),
                anyBoolean(), any());

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
        }).when(conversationService).say(any(), any(), any(), anyBoolean(), anyBoolean(), any(), inputCaptor.capture(),
                anyBoolean(), any());

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
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Agent not deployed"));

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 3);

        assertEquals(FireStatus.FAILED.name(), result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Agent not deployed"));
        assertEquals(3, result.attemptNumber());
    }

    @Test
    void fire_logsFireAttemptEvenOnFailure() throws Exception {
        var schedule = makeCronSchedule("sched-err2", "new");
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("fail"));

        executor.fire(schedule, "instance-1", 1);

        verify(scheduleStore).logFire(argThat(log -> log.status().equals(FireStatus.FAILED.name())));
    }

    @Test
    void fire_invalidEnvironment_defaultsToUnrestricted() throws Exception {
        var schedule = makeCronSchedule("sched-env", "new");
        schedule.setEnvironment("nonsense");
        when(conversationService.startConversation(eq(Environment.production), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-env", null));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(8)).onComplete(null);
            return null;
        }).when(conversationService).say(eq(Environment.production), any(), any(), anyBoolean(), anyBoolean(), any(),
                any(), anyBoolean(), any());

        ScheduleFireLog result = executor.fire(schedule, "instance-1", 1);

        assertEquals(FireStatus.COMPLETED.name(), result.status());
    }

    // --- Helpers ---

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
