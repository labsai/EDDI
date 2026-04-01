package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IScheduleStore}.
 * <p>
 * Uses {@code SELECT ... FOR UPDATE SKIP LOCKED} for atomic CAS claiming,
 * ensuring exactly-one-instance execution in clustered deployments.
 * <p>
 * Activated via {@code @DefaultBean}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresScheduleStore implements IScheduleStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresScheduleStore.class);

    private static final String CREATE_SCHEDULES_TABLE = """
            CREATE TABLE IF NOT EXISTS eddi_schedules (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(512),
                agent_id VARCHAR(255),
                tenant_id VARCHAR(255),
                trigger_type VARCHAR(64),
                cron_expression VARCHAR(128),
                heartbeat_interval_seconds BIGINT,
                conversation_strategy VARCHAR(64),
                max_cost_per_fire DOUBLE PRECISION,
                enabled BOOLEAN NOT NULL DEFAULT false,
                next_fire BIGINT,
                last_fired BIGINT,
                fire_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                claimed_by VARCHAR(128),
                claimed_at BIGINT,
                fire_id VARCHAR(255),
                fail_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at BIGINT,
                created_at BIGINT,
                updated_at BIGINT
            )
            """;

    private static final String CREATE_FIRE_LOGS_TABLE = """
            CREATE TABLE IF NOT EXISTS eddi_schedule_fire_logs (
                id VARCHAR(255) PRIMARY KEY,
                schedule_id VARCHAR(64) NOT NULL,
                fire_id VARCHAR(255),
                fire_time BIGINT,
                started_at BIGINT,
                completed_at BIGINT,
                status VARCHAR(32),
                instance_id VARCHAR(128),
                conversation_id VARCHAR(255),
                error_message TEXT,
                attempt_number INTEGER NOT NULL DEFAULT 1,
                cost DOUBLE PRECISION
            )
            """;

    private static final String CREATE_INDEXES = """
            CREATE INDEX IF NOT EXISTS idx_schedules_due ON eddi_schedules (enabled, next_fire, fire_status);
            CREATE INDEX IF NOT EXISTS idx_schedules_agent ON eddi_schedules (agent_id);
            CREATE INDEX IF NOT EXISTS idx_schedules_tenant ON eddi_schedules (tenant_id);
            CREATE INDEX IF NOT EXISTS idx_fire_logs_schedule ON eddi_schedule_fire_logs (schedule_id, started_at DESC);
            CREATE INDEX IF NOT EXISTS idx_fire_logs_status ON eddi_schedule_fire_logs (status, started_at DESC);
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresScheduleStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_SCHEDULES_TABLE);
            stmt.execute(CREATE_FIRE_LOGS_TABLE);
            for (String idx : CREATE_INDEXES.split(";")) {
                String trimmed = idx.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            schemaInitialized = true;
            LOGGER.info("PostgresScheduleStore schema initialized");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize schedule tables", e);
        }
    }

    // ========================= CRUD =========================

    @Override
    public String createSchedule(ScheduleConfiguration schedule) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String id = UUID.randomUUID().toString();
        schedule.setId(id);
        Instant now = Instant.now();
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);

        String sql = """
                INSERT INTO eddi_schedules (id, name, agent_id, tenant_id, trigger_type, cron_expression,
                    heartbeat_interval_seconds, conversation_strategy, max_cost_per_fire,
                    enabled, next_fire, fire_status, fail_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, schedule.getName());
            ps.setString(3, schedule.getAgentId());
            ps.setString(4, schedule.getTenantId());
            ps.setString(5, schedule.getTriggerType() != null ? schedule.getTriggerType().name() : null);
            ps.setString(6, schedule.getCronExpression());
            setNullableLong(ps, 7, schedule.getHeartbeatIntervalSeconds());
            ps.setString(8, schedule.getConversationStrategy());
            setNullableDouble(ps, 9, schedule.getMaxCostPerFire());
            ps.setBoolean(10, schedule.isEnabled());
            setNullableEpoch(ps, 11, schedule.getNextFire());
            ps.setString(12, FireStatus.PENDING.name());
            setNullableEpoch(ps, 13, now);
            setNullableEpoch(ps, 14, now);
            ps.executeUpdate();
            LOGGER.infof("Created schedule '%s' (id=%s, type=%s) for Agent %s", schedule.getName(), id, schedule.getTriggerType(),
                    schedule.getAgentId());
            return id;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create schedule", e);
        }
    }

    @Override
    public ScheduleConfiguration readSchedule(String scheduleId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM eddi_schedules WHERE id = ?")) {
            ps.setString(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return fromResultSet(rs);
                }
                throw new IResourceStore.ResourceNotFoundException("Schedule with id=" + scheduleId + " not found");
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read schedule " + scheduleId, e);
        }
    }

    @Override
    public void updateSchedule(String scheduleId, ScheduleConfiguration schedule)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        schedule.setUpdatedAt(Instant.now());
        String sql = """
                UPDATE eddi_schedules SET name=?, agent_id=?, tenant_id=?, trigger_type=?, cron_expression=?,
                    heartbeat_interval_seconds=?, conversation_strategy=?, max_cost_per_fire=?,
                    enabled=?, next_fire=?, fire_status=?, fail_count=?, updated_at=?
                WHERE id=?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schedule.getName());
            ps.setString(2, schedule.getAgentId());
            ps.setString(3, schedule.getTenantId());
            ps.setString(4, schedule.getTriggerType() != null ? schedule.getTriggerType().name() : null);
            ps.setString(5, schedule.getCronExpression());
            setNullableLong(ps, 6, schedule.getHeartbeatIntervalSeconds());
            ps.setString(7, schedule.getConversationStrategy());
            setNullableDouble(ps, 8, schedule.getMaxCostPerFire());
            ps.setBoolean(9, schedule.isEnabled());
            setNullableEpoch(ps, 10, schedule.getNextFire());
            ps.setString(11, schedule.getFireStatus() != null ? schedule.getFireStatus().name() : FireStatus.PENDING.name());
            ps.setInt(12, schedule.getFailCount());
            setNullableEpoch(ps, 13, schedule.getUpdatedAt());
            ps.setString(14, scheduleId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceNotFoundException("Schedule with id=" + scheduleId + " not found");
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update schedule " + scheduleId, e);
        }
    }

    @Override
    public void setScheduleEnabled(String scheduleId, boolean enabled, Instant nextFire)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = enabled && nextFire != null
                ? "UPDATE eddi_schedules SET enabled=?, next_fire=?, fire_status=?, fail_count=0, updated_at=? WHERE id=?"
                : "UPDATE eddi_schedules SET enabled=?, updated_at=? WHERE id=?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = Instant.now().toEpochMilli();
            if (enabled && nextFire != null) {
                ps.setBoolean(1, true);
                ps.setLong(2, nextFire.toEpochMilli());
                ps.setString(3, FireStatus.PENDING.name());
                ps.setLong(4, now);
                ps.setString(5, scheduleId);
            } else {
                ps.setBoolean(1, enabled);
                ps.setLong(2, now);
                ps.setString(3, scheduleId);
            }
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceNotFoundException("Schedule with id=" + scheduleId + " not found");
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to set enabled for " + scheduleId, e);
        }
    }

    @Override
    public void deleteSchedule(String scheduleId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedules WHERE id = ?")) {
            ps.setString(1, scheduleId);
            ps.executeUpdate();
            LOGGER.infof("Deleted schedule id=%s", scheduleId);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedule " + scheduleId, e);
        }
    }

    @Override
    public int deleteSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedules WHERE agent_id = ?")) {
            ps.setString(1, agentId);
            int count = ps.executeUpdate();
            if (count > 0) {
                LOGGER.infof("Cascade-deleted %d schedule(s) for Agent %s", count, agentId);
            }
            return count;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedules for Agent " + agentId, e);
        }
    }

    @Override
    public List<ScheduleConfiguration> readAllSchedules(int limit) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM eddi_schedules ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            return readScheduleList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read all schedules", e);
        }
    }

    @Override
    public List<ScheduleConfiguration> readSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM eddi_schedules WHERE agent_id = ? LIMIT 500")) {
            ps.setString(1, agentId);
            return readScheduleList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read schedules for agent " + agentId, e);
        }
    }

    // ========================= Polling & Claiming =========================

    @Override
    public List<ScheduleConfiguration> findDueSchedules(Instant now, Instant leaseExpiry, int maxRetries)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = now.toEpochMilli();
        long leaseMs = leaseExpiry.toEpochMilli();

        String sql = """
                SELECT * FROM eddi_schedules
                WHERE enabled = true AND next_fire <= ?
                AND (
                    fire_status = 'PENDING'
                    OR (fire_status = 'CLAIMED' AND claimed_at <= ?)
                    OR (fire_status = 'FAILED' AND next_retry_at <= ? AND fail_count < ?)
                )
                LIMIT 100
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setLong(2, leaseMs);
            ps.setLong(3, nowMs);
            ps.setInt(4, maxRetries);
            return readScheduleList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to find due schedules", e);
        }
    }

    @Override
    public boolean tryClaim(String scheduleId, String instanceId, Instant now) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = now.toEpochMilli();
        String fireId = scheduleId + "_" + now;

        String sql = """
                UPDATE eddi_schedules
                SET fire_status = 'CLAIMED', claimed_by = ?, claimed_at = ?, fire_id = ?, updated_at = ?
                WHERE id = ? AND (fire_status = 'PENDING' OR (fire_status = 'FAILED' AND next_retry_at <= ?))
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, instanceId);
            ps.setLong(2, nowMs);
            ps.setString(3, fireId);
            ps.setLong(4, nowMs);
            ps.setString(5, scheduleId);
            ps.setLong(6, nowMs);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOGGER.debugf("Claimed schedule %s on instance %s", scheduleId, instanceId);
                return true;
            }
            return false;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to claim schedule " + scheduleId, e);
        }
    }

    @Override
    public void markCompleted(String scheduleId, Instant nextFire) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = Instant.now().toEpochMilli();
        String sql = nextFire != null ? """
                UPDATE eddi_schedules SET fire_status='PENDING', last_fired=?, fail_count=0,
                    claimed_by=NULL, claimed_at=NULL, fire_id=NULL, next_retry_at=NULL,
                    next_fire=?, updated_at=? WHERE id=?
                """ : """
                UPDATE eddi_schedules SET fire_status='PENDING', last_fired=?, fail_count=0,
                    claimed_by=NULL, claimed_at=NULL, fire_id=NULL, next_retry_at=NULL,
                    enabled=false, next_fire=NULL, updated_at=? WHERE id=?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            if (nextFire != null) {
                ps.setLong(2, nextFire.toEpochMilli());
                ps.setLong(3, nowMs);
                ps.setString(4, scheduleId);
            } else {
                ps.setLong(2, nowMs);
                ps.setString(3, scheduleId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to mark completed: " + scheduleId, e);
        }
    }

    @Override
    public void markFailed(String scheduleId, Instant nextRetryAt) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = Instant.now().toEpochMilli();
        String sql = """
                UPDATE eddi_schedules SET fire_status='FAILED', next_retry_at=?,
                    claimed_by=NULL, claimed_at=NULL, fail_count=fail_count+1, updated_at=?
                WHERE id=?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nextRetryAt.toEpochMilli());
            ps.setLong(2, nowMs);
            ps.setString(3, scheduleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to mark failed: " + scheduleId, e);
        }
    }

    @Override
    public void markDeadLettered(String scheduleId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = Instant.now().toEpochMilli();
        String sql = """
                UPDATE eddi_schedules SET fire_status='DEAD_LETTERED',
                    claimed_by=NULL, claimed_at=NULL, updated_at=?
                WHERE id=?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setString(2, scheduleId);
            ps.executeUpdate();
            LOGGER.warnf("Schedule %s dead-lettered after max retries", scheduleId);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to dead-letter: " + scheduleId, e);
        }
    }

    @Override
    public void requeueDeadLetter(String scheduleId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = Instant.now().toEpochMilli();
        String sql = """
                UPDATE eddi_schedules SET fire_status='PENDING', fail_count=0, next_retry_at=NULL,
                    claimed_by=NULL, claimed_at=NULL, next_fire=?, updated_at=?
                WHERE id=? AND fire_status='DEAD_LETTERED'
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setLong(2, nowMs);
            ps.setString(3, scheduleId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceNotFoundException("Schedule " + scheduleId + " not found or not in DEAD_LETTERED state");
            }
            LOGGER.infof("Requeued dead-lettered schedule %s", scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to requeue: " + scheduleId, e);
        }
    }

    // ========================= Fire Log =========================

    @Override
    public void logFire(ScheduleFireLog fireLog) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = """
                INSERT INTO eddi_schedule_fire_logs (id, schedule_id, fire_id, fire_time, started_at, completed_at,
                    status, instance_id, conversation_id, error_message, attempt_number, cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fireLog.id());
            ps.setString(2, fireLog.scheduleId());
            ps.setString(3, fireLog.fireId());
            setNullableEpoch(ps, 4, fireLog.fireTime());
            setNullableEpoch(ps, 5, fireLog.startedAt());
            setNullableEpoch(ps, 6, fireLog.completedAt());
            ps.setString(7, fireLog.status());
            ps.setString(8, fireLog.instanceId());
            ps.setString(9, fireLog.conversationId());
            ps.setString(10, fireLog.errorMessage());
            ps.setInt(11, fireLog.attemptNumber());
            setNullableDouble(ps, 12, fireLog.cost());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to log fire", e);
        }
    }

    @Override
    public List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM eddi_schedule_fire_logs WHERE schedule_id = ? ORDER BY started_at DESC LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scheduleId);
            ps.setInt(2, limit);
            return readFireLogList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read fire logs", e);
        }
    }

    @Override
    public List<ScheduleFireLog> readFailedFireLogs(int limit) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = """
                SELECT * FROM eddi_schedule_fire_logs
                WHERE status IN ('FAILED', 'DEAD_LETTERED')
                ORDER BY started_at DESC LIMIT ?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            return readFireLogList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read failed fire logs", e);
        }
    }

    // ========================= Helpers =========================

    private List<ScheduleConfiguration> readScheduleList(PreparedStatement ps) throws SQLException {
        List<ScheduleConfiguration> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(fromResultSet(rs));
            }
        }
        return result;
    }

    private ScheduleConfiguration fromResultSet(ResultSet rs) throws SQLException {
        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setId(rs.getString("id"));
        config.setName(rs.getString("name"));
        config.setAgentId(rs.getString("agent_id"));
        config.setTenantId(rs.getString("tenant_id"));

        String triggerType = rs.getString("trigger_type");
        if (triggerType != null) {
            try {
                config.setTriggerType(ScheduleConfiguration.TriggerType.valueOf(triggerType));
            } catch (IllegalArgumentException ignored) {
            }
        }

        config.setCronExpression(rs.getString("cron_expression"));
        long intervalSeconds = rs.getLong("heartbeat_interval_seconds");
        config.setHeartbeatIntervalSeconds(rs.wasNull() ? null : intervalSeconds);
        config.setConversationStrategy(rs.getString("conversation_strategy"));
        config.setMaxCostPerFire(rs.getDouble("max_cost_per_fire"));
        config.setEnabled(rs.getBoolean("enabled"));
        config.setNextFire(instantFromEpoch(rs, "next_fire"));
        config.setLastFired(instantFromEpoch(rs, "last_fired"));

        String fireStatus = rs.getString("fire_status");
        if (fireStatus != null) {
            try {
                config.setFireStatus(FireStatus.valueOf(fireStatus));
            } catch (IllegalArgumentException ignored) {
            }
        }

        config.setClaimedBy(rs.getString("claimed_by"));
        config.setClaimedAt(instantFromEpoch(rs, "claimed_at"));
        config.setFireId(rs.getString("fire_id"));
        config.setFailCount(rs.getInt("fail_count"));
        config.setNextRetryAt(instantFromEpoch(rs, "next_retry_at"));
        config.setCreatedAt(instantFromEpoch(rs, "created_at"));
        config.setUpdatedAt(instantFromEpoch(rs, "updated_at"));

        return config;
    }

    private List<ScheduleFireLog> readFireLogList(PreparedStatement ps) throws SQLException {
        List<ScheduleFireLog> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new ScheduleFireLog(rs.getString("id"), rs.getString("schedule_id"), rs.getString("fire_id"),
                        instantFromEpoch(rs, "fire_time"), instantFromEpoch(rs, "started_at"), instantFromEpoch(rs, "completed_at"),
                        rs.getString("status"), rs.getString("instance_id"), rs.getString("conversation_id"), rs.getString("error_message"),
                        rs.getInt("attempt_number"), rs.getDouble("cost")));
            }
        }
        return result;
    }

    private static Instant instantFromEpoch(ResultSet rs, String column) throws SQLException {
        long val = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(val);
    }

    private static void setNullableEpoch(PreparedStatement ps, int index, Instant instant) throws SQLException {
        if (instant != null) {
            ps.setLong(index, instant.toEpochMilli());
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, double value) throws SQLException {
        if (value != 0.0) {
            ps.setDouble(index, value);
        } else {
            ps.setNull(index, Types.DOUBLE);
        }
    }
}
