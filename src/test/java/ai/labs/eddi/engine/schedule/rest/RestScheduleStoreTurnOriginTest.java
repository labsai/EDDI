/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.security.TurnOriginTestSupport;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The schedule routes of the email escalation guard (email plan v3 §5.3): a
 * tainted turn that creates, changes, fires or retries a schedule through
 * EDDI's own API — an httpcall to {@code /schedulestore/schedules}, MCP
 * {@code create_schedule}, {@code fire_schedule_now} or
 * {@code retry_failed_schedule} — leaves a schedule that fires tainted, never
 * an untainted one; and nothing a request says takes a stored taint away.
 */
@DisplayName("RestScheduleStore — the email origin taint on the schedules a tainted turn touches")
class RestScheduleStoreTurnOriginTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService pollerService;
    private RestScheduleStore rest;

    @BeforeEach
    void setUp() throws Exception {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        pollerService = mock(SchedulePollerService.class);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(pollerService.claimForManualFire(any())).thenReturn(true);
        ResourceAccessGuard guard = mock(ResourceAccessGuard.class);
        lenient().when(guard.seesEverything()).thenReturn(true);
        lenient().when(guard.hasAccess(any(), any())).thenReturn(true);

        rest = new RestScheduleStore();
        set("scheduleStore", scheduleStore);
        set("fireExecutor", fireExecutor);
        set("pollerService", pollerService);
        set("identity", identity);
        set("ownershipValidator", new OwnershipValidator(true));
        set("resourceAccessGuard", guard);
        set("defaultTimeZone", "UTC");
        set("minIntervalSeconds", 60L);
    }

    @Test
    @DisplayName("a schedule created for a tainted request is stored tainted, next to its own metadata")
    void createFromTaintedRequestIsTainted() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("s1");
        ScheduleConfiguration body = schedule("ignored");
        body.setMetadata(new HashMap<>(Map.of("note", "kept")));

        Response response;
        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            response = rest.createSchedule(body);
        }

        assertEquals(201, response.getStatus());
        ArgumentCaptor<ScheduleConfiguration> stored = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(stored.capture());
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromMetadata(stored.getValue().getMetadata()));
        assertEquals("kept", stored.getValue().getMetadata().get("note"));
    }

    @Test
    @DisplayName("a schedule created for an untainted request carries no taint")
    void createFromUntaintedRequestIsNotTainted() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("s1");

        rest.createSchedule(schedule("ignored"));

        ArgumentCaptor<ScheduleConfiguration> stored = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(stored.capture());
        assertNull(TurnOrigin.fromMetadata(stored.getValue().getMetadata()));
    }

    @Test
    @DisplayName("an edit from a tainted request taints the schedule — rewriting its message would otherwise be an untainted start")
    void updateFromTaintedRequestTaints() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.updateSchedule("s1", schedule("s1")).getStatus());
        }

        assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromMetadata(updated("s1").getMetadata()));
    }

    @Test
    @DisplayName("an untainted edit whose body sends metadata without the taint keeps the stored taint: it is sticky")
    void updateKeepsStoredTaint() throws Exception {
        ScheduleConfiguration stored = schedule("s1");
        stored.setMetadata(new HashMap<>(Map.of(TurnOrigin.CONTEXT_KEY, TurnOrigin.EMAIL)));
        when(scheduleStore.readSchedule("s1")).thenReturn(stored);
        // A body that names metadata replaces it (an absent one is carried over), so
        // this is the edit that would drop the taint.
        ScheduleConfiguration body = schedule("s1");
        body.setMetadata(new HashMap<>(Map.of("note", "rewritten")));

        assertEquals(200, rest.updateSchedule("s1", body).getStatus());

        assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromMetadata(updated("s1").getMetadata()));
        assertEquals("rewritten", updated("s1").getMetadata().get("note"));
    }

    @Test
    @DisplayName("a manual fire for a tainted request runs tainted, on the thread the fire runs on")
    void fireNowFromTaintedRequestRunsTainted() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));
        var duringFire = new AtomicReference<String>("not fired");
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            duringFire.set(TurnOrigin.current());
            return new ScheduleFireLog("log-1", "s1", "fire-1", null, Instant.now(), Instant.now(), FireStatus.COMPLETED.name(), "n1",
                    "conv-1", null, 1, 0.0);
        });

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.fireNow("s1").getStatus());
        }

        assertEquals(TurnOrigin.EMAIL, duringFire.get());
    }

    @Test
    @DisplayName("a manual fire for an untainted request runs untainted")
    void fireNowFromUntaintedRequestRunsUntainted() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));
        var duringFire = new AtomicReference<String>("not fired");
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            duringFire.set(TurnOrigin.current());
            return null;
        });

        rest.fireNow("s1");

        assertNull(duringFire.get());
    }

    @Test
    @DisplayName("a dead-letter retry for a tainted request taints the schedule before the poller may fire it")
    void retryFromTaintedRequestTaintsFirst() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.retryDeadLetter("s1").getStatus());
        }

        // The origin alone: writing the copy read back would revert a concurrent edit.
        InOrder order = inOrder(scheduleStore);
        order.verify(scheduleStore).setTurnOrigin("s1", TurnOrigin.EMAIL);
        order.verify(scheduleStore).requeueDeadLetter("s1");
        verify(scheduleStore, never()).updateSchedule(any(), any());
    }

    @Test
    @DisplayName("an untainted retry writes nothing but the requeue")
    void retryFromUntaintedRequestOnlyRequeues() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));

        assertEquals(200, rest.retryDeadLetter("s1").getStatus());

        verify(scheduleStore, never()).updateSchedule(any(), any());
        verify(scheduleStore, never()).setTurnOrigin(any(), any());
        verify(scheduleStore).requeueDeadLetter("s1");
    }

    @Test
    @DisplayName("an enable for a tainted request taints the schedule before it is switched on")
    void enableFromTaintedRequestTaintsFirst() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.enableSchedule("s1").getStatus());
        }

        // The origin alone: writing the copy read back would revert a concurrent edit.
        InOrder order = inOrder(scheduleStore);
        order.verify(scheduleStore).setTurnOrigin("s1", TurnOrigin.EMAIL);
        order.verify(scheduleStore).setScheduleEnabled(eq("s1"), eq(true), any());
        verify(scheduleStore, never()).updateSchedule(any(), any());
    }

    @Test
    @DisplayName("an untainted enable, and any disable, write nothing but the switch")
    void untaintedEnableOnlySwitches() throws Exception {
        when(scheduleStore.readSchedule("s1")).thenReturn(schedule("s1"));

        assertEquals(200, rest.enableSchedule("s1").getStatus());
        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.disableSchedule("s1").getStatus());
        }

        verify(scheduleStore, never()).updateSchedule(any(), any());
        verify(scheduleStore, never()).setTurnOrigin(any(), any());
        verify(scheduleStore).setScheduleEnabled(eq("s1"), eq(true), any());
        verify(scheduleStore).setScheduleEnabled(eq("s1"), eq(false), any());
    }

    @Test
    @DisplayName("a dead-letter dismissal for a tainted request taints the schedule before it is re-armed")
    void dismissFromTaintedRequestTaintsFirst() throws Exception {
        ScheduleConfiguration dead = schedule("s1");
        dead.setFireStatus(FireStatus.DEAD_LETTERED);
        when(scheduleStore.readSchedule("s1")).thenReturn(dead);

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals(200, rest.dismissDeadLetter("s1").getStatus());
        }

        // The origin alone: writing the copy read back would revert a concurrent edit.
        InOrder order = inOrder(scheduleStore);
        order.verify(scheduleStore).setTurnOrigin("s1", TurnOrigin.EMAIL);
        order.verify(scheduleStore).dismissDeadLetter(eq("s1"), any());
        verify(scheduleStore, never()).updateSchedule(any(), any());
    }

    private ScheduleConfiguration updated(String id) throws Exception {
        ArgumentCaptor<ScheduleConfiguration> captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).updateSchedule(eq(id), captor.capture());
        return captor.getValue();
    }

    private static ScheduleConfiguration schedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Check-in");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 9 * * *");
        s.setMessage("Hello");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    private void set(String name, Object value) throws Exception {
        Field field = RestScheduleStore.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(rest, value);
    }
}
