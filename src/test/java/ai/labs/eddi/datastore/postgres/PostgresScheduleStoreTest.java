/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.schedule.ScheduleOwnerScope;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresScheduleStore} using Testcontainers.
 * <p>
 * Covers the full schedule lifecycle: CRUD, enable/disable, claiming,
 * completion/failure state transitions, dead-lettering, requeue, and fire log
 * persistence.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresScheduleStore IT")
class PostgresScheduleStoreTest extends PostgresTestBase {

    private static PostgresScheduleStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        store = new PostgresScheduleStore(dsInstance,
                new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)),
                100);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "eddi_schedules", "eddi_schedule_fire_logs");
        } catch (SQLException ignored) {
        }
    }

    // ─── CRUD ───────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("createSchedule + readSchedule round-trip")
        void createAndRead() throws Exception {
            var config = createCronSchedule("Daily Digest", "agent1", "tenant1");
            String id = store.createSchedule(config);
            assertNotNull(id);

            var found = store.readSchedule(id);
            assertEquals(id, found.getId());
            assertEquals("Daily Digest", found.getName());
            assertEquals("agent1", found.getAgentId());
            assertEquals("tenant1", found.getTenantId());
            assertEquals(TriggerType.CRON, found.getTriggerType());
            assertEquals("0 9 * * MON-FRI", found.getCronExpression());
            assertTrue(found.isEnabled());
            assertEquals(FireStatus.PENDING, found.getFireStatus());
        }

        @Test
        @DisplayName("readSchedule non-existent — throws ResourceNotFoundException")
        void readNonExistent() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readSchedule("nonexistent-id"));
        }

        @Test
        @DisplayName("Finding #5 parity: HITL metadata survives create/read round-trip")
        void metadataRoundTrip() throws Exception {
            var config = createCronSchedule("HITL timeout", "agent1", "t");
            config.setMetadata(Map.of("hitlType", "hitl_timeout", "policy", "AUTO_APPROVE",
                    "surface", "regular", "conversationId", "conv-xyz"));
            String id = store.createSchedule(config);

            var found = store.readSchedule(id);
            assertNotNull(found.getMetadata());
            assertEquals("hitl_timeout", found.getMetadata().get("hitlType"));
            assertEquals("AUTO_APPROVE", found.getMetadata().get("policy"));
            assertEquals("conv-xyz", found.getMetadata().get("conversationId"));
        }

        @Test
        @DisplayName("updateSchedule — modifies fields")
        void updateSchedule() throws Exception {
            var config = createCronSchedule("Original", "agent1", "t");
            String id = store.createSchedule(config);

            config.setName("Updated Name");
            store.updateSchedule(id, config);

            var found = store.readSchedule(id);
            assertEquals("Updated Name", found.getName());
        }

        @Test
        @DisplayName("updateSchedule — a disable between snapshot and save is not overwritten")
        void updateKeepsAConcurrentDisable() throws Exception {
            String id = store.createSchedule(createCronSchedule("Race", "agent1", "t"));

            // An editor reads the schedule (enabled) ...
            var snapshot = store.readSchedule(id);
            assertTrue(snapshot.isEnabled());
            // ... another operator disables it ...
            store.setScheduleEnabled(id, false, null);
            // ... and the editor saves the stale copy.
            snapshot.setName("Edited");
            store.updateSchedule(id, snapshot);

            var found = store.readSchedule(id);
            assertEquals("Edited", found.getName(), "the edit itself lands");
            assertFalse(found.isEnabled(), "the stale enabled=true must not re-enable the schedule");
        }

        @Test
        @DisplayName("updateSchedule — never disables either; enabled changes only via setScheduleEnabled")
        void updateDoesNotDisable() throws Exception {
            String id = store.createSchedule(createCronSchedule("Stay on", "agent1", "t"));
            var body = store.readSchedule(id);
            body.setEnabled(false);
            store.updateSchedule(id, body);

            assertTrue(store.readSchedule(id).isEnabled());
        }

        @Test
        @DisplayName("updateSchedule non-existent — throws ResourceNotFoundException")
        void updateNonExistent() {
            var config = createCronSchedule("N/A", "a", "t");
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.updateSchedule("nonexistent", config));
        }

        @Test
        @DisplayName("deleteSchedule — removes schedule")
        void deleteSchedule() throws Exception {
            String id = store.createSchedule(createCronSchedule("Temp", "a", "t"));
            store.deleteSchedule(id);

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.readSchedule(id));
        }

        @Test
        @DisplayName("deleteSchedulesByAgentId — cascades correctly")
        void deleteByAgent() throws Exception {
            store.createSchedule(createCronSchedule("S1", "agentX", "t"));
            store.createSchedule(createCronSchedule("S2", "agentX", "t"));
            store.createSchedule(createCronSchedule("S3", "agentY", "t"));

            int deleted = store.deleteSchedulesByAgentId("agentX");
            assertEquals(2, deleted);

            assertEquals(0, store.readSchedulesByAgentId("agentX").size());
            assertEquals(1, store.readSchedulesByAgentId("agentY").size());
        }
    }

    // ─── List queries ───────────────────────────────────────────

    @Nested
    @DisplayName("List queries")
    class ListQueries {

        @Test
        @DisplayName("readAllSchedules — respects limit")
        void readAll() throws Exception {
            for (int i = 0; i < 5; i++) {
                store.createSchedule(createCronSchedule("S" + i, "a" + i, "t"));
            }

            assertEquals(5, store.readAllSchedules(10).size());
            assertEquals(3, store.readAllSchedules(3).size());
        }

        @Test
        @DisplayName("readSchedulesByAgentId — filters by agent")
        void readByAgent() throws Exception {
            store.createSchedule(createCronSchedule("S1", "agentA", "t"));
            store.createSchedule(createCronSchedule("S2", "agentA", "t"));
            store.createSchedule(createCronSchedule("S3", "agentB", "t"));

            assertEquals(2, store.readSchedulesByAgentId("agentA").size());
            assertEquals(1, store.readSchedulesByAgentId("agentB").size());
            assertEquals(0, store.readSchedulesByAgentId("agentC").size());
        }

        @Test
        @DisplayName("owner scope is part of the query: limit/offset count only the caller's own and shared schedules")
        void ownerScopedPaging() throws Exception {
            for (int i = 0; i < 5; i++) {
                store.createSchedule(owned(createCronSchedule("other" + i, "a", "t"), "victim-42"));
            }
            for (int i = 0; i < 3; i++) {
                store.createSchedule(owned(createCronSchedule("own" + i, "a", "t"), "editor-1"));
            }
            store.createSchedule(createCronSchedule("unowned", "a", "t"));
            store.createSchedule(owned(createCronSchedule("blank", "a", "t"), "  "));
            store.createSchedule(owned(createCronSchedule("system", "a", "t"), ScheduleOwnerScope.SHARED_OWNER));
            store.createSchedule(owned(createCronSchedule("own-other-agent", "b", "t"), "editor-1"));

            var editor = ScheduleOwnerScope.visibleTo("editor-1");
            // excludeHitlTimeouts=true as well, so the two clauses are composed
            var firstPage = store.readAllSchedules(4, 0, true, editor);
            var secondPage = store.readAllSchedules(4, 4, true, editor);
            assertEquals(4, firstPage.size(), "a full first page of the caller's visible rows");
            assertEquals(3, secondPage.size());
            var seen = new HashSet<String>();
            firstPage.forEach(s -> seen.add(s.getName()));
            secondPage.forEach(s -> seen.add(s.getName()));
            assertEquals(Set.of("own0", "own1", "own2", "unowned", "blank", "system", "own-other-agent"), seen);

            assertEquals(4, store.readAllSchedules(4, 0, false, editor).size(), "owner clause alone, as the WHERE");

            var byAgent = store.readSchedulesByAgentId("a", 100, 0, true, editor);
            assertEquals(6, byAgent.size());
            assertTrue(byAgent.stream().noneMatch(s -> "victim-42".equals(s.getUserId())));
            assertEquals(6, store.readSchedulesByAgentId("a", 100, 0, false, editor).size());

            assertEquals(3, store.readAllSchedules(100, 0, true, ScheduleOwnerScope.visibleTo(null)).size(),
                    "a caller with no id sees only shared schedules");
            assertEquals(12, store.readAllSchedules(100, 0, false, ScheduleOwnerScope.ALL).size());
            assertEquals(12, store.readAllSchedules(100, 0, false).size(), "the unscoped overload filters nothing");
        }

        /**
         * The workspace refinements of the owner scope run in the query against a real
         * database: shared rows only when the caller created them, and every team
         * cadence only when asked.
         */
        @Test
        @DisplayName("owner scope refinements — shared rows by creator, team cadences on request")
        void ownerScopeRefinements() throws Exception {
            var mine = owned(createCronSchedule("mine", "a", "t"), "alice");
            var theirs = owned(createCronSchedule("theirs", "a", "t"), "bob");
            var systemByAlice = owned(createCronSchedule("system-by-alice", "a", "t"), ScheduleOwnerScope.SHARED_OWNER);
            systemByAlice.setCreatedBy("alice");
            var systemByBob = owned(createCronSchedule("system-by-bob", "a", "t"), ScheduleOwnerScope.SHARED_OWNER);
            systemByBob.setCreatedBy("bob");
            var legacy = createCronSchedule("legacy-no-user", "a", "t");
            var bobsCadence = owned(createCronSchedule("bobs-cadence", "a", "t"), "bob");
            bobsCadence.setMetadata(Map.of("teamCadenceType", "team_cadence", "groupId", "g1", "cadenceId", "c1"));
            for (var s : List.of(mine, theirs, systemByAlice, systemByBob, legacy, bobsCadence)) {
                store.createSchedule(s);
            }

            var alice = ScheduleOwnerScope.visibleTo("alice");
            assertEquals(Set.of("mine", "system-by-alice"),
                    names(store.readAllSchedules(100, 0, false, alice.sharedOnlyIfCreatedByCaller())));
            assertEquals(Set.of("mine", "system-by-alice", "system-by-bob", "legacy-no-user"),
                    names(store.readAllSchedules(100, 0, false, alice)));
            assertEquals(Set.of("mine", "system-by-alice", "system-by-bob", "legacy-no-user", "bobs-cadence"),
                    names(store.readAllSchedules(100, 0, false, alice.withTeamCadences())));
            assertEquals(Set.of("mine", "system-by-alice"), names(store.readSchedulesByAgentId("a", 100, 0, true,
                    alice.sharedOnlyIfCreatedByCaller())));
            assertEquals(Set.of(), names(store.readAllSchedules(100, 0, false,
                    ScheduleOwnerScope.visibleTo(null).sharedOnlyIfCreatedByCaller())),
                    "no caller id and shared rows restricted to their creator admits nothing");
        }

        private Set<String> names(List<ScheduleConfiguration> schedules) {
            Set<String> names = new HashSet<>();
            schedules.forEach(s -> names.add(s.getName()));
            return names;
        }
    }

    private static ScheduleConfiguration owned(ScheduleConfiguration cfg, String userId) {
        cfg.setUserId(userId);
        return cfg;
    }

    // ─── Enable/Disable ─────────────────────────────────────────

    @Nested
    @DisplayName("Enable/Disable")
    class EnableDisable {

        @Test
        @DisplayName("setScheduleEnabled — enables with nextFire")
        void enableWithNextFire() throws Exception {
            var config = createCronSchedule("S1", "a", "t");
            config.setEnabled(false);
            String id = store.createSchedule(config);

            Instant nextFire = Instant.now().plus(1, ChronoUnit.HOURS);
            store.setScheduleEnabled(id, true, nextFire);

            var found = store.readSchedule(id);
            assertTrue(found.isEnabled());
            assertNotNull(found.getNextFire());
            assertEquals(FireStatus.PENDING, found.getFireStatus());
        }

        @Test
        @DisplayName("setScheduleEnabled — disables")
        void disable() throws Exception {
            String id = store.createSchedule(createCronSchedule("S", "a", "t"));
            store.setScheduleEnabled(id, false, null);

            assertFalse(store.readSchedule(id).isEnabled());
        }

        @Test
        @DisplayName("setScheduleEnabled non-existent — throws ResourceNotFoundException")
        void nonExistent() {
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.setScheduleEnabled("ghost", true, Instant.now()));
        }
    }

    // ─── Claiming + State Machine ───────────────────────────────

    @Nested
    @DisplayName("Claiming and State Transitions")
    class StateMachine {

        @Test
        @DisplayName("tryClaim — claims PENDING schedule")
        void claimPending() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            config.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            String id = store.createSchedule(config);

            // Past leaseExpiry: a fresh PENDING row is claimed via the PENDING clause,
            // never the lease-steal clause.
            boolean claimed = store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES));
            assertTrue(claimed);

            var found = store.readSchedule(id);
            assertEquals(FireStatus.CLAIMED, found.getFireStatus());
            assertEquals("node-1", found.getClaimedBy());
            assertNotNull(found.getClaimedAt());
            assertNotNull(found.getFireId());
        }

        @Test
        @DisplayName("tryClaim — cannot double-claim")
        void cannotDoubleClaim() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            config.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            String id = store.createSchedule(config);

            // A PAST leaseExpiry (5 min ago) means the second claim cannot steal the
            // fresh lease: node-1's claimed_at ≈ now is NOT <= leaseExpiry.
            assertTrue(store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES)));
            // Second claim should fail — already CLAIMED with a still-valid lease
            assertFalse(store.tryClaim(id, "node-2", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES)));
        }

        @Test
        @DisplayName("tryClaim steals a CLAIMED schedule whose lease expired")
        void claimStealsExpiredLease() throws Exception {
            var config = createCronSchedule("Steal", "a", "t");
            config.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            String id = store.createSchedule(config);

            // node-1 claims at a fixed instant T; its lease (claimed_at) is anchored at T.
            Instant t = Instant.now().minus(10, ChronoUnit.MINUTES);
            assertTrue(store.tryClaim(id, "node-1", t, t.minus(5, ChronoUnit.MINUTES)));
            assertEquals("node-1", store.readSchedule(id).getClaimedBy());

            // node-2 polls with a leaseExpiry AFTER node-1's claimed_at (T) — lease
            // considered expired, so the CLAIMED row is stolen.
            assertTrue(store.tryClaim(id, "node-2", Instant.now(), t.plusSeconds(1)),
                    "an expired-lease CLAIMED schedule must be reclaimable");
            var found = store.readSchedule(id);
            assertEquals(FireStatus.CLAIMED, found.getFireStatus());
            assertEquals("node-2", found.getClaimedBy(), "the stolen lease is now owned by node-2");
        }

        @Test
        @DisplayName("tryClaim does NOT steal a CLAIMED schedule whose lease is still valid")
        void claimDoesNotStealValidLease() throws Exception {
            var config = createCronSchedule("NoSteal", "a", "t");
            config.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            String id = store.createSchedule(config);

            assertTrue(store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES)));

            // leaseExpiry BEFORE node-1's fresh claimed_at → lease still valid → no steal.
            assertFalse(store.tryClaim(id, "node-2", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES)),
                    "a still-valid lease must not be stolen");
            assertEquals("node-1", store.readSchedule(id).getClaimedBy(), "node-1 keeps its valid lease");
        }

        @Test
        @DisplayName("markCompleted with nextFire — resets to PENDING + reschedules")
        void markCompletedWithReschedule() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            String id = store.createSchedule(config);
            store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES));

            Instant nextFire = Instant.now().plus(1, ChronoUnit.DAYS);
            store.markCompleted(id, nextFire);

            var found = store.readSchedule(id);
            assertEquals(FireStatus.PENDING, found.getFireStatus());
            assertTrue(found.isEnabled());
            assertNull(found.getClaimedBy());
            assertNotNull(found.getLastFired());
            assertEquals(0, found.getFailCount());
        }

        @Test
        @DisplayName("markCompleted without nextFire — disables schedule")
        void markCompletedOneShot() throws Exception {
            var config = createCronSchedule("OneShot", "a", "t");
            String id = store.createSchedule(config);
            store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES));

            store.markCompleted(id, null);

            var found = store.readSchedule(id);
            assertFalse(found.isEnabled());
            assertNull(found.getNextFire());
        }

        @Test
        @DisplayName("markFailed — sets FAILED + increments failCount")
        void markFailed() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            String id = store.createSchedule(config);
            store.tryClaim(id, "node-1", Instant.now(), Instant.now().minus(5, ChronoUnit.MINUTES));

            Instant retry = Instant.now().plus(5, ChronoUnit.MINUTES);
            store.markFailed(id, retry);

            var found = store.readSchedule(id);
            assertEquals(FireStatus.FAILED, found.getFireStatus());
            assertEquals(1, found.getFailCount());
            assertNull(found.getClaimedBy());
        }

        @Test
        @DisplayName("markDeadLettered — transitions to DEAD_LETTERED")
        void markDeadLettered() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            String id = store.createSchedule(config);

            store.markDeadLettered(id);

            assertEquals(FireStatus.DEAD_LETTERED, store.readSchedule(id).getFireStatus());
        }

        @Test
        @DisplayName("requeueDeadLetter — resets DEAD_LETTERED to PENDING")
        void requeueDeadLetter() throws Exception {
            var config = createCronSchedule("S", "a", "t");
            String id = store.createSchedule(config);
            store.markDeadLettered(id);

            store.requeueDeadLetter(id);

            var found = store.readSchedule(id);
            assertEquals(FireStatus.PENDING, found.getFireStatus());
            assertEquals(0, found.getFailCount());
        }

        @Test
        @DisplayName("requeueDeadLetter non-DEAD_LETTERED — throws ResourceNotFoundException")
        void requeueNonDeadLettered() throws Exception {
            String id = store.createSchedule(createCronSchedule("S", "a", "t"));
            // Schedule is PENDING, not DEAD_LETTERED
            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> store.requeueDeadLetter(id));
        }
    }

    // ─── findDueSchedules ───────────────────────────────────────

    @Nested
    @DisplayName("findDueSchedules")
    class FindDue {

        @Test
        @DisplayName("returns only due schedules")
        void returnsOnlyDue() throws Exception {
            var due = createCronSchedule("Due", "a", "t");
            due.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            store.createSchedule(due);

            var notDue = createCronSchedule("NotDue", "a", "t");
            notDue.setNextFire(Instant.now().plus(1, ChronoUnit.HOURS));
            store.createSchedule(notDue);

            var disabled = createCronSchedule("Disabled", "a", "t");
            disabled.setEnabled(false);
            disabled.setNextFire(Instant.now().minus(1, ChronoUnit.MINUTES));
            store.createSchedule(disabled);

            List<ScheduleConfiguration> dueList = store.findDueSchedules(
                    Instant.now(),
                    Instant.now().minus(30, ChronoUnit.MINUTES),
                    3);

            assertEquals(1, dueList.size());
            assertEquals("Due", dueList.getFirst().getName());
        }
    }

    // ─── Fire Logs ──────────────────────────────────────────────

    @Nested
    @DisplayName("Fire Logs")
    class FireLogs {

        @Test
        @DisplayName("logFire + readFireLogs round-trip")
        void logAndRead() throws Exception {
            String scheduleId = store.createSchedule(createCronSchedule("S", "a", "t"));

            var log = new ScheduleFireLog(
                    UUID.randomUUID().toString(), scheduleId, "fire_1",
                    Instant.now(), Instant.now(), Instant.now().plus(5, ChronoUnit.SECONDS),
                    "COMPLETED", "node-1", "conv-123", null, 1, 0.05);
            store.logFire(log);

            List<ScheduleFireLog> logs = store.readFireLogs(scheduleId, 10);
            assertEquals(1, logs.size());
            assertEquals(scheduleId, logs.getFirst().scheduleId());
            assertEquals("COMPLETED", logs.getFirst().status());
            assertEquals("conv-123", logs.getFirst().conversationId());
        }

        /**
         * The write-side half of the erasure guarantee: the insert is conditional on
         * the schedule still existing, so a fire that is in flight when a cascade
         * delete or GDPR erasure runs cannot commit its log afterwards. That log would
         * carry a conversationId findable only by a scheduleId that no longer resolves
         * — personal data no erasure path could reach again. Exercises the real
         * {@code WHERE EXISTS} against PostgreSQL, which the mocked-driver unit test
         * cannot.
         */
        @Test
        @DisplayName("logFire — writes no row once the schedule is gone")
        void logFireAfterScheduleDeleted() throws Exception {
            String scheduleId = store.createSchedule(createCronSchedule("Erased", "a", "t"));
            store.deleteSchedule(scheduleId);

            store.logFire(new ScheduleFireLog(
                    UUID.randomUUID().toString(), scheduleId, "fire_late",
                    Instant.now(), Instant.now(), Instant.now(),
                    "COMPLETED", "node-1", "conv-erased", null, 1, 0.0));

            assertTrue(store.readFireLogs(scheduleId, 10).isEmpty(),
                    "a fire log must not outlive the schedule it belongs to");
        }

        @Test
        @DisplayName("readFailedFireLogs — filters FAILED and DEAD_LETTERED")
        void readFailed() throws Exception {
            String scheduleId = store.createSchedule(createCronSchedule("S", "a", "t"));

            store.logFire(new ScheduleFireLog(
                    UUID.randomUUID().toString(), scheduleId, "fire_ok",
                    Instant.now(), Instant.now(), Instant.now(),
                    "COMPLETED", "n1", "c1", null, 1, 0.0));

            store.logFire(new ScheduleFireLog(
                    UUID.randomUUID().toString(), scheduleId, "fire_err",
                    Instant.now(), Instant.now(), null,
                    "FAILED", "n1", null, "NullPointerException", 1, 0.0));

            store.logFire(new ScheduleFireLog(
                    UUID.randomUUID().toString(), scheduleId, "fire_dead",
                    Instant.now(), Instant.now(), null,
                    "DEAD_LETTERED", "n1", null, "Max retries", 3, 0.0));

            List<ScheduleFireLog> failed = store.readFailedFireLogs(10);
            assertEquals(2, failed.size());
            assertTrue(failed.stream().allMatch(f -> "FAILED".equals(f.status()) || "DEAD_LETTERED".equals(f.status())));
        }

        @Test
        @DisplayName("readFireLogs — respects limit")
        void respectsLimit() throws Exception {
            String scheduleId = store.createSchedule(createCronSchedule("S", "a", "t"));
            for (int i = 0; i < 5; i++) {
                store.logFire(new ScheduleFireLog(
                        UUID.randomUUID().toString(), scheduleId, "fire_" + i,
                        Instant.now(), Instant.now(), Instant.now(),
                        "COMPLETED", "n1", "c" + i, null, 1, 0.01));
            }

            assertEquals(3, store.readFireLogs(scheduleId, 3).size());
        }
    }

    // ─── Heartbeat trigger type ─────────────────────────────────

    @Nested
    @DisplayName("Heartbeat trigger")
    class HeartbeatTrigger {

        @Test
        @DisplayName("heartbeat schedule round-trip preserves interval")
        void heartbeatRoundTrip() throws Exception {
            var config = new ScheduleConfiguration();
            config.setName("Health Check");
            config.setAgentId("health-agent");
            config.setTenantId("ops");
            config.setTriggerType(TriggerType.HEARTBEAT);
            config.setHeartbeatIntervalSeconds(300L);
            config.setConversationStrategy("persistent");
            config.setEnabled(true);
            config.setNextFire(Instant.now().plus(5, ChronoUnit.MINUTES));

            String id = store.createSchedule(config);
            var found = store.readSchedule(id);

            assertEquals(TriggerType.HEARTBEAT, found.getTriggerType());
            assertEquals(300L, found.getHeartbeatIntervalSeconds());
            assertEquals("persistent", found.getConversationStrategy());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static ScheduleConfiguration createCronSchedule(String name, String agentId, String tenantId) {
        var config = new ScheduleConfiguration();
        config.setName(name);
        config.setAgentId(agentId);
        config.setTenantId(tenantId);
        config.setTriggerType(TriggerType.CRON);
        config.setCronExpression("0 9 * * MON-FRI");
        config.setConversationStrategy("new");
        config.setEnabled(true);
        config.setNextFire(Instant.now().plus(1, ChronoUnit.DAYS));
        return config;
    }
}
