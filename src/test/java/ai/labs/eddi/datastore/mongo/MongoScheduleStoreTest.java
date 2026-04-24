/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.schedule.mongo.MongoScheduleStore;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoScheduleStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("MongoScheduleStore IT")
class MongoScheduleStoreTest extends MongoTestBase {

    private static MongoScheduleStore store;

    @BeforeAll
    static void init() {
        store = new MongoScheduleStore(getDatabase(), jsonSerialization, documentBuilder);
    }

    @BeforeEach
    void clean() {
        dropCollections("eddi_schedules", "eddi_schedule_fire_logs");
    }

    // ─── CRUD ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("create + read round-trip")
        void createAndRead() throws Exception {
            var cfg = newSchedule("Test Schedule", "agent-1");
            String id = store.createSchedule(cfg);
            assertNotNull(id);

            var read = store.readSchedule(id);
            assertEquals("Test Schedule", read.getName());
            assertEquals("agent-1", read.getAgentId());
        }

        @Test
        @DisplayName("read non-existent — throws ResourceNotFoundException")
        void readNotFound() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readSchedule("no-such-id"));
        }

        @Test
        @DisplayName("update schedule")
        void update() throws Exception {
            var cfg = newSchedule("Original", "agent-1");
            String id = store.createSchedule(cfg);

            cfg.setName("Updated");
            store.updateSchedule(id, cfg);

            assertEquals("Updated", store.readSchedule(id).getName());
        }

        @Test
        @DisplayName("update non-existent — throws ResourceNotFoundException")
        void updateNotFound() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.updateSchedule("ghost", newSchedule("x", "a")));
        }

        @Test
        @DisplayName("delete schedule")
        void delete() throws Exception {
            String id = store.createSchedule(newSchedule("Del", "a"));
            store.deleteSchedule(id);
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readSchedule(id));
        }

        @Test
        @DisplayName("deleteByAgentId — cascade")
        void deleteByAgent() throws Exception {
            store.createSchedule(newSchedule("S1", "agent-x"));
            store.createSchedule(newSchedule("S2", "agent-x"));
            store.createSchedule(newSchedule("S3", "agent-y"));

            int deleted = store.deleteSchedulesByAgentId("agent-x");
            assertEquals(2, deleted);
            assertEquals(1, store.readAllSchedules(100).size());
        }
    }

    // ─── List Queries ───────────────────────────────────────────

    @Nested
    @DisplayName("List Queries")
    class ListQueries {

        @Test
        @DisplayName("readAllSchedules with limit")
        void readAll() throws Exception {
            for (int i = 0; i < 5; i++) {
                store.createSchedule(newSchedule("S" + i, "a"));
            }
            assertEquals(3, store.readAllSchedules(3).size());
            assertEquals(5, store.readAllSchedules(100).size());
        }

        @Test
        @DisplayName("readSchedulesByAgentId")
        void readByAgent() throws Exception {
            store.createSchedule(newSchedule("S1", "a1"));
            store.createSchedule(newSchedule("S2", "a1"));
            store.createSchedule(newSchedule("S3", "a2"));

            assertEquals(2, store.readSchedulesByAgentId("a1").size());
        }
    }

    // ─── Claiming & State ───────────────────────────────────────

    @Nested
    @DisplayName("Claiming and State")
    class ClaimingAndState {

        @Test
        @DisplayName("tryClaim PENDING schedule — succeeds")
        void claimPending() throws Exception {
            var cfg = newSchedule("Claimable", "a");
            cfg.setEnabled(true);
            cfg.setNextFire(Instant.now().minusSeconds(60));
            cfg.setFireStatus(FireStatus.PENDING);
            String id = store.createSchedule(cfg);

            assertTrue(store.tryClaim(id, "node-1", Instant.now()));
            assertEquals(FireStatus.CLAIMED, store.readSchedule(id).getFireStatus());
        }

        @Test
        @DisplayName("double-claim — second claim fails")
        void doubleClaim() throws Exception {
            var cfg = newSchedule("Claimable", "a");
            cfg.setEnabled(true);
            cfg.setNextFire(Instant.now().minusSeconds(60));
            cfg.setFireStatus(FireStatus.PENDING);
            String id = store.createSchedule(cfg);

            assertTrue(store.tryClaim(id, "node-1", Instant.now()));
            assertFalse(store.tryClaim(id, "node-2", Instant.now()));
        }

        @Test
        @DisplayName("markCompleted — resets to PENDING with nextFire")
        void markCompleted() throws Exception {
            var cfg = newSchedule("Complete", "a");
            cfg.setEnabled(true);
            cfg.setFireStatus(FireStatus.CLAIMED);
            String id = store.createSchedule(cfg);

            Instant next = Instant.now().plusSeconds(3600);
            store.markCompleted(id, next);

            var read = store.readSchedule(id);
            assertEquals(FireStatus.PENDING, read.getFireStatus());
        }

        @Test
        @DisplayName("markCompleted null nextFire — disables schedule")
        void markCompletedOneShot() throws Exception {
            var cfg = newSchedule("OneShot", "a");
            cfg.setEnabled(true);
            cfg.setFireStatus(FireStatus.CLAIMED);
            String id = store.createSchedule(cfg);

            store.markCompleted(id, null);
            assertFalse(store.readSchedule(id).isEnabled());
        }

        @Test
        @DisplayName("markFailed — increments failCount")
        void markFailed() throws Exception {
            var cfg = newSchedule("Fail", "a");
            cfg.setFireStatus(FireStatus.CLAIMED);
            String id = store.createSchedule(cfg);

            store.markFailed(id, Instant.now().plusSeconds(300));
            var read = store.readSchedule(id);
            assertEquals(FireStatus.FAILED, read.getFireStatus());
            assertEquals(1, read.getFailCount());
        }

        @Test
        @DisplayName("markDeadLettered — sets DEAD_LETTERED")
        void markDeadLettered() throws Exception {
            var cfg = newSchedule("DL", "a");
            cfg.setFireStatus(FireStatus.FAILED);
            String id = store.createSchedule(cfg);

            store.markDeadLettered(id);
            assertEquals(FireStatus.DEAD_LETTERED, store.readSchedule(id).getFireStatus());
        }

        @Test
        @DisplayName("requeueDeadLetter — resets to PENDING")
        void requeueDeadLetter() throws Exception {
            var cfg = newSchedule("Requeue", "a");
            cfg.setFireStatus(FireStatus.DEAD_LETTERED);
            String id = store.createSchedule(cfg);
            // Need to set status via markDeadLettered since createSchedule may not persist
            // fireStatus directly
            store.markDeadLettered(id);

            store.requeueDeadLetter(id);
            var read = store.readSchedule(id);
            assertEquals(FireStatus.PENDING, read.getFireStatus());
            assertEquals(0, read.getFailCount());
        }

        @Test
        @DisplayName("requeueDeadLetter non-DL — throws ResourceNotFoundException")
        void requeueNonDl() throws Exception {
            var cfg = newSchedule("NotDL", "a");
            cfg.setFireStatus(FireStatus.PENDING);
            String id = store.createSchedule(cfg);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.requeueDeadLetter(id));
        }
    }

    // ─── Enable/Disable ─────────────────────────────────────────

    @Nested
    @DisplayName("Enable/Disable")
    class EnableDisable {

        @Test
        @DisplayName("setScheduleEnabled true — sets nextFire and resets to PENDING")
        void enable() throws Exception {
            var cfg = newSchedule("Disabled", "a");
            cfg.setEnabled(false);
            String id = store.createSchedule(cfg);

            Instant nextFire = Instant.now().plusSeconds(3600);
            store.setScheduleEnabled(id, true, nextFire);

            var read = store.readSchedule(id);
            assertTrue(read.isEnabled());
            assertEquals(FireStatus.PENDING, read.getFireStatus());
        }

        @Test
        @DisplayName("setScheduleEnabled non-existent — throws ResourceNotFoundException")
        void enableNotFound() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.setScheduleEnabled("ghost", true, Instant.now()));
        }
    }

    // ─── Fire Logs ──────────────────────────────────────────────

    @Nested
    @DisplayName("Fire Logs")
    class FireLogs {

        @Test
        @DisplayName("logFire + readFireLogs")
        void logAndRead() throws Exception {
            var log = new ScheduleFireLog("fire-1", "sched-1", "fire-key-1",
                    Instant.now(), Instant.now(), Instant.now(),
                    "COMPLETED", "node-1", "conv-1", null, 1, 0.0);
            store.logFire(log);

            List<ScheduleFireLog> logs = store.readFireLogs("sched-1", 10);
            assertEquals(1, logs.size());
            assertEquals("fire-1", logs.getFirst().id());
        }

        @Test
        @DisplayName("readFailedFireLogs — filters FAILED + DEAD_LETTERED")
        void readFailed() throws Exception {
            store.logFire(new ScheduleFireLog("f1", "s1", "fk1",
                    Instant.now(), Instant.now(), Instant.now(),
                    "COMPLETED", "n1", "c1", null, 1, 0.0));
            store.logFire(new ScheduleFireLog("f2", "s1", "fk2",
                    Instant.now(), Instant.now(), Instant.now(),
                    "FAILED", "n1", "c2", "error msg", 1, 0.0));
            store.logFire(new ScheduleFireLog("f3", "s1", "fk3",
                    Instant.now(), Instant.now(), Instant.now(),
                    "DEAD_LETTERED", "n1", "c3", "max retries", 3, 0.0));

            List<ScheduleFireLog> failed = store.readFailedFireLogs(10);
            assertEquals(2, failed.size());
        }
    }

    // ─── findDueSchedules ───────────────────────────────────────

    @Nested
    @DisplayName("Due Schedules")
    class DueSchedules {

        @Test
        @DisplayName("findDueSchedules — returns enabled PENDING past nextFire")
        void findDue() throws Exception {
            // This schedule IS due: enabled, PENDING, nextFire in the past
            var due = newSchedule("Due", "a");
            due.setEnabled(true);
            due.setNextFire(Instant.now().minusSeconds(60));
            due.setFireStatus(FireStatus.PENDING);
            store.createSchedule(due);

            List<ScheduleConfiguration> dueList = store.findDueSchedules(
                    Instant.now(), Instant.now().minusSeconds(300), 3);

            assertFalse(dueList.isEmpty(), "Expected at least one due schedule");
            assertTrue(dueList.stream().anyMatch(s -> "Due".equals(s.getName())),
                    "Expected 'Due' schedule in results");
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static ScheduleConfiguration newSchedule(String name, String agentId) {
        var cfg = new ScheduleConfiguration();
        cfg.setName(name);
        cfg.setAgentId(agentId);
        cfg.setEnabled(false);
        cfg.setFireStatus(FireStatus.PENDING);
        cfg.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
        cfg.setCronExpression("0 0 * * * ?");
        return cfg;
    }
}
