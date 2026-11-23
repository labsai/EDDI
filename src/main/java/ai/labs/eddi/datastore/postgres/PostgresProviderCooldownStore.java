/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IProviderCooldownStore}: one row per
 * provider quota in {@code provider_cooldowns}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresProviderCooldownStore implements IProviderCooldownStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS provider_cooldowns (
                throttle_key TEXT PRIMARY KEY,
                until TIMESTAMP NOT NULL,
                daily BOOLEAN NOT NULL DEFAULT FALSE,
                observed_at TIMESTAMP NOT NULL
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresProviderCooldownStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /** Resolved lazily, so a MongoDB deployment never touches the datasource. */
    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create the provider_cooldowns table", e);
        }
    }

    @Override
    public Optional<Cooldown> read(String throttleKey) {
        String sql = "SELECT until, daily FROM provider_cooldowns WHERE throttle_key = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, throttleKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cooldown(throttleKey, resultSet.getTimestamp(1).toInstant(),
                        resultSet.getBoolean(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a provider cooldown", e);
        }
    }

    @Override
    public void extend(String throttleKey, Instant until, boolean daily) {
        // Only ever later: the right-hand sides read the row as it was, so a refusal
        // naming an earlier time keeps the recorded one, and its kind with it.
        String sql = """
                INSERT INTO provider_cooldowns (throttle_key, until, daily, observed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (throttle_key) DO UPDATE SET
                    daily = CASE WHEN EXCLUDED.until > provider_cooldowns.until
                                 THEN EXCLUDED.daily ELSE provider_cooldowns.daily END,
                    until = GREATEST(provider_cooldowns.until, EXCLUDED.until),
                    observed_at = EXCLUDED.observed_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, throttleKey);
            statement.setTimestamp(2, Timestamp.from(until));
            statement.setBoolean(3, daily);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a provider cooldown", e);
        }
    }
}
