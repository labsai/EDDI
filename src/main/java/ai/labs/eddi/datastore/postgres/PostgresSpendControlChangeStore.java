/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.spendcontrol.ISpendControlChangeStore;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeJson;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link ISpendControlChangeStore}. Schema is
 * auto-created on first access.
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS spend_control_changes (
 *     id          VARCHAR(64) PRIMARY KEY,
 *     status      VARCHAR(16) NOT NULL,
 *     proposed_at BIGINT NOT NULL,
 *     document    TEXT NOT NULL
 * );
 * </pre>
 */
@ApplicationScoped
@DefaultBean
public class PostgresSpendControlChangeStore implements ISpendControlChangeStore {

    public static final String TABLE = "spend_control_changes";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS spend_control_changes (
                id VARCHAR(64) PRIMARY KEY,
                status VARCHAR(16) NOT NULL,
                proposed_at BIGINT NOT NULL,
                document TEXT NOT NULL
            )
            """;
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_spend_control_changes_status ON spend_control_changes "
            + "(status, proposed_at)";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresSpendControlChangeStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize the spend-control change table", e);
        }
    }

    @Override
    public void create(SpendControlChange change) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("INSERT INTO spend_control_changes (id, status, proposed_at, document) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, change.id());
            ps.setString(2, change.status().name());
            ps.setLong(3, change.proposedAtMillis());
            ps.setString(4, SpendControlChangeJson.write(change));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store a spend-control change", e);
        }
    }

    @Override
    public Optional<SpendControlChange> read(String id) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT document FROM spend_control_changes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(SpendControlChangeJson.read(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a spend-control change", e);
        }
    }

    @Override
    public List<SpendControlChange> list(Status status, int limit) {
        ensureSchema();
        String sql = status == null
                ? "SELECT document FROM spend_control_changes ORDER BY proposed_at DESC LIMIT ?"
                : "SELECT document FROM spend_control_changes WHERE status = ? ORDER BY proposed_at DESC LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (status != null) {
                ps.setString(index++, status.name());
            }
            ps.setInt(index, limit);
            List<SpendControlChange> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(SpendControlChangeJson.read(rs.getString(1)));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list spend-control changes", e);
        }
    }

    @Override
    public long count(Status status) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM spend_control_changes WHERE status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count spend-control changes", e);
        }
    }

    @Override
    public boolean compareAndSetStatus(String id, Status expected, SpendControlChange next) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("UPDATE spend_control_changes SET status = ?, document = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, next.status().name());
            ps.setString(2, SpendControlChangeJson.write(next));
            ps.setString(3, id);
            ps.setString(4, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update a spend-control change", e);
        }
    }

    @Override
    public long deleteDecidedBefore(long proposedBeforeMillis) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM spend_control_changes WHERE status <> ? AND proposed_at < ?")) {
            ps.setString(1, Status.PENDING.name());
            ps.setLong(2, proposedBeforeMillis);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prune spend-control changes", e);
        }
    }
}
