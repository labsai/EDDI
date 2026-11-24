/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

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
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL {@link IDocumentSpendStore}: one row per (kind, subject, day).
 * <p>
 * A charge is one {@code INSERT … ON CONFLICT DO UPDATE SET spent_usd =
 * spent_usd + EXCLUDED.spent_usd … RETURNING}: the conflict path takes the row
 * lock and adds to the committed value, so concurrent charges on any node add
 * up exactly. The schema is created on first use (the datasource bean is
 * inactive on a MongoDB deployment, so nothing touches it at construction).
 */
@ApplicationScoped
@DefaultBean
public class PostgresDocumentSpendStore implements IDocumentSpendStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresDocumentSpendStore.class);

    static final String TABLE = "document_spend_days";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS document_spend_days (
                subject_kind VARCHAR(16) NOT NULL,
                subject TEXT NOT NULL,
                day BIGINT NOT NULL,
                spent_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                calls BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (subject_kind, subject, day)
            )
            """;

    private static final String CREATE_DAY_INDEX = "CREATE INDEX IF NOT EXISTS idx_document_spend_day ON document_spend_days (day)";

    private static final String ADD = """
            INSERT INTO document_spend_days (subject_kind, subject, day, spent_usd, calls)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT (subject_kind, subject, day)
            DO UPDATE SET spent_usd = document_spend_days.spent_usd + EXCLUDED.spent_usd,
                          calls = document_spend_days.calls + 1
            RETURNING spent_usd, calls
            """;

    private static final String DELETE_SUBJECT_EXPIRED = "DELETE FROM document_spend_days WHERE subject_kind = ? AND subject = ? AND day < ?";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;
    private final AtomicLong nextSweepAtMillis = new AtomicLong();

    @Inject
    public PostgresDocumentSpendStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private void createSchema() {
        if (!schemaInitialized) {
            createSchemaLocked();
        }
    }

    private synchronized void createSchemaLocked() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_DAY_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            // schemaInitialized stays false, so the next call retries the DDL.
            throw new DocumentSpendStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    @Override
    public DaySpend add(SubjectKind kind, String subject, long epochDay, double usd, long oldestRetainedDay) {
        createSchema();
        double amount = Double.isFinite(usd) && usd > 0.0 ? usd : 0.0;
        DaySpend after;
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(ADD)) {
                statement.setString(1, kind.name());
                statement.setString(2, subject);
                statement.setLong(3, epochDay);
                statement.setDouble(4, amount);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    after = new DaySpend(kind, subject, epochDay, rows.getDouble(1), rows.getLong(2));
                }
            }
            deleteExpired(connection, kind, subject, oldestRetainedDay);
        } catch (SQLException e) {
            throw new DocumentSpendStoreException("Could not record document spend", e);
        }
        sweepIfDue(oldestRetainedDay);
        return after;
    }

    /**
     * The subject's expired buckets, after the increment has committed: a failure
     * is logged, never thrown, so a caller is not told the charge was lost.
     */
    private static void deleteExpired(Connection connection, SubjectKind kind, String subject, long oldestRetainedDay) {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SUBJECT_EXPIRED)) {
            statement.setString(1, kind.name());
            statement.setString(2, subject);
            statement.setLong(3, oldestRetainedDay);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.debugf("Could not delete expired document spend buckets; retried on a later write: %s", sanitize(e.getMessage()));
        }
    }

    private void sweepIfDue(long oldestRetainedDay) {
        long now = System.currentTimeMillis();
        long due = nextSweepAtMillis.get();
        if (now >= due && nextSweepAtMillis.compareAndSet(due, now + SWEEP_INTERVAL_MILLIS)) {
            try {
                deleteBefore(oldestRetainedDay);
            } catch (DocumentSpendStoreException e) {
                LOGGER.debugf("Document spend sweep failed; retried later: %s", sanitize(e.getMessage()));
            }
        }
    }

    @Override
    public double spent(SubjectKind kind, String subject, long epochDay) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT spent_usd FROM document_spend_days WHERE subject_kind = ? AND subject = ? AND day = ?")) {
            statement.setString(1, kind.name());
            statement.setString(2, subject);
            statement.setLong(3, epochDay);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getDouble(1) : 0.0;
            }
        } catch (SQLException e) {
            throw new DocumentSpendStoreException("Could not read document spend", e);
        }
    }

    @Override
    public long deleteBySubject(SubjectKind kind, String subject) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM document_spend_days WHERE subject_kind = ? AND subject = ?")) {
            statement.setString(1, kind.name());
            statement.setString(2, subject);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DocumentSpendStoreException("Could not delete document spend", e);
        }
    }

    @Override
    public List<DaySpend> findBySubject(SubjectKind kind, String subject, int max) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT day, spent_usd, calls FROM document_spend_days "
                        + "WHERE subject_kind = ? AND subject = ? ORDER BY day DESC LIMIT ?")) {
            statement.setString(1, kind.name());
            statement.setString(2, subject);
            statement.setInt(3, Math.max(0, max));
            List<DaySpend> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new DaySpend(kind, subject, rows.getLong(1), rows.getDouble(2), rows.getLong(3)));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new DocumentSpendStoreException("Could not read document spend", e);
        }
    }

    @Override
    public long deleteBefore(long oldestRetainedDay) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM document_spend_days WHERE day < ?")) {
            statement.setLong(1, oldestRetainedDay);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DocumentSpendStoreException("Could not delete expired document spend", e);
        }
    }
}
