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
        verify(preparedStatement).setString(5, null); // trigger_type
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

        // then — fire_status is param 11
        verify(preparedStatement).setString(11, FireStatus.PENDING.name());
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
