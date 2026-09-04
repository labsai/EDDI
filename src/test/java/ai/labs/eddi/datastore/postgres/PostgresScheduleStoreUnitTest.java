/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresScheduleStore} with mocked JDBC connections.
 * <p>
 * Targets error paths, edge cases in fromResultSet, and branches not covered by
 * the integration test.
 */
class PostgresScheduleStoreUnitTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    @SuppressWarnings("unchecked")
    private Instance<DataSource> dataSourceInstance;
    private IJsonSerialization jsonSerialization;
    private PostgresScheduleStore sut;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        dataSourceInstance = mock(Instance.class);
        jsonSerialization = mock(IJsonSerialization.class);

        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        sut = new PostgresScheduleStore(dataSourceInstance, jsonSerialization, 100);
    }

    // ─── createSchedule ─────────────────────────────────────────

    @Test
    void createSchedule_success_returnsId() throws Exception {
        // given
        var config = newScheduleConfig();

        // when
        String id = sut.createSchedule(config);

        // then
        assertNotNull(id);
        assertNotNull(config.getId());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void createSchedule_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("Duplicate"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.createSchedule(newScheduleConfig()));
    }

    @Test
    void createSchedule_nullTriggerType_setsNull() throws Exception {
        // given
        var config = newScheduleConfig();
        config.setTriggerType(null);

        // when
        sut.createSchedule(config);

        // then
        verify(preparedStatement).setString(6, null); // trigger_type (param 5 is user_id)
    }

    /**
     * GDPR erasure regression. PostgresScheduleStore did not persist userId at all
     * — the column did not exist and nothing referenced it — so a schedule read
     * back always had a null userId, the erasure scan matched nothing, and the
     * sweep reported success while the user's schedules kept firing. Mongo was
     * unaffected because it stores the whole document, which is why the Mongo-side
     * fix looked complete. These pin the column on both write paths.
     */
    @Test
    void createSchedule_persistsUserIdSoErasureCanFindIt() throws Exception {
        var config = newScheduleConfig();
        config.setUserId("user-42");

        sut.createSchedule(config);

        verify(preparedStatement).setString(5, "user-42");
    }

    @Test
    void updateSchedule_persistsUserId() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var config = newScheduleConfig();
        config.setUserId("user-42");

        sut.updateSchedule("sched-1", config);

        verify(preparedStatement).setString(4, "user-42");
    }

    // ─── readSchedule ───────────────────────────────────────────

    @Test
    void readSchedule_found_returnsConfig() throws Exception {
        // given
        setupResultSetForSchedule();
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then
        assertNotNull(result);
        assertEquals("sched-1", result.getId());
        assertEquals("Test Schedule", result.getName());
        assertEquals("agent1", result.getAgentId());
        assertEquals(TriggerType.CRON, result.getTriggerType());
        assertEquals(FireStatus.PENDING, result.getFireStatus());
    }

    @Test
    void readSchedule_notFound_throwsResourceNotFoundException() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when/then
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> sut.readSchedule("missing"));
    }

    @Test
    void readSchedule_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.readSchedule("sched-1"));
    }

    // ─── updateSchedule ─────────────────────────────────────────

    @Test
    void updateSchedule_notFound_throwsResourceNotFoundException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when/then
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> sut.updateSchedule("missing", newScheduleConfig()));
    }

    @Test
    void updateSchedule_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.updateSchedule("sched-1", newScheduleConfig()));
    }

    @Test
    void updateSchedule_success_updatesTimestamp() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var config = newScheduleConfig();

        // when
        sut.updateSchedule("sched-1", config);

        // then
        assertNotNull(config.getUpdatedAt());
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void updateSchedule_nullFireStatus_defaultsToPending() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var config = newScheduleConfig();
        config.setFireStatus(null);

        // when
        sut.updateSchedule("sched-1", config);

        // then — fire_status is param 12 (user_id was inserted at 4)
        verify(preparedStatement).setString(12, FireStatus.PENDING.name());
    }

    // ─── schedule payload columns ───────────────────────────────
    //
    // eddi_schedules had no column at all for message, timeZone, environment,
    // agentVersion, oneTimeAt, persistentConversationId, createdBy or
    // allowSelfScheduling. Every one of them was dropped on write and read back
    // null on PostgreSQL, while MongoDB (which serializes the whole document) kept
    // them — so the same schedule behaved differently on the two backends: a CRON
    // fire ran with a null message, a Europe/Vienna cron was recomputed in UTC, a
    // test-environment schedule fired against production, and
    // conversationStrategy=persistent started a fresh conversation on every fire.

    @Test
    void createSchedule_persistsMessageTimeZoneEnvironmentAndTheRestOfThePayload() throws Exception {
        var config = newScheduleConfig();
        config.setMessage("generate the daily report");
        config.setTimeZone("Europe/Vienna");
        config.setEnvironment("test");
        config.setAgentVersion(7);
        config.setOneTimeAt("2026-09-03T10:00:00Z");
        config.setPersistentConversationId("conv-1");
        config.setCreatedBy("alice");
        config.setAllowSelfScheduling(true);

        sut.createSchedule(config);

        verify(preparedStatement).setString(17, "generate the daily report");
        verify(preparedStatement).setString(18, "2026-09-03T10:00:00Z");
        verify(preparedStatement).setString(19, "Europe/Vienna");
        verify(preparedStatement).setString(20, "test");
        verify(preparedStatement).setInt(21, 7);
        verify(preparedStatement).setString(22, "conv-1");
        verify(preparedStatement).setString(23, "alice");
        verify(preparedStatement).setBoolean(24, true);
    }

    @Test
    void updateSchedule_persistsTheEditablePayloadFields() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var config = newScheduleConfig();
        config.setMessage("changed");
        config.setTimeZone("Europe/Vienna");
        config.setEnvironment("test");
        config.setAgentVersion(3);
        config.setAllowSelfScheduling(true);

        sut.updateSchedule("sched-1", config);

        verify(preparedStatement).setString(16, "changed");
        verify(preparedStatement).setString(18, "Europe/Vienna");
        verify(preparedStatement).setString(19, "test");
        verify(preparedStatement).setInt(20, 3);
        verify(preparedStatement).setBoolean(21, true);
    }

    /**
     * The claim/provenance columns are owned by createSchedule and by the
     * claim/completion methods — an ordinary PUT must not be able to write them.
     * Writing persistent_conversation_id from here in particular un-claimed a fire
     * that was still running.
     */
    @Test
    void updateSchedule_doesNotWriteProvenanceOrClaimColumns() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        sut.updateSchedule("sched-1", newScheduleConfig());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("created_at="), "created_at must not be in the UPDATE SET list");
        assertFalse(sql.getValue().contains("created_by="), "created_by must not be in the UPDATE SET list");
        assertFalse(sql.getValue().contains("last_fired="), "last_fired must not be in the UPDATE SET list");
        assertFalse(sql.getValue().contains("claimed_by="), "claimed_by must not be in the UPDATE SET list");
        assertFalse(sql.getValue().contains("persistent_conversation_id="),
                "persistent_conversation_id is owned by setPersistentConversationId");
    }

    @Test
    void setPersistentConversationId_writesThatOneFieldOnly() throws Exception {
        sut.setPersistentConversationId("sched-1", "conv-42");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().startsWith("UPDATE eddi_schedules SET persistent_conversation_id=?"));
        assertFalse(sql.getValue().contains("fire_status"), "must not touch the claim state mid-fire");
        assertFalse(sql.getValue().contains("next_fire"), "must not touch the arming mid-fire");
        verify(preparedStatement).setString(1, "conv-42");
        verify(preparedStatement).setString(3, "sched-1");
    }

    @Test
    void readSchedule_readsBackTheFullPayload() throws Exception {
        setupResultSetForSchedule();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("message")).thenReturn("generate the daily report");
        when(resultSet.getString("time_zone")).thenReturn("Europe/Vienna");
        when(resultSet.getString("environment")).thenReturn("test");
        when(resultSet.getString("one_time_at")).thenReturn("2026-09-03T10:00:00Z");
        when(resultSet.getInt("agent_version")).thenReturn(7);
        when(resultSet.getString("persistent_conversation_id")).thenReturn("conv-1");
        when(resultSet.getString("created_by")).thenReturn("alice");
        when(resultSet.getBoolean("allow_self_scheduling")).thenReturn(true);

        ScheduleConfiguration result = sut.readSchedule("sched-1");

        assertEquals("generate the daily report", result.getMessage());
        assertEquals("Europe/Vienna", result.getTimeZone());
        assertEquals("test", result.getEnvironment());
        assertEquals("2026-09-03T10:00:00Z", result.getOneTimeAt());
        assertEquals(7, result.getAgentVersion());
        assertEquals("conv-1", result.getPersistentConversationId());
        assertEquals("alice", result.getCreatedBy());
        assertTrue(result.isAllowSelfScheduling());
    }

    /**
     * Enabling clears the failure state whether or not a nextFire could be
     * computed. Gating that reset on a non-null nextFire left a re-enabled schedule
     * stuck in FAILED/DEAD_LETTERED with a non-zero failCount, so it could never be
     * claimed again.
     */
    @Test
    void setScheduleEnabled_withoutNextFire_stillClearsTheFailureState() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        sut.setScheduleEnabled("sched-1", true, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("fire_status=?"));
        assertTrue(sql.getValue().contains("fail_count=0"));
        assertTrue(sql.getValue().contains("next_retry_at=NULL"));
        verify(preparedStatement).setString(2, FireStatus.PENDING.name());
    }

    @Test
    void deleteSchedule_cascadesTheFireLogs() throws Exception {
        sut.deleteSchedule("sched-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("DELETE FROM eddi_schedule_fire_logs")),
                "fire logs must be deleted with their schedule — nothing can find them afterwards");
    }

    /**
     * The three bulk delete paths cascade too, and GDPR erasure is the reason the
     * cascade exists: every fire log carries a conversationId of the user being
     * erased, and once the schedule row is gone nothing can find those logs again.
     * An erasure that reports success while leaving them behind is a compliance
     * failure. Only the single-schedule path was pinned; these cover the rest.
     */
    @Test
    void deleteSchedulesByUserId_cascadesTheFireLogsBeforeTheSchedules() throws Exception {
        sut.deleteSchedulesByUserId("user-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("DELETE FROM eddi_schedule_fire_logs")
                && s.contains("SELECT id FROM eddi_schedules WHERE user_id = ?")),
                "an erased user's fire logs must go with their schedules: " + sql.getAllValues());
        // Ordering matters: resolving the ids after the schedules are gone finds none.
        int cascade = indexOfSqlContaining(sql.getAllValues(), "eddi_schedule_fire_logs");
        int scheduleDelete = indexOfSqlContaining(sql.getAllValues(), "DELETE FROM eddi_schedules");
        assertTrue(cascade < scheduleDelete,
                "the cascade must run BEFORE the schedules are deleted: " + sql.getAllValues());
    }

    @Test
    void deleteSchedulesByAgentId_cascadesTheFireLogs() throws Exception {
        sut.deleteSchedulesByAgentId("agent-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("DELETE FROM eddi_schedule_fire_logs")
                && s.contains("SELECT id FROM eddi_schedules WHERE agent_id = ?")),
                "a deleted agent must not leave orphaned fire logs: " + sql.getAllValues());
    }

    @Test
    void deleteSchedulesByName_cascadesTheFireLogs() throws Exception {
        sut.deleteSchedulesByName("hitl-timeout-conv-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("DELETE FROM eddi_schedule_fire_logs")
                && s.contains("SELECT id FROM eddi_schedules WHERE name = ?")),
                "a resolved HITL pause must not leave its fire log behind: " + sql.getAllValues());
    }

    /**
     * The eight payload columns never existed on this table, so a deployment
     * upgrading into this build has to gain them — the same idempotent
     * {@code ADD COLUMN IF NOT EXISTS} pattern the metadata and user_id upgrades
     * already use. Without them a schedule reads back with a null message, a null
     * timeZone and a null environment, and {@code conversationStrategy=persistent}
     * can never work. A fresh CREATE TABLE declaring the columns is not enough:
     * every existing install already has the table.
     */
    @Test
    void ensureSchema_addsEveryMissingPayloadColumnToAPreExistingTable() throws Exception {
        // Any store call triggers the one-time ensureSchema.
        when(resultSet.next()).thenReturn(false);
        sut.readAllSchedules(10);

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(ddl.capture());
        for (String column : List.of("agent_version", "environment", "one_time_at", "time_zone", "message",
                "persistent_conversation_id", "allow_self_scheduling", "created_by")) {
            assertTrue(ddl.getAllValues().stream().anyMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS " + column)),
                    column + " has no idempotent upgrade — an existing install would never gain it");
        }
        // A NOT NULL column added to a populated table needs a DEFAULT, or the ALTER
        // fails on every row already there.
        assertTrue(ddl.getAllValues().stream()
                .anyMatch(s -> s.contains("allow_self_scheduling BOOLEAN NOT NULL DEFAULT false")),
                "a NOT NULL upgrade column must carry a DEFAULT: " + ddl.getAllValues());
    }

    private static int indexOfSqlContaining(List<String> statements, String needle) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(needle)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    @Test
    void deleteFireLogsOlderThan_deletesByStartedAt() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);
        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);

        assertEquals(3, sut.deleteFireLogsOlderThan(cutoff));

        verify(preparedStatement).setLong(1, cutoff.toEpochMilli());
    }

    @Test
    void readAllSchedules_paged_ordersDeterministicallyAndBindsOffset() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.readAllSchedules(50, 100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("ORDER BY created_at DESC NULLS LAST, id DESC"),
                "paging without a deterministic order skips and repeats rows");
        verify(preparedStatement).setInt(1, 50);
        verify(preparedStatement).setInt(2, 100);
    }

    // ─── deleteSchedule ─────────────────────────────────────────

    @Test
    void deleteSchedule_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteSchedule("sched-1"));
    }

    // ─── deleteSchedulesByAgentId ───────────────────────────────

    @Test
    void deleteSchedulesByAgentId_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteSchedulesByAgentId("agent1"));
    }

    @Test
    void deleteSchedulesByAgentId_noneDeleted_returnsZero() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        int count = sut.deleteSchedulesByAgentId("non-existent-agent");

        // then
        assertEquals(0, count);
    }

    // ─── setScheduleEnabled ─────────────────────────────────────

    @Test
    void setScheduleEnabled_enableWithNextFire_setsParams() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // when
        sut.setScheduleEnabled("sched-1", true, Instant.now().plus(1, ChronoUnit.HOURS));

        // then — should use the longer SQL with nextFire
        verify(preparedStatement).setBoolean(1, true);
        verify(preparedStatement).setString(3, FireStatus.PENDING.name());
        verify(preparedStatement).setString(5, "sched-1");
    }

    @Test
    void setScheduleEnabled_disableWithoutNextFire_setsParams() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // when
        sut.setScheduleEnabled("sched-1", false, null);

        // then — should use the shorter SQL
        verify(preparedStatement).setBoolean(1, false);
        verify(preparedStatement).setString(3, "sched-1");
    }

    @Test
    void setScheduleEnabled_notFound_throwsResourceNotFoundException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when/then
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> sut.setScheduleEnabled("missing", true, Instant.now()));
    }

    @Test
    void setScheduleEnabled_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.setScheduleEnabled("sched-1", true, Instant.now()));
    }

    // ─── tryClaim ───────────────────────────────────────────────

    @Test
    void tryClaim_success_returnsTrue() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // when
        boolean claimed = sut.tryClaim("sched-1", "node-1", Instant.now(), Instant.now().minusSeconds(300));

        // then
        assertTrue(claimed);
    }

    @Test
    void tryClaim_alreadyClaimed_returnsFalse() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        boolean claimed = sut.tryClaim("sched-1", "node-2", Instant.now(), Instant.now().minusSeconds(300));

        // then
        assertFalse(claimed);
    }

    @Test
    void tryClaim_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.tryClaim("sched-1", "node-1", Instant.now(), Instant.now().minusSeconds(300)));
    }

    // ─── markCompleted ──────────────────────────────────────────

    @Test
    void markCompleted_withNextFire_reschedules() throws Exception {
        // given
        Instant next = Instant.now().plus(1, ChronoUnit.DAYS);

        // when
        sut.markCompleted("sched-1", next);

        // then — with nextFire: params are lastFired(1), nextFire(2), updatedAt(3),
        // id(4)
        verify(preparedStatement).setString(4, "sched-1");
    }

    @Test
    void markCompleted_withoutNextFire_disables() throws Exception {
        // when
        sut.markCompleted("sched-1", null);

        // then — without nextFire: params are lastFired(1), updatedAt(2), id(3)
        verify(preparedStatement).setString(3, "sched-1");
    }

    @Test
    void markCompleted_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.markCompleted("sched-1", Instant.now()));
    }

    // ─── markFailed ─────────────────────────────────────────────

    @Test
    void markFailed_setsRetryAt() throws Exception {
        // given
        Instant retry = Instant.now().plus(5, ChronoUnit.MINUTES);

        // when
        sut.markFailed("sched-1", retry);

        // then
        verify(preparedStatement).setLong(1, retry.toEpochMilli());
        verify(preparedStatement).setString(3, "sched-1");
    }

    @Test
    void markFailed_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.markFailed("sched-1", Instant.now()));
    }

    // ─── markDeadLettered ───────────────────────────────────────

    @Test
    void markDeadLettered_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.markDeadLettered("sched-1"));
    }

    // ─── requeueDeadLetter ──────────────────────────────────────

    @Test
    void requeueDeadLetter_notDeadLettered_throwsResourceNotFoundException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when/then
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> sut.requeueDeadLetter("sched-1"));
    }

    @Test
    void requeueDeadLetter_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.requeueDeadLetter("sched-1"));
    }

    @Test
    void requeueDeadLetter_success_resets() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(1);

        // when
        sut.requeueDeadLetter("sched-1");

        // then
        verify(preparedStatement).setString(3, "sched-1");
    }

    // ─── findDueSchedules ───────────────────────────────────────

    @Test
    void findDueSchedules_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.findDueSchedules(Instant.now(),
                        Instant.now().minus(30, ChronoUnit.MINUTES), 3));
    }

    // ─── readAllSchedules ───────────────────────────────────────

    @Test
    void readAllSchedules_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.readAllSchedules(10));
    }

    // ─── readSchedulesByAgentId ─────────────────────────────────

    @Test
    void readSchedulesByAgentId_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.readSchedulesByAgentId("agent1"));
    }

    // ─── logFire ────────────────────────────────────────────────

    @Test
    void logFire_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));
        var log = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), Instant.now(),
                "COMPLETED", "n1", "conv-1", null, 1, 0.05);

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.logFire(log));
    }

    @Test
    void logFire_withNullInstants_setsNulls() throws Exception {
        // given — null fireTime, startedAt, completedAt
        var log = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                null, null, null,
                "PENDING", "n1", null, "error msg", 1, 0.0);

        // when
        sut.logFire(log);

        // then — nullableEpoch should set SQL NULL for nulls
        verify(preparedStatement).setNull(4, Types.BIGINT); // fireTime
        verify(preparedStatement).setNull(5, Types.BIGINT); // startedAt
        verify(preparedStatement).setNull(6, Types.BIGINT); // completedAt
        // cost=0.0 should also set NULL (via setNullableDouble)
        verify(preparedStatement).setNull(12, Types.DOUBLE);
    }

    // ─── readFireLogs ───────────────────────────────────────────

    @Test
    void readFireLogs_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.readFireLogs("sched-1", 10));
    }

    // ─── readFailedFireLogs ─────────────────────────────────────

    @Test
    void readFailedFireLogs_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.readFailedFireLogs(10));
    }

    // ─── fromResultSet edge cases ───────────────────────────────

    @Test
    void fromResultSet_nullTriggerType_doesNotSetTriggerType() throws Exception {
        // given
        setupResultSetForSchedule();
        doReturn(null).when(resultSet).getString("trigger_type");
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then
        assertEquals(TriggerType.CRON, result.getTriggerType());
    }

    @Test
    void fromResultSet_invalidTriggerType_ignoredGracefully() throws Exception {
        // given
        setupResultSetForSchedule();
        doReturn("INVALID_TYPE").when(resultSet).getString("trigger_type");
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then — invalid trigger type is silently ignored, keeping default
        assertEquals(TriggerType.CRON, result.getTriggerType());
    }

    @Test
    void fromResultSet_invalidFireStatus_ignoredGracefully() throws Exception {
        // given
        setupResultSetForSchedule();
        doReturn("UNKNOWN_STATUS").when(resultSet).getString("fire_status");
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then — invalid fire status silently ignored, keeping default
        assertEquals(FireStatus.PENDING, result.getFireStatus());
    }

    @Test
    void fromResultSet_nullFireStatus_keepsDefault() throws Exception {
        // given
        setupResultSetForSchedule();
        doReturn(null).when(resultSet).getString("fire_status");
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then — null fire status keeps default PENDING
        assertEquals(FireStatus.PENDING, result.getFireStatus());
    }

    @Test
    void fromResultSet_nullHeartbeatInterval_keepsNull() throws Exception {
        // given
        setupResultSetForSchedule();
        when(resultSet.getLong("heartbeat_interval_seconds")).thenReturn(0L);
        when(resultSet.wasNull()).thenReturn(true, false, false, false, false, false, false, false, false, false);
        when(resultSet.next()).thenReturn(true);

        // when
        ScheduleConfiguration result = sut.readSchedule("sched-1");

        // then
        assertNull(result.getHeartbeatIntervalSeconds());
    }

    // ─── ensureSchema failure ───────────────────────────────────

    @Test
    void ensureSchema_sqlException_logsButDoesNotThrow() throws Exception {
        // given
        DataSource failDs = mock(DataSource.class);
        when(failDs.getConnection()).thenThrow(new SQLException("Schema error"));

        @SuppressWarnings("unchecked")
        Instance<DataSource> failInstance = mock(Instance.class);
        when(failInstance.get()).thenReturn(failDs);

        var freshStore = new PostgresScheduleStore(failInstance, jsonSerialization, 100);

        // when/then — ensureSchema catches the error, but subsequent DB calls fail
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> freshStore.readAllSchedules(10));
    }

    // ─── Helpers ────────────────────────────────────────────────

    private ScheduleConfiguration newScheduleConfig() {
        var config = new ScheduleConfiguration();
        config.setName("Test Schedule");
        config.setAgentId("agent1");
        config.setTenantId("tenant1");
        config.setTriggerType(TriggerType.CRON);
        config.setCronExpression("0 9 * * MON-FRI");
        config.setConversationStrategy("new");
        config.setEnabled(true);
        config.setNextFire(Instant.now().plus(1, ChronoUnit.DAYS));
        config.setFireStatus(FireStatus.PENDING);
        return config;
    }

    private void setupResultSetForSchedule() throws Exception {
        when(resultSet.getString("id")).thenReturn("sched-1");
        when(resultSet.getString("name")).thenReturn("Test Schedule");
        when(resultSet.getString("agent_id")).thenReturn("agent1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant1");
        when(resultSet.getString("trigger_type")).thenReturn("CRON");
        when(resultSet.getString("cron_expression")).thenReturn("0 9 * * MON-FRI");
        when(resultSet.getLong("heartbeat_interval_seconds")).thenReturn(0L);
        when(resultSet.wasNull()).thenReturn(true);
        when(resultSet.getString("conversation_strategy")).thenReturn("new");
        when(resultSet.getDouble("max_cost_per_fire")).thenReturn(0.0);
        when(resultSet.getBoolean("enabled")).thenReturn(true);
        when(resultSet.getString("fire_status")).thenReturn("PENDING");
        when(resultSet.getString("claimed_by")).thenReturn(null);
        when(resultSet.getString("fire_id")).thenReturn(null);
        when(resultSet.getInt("fail_count")).thenReturn(0);

        long nowMs = Instant.now().toEpochMilli();
        // For instantFromEpoch: getLong then wasNull
        when(resultSet.getLong("next_fire")).thenReturn(nowMs);
        when(resultSet.getLong("last_fired")).thenReturn(0L);
        when(resultSet.getLong("claimed_at")).thenReturn(0L);
        when(resultSet.getLong("next_retry_at")).thenReturn(0L);
        when(resultSet.getLong("created_at")).thenReturn(nowMs);
        when(resultSet.getLong("updated_at")).thenReturn(nowMs);
    }
}
