package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MCP schedule tools (create, list, read, fire, delete, retry).
 */
class McpScheduleToolsTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService pollerService;
    private IJsonSerialization jsonSerialization;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        pollerService = mock(SchedulePollerService.class);
        jsonSerialization = mock(IJsonSerialization.class);

        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(mock(IRestAgentStore.class));
        when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(mock(IRestWorkflowStore.class));
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(mock(IRestDocumentDescriptorStore.class));

        lenient().when(jsonSerialization.serialize(any())).thenAnswer(inv -> {
            // Simple serialization for assertions
            return inv.getArgument(0).toString();
        });
        when(pollerService.getInstanceId()).thenReturn("test-instance");

        tools = new McpAdminTools(restInterfaceFactory, mock(IRestAgentAdministration.class), jsonSerialization, scheduleStore, fireExecutor,
                pollerService, mock(SecurityIdentity.class), false);
    }

    // --- createSchedule ---

    @Test
    void createSchedule_cron_success() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("sched-1");

        String result = tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, "Good morning", "Morning greeting", "UTC", "new", null,
                "production");

        assertTrue(result.contains("schedule_created"));
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    void createSchedule_heartbeat_success() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("hb-1");

        String result = tools.createSchedule("agent-1", "HEARTBEAT", null, 300L, null, "Health check", "UTC", null, null, "production");

        assertTrue(result.contains("schedule_created"));
        verify(scheduleStore).createSchedule(argThat(s -> s.getTriggerType() == TriggerType.HEARTBEAT
                && "persistent".equals(s.getConversationStrategy()) && "heartbeat".equals(s.getMessage())));
    }

    @Test
    void createSchedule_heartbeat_inferredFromInterval() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("hb-2");

        // triggerType is null, but heartbeatIntervalSeconds is set → infer HEARTBEAT
        String result = tools.createSchedule("agent-1", null, null, 600L, null, "Auto-inferred heartbeat", "UTC", null, null, null);

        assertTrue(result.contains("schedule_created"));
        verify(scheduleStore).createSchedule(argThat(s -> s.getTriggerType() == TriggerType.HEARTBEAT));
    }

    @Test
    void createSchedule_missingAgentId_error() {
        String result = tools.createSchedule(null, "CRON", "0 9 * * *", null, "Hello", "Test", "UTC", "new", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void createSchedule_missingName_error() {
        String result = tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, "Hello", null, "UTC", "new", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("name is required"));
    }

    @Test
    void createSchedule_cronWithoutMessage_error() {
        String result = tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, null, "No msg", "UTC", "new", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("message is required"));
    }

    @Test
    void createSchedule_heartbeatWithoutInterval_error() {
        String result = tools.createSchedule("agent-1", "HEARTBEAT", null, null, null, "No interval", "UTC", null, null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("heartbeatIntervalSeconds"));
    }

    @Test
    void createSchedule_invalidCron_error() {
        String result = tools.createSchedule("agent-1", "CRON", "bad cron", null, "Hello", "Bad cron", "UTC", "new", null, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Invalid"));
    }

    @Test
    void createSchedule_customUserId() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("sched-uid");

        tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, "Hello", "Custom user", "UTC", "new", "admin@company.com", null);

        verify(scheduleStore).createSchedule(argThat(s -> "admin@company.com".equals(s.getUserId())));
    }

    // --- listSchedules ---

    @Test
    void listSchedules_noFilter_callsReadAll() throws Exception {
        when(scheduleStore.readAllSchedules(100)).thenReturn(List.of());

        tools.listSchedules(null);

        verify(scheduleStore).readAllSchedules(100);
    }

    @Test
    void listSchedules_withAgentId_callsReadByAgent() throws Exception {
        when(scheduleStore.readSchedulesByAgentId("agent-1")).thenReturn(List.of());

        tools.listSchedules("agent-1");

        verify(scheduleStore).readSchedulesByAgentId("agent-1");
        verify(scheduleStore, never()).readAllSchedules(anyInt());
    }

    // --- readSchedule ---

    @Test
    void readSchedule_success() throws Exception {
        var schedule = makeSchedule("sched-1");
        when(scheduleStore.readSchedule("sched-1")).thenReturn(schedule);
        when(scheduleStore.readFireLogs("sched-1", 10)).thenReturn(List.of());

        tools.readSchedule("sched-1");

        verify(scheduleStore).readSchedule("sched-1");
        verify(scheduleStore).readFireLogs("sched-1", 10);
    }

    @Test
    void readSchedule_missingId_error() {
        String result = tools.readSchedule(null);
        assertTrue(result.contains("error"));
    }

    // --- fireScheduleNow ---

    @Test
    void fireNow_callsFireWithAttempt1() throws Exception {
        var schedule = makeSchedule("sched-1");
        when(scheduleStore.readSchedule("sched-1")).thenReturn(schedule);
        when(fireExecutor.fire(eq(schedule), eq("test-instance"), eq(1))).thenReturn(makeFireLog("sched-1"));

        tools.fireScheduleNow("sched-1");

        verify(fireExecutor).fire(schedule, "test-instance", 1);
    }

    @Test
    void fireNow_missingId_error() {
        String result = tools.fireScheduleNow("");
        assertTrue(result.contains("error"));
    }

    // --- deleteSchedule ---

    @Test
    void delete_callsStore() throws Exception {
        tools.deleteSchedule("sched-1");
        verify(scheduleStore).deleteSchedule("sched-1");
    }

    @Test
    void delete_missingId_error() {
        String result = tools.deleteSchedule(null);
        assertTrue(result.contains("error"));
    }

    // --- retryFailedSchedule ---

    @Test
    void retry_callsRequeue() throws Exception {
        String result = tools.retryFailedSchedule("sched-1");

        verify(scheduleStore).requeueDeadLetter("sched-1");
        assertTrue(result.contains("schedule_requeued"));
    }

    // --- Helpers ---

    private static ScheduleConfiguration makeSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test Schedule");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 9 * * *");
        s.setMessage("Hello");
        s.setEnvironment("production");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    private static ScheduleFireLog makeFireLog(String scheduleId) {
        return new ScheduleFireLog("log-1", scheduleId, "fire-1", Instant.now(), Instant.now(), Instant.now(), FireStatus.COMPLETED.name(),
                "test-instance", "conv-1", null, 1, 0.0);
    }
}
