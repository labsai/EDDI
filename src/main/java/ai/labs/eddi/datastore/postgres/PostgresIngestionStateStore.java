/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
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
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IIngestionStateStore}.
 *
 * <p>
 * The partial unique index on {@code (source_id) WHERE status = 'RUNNING'} is
 * what makes {@link #startRun} mutually exclusive across instances: a second
 * caller's INSERT violates it and is told a run is already in flight, rather
 * than two crawls racing into one knowledge base.
 */
@ApplicationScoped
public class PostgresIngestionStateStore implements IIngestionStateStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresIngestionStateStore.class);

    private static final String CREATE_DOCUMENTS_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingestion_documents (
                source_id VARCHAR(255) NOT NULL,
                document_id TEXT NOT NULL,
                content_hash VARCHAR(64),
                etag VARCHAR(255),
                last_modified VARCHAR(255),
                first_ingested_at TIMESTAMP,
                last_ingested_at TIMESTAMP,
                last_run_id VARCHAR(64),
                missed_runs INTEGER NOT NULL DEFAULT 0,
                tombstoned BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (source_id, document_id)
            )
            """;

    private static final String CREATE_RUNS_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingestion_runs (
                run_id VARCHAR(64) PRIMARY KEY,
                source_id VARCHAR(255) NOT NULL,
                status VARCHAR(16) NOT NULL,
                started_at TIMESTAMP NOT NULL,
                finished_at TIMESTAMP,
                documents_seen INTEGER NOT NULL DEFAULT 0,
                documents_ingested INTEGER NOT NULL DEFAULT 0,
                documents_unchanged INTEGER NOT NULL DEFAULT 0,
                documents_failed INTEGER NOT NULL DEFAULT 0,
                documents_tombstoned INTEGER NOT NULL DEFAULT 0,
                segments_stored INTEGER NOT NULL DEFAULT 0,
                cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                error TEXT
            )
            """;

    private static final String CREATE_RUNS_SOURCE_INDEX = "CREATE INDEX IF NOT EXISTS idx_ingestion_runs_source ON rag_ingestion_runs (source_id, started_at DESC)";

    /** One in-flight run per source — see the class comment. */
    private static final String CREATE_ACTIVE_RUN_INDEX = "CREATE UNIQUE INDEX IF NOT EXISTS idx_ingestion_runs_active ON rag_ingestion_runs (source_id) "
            + "WHERE status = 'RUNNING'";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresIngestionStateStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    /**
     * Resolved lazily, never at construction: on a MongoDB deployment the
     * datasource bean is INACTIVE and resolving it during startup would abort the
     * boot of a deployment that never touches Postgres.
     */
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
            statement.execute(CREATE_DOCUMENTS_TABLE);
            statement.execute(CREATE_RUNS_TABLE);
            statement.execute(CREATE_RUNS_SOURCE_INDEX);
            statement.execute(CREATE_ACTIVE_RUN_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the rag_ingestion schema");
        }
    }

    @Override
    public Optional<DocumentState> lookup(String sourceId, String documentId) {
        if (sourceId == null || documentId == null) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM rag_ingestion_documents WHERE source_id = ? AND document_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            statement.setString(2, documentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toDocumentState(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to look up ingestion state");
            return Optional.empty();
        }
    }

    @Override
    public void recordIngested(String sourceId, String documentId, String contentHash,
                               String etag, String lastModified, String runId) {

        // first_ingested_at is only written on insert, so re-ingesting a changed
        // page keeps the date it first entered the knowledge base.
        String sql = """
                INSERT INTO rag_ingestion_documents
                    (source_id, document_id, content_hash, etag, last_modified,
                     first_ingested_at, last_ingested_at, last_run_id, missed_runs, tombstoned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, FALSE)
                ON CONFLICT (source_id, document_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    etag = EXCLUDED.etag,
                    last_modified = EXCLUDED.last_modified,
                    last_ingested_at = EXCLUDED.last_ingested_at,
                    last_run_id = EXCLUDED.last_run_id,
                    missed_runs = 0,
                    tombstoned = FALSE
                """;
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            statement.setString(2, documentId);
            statement.setString(3, contentHash);
            statement.setString(4, etag);
            statement.setString(5, lastModified);
            statement.setTimestamp(6, now);
            statement.setTimestamp(7, now);
            statement.setString(8, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to record an ingested document");
        }
    }

    @Override
    public void recordSeen(String sourceId, String documentId, String runId) {
        String sql = """
                UPDATE rag_ingestion_documents
                   SET last_run_id = ?, missed_runs = 0, tombstoned = FALSE
                 WHERE source_id = ? AND document_id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sourceId);
            statement.setString(3, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to record a seen document");
        }
    }

    @Override
    public List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);
        List<DocumentState> tombstoned = new ArrayList<>();

        String bump = """
                UPDATE rag_ingestion_documents
                   SET missed_runs = missed_runs + 1
                 WHERE source_id = ? AND tombstoned = FALSE
                   AND (last_run_id IS DISTINCT FROM ?)
                """;
        // RETURNING makes the select-and-mark one statement, so two runs finishing
        // together cannot both report the same document as newly tombstoned.
        String tombstone = """
                UPDATE rag_ingestion_documents
                   SET tombstoned = TRUE
                 WHERE source_id = ? AND tombstoned = FALSE AND missed_runs >= ?
                RETURNING *
                """;

        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(bump)) {
                statement.setString(1, sourceId);
                statement.setString(2, runId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(tombstone)) {
                statement.setString(1, sourceId);
                statement.setInt(2, threshold);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        tombstoned.add(toDocumentState(resultSet));
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to tombstone missing documents");
        }
        return tombstoned;
    }

    @Override
    public List<DocumentState> listDocuments(String sourceId, int limit) {
        List<DocumentState> states = new ArrayList<>();
        String sql = "SELECT * FROM rag_ingestion_documents WHERE source_id = ? LIMIT ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    states.add(toDocumentState(resultSet));
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to list ingestion documents");
        }
        return states;
    }

    @Override
    public void purgeSource(String sourceId) {
        try (Connection connection = connection()) {
            for (String sql : new String[]{
                    "DELETE FROM rag_ingestion_documents WHERE source_id = ?",
                    "DELETE FROM rag_ingestion_runs WHERE source_id = ?"}) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, sourceId);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to purge an ingestion source");
        }
    }

    @Override
    public Optional<String> startRun(String sourceId) {
        String runId = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO rag_ingestion_runs (run_id, source_id, status, started_at)
                VALUES (?, ?, 'RUNNING', ?)
                ON CONFLICT DO NOTHING
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sourceId);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            // Zero rows means the partial unique index rejected it: a run is already
            // in flight for this source. Losing that race is expected, not an error.
            return statement.executeUpdate() == 1 ? Optional.of(runId) : Optional.empty();
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to start an ingestion run");
            return Optional.empty();
        }
    }

    @Override
    public void finishRun(IngestionRun run) {
        String sql = """
                UPDATE rag_ingestion_runs
                   SET status = ?, finished_at = ?, documents_seen = ?, documents_ingested = ?,
                       documents_unchanged = ?, documents_failed = ?, documents_tombstoned = ?,
                       segments_stored = ?, cost_usd = ?, error = ?
                 WHERE run_id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, run.status().name());
            statement.setTimestamp(2, Timestamp.from(run.finishedAt() == null ? Instant.now() : run.finishedAt()));
            statement.setInt(3, run.documentsSeen());
            statement.setInt(4, run.documentsIngested());
            statement.setInt(5, run.documentsUnchanged());
            statement.setInt(6, run.documentsFailed());
            statement.setInt(7, run.documentsTombstoned());
            statement.setInt(8, run.segmentsStored());
            statement.setDouble(9, run.costUsd());
            statement.setString(10, run.error());
            statement.setString(11, run.runId());
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to finish an ingestion run");
        }
    }

    @Override
    public Optional<IngestionRun> activeRun(String sourceId) {
        String sql = "SELECT * FROM rag_ingestion_runs WHERE source_id = ? AND status = 'RUNNING'";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRun(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to read the active ingestion run");
            return Optional.empty();
        }
    }

    @Override
    public List<IngestionRun> listRuns(String sourceId, int limit) {
        List<IngestionRun> history = new ArrayList<>();
        String sql = "SELECT * FROM rag_ingestion_runs WHERE source_id = ? ORDER BY started_at DESC LIMIT ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    history.add(toRun(resultSet));
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to list ingestion runs");
        }
        return history;
    }

    @Override
    public int reapStaleRuns(Instant startedBefore) {
        String sql = """
                UPDATE rag_ingestion_runs
                   SET status = 'FAILED', finished_at = ?,
                       error = 'Run abandoned — no completion recorded before the stale threshold'
                 WHERE status = 'RUNNING' AND started_at < ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setTimestamp(2, Timestamp.from(startedBefore));
            return statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to reap stale ingestion runs");
            return 0;
        }
    }

    private static DocumentState toDocumentState(ResultSet resultSet) throws SQLException {
        return new DocumentState(
                resultSet.getString("source_id"),
                resultSet.getString("document_id"),
                resultSet.getString("content_hash"),
                resultSet.getString("etag"),
                resultSet.getString("last_modified"),
                toInstant(resultSet.getTimestamp("first_ingested_at")),
                toInstant(resultSet.getTimestamp("last_ingested_at")),
                resultSet.getString("last_run_id"),
                resultSet.getInt("missed_runs"),
                resultSet.getBoolean("tombstoned"));
    }

    private static IngestionRun toRun(ResultSet resultSet) throws SQLException {
        return new IngestionRun(
                resultSet.getString("run_id"),
                resultSet.getString("source_id"),
                IngestionRun.Status.valueOf(resultSet.getString("status")),
                toInstant(resultSet.getTimestamp("started_at")),
                toInstant(resultSet.getTimestamp("finished_at")),
                resultSet.getInt("documents_seen"),
                resultSet.getInt("documents_ingested"),
                resultSet.getInt("documents_unchanged"),
                resultSet.getInt("documents_failed"),
                resultSet.getInt("documents_tombstoned"),
                resultSet.getInt("segments_stored"),
                resultSet.getDouble("cost_usd"),
                resultSet.getString("error"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
