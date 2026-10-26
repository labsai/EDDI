/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IEmailSuppressionStore}: table
 * {@code email_suppressions} with primary key {@code (integration_id,
 * address_hash)}, so suppressing is an insert that does nothing on conflict.
 * Created on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresEmailSuppressionStore implements IEmailSuppressionStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresEmailSuppressionStore.class);

    static final String TABLE = "email_suppressions";

    private static final String[] SCHEMA = {"""
            CREATE TABLE IF NOT EXISTS email_suppressions (
                integration_id VARCHAR(255) NOT NULL,
                address_hash VARCHAR(80) NOT NULL,
                domain VARCHAR(255),
                reason VARCHAR(255),
                created_at TIMESTAMP NOT NULL,
                PRIMARY KEY (integration_id, address_hash)
            )
            """, "CREATE INDEX IF NOT EXISTS idx_email_suppressions_hash ON email_suppressions (address_hash)"};

    /** Resolved lazily, as in {@link PostgresEmailMessageLedger}. */
    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresEmailSuppressionStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private Connection connection() throws SQLException {
        createSchema();
        return dataSourceInstance.get().getConnection();
    }

    private synchronized void createSchema() throws SQLException {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA) {
                statement.execute(sql);
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the %s schema", TABLE);
            throw e;
        }
    }

    @Override
    public boolean suppress(String integrationId, String normalizedAddress, String reason, Instant now) {
        String sql = "INSERT INTO " + TABLE + " (integration_id, address_hash, domain, reason, created_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, IEmailSuppressionStore.hash(normalizedAddress));
            statement.setString(3, IEmailSuppressionStore.domainOf(normalizedAddress));
            statement.setString(4, reason == null ? null : reason.length() > 255 ? reason.substring(0, 255) : reason);
            statement.setTimestamp(5, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write an email suppression", e);
        }
    }

    @Override
    public boolean isSuppressed(String integrationId, String normalizedAddress) {
        String sql = "SELECT 1 FROM " + TABLE + " WHERE integration_id = ? AND address_hash = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, IEmailSuppressionStore.hash(normalizedAddress));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the email suppressions", e);
        }
    }

    @Override
    public List<Suppression> list(String integrationId, int limit) {
        String sql = "SELECT integration_id, address_hash, domain, reason, created_at FROM " + TABLE
                + " WHERE integration_id = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setInt(2, limit);
            return read(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the email suppressions", e);
        }
    }

    @Override
    public boolean remove(String integrationId, String normalizedAddress) {
        return update("DELETE FROM " + TABLE + " WHERE integration_id = ? AND address_hash = ?", integrationId,
                IEmailSuppressionStore.hash(normalizedAddress)) == 1;
    }

    @Override
    public long removeAll(String integrationId) {
        return update("DELETE FROM " + TABLE + " WHERE integration_id = ?", integrationId);
    }

    @Override
    public List<Suppression> findByAddress(String normalizedAddress) {
        String sql = "SELECT integration_id, address_hash, domain, reason, created_at FROM " + TABLE + " WHERE address_hash = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, IEmailSuppressionStore.hash(normalizedAddress));
            return read(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the email suppressions", e);
        }
    }

    @Override
    public long deleteByAddress(String normalizedAddress) {
        return update("DELETE FROM " + TABLE + " WHERE address_hash = ?", IEmailSuppressionStore.hash(normalizedAddress));
    }

    private int update(String sql, String... parameters) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to change the email suppressions", e);
        }
    }

    private static List<Suppression> read(PreparedStatement statement) throws SQLException {
        List<Suppression> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Timestamp created = rows.getTimestamp("created_at");
                result.add(new Suppression(rows.getString("integration_id"), rows.getString("address_hash"), rows.getString("domain"),
                        rows.getString("reason"), created == null ? null : created.toInstant()));
            }
        }
        return result;
    }
}
