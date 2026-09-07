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
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import java.io.IOException;

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
                strictConfigurationParser(),
                scheduleStore, scheduleFireExecutor, schedulePollerService,
                identity, false);
        // fire_schedule_now claims the schedule first, exactly as the poller and the
        // REST endpoint do. Default the claim to "won" so tests about anything else
        // still reach the fire.
        when(schedulePollerService.claimForManualFire(any())).thenReturn(true);
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
            assertTrue(result.contains("Check server logs"),
                    "an unrecognised failure must NOT leak its own message to an MCP client");
        }

        /**
         * An over-limit refusal is actionable ("undeploy an agent first"), so its
         * reason is passed through verbatim rather than replaced by "check server logs"
         * — which a model driving an MCP client cannot self-correct from and will retry
         * in a loop.
         */
        @Test
        @DisplayName("an over-quota refusal returns the quota reason verbatim")
        void quotaExceededReturnsTheReason() {
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new QuotaExceededException("Agent limit reached (5)"));

            String result = tools.deployAgent("agent1", 1, null);

            assertEquals("{\"error\":\"Agent limit reached (5)\"}", result);
        }

        /**
         * The regression the {@code QuotaRefusal} marker exists for.
         * {@code QuotaAccountingUnavailableException} is a sibling of
         * {@code QuotaExceededException}, not a subclass, so the previous
         * {@code catch (QuotaExceededException)} stopped matching it and a quota-store
         * outage fell into the generic branch — reaching the client as "Failed to
         * deploy agent. Check server logs for details."
         */
        @Test
        @DisplayName("a quota-store outage also returns its reason, not the generic 'check server logs'")
        void quotaAccountingUnavailableReturnsTheReason() {
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new QuotaAccountingUnavailableException(
                            "Quota accounting unavailable — denying request for safety"));

            String result = tools.deployAgent("agent1", 1, null);

            assertEquals("{\"error\":\"Quota accounting unavailable — denying request for safety\"}", result);
            assertFalse(result.contains("Check server logs"),
                    "a store outage is not an unknown failure; the MCP client must be told what happened");
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

        /**
         * {@code fire_schedule_now} used to call the executor directly: no cluster
         * claim, so it raced the poller — and with
         * {@code conversationStrategy=persistent} both pushed a turn into the SAME
         * conversation — and no {@code recordManualFireOutcome}, so the fire never
         * reached the retry/backoff/one-shot state machine. A failure here did not
         * increment failCount and a success did not re-arm the schedule. Routing it
         * through the same claim/fire/finally flow as the REST endpoint is the point.
         */
        @Test
        @DisplayName("claims the schedule and records the outcome, like the REST endpoint")
        void claimsAndRecordsTheOutcome() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            schedule.setFailCount(2);
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.getInstanceId()).thenReturn("inst1");
            var fireLog = new ScheduleFireLog("log1", "sched1", "fire1", Instant.now(), Instant.now(),
                    Instant.now(), "COMPLETED", "inst1", "conv1", null, 3, 0.0);
            when(scheduleFireExecutor.fire(any(), anyString(), anyInt())).thenReturn(fireLog);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");

            var inOrder = inOrder(schedulePollerService, scheduleFireExecutor);
            inOrder.verify(schedulePollerService).claimForManualFire(schedule);
            // The attempt this actually is, not a constant 1: a manual retry of a
            // schedule on its third failure logged as "attempt 1" and hid the history.
            inOrder.verify(scheduleFireExecutor).fire(schedule, "inst1", 3);
            inOrder.verify(schedulePollerService).recordManualFireOutcome(schedule, fireLog);
        }

        @Test
        @DisplayName("a refused claim does not fire")
        void refusedClaimDoesNotFire() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.claimForManualFire(any())).thenReturn(false);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");

            verify(scheduleFireExecutor, never()).fire(any(), anyString(), anyInt());
            verify(schedulePollerService, never()).recordManualFireOutcome(any(), any());
        }

        /**
         * REST refuses a manual fire of a HITL approval timeout for EVERYONE, because
         * firing it applies the configured AUTO_APPROVE/AUTO_REJECT/ABORT decision with
         * a system actor and no owner/admin/approver check. This tool must refuse too,
         * or it is the same bypass behind a different door.
         */
        @Test
        @DisplayName("refuses a HITL approval timeout")
        void refusesHitlTimeoutSchedule() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("hitl-timeout-conv-1");
            schedule.setMetadata(Map.of("hitlType", "hitl_timeout", "conversationId", "conv-1"));
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.fireScheduleNow("sched1");

            verify(schedulePollerService, never()).claimForManualFire(any());
            verify(scheduleFireExecutor, never()).fire(any(), anyString(), anyInt());
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

        /**
         * The same interrupt discipline the REST endpoint got, on the tool that shares
         * its flow.
         * <p>
         * {@code ScheduleFireExecutor.fire} deliberately re-asserts an interrupt that a
         * blocking call inside it consumed, so on the interrupted path — shutdown, a
         * cancelled tool invocation — this finally block runs with the flag set. The
         * synchronous Mongo driver then throws {@code MongoInterruptedException} on
         * connection checkout, {@code recordManualFireOutcome} swallows it, and the
         * schedule stays CLAIMED with failCount never incremented until its lease
         * expires. The flag must be parked across the write and re-asserted after it,
         * or the cancellation signal is lost instead.
         */
        @Test
        @DisplayName("an interrupted fire releases the claim with the flag parked, then restores it")
        void interruptedFireReleasesTheClaimWithTheFlagParked() throws Exception {
            var schedule = new ScheduleConfiguration();
            schedule.setName("test");
            when(scheduleStore.readSchedule("sched1")).thenReturn(schedule);
            when(schedulePollerService.getInstanceId()).thenReturn("inst1");
            var fireLog = new ScheduleFireLog("log1", "sched1", "fire1", Instant.now(), Instant.now(),
                    Instant.now(), "FAILED", "inst1", "conv1", "interrupted", 1, 0.0);
            // Mirror ScheduleFireExecutor.restoreInterrupt: the flag is set when fire()
            // returns on the interrupted path.
            when(scheduleFireExecutor.fire(any(), anyString(), anyInt())).thenAnswer(inv -> {
                Thread.currentThread().interrupt();
                return fireLog;
            });
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            var flagDuringRelease = new AtomicBoolean(true);
            doAnswer(inv -> {
                flagDuringRelease.set(Thread.currentThread().isInterrupted());
                return null;
            }).when(schedulePollerService).recordManualFireOutcome(any(), any());

            try {
                tools.fireScheduleNow("sched1");

                assertFalse(flagDuringRelease.get(),
                        "the bookkeeping write must not run under a set interrupt flag — the sync Mongo "
                                + "driver throws MongoInterruptedException on connection checkout and the claim leaks");
                assertTrue(Thread.currentThread().isInterrupted(),
                        "the interrupt must be re-asserted afterwards, or the cancellation signal is swallowed");
            } finally {
                Thread.interrupted(); // never leak a set flag into the next test
            }
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

    /**
     * A parser that defers to this test's {@code jsonSerialization} mock, so the
     * existing {@code when(jsonSerialization.deserialize(...))} stubs keep
     * describing what these dispatch tests are actually about. Strictness itself is
     * covered by {@code StrictConfigurationParserTest}; here the only thing that
     * matters is that each resource type reaches the right store.
     */
    private StrictConfigurationParser strictConfigurationParser() {
        var parser = mock(StrictConfigurationParser.class);
        try {
            lenient().when(parser.parse(anyString(), any()))
                    .thenAnswer(invocation -> jsonSerialization.deserialize(invocation.getArgument(0), invocation.getArgument(1)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return parser;
    }
}
