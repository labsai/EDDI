/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestScheduleStore}.
 */
class RestScheduleStoreTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SecurityIdentity identity;
    private RestScheduleStore rest;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        var pollerService = mock(SchedulePollerService.class);
        identity = mock(SecurityIdentity.class);

        rest = new RestScheduleStore();
        // Inject mocks (field injection in REST — we use reflection for tests)
        setField(rest, "scheduleStore", scheduleStore);
        setField(rest, "fireExecutor", fireExecutor);
        setField(rest, "pollerService", pollerService);
        setField(rest, "identity", identity);
        // Auth-enabled validator: isAdmin() is then driven by the mocked identity's
        // role.
        setField(rest, "ownershipValidator", new OwnershipValidator(true));
        setField(rest, "defaultTimeZone", "UTC");
        setField(rest, "minIntervalSeconds", 60L);
    }

    /** A HITL approval-timeout schedule as stored by ConversationService. */
    private static ScheduleConfiguration hitlSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("hitl-timeout-conv-" + id);
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setOneTimeAt(Instant.now().plusSeconds(3600).toString());
        s.setMetadata(Map.of("hitlType", "hitl_timeout", "policy", "AUTO_APPROVE",
                "surface", "regular", "conversationId", "conv-" + id));
        return s;
    }

    // --- Create ---

    @Test
    void create_cronSchedule_setsDefaults() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("new-id");

        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
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
        schedule.setAgentId("agent-1");
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
    void create_rejectsMissingAgentId() {
        var schedule = new ScheduleConfiguration();
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsCronWithoutMessage() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("0 9 * * *");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsHeartbeatWithoutInterval() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setName("Bad heartbeat");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsIntervalBelowMinimum() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setHeartbeatIntervalSeconds(30L); // below 60s minimum
        schedule.setName("Too fast");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("Invalid schedule"));
    }

    @Test
    void create_rejectsInvalidCron() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("not valid");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsInvalidTimezone() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
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
    void readAll_filtersByAgentId() throws Exception {
        when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of());

        rest.readAllSchedules("agent-1");

        verify(scheduleStore).readSchedulesByAgentId("agent-1");
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

    // --- Finding #5: HITL schedule bypass guards ---

    @Test
    void fireNow_hitlSchedule_refusedWithConflict_evenForAdmin() throws Exception {
        // An admin firing manually would STILL side-step the /resume audit path.
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.fireNow("h1");

        assertEquals(409, response.getStatus());
        // Must NOT invoke the fire executor's HITL fast-path.
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_hitlSchedule_refusedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false); // plain editor
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.fireNow("h1");

        assertEquals(409, response.getStatus());
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_regularSchedule_stillFires() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        var regular = makeCronSchedule("r1");
        when(scheduleStore.readSchedule("r1")).thenReturn(regular);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(new ai.labs.eddi.engine.schedule.model.ScheduleFireLog(
                        "log-1", "r1", "fire-1", null, Instant.now(), Instant.now(),
                        FireStatus.COMPLETED.name(), "n1", "conv-1", null, 1, 0.0));

        Response response = rest.fireNow("r1");

        assertEquals(200, response.getStatus());
        verify(fireExecutor).fire(any(), any(), anyInt());
    }

    @Test
    void deleteSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.deleteSchedule("h1");

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).deleteSchedule("h1");
    }

    @Test
    void deleteSchedule_hitl_allowedForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.deleteSchedule("h1");

        assertEquals(204, response.getStatus());
        verify(scheduleStore).deleteSchedule("h1");
    }

    @Test
    void disableSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.disableSchedule("h1");

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).setScheduleEnabled(eq("h1"), anyBoolean(), any());
    }

    @Test
    void updateSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        // Body omits the metadata marker — the guard must still detect HITL via the
        // STORED schedule.
        var body = makeCronSchedule("h1");
        Response response = rest.updateSchedule("h1", body);

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("h1"), any());
    }

    // --- G3: forging a HITL timeout schedule via create/update is denied for
    // EVERYONE (even admin). These schedules are minted internally only; a forged
    // one would let the poller force-resume/abort a victim's approval
    // unauthenticated.

    @Test
    void createSchedule_hitlBody_rejectedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);

        Response response = rest.createSchedule(hitlSchedule("v1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void createSchedule_hitlBody_rejectedForAdmin() throws Exception {
        // Even an admin cannot mint a HITL timeout schedule via REST.
        when(identity.hasRole("eddi-admin")).thenReturn(true);

        Response response = rest.createSchedule(hitlSchedule("v1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void updateSchedule_convertBodyToHitl_rejectedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        // Stored schedule is a plain (non-HITL) cron — the guard must catch the
        // hitl_timeout marker on the INCOMING body, closing the conversion path.
        when(scheduleStore.readSchedule("r1")).thenReturn(makeCronSchedule("r1"));

        Response response = rest.updateSchedule("r1", hitlSchedule("r1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("r1"), any());
    }

    @Test
    void updateSchedule_convertBodyToHitl_rejectedForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("r1")).thenReturn(makeCronSchedule("r1"));

        Response response = rest.updateSchedule("r1", hitlSchedule("r1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("r1"), any());
    }

    @Test
    void readAllSchedules_redactsHitlForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readAllSchedules(anyInt()))
                .thenReturn(List.of(makeCronSchedule("r1"), hitlSchedule("h1")));

        List<ScheduleConfiguration> result = rest.readAllSchedules(null);

        assertEquals(1, result.size());
        assertEquals("r1", result.get(0).getId());
    }

    @Test
    void readAllSchedules_showsHitlForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readAllSchedules(anyInt()))
                .thenReturn(List.of(makeCronSchedule("r1"), hitlSchedule("h1")));

        List<ScheduleConfiguration> result = rest.readAllSchedules(null);

        assertEquals(2, result.size());
    }

    private static ScheduleConfiguration makeCronSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
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
