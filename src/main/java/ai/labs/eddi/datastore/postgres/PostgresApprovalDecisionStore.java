/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IApprovalDecisionStore}: table
 * {@code approval_decisions}. Indexed columns plus the rest of the row as JSON
 * text in {@code data} (without {@code user_id} / {@code decided_by}, which
 * live only in their columns so an erasure is a column update). Insert is
 * {@code ON CONFLICT (id) DO NOTHING}, which makes the event and the rebuild
 * idempotent.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresApprovalDecisionStore implements IApprovalDecisionStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresApprovalDecisionStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS approval_decisions (
                id VARCHAR(160) PRIMARY KEY,
                agent_id VARCHAR(255),
                group_id VARCHAR(255),
                category VARCHAR(255),
                decided_at BIGINT NOT NULL,
                surface VARCHAR(16),
                pause_type VARCHAR(32),
                channel VARCHAR(16),
                agent_version INTEGER,
                user_id VARCHAR(255),
                decided_by VARCHAR(255),
                data TEXT NOT NULL
            )
            """;

    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_approval_decisions_agent ON approval_decisions (agent_id, category, decided_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_approval_decisions_group ON approval_decisions (group_id, category, decided_at DESC) "
                    + "WHERE group_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_approval_decisions_decided ON approval_decisions (decided_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_approval_decisions_user ON approval_decisions (user_id) WHERE user_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_approval_decisions_decider ON approval_decisions (decided_by)"
    };

    private static final String COLUMNS = "id, agent_id, group_id, category, decided_at, surface, pause_type, channel, agent_version, "
            + "user_id, decided_by, data";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresApprovalDecisionStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                stmt.execute(index);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresApprovalDecisionStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize approval_decisions table", e);
        }
    }

    @Override
    public boolean insertIfAbsent(ApprovalDecision d) {
        ensureSchema();
        String sql = "INSERT INTO approval_decisions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.id());
            ps.setString(2, d.agentId());
            ps.setString(3, d.groupId());
            ps.setString(4, d.category());
            ps.setLong(5, d.decidedAt() != null ? d.decidedAt().toEpochMilli() : 0L);
            ps.setString(6, d.surface());
            ps.setString(7, d.pauseType());
            ps.setString(8, d.channel());
            if (d.agentVersion() != null) {
                ps.setInt(9, d.agentVersion());
            } else {
                ps.setNull(9, Types.INTEGER);
            }
            ps.setString(10, d.userId());
            ps.setString(11, d.decidedBy());
            ps.setString(12, OversightJson.write(d.withoutPersonalFields()));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store approval decision", e);
        }
    }

    @Override
    public List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String where = where(filter, params);
        params.add(Math.max(0, limit));
        params.add(Math.max(0, skip));
        return select("SELECT " + COLUMNS + " FROM approval_decisions" + where + " ORDER BY decided_at DESC, id DESC LIMIT ? OFFSET ?",
                params);
    }

    @Override
    public long count(DecisionFilter filter) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM approval_decisions" + where(filter, params);
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count approval decisions", e);
        }
    }

    @Override
    public long deleteDecidedBefore(Instant cutoff) {
        ensureSchema();
        return update("DELETE FROM approval_decisions WHERE decided_at < ?", List.of(cutoff.toEpochMilli()));
    }

    @Override
    public List<ApprovalDecision> readByUser(String userId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM approval_decisions WHERE user_id = ? OR decided_by = ? LIMIT ?",
                List.of(userId, userId, Math.max(0, limit)));
    }

    @Override
    public long eraseUser(String userId, String pseudonym) {
        ensureSchema();
        long changed = update("UPDATE approval_decisions SET user_id = NULL WHERE user_id = ?", List.of(userId));
        changed += update("UPDATE approval_decisions SET decided_by = ? WHERE decided_by = ?", List.of(pseudonym, userId));
        return changed;
    }

    /** The WHERE clause of a filter; appends its parameters. */
    public static String where(DecisionFilter f, List<Object> params) {
        List<String> parts = new ArrayList<>();
        addEq(parts, params, "agent_id", f.agentId());
        addEq(parts, params, "group_id", f.groupId());
        addEq(parts, params, "category", f.category());
        if (f.from() != null) {
            parts.add("decided_at >= ?");
            params.add(f.from().toEpochMilli());
        }
        if (f.to() != null) {
            parts.add("decided_at < ?");
            params.add(f.to().toEpochMilli());
        }
        addEq(parts, params, "surface", f.surface());
        addEq(parts, params, "pause_type", f.pauseType());
        addEq(parts, params, "channel", f.channel());
        if (f.agentVersion() != null) {
            parts.add("agent_version = ?");
            params.add(f.agentVersion());
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private static void addEq(List<String> parts, List<Object> params, String column, String value) {
        if (value != null) {
            parts.add(column + " = ?");
            params.add(value);
        }
    }

    private List<ApprovalDecision> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            List<ApprovalDecision> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ApprovalDecision stored = OversightJson.read(rs.getString("data"), ApprovalDecision.class);
                    rows.add(stored.withPersonalFields(rs.getString("user_id"), rs.getString("decided_by")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read approval decisions", e);
        }
    }

    private long update(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update approval decisions", e);
        }
    }

    static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (value instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, value != null ? value.toString() : null);
            }
        }
    }
}
