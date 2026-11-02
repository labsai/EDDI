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
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IEmailMailboxStateStore}: tables
 * {@code email_mailbox_state} (one row per integration) and
 * {@code email_mailbox_notices} (primary key {@code (integration_id,
 * notice_key)}, so a claim is an insert that does nothing on conflict), created
 * on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresEmailMailboxStateStore implements IEmailMailboxStateStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresEmailMailboxStateStore.class);

    static final String TABLE = "email_mailbox_state";
    static final String NOTICES = "email_mailbox_notices";

    private static final String[] SCHEMA = {"""
            CREATE TABLE IF NOT EXISTS email_mailbox_state (
                integration_id VARCHAR(255) PRIMARY KEY,
                folder VARCHAR(255),
                uid_validity BIGINT NOT NULL DEFAULT 0,
                last_uid BIGINT NOT NULL DEFAULT 0,
                since TIMESTAMP,
                failures INTEGER NOT NULL DEFAULT 0,
                backoff_until TIMESTAMP,
                last_error VARCHAR(512),
                budget_day VARCHAR(10),
                spent_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                updated_at TIMESTAMP
            )
            """, """
            CREATE TABLE IF NOT EXISTS email_mailbox_notices (
                integration_id VARCHAR(255) NOT NULL,
                notice_key VARCHAR(255) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                PRIMARY KEY (integration_id, notice_key)
            )
            """};

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresEmailMailboxStateStore(Instance<DataSource> dataSourceInstance) {
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
    public Optional<MailboxState> read(String integrationId) {
        String sql = "SELECT folder, uid_validity, last_uid, since, failures, backoff_until, last_error, budget_day, spent_usd, updated_at FROM "
                + TABLE + " WHERE integration_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MailboxState(integrationId, rows.getString("folder"), rows.getLong("uid_validity"), rows.getLong("last_uid"),
                        instant(rows.getTimestamp("since")), rows.getInt("failures"), instant(rows.getTimestamp("backoff_until")),
                        rows.getString("last_error"), rows.getString("budget_day"), rows.getDouble("spent_usd"),
                        instant(rows.getTimestamp("updated_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the mailbox state", e);
        }
    }

    @Override
    public void saveCursor(String integrationId, String folder, long uidValidity, long lastUid, Instant since, Instant now) {
        String sql = "INSERT INTO " + TABLE + " (integration_id, folder, uid_validity, last_uid, since, failures, backoff_until, last_error, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, 0, NULL, NULL, ?) ON CONFLICT (integration_id) DO UPDATE SET folder = EXCLUDED.folder, "
                + "uid_validity = EXCLUDED.uid_validity, last_uid = EXCLUDED.last_uid, since = EXCLUDED.since, failures = 0, "
                + "backoff_until = NULL, last_error = NULL, updated_at = EXCLUDED.updated_at";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, folder);
            statement.setLong(3, uidValidity);
            statement.setLong(4, lastUid);
            statement.setTimestamp(5, timestamp(since));
            statement.setTimestamp(6, timestamp(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write the mailbox cursor", e);
        }
    }

    @Override
    public void recordFailure(String integrationId, int failures, Instant backoffUntil, String lastError, Instant now) {
        String sql = "INSERT INTO " + TABLE + " (integration_id, failures, backoff_until, last_error, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (integration_id) DO UPDATE SET failures = EXCLUDED.failures, backoff_until = EXCLUDED.backoff_until, "
                + "last_error = EXCLUDED.last_error, updated_at = EXCLUDED.updated_at";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setInt(2, failures);
            statement.setTimestamp(3, timestamp(backoffUntil));
            statement.setString(4, lastError);
            statement.setTimestamp(5, timestamp(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a mailbox poll failure", e);
        }
    }

    @Override
    public double addSpend(String integrationId, String day, double amountUsd, Instant now) {
        String sql = "INSERT INTO " + TABLE + " AS s (integration_id, budget_day, spent_usd, updated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (integration_id) DO UPDATE SET spent_usd = CASE WHEN s.budget_day = EXCLUDED.budget_day "
                + "THEN s.spent_usd + EXCLUDED.spent_usd ELSE EXCLUDED.spent_usd END, budget_day = EXCLUDED.budget_day, "
                + "updated_at = EXCLUDED.updated_at RETURNING spent_usd";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, day);
            statement.setDouble(3, amountUsd);
            statement.setTimestamp(4, timestamp(now));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getDouble(1) : amountUsd;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record mailbox spend", e);
        }
    }

    @Override
    public boolean claimNotice(String integrationId, String noticeKey, Instant now) {
        String sql = "INSERT INTO " + NOTICES + " (integration_id, notice_key, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, noticeKey);
            statement.setTimestamp(3, timestamp(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim a mailbox notice", e);
        }
    }

    @Override
    public void delete(String integrationId) {
        try (Connection connection = connection();
                PreparedStatement state = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE integration_id = ?");
                PreparedStatement notice = connection.prepareStatement("DELETE FROM " + NOTICES + " WHERE integration_id = ?")) {
            state.setString(1, integrationId);
            state.executeUpdate();
            notice.setString(1, integrationId);
            notice.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete the mailbox state", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
