/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL {@link ITeamBudgetStore}: one row per {@code (group_id, month)}.
 * <p>
 * Spend is {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}, atomic
 * across nodes. An adjustment locks the row ({@code SELECT ... FOR UPDATE})
 * inside a transaction, evaluates the transfer-id and "cap stays at or above
 * spend" predicates on the committed values, then updates -- the same guarantee
 * as the MongoDB conditional update. Retention is a sweep
 * ({@code TeamBudgetMaintenance}); the month is part of the key, so an expired
 * row is never read again whether or not it has been swept.
 */
@ApplicationScoped
@DefaultBean
public class PostgresTeamBudgetStore implements ITeamBudgetStore {

    static final String TABLE = "team_budget_months";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS team_budget_months (
                group_id VARCHAR(255) NOT NULL,
                month VARCHAR(7) NOT NULL,
                spent_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                adjustment_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                applied_transfer_ids TEXT NOT NULL DEFAULT '[]',
                adjustments TEXT NOT NULL DEFAULT '[]',
                expires_at_ms BIGINT NOT NULL,
                PRIMARY KEY (group_id, month)
            )
            """;
    private static final String CREATE_EXPIRES_INDEX = "CREATE INDEX IF NOT EXISTS idx_team_budget_expires ON team_budget_months (expires_at_ms)";

    private static final String ADD_SPEND = """
            INSERT INTO team_budget_months (group_id, month, spent_usd, expires_at_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (group_id, month)
            DO UPDATE SET spent_usd = team_budget_months.spent_usd + EXCLUDED.spent_usd
            RETURNING spent_usd
            """;
    private static final String COLUMNS = "group_id, month, spent_usd, adjustment_usd, applied_transfer_ids, adjustments";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresTeamBudgetStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_EXPIRES_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not create the " + TABLE + " schema", e);
        }
    }

    @Override
    public double addSpend(String groupId, String month, double usd, Instant expiresAt) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(ADD_SPEND)) {
            statement.setString(1, groupId);
            statement.setString(2, month);
            statement.setDouble(3, usd);
            statement.setLong(4, expiresAt.toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getDouble(1) : usd;
            }
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not record team spend", e);
        }
    }

    @Override
    public Optional<TeamBudgetMonth> read(String groupId, String month) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT " + COLUMNS + " FROM team_budget_months WHERE group_id = ? AND month = ?")) {
            statement.setString(1, groupId);
            statement.setString(2, month);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toMonth(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budget", e);
        }
    }

    @Override
    public List<TeamBudgetMonth> readMany(Collection<String> groupIds, String month) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT " + COLUMNS + " FROM team_budget_months WHERE month = ? AND group_id = ANY (?)")) {
            statement.setString(1, month);
            statement.setArray(2, connection.createArrayOf("varchar", groupIds.toArray()));
            List<TeamBudgetMonth> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(toMonth(rows));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budgets", e);
        }
    }

    @Override
    public List<TeamBudgetMonth> history(String groupId, int count) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + COLUMNS + " FROM team_budget_months WHERE group_id = ? ORDER BY month DESC LIMIT ?")) {
            statement.setString(1, groupId);
            statement.setInt(2, Math.max(1, count));
            List<TeamBudgetMonth> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(toMonth(rows));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budget history", e);
        }
    }

    @Override
    public AdjustOutcome applyAdjustment(String groupId, String month, double deltaUsd, double baseUsd, TeamBudgetMonth.Adjustment entry,
                                         String transferId, Instant expiresAt) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            connection.setAutoCommit(false);
            try {
                TeamBudgetMonth current = lockMonth(connection, groupId, month, expiresAt);
                if (transferId != null && current.appliedTransferIds().contains(transferId)) {
                    connection.rollback();
                    return AdjustOutcome.DUPLICATE;
                }
                if (deltaUsd < 0.0 && !(baseUsd + current.adjustmentUsd() + deltaUsd >= current.spentUsd())) {
                    connection.rollback();
                    return AdjustOutcome.REFUSED;
                }
                List<TeamBudgetMonth.Adjustment> history = new ArrayList<>(current.adjustments());
                history.add(entry);
                List<String> transfers = new ArrayList<>(current.appliedTransferIds());
                if (transferId != null) {
                    transfers.add(transferId);
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE team_budget_months SET adjustment_usd = adjustment_usd + ?, applied_transfer_ids = ?, adjustments = ? "
                                + "WHERE group_id = ? AND month = ?")) {
                    update.setDouble(1, deltaUsd);
                    update.setString(2, JSON.writeValueAsString(tail(transfers, MAX_TRANSFER_IDS)));
                    update.setString(3,
                            JSON.writeValueAsString(tail(history, MAX_ADJUSTMENTS).stream().map(PostgresTeamBudgetStore::toMap).toList()));
                    update.setString(4, groupId);
                    update.setString(5, month);
                    update.executeUpdate();
                }
                connection.commit();
                return AdjustOutcome.APPLIED;
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not adjust the team budget", e);
        }
    }

    @Override
    public AdjustOutcome setAdjustment(String groupId, String month, double expectedAdjustmentUsd, double newAdjustmentUsd, double baseUsd,
                                       TeamBudgetMonth.Adjustment entry, Instant expiresAt) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            connection.setAutoCommit(false);
            try {
                TeamBudgetMonth current = lockMonth(connection, groupId, month, expiresAt);
                if (current.adjustmentUsd() != expectedAdjustmentUsd) {
                    connection.rollback();
                    return AdjustOutcome.CONFLICT;
                }
                if (!(baseUsd + newAdjustmentUsd >= current.spentUsd())) {
                    connection.rollback();
                    return AdjustOutcome.REFUSED;
                }
                List<TeamBudgetMonth.Adjustment> history = new ArrayList<>(current.adjustments());
                history.add(entry);
                try (PreparedStatement update = connection
                        .prepareStatement("UPDATE team_budget_months SET adjustment_usd = ?, adjustments = ? WHERE group_id = ? AND month = ?")) {
                    update.setDouble(1, newAdjustmentUsd);
                    update.setString(2,
                            JSON.writeValueAsString(tail(history, MAX_ADJUSTMENTS).stream().map(PostgresTeamBudgetStore::toMap).toList()));
                    update.setString(3, groupId);
                    update.setString(4, month);
                    update.executeUpdate();
                }
                connection.commit();
                return AdjustOutcome.APPLIED;
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not set the team budget", e);
        }
    }

    /**
     * Creates the month's row if absent and locks it ({@code SELECT ... FOR
     * UPDATE}) for the rest of the caller's transaction.
     */
    private static TeamBudgetMonth lockMonth(Connection connection, String groupId, String month, Instant expiresAt) throws SQLException {
        try (PreparedStatement ensure = connection
                .prepareStatement("INSERT INTO team_budget_months (group_id, month, expires_at_ms) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ensure.setString(1, groupId);
            ensure.setString(2, month);
            ensure.setLong(3, expiresAt.toEpochMilli());
            ensure.executeUpdate();
        }
        try (PreparedStatement lock = connection
                .prepareStatement("SELECT " + COLUMNS + " FROM team_budget_months WHERE group_id = ? AND month = ? FOR UPDATE")) {
            lock.setString(1, groupId);
            lock.setString(2, month);
            try (ResultSet rows = lock.executeQuery()) {
                rows.next();
                return toMonth(rows);
            }
        }
    }

    @Override
    public int deleteExpired(Instant now) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM team_budget_months WHERE expires_at_ms < ?")) {
            statement.setLong(1, now.toEpochMilli());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not delete expired team budgets", e);
        }
    }

    private static <T> List<T> tail(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(list.size() - max, list.size());
    }

    private static Map<String, Object> toMap(TeamBudgetMonth.Adjustment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", a.at() == null ? null : a.at().toEpochMilli());
        m.put("actor", a.actor());
        m.put("kind", a.kind());
        m.put("deltaUsd", a.deltaUsd());
        m.put("capAfterUsd", a.capAfterUsd());
        m.put("reason", a.reason());
        m.put("counterparty", a.counterparty());
        m.put("transferId", a.transferId());
        return m;
    }

    private static TeamBudgetMonth toMonth(ResultSet rows) throws SQLException {
        try {
            List<String> transfers = JSON.readValue(rows.getString(5), new TypeReference<List<String>>() {
            });
            List<Map<String, Object>> raw = JSON.readValue(rows.getString(6), new TypeReference<List<Map<String, Object>>>() {
            });
            List<TeamBudgetMonth.Adjustment> history = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object at = m.get("at");
                history.add(
                        new TeamBudgetMonth.Adjustment(at instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null, (String) m.get("actor"),
                                (String) m.get("kind"), num(m.get("deltaUsd")), num(m.get("capAfterUsd")), (String) m.get("reason"),
                                (String) m.get("counterparty"), (String) m.get("transferId")));
            }
            return new TeamBudgetMonth(rows.getString(1), rows.getString(2), rows.getDouble(3), rows.getDouble(4), transfers, history);
        } catch (JsonProcessingException e) {
            throw new SQLException("Unreadable team budget row", e);
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
