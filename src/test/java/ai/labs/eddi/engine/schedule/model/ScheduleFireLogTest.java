package ai.labs.eddi.engine.schedule.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleFireLogTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void construction() {
        var now = Instant.now();
        var log = new ScheduleFireLog(
                "id-1", "sched-1", "fire-1", now,
                now.minusSeconds(5), now, "COMPLETED",
                "instance-1", "conv-123", null, 1, 0.05);

        assertEquals("id-1", log.id());
        assertEquals("sched-1", log.scheduleId());
        assertEquals("fire-1", log.fireId());
        assertEquals("COMPLETED", log.status());
        assertNull(log.errorMessage());
        assertEquals(1, log.attemptNumber());
        assertEquals(0.05, log.cost());
    }

    @Test
    void failedFire() {
        var now = Instant.now();
        var log = new ScheduleFireLog(
                "id-2", "sched-1", "fire-2", now,
                now.minusSeconds(10), null, "FAILED",
                "instance-1", null, "Connection refused", 3, 0.0);

        assertEquals("FAILED", log.status());
        assertNull(log.completedAt());
        assertNull(log.conversationId());
        assertEquals("Connection refused", log.errorMessage());
        assertEquals(3, log.attemptNumber());
    }

    @Test
    void deadLettered() {
        var now = Instant.now();
        var log = new ScheduleFireLog(
                "id-3", "sched-1", "fire-3", now,
                now, null, "DEAD_LETTERED",
                "instance-2", null, "Max retries exceeded", 5, 0.0);

        assertEquals("DEAD_LETTERED", log.status());
        assertEquals(5, log.attemptNumber());
    }

    @Test
    void equality() {
        var now = Instant.now();
        var l1 = new ScheduleFireLog("id", "s", "f", now, now, now, "COMPLETED", "i", "c", null, 1, 0.0);
        var l2 = new ScheduleFireLog("id", "s", "f", now, now, now, "COMPLETED", "i", "c", null, 1, 0.0);
        assertEquals(l1, l2);
        assertEquals(l1.hashCode(), l2.hashCode());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var now = Instant.now();
        var log = new ScheduleFireLog("id", "sched", "fire", now, now, now, "COMPLETED", "inst", "conv", null, 1, 0.01);

        String json = mapper.writeValueAsString(log);
        var restored = mapper.readValue(json, ScheduleFireLog.class);

        assertEquals("id", restored.id());
        assertEquals("COMPLETED", restored.status());
        assertEquals(0.01, restored.cost());
    }
}
