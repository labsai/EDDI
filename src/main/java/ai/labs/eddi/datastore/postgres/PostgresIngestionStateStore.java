/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreException;
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
 *
 * <p>
 * PostgreSQL could fence document writes by subquerying the run table in the
 * same statement. It deliberately does not: MongoDB has no equivalent, and a
 * PostgreSQL-only fence would re-introduce exactly the backend divergence the
 * shared contract exists to catch. Ownership is denormalized onto the document
 * row instead, identically on both backends — see
 * {@link ai.labs.eddi.modules.ingestion.IIngestionStateStore} for the rules and
 * the MongoDB store for why the choice was forced there.
 */
@ApplicationScoped
@DefaultBean
public class PostgresIngestionStateStore implements IIngestionStateStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresIngestionStateStore.class);

    private static final String CREATE_DOCUMENTS_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingestion_documents (
                source_id TEXT NOT NULL,
                document_id TEXT NOT NULL,
                content_hash TEXT,
                etag TEXT,
                last_modified TEXT,
                first_ingested_at TIMESTAMP,
                last_ingested_at TIMESTAMP,
                last_run_id VARCHAR(64),
                missed_runs INTEGER NOT NULL DEFAULT 0,
                tombstoned BOOLEAN NOT NULL DEFAULT FALSE,
                fencing_run_id VARCHAR(64),
                fencing_generation BIGINT,
                PRIMARY KEY (source_id, document_id)
            )
            """;

    private static final String CREATE_RUNS_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingestion_runs (
                run_id VARCHAR(64) PRIMARY KEY,
                source_id TEXT NOT NULL,
                status VARCHAR(16) NOT NULL,
                generation BIGINT NOT NULL DEFAULT 0,
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

    /**
     * The fencing columns, added separately so a database created by an earlier
     * build of this schema gains them. {@code CREATE TABLE IF NOT EXISTS} is a
     * no-op against an existing table and would otherwise leave the fence silently
     * un-enforceable.
     */
    private static final String[] ADD_FENCING_COLUMNS = {
            "ALTER TABLE rag_ingestion_documents ADD COLUMN IF NOT EXISTS fencing_run_id VARCHAR(64)",
            "ALTER TABLE rag_ingestion_documents ADD COLUMN IF NOT EXISTS fencing_generation BIGINT",
            "ALTER TABLE rag_ingestion_runs ADD COLUMN IF NOT EXISTS generation BIGINT NOT NULL DEFAULT 0"};

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
            for (String alter : ADD_FENCING_COLUMNS) {
                statement.execute(alter);
            }
            statement.execute(CREATE_RUNS_SOURCE_INDEX);
            statement.execute(CREATE_ACTIVE_RUN_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            // Thrown, not only logged: carrying on would fail every later call on a
            // missing table, with an error that no longer says why.
            throw new IngestionStateStoreException("Failed to create the rag_ingestion schema", e);
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
            // Never swallowed: an empty answer here means "never ingested", so the
            // page is re-embedded and re-billed on every run while the database is
            // unwell, and the operator sees a healthy-looking run.
            throw new IngestionStateStoreException("Failed to look up ingestion state", e);
        }
    }

    @Override
    public void recordIngested(String sourceId, String documentId, String contentHash,
                               String etag, String lastModified, String runId) {

        // first_ingested_at is only written on insert, so re-ingesting a changed
        // page keeps the date it first entered the knowledge base.
        //
        // The WHERE on DO UPDATE is the fence, applied by the same statement as the
        // write: a row owned by another run is left exactly as it is. Zero affected
        // rows can mean nothing else here — an insert affects one row, and a
        // conflict without the fence affects one too.
        String sql = """
                INSERT INTO rag_ingestion_documents
                    (source_id, document_id, content_hash, etag, last_modified,
                     first_ingested_at, last_ingested_at, last_run_id, missed_runs, tombstoned,
                     fencing_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, FALSE, ?)
                ON CONFLICT (source_id, document_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    etag = EXCLUDED.etag,
                    last_modified = EXCLUDED.last_modified,
                    last_ingested_at = EXCLUDED.last_ingested_at,
                    last_run_id = EXCLUDED.last_run_id,
                    missed_runs = 0,
                    tombstoned = FALSE
                WHERE rag_ingestion_documents.fencing_run_id = EXCLUDED.fencing_run_id
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
            statement.setString(9, runId);
            if (statement.executeUpdate() == 0) {
                fenced("record an ingested document", sourceId, documentId, runId);
            }
        } catch (SQLException e) {
            // Losing this write means the document is embedded again next run, and
            // the run after that, for as long as the failure lasts.
            throw new IngestionStateStoreException("Failed to record an ingested document", e);
        }
    }

    @Override
    public void recordSeen(String sourceId, String documentId, String runId) {
        String sql = """
                UPDATE rag_ingestion_documents
                   SET last_run_id = ?, missed_runs = 0, tombstoned = FALSE
                 WHERE source_id = ? AND document_id = ? AND fencing_run_id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sourceId);
            statement.setString(3, documentId);
            statement.setString(4, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IngestionStateStoreException("Failed to record a seen document", e);
        }
    }

    @Override
    public void recordUnreachable(String sourceId, String documentId, String runId) {
        // Only the run marker — see the interface. The miss counter and the
        // tombstone flag stay as they are.
        String sql = """
                UPDATE rag_ingestion_documents
                   SET last_run_id = ?
                 WHERE source_id = ? AND document_id = ? AND fencing_run_id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, sourceId);
            statement.setString(3, documentId);
            statement.setString(4, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IngestionStateStoreException("Failed to record an unreachable document", e);
        }
    }

    @Override
    public List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);
        List<DocumentState> tombstoned = new ArrayList<>();

        // Both statements are fenced on fencing_run_id: a superseded run raises
        // nobody's miss counter and tombstones nobody, so it deletes no vectors.
        String bump = """
                UPDATE rag_ingestion_documents
                   SET missed_runs = missed_runs + 1
                 WHERE source_id = ? AND tombstoned = FALSE AND fencing_run_id = ?
                   AND (last_run_id IS DISTINCT FROM ?)
                """;
        // RETURNING makes the select-and-mark one statement, so two runs finishing
        // together cannot both report the same document as newly tombstoned.
        String tombstone = """
                UPDATE rag_ingestion_documents
                   SET tombstoned = TRUE
                 WHERE source_id = ? AND tombstoned = FALSE AND fencing_run_id = ?
                   AND missed_runs >= ?
                RETURNING *
                """;

        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(bump)) {
                statement.setString(1, sourceId);
                statement.setString(2, runId);
                statement.setString(3, runId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(tombstone)) {
                statement.setString(1, sourceId);
                statement.setString(2, runId);
                statement.setInt(3, threshold);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        tombstoned.add(toDocumentState(resultSet));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IngestionStateStoreException("Failed to tombstone missing documents", e);
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
            throw new IngestionStateStoreException("Failed to list ingestion documents", e);
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
            throw new IngestionStateStoreException("Failed to purge an ingestion source", e);
        }
    }

    @Override
    public Optional<String> startRun(String sourceId) {
        String runId = UUID.randomUUID().toString();
        // The generation is derived in the statement that claims the run. Two
        // claimants racing under READ COMMITTED can still read the same MAX and
        // compute the same number, which is harmless: only one of them survives the
        // partial unique index, so only one of them ever stamps anything.
        String sql = """
                INSERT INTO rag_ingestion_runs (run_id, source_id, status, generation, started_at)
                SELECT ?, ?, 'RUNNING', COALESCE(MAX(generation), 0) + 1, ?
                  FROM rag_ingestion_runs WHERE source_id = ?
                ON CONFLICT DO NOTHING
                RETURNING generation
                """;
        try (Connection connection = connection()) {
            long generation;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                statement.setString(2, sourceId);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.setString(4, sourceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    // No row means the partial unique index rejected it: a run is
                    // already in flight for this source. Losing that race is expected,
                    // not an error.
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    generation = resultSet.getLong(1);
                }
            }
            takeOwnership(connection, sourceId, runId, generation);
            return Optional.of(runId);
        } catch (SQLException e) {
            // An empty Optional means "a run is already in flight", which would be a
            // lie here and would show the operator a 409 for a database fault.
            throw new IngestionStateStoreException("Failed to start an ingestion run", e);
        }
    }

    @Override
    public void finishRun(IngestionRun run) {
        String sql = """
                UPDATE rag_ingestion_runs
                   SET status = ?, finished_at = ?, documents_seen = ?, documents_ingested = ?,
                       documents_unchanged = ?, documents_failed = ?, documents_tombstoned = ?,
                       segments_stored = ?, cost_usd = ?, error = ?
                 WHERE run_id = ? AND status = 'RUNNING'
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
            if (statement.executeUpdate() == 0) {
                // Reaped while it was still working: the reaper already declared it
                // dead and another run may have started since, so this result is
                // discarded rather than overwriting the record — but not silently.
                LOGGER.warnf("Ingestion run %s was already closed (reaped) before it finished; "
                        + "its result is discarded", LogSanitizer.sanitize(run.runId()));
            }
        } catch (SQLException e) {
            // Losing this leaves the run RUNNING, which blocks the source until it
            // is reaped.
            throw new IngestionStateStoreException("Failed to finish an ingestion run", e);
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
            throw new IngestionStateStoreException("Failed to read the active ingestion run", e);
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
            throw new IngestionStateStoreException("Failed to list ingestion runs", e);
        }
        return history;
    }

    @Override
    public int reapStaleRuns(String sourceId, Instant startedBefore) {
        String sql = """
                UPDATE rag_ingestion_runs
                   SET status = 'FAILED', finished_at = ?,
                       error = 'Run abandoned — no completion recorded before the stale threshold'
                 WHERE source_id = ? AND status = 'RUNNING' AND started_at < ?
                """;
        // Ownership goes to nobody, so the worker just declared dead is fenced from
        // this moment rather than only once a replacement run claims the source.
        // NULL never equals a runId, so every later write of its own matches
        // nothing.
        String release = "UPDATE rag_ingestion_documents SET fencing_run_id = NULL WHERE source_id = ?";
        try (Connection connection = connection()) {
            int reaped;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.setString(2, sourceId);
                statement.setTimestamp(3, Timestamp.from(startedBefore));
                reaped = statement.executeUpdate();
            }
            if (reaped > 0) {
                try (PreparedStatement statement = connection.prepareStatement(release)) {
                    statement.setString(1, sourceId);
                    statement.executeUpdate();
                }
            }
            return reaped;
        } catch (SQLException e) {
            throw new IngestionStateStoreException("Failed to reap stale ingestion runs", e);
        }
    }

    /**
     * Hands the source's existing document rows to the run that has just claimed
     * it, so every later write can be fenced on the {@code runId} the caller
     * already holds.
     *
     * <p>
     * The generation guard is what keeps the stamps ordered. Without it a stamp
     * held up long enough for its own run to be reaped could land after the
     * replacement run's and take the source back, silencing the run that is
     * actually working.
     */
    private static void takeOwnership(Connection connection, String sourceId, String runId, long generation)
            throws SQLException {

        String sql = """
                UPDATE rag_ingestion_documents
                   SET fencing_run_id = ?, fencing_generation = ?
                 WHERE source_id = ? AND (fencing_generation IS NULL OR fencing_generation < ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setLong(2, generation);
            statement.setString(3, sourceId);
            statement.setLong(4, generation);
            statement.executeUpdate();
        }
    }

    private static void fenced(String what, String sourceId, String documentId, String runId) {
        // Debug, not warn: the reaper has already decided this run is dead and
        // finishRun says so once. A doomed crawl would otherwise log a line per
        // document about a result that is discarded anyway.
        LOGGER.debugf("Ignored an attempt to %s for source '%s', document '%s': run %s no longer owns the source",
                what, LogSanitizer.sanitize(sourceId), LogSanitizer.sanitize(documentId),
                LogSanitizer.sanitize(runId));
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
