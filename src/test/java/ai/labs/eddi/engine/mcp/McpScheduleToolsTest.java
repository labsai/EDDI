/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MCP schedule tools (create, list, read, fire, delete, retry) as adapters
 * over {@link IRestScheduleStore}: what each passes to the REST store, and how
 * each REST answer becomes a tool result. What REST itself decides is
 * {@code RestScheduleStoreTest}'s, and that both surfaces decide alike is
 * {@code McpScheduleParityTest}'s.
 */
class McpScheduleToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private IRestScheduleStore rest;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() {
        rest = mock(IRestScheduleStore.class);
        tools = new McpAdminTools(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), new JsonSerialization(mapper),
                mock(StrictConfigurationParser.class), rest, mock(SecurityIdentity.class), false, mock(AgentCascadeService.class));
    }

    @Nested
    @DisplayName("create_schedule")
    class Create {

        @Test
        @DisplayName("sends the arguments as they are and leaves every default to REST")
        void sendsTheArgumentsAsTheyAre() {
            when(rest.createSchedule(any())).thenReturn(Response.status(201).entity(created()).build());

            tools.createSchedule("agent-1", null, "0 9 * * MON-FRI", null, "Good morning", "Morning", null, null, null, null);

            ArgumentCaptor<ScheduleConfiguration> body = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(rest).createSchedule(body.capture());
            ScheduleConfiguration sent = body.getValue();
            assertEquals("agent-1", sent.getAgentId());
            assertEquals("Morning", sent.getName());
            assertEquals("0 9 * * MON-FRI", sent.getCronExpression());
            assertEquals("Good morning", sent.getMessage());
            // Unset is unset: REST's applyDefaults owns the time zone, strategy,
            // environment and userId defaults, and computes nextFire itself.
            assertNull(sent.getTimeZone());
            assertNull(sent.getConversationStrategy());
            assertNull(sent.getEnvironment());
            assertNull(sent.getUserId());
            assertNull(sent.getNextFire());
            assertNull(sent.getCreatedBy());
        }

        @Test
        @DisplayName("a named trigger type is sent; heartbeat arguments pass through")
        void heartbeat() {
            when(rest.createSchedule(any())).thenReturn(Response.status(201).entity(created()).build());

            tools.createSchedule("agent-1", "heartbeat", null, 300L, null, "Pulse", "Europe/Vienna", "persistent", "admin@example.com", "test");

            ArgumentCaptor<ScheduleConfiguration> body = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(rest).createSchedule(body.capture());
            ScheduleConfiguration sent = body.getValue();
            assertEquals(TriggerType.HEARTBEAT, sent.getTriggerType());
            assertEquals(300L, sent.getHeartbeatIntervalSeconds());
            assertEquals("Europe/Vienna", sent.getTimeZone());
            assertEquals("persistent", sent.getConversationStrategy());
            assertEquals("admin@example.com", sent.getUserId());
            assertEquals("test", sent.getEnvironment());
        }

        @Test
        @DisplayName("201: the stored schedule as the tool result")
        void created201() throws Exception {
            when(rest.createSchedule(any())).thenReturn(Response.status(201).entity(created()).build());

            JsonNode result = json(tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, "Hi", "Morning", null, null, null, null));

            assertEquals("schedule_created", result.path("action").asText());
            assertEquals("s-1", result.path("scheduleId").asText());
            assertEquals("Morning", result.path("name").asText());
            assertEquals("CRON", result.path("triggerType").asText());
            assertEquals("agent-1", result.path("agentId").asText());
            assertEquals("0 9 * * *", result.path("cronExpression").asText());
            assertEquals("At 09:00", result.path("description").asText());
            assertEquals("UTC", result.path("timeZone").asText());
            assertEquals("2026-11-08T09:00:00Z", result.path("nextFire").asText());
            assertEquals("new", result.path("conversationStrategy").asText());
            assertEquals("production", result.path("environment").asText());
            assertFalse(result.has("heartbeatIntervalSeconds"));
        }

        @Test
        @DisplayName("a REST refusal keeps its code and message")
        void refusal() throws Exception {
            when(rest.createSchedule(any())).thenReturn(Response.status(400)
                    .entity(Map.of("error", "invalid_schedule", "message", "conversationStrategy must be 'new' or 'persistent', got: x"))
                    .build());

            JsonNode result = json(tools.createSchedule("agent-1", null, "0 9 * * *", null, "Hi", "Morning", null, "x", null, null));

            assertEquals("invalid_schedule", result.path("errorCode").asText());
            assertEquals("conversationStrategy must be 'new' or 'persistent', got: x", result.path("error").asText());
        }

        @Test
        @DisplayName("the USE gate's refusal is FORBIDDEN")
        void forbidden() throws Exception {
            when(rest.createSchedule(any())).thenThrow(new ForbiddenException("Access denied: you do not have use access to this agent"));

            JsonNode result = json(tools.createSchedule("agent-1", null, "0 9 * * *", null, "Hi", "Morning", null, null, null, null));

            assertEquals("FORBIDDEN", result.path("errorCode").asText());
        }

        @Test
        @DisplayName("an unknown trigger type is refused before REST is asked")
        void unknownTriggerType() throws Exception {
            JsonNode result = json(tools.createSchedule("agent-1", "WEEKLY", "0 9 * * *", null, "Hi", "Morning", null, null, null, null));

            assertEquals("invalid_schedule", result.path("errorCode").asText());
            assertEquals("triggerType must be 'CRON' or 'HEARTBEAT', got: WEEKLY", result.path("error").asText());
            verifyNoInteractions(rest);
        }

        @Test
        @DisplayName("agentId and name are required")
        void requiredArguments() {
            assertTrue(tools.createSchedule(null, null, "0 9 * * *", null, "Hi", "Morning", null, null, null, null).contains("agentId is required"));
            assertTrue(tools.createSchedule("agent-1", null, "0 9 * * *", null, "Hi", " ", null, null, null, null).contains("name is required"));
            verifyNoInteractions(rest);
        }

        private ScheduleConfiguration created() {
            var s = new ScheduleConfiguration();
            s.setId("s-1");
            s.setName("Morning");
            s.setAgentId("agent-1");
            s.setTriggerType(TriggerType.CRON);
            s.setCronExpression("0 9 * * *");
            s.setCronDescription("At 09:00");
            s.setTimeZone("UTC");
            s.setNextFire(Instant.parse("2026-11-08T09:00:00Z"));
            s.setConversationStrategy("new");
            s.setEnvironment("production");
            return s;
        }
    }

    @Nested
    @DisplayName("list_schedules")
    class ListSchedules {

        private List<ScheduleConfiguration> pageOf(int n) {
            var page = new ArrayList<ScheduleConfiguration>();
            for (int i = 0; i < n; i++) {
                page.add(schedule("s-" + i));
            }
            return page;
        }

        @Test
        @DisplayName("asks REST for one row more than the first 100 of the agent's schedules and shapes each")
        void firstPage() throws Exception {
            when(rest.readAllSchedules("agent-1", 101, 0)).thenReturn(List.of(schedule("s-1")));

            JsonNode result = json(tools.listSchedules("agent-1", null, null));

            assertEquals(1, result.path("count").asInt());
            assertFalse(result.path("truncated").asBoolean());
            JsonNode item = result.path("schedules").get(0);
            assertEquals("s-1", item.path("scheduleId").asText());
            assertEquals("At 09:00", item.path("cronDescription").asText());
            assertEquals("PENDING", item.path("fireStatus").asText());
        }

        @Test
        @DisplayName("exactly a full page and nothing more is not truncated")
        void exactlyFullPage() throws Exception {
            when(rest.readAllSchedules(null, 101, 0)).thenReturn(pageOf(100));

            JsonNode result = json(tools.listSchedules(null, null, null));

            assertEquals(100, result.path("count").asInt());
            assertFalse(result.path("truncated").asBoolean());
            assertTrue(result.path("nextOffset").isMissingNode());
        }

        @Test
        @DisplayName("a full page plus one is truncated, trimmed to the page, and the rest is reachable by offset")
        void fullPagePlusOne() throws Exception {
            when(rest.readAllSchedules(null, 101, 0)).thenReturn(pageOf(101));
            when(rest.readAllSchedules(null, 101, 100)).thenReturn(List.of(schedule("s-last")));

            JsonNode first = json(tools.listSchedules(null, null, null));
            assertEquals(100, first.path("count").asInt());
            assertTrue(first.path("truncated").asBoolean());
            assertEquals(100, first.path("nextOffset").asInt());

            JsonNode second = json(tools.listSchedules(null, first.path("nextOffset").asInt(), null));
            assertEquals(1, second.path("count").asInt());
            assertEquals("s-last", second.path("schedules").get(0).path("scheduleId").asText());
            assertFalse(second.path("truncated").asBoolean());
        }

        @Test
        @DisplayName("limit and offset are clamped: too big, zero, negative")
        void clamping() throws Exception {
            when(rest.readAllSchedules(null, 501, 0)).thenReturn(List.of());
            when(rest.readAllSchedules(null, 2, 0)).thenReturn(List.of());

            tools.listSchedules(null, -5, 100000);
            tools.listSchedules(null, null, 0);
            tools.listSchedules(null, null, -3);

            verify(rest).readAllSchedules(null, 501, 0);
            verify(rest, times(2)).readAllSchedules(null, 2, 0);
        }
    }

    @Nested
    @DisplayName("read_schedule")
    class Read {

        @Test
        @DisplayName("the schedule and its last ten fires, both through REST")
        void scheduleAndFires() throws Exception {
            when(rest.readSchedule("s-1")).thenReturn(schedule("s-1"));
            when(rest.readFireLogs("s-1", 10)).thenReturn(List.of(fireLog("COMPLETED", null)));

            JsonNode result = json(tools.readSchedule("s-1"));

            assertEquals("s-1", result.path("scheduleId").asText());
            assertEquals("At 09:00", result.path("cronDescription").asText());
            assertEquals("conv-1", result.path("recentFires").get(0).path("conversationId").asText());
        }

        @Test
        @DisplayName("REST's not-found is the error")
        void notFound() throws Exception {
            when(rest.readSchedule("nope")).thenThrow(new NotFoundException("Schedule not found: nope"));

            JsonNode result = json(tools.readSchedule("nope"));

            assertTrue(result.path("error").asText().contains("Schedule not found: nope"), result.toString());
            verify(rest, never()).readFireLogs(anyString(), anyInt());
        }

        @Test
        @DisplayName("a blank id is refused before REST is asked")
        void blankId() {
            assertTrue(tools.readSchedule(" ").contains("scheduleId is required"));
            verifyNoInteractions(rest);
        }
    }

    @Nested
    @DisplayName("fire_schedule_now")
    class Fire {

        @Test
        @DisplayName("200: the fire log as the tool result")
        void fired() throws Exception {
            when(rest.readSchedule("s-1")).thenReturn(schedule("s-1"));
            when(rest.fireNow("s-1")).thenReturn(Response.ok(fireLog("COMPLETED", null)).build());

            JsonNode result = json(tools.fireScheduleNow("s-1"));

            assertEquals("schedule_fired", result.path("action").asText());
            assertEquals("s-1", result.path("scheduleId").asText());
            assertEquals("Schedule s-1", result.path("scheduleName").asText());
            assertEquals("COMPLETED", result.path("fireStatus").asText());
            assertEquals("conv-1", result.path("conversationId").asText());
            assertEquals("1500ms", result.path("duration").asText());
            assertFalse(result.has("error"));
        }

        @Test
        @DisplayName("a fire that failed is an outcome, not a refusal: an error field in an ordinary result")
        void fireFailed() throws Exception {
            when(rest.readSchedule("s-1")).thenReturn(schedule("s-1"));
            when(rest.fireNow("s-1")).thenReturn(Response.ok(fireLog("FAILED", "LLM unavailable")).build());

            String raw = tools.fireScheduleNow("s-1");

            assertFalse(McpErrorResultInterceptor.isErrorPayload(raw), raw);
            assertEquals("LLM unavailable", json(raw).path("error").asText());
        }

        @Test
        @DisplayName("REST's plain-text refusals keep their text: a claim refused, a fire past its bound")
        void textRefusals() throws Exception {
            when(rest.readSchedule("s-1")).thenReturn(schedule("s-1"));
            when(rest.fireNow("s-1")).thenReturn(Response.status(409).entity("Schedule s-1 is firing already.").build());
            JsonNode conflict = json(tools.fireScheduleNow("s-1"));
            assertEquals("CONFLICT", conflict.path("errorCode").asText());
            assertEquals("Schedule s-1 is firing already.", conflict.path("error").asText());

            when(rest.fireNow("s-1")).thenReturn(Response.status(504).entity("The fire did not finish within its bound.").build());
            JsonNode timeout = json(tools.fireScheduleNow("s-1"));
            assertEquals("TIMEOUT", timeout.path("errorCode").asText());
            assertEquals("The fire did not finish within its bound.", timeout.path("error").asText());
        }

        @Test
        @DisplayName("an EDIT gate's refusal is FORBIDDEN; a server failure is INTERNAL and not echoed")
        void thrown() throws Exception {
            when(rest.readSchedule("s-1")).thenReturn(schedule("s-1"));
            when(rest.fireNow("s-1")).thenThrow(new ForbiddenException("Access denied: you do not have edit access to this group"));
            assertEquals("FORBIDDEN", json(tools.fireScheduleNow("s-1")).path("errorCode").asText());

            doThrow(new InternalServerErrorException("mongo-host-7 refused")).when(rest).fireNow("s-1");
            JsonNode internal = json(tools.fireScheduleNow("s-1"));
            assertEquals("INTERNAL", internal.path("errorCode").asText());
            assertFalse(internal.path("error").asText().contains("mongo-host-7"), internal.toString());
        }

        @Test
        @DisplayName("a schedule the caller may not read is not fired")
        void unreadable() throws Exception {
            when(rest.readSchedule("s-1")).thenThrow(new ForbiddenException("Access denied: this schedule runs as another user"));

            assertEquals("FORBIDDEN", json(tools.fireScheduleNow("s-1")).path("errorCode").asText());
            verify(rest, never()).fireNow(anyString());
        }
    }

    @Nested
    @DisplayName("delete_schedule")
    class Delete {

        @Test
        @DisplayName("204: deleted")
        void deleted() throws Exception {
            when(rest.deleteSchedule("s-1")).thenReturn(Response.noContent().build());

            JsonNode result = json(tools.deleteSchedule("s-1"));

            assertEquals("schedule_deleted", result.path("action").asText());
            assertEquals("s-1", result.path("scheduleId").asText());
        }

        @Test
        @DisplayName("the system schedule's refusal keeps REST's SYSTEM_SCHEDULE code")
        void systemSchedule() throws Exception {
            when(rest.deleteSchedule("sys")).thenReturn(Response.status(409)
                    .entity(Map.of("error", "SYSTEM_SCHEDULE", "message", "The alerts-maintenance schedule is managed by the server"))
                    .build());

            JsonNode result = json(tools.deleteSchedule("sys"));

            assertEquals("SYSTEM_SCHEDULE", result.path("errorCode").asText());
            assertEquals("The alerts-maintenance schedule is managed by the server", result.path("error").asText());
        }

        @Test
        @DisplayName("an ownership refusal returned as a 403 text body keeps its text")
        void ownerRefusal() throws Exception {
            when(rest.deleteSchedule("s-1")).thenReturn(Response.status(403).entity("A schedule may only run as yourself.").build());

            JsonNode result = json(tools.deleteSchedule("s-1"));

            assertEquals("FORBIDDEN", result.path("errorCode").asText());
            assertEquals("A schedule may only run as yourself.", result.path("error").asText());
        }
    }

    @Nested
    @DisplayName("retry_failed_schedule")
    class Retry {

        @Test
        @DisplayName("200: requeued")
        void requeued() throws Exception {
            when(rest.retryDeadLetter("s-1")).thenReturn(Response.ok().build());

            JsonNode result = json(tools.retryFailedSchedule("s-1"));

            assertEquals("schedule_requeued", result.path("action").asText());
            assertEquals("PENDING", result.path("status").asText());
        }

        @Test
        @DisplayName("a schedule that is not dead-lettered is REST's not-found")
        void notDeadLettered() throws Exception {
            when(rest.retryDeadLetter("s-1")).thenThrow(new NotFoundException("Schedule not found or not dead-lettered: s-1"));

            JsonNode result = json(tools.retryFailedSchedule("s-1"));

            assertTrue(result.path("error").asText().contains("not dead-lettered: s-1"), result.toString());
        }

        @Test
        @DisplayName("a blank id is refused before REST is asked")
        void blankId() {
            assertTrue(tools.retryFailedSchedule(null).contains("scheduleId is required"));
            assertTrue(tools.deleteSchedule("").contains("scheduleId is required"));
            assertTrue(tools.fireScheduleNow(null).contains("scheduleId is required"));
            verifyNoInteractions(rest);
        }
    }

    private static ScheduleConfiguration schedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Schedule " + id);
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 9 * * *");
        s.setCronDescription("At 09:00");
        s.setMessage("Hello");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    private static ScheduleFireLog fireLog(String status, String error) {
        Instant started = Instant.parse("2026-11-07T09:00:00Z");
        return new ScheduleFireLog("log-1", "s-1", "fire-1", started, started, started.plusMillis(1500), status, "instance-1", "conv-1",
                error, 1, 0.0);
    }

    private JsonNode json(String result) throws Exception {
        return mapper.readTree(result);
    }
}
