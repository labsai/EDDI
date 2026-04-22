package ai.labs.eddi.engine.schedule.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleConfigurationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void defaults() {
        var config = new ScheduleConfiguration();
        assertEquals(ScheduleConfiguration.TriggerType.CRON, config.getTriggerType());
        assertTrue(config.isEnabled());
        assertEquals(ScheduleConfiguration.FireStatus.PENDING, config.getFireStatus());
        assertEquals(-1.0, config.getMaxCostPerFire());
        assertFalse(config.isAllowSelfScheduling());
    }

    @Test
    void triggerType_heartbeat() {
        var config = new ScheduleConfiguration();
        config.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
        config.setHeartbeatIntervalSeconds(300L);

        assertEquals(ScheduleConfiguration.TriggerType.HEARTBEAT, config.getTriggerType());
        assertEquals(300L, config.getHeartbeatIntervalSeconds());
    }

    @Test
    void cronConfiguration() {
        var config = new ScheduleConfiguration();
        config.setCronExpression("0 9 * * MON-FRI");
        config.setTimeZone("Europe/Vienna");

        assertEquals("0 9 * * MON-FRI", config.getCronExpression());
        assertEquals("Europe/Vienna", config.getTimeZone());
    }

    @Test
    void setAllFields() {
        var now = Instant.now();
        var config = new ScheduleConfiguration();

        config.setId("sched-1");
        config.setName("Morning Brief");
        config.setAgentId("agent-1");
        config.setAgentVersion(3);
        config.setEnvironment("production");
        config.setTenantId("default");
        config.setMessage("Good morning");
        config.setUserId("system:scheduler");
        config.setConversationStrategy("persistent");
        config.setPersistentConversationId("conv-123");
        config.setEnabled(false);
        config.setNextFire(now);
        config.setLastFired(now.minusSeconds(3600));
        config.setFireStatus(ScheduleConfiguration.FireStatus.COMPLETED);
        config.setClaimedBy("instance-1");
        config.setClaimedAt(now.minusSeconds(60));
        config.setFireId("sched-1:12345");
        config.setFailCount(2);
        config.setNextRetryAt(now.plusSeconds(30));
        config.setMaxCostPerFire(5.0);
        config.setAllowSelfScheduling(true);
        config.setCreatedBy("admin");
        config.setMetadata(Map.of("env", "test"));
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        config.setCronDescription("Every weekday at 9am");

        assertEquals("sched-1", config.getId());
        assertEquals("Morning Brief", config.getName());
        assertEquals("agent-1", config.getAgentId());
        assertEquals(3, config.getAgentVersion());
        assertFalse(config.isEnabled());
        assertEquals(ScheduleConfiguration.FireStatus.COMPLETED, config.getFireStatus());
        assertEquals(2, config.getFailCount());
        assertEquals(5.0, config.getMaxCostPerFire());
        assertTrue(config.isAllowSelfScheduling());
        assertEquals("Every weekday at 9am", config.getCronDescription());
    }

    @Test
    void fireStatus_allValues() {
        assertEquals(6, ScheduleConfiguration.FireStatus.values().length);
    }

    @Test
    void triggerType_allValues() {
        assertEquals(2, ScheduleConfiguration.TriggerType.values().length);
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var config = new ScheduleConfiguration();
        config.setId("s1");
        config.setName("Test");
        config.setAgentId("a1");
        config.setCronExpression("0 * * * *");

        String json = mapper.writeValueAsString(config);
        var restored = mapper.readValue(json, ScheduleConfiguration.class);

        assertEquals("s1", restored.getId());
        assertEquals("Test", restored.getName());
        assertEquals("0 * * * *", restored.getCronExpression());
    }
}
