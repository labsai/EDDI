/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IScheduleStore}.
 * <p>
 * Uses a conditional {@code UPDATE} for atomic CAS claiming, so exactly one
 * instance owns a schedule per claim. That is <em>not</em> exactly-once
 * delivery — see {@link IScheduleStore}: an expired lease may be stolen while
 * the original, wedged fire can still commit, so fire targets must be
 * idempotent.
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
                agent_version INTEGER,
                environment VARCHAR(64),
                tenant_id VARCHAR(255),
                user_id VARCHAR(255),
                trigger_type VARCHAR(64),
                cron_expression VARCHAR(128),
                heartbeat_interval_seconds BIGINT,
                one_time_at VARCHAR(64),
                time_zone VARCHAR(64),
                message TEXT,
                conversation_strategy VARCHAR(64),
                persistent_conversation_id VARCHAR(255),
                max_cost_per_fire DOUBLE PRECISION,
                allow_self_scheduling BOOLEAN NOT NULL DEFAULT false,
                created_by VARCHAR(255),
                enabled BOOLEAN NOT NULL DEFAULT false,
                next_fire BIGINT,
                last_fired BIGINT,
                fire_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                claimed_by VARCHAR(128),
                claimed_at BIGINT,
                fire_id VARCHAR(255),
                fail_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at BIGINT,
                metadata JSONB,
                created_at BIGINT,
                updated_at BIGINT
            )
            """;

    /**
     * Adds the {@code metadata} column to schedule tables created before it
     * existed. Mongo persists the full document, so HITL timeout metadata
     * (hitlType/policy/surface/conversationId) has always survived there; this
     * keeps Postgres at parity — without it the HITL timeout fast-path never
     * triggers and the manual-fire security guard cannot recognize the schedule.
     */
    private static final String ADD_METADATA_COLUMN = "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS metadata JSONB";

    /**
     * Adds the {@code user_id} column to schedule tables created before it existed.
     * Mongo persists the whole document, so {@code userId} has always been stored
     * there; Postgres never had the column at all. The portable
     * {@code deleteSchedulesByUserId} scan therefore compared against a column that
     * did not exist, matched nothing, and reported a successful GDPR erasure while
     * the user's schedules kept firing. Without this upgrade an existing deployment
     * stays in that state.
     */
    private static final String ADD_USER_ID_COLUMN = "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)";

    /**
     * Adds the eight {@link ScheduleConfiguration} fields that never had a column
     * here at all, so a schedule created on PostgreSQL read back with them null.
     * Mongo serializes the whole document and has always kept them, which is why
     * the loss only ever showed up on a PostgreSQL deployment:
     * <ul>
     * <li>{@code message} — the text a CRON schedule sends to the agent. Null meant
     * the fire ran with no input at all (only HEARTBEAT has a default).</li>
     * <li>{@code time_zone} — dropping it silently re-evaluated every cron against
     * {@code eddi.schedule.default-timezone}, so a 09:00 Europe/Vienna job fired at
     * the wrong hour.</li>
     * <li>{@code environment} — {@code resolveEnvironment(null)} falls back to
     * production, so a schedule created against {@code test} fired against
     * production.</li>
     * <li>{@code persistent_conversation_id} — without it
     * {@code conversationStrategy=persistent} (the default for every HEARTBEAT)
     * could never work: each fire started a brand-new conversation.</li>
     * <li>{@code one_time_at}, {@code agent_version}, {@code created_by},
     * {@code allow_self_scheduling} — the remaining persisted fields.</li>
     * </ul>
     * Each statement is a separate idempotent {@code ADD COLUMN IF NOT EXISTS}, the
     * same upgrade pattern as {@link #ADD_METADATA_COLUMN} and
     * {@link #ADD_USER_ID_COLUMN}, so existing installs pick them up on the next
     * start. Values for rows written before the upgrade stay null — there is
     * nothing to backfill from.
     */
    private static final String[] ADD_PAYLOAD_COLUMNS = {
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS agent_version INTEGER",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS environment VARCHAR(64)",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS one_time_at VARCHAR(64)",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS time_zone VARCHAR(64)",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS message TEXT",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS persistent_conversation_id VARCHAR(255)",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS allow_self_scheduling BOOLEAN NOT NULL DEFAULT false",
            "ALTER TABLE eddi_schedules ADD COLUMN IF NOT EXISTS created_by VARCHAR(255)"};

    /**
     * Bulk delete by user, overriding the portable scan-and-delete default.
     * <p>
     * The default in {@link IScheduleStore} reads every schedule and filters in
     * Java, which is bounded by {@code ERASURE_SCAN_LIMIT}; a deployment with more
     * schedules than that would erase only part of the user's data. A single
     * indexed DELETE has no such ceiling and is what an erasure needs.
     *
     * @param userId
     *            the user whose schedules to erase
     * @return number of schedules deleted
     */
    @Override
    public int deleteSchedulesByUserId(String userId) throws IResourceStore.ResourceStoreException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        // Every other public operation here initialises the schema first. An erasure
        // must too: if it is the first call after a cold start the table (or the
        // user_id column) may not exist yet, and an erasure that fails with a SQL
        // error is a compliance incident, not a retry.
        ensureSchema();
        // The fire logs go with them: each carries a conversationId of the user being
        // erased, and once the schedule row is gone nothing can find them again.
        deleteFireLogsWhereScheduleMatches("user_id", userId);
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedules WHERE user_id = ?")) {
            ps.setString(1, userId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOGGER.infof("GDPR erasure: deleted %d schedule(s)", deleted);
            }
            return deleted;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedules by userId", e);
        }
    }

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
            CREATE INDEX IF NOT EXISTS idx_schedules_user ON eddi_schedules (user_id);
            CREATE INDEX IF NOT EXISTS idx_schedules_name ON eddi_schedules (name);
            CREATE INDEX IF NOT EXISTS idx_fire_logs_schedule ON eddi_schedule_fire_logs (schedule_id, started_at DESC);
            CREATE INDEX IF NOT EXISTS idx_fire_logs_status ON eddi_schedule_fire_logs (status, started_at DESC);
            """;

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;
    /**
     * Max schedules claimed per poll cycle — see MongoScheduleStore for parity
     * notes.
     */
    private final int pollBatchSize;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresScheduleStore(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization,
            @ConfigProperty(name = "eddi.schedule.poll-batch-size", defaultValue = "100") int pollBatchSize) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
        this.pollBatchSize = pollBatchSize > 0 ? pollBatchSize : 100;
    }

    /**
     * Adding {@code user_id} to an existing table leaves every schedule created
     * before the upgrade with a NULL value, and nothing in the row can tell us who
     * owned it — the identity was simply never recorded. Those rows therefore
     * cannot be erased by user, and silently doing nothing about them is exactly
     * the failure mode this column was added to end. There is no data to backfill
     * from, so the honest response is to make the residue visible and let an
     * operator decide (attribute them, or delete them wholesale).
     */
    private static void warnAboutUnattributableSchedules(Statement stmt) {
        // Purely diagnostic: schema initialisation must never fail because a count
        // could not be taken. (A real driver never returns a null ResultSet from
        // executeQuery, but this runs on the startup path, so it stays defensive.)
        try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM eddi_schedules WHERE user_id IS NULL")) {
            if (rs != null && rs.next()) {
                long legacy = rs.getLong(1);
                if (legacy > 0) {
                    LOGGER.warnf("%d schedule(s) predate the user_id column and carry no owner. They cannot be removed by "
                            + "a GDPR erasure request; attribute or delete them manually.", legacy);
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warnf("Could not count unattributable schedules: %s", e.getMessage());
        }
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_SCHEDULES_TABLE);
            stmt.execute(ADD_METADATA_COLUMN); // idempotent upgrade for pre-existing tables
            stmt.execute(ADD_USER_ID_COLUMN); // idempotent upgrade for pre-existing tables (GDPR erasure)
            for (String addColumn : ADD_PAYLOAD_COLUMNS) {
                stmt.execute(addColumn); // idempotent upgrade for pre-existing tables (schedule payload)
            }
            warnAboutUnattributableSchedules(stmt);
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

        // New columns are appended at the END of the list on purpose: the existing
        // parameter indices stay put, so the upgrade cannot silently re-bind a value
        // to the wrong column.
        String sql = """
                INSERT INTO eddi_schedules (id, name, agent_id, tenant_id, user_id, trigger_type, cron_expression,
                    heartbeat_interval_seconds, conversation_strategy, max_cost_per_fire,
                    enabled, next_fire, fire_status, fail_count, metadata, created_at, updated_at,
                    message, one_time_at, time_zone, environment, agent_version,
                    persistent_conversation_id, created_by, allow_self_scheduling)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, schedule.getName());
            ps.setString(3, schedule.getAgentId());
            ps.setString(4, schedule.getTenantId());
            // Persisted so GDPR erasure can find this row. Without it
            // deleteSchedulesByUserId scans a column that is always null, matches
            // nothing, and reports success while the schedule keeps firing.
            ps.setString(5, schedule.getUserId());
            ps.setString(6, schedule.getTriggerType() != null ? schedule.getTriggerType().name() : null);
            ps.setString(7, schedule.getCronExpression());
            setNullableLong(ps, 8, schedule.getHeartbeatIntervalSeconds());
            ps.setString(9, schedule.getConversationStrategy());
            setNullableDouble(ps, 10, schedule.getMaxCostPerFire());
            ps.setBoolean(11, schedule.isEnabled());
            setNullableEpoch(ps, 12, schedule.getNextFire());
            ps.setString(13, FireStatus.PENDING.name());
            ps.setString(14, serializeMetadata(schedule.getMetadata()));
            setNullableEpoch(ps, 15, now);
            setNullableEpoch(ps, 16, now);
            // The eight fields that had no column before — see ADD_PAYLOAD_COLUMNS for
            // what each one silently did when it read back null.
            ps.setString(17, schedule.getMessage());
            ps.setString(18, schedule.getOneTimeAt());
            ps.setString(19, schedule.getTimeZone());
            ps.setString(20, schedule.getEnvironment());
            ps.setInt(21, schedule.getAgentVersion());
            ps.setString(22, schedule.getPersistentConversationId());
            ps.setString(23, schedule.getCreatedBy());
            ps.setBoolean(24, schedule.isAllowSelfScheduling());
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
        // created_at, created_by, last_fired, the claim columns (claimed_by/claimed_at/
        // fire_id/next_retry_at) and persistent_conversation_id are deliberately NOT in
        // this SET list: they are provenance or runtime state, owned by createSchedule,
        // the claim/completion methods and setPersistentConversationId respectively. A
        // PUT body that omits them must not be able to erase them.
        String sql = """
                UPDATE eddi_schedules SET name=?, agent_id=?, tenant_id=?, user_id=?, trigger_type=?, cron_expression=?,
                    heartbeat_interval_seconds=?, conversation_strategy=?, max_cost_per_fire=?,
                    enabled=?, next_fire=?, fire_status=?, fail_count=?, metadata=?::jsonb, updated_at=?,
                    message=?, one_time_at=?, time_zone=?, environment=?, agent_version=?, allow_self_scheduling=?
                WHERE id=?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schedule.getName());
            ps.setString(2, schedule.getAgentId());
            ps.setString(3, schedule.getTenantId());
            ps.setString(4, schedule.getUserId());
            ps.setString(5, schedule.getTriggerType() != null ? schedule.getTriggerType().name() : null);
            ps.setString(6, schedule.getCronExpression());
            setNullableLong(ps, 7, schedule.getHeartbeatIntervalSeconds());
            ps.setString(8, schedule.getConversationStrategy());
            setNullableDouble(ps, 9, schedule.getMaxCostPerFire());
            ps.setBoolean(10, schedule.isEnabled());
            setNullableEpoch(ps, 11, schedule.getNextFire());
            ps.setString(12, schedule.getFireStatus() != null ? schedule.getFireStatus().name() : FireStatus.PENDING.name());
            ps.setInt(13, schedule.getFailCount());
            ps.setString(14, serializeMetadata(schedule.getMetadata()));
            setNullableEpoch(ps, 15, schedule.getUpdatedAt());
            ps.setString(16, schedule.getMessage());
            ps.setString(17, schedule.getOneTimeAt());
            ps.setString(18, schedule.getTimeZone());
            ps.setString(19, schedule.getEnvironment());
            ps.setInt(20, schedule.getAgentVersion());
            ps.setBoolean(21, schedule.isAllowSelfScheduling());
            ps.setString(22, scheduleId);
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
    public void setPersistentConversationId(String scheduleId, String conversationId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("UPDATE eddi_schedules SET persistent_conversation_id=?, updated_at=? WHERE id=?")) {
            ps.setString(1, conversationId);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.setString(3, scheduleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to set persistent conversation id for " + scheduleId, e);
        }
    }

    @Override
    public void setScheduleEnabled(String scheduleId, boolean enabled, Instant nextFire)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        ensureSchema();
        // Re-enabling always clears the failure state. Doing it only when a nextFire
        // was supplied left a one-shot schedule enabled but stuck in FAILED/
        // DEAD_LETTERED with a non-zero failCount, so it could never be claimed again.
        String sql = enabled
                ? (nextFire != null
                        ? "UPDATE eddi_schedules SET enabled=?, next_fire=?, fire_status=?, fail_count=0, next_retry_at=NULL, updated_at=? WHERE id=?"
                        : "UPDATE eddi_schedules SET enabled=?, fire_status=?, fail_count=0, next_retry_at=NULL, updated_at=? WHERE id=?")
                : "UPDATE eddi_schedules SET enabled=?, updated_at=? WHERE id=?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = Instant.now().toEpochMilli();
            if (enabled && nextFire != null) {
                ps.setBoolean(1, true);
                ps.setLong(2, nextFire.toEpochMilli());
                ps.setString(3, FireStatus.PENDING.name());
                ps.setLong(4, now);
                ps.setString(5, scheduleId);
            } else if (enabled) {
                ps.setBoolean(1, true);
                ps.setString(2, FireStatus.PENDING.name());
                ps.setLong(3, now);
                ps.setString(4, scheduleId);
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
        // Fire logs first: a schedule's logs are unreachable once the schedule is gone,
        // and each one carries a conversationId — leaving them behind orphans personal
        // data that no erasure path can find.
        deleteFireLogsByScheduleId(scheduleId);
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
        deleteFireLogsWhereScheduleMatches("agent_id", agentId);
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
    public int deleteSchedulesByName(String name) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        deleteFireLogsWhereScheduleMatches("name", name);
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedules WHERE name = ?")) {
            ps.setString(1, name);
            int count = ps.executeUpdate();
            if (count > 0) {
                LOGGER.infof("Deleted %d HITL timeout schedule(s) with name '%s'", count, name);
            }
            return count;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedules by name: " + name, e);
        }
    }

    @Override
    public int deleteFireLogsByScheduleId(String scheduleId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedule_fire_logs WHERE schedule_id = ?")) {
            ps.setString(1, scheduleId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete fire logs for schedule " + scheduleId, e);
        }
    }

    @Override
    public int deleteFireLogsOlderThan(Instant cutoff) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM eddi_schedule_fire_logs WHERE started_at < ?")) {
            ps.setLong(1, cutoff.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to prune fire logs", e);
        }
    }

    /**
     * Cascade the fire logs of every schedule matching {@code column = value}. The
     * column name is a compile-time literal supplied by this class only — never
     * caller input — so it cannot carry SQL injection; the value is always bound.
     */
    private void deleteFireLogsWhereScheduleMatches(String column, String value) throws IResourceStore.ResourceStoreException {
        String sql = "DELETE FROM eddi_schedule_fire_logs WHERE schedule_id IN (SELECT id FROM eddi_schedules WHERE " + column + " = ?)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to cascade-delete fire logs by " + column, e);
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
    public List<ScheduleConfiguration> readAllSchedules(int limit, int offset) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // id is the tie-breaker: created_at alone is not unique (bulk-created HITL
        // timeout or cadence schedules share a millisecond), and a non-deterministic
        // order makes paging skip and repeat rows.
        String sql = "SELECT * FROM eddi_schedules ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, Math.max(0, offset));
            return readScheduleList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read all schedules", e);
        }
    }

    @Override
    public List<ScheduleConfiguration> readSchedulesByAgentId(String agentId) throws IResourceStore.ResourceStoreException {
        return readSchedulesByAgentId(agentId, 500, 0);
    }

    @Override
    public List<ScheduleConfiguration> readSchedulesByAgentId(String agentId, int limit, int offset) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM eddi_schedules WHERE agent_id = ? ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, limit);
            ps.setInt(3, Math.max(0, offset));
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
                LIMIT ?
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setLong(2, leaseMs);
            ps.setLong(3, nowMs);
            ps.setInt(4, maxRetries);
            ps.setInt(5, pollBatchSize);
            return readScheduleList(ps);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to find due schedules", e);
        }
    }

    @Override
    public boolean tryClaim(String scheduleId, String instanceId, Instant now, Instant leaseExpiry) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        long nowMs = now.toEpochMilli();
        long leaseMs = leaseExpiry.toEpochMilli();
        String fireId = IScheduleStore.fireIdOf(scheduleId, now);

        // The CLAIMED+lease-expired clause steals a crashed/wedged instance's claim
        // and MUST mirror findDueSchedules, else expired CLAIMED rows are returned
        // every poll but never re-fired.
        String sql = """
                UPDATE eddi_schedules
                SET fire_status = 'CLAIMED', claimed_by = ?, claimed_at = ?, fire_id = ?, updated_at = ?
                WHERE id = ? AND (fire_status = 'PENDING'
                    OR (fire_status = 'FAILED' AND next_retry_at <= ?)
                    OR (fire_status = 'CLAIMED' AND claimed_at <= ?))
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, instanceId);
            ps.setLong(2, nowMs);
            ps.setString(3, fireId);
            ps.setLong(4, nowMs);
            ps.setString(5, scheduleId);
            ps.setLong(6, nowMs);
            ps.setLong(7, leaseMs);
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

    private List<ScheduleConfiguration> readScheduleList(PreparedStatement ps) throws SQLException, IResourceStore.ResourceStoreException {
        List<ScheduleConfiguration> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(fromResultSet(rs));
            }
        }
        return result;
    }

    private ScheduleConfiguration fromResultSet(ResultSet rs) throws SQLException, IResourceStore.ResourceStoreException {
        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setId(rs.getString("id"));
        config.setName(rs.getString("name"));
        config.setAgentId(rs.getString("agent_id"));
        config.setTenantId(rs.getString("tenant_id"));
        config.setUserId(rs.getString("user_id"));

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
        config.setOneTimeAt(rs.getString("one_time_at"));
        config.setTimeZone(rs.getString("time_zone"));
        config.setMessage(rs.getString("message"));
        config.setEnvironment(rs.getString("environment"));
        config.setAgentVersion(rs.getInt("agent_version"));
        config.setConversationStrategy(rs.getString("conversation_strategy"));
        config.setPersistentConversationId(rs.getString("persistent_conversation_id"));
        config.setCreatedBy(rs.getString("created_by"));
        config.setAllowSelfScheduling(rs.getBoolean("allow_self_scheduling"));
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
        config.setMetadata(deserializeMetadata(rs.getString("metadata")));
        config.setCreatedAt(instantFromEpoch(rs, "created_at"));
        config.setUpdatedAt(instantFromEpoch(rs, "updated_at"));

        return config;
    }

    private String serializeMetadata(Map<String, Object> metadata) throws IResourceStore.ResourceStoreException {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return jsonSerialization.serialize(metadata);
        } catch (Exception e) {
            // Fail closed: the metadata carries the HITL contract (hitlType/policy/
            // surface/conversationId). Silently storing null would persist a HITL
            // timeout schedule the poller no longer recognizes and the manual-fire
            // guard no longer protects — worse than failing the write.
            throw new IResourceStore.ResourceStoreException("Failed to serialize schedule metadata", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMetadata(String json) throws IResourceStore.ResourceStoreException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonSerialization.deserialize(json, Map.class);
        } catch (Exception e) {
            // Fail closed (see serializeMetadata): loading a HITL schedule without its
            // metadata would make it fire wrong / bypass the manual-fire guard.
            throw new IResourceStore.ResourceStoreException("Failed to deserialize schedule metadata", e);
        }
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
