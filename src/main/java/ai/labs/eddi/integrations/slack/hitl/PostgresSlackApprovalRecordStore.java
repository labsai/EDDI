/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link ISlackApprovalRecordStore}.
 * <p>
 * {@link #tryRecord} is one {@code INSERT … ON CONFLICT DO UPDATE … WHERE}
 * statement: it inserts, or replaces a row that has expired, and changes zero
 * rows when a live one exists. Expired rows are swept on each write, which
 * keeps the table bounded without a scheduler.
 */
@ApplicationScoped
@DefaultBean
public class PostgresSlackApprovalRecordStore implements ISlackApprovalRecordStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresSlackApprovalRecordStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS slack_hitl_approval_records (
                integration_name VARCHAR(255) NOT NULL,
                subject VARCHAR(512) NOT NULL,
                pause_epoch VARCHAR(32) NOT NULL,
                approval_channel_id VARCHAR(255),
                created_at TIMESTAMP NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                PRIMARY KEY (integration_name, subject, pause_epoch)
            )
            """;

    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_slack_approval_expires ON slack_hitl_approval_records (expires_at)";

    /**
     * Resolved lazily: on a MongoDB deployment the datasource bean is inactive, and
     * resolving it at construction aborts the boot. See
     * {@code PostgresOAuthStateStore}.
     */
    private final Instance<DataSource> dataSourceInstance;
    private final Duration retention;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresSlackApprovalRecordStore(Instance<DataSource> dataSourceInstance,
            @ConfigProperty(name = "eddi.slack.hitl.approval-record-retention", defaultValue = "30d") Duration retention) {
        this.dataSourceInstance = dataSourceInstance;
        this.retention = SlackApprovalRecordRetention.sanitize(retention);
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the slack_hitl_approval_records schema");
        }
    }

    @Override
    public boolean tryRecord(String integrationName, String subject, String pauseEpoch, String approvalChannelId) {
        createSchema();
        Instant now = Instant.now();
        sweepExpired(now);
        String sql = """
                INSERT INTO slack_hitl_approval_records AS r
                    (integration_name, subject, pause_epoch, approval_channel_id, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (integration_name, subject, pause_epoch) DO UPDATE
                    SET approval_channel_id = EXCLUDED.approval_channel_id,
                        created_at = EXCLUDED.created_at,
                        expires_at = EXCLUDED.expires_at
                    WHERE r.expires_at <= ?
                """;
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationName);
            statement.setString(2, subject);
            statement.setString(3, pauseEpoch);
            statement.setString(4, approvalChannelId);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now.plus(retention)));
            statement.setTimestamp(7, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a Slack HITL approval card", e);
        }
    }

    @Override
    public List<SlackApprovalRecord> findBySubject(String integrationName, String subject) {
        createSchema();
        String sql = """
                SELECT integration_name, subject, pause_epoch, approval_channel_id, created_at, expires_at
                  FROM slack_hitl_approval_records
                 WHERE integration_name = ? AND subject = ? AND expires_at > ?
                """;
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationName);
            statement.setString(2, subject);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            List<SlackApprovalRecord> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SlackApprovalRecord(
                            rows.getString("integration_name"),
                            rows.getString("subject"),
                            rows.getString("pause_epoch"),
                            rows.getString("approval_channel_id"),
                            toInstant(rows.getTimestamp("created_at")),
                            toInstant(rows.getTimestamp("expires_at"))));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read Slack HITL approval records", e);
        }
    }

    @Override
    public void delete(String integrationName, String subject, String pauseEpoch) {
        createSchema();
        String sql = "DELETE FROM slack_hitl_approval_records WHERE integration_name = ? AND subject = ? AND pause_epoch = ?";
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationName);
            statement.setString(2, subject);
            statement.setString(3, pauseEpoch);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete a Slack HITL approval record", e);
        }
    }

    private void sweepExpired(Instant now) {
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM slack_hitl_approval_records WHERE expires_at <= ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            // Housekeeping only — reads ignore expired rows anyway.
            LOGGER.debugf(e, "Failed to sweep expired Slack HITL approval records");
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
