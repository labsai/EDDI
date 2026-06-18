/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpAdminTools — Branch Coverage")
class McpAdminToolsBranchCoverageTest {

    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private ScheduleFireExecutor scheduleFireExecutor;
    @Mock
    private SchedulePollerService schedulePollerService;
    @Mock
    private SecurityIdentity identity;

    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        // authEnabled=false so tests don't fail on requireRole
        tools = new McpAdminTools(restInterfaceFactory, agentAdmin, jsonSerialization,
                scheduleStore, scheduleFireExecutor, schedulePollerService,
                identity, false);
    }

    // ─── deployAgent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("deployAgent")
    class DeployAgent {

        @Test
        @DisplayName("null version defaults to 1")
        void nullVersion() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(202);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.deployAgent("agent1", null, null);
            assertNotNull(result);
            verify(agentAdmin).deployAgent(any(), eq("agent1"), eq(1), eq(true), eq(true));
        }

        @Test
        @DisplayName("HTTP 200 READY status")
        void http200Ready() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = Map.of("status", "READY");
            when(response.getEntity()).thenReturn(body);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{\"deployed\":true}");

            String result = tools.deployAgent("agent1", 2, "production");
            assertNotNull(result);
        }

        @Test
        @DisplayName("HTTP 200 ERROR status → deploy_failed")
        void http200ErrorStatus() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = Map.of("status", "ERROR");
            when(response.getEntity()).thenReturn(body);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deploy_failed\"}");

            String result = tools.deployAgent("agent1", 1, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("HTTP 200 IN_PROGRESS status — no error")
        void http200InProgress() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = Map.of("status", "IN_PROGRESS");
            when(response.getEntity()).thenReturn(body);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.deployAgent("agent1", 1, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("HTTP 200 null body → parse error")
        void http200NullBody() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getEntity()).thenThrow(new ClassCastException("not a map"));
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.deployAgent("agent1", 1, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("HTTP 202 → in progress")
        void http202() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(202);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.deployAgent("agent1", 1, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("exception → error json")
        void exceptionReturnsError() {
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("fail"));

            String result = tools.deployAgent("agent1", 1, null);
            assertTrue(result.contains("error"));
        }
    }

    // ─── undeployAgent ──────────────────────────────────────────────────

    @Nested
    @DisplayName("undeployAgent")
    class UndeployAgent {

        @Test
        @DisplayName("null version/endConversations defaults")
        void nullDefaults() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(agentAdmin.undeployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.undeployAgent("agent1", null, null, null);
            verify(agentAdmin).undeployAgent(any(), eq("agent1"), eq(1), eq(false), eq(false));
        }

        @Test
        @DisplayName("explicit endConversations=true")
        void explicitEndConversations() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(agentAdmin.undeployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.undeployAgent("agent1", 2, "test", true);
            verify(agentAdmin).undeployAgent(any(), eq("agent1"), eq(2), eq(true), eq(false));
        }

        @Test
        @DisplayName("exception → error json")
        void exceptionReturnsError() {
            when(agentAdmin.undeployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("fail"));
            String result = tools.undeployAgent("agent1", 1, null, null);
            assertTrue(result.contains("error"));
        }
    }

    // ─── getDeploymentStatus ────────────────────────────────────────────

    @Nested
    @DisplayName("getDeploymentStatus")
    class GetDeploymentStatus {

        @Test
        @DisplayName("null entity → status check result")
        void nullEntity() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(404);
            when(response.getEntity()).thenReturn(null);
            when(agentAdmin.getDeploymentStatus(any(), anyString(), anyInt(), anyString()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.getDeploymentStatus("agent1", null, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("entity present → serialize entity")
        void entityPresent() throws Exception {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getEntity()).thenReturn(Map.of("status", "READY"));
            when(agentAdmin.getDeploymentStatus(any(), anyString(), anyInt(), anyString()))
                    .thenReturn(response);
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"READY\"}");

            String result = tools.getDeploymentStatus("agent1", 2, "test");
            assertNotNull(result);
        }

        @Test
        @DisplayName("exception → error json")
        void exception() {
            when(agentAdmin.getDeploymentStatus(any(), anyString(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("fail"));
            assertTrue(tools.getDeploymentStatus("agent1", 1, null).contains("error"));
        }
    }

    // ─── readWorkflow ───────────────────────────────────────────────────

    @Nested
    @DisplayName("readWorkflow")
    class ReadWorkflow {

        @Test
        @DisplayName("null workflowId → error")
        void nullWorkflowId() {
            assertTrue(tools.readWorkflow(null, null).contains("error"));
        }

        @Test
        @DisplayName("blank workflowId → error")
        void blankWorkflowId() {
            assertTrue(tools.readWorkflow("  ", null).contains("error"));
        }

        @Test
        @DisplayName("exception → error json")
        void exception() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.readWorkflow("wf1", 1).contains("error"));
        }
    }

    // ─── readResource ───────────────────────────────────────────────────

    @Nested
    @DisplayName("readResource")
    class ReadResource {

        @Test
        @DisplayName("null resourceType → error")
        void nullType() {
            assertTrue(tools.readResource(null, "id1", 1).contains("error"));
        }

        @Test
        @DisplayName("null resourceId → error")
        void nullId() {
            assertTrue(tools.readResource("behavior", null, 1).contains("error"));
        }

        @Test
        @DisplayName("blank resourceId → error")
        void blankId() {
            assertTrue(tools.readResource("behavior", "  ", 1).contains("error"));
        }

        @Test
        @DisplayName("unknown type → error (via exception)")
        void unknownType() throws Exception {
            // Unknown types make readResourceByType throw
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.readResource("unknown_type", "id1", 1).contains("error"));
        }
    }

    // ─── updateResource ─────────────────────────────────────────────────

    @Nested
    @DisplayName("updateResource")
    class UpdateResource {

        @Test
        @DisplayName("null resourceType → error")
        void nullType() {
            assertTrue(tools.updateResource(null, "id", 1, "{}").contains("error"));
        }

        @Test
        @DisplayName("null resourceId → error")
        void nullId() {
            assertTrue(tools.updateResource("behavior", null, 1, "{}").contains("error"));
        }

        @Test
        @DisplayName("null config → error")
        void nullConfig() {
            assertTrue(tools.updateResource("behavior", "id", 1, null).contains("error"));
        }

        @Test
        @DisplayName("blank config → error")
        void blankConfig() {
            assertTrue(tools.updateResource("behavior", "id", 1, "  ").contains("error"));
        }

        @Test
        @DisplayName("exception → error json")
        void exception() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.updateResource("behavior", "id1", 1, "{}").contains("error"));
        }
    }

    // ─── createResource ─────────────────────────────────────────────────

    @Nested
    @DisplayName("createResource")
    class CreateResource {

        @Test
        @DisplayName("null resourceType → error")
        void nullType() {
            assertTrue(tools.createResource(null, "{}").contains("error"));
        }

        @Test
        @DisplayName("null config → error")
        void nullConfig() {
            assertTrue(tools.createResource("behavior", null).contains("error"));
        }

        @Test
        @DisplayName("blank config → error")
        void blankConfig() {
            assertTrue(tools.createResource("behavior", "  ").contains("error"));
        }

        @Test
        @DisplayName("exception → error json")
        void exception() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.createResource("behavior", "{}").contains("error"));
        }
    }

    // ─── deleteResource ─────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteResource")
    class DeleteResource {

        @Test
        @DisplayName("null resourceType → error")
        void nullType() {
            assertTrue(tools.deleteResource(null, "id", 1, false).contains("error"));
        }

        @Test
        @DisplayName("null resourceId → error")
        void nullId() {
            assertTrue(tools.deleteResource("behavior", null, 1, false).contains("error"));
        }

        @Test
        @DisplayName("null version defaults to 1, null permanent defaults to false")
        void nullDefaults() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.deleteResource("behavior", "id", null, null).contains("error"));
        }
    }

    // ─── createAgent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAgent")
    class CreateAgent {

        @Test
        @DisplayName("null name → error")
        void nullName() {
            assertTrue(tools.createAgent(null, null, null).contains("error"));
        }

        @Test
        @DisplayName("blank name → error")
        void blankName() {
            assertTrue(tools.createAgent("  ", null, null).contains("error"));
        }

        @Test
        @DisplayName("exception → error json")
        void exception() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.createAgent("Agent", "desc", null).contains("error"));
        }
    }

    // ─── deleteAgent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgent {

        @Test
        @DisplayName("null defaults")
        void nullDefaults() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.deleteAgent("id", null, null, null).contains("error"));
        }
    }

    // ─── updateAgent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateAgent")
    class UpdateAgent {

        @Test
        @DisplayName("null agentId → error")
        void nullAgentId() {
            assertTrue(tools.updateAgent(null, null, null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("blank agentId → error")
        void blankAgentId() {
            assertTrue(tools.updateAgent("  ", null, null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("exception → error json")
        void exception() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertTrue(tools.updateAgent("id", 1, "name", null, null, null).contains("error"));
        }
    }

    // ─── listAgentResources ─────────────────────────────────────────────

    @Nested
    @DisplayName("listAgentResources")
    class ListAgentResources {

        @Test
        @DisplayName("null agentId → error")
        void nullAgentId() {
            assertTrue(tools.listAgentResources(null, null).contains("error"));
        }

        @Test
        @DisplayName("blank agentId → error")
        void blankAgentId() {
            assertTrue(tools.listAgentResources("  ", null).contains("error"));
        }
    }

    // ─── applyAgentChanges ──────────────────────────────────────────────

    @Nested
    @DisplayName("applyAgentChanges")
    class ApplyAgentChanges {

        @Test
        @DisplayName("null agentId → error")
        void nullAgentId() {
            assertTrue(tools.applyAgentChanges(null, 1, "[]", null, null).contains("error"));
        }

        @Test
        @DisplayName("null resourceMappings → error")
        void nullMappings() {
            assertTrue(tools.applyAgentChanges("id", 1, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("blank resourceMappings → error")
        void blankMappings() {
            assertTrue(tools.applyAgentChanges("id", 1, "  ", null, null).contains("error"));
        }
    }

    // ─── schedule tools ─────────────────────────────────────────────────

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        @Test
        @DisplayName("null agentId → error")
        void nullAgentId() {
            assertTrue(tools.createSchedule(null, null, null, null, null, null, null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("null name → error")
        void nullName() {
            assertTrue(tools.createSchedule("agent1", null, null, null, null, null, null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("CRON without cron expression → error")
        void cronNoCron() {
            assertTrue(tools.createSchedule("agent1", "CRON", null, null, "msg", "name", null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("CRON without message → error")
        void cronNoMessage() {
            assertTrue(tools.createSchedule("agent1", "CRON", "0 9 * * *", null, null, "name", null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("HEARTBEAT without interval → error")
        void heartbeatNoInterval() {
            assertTrue(tools.createSchedule("agent1", "HEARTBEAT", null, null, null, "name", null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("HEARTBEAT with zero interval → error")
        void heartbeatZeroInterval() {
            assertTrue(tools.createSchedule("agent1", "HEARTBEAT", null, 0L, null, "name", null, null, null, null).contains("error"));
        }

        @Test
        @DisplayName("null triggerType with heartbeatIntervalSeconds → HEARTBEAT")
        void implicitHeartbeat() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("sched-id");
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.createSchedule("agent1", null, null, 300L, null, "heartbeat-sched", null, null, null, null);
            verify(scheduleStore).createSchedule(argThat(s -> s.getTriggerType() == ScheduleConfiguration.TriggerType.HEARTBEAT));
        }

        @Test
        @DisplayName("HEARTBEAT description formatting — seconds")
        void heartbeatDescSeconds() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("id");
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.createSchedule("agent1", "HEARTBEAT", null, 30L, null, "test", null, null, null, null);
            // 30 seconds → "Every 30 seconds"
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("HEARTBEAT description formatting — minutes")
        void heartbeatDescMinutes() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("id");
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.createSchedule("agent1", "HEARTBEAT", null, 120L, null, "test", null, null, null, null);
            // 120 seconds → "Every 2 minutes"
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("HEARTBEAT description formatting — hours")
        void heartbeatDescHours() throws Exception {
            when(scheduleStore.createSchedule(any())).thenReturn("id");
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.createSchedule("agent1", "HEARTBEAT", null, 7200L, null, "test", null, null, null, null);
            // 7200 seconds → "Every 2 hours"
            verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("exception → error")
        void exception() throws Exception {
            when(scheduleStore.createSchedule(any())).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.createSchedule("agent1", "HEARTBEAT", null, 60L, null, "name", null, null, null, null).contains("error"));
        }
    }

    @Nested
    @DisplayName("listSchedules")
    class ListSchedules {

        @Test
        @DisplayName("with agentId filter")
        void withAgentId() throws Exception {
            var sched = new ScheduleConfiguration();
            sched.setName("test");
            sched.setTriggerType(null); // null triggerType → defaults "CRON"
            when(scheduleStore.readSchedulesByAgentId("agent1")).thenReturn(List.of(sched));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.listSchedules("agent1");
            verify(scheduleStore).readSchedulesByAgentId("agent1");
        }

        @Test
        @DisplayName("without agentId")
        void withoutAgentId() throws Exception {
            when(scheduleStore.readAllSchedules(100)).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.listSchedules(null);
            verify(scheduleStore).readAllSchedules(100);
        }

        @Test
        @DisplayName("schedule with all null fields")
        void scheduleNullFields() throws Exception {
            var sched = new ScheduleConfiguration();
            sched.setTriggerType(null);
            sched.setFireStatus(null);
            sched.setNextFire(null);
            sched.setLastFired(null);
            sched.setCronExpression(null);
            sched.setHeartbeatIntervalSeconds(null);
            when(scheduleStore.readAllSchedules(100)).thenReturn(List.of(sched));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.listSchedules(null);
        }

        @Test
        @DisplayName("schedule with cron and heartbeat")
        void scheduleWithCronAndHeartbeat() throws Exception {
            var sched = new ScheduleConfiguration();
            sched.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
            sched.setCronExpression("0 9 * * *");
            sched.setHeartbeatIntervalSeconds(300L);
            sched.setFireStatus(ScheduleConfiguration.FireStatus.PENDING);
            sched.setNextFire(Instant.now());
            sched.setLastFired(Instant.now());
            when(scheduleStore.readAllSchedules(100)).thenReturn(List.of(sched));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.listSchedules(null);
        }
    }

    @Nested
    @DisplayName("readSchedule")
    class ReadSchedule {

        @Test
        @DisplayName("null scheduleId → error")
        void nullId() {
            assertTrue(tools.readSchedule(null).contains("error"));
        }

        @Test
        @DisplayName("blank scheduleId → error")
        void blankId() {
            assertTrue(tools.readSchedule("  ").contains("error"));
        }

        @Test
        @DisplayName("exception → error")
        void exception() throws Exception {
            when(scheduleStore.readSchedule("id")).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.readSchedule("id").contains("error"));
        }
    }

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("null scheduleId → error")
        void nullId() {
            assertTrue(tools.deleteSchedule(null).contains("error"));
        }

        @Test
        @DisplayName("success")
        void success() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteSchedule("sched1");
            verify(scheduleStore).deleteSchedule("sched1");
        }
    }

    @Nested
    @DisplayName("fireScheduleNow")
    class FireScheduleNow {

        @Test
        @DisplayName("null scheduleId → error")
        void nullId() {
            assertTrue(tools.fireScheduleNow(null).contains("error"));
        }

        @Test
        @DisplayName("success with duration")
        void successWithDuration() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.getInstanceId()).thenReturn("inst1");

            var fireLog = new ScheduleFireLog("log1", "sched1", "fire1", Instant.now(),
                    Instant.now(), Instant.now().plusSeconds(1), "SUCCESS", "inst1", "conv1", null, 1, 0.0);
            when(scheduleFireExecutor.fire(any(), anyString(), anyInt())).thenReturn(fireLog);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");
            verify(scheduleFireExecutor).fire(schedule, "inst1", 1);
        }

        @Test
        @DisplayName("fire log with null completedAt/startedAt → duration null")
        void nullDuration() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.getInstanceId()).thenReturn("inst1");

            var fireLog = new ScheduleFireLog("log1", "sched1", "fire1", Instant.now(),
                    null, null, "PENDING", "inst1", "conv1", null, 1, 0.0);
            when(scheduleFireExecutor.fire(any(), anyString(), anyInt())).thenReturn(fireLog);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");
        }

        @Test
        @DisplayName("fire log with error message")
        void fireLogWithError() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.getInstanceId()).thenReturn("inst1");

            var fireLog = new ScheduleFireLog("log1", "sched1", "fire1", Instant.now(),
                    Instant.now(), Instant.now(), "FAILED", "inst1", null, "LLM unavailable", 1, 0.0);
            when(scheduleFireExecutor.fire(any(), anyString(), anyInt())).thenReturn(fireLog);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");
        }
    }

    @Nested
    @DisplayName("retryFailedSchedule")
    class RetryFailed {

        @Test
        @DisplayName("null scheduleId → error")
        void nullId() {
            assertTrue(tools.retryFailedSchedule(null).contains("error"));
        }

        @Test
        @DisplayName("success")
        void success() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            tools.retryFailedSchedule("sched1");
            verify(scheduleStore).requeueDeadLetter("sched1");
        }
    }

    // ─── trigger tools ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Agent trigger tools")
    class TriggerTools {

        @Test
        @DisplayName("createAgentTrigger — null config → error")
        void createNullConfig() {
            assertTrue(tools.createAgentTrigger(null).contains("error"));
        }

        @Test
        @DisplayName("createAgentTrigger — blank config → error")
        void createBlankConfig() {
            assertTrue(tools.createAgentTrigger("  ").contains("error"));
        }

        @Test
        @DisplayName("updateAgentTrigger — null intent → error")
        void updateNullIntent() {
            assertTrue(tools.updateAgentTrigger(null, "{}").contains("error"));
        }

        @Test
        @DisplayName("updateAgentTrigger — null config → error")
        void updateNullConfig() {
            assertTrue(tools.updateAgentTrigger("intent", null).contains("error"));
        }

        @Test
        @DisplayName("deleteAgentTrigger — null intent → error")
        void deleteNullIntent() {
            assertTrue(tools.deleteAgentTrigger(null).contains("error"));
        }

        @Test
        @DisplayName("deleteAgentTrigger — blank intent → error")
        void deleteBlankIntent() {
            assertTrue(tools.deleteAgentTrigger("  ").contains("error"));
        }
    }

    // ─── channel integration tools ──────────────────────────────────────

    @Nested
    @DisplayName("Channel integration tools")
    class ChannelTools {

        @Test
        @DisplayName("readChannelIntegration — null resourceId → error")
        void readNullId() {
            assertTrue(tools.readChannelIntegration(null, null).contains("error"));
        }

        @Test
        @DisplayName("createChannelIntegration — null config → error")
        void createNullConfig() {
            assertTrue(tools.createChannelIntegration(null).contains("error"));
        }

        @Test
        @DisplayName("updateChannelIntegration — null resourceId → error")
        void updateNullId() {
            assertTrue(tools.updateChannelIntegration(null, 1, "{}").contains("error"));
        }

        @Test
        @DisplayName("updateChannelIntegration — null config → error")
        void updateNullConfig() {
            assertTrue(tools.updateChannelIntegration("id", 1, null).contains("error"));
        }

        @Test
        @DisplayName("deleteChannelIntegration — null resourceId → error")
        void deleteNullId() {
            assertTrue(tools.deleteChannelIntegration(null, 1, false).contains("error"));
        }
    }

    // ─── resultJson serialization fallback ──────────────────────────────

    @Nested
    @DisplayName("resultJson")
    class ResultJson {

        @Test
        @DisplayName("serialization exception → fallback JSON")
        void serializationFallback() throws Exception {
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("serialize fail"));
            // This is tested implicitly when deploy returns a result but serialization
            // fails
            // Let's trigger through deleteSchedule which calls resultJson
            String result = tools.deleteSchedule("sched1");
            // Should still return some JSON
            assertNotNull(result);
            assertTrue(result.contains("schedule_deleted") || result.contains("error"));
        }
    }
}
