/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional unit tests for {@link RestScheduleStore} — covering error paths,
 * fire-now, fire logs, dead letters, dismissDeadLetter, and validation edge
 * cases. Existing tests in {@link RestScheduleStoreTest} cover
 * create/read/enable/disable basics.
 */
class RestScheduleStoreExpandedTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService pollerService;
    private ai.labs.eddi.engine.security.OwnershipValidator ownershipValidator;
    private RestScheduleStore sut;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        pollerService = mock(SchedulePollerService.class);
        ownershipValidator = mock(ai.labs.eddi.engine.security.OwnershipValidator.class);
        // admin by default: the HITL redaction/guards are tested in
        // RestScheduleStoreTest — these tests exercise the general surface
        doReturn(true).when(ownershipValidator).isAdmin(any());

        sut = new RestScheduleStore();
        setField(sut, "scheduleStore", scheduleStore);
        setField(sut, "fireExecutor", fireExecutor);
        setField(sut, "pollerService", pollerService);
        setField(sut, "identity", mock(io.quarkus.security.identity.SecurityIdentity.class));
        setField(sut, "ownershipValidator", ownershipValidator);
        setField(sut, "defaultTimeZone", "UTC");
        setField(sut, "minIntervalSeconds", 60L);
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

    private static ScheduleConfiguration makeHeartbeatSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("HB Test");
        s.setTriggerType(TriggerType.HEARTBEAT);
        s.setAgentId("agent-1");
        s.setHeartbeatIntervalSeconds(300L);
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    /** A HITL approval-timeout schedule as stored by ConversationService. */
    private static ScheduleConfiguration hitlSchedule(String id) {
        var s = makeCronSchedule(id);
        s.setName("hitl-timeout-conv-" + id);
        s.setMetadata(java.util.Map.of("hitlType", "hitl_timeout", "policy", "AUTO_REJECT",
                "surface", "regular", "conversationId", "conv-" + id));
        return s;
    }

    // ─── readSchedule ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readSchedule")
    class ReadSchedule {

        @Test
        @DisplayName("should return schedule with enriched cron description")
        void success() throws Exception {
            var schedule = makeCronSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);

            ScheduleConfiguration result = sut.readSchedule("s1");

            assertNotNull(result);
            assertEquals("s1", result.getId());
            assertNotNull(result.getCronDescription());
        }

        @Test
        @DisplayName("should enrich heartbeat description")
        void heartbeatDescription() throws Exception {
            var schedule = makeHeartbeatSchedule("s2");
            when(scheduleStore.readSchedule("s2")).thenReturn(schedule);

            ScheduleConfiguration result = sut.readSchedule("s2");

            assertNotNull(result);
            assertNotNull(result.getCronDescription());
            assertTrue(result.getCronDescription().contains("minute"));
        }

        @Test
        @DisplayName("should throw NotFoundException when schedule does not exist")
        void notFound() throws Exception {
            when(scheduleStore.readSchedule("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(NotFoundException.class, () -> sut.readSchedule("missing"));
        }

        @Test
        @DisplayName("should throw InternalServerError on general exception")
        void generalError() throws Exception {
            when(scheduleStore.readSchedule("s1"))
                    .thenThrow(new RuntimeException("db error"));

            assertThrows(InternalServerErrorException.class, () -> sut.readSchedule("s1"));
        }
    }

    // ─── readAllSchedules ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("readAllSchedules")
    class ReadAllSchedules {

        @Test
        @DisplayName("should throw InternalServerError when store fails")
        void storeError() throws Exception {
            when(scheduleStore.readAllSchedules(500))
                    .thenThrow(new RuntimeException("db error"));

            assertThrows(InternalServerErrorException.class, () -> sut.readAllSchedules(null));
        }

        @Test
        @DisplayName("should handle blank agentId as null (read all)")
        void blankAgentId() throws Exception {
            when(scheduleStore.readAllSchedules(500)).thenReturn(List.of());

            List<ScheduleConfiguration> result = sut.readAllSchedules("  ");

            verify(scheduleStore).readAllSchedules(500);
            verify(scheduleStore, never()).readSchedulesByAgentId(anyString());
        }
    }

    // ─── updateSchedule ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateSchedule")
    class UpdateSchedule {

        @Test
        @DisplayName("should update schedule successfully")
        void success() throws Exception {
            var schedule = makeCronSchedule("s1");

            Response response = sut.updateSchedule("s1", schedule);

            assertEquals(200, response.getStatus());
            verify(scheduleStore).updateSchedule(eq("s1"), any());
        }

        @Test
        @DisplayName("should throw NotFoundException when schedule does not exist")
        void notFound() throws Exception {
            var schedule = makeCronSchedule("s1");
            doThrow(new IResourceStore.ResourceNotFoundException("nope"))
                    .when(scheduleStore).updateSchedule(eq("s1"), any());

            assertThrows(NotFoundException.class, () -> sut.updateSchedule("s1", schedule));
        }

        @Test
        @DisplayName("should return 400 for invalid schedule")
        void invalidSchedule() {
            var schedule = new ScheduleConfiguration(); // missing agentId

            Response response = sut.updateSchedule("s1", schedule);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should throw InternalServerError on general exception")
        void generalError() throws Exception {
            var schedule = makeCronSchedule("s1");
            doThrow(new RuntimeException("db error"))
                    .when(scheduleStore).updateSchedule(eq("s1"), any());

            assertThrows(InternalServerErrorException.class,
                    () -> sut.updateSchedule("s1", schedule));
        }
    }

    // ─── deleteSchedule ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("should return 204 on successful delete")
        void success() throws Exception {
            Response response = sut.deleteSchedule("s1");

            assertEquals(204, response.getStatus());
            verify(scheduleStore).deleteSchedule("s1");
        }

        @Test
        @DisplayName("should throw InternalServerError when delete fails")
        void error() throws Exception {
            doThrow(new RuntimeException("db error"))
                    .when(scheduleStore).deleteSchedule("s1");

            assertThrows(InternalServerErrorException.class,
                    () -> sut.deleteSchedule("s1"));
        }
    }

    // ─── fireNow ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fireNow")
    class FireNow {

        @Test
        @DisplayName("should fire schedule and return fire log")
        void success() throws Exception {
            var schedule = makeCronSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);
            when(pollerService.getInstanceId()).thenReturn("instance-1");
            var fireLog = mock(ScheduleFireLog.class);
            when(fireExecutor.fire(eq(schedule), eq("instance-1"), eq(1))).thenReturn(fireLog);

            Response response = sut.fireNow("s1");

            assertEquals(200, response.getStatus());
            assertSame(fireLog, response.getEntity());
        }

        @Test
        @DisplayName("should throw NotFoundException when schedule missing")
        void notFound() throws Exception {
            when(scheduleStore.readSchedule("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(NotFoundException.class, () -> sut.fireNow("missing"));
        }

        @Test
        @DisplayName("should throw InternalServerError when firing fails")
        void fireError() throws Exception {
            var schedule = makeCronSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);
            when(fireExecutor.fire(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("fire failed"));

            assertThrows(InternalServerErrorException.class, () -> sut.fireNow("s1"));
        }
    }

    // ─── readFireLogs ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFireLogs")
    class ReadFireLogs {

        @Test
        @DisplayName("should return fire logs for schedule")
        void success() throws Exception {
            when(scheduleStore.readFireLogs("s1", 10))
                    .thenReturn(List.of(mock(ScheduleFireLog.class)));

            List<ScheduleFireLog> result = sut.readFireLogs("s1", 10);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should throw InternalServerError when reading fails")
        void error() throws Exception {
            when(scheduleStore.readFireLogs("s1", 10))
                    .thenThrow(new RuntimeException("db error"));

            assertThrows(InternalServerErrorException.class,
                    () -> sut.readFireLogs("s1", 10));
        }
    }

    // ─── readFailedFires ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFailedFires")
    class ReadFailedFires {

        @Test
        @DisplayName("should return failed fire logs")
        void success() throws Exception {
            when(scheduleStore.readFailedFireLogs(50))
                    .thenReturn(List.of(mock(ScheduleFireLog.class)));

            List<ScheduleFireLog> result = sut.readFailedFires(50);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should throw InternalServerError when reading fails")
        void error() throws Exception {
            when(scheduleStore.readFailedFireLogs(50))
                    .thenThrow(new RuntimeException("db error"));

            assertThrows(InternalServerErrorException.class,
                    () -> sut.readFailedFires(50));
        }
    }

    // ─── retryDeadLetter ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryDeadLetter")
    class RetryDeadLetter {

        @Test
        @DisplayName("should requeue dead-lettered schedule")
        void success() throws Exception {
            Response response = sut.retryDeadLetter("s1");

            assertEquals(200, response.getStatus());
            verify(scheduleStore).requeueDeadLetter("s1");
        }

        @Test
        @DisplayName("should throw NotFoundException when not dead-lettered")
        void notFound() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("not found"))
                    .when(scheduleStore).requeueDeadLetter("s1");

            assertThrows(NotFoundException.class, () -> sut.retryDeadLetter("s1"));
        }

        @Test
        @DisplayName("should throw InternalServerError on general failure")
        void error() throws Exception {
            doThrow(new RuntimeException("db error"))
                    .when(scheduleStore).requeueDeadLetter("s1");

            assertThrows(InternalServerErrorException.class,
                    () -> sut.retryDeadLetter("s1"));
        }

        @Test
        @DisplayName("should 403 for a non-admin on a HITL timeout schedule and not requeue it")
        void nonAdminHitlForbidden() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(any());
            when(scheduleStore.readSchedule("s1")).thenReturn(hitlSchedule("s1"));

            Response response = sut.retryDeadLetter("s1");

            assertEquals(403, response.getStatus());
            verify(scheduleStore, never()).requeueDeadLetter(anyString());
        }
    }

    // ─── dismissDeadLetter ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("dismissDeadLetter")
    class DismissDeadLetter {

        @Test
        @DisplayName("should mark completed with recomputed nextFire for cron")
        void dismissCron() throws Exception {
            var schedule = makeCronSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);

            Response response = sut.dismissDeadLetter("s1");

            assertEquals(200, response.getStatus());
            verify(scheduleStore).markCompleted(eq("s1"), any(Instant.class));
        }

        @Test
        @DisplayName("should mark completed with recomputed nextFire for heartbeat")
        void dismissHeartbeat() throws Exception {
            var schedule = makeHeartbeatSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);

            Response response = sut.dismissDeadLetter("s1");

            assertEquals(200, response.getStatus());
            verify(scheduleStore).markCompleted(eq("s1"), any(Instant.class));
        }

        @Test
        @DisplayName("should throw NotFoundException when schedule missing")
        void notFound() throws Exception {
            when(scheduleStore.readSchedule("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(NotFoundException.class, () -> sut.dismissDeadLetter("missing"));
        }

        @Test
        @DisplayName("should throw InternalServerError on general failure")
        void error() throws Exception {
            var schedule = makeCronSchedule("s1");
            when(scheduleStore.readSchedule("s1")).thenReturn(schedule);
            doThrow(new RuntimeException("db error"))
                    .when(scheduleStore).markCompleted(eq("s1"), any());

            assertThrows(InternalServerErrorException.class,
                    () -> sut.dismissDeadLetter("s1"));
        }

        @Test
        @DisplayName("should 403 for a non-admin on a HITL timeout schedule and not disarm it")
        void nonAdminHitlForbidden() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(any());
            when(scheduleStore.readSchedule("s1")).thenReturn(hitlSchedule("s1"));

            Response response = sut.dismissDeadLetter("s1");

            assertEquals(403, response.getStatus());
            verify(scheduleStore, never()).markCompleted(anyString(), any());
        }
    }

    // ─── enable/disable error paths ────────────────────────────────────────────

    @Nested
    @DisplayName("enable/disable error paths")
    class EnableDisableErrors {

        @Test
        @DisplayName("enable should throw NotFoundException when schedule missing")
        void enableNotFound() throws Exception {
            when(scheduleStore.readSchedule("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(NotFoundException.class, () -> sut.enableSchedule("missing"));
        }

        @Test
        @DisplayName("disable should throw InternalServerError on general failure")
        void disableError() throws Exception {
            doThrow(new RuntimeException("db error"))
                    .when(scheduleStore).setScheduleEnabled(eq("s1"), eq(false), isNull());

            assertThrows(InternalServerErrorException.class,
                    () -> sut.disableSchedule("s1"));
        }
    }

    // ─── Validation edge cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Validation edge cases")
    class ValidationEdgeCases {

        @Test
        @DisplayName("should reject both cronExpression and oneTimeAt")
        void cronAndOneTime() {
            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("0 9 * * *");
            s.setOneTimeAt(Instant.now().plusSeconds(3600).toString());
            s.setMessage("hello");

            Response response = sut.createSchedule(s);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should accept oneTimeAt schedule")
        void oneTimeAt() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("ot-id");

            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setOneTimeAt(Instant.now().plusSeconds(3600).toString());
            s.setMessage("once");

            Response response = sut.createSchedule(s);

            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("should reject invalid conversationStrategy")
        void invalidStrategy() {
            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("0 9 * * *");
            s.setMessage("hello");
            s.setConversationStrategy("invalid");

            Response response = sut.createSchedule(s);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should accept conversationStrategy=new")
        void validStrategyNew() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("id1");

            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("0 9 * * *");
            s.setMessage("hello");
            s.setConversationStrategy("new");

            Response response = sut.createSchedule(s);

            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("should accept conversationStrategy=persistent")
        void validStrategyPersistent() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("id1");

            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("0 9 * * *");
            s.setMessage("hello");
            s.setConversationStrategy("persistent");

            Response response = sut.createSchedule(s);

            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("should reject cron with sub-minute frequency")
        void cronTooFrequent() throws Exception {
            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("* * * * *"); // every minute — interval=60s
            s.setMessage("spam");

            // minIntervalSeconds is set to 60, * * * * * has a 60s interval
            // This should pass since 60 >= 60
            when(scheduleStore.createSchedule(any())).thenReturn("id1");
            Response response = sut.createSchedule(s);
            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("should reject CRON without any time expression")
        void cronNoExpression() {
            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setTriggerType(TriggerType.CRON);
            s.setMessage("hello");
            // No cron and no oneTimeAt

            Response response = sut.createSchedule(s);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("should create InternalServerError when createSchedule throws")
        void createException() throws Exception {
            when(scheduleStore.createSchedule(any()))
                    .thenThrow(new RuntimeException("db error"));

            var s = new ScheduleConfiguration();
            s.setAgentId("agent-1");
            s.setCronExpression("0 9 * * *");
            s.setMessage("hello");

            assertThrows(InternalServerErrorException.class,
                    () -> sut.createSchedule(s));
        }
    }

    // ─── describeHeartbeat formatting ──────────────────────────────────────────

    @Nested
    @DisplayName("Heartbeat description formatting")
    class HeartbeatDescription {

        @Test
        @DisplayName("should describe seconds")
        void seconds() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(30L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertTrue(result.getCronDescription().contains("30 seconds"));
        }

        @Test
        @DisplayName("should describe single minute")
        void oneMinute() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(60L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertEquals("Every minute", result.getCronDescription());
        }

        @Test
        @DisplayName("should describe multiple minutes")
        void minutes() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(300L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertTrue(result.getCronDescription().contains("5 minutes"));
        }

        @Test
        @DisplayName("should describe single hour")
        void oneHour() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(3600L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertEquals("Every hour", result.getCronDescription());
        }

        @Test
        @DisplayName("should describe multiple hours")
        void hours() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(7200L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertTrue(result.getCronDescription().contains("2 hours"));
        }

        @Test
        @DisplayName("should describe single day")
        void oneDay() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(86400L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertEquals("Every day", result.getCronDescription());
        }

        @Test
        @DisplayName("should describe multiple days")
        void days() throws Exception {
            var s = makeHeartbeatSchedule("s1");
            s.setHeartbeatIntervalSeconds(172800L);
            when(scheduleStore.readSchedule("s1")).thenReturn(s);

            var result = sut.readSchedule("s1");

            assertTrue(result.getCronDescription().contains("2 days"));
        }
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException e) {
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
