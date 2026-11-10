/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.hitl.oversight.OversightJson;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IOutcomeEventStore}: table
 * {@code group_outcomes}. Indexed columns plus the row as JSON text in
 * {@code data} (without {@code recorded_by}, which lives only in its column so
 * an erasure is a column update). Insert is
 * {@code ON CONFLICT (id) DO NOTHING}, which makes a retrying webhook
 * idempotent.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresOutcomeEventStore implements IOutcomeEventStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresOutcomeEventStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS group_outcomes (
                id VARCHAR(160) PRIMARY KEY,
                group_id VARCHAR(255) NOT NULL,
                subject_id VARCHAR(255) NOT NULL,
                recorded_at BIGINT NOT NULL,
                recorded_by VARCHAR(255),
                data TEXT NOT NULL
            )
            """;

    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_group_outcomes_group ON group_outcomes (group_id, recorded_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_group_outcomes_subject ON group_outcomes (group_id, subject_id, recorded_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_group_outcomes_user ON group_outcomes (recorded_by)"
    };

    private static final String COLUMNS = "id, group_id, subject_id, recorded_at, recorded_by, data";
    private static final String ORDER = " ORDER BY recorded_at DESC, id DESC";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresOutcomeEventStore(Instance<DataSource> dataSourceInstance) {
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
            LOGGER.info("PostgresOutcomeEventStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize group_outcomes table", e);
        }
    }

    @Override
    public boolean insertIfAbsent(OutcomeEvent e) {
        ensureSchema();
        String sql = "INSERT INTO group_outcomes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";
        return update(sql, List.of(e.id(), e.groupId(), e.subjectId(), e.recordedAt() != null ? e.recordedAt().toEpochMilli() : 0L,
                e.recordedBy() != null ? e.recordedBy() : "", OversightJson.write(e.withRecordedBy(null)))) > 0;
    }

    @Override
    public List<OutcomeEvent> listBySubject(String groupId, String subjectId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM group_outcomes WHERE group_id = ? AND subject_id = ?" + ORDER + " LIMIT ?",
                List.of(groupId, subjectId, Math.max(0, limit)));
    }

    @Override
    public List<OutcomeEvent> listByGroup(String groupId, Instant since, int limit) {
        ensureSchema();
        if (since == null) {
            return select("SELECT " + COLUMNS + " FROM group_outcomes WHERE group_id = ?" + ORDER + " LIMIT ?",
                    List.of(groupId, Math.max(0, limit)));
        }
        return select("SELECT " + COLUMNS + " FROM group_outcomes WHERE group_id = ? AND recorded_at >= ?" + ORDER + " LIMIT ?",
                List.of(groupId, since.toEpochMilli(), Math.max(0, limit)));
    }

    @Override
    public List<OutcomeEvent> listBySubjects(String groupId, Collection<String> subjectIds, int limit) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return List.of();
        }
        ensureSchema();
        List<Object> params = new ArrayList<>();
        params.add(groupId);
        StringBuilder marks = new StringBuilder();
        for (String id : subjectIds) {
            marks.append(marks.isEmpty() ? "?" : ", ?");
            params.add(id);
        }
        params.add(Math.max(0, limit));
        return select("SELECT " + COLUMNS + " FROM group_outcomes WHERE group_id = ? AND subject_id IN (" + marks + ")" + ORDER + " LIMIT ?",
                params);
    }

    @Override
    public long countByGroup(String groupId) {
        ensureSchema();
        return count("SELECT COUNT(*) FROM group_outcomes WHERE group_id = ?", List.of(groupId));
    }

    @Override
    public long countBySubject(String groupId, String subjectId) {
        ensureSchema();
        return count("SELECT COUNT(*) FROM group_outcomes WHERE group_id = ? AND subject_id = ?", List.of(groupId, subjectId));
    }

    @Override
    public long deleteOldestBeyond(String groupId, int keep) {
        ensureSchema();
        return update("DELETE FROM group_outcomes WHERE id IN (SELECT id FROM group_outcomes WHERE group_id = ?" + ORDER + " OFFSET ?)",
                List.of(groupId, Math.max(0, keep)));
    }

    @Override
    public long deleteRecordedBefore(String groupId, Instant cutoff) {
        ensureSchema();
        return update("DELETE FROM group_outcomes WHERE group_id = ? AND recorded_at < ?", List.of(groupId, cutoff.toEpochMilli()));
    }

    @Override
    public long deleteByGroup(String groupId) {
        ensureSchema();
        return update("DELETE FROM group_outcomes WHERE group_id = ?", List.of(groupId));
    }

    @Override
    public List<OutcomeEvent> readByUser(String userId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM group_outcomes WHERE recorded_by = ?" + ORDER + " LIMIT ?",
                List.of(userId, Math.max(0, limit)));
    }

    @Override
    public long eraseUser(String userId, String pseudonym) {
        ensureSchema();
        return update("UPDATE group_outcomes SET recorded_by = ? WHERE recorded_by = ?", List.of(pseudonym, userId));
    }

    private List<OutcomeEvent> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            List<OutcomeEvent> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String by = rs.getString("recorded_by");
                    rows.add(OversightJson.read(rs.getString("data"), OutcomeEvent.class).withRecordedBy(by == null || by.isEmpty() ? null : by));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read group outcomes", e);
        }
    }

    private long count(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count group outcomes", e);
        }
    }

    private long update(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write group outcomes", e);
        }
    }
}
