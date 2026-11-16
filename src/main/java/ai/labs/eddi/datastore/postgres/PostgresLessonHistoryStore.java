/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryEntry;
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
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link ILessonHistoryStore}: table
 * {@code group_lesson_history}. Indexed columns plus the row as JSON text in
 * {@code data}; insert is {@code ON CONFLICT (id) DO NOTHING}.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresLessonHistoryStore implements ILessonHistoryStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresLessonHistoryStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS group_lesson_history (
                id VARCHAR(160) PRIMARY KEY,
                group_id VARCHAR(255) NOT NULL,
                at BIGINT NOT NULL,
                data TEXT NOT NULL
            )
            """;

    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_group_lesson_history_group ON group_lesson_history (group_id, at DESC)";

    private static final String ORDER = " ORDER BY at DESC, id DESC";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresLessonHistoryStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX);
            schemaInitialized = true;
            LOGGER.info("PostgresLessonHistoryStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize group_lesson_history table", e);
        }
    }

    @Override
    public boolean insertIfAbsent(LessonHistoryEntry entry) {
        ensureSchema();
        return update("INSERT INTO group_lesson_history (id, group_id, at, data) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                List.of(entry.id(), entry.groupId(), entry.at() != null ? entry.at().toEpochMilli() : 0L, OversightJson.write(entry))) > 0;
    }

    @Override
    public List<LessonHistoryEntry> listByGroup(String groupId, int limit) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT data FROM group_lesson_history WHERE group_id = ?" + ORDER + " LIMIT ?")) {
            PostgresApprovalDecisionStore.bind(ps, List.of(groupId, Math.max(0, limit)));
            List<LessonHistoryEntry> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(OversightJson.read(rs.getString("data"), LessonHistoryEntry.class));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read lesson history", e);
        }
    }

    @Override
    public long countByGroup(String groupId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM group_lesson_history WHERE group_id = ?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count lesson history", e);
        }
    }

    @Override
    public long deleteOldestBeyond(String groupId, int keep) {
        ensureSchema();
        return update("DELETE FROM group_lesson_history WHERE id IN (SELECT id FROM group_lesson_history WHERE group_id = ?" + ORDER + " OFFSET ?)",
                List.of(groupId, Math.max(0, keep)));
    }

    @Override
    public long deleteByGroup(String groupId) {
        ensureSchema();
        return update("DELETE FROM group_lesson_history WHERE group_id = ?", List.of(groupId));
    }

    private long update(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write lesson history", e);
        }
    }
}
