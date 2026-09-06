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
import org.mockito.InOrder;

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

    /**
     * A configuration update must not touch the fire lifecycle at all.
     * <p>
     * {@code fire_status} and {@code fail_count} used to be written from the
     * caller's object (defaulting to PENDING when absent), which made every PUT a
     * read-modify-write over live state: the REST layer read PENDING, the poller
     * claimed the row, and this UPDATE then wrote PENDING back over the fresh
     * CLAIMED — un-claiming a fire that was still running, so the next poll fired
     * it a second time into the same persistent conversation. Carrying the values
     * over in the REST layer only narrowed the window to milliseconds and did
     * nothing at all for a non-REST caller. The two columns belong to
     * tryClaim/markCompleted/markFailed/setScheduleEnabled/requeueDeadLetter.
     */
    @Test
    void updateSchedule_doesNotWriteTheFireLifecycleColumns() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var config = newScheduleConfig();
        config.setFireStatus(FireStatus.PENDING);
        config.setFailCount(0);

        sut.updateSchedule("sched-1", config);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("fire_status="),
                "fire_status must not be in the UPDATE SET list — a PUT would un-claim a running fire: " + sql.getValue());
        assertFalse(sql.getValue().contains("fail_count="),
                "fail_count is owned by markFailed/markCompleted, not by an edit: " + sql.getValue());
        assertTrue(sql.getValue().contains("next_fire="),
                "next_fire stays: an edited cron or interval legitimately re-arms the schedule");
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

        // fire_status and fail_count left the SET list, so every parameter after
        // next_fire shifted down by two.
        verify(preparedStatement).setString(14, "changed");
        verify(preparedStatement).setString(16, "Europe/Vienna");
        verify(preparedStatement).setString(17, "test");
        verify(preparedStatement).setInt(18, 3);
        verify(preparedStatement).setBoolean(19, true);
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
     * The cascade and the schedule delete were two autocommit statements on two
     * connections. The cascade prevents the orphan that matters (a fire log
     * carrying a conversationId whose schedule is gone), but unbunched it created
     * the opposite one: if the second statement failed the fire history was already
     * gone while the schedule survived and kept firing. One transaction makes the
     * pair all-or-nothing.
     */
    @Test
    void deleteSchedule_runsTheCascadeAndTheDeleteInOneTransaction() throws Exception {
        sut.deleteSchedule("sched-1");

        var inOrder = inOrder(connection);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void deleteSchedule_rollsBackWhenTheScheduleDeleteFails() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("lock timeout"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> sut.deleteSchedule("sched-1"));

        verify(connection).rollback();
        verify(connection, never()).commit();
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
     * The count these two report is the number of SCHEDULE rows removed, not the
     * fire logs the cascade deleted first. The cascade runs on the same connection
     * immediately before, so returning its row count instead would tell the GDPR
     * sweep — which logs the number as evidence of the erasure — a number that has
     * nothing to do with how many schedules were erased.
     */
    @Test
    void deleteSchedulesByUserId_returnsTheScheduleRowCountNotTheCascadeCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(7, 3);

        assertEquals(3, sut.deleteSchedulesByUserId("user-1"),
                "7 fire logs and 3 schedules were removed; the erasure count is 3");
    }

    @Test
    void deleteSchedulesByName_returnsTheScheduleRowCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(4, 2);

        assertEquals(2, sut.deleteSchedulesByName("hitl-timeout-conv-1"));
    }

    /**
     * The cascade and the schedule delete are one transaction, so a failure in
     * either must roll BOTH back — otherwise a failed erasure has already destroyed
     * the fire history of schedules that survive and keep firing.
     */
    @Test
    void deleteWithCascade_rollsBackWhenTheScheduleDeleteFails() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true); // a pooled connection's usual state
        when(preparedStatement.executeUpdate()).thenReturn(5).thenThrow(new SQLException("lock timeout"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteSchedulesByUserId("user-1"));

        assertInstanceOf(SQLException.class, thrown.getCause());
        assertEquals("lock timeout", thrown.getCause().getMessage());
        InOrder ordered = inOrder(connection);
        ordered.verify(connection).setAutoCommit(false);
        ordered.verify(connection).rollback();
        ordered.verify(connection).setAutoCommit(true); // the caller's autocommit is restored
        verify(connection, never()).commit();
    }

    /**
     * A rollback that fails too must not replace the real cause. Rethrowing the
     * rollback failure would report "connection closed" where the actual reason the
     * erasure did not happen was the lock timeout below it.
     */
    @Test
    void deleteWithCascade_rollbackFailureIsSuppressedNotSubstituted() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("lock timeout"));
        doThrow(new SQLException("connection closed")).when(connection).rollback();

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteSchedulesByUserId("user-1"));

        Throwable cause = thrown.getCause();
        assertEquals("lock timeout", cause.getMessage(), "the original failure must stay the cause");
        assertEquals(1, cause.getSuppressed().length);
        assertEquals("connection closed", cause.getSuppressed()[0].getMessage());
    }

    /**
     * A connection handed over with autocommit already off must be given back the
     * same way. Restoring it to {@code true} unconditionally would silently change
     * the transaction semantics of whatever the caller does with it next.
     */
    @Test
    void deleteWithCascade_restoresAutoCommitEvenWhenItWasAlreadyFalse() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        when(preparedStatement.executeUpdate()).thenReturn(1, 1);

        sut.deleteSchedulesByAgentId("agent-1");

        verify(connection).commit();
        verify(connection, never()).setAutoCommit(true);
    }

    // ─── fire-log deletion ──────────────────────────────────────

    @Test
    void deleteFireLogsByScheduleId_deletesByScheduleIdAndReturnsTheCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(12);

        assertEquals(12, sut.deleteFireLogsByScheduleId("sched-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertEquals("DELETE FROM eddi_schedule_fire_logs WHERE schedule_id = ?", sql.getValue());
        verify(preparedStatement).setString(1, "sched-1");
    }

    @Test
    void deleteFireLogsByScheduleId_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteFireLogsByScheduleId("sched-1"));
        assertTrue(thrown.getMessage().contains("sched-1"), thrown.getMessage());
    }

    @Test
    void deleteFireLogsOlderThan_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteFireLogsOlderThan(Instant.parse("2020-01-01T00:00:00Z")));
    }

    /**
     * A schedule whose triggerType is somehow absent must write SQL NULL, not the
     * string "null" and not an NPE. The column is nullable and
     * {@code fromResultSet} already tolerates a null trigger_type.
     */
    @Test
    void updateSchedule_nullTriggerType_bindsSqlNull() throws Exception {
        var config = newScheduleConfig();
        config.setTriggerType(null);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        sut.updateSchedule("sched-1", config);

        verify(preparedStatement).setString(5, null);
    }

    @Test
    void setPersistentConversationId_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.setPersistentConversationId("sched-1", "conv-1"));
        assertTrue(thrown.getMessage().contains("sched-1"), thrown.getMessage());
    }

    /**
     * The single-field write the fire path uses. It must touch
     * persistent_conversation_id (and updated_at) and NOTHING else — writing the
     * whole schedule back from the fire path un-claimed a row that was still
     * firing.
     */
    @Test
    void setPersistentConversationId_writesOnlyThatColumn() throws Exception {
        sut.setPersistentConversationId("sched-1", "conv-9");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertEquals("UPDATE eddi_schedules SET persistent_conversation_id=?, updated_at=? WHERE id=?", sql.getValue());
        verify(preparedStatement).setString(1, "conv-9");
        verify(preparedStatement).setString(3, "sched-1");
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

    /**
     * The retention sweep filters on {@code started_at} alone, and PostgreSQL
     * cannot use a compound index without its leading column — so neither
     * {@code (schedule_id, started_at)} nor {@code (status, started_at)} can serve
     * it. Without a standalone index the hourly prune is a full scan of exactly the
     * table it exists to keep bounded.
     */
    @Test
    void ensureSchema_createsAStandaloneStartedAtIndexForTheRetentionSweep() throws Exception {
        when(resultSet.next()).thenReturn(false);
        sut.readAllSchedules(10);

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(ddl.capture());
        assertTrue(ddl.getAllValues().stream()
                .anyMatch(s -> s.contains("idx_fire_logs_started_at") && s.contains("eddi_schedule_fire_logs (started_at)")),
                "the prune has no index it can use: " + ddl.getAllValues());
    }

    /**
     * The HITL redaction has to be part of the QUERY. Filtering the returned page
     * counted limit/offset over rows a non-admin cannot see, so their first page
     * could come back short or empty while later pages held their own schedules.
     * <p>
     * {@code IS DISTINCT FROM} rather than {@code <>}: a schedule with no metadata
     * yields SQL NULL there, and {@code <>} would drop every one of those rows —
     * which is every ordinary schedule.
     */
    @Test
    void readAllSchedules_excludingHitlTimeouts_filtersInTheQuery() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.readAllSchedules(50, 0, true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("metadata->>'hitlType' IS DISTINCT FROM 'hitl_timeout'"),
                "the redaction must be in the WHERE clause: " + sql.getValue());
        assertTrue(sql.getValue().indexOf("WHERE") < sql.getValue().indexOf("LIMIT"),
                "the filter must precede LIMIT, or paging still counts hidden rows: " + sql.getValue());
    }

    @Test
    void readSchedulesByAgentId_excludingHitlTimeouts_addsTheFilterToTheAgentClause() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.readSchedulesByAgentId("agent-1", 50, 0, true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("agent_id = ? AND metadata->>'hitlType' IS DISTINCT FROM 'hitl_timeout'"),
                "the redaction must AND onto the agent filter: " + sql.getValue());
    }

    @Test
    void readAllSchedules_asAdmin_addsNoRedactionClause() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.readAllSchedules(50, 0, false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("hitlType"), "an admin listing must not be filtered: " + sql.getValue());
    }

    @Test
    void readAllSchedules_paged_ordersDeterministicallyAndBindsOffset() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.readAllSchedules(50, 100, false);

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

    // ─── markSkipped ────────────────────────────────────────────

    /**
     * A skipped fire releases the claim and re-arms the cadence — and the statement
     * must touch nothing else. Incrementing {@code fail_count} here dead-letters a
     * healthy heartbeat during a human pause; clearing it lets a schedule that
     * alternates failing and skipping dodge {@code max-retries} forever. Neither
     * belongs in a skip, and nor does {@code last_fired}: nothing fired.
     */
    @Test
    void markSkipped_reArmsWithoutTouchingTheRetryState() throws Exception {
        // when
        sut.markSkipped("sched-1", Instant.parse("2099-01-01T00:00:00Z"));

        // then
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String sqlText = sql.getValue();
        assertTrue(sqlText.contains("next_fire=?"), "the cadence must be re-armed: " + sqlText);
        assertTrue(sqlText.contains("fire_status='PENDING'"), "the claim must be released: " + sqlText);
        assertTrue(sqlText.contains("claimed_by=NULL"), "the claim owner must be cleared: " + sqlText);
        assertFalse(sqlText.contains("fail_count"), "a skip is neither a failure nor a success: " + sqlText);
        assertFalse(sqlText.contains("last_fired"), "nothing fired, so last_fired must not move: " + sqlText);
        assertFalse(sqlText.contains("next_retry_at"), "a skip does not put the schedule into (or out of) retry: " + sqlText);
        verify(preparedStatement).setLong(1, Instant.parse("2099-01-01T00:00:00Z").toEpochMilli());
        verify(preparedStatement).setString(3, "sched-1");
    }

    @Test
    void markSkipped_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.markSkipped("sched-1", Instant.now()));
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

    /**
     * The write-side half of the erasure guarantee, and the half that actually
     * closes the window.
     * <p>
     * The cascade transaction and the post-commit sweep remove the fire logs that
     * exist when they run, but neither can stop a fire that is mid-flight at that
     * moment from inserting its log afterwards — and an erasure is exactly when a
     * schedule is most likely to be mid-fire. That late row carries the erased
     * user's conversationId and is findable only by a scheduleId that no longer
     * resolves, so no erasure path can ever reach it: a GDPR erasure would report
     * success over personal data it left behind.
     * <p>
     * {@code INSERT ... SELECT ... WHERE EXISTS} makes the row conditional on the
     * schedule being visible to the statement that writes it, so the log is either
     * written while the schedule is still there or not written at all. Without the
     * guard this is an unconditional {@code VALUES} insert that always lands.
     */
    @Test
    void logFire_guardsTheInsertOnTheScheduleStillExisting() throws Exception {
        var log = new ScheduleFireLog("log-1", "sched-erased", "fire-1",
                Instant.now(), Instant.now(), Instant.now(),
                "COMPLETED", "n1", "conv-erased", null, 1, 0.05);

        sut.logFire(log);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("WHERE EXISTS (SELECT 1 FROM eddi_schedules WHERE id = ?)"),
                "the fire-log insert must be conditional on the schedule still existing: " + sql.getValue());
        assertFalse(sql.getValue().contains("VALUES"),
                "an unconditional VALUES insert cannot be guarded: " + sql.getValue());
        // The guard's own parameter, bound last so the twelve column parameters keep
        // the indices logFire_withNullInstants_setsNulls pins.
        verify(preparedStatement).setString(13, "sched-erased");
    }

    /**
     * The guard must not cost the normal path its row: a fire of a schedule that
     * still exists writes exactly as before, with the twelve column parameters at
     * their original indices.
     */
    @Test
    void logFire_normalPathStillWritesTheRow() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var log = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), Instant.now(),
                "COMPLETED", "n1", "conv-1", null, 1, 0.05);

        sut.logFire(log);

        verify(preparedStatement).setString(1, "log-1");
        verify(preparedStatement).setString(2, "sched-1");
        verify(preparedStatement).setString(9, "conv-1");
        verify(preparedStatement).executeUpdate();
    }

    /**
     * Zero rows written is the guard doing its job, not a failure. Throwing here
     * would turn a benign race — the schedule was erased while this fire ran — into
     * an error on the fire path, and {@code ScheduleFireExecutor} would log it as
     * though the store were broken.
     */
    @Test
    void logFire_zeroRowsFromTheGuardIsNotAnError() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);
        var log = new ScheduleFireLog("log-1", "sched-erased", "fire-1",
                Instant.now(), Instant.now(), Instant.now(),
                "COMPLETED", "n1", "conv-erased", null, 1, 0.05);

        assertDoesNotThrow(() -> sut.logFire(log));
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

    /**
     * The transaction removes the logs of the schedules that existed when it
     * started. It cannot stop the fire that is running RIGHT NOW on one of those
     * schedules from committing its log a moment later — and an erasure is exactly
     * when a schedule is most likely to be mid-fire. That log then carries the
     * erased user's conversationId with no schedule left to find it by, which is
     * the orphan the whole cascade exists to prevent.
     * <p>
     * Hence the second pass over the ids resolved inside the transaction, after the
     * commit. MongoScheduleStore reaches the same guarantee the same way.
     */
    @Test
    void deleteSchedulesByUserId_sweepsTheFireLogsAgainAfterTheSchedulesAreGone() throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("sched-erased");

        sut.deleteSchedulesByUserId("user-1");

        InOrder ordered = inOrder(connection, preparedStatement);
        ordered.verify(connection).commit();
        ordered.verify(connection).prepareStatement("DELETE FROM eddi_schedule_fire_logs WHERE schedule_id = ?");
        ordered.verify(preparedStatement).setString(1, "sched-erased");
        ordered.verify(preparedStatement).executeBatch();
    }

    /**
     * The single-schedule path needs the second pass MORE than the bulk ones — it
     * is the one an operator uses to remove a schedule that is firing right now —
     * and it knows its id without resolving anything.
     */
    @Test
    void deleteSchedule_sweepsTheFireLogsAgainAfterTheScheduleIsGone() throws Exception {
        sut.deleteSchedule("sched-1");

        InOrder ordered = inOrder(connection, preparedStatement);
        ordered.verify(connection).commit();
        ordered.verify(connection).prepareStatement("DELETE FROM eddi_schedule_fire_logs WHERE schedule_id = ?");
        ordered.verify(preparedStatement).setString(1, "sched-1");
        ordered.verify(preparedStatement).executeBatch();
    }

    /**
     * A failing sweep must be reported, not swallowed: the schedules are already
     * gone at that point, so the caller (a GDPR erasure above all) has to be able
     * to tell "the erasure did not happen" from "the schedules went but their fire
     * logs may not have".
     */
    @Test
    void deleteSchedule_aFailingPostDeleteSweepIsReportedNotSwallowed() throws Exception {
        when(preparedStatement.executeBatch()).thenThrow(new SQLException("connection reset"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> sut.deleteSchedule("sched-1"));

        assertTrue(thrown.getMessage().contains("sweep"),
                "the second pass must be distinguishable from the first in the error: " + thrown.getMessage());
        verify(connection).commit();
    }

    // ─── outcome writes are fenced by the claim's fireId ────────

    /**
     * Lease stealing is deliberate, so a fire that overran its lease and a
     * replacement fire can be in flight at once. An outcome write keyed on
     * {@code id} alone lets the loser release or fail the WINNER's claim on its way
     * out — the row goes back to PENDING with a fire still running, and the next
     * poll starts a third copy. The extra {@code fire_id} predicate makes the stale
     * UPDATE match zero rows instead.
     */
    @Test
    void markCompleted_fencesTheUpdateOnTheClaimsFireId() throws Exception {
        sut.markCompleted("sched-1", "sched-1_fire-a", Instant.now().plusSeconds(60));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND fire_id=?"), "the outcome write must be fenced: " + sql.getValue());
        verify(preparedStatement).setString(5, "sched-1_fire-a");
    }

    @Test
    void markCompleted_oneShot_fencesTheUpdateOnTheClaimsFireId() throws Exception {
        sut.markCompleted("sched-1", "sched-1_fire-a", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND fire_id=?"), "the outcome write must be fenced: " + sql.getValue());
        verify(preparedStatement).setString(4, "sched-1_fire-a");
    }

    @Test
    void markFailed_fencesTheUpdateOnTheClaimsFireId() throws Exception {
        sut.markFailed("sched-1", "sched-1_fire-a", Instant.now().plusSeconds(60));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND fire_id=?"), "the outcome write must be fenced: " + sql.getValue());
        verify(preparedStatement).setString(4, "sched-1_fire-a");
    }

    @Test
    void markSkipped_fencesTheUpdateOnTheClaimsFireId() throws Exception {
        sut.markSkipped("sched-1", "sched-1_fire-a", Instant.parse("2099-01-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND fire_id=?"), "the outcome write must be fenced: " + sql.getValue());
        verify(preparedStatement).setString(4, "sched-1_fire-a");
    }

    @Test
    void markDeadLettered_fencesTheUpdateOnTheClaimsFireId() throws Exception {
        sut.markDeadLettered("sched-1", "sched-1_fire-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("AND fire_id=?"), "the last attempt must be fenced too: " + sql.getValue());
        verify(preparedStatement).setString(3, "sched-1_fire-a");
    }

    /**
     * The unfenced overload is for the one caller that holds no claim —
     * {@code dismissDeadLetter}, where no fire is running by definition. It must
     * still match by id alone, or dismissing a dead letter would silently do
     * nothing.
     */
    @Test
    void markCompleted_withoutAFireId_matchesByScheduleIdAlone() throws Exception {
        sut.markCompleted("sched-1", Instant.now().plusSeconds(60));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("fire_id=?"), "an unfenced write must not add a predicate it cannot bind: " + sql.getValue());
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
