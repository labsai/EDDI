package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestScheduleStore}.
 */
class RestScheduleStoreTest {

    private IScheduleStore scheduleStore;
    private RestScheduleStore rest;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        var fireExecutor = mock(ScheduleFireExecutor.class);
        var pollerService = mock(SchedulePollerService.class);

        rest = new RestScheduleStore();
        // Inject mocks (field injection in REST — we use reflection for tests)
        setField(rest, "scheduleStore", scheduleStore);
        setField(rest, "fireExecutor", fireExecutor);
        setField(rest, "pollerService", pollerService);
        setField(rest, "defaultTimeZone", "UTC");
        setField(rest, "minIntervalSeconds", 60L);
    }

    // --- Create ---

    @Test
    void create_cronSchedule_setsDefaults() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("new-id");

        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Good morning");
        schedule.setName("Morning check");

        Response response = rest.createSchedule(schedule);

        assertEquals(201, response.getStatus());
        var created = (ScheduleConfiguration) response.getEntity();
        assertEquals(TriggerType.CRON, created.getTriggerType());
        assertEquals("production", created.getEnvironment());
        assertEquals("system:scheduler", created.getUserId());
        assertEquals("new", created.getConversationStrategy());
        assertEquals("UTC", created.getTimeZone());
        assertNotNull(created.getNextFire());
    }

    @Test
    void create_heartbeatSchedule_setsDefaults() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("hb-id");

        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setHeartbeatIntervalSeconds(300L);
        schedule.setName("Health check");

        Response response = rest.createSchedule(schedule);

        assertEquals(201, response.getStatus());
        var created = (ScheduleConfiguration) response.getEntity();
        assertEquals(TriggerType.HEARTBEAT, created.getTriggerType());
        assertEquals("persistent", created.getConversationStrategy());
        assertEquals("heartbeat", created.getMessage());
        assertNotNull(created.getNextFire());
    }

    @Test
    void create_rejectsMissingBotId() {
        var schedule = new ScheduleConfiguration();
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsCronWithoutMessage() {
        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setCronExpression("0 9 * * *");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsHeartbeatWithoutInterval() {
        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setName("Bad heartbeat");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsIntervalBelowMinimum() {
        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setHeartbeatIntervalSeconds(30L); // below 60s minimum
        schedule.setName("Too fast");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("below minimum"));
    }

    @Test
    void create_rejectsInvalidCron() {
        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setCronExpression("not valid");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsInvalidTimezone() {
        var schedule = new ScheduleConfiguration();
        schedule.setBotId("bot-1");
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Hello");
        schedule.setTimeZone("Invalid/Zone");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    // --- Read ---

    @Test
    void readAll_delegatesToStore() throws Exception {
        when(scheduleStore.readAllSchedules(500)).thenReturn(List.of());

        List<ScheduleConfiguration> result = rest.readAllSchedules(null);

        assertEquals(0, result.size());
        verify(scheduleStore).readAllSchedules(500);
    }

    @Test
    void readAll_filtersByBotId() throws Exception {
        when(scheduleStore.readSchedulesByBotId("bot-1")).thenReturn(List.of());

        rest.readAllSchedules("bot-1");

        verify(scheduleStore).readSchedulesByBotId("bot-1");
        verify(scheduleStore, never()).readAllSchedules(anyInt());
    }

    // --- Enable / Disable ---

    @Test
    void enable_callsAtomicSetEnabled() throws Exception {
        var schedule = makeCronSchedule("sched-1");
        when(scheduleStore.readSchedule("sched-1")).thenReturn(schedule);

        rest.enableSchedule("sched-1");

        verify(scheduleStore).setScheduleEnabled(eq("sched-1"), eq(true), any(Instant.class));
    }

    @Test
    void disable_callsAtomicSetEnabled() throws Exception {
        rest.disableSchedule("sched-1");

        verify(scheduleStore).setScheduleEnabled("sched-1", false, null);
    }

    // --- Helpers ---

    private static ScheduleConfiguration makeCronSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test");
        s.setTriggerType(TriggerType.CRON);
        s.setBotId("bot-1");
        s.setCronExpression("0 9 * * *");
        s.setMessage("Hello");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException e) {
            // Try superclass
            try {
                var field = target.getClass().getSuperclass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception ex) {
                throw new RuntimeException("Cannot set field " + fieldName, ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + fieldName, e);
        }
    }
}
